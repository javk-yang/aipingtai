package com.agentforge.agent.skill.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 技能启停请求。 */
@Data
public class SkillStatusRequest {

    @NotNull
    private Boolean enabled;
}
