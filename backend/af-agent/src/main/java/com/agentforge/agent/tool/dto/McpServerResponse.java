package com.agentforge.agent.tool.dto;

import java.time.LocalDateTime;

/** 管理端 MCP Server 响应，不回显 headers_json 等敏感连接信息。 */
public record McpServerResponse(
        Long id,
        String code,
        String name,
        String transport,
        String command,
        String url,
        Integer status,
        LocalDateTime lastPingAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
