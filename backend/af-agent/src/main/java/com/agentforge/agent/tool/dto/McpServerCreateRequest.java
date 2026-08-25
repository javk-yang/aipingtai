package com.agentforge.agent.tool.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 注册 MCP Server 请求。headers 不在日志中输出，生产环境应改为密钥引用。 */
@Data
public class McpServerCreateRequest {

    @NotBlank
    @Size(max = 64)
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @NotBlank
    @Pattern(regexp = "stdio|sse|http")
    private String transport;

    @Size(max = 512)
    private String command;

    private List<String> args;

    @Size(max = 512)
    private String url;

    private Map<String, String> headers;
}
