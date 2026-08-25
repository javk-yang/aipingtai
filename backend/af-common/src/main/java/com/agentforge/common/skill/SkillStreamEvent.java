package com.agentforge.common.skill;

/** Python 引擎传给 Java 中继层的技能生命周期事件（对齐 ToolStreamEvent）。 */
public record SkillStreamEvent(
        String type,
        String callId,
        Long skillId,
        String skillCode,
        String skillName,
        String skillVersion,
        Object callArgs,
        Object result,
        String status,
        String errorCode,
        String errorMessage,
        long durationMs
) {
    public static final String TYPE_START = "skill_call_start";
    public static final String TYPE_RESULT = "skill_call_result";
    public static final String TYPE_ERROR = "skill_call_error";
}
