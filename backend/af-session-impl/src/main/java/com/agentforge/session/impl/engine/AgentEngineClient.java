package com.agentforge.session.impl.engine;

import com.agentforge.common.skill.SkillStreamEvent;
import com.agentforge.common.tool.ToolStreamEvent;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent 引擎客户端 —— 依赖倒置: 会话模块不认具体引擎实现
 *
 * 设计决策:
 * 1. 为什么抽象成接口?
 *    现在用 MockAgentEngineClient(造假流式) 让端到端今天就能跑通验证;
 *    P7 写 HttpAgentEngineClient 调真实 Python LangGraph 引擎, ChatService 一行不改。
 * 2. 为什么 onDelta 用 Consumer<String> 回调而不是返回 Stream?
 *    引擎是"推"模型(产生一段推一段), 回调最自然; 而且 Java 侧能边收边落库边中继 SSE,
 *    不需要把整个流缓存完再返回(铁律5: 增量)。
 * 3. traceId 贯穿(铁律4): 真实 HTTP 实现会把 traceId 放进请求头, 让引擎侧日志可串联。
 */
public interface AgentEngineClient {

    record StreamResult(String model, int tokenInput, int tokenOutput) {}

    /**
     * 流式生成
     * @param prompt          已装配好的完整 prompt(含历史上下文)
     * @param conversationId 会话 ID，传给 LangGraph 作为 thread_id/checkpoint 键
     * @param traceId         链路 ID(铁律4)
     * @param tenantId        当前租户 ID，用于 Python 动态发现租户隔离的工具
     * @param agentConfig     已发布 Agent 的运行时配置（系统提示词及资源绑定）
     * @param onDelta         每产生一段文本调用一次
     * @param onToolEvent     工具生命周期事件（tool_call_start/result/error）
     * @param onSkillEvent    技能生命周期事件（skill_call_start/result/error）
     */
    StreamResult stream(String prompt, String conversationId, String traceId, Long tenantId,
                        Map<String, Object> llmConfig,
                        Map<String, Object> agentConfig,
                        Consumer<String> onDelta,
                        Consumer<ToolStreamEvent> onToolEvent,
                        Consumer<SkillStreamEvent> onSkillEvent) throws Exception;
}
