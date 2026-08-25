package com.agentforge.common.tool;

/**
 * 工具调用标准结果。
 * status 固定为 success/error/timeout，避免将实现层异常直接泄露给调用方。
 */
public record ToolCallResult(
        String callId,
        String toolCode,
        String status,
        Object result,
        String errorCode,
        String errorMessage,
        long durationMs
) {

    public static ToolCallResult success(String callId, String toolCode, Object result, long durationMs) {
        return new ToolCallResult(callId, toolCode, "success", result, null, null, durationMs);
    }

    public static ToolCallResult error(
            String callId,
            String toolCode,
            String errorCode,
            String errorMessage,
            long durationMs) {
        return new ToolCallResult(callId, toolCode, "error", null, errorCode, errorMessage, durationMs);
    }

    public static ToolCallResult timeout(String callId, String toolCode, String errorMessage, long durationMs) {
        return new ToolCallResult(callId, toolCode, "timeout", null, "TOOL_TIMEOUT", errorMessage, durationMs);
    }
}
