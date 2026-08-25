package com.agentforge.session.impl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 技能调用记录实体 —— message_skill_call 表
 *
 * 与 MessageToolCall 相同的设计决策：审计型数据，不继承 BaseEntity
 * （无 updated_at/deleted_at 列），不软删不更新，只有 started_at/finished_at。
 * call_id 为跨 Python / Java / SSE / MySQL 的稳定关联 ID（唯一键 uk_tenant_call）。
 */
@Data
@TableName("message_skill_call")
public class MessageSkillCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 触发的 assistant 消息 */
    private Long messageId;

    /** 跨 Python / Java / SSE / MySQL 的技能调用关联 ID */
    private String callId;

    /** 技能 ID（可空=临时技能） */
    private Long skillId;

    private String skillCode;

    private String skillName;

    private String skillVersion;

    /** 技能入参（触发上下文，JSON 字符串存储） */
    private String callArgs;

    /** 技能执行结果（MEDIUMTEXT） */
    private String callResult;

    /** 0执行中 1成功 2失败 3超时 */
    private Integer status;

    /** 耗时 ms */
    private Integer durationMs;

    private String errorMsg;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
