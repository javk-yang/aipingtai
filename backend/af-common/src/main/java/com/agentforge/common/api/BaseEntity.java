package com.agentforge.common.api;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类 —— 所有 MyBatis-Plus 实体的公共字段
 *
 * 设计决策:
 *
 * 1. 为什么不用 @MappedSuperclass 而是继承?
 *    MyBatis-Plus 用 @TableField + MetaObjectHandler 做自动填充,
 *    继承 BaseEntity 后子类自动拥有这些字段, Mapper 生成的 SQL 自动包含。
 *
 * 2. 为什么 id 用 Long 不用主键?
 *    有些表的主键不是 id(比如 conversation 用 pk_id), 所以 BaseEntity 不声明主键,
 *    只声明公共业务字段。主键由各子类自己用 @TableId 声明。
 *
 * 3. 为什么 createdAt/updatedAt 用 @TableField(fill = ...)?
 *    fill = INSERT: 插入时由 MetaObjectHandler 自动填值
 *    fill = INSERT_UPDATE: 插入和更新都自动填值
 *    这样 Service 层不需要手动 setCreatedAt(new Date()), 框架自动做。
 *
 * 4. 为什么 deletedAt 用 @TableLogic?
 *    MyBatis-Plus 的逻辑删除: DELETE 语句自动转为 UPDATE deleted_at=now(),
 *    查询自动加 WHERE deleted_at IS NULL。
 *    注意: 只有需要软删除的表才继承 BaseEntity; 审计日志表不继承(不删除)。
 */
@Data
public abstract class BaseEntity implements Serializable {

    /** 租户 ID: 多租户预留, 单租户期间恒为 1 (P1 设计第 ② 条) */
    @TableField("tenant_id")
    private Long tenantId;

    /** 创建时间: INSERT 时自动填充 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间: INSERT + UPDATE 时自动填充 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 软删除标记: 逻辑删除用, deleted_at IS NULL = 未删除 */
    @TableField("deleted_at")
    @TableLogic(value = "NULL", delval = "NOW(3)")
    private LocalDateTime deletedAt;
}
