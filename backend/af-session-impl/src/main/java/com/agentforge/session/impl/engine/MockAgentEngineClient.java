package com.agentforge.session.impl.engine;

import com.agentforge.common.skill.SkillStreamEvent;
import com.agentforge.common.tool.ToolStreamEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Mock 引擎 —— 造假流式文本, 让 SSE 全链路端到端今天就能跑通验证
 *
 * 设计决策:
 * 1. 为什么拆分句子推送 + 28ms 延迟?
 *    模拟真实 LLM 的"逐字打字机"节奏, 前端 SSE 渲染效果真实, 便于验证节流落库/断线恢复
 * 2. 为什么忽略 history 只回固定模板?
 *    Mock 的目的是验证"管道"(Java 中继 + 增量落库 + SSE 事件), 不验证"智能";
 *    真实推理在 P7 的 HttpAgentEngineClient 里, 那里才会真正消费 prompt 历史
 * 3. 替换点明确: P7 写 HttpAgentEngineClient(调真实 Python), 用 @ConditionalOnProperty 切换,
 *    ChatService 完全无感 —— 这就是依赖倒置的价值
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.engine", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockAgentEngineClient implements AgentEngineClient {

    @Override
    public StreamResult stream(String prompt, String conversationId, String traceId, Long tenantId,
                               Map<String, Object> llmConfig,
                               Map<String, Object> agentConfig,
                               Consumer<String> onDelta,
                               Consumer<ToolStreamEvent> onToolEvent,
                               Consumer<SkillStreamEvent> onSkillEvent,
                               Consumer<String> onReasoning) throws Exception {
        String reply = buildReply(prompt);
        // 按换行/句号切分, 逐段推送, 模拟打字机效果
        for (String chunk : reply.split("(?<=\\n)|(?<=[。！？])")) {
            if (chunk.isEmpty()) continue;
            onDelta.accept(chunk);
            Thread.sleep(28);
        }
        return new StreamResult("mock-engine", prompt.length() / 2, reply.length() / 2);
    }

    private String buildReply(String prompt) {
        return "（Mock 引擎）已收到你的输入。\n\n" +
               "这是 AgentForge 的流式响应骨架：当前由 Mock 引擎生成假文本，仅用于打通 SSE 全链路。\n\n" +
               "P7 阶段将替换为真实 LangGraph Agent 引擎（Python FastAPI 进程），届时此处会输出真实推理与工具调用过程。\n\n" +
               "你的原始 prompt 长度：" + prompt.length() + " 字符。";
    }
}
