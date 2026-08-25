package com.agentforge.session.impl.usage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 配额实体 —— api_quota 表（租户/用户的用量上限与周期）
 *
 * 设计决策:
 * 1. scope 维度: tenant=租户级, user=用户级。
 *    预检时"先租户后用户"取最严格的那个作为当前生效配额。
 * 2. 唯一键 (scope, scope_id, period): 同维度同周期只有一条,
 *    改动走 upsert, 不会出现多条配额互相打架。
 * 3. soft_threshold: 软告警阈值(百分比), 达到后不阻断但标记 nearLimit,
 *    给运营留出"预警 → 扩容"的时间窗。
 */
@Data
@TableName("api_quota")
public class ApiQuota {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户 */
    private Long tenantId;

    /** 作用域: tenant / user */
    private String scope;

    /** 作用域 ID: 租户 ID 或用户 ID */
    private Long scopeId;

    /** 周期: day / month */
    private String period;

    /** token 上限 */
    private Long tokenLimit;

    /** 金额上限(元) */
    private BigDecimal costLimit;

    /** 软告警阈值(百分比, 默认 80) */
    private Integer softThreshold;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
