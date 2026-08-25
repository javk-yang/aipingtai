package com.agentforge.agent.skill.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** 创建/注册技能请求。 */
@Data
public class SkillCreateRequest {

    @NotBlank
    @Size(max = 128)
    private String code;

    @NotBlank
    @Size(max = 128)
    private String name;

    @Size(max = 512)
    private String description;

    /** 触发规则：[{type: keyword|regex, values: [...]} | {type: regex, pattern: "..."}] */
    @NotNull
    private List<Map<String, Object>> triggers;

    /** 技能全文（instructions + steps + prompt），可空=只有元数据 */
    private Map<String, Object> content;

    /** SKILL.md 相对路径（如 unit_converter/SKILL.md），非空时全文以文件为准，content 仅作兼容 */
    @Size(max = 512)
    private String skillFileUrl;

    @Size(max = 32)
    private String version = "1.0.0";
}
