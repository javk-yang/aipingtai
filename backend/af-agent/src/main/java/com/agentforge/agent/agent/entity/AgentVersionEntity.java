package com.agentforge.agent.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 智能体配置版本快照。 */
@Data
@TableName("agent_version")
public class AgentVersionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long agentId;
    private String version;
    private String graphJson;
    private String systemPrompt;
    private String modelConfig;
    private String toolsJson;
    private String changeLog;
    private Integer published;
    private Long createdBy;
    private LocalDateTime createdAt;
}
