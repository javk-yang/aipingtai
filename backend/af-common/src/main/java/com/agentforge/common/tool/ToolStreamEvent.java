package com.agentforge.common.tool;

/** Python 引擎传给 Java 中继层的工具生命周期事件。 */
public record ToolStreamEvent(
        String type,
        String callId,
        Long toolId,
        String toolCode,
        String toolName,
        Object arguments,
        Object result,
        String status,
        String errorCode,
        String errorMessage,
        long durationMs
) {
    public static final String TYPE_START = "tool_call_start";
    public static final String TYPE_RESULT = "tool_call_result";
    public static final String TYPE_ERROR = "tool_call_error";
}
