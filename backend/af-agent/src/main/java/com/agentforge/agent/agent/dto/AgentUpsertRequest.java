package com.agentforge.agent.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class AgentUpsertRequest {
    @NotBlank(message = "智能体编码不能为空")
    @Size(max = 64, message = "智能体编码不能超过64个字符")
    @Pattern(regexp = "[A-Za-z][A-Za-z0-9_-]*", message = "智能体编码须以字母开头，仅支持字母、数字、下划线和短横线")
    private String code;

    @NotBlank(message = "智能体名称不能为空")
    @Size(max = 128, message = "智能体名称不能超过128个字符")
    private String name;

    @Size(max = 512, message = "描述不能超过512个字符")
    private String description;

    @Size(max = 32, message = "类型不能超过32个字符")
    private String agentType = "chat";

    @Size(max = 20000, message = "系统提示词不能超过20000个字符")
    private String systemPrompt;

    private Long modelConfigId;
    private List<Long> toolIds;
    private List<Long> skillIds;
    private List<String> knowledgeDocIds;
    private Boolean enabled;
    private Boolean defaultAgent;
}
