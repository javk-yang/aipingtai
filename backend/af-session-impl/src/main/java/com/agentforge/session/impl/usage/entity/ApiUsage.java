package com.agentforge.session.impl.usage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * API 用量实体 —— api_usage 表（计费依据，只追加）
 *
 * 每完成一条 assistant 消息就写一行:
 * 按调用计 token 与成本, 是统计看板 / 计费 / 配额扣减的唯一数据源。
 * 与 message 表按 conversation_id 关联, 可回溯到具体对话。
 */
@Data
@TableName("api_usage")
public class ApiUsage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户(计费维度) */
    private Long tenantId;

    /** 用户(子维度) */
    private Long userId;

    /** 会话 ID(溯源到具体对话) */
    private String conversationId;

    /** 生成模型(如 gpt-4o-mini / deepseek-chat) */
    private String model;

    /** 输入 token */
    private Integer tokenInput;

    /** 输出 token */
    private Integer tokenOutput;

    /** 本调用成本(元), 由单价 × token 计算 */
    private BigDecimal cost;

    /** 耗时(毫秒) */
    private Integer latencyMs;

    private LocalDateTime createdAt;
}
