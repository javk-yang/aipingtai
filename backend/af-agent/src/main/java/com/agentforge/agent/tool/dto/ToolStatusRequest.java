package com.agentforge.agent.tool.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ToolStatusRequest {
    @NotNull
    private Boolean enabled;
}
