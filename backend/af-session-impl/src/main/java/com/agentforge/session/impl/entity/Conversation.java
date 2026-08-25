package com.agentforge.session.impl.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 会话实体 —— conversation 表
 *
 * 设计决策:
 * 1. 主键双字段: pk_id(BIGINT 自增, 聚簇, 分表路由) + id(CHAR(32) UUID, 对外暴露不可枚举)
 *    BaseEntity 故意不声明主键, 这里自己用 @TableId 指定 pk_id 为自增主键
 * 2. 继承 BaseEntity: 表有 tenant_id/created_at/updated_at/deleted_at, 逻辑删除自动生效
 * 3. id(UUID) 用 @TableId(exist=false)? 不——它不是主键, 用普通 @TableField,
 *    因为 @TableId 只能标一个。pk_id 才是真主键, id 是业务列。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("conversation")
public class Conversation extends BaseEntity {

    /** 内部自增主键(聚簇) */
    @TableId(value = "pk_id", type = IdType.AUTO)
    private Long pkId;

    /** 对外 ID: CHAR(32) UUID */
    private String id;

    /** 所属用户 */
    private Long userId;

    /** 绑定的智能体(可空) */
    private Long agentId;

    /** 标题 */
    private String title;

    /** 状态: 1活跃 2归档 3已删除 */
    private Integer status;

    /** 消息数(冗余) */
    private Integer messageCount;

    /** 最后消息时间 */
    private LocalDateTime lastMessageAt;
}
