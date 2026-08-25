package com.agentforge.agent.tool.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** mcp_server 表实体：保存 MCP 端点配置，实际协议连接由 Python 引擎负责。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mcp_server")
public class McpServerEntity extends BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String serverCode;
    private String name;
    private String transport;
    private String command;
    private String argsJson;
    private String url;
    private String headersJson;
    private Integer status;
    private LocalDateTime lastPingAt;
}
