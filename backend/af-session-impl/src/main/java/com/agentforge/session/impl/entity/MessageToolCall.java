package com.agentforge.session.impl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用记录实体 —— message_tool_call 表
 *
 * 设计决策(关键):
 * 不继承 BaseEntity! 该表没有 updated_at / deleted_at 两列(BaseEntity 会尝试映射 → 报 Unknown column)。
 * 工具调用是"审计型"数据, 不软删、不更新, 只有 started_at/finished_at 时间戳。
 * 自己声明需要的字段, 避免被基类带出的列拖垮。
 */
@Data
@TableName("message_tool_call")
public class MessageToolCall {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tenantId;

    /** 触发的 assistant 消息 */
    private Long messageId;

    /** 跨 Python / Java / SSE / MySQL 的稳定调用关联 ID */
    private String callId;

    /** 调用的工具(可空=临时工具) */
    private Long toolId;

    private String toolName;

    /** 入参(JSON 字符串存储) */
    private String callArgs;

    /** 结果(MEDIUMTEXT) */
    private String callResult;

    /** 0调用中 1成功 2失败 3超时 */
    private Integer status;

    /** 耗时 ms */
    private Integer durationMs;

    private String errorMsg;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
