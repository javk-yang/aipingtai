package com.agentforge.common.tool;

import java.util.Map;

/** 工具调用标准请求：callId 用于跨 Python、Java、前端和审计表关联同一次调用。 */
public record ToolCallRequest(
        String callId,
        String toolCode,
        Map<String, Object> arguments,
        String conversationId,
        String traceId
) {
}
