package com.agentforge.agent.agent.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AgentResponse(
        Long id,
        String code,
        String name,
        String description,
        String agentType,
        Integer status,
        Integer isDefault,
        String version,
        String systemPrompt,
        Long modelConfigId,
        List<Long> toolIds,
        List<Long> skillIds,
        List<String> knowledgeDocIds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
