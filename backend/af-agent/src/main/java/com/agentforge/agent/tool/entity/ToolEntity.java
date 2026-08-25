package com.agentforge.agent.tool.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** tool 表实体：本地内置工具与 MCP 工具统一注册。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("tool")
public class ToolEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String toolCode;
    private String name;
    private String description;
    private Long mcpServerId;
    private String inputSchema;
    private String outputSchema;
    private Integer isAsync;
    private Integer timeoutMs;
    private Integer status;
}
