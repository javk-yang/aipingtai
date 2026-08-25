package com.agentforge.agent.skill.dto;

/** skillzip 导入结果。 */
public record SkillUploadResponse(
        Long id,
        String code,
        String name,
        String version,
        String filePath,
        Boolean imported
) {}
