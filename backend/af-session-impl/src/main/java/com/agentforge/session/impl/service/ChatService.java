package com.agentforge.session.impl.service;

import com.agentforge.agent.agent.service.AgentService;
import com.agentforge.common.audit.AuditService;
import com.agentforge.common.security.LoginUser;
import com.agentforge.common.skill.SkillStreamEvent;
import com.agentforge.common.tool.ToolStreamEvent;
import com.agentforge.session.api.dto.ChatRequest;
import com.agentforge.session.api.dto.ChatStreamEvent;
import com.agentforge.session.api.dto.ConversationCreateRequest;
import com.agentforge.session.api.dto.ConversationResponse;
import com.agentforge.session.impl.engine.AgentEngineClient;
import com.agentforge.session.impl.entity.Message;
import com.agentforge.session.impl.entity.MessageSkillCall;
import com.agentforge.session.impl.entity.MessageToolCall;
import com.agentforge.session.impl.mapper.ConversationMapper;
import com.agentforge.session.impl.mapper.MessageMapper;
import com.agentforge.session.impl.mapper.MessageSkillCallMapper;
import com.agentforge.session.impl.mapper.MessageToolCallMapper;
import com.agentforge.session.impl.usage.service.UsageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 聊天流式服务 —— P6 的灵魂
 *
 * 一条聊天请求的完整生命周期(全部满足铁律):
 *   1. 解析/建会话(同步段, 失败可被全局异常处理器捕获)
 *   2. 从 message 表装配上下文(铁律: 历史单一数据源, 不缓存一份额外副本)
 *   3. 落 user 消息(status=1 完成)
 *   4. 建 assistant 消息空壳(status=0 流式中)  ← 断线重连的恢复点
 *   5. 异步调引擎: 每收到 delta → 累积 + 中继 SSE content_delta
 *      - 节流落库: 调度器每 500ms 覆盖式写累积内容(铁律5: 任意时刻中断 DB 都有半成品)
 *      - 心跳: 每 15s 发 ping(防代理/Nginx 空闲断流)
 *   6. 完成: final flush + status=1, 发 message_done, complete
 *   7. 异常/超时: status=2 失败 / status=3 中断, 清理调度器
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ConversationService conversationService;
    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final MessageToolCallMapper messageToolCallMapper;
    private final MessageSkillCallMapper messageSkillCallMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final AgentEngineClient engineClient;
    private final ExecutorService streamExecutor;
    private final ScheduledExecutorService scheduler;
    private final UsageService usageService;          // P13: 配额预检 + 用量记账
    private final AuditService auditService;          // P13: 审计埋点
    private final ModelConfigService modelConfigService;  // 模型配置解析
    private final AgentService agentService;              // 智能体运行时配置

    /** SSE 超时: 10 分钟 */
    private static final long SSE_TIMEOUT_MS = 600_000L;
    /** 节流落库间隔 */
    private static final long FLUSH_MS = 500L;
    /** 心跳间隔(秒) */
    private static final long PING_S = 15L;

    public SseEmitter stream(ChatRequest req, LoginUser user) {
        Long userId = user.getUserId();
        Long tenantId = user.getTenantId();
        String traceId = MDC.get("traceId");
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        // 0. P13 配额预检(同步段): 当日 token 已超限 → 直接 403 拒绝, 不进流
        usageService.precheck(tenantId);
        long startNanos = System.nanoTime();

        // 1. 解析/建会话(同步段: 任何失败都被全局异常处理器捕获为 R 错误)
        String convId;
        ConversationResponse conversation;
        if (req.getConversationId() == null || req.getConversationId().isBlank()) {
            ConversationCreateRequest createReq = new ConversationCreateRequest();
            createReq.setAgentId(req.getAgentId());
            conversation = conversationService.create(userId, tenantId, createReq);
            convId = conversation.getId();
        } else {
            convId = req.getConversationId();
            conversation = conversationService.get(userId, tenantId, convId);   // 校验存在+归属, 抛错即中断
        }

        // 会话绑定的 Agent 优先于请求级 Agent；历史会话可在后续请求中稳定复用同一智能体。
        Long effectiveAgentId = conversation.getAgentId() != null
                ? conversation.getAgentId()
                : req.getAgentId();

        // 2. 装配上下文(最近 20 条历史, 全从 message 表来)
        String prompt = buildPrompt(convId, tenantId, req.getContent());

        // 2a. 如果指定了 Agent，读取已发布配置：系统提示词和 Agent 默认模型优先。
        Map<String, Object> agentRuntime = effectiveAgentId == null
                ? null : agentService.resolveRuntime(tenantId, effectiveAgentId);

        // 2b. 解析模型配置: 请求指定 modelConfigId > Agent 绑定模型 > 平台默认模型
        Long requestedModelId = req.getModelConfigId();
        if (requestedModelId == null && agentRuntime != null) {
            Object configured = agentRuntime.get("model_config_id");
            if (configured instanceof Number n) requestedModelId = n.longValue();
        }
        Map<String, Object> llmConfig = modelConfigService.resolveConfig(
                requestedModelId != null ? requestedModelId : modelConfigService.defaultConfigId(tenantId),
                tenantId);
        if (agentRuntime != null) {
            String systemPrompt = String.valueOf(agentRuntime.getOrDefault("system_prompt", ""));
            if (!systemPrompt.isBlank()) {
                log.debug("agent runtime system prompt loaded agentId={} length={}",
                        effectiveAgentId, systemPrompt.length());
            }
        }

        // 3. 落 user 消息
        int baseSeq = messageMapper.selectMaxSeq(convId, tenantId);
        Message userMsg = new Message();
        userMsg.setConversationId(convId);
        userMsg.setTenantId(tenantId);
        userMsg.setRole("user");
        userMsg.setSeq(baseSeq + 1);
        userMsg.setContentType("text");
        userMsg.setStatus(1);
        userMsg.setContent(req.getContent());
        messageMapper.insert(userMsg);
        conversationMapper.incrementMessageCount(convId);

        // 4. 建 assistant 消息空壳(status=0 流式中) —— 断线恢复点
        Message assistant = new Message();
        assistant.setConversationId(convId);
        assistant.setTenantId(tenantId);
        assistant.setRole("assistant");
        assistant.setSeq(baseSeq + 2);
        assistant.setContentType("text");
        assistant.setStatus(0);
        assistant.setContent("");
        messageMapper.insert(assistant);

        // 5. SSE 发射器 + 累积缓冲(同一会话并发写入靠同步块串行化)
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        StringBuilder buf = new StringBuilder();

        // 5a. 心跳
        ScheduledFuture<?> ping = scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().name(ChatStreamEvent.TYPE_PING).data("{}"));
            } catch (Exception ignored) { /* 连接已断, 超时/完成回调会清理 */ }
        }, PING_S, PING_S, TimeUnit.SECONDS);

        // 5b. 节流落库(铁律5): 每 500ms 覆盖式写累积内容
        ScheduledFuture<?> flush = scheduler.scheduleAtFixedRate(() -> {
            String cur;
            synchronized (buf) { cur = buf.toString(); }
            if (!cur.isEmpty()) {
                try { messageMapper.updateContent(assistant.getId(), cur); }
                catch (Exception ignored) {}
            }
        }, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS);

        // 6. 异步跑引擎循环(不阻塞 Tomcat 工作线程)
        final String streamConvId = convId;
        final String streamTraceId = traceId;
        final String streamPrompt = prompt;
        final Map<String, Object> streamLlmConfig = llmConfig;
        final Map<String, Object> streamAgentConfig = agentRuntime;
        streamExecutor.submit(() -> {
            try {
                // message_start: 通知前端"流式中消息已建"
                emitter.send(SseEmitter.event()
                        .name(ChatStreamEvent.TYPE_MESSAGE_START)
                        .id(String.valueOf(assistant.getSeq()))
                        .data(eventData("role", "assistant", "messageId", assistant.getId(),
                                "traceId", streamTraceId)));

                // 调引擎: 每段 delta → 累积 + 中继 SSE(content_delta)
                AgentEngineClient.StreamResult streamResult = engineClient.stream(
                        streamPrompt, streamConvId, streamTraceId, tenantId, streamLlmConfig, streamAgentConfig, delta -> {
                    synchronized (buf) { buf.append(delta); }
                    try {
                        emitter.send(SseEmitter.event()
                                .name(ChatStreamEvent.TYPE_CONTENT_DELTA)
                                .id(String.valueOf(assistant.getSeq()))
                                .data(eventData("delta", delta, "traceId", streamTraceId)));
                    } catch (IOException e) {
                        throw new RuntimeException("sse send failed", e);  // 连接断开, 中断流
                    }
                }, toolEvent -> handleToolEvent(
                        toolEvent, tenantId, assistant.getId(), assistant.getSeq(), emitter),
                skillEvent -> handleSkillEvent(
                        skillEvent, tenantId, assistant.getId(), assistant.getSeq(), emitter));

                // 完成: final flush + status=1
                String finalContent;
                synchronized (buf) { finalContent = buf.toString(); }
                messageMapper.updateContent(assistant.getId(), finalContent);
                messageMapper.completeMessage(assistant.getId(), streamResult.model(),
                        streamResult.tokenInput(), streamResult.tokenOutput());
                conversationMapper.incrementMessageCount(streamConvId);   // 助手消息计数

                // P13 用量记账: Redis 计数 + 落 api_usage 表(计费/看板数据源)
                int latencyMs = (int) ((System.nanoTime() - startNanos) / 1_000_000);
                usageService.record(tenantId, userId, streamConvId,
                        streamResult.model(),
                        streamResult.tokenInput(),
                        streamResult.tokenOutput(),
                        latencyMs);

                Map<String, Object> completionAudit = eventData(
                        "conversationId", streamConvId,
                        "traceId", streamTraceId,
                        "model", streamResult.model(),
                        "tokenInput", streamResult.tokenInput(),
                        "tokenOutput", streamResult.tokenOutput(),
                        "latencyMs", latencyMs);
                auditService.record("chat.message.complete", "message",
                        String.valueOf(assistant.getId()), completionAudit, 1);

                emitter.send(SseEmitter.event()
                        .name(ChatStreamEvent.TYPE_MESSAGE_DONE)
                        .id(String.valueOf(assistant.getSeq()))
                        .data(eventData("model", streamResult.model(),
                                "tokenInput", streamResult.tokenInput(),
                                "tokenOutput", streamResult.tokenOutput(),
                                "traceId", streamTraceId)));
                emitter.complete();
            } catch (Exception e) {
                log.warn("chat stream error conv={} trace={}", streamConvId, streamTraceId, e);
                messageMapper.updateStatus(assistant.getId(), 2);   // 标记失败
                Map<String, Object> failureAudit = new LinkedHashMap<>();
                failureAudit.put("conversationId", streamConvId);
                failureAudit.put("traceId", streamTraceId);
                failureAudit.put("errorType", e.getClass().getSimpleName());
                auditService.record("chat.message.complete", "message",
                        String.valueOf(assistant.getId()), failureAudit,
                        0);   // P13 审计: 失败留痕
                try {
                    emitter.send(SseEmitter.event()
                            .name(ChatStreamEvent.TYPE_ERROR)
                            .data(eventData("code", 3303,
                                    "message", "生成失败，请稍后重试",
                                    "traceId", streamTraceId)));
                } catch (Exception ignored) {}
                emitter.completeWithError(e);
            } finally {
                ping.cancel(false);
                flush.cancel(false);
            }
        });

        // 7. 超时/完成清理
        emitter.onTimeout(() -> {
            ping.cancel(false);
            flush.cancel(false);
            messageMapper.updateStatus(assistant.getId(), 3);   // 中断
            log.info("sse timeout conv={}", streamConvId);
        });
        emitter.onCompletion(() -> {
            ping.cancel(false);
            flush.cancel(false);
        });

        return emitter;
    }

    private Map<String, Object> eventData(Object... values) {
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            if (values[i + 1] != null) {
                data.put(String.valueOf(values[i]), values[i + 1]);
            }
        }
        return data;
    }

    private void handleToolEvent(
            ToolStreamEvent event,
            Long tenantId,
            Long messageId,
            Integer seq,
            SseEmitter emitter) {
        try {
            if (ToolStreamEvent.TYPE_START.equals(event.type())) {
                MessageToolCall record = new MessageToolCall();
                record.setTenantId(tenantId);
                record.setMessageId(messageId);
                record.setCallId(event.callId());
                record.setToolId(event.toolId());
                record.setToolName(event.toolName());
                record.setCallArgs(writeJson(event.arguments()));
                record.setStatus(0);
                record.setStartedAt(LocalDateTime.now());
                messageToolCallMapper.insert(record);
            } else {
                int status = "success".equals(event.status()) ? 1
                        : "timeout".equals(event.status()) ? 3 : 2;
                messageToolCallMapper.finishByCallId(
                        tenantId,
                        event.callId(),
                        event.result() == null ? null : writeJson(event.result()),
                        status,
                        Math.toIntExact(Math.min(event.durationMs(), Integer.MAX_VALUE)),
                        event.errorMessage());
            }

            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .id(String.valueOf(seq))
                    .data(toToolEventData(event)));
        } catch (IOException e) {
            throw new RuntimeException("sse tool event send failed", e);
        }
    }

    private Map<String, Object> toToolEventData(ToolStreamEvent event) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("callId", event.callId());
        data.put("toolId", event.toolId());
        data.put("toolCode", event.toolCode());
        data.put("toolName", event.toolName());
        data.put("status", event.status());
        data.put("durationMs", event.durationMs());
        if (event.arguments() != null) data.put("arguments", event.arguments());
        if (event.result() != null) data.put("result", event.result());
        if (event.errorCode() != null) data.put("errorCode", event.errorCode());
        if (event.errorMessage() != null) data.put("errorMessage", event.errorMessage());
        return data;
    }

    /**
     * 技能事件 → message_skill_call 落库 + SSE 中继（双轨审计的技能轨）。
     * 技能内工具事件仍走 handleToolEvent（message_tool_call），call_id 各自独立且可关联。
     */
    private void handleSkillEvent(
            SkillStreamEvent event,
            Long tenantId,
            Long messageId,
            Integer seq,
            SseEmitter emitter) {
        try {
            if (SkillStreamEvent.TYPE_START.equals(event.type())) {
                MessageSkillCall record = new MessageSkillCall();
                record.setTenantId(tenantId);
                record.setMessageId(messageId);
                record.setCallId(event.callId());
                record.setSkillId(event.skillId());
                record.setSkillCode(event.skillCode());
                record.setSkillName(event.skillName());
                record.setSkillVersion(event.skillVersion());
                record.setCallArgs(event.callArgs() == null ? null : writeJson(event.callArgs()));
                record.setStatus(0);
                record.setStartedAt(LocalDateTime.now());
                messageSkillCallMapper.insert(record);
            } else {
                int status = "success".equals(event.status()) ? 1
                        : "timeout".equals(event.status()) ? 3 : 2;
                messageSkillCallMapper.finishByCallId(
                        tenantId,
                        event.callId(),
                        event.result() == null ? null : writeJson(event.result()),
                        status,
                        Math.toIntExact(Math.min(event.durationMs(), Integer.MAX_VALUE)),
                        event.errorMessage());
            }

            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .id(String.valueOf(seq))
                    .data(toSkillEventData(event)));
        } catch (IOException e) {
            throw new RuntimeException("sse skill event send failed", e);
        }
    }

    private Map<String, Object> toSkillEventData(SkillStreamEvent event) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("callId", event.callId());
        data.put("skillId", event.skillId());
        data.put("skillCode", event.skillCode());
        data.put("skillName", event.skillName());
        data.put("status", event.status());
        data.put("durationMs", event.durationMs());
        if (event.skillVersion() != null) data.put("skillVersion", event.skillVersion());
        if (event.callArgs() != null) data.put("callArgs", event.callArgs());
        if (event.result() != null) data.put("result", event.result());
        if (event.errorCode() != null) data.put("errorCode", event.errorCode());
        if (event.errorMessage() != null) data.put("errorMessage", event.errorMessage());
        return data;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("tool audit json serialize failed", e);
        }
    }

    /** 从 message 表装配上下文 prompt: 最近 20 条(倒序取) + 反转, 追加本轮输入 */
    private String buildPrompt(String convId, Long tenantId, String currentInput) {
        List<Message> history = messageMapper.selectList(new LambdaQueryWrapper<Message>()
                .eq(Message::getConversationId, convId)
                .eq(Message::getTenantId, tenantId)
                .orderByDesc(Message::getSeq)
                .last("LIMIT 20"));
        StringBuilder sb = new StringBuilder();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            String content = sanitizeHistoryContent(m.getRole(), m.getContent());
            if (content == null || content.isBlank()) {
                continue;
            }
            sb.append(m.getRole()).append(": ").append(content).append("\n");
        }
        sb.append("user: ").append(currentInput).append("\n");
        return sb.toString();
    }

    /**
     * 历史消息是模型上下文，不应把内部协议原样重新喂给模型。
     * 旧版本曾把上游返回的 tool_calls JSON 落入 assistant.content，
     * DeepSeek 会在下一轮把这段 JSON 当作示例继续复述，形成“你好仍返回 tool_calls”的循环。
     */
    private String sanitizeHistoryContent(String role, String content) {
        if (content == null) {
            return null;
        }
        String value = content.trim();
        if (!"assistant".equalsIgnoreCase(role) || !isRawToolCallJson(value)) {
            return content;
        }
        return "（历史工具动作已隐藏，仅保留可审计的工具事件；禁止复述内部 tool_calls 协议。）";
    }

    private boolean isRawToolCallJson(String content) {
        if (content == null || content.isBlank() || !content.startsWith("{")) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(content);
            return root != null && (root.has("tool_calls") || root.has("tool_code"));
        } catch (Exception ignored) {
            return false;
        }
    }
}
