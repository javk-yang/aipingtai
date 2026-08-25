package com.agentforge.agent.tool.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.Map;

/** 创建/注册工具请求。 */
@Data
public class ToolCreateRequest {

    @NotBlank
    @Size(max = 128)
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    private Long mcpServerId;

    @NotNull
    private Map<String, Object> inputSchema;

    private Map<String, Object> outputSchema;

    private Boolean async = false;

    @Min(100)
    @Max(600_000)
    private Integer timeoutMs = 30_000;
}
