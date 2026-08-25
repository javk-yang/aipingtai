package com.agentforge.agent.agent.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AgentStatusRequest {
    @NotNull(message = "状态不能为空")
    private Boolean enabled;
}
