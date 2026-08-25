package com.agentforge.common.tool;

import java.util.Map;

/**
 * 工具描述契约：Java 注册中心、Python Agent 引擎共同遵守的最小工具元数据。
 *
 * executorType 当前支持 builtin/mcp。transport 仅 MCP 工具使用，支持 stdio/sse/http。
 */
public record ToolDescriptor(
        Long id,
        String code,
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String executorType,
        String transport,
        Map<String, Object> executorConfig,
        Integer timeoutMs,
        Boolean enabled
) {
}
