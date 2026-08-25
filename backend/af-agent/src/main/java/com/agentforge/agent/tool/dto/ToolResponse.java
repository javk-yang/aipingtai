package com.agentforge.agent.tool.dto;

import com.agentforge.common.tool.ToolDescriptor;

import java.util.Map;

/**
 * 管理端工具响应。
 *
 * 与内部执行契约 ToolDescriptor 分离：管理端只展示治理元数据，绝不回显
 * MCP command、args、headers 等执行凭据；Python 动态发现接口仍使用内部契约。
 */
public record ToolResponse(
        Long id,
        String code,
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String executorType,
        String transport,
        Integer timeoutMs,
        Boolean enabled
) {
    public static ToolResponse from(ToolDescriptor descriptor) {
        return new ToolResponse(
                descriptor.id(),
                descriptor.code(),
                descriptor.name(),
                descriptor.description(),
                descriptor.inputSchema(),
                descriptor.outputSchema(),
                descriptor.executorType(),
                descriptor.transport(),
                descriptor.timeoutMs(),
                descriptor.enabled());
    }
}
