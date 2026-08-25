package com.agentforge.common.skill;

import java.util.List;
import java.util.Map;

/**
 * 技能描述契约（Java 工具注册中心 → Python SkillEngine 内部发现）。
 *
 * 渐进式披露（P9 核心设计）：
 * - 列表接口只返回元数据：content 恒为 null，payload 轻量；
 * - 命中技能后 Python 再调详情接口拉 content（指令 + 步骤 + 模板）。
 */
public record SkillDescriptor(
        Long id,
        String code,
        String name,
        String description,
        List<Map<String, Object>> triggers,
        Map<String, Object> content,
        String version,
        Boolean enabled,
        Boolean builtin
) {}
