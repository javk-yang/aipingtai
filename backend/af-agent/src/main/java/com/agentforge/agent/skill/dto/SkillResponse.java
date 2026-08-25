package com.agentforge.agent.skill.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 管理端技能响应。
 *
 * 技能内容（content）无执行凭据类敏感信息，管理端直接回显以便编辑；
 * Python 动态发现走内部契约 SkillDescriptor，两者职责分离。
 */
public record SkillResponse(
        Long id,
        String code,
        String name,
        String description,
        List<Map<String, Object>> triggers,
        Map<String, Object> content,
        String version,
        Boolean enabled,
        Boolean builtin,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
