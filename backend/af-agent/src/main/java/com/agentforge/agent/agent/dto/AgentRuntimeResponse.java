package com.agentforge.agent.agent.dto;

import java.util.List;

/** 引擎运行所需的已发布智能体配置。 */
public record AgentRuntimeResponse(
        Long id,
        String code,
        String name,
        String systemPrompt,
        Long modelConfigId,
        List<Long> toolIds,
        List<Long> skillIds,
        List<String> knowledgeDocIds
) {}
