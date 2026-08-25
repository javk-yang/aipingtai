package com.agentforge.auth.impl.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 角色实体 —— 对应 sys_role 表
 * RBAC 的 R: 权限的聚合容器
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 角色编码: 程序判定用, 如 admin / agent_builder */
    private String roleCode;

    /** 角色显示名 */
    private String roleName;

    private String description;

    private Integer sortOrder;

    /** 状态: 1启用 0禁用 */
    private Integer status;
}
