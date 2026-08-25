package com.agentforge.agent.skill.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** skill 表实体：技能元数据 + 渐进式披露的完整内容。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("skill")
public class SkillEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String skillCode;
    private String name;
    private String description;

    /** 触发规则 JSON（keyword / regex 列表） */
    private String triggersJson;

    /** 技能全文 JSON（指令 + 步骤 + 模板），命中后才拉取 */
    private String contentJson;

    private String skillFileUrl;
    private String version;

    /** 1启用 0禁用 */
    private Integer enabled;

    /** 是否内置技能（内置不可删，只能启停） */
    private Integer isBuiltin;
}
