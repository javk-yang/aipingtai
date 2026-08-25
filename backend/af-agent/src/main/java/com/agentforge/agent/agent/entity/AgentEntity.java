package com.agentforge.agent.agent.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 智能体定义。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("agent")
public class AgentEntity extends BaseEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String agentCode;
    private String name;
    private String description;
    private String agentType;
    private Integer status;
    private Integer isDefault;
    private Long createdBy;
}
