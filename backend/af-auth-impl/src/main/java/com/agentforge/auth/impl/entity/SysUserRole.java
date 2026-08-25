package com.agentforge.auth.impl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户-角色关联实体 —— 对应 sys_user_role 表
 * 注意: 关联表是硬删除(无 deleted_at), P1 设计第 ④ 条
 * 所以不继承 BaseEntity(它带 @TableLogic 逻辑删除字段)
 */
@Data
@TableName("sys_user_role")
public class SysUserRole {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;

    private LocalDateTime createdAt;
}
