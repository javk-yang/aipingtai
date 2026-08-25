package com.agentforge.agent.tool.service;

import com.agentforge.agent.tool.dto.McpServerCreateRequest;
import com.agentforge.agent.tool.dto.McpServerResponse;
import com.agentforge.agent.tool.dto.ToolCreateRequest;
import com.agentforge.agent.tool.entity.McpServerEntity;
import com.agentforge.agent.tool.entity.ToolEntity;
import com.agentforge.agent.tool.mapper.McpServerMapper;
import com.agentforge.agent.tool.mapper.ToolMapper;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.agentforge.common.tool.ToolDescriptor;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Java 工具注册中心。
 *
 * 只负责元数据、租户隔离、启停治理；实际工具执行留给 Python Tool Gateway。
 */
@Service
@RequiredArgsConstructor
public class ToolRegistryService {

    private final ToolMapper toolMapper;
    private final McpServerMapper mcpServerMapper;
    private final ObjectMapper objectMapper;

    public List<ToolDescriptor> listEnabled(Long tenantId) {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolEntity>()
                        .eq(ToolEntity::getTenantId, tenantId)
                        .eq(ToolEntity::getStatus, 1)
                        .orderByAsc(ToolEntity::getId))
                .stream().map(this::toDescriptor).toList();
    }

    public List<ToolDescriptor> listAll(Long tenantId) {
        return toolMapper.selectList(new LambdaQueryWrapper<ToolEntity>()
                        .eq(ToolEntity::getTenantId, tenantId)
                        .orderByDesc(ToolEntity::getId))
                .stream().map(this::toDescriptor).toList();
    }

    @Transactional
    public ToolDescriptor createTool(Long tenantId, ToolCreateRequest req) {
        long count = toolMapper.selectCount(new LambdaQueryWrapper<ToolEntity>()
                .eq(ToolEntity::getTenantId, tenantId)
                .eq(ToolEntity::getToolCode, req.getCode()));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "工具编码已存在");
        }
        if (req.getMcpServerId() != null) {
            requireServer(req.getMcpServerId(), tenantId);
        }

        ToolEntity entity = new ToolEntity();
        entity.setTenantId(tenantId);
        entity.setToolCode(req.getCode());
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());
        entity.setMcpServerId(req.getMcpServerId());
        entity.setInputSchema(writeJson(req.getInputSchema()));
        entity.setOutputSchema(req.getOutputSchema() == null ? null : writeJson(req.getOutputSchema()));
        entity.setIsAsync(Boolean.TRUE.equals(req.getAsync()) ? 1 : 0);
        entity.setTimeoutMs(req.getTimeoutMs());
        entity.setStatus(1);
        toolMapper.insert(entity);
        return toDescriptor(entity);
    }

    @Transactional
    public ToolDescriptor updateTool(Long tenantId, Long id, ToolCreateRequest req) {
        ToolEntity entity = requireTool(id, tenantId);
        if (req.getMcpServerId() != null) {
            requireServer(req.getMcpServerId(), tenantId);
        }
        entity.setToolCode(req.getCode());
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());
        entity.setMcpServerId(req.getMcpServerId());
        entity.setInputSchema(writeJson(req.getInputSchema()));
        entity.setOutputSchema(req.getOutputSchema() == null ? null : writeJson(req.getOutputSchema()));
        entity.setIsAsync(Boolean.TRUE.equals(req.getAsync()) ? 1 : 0);
        entity.setTimeoutMs(req.getTimeoutMs());
        toolMapper.updateById(entity);
        return toDescriptor(entity);
    }

    @Transactional
    public void deleteTool(Long tenantId, Long id) {
        ToolEntity entity = requireTool(id, tenantId);
        toolMapper.deleteById(entity.getId());
    }
    @Transactional
    public ToolDescriptor setEnabled(Long tenantId, Long id, boolean enabled) {
        ToolEntity entity = requireTool(id, tenantId);
        entity.setStatus(enabled ? 1 : 0);
        toolMapper.updateById(entity);
        return toDescriptor(entity);
    }

    @Transactional
    public McpServerResponse createServer(Long tenantId, McpServerCreateRequest req) {
        validateServerConfig(req);
        long count = mcpServerMapper.selectCount(new LambdaQueryWrapper<McpServerEntity>()
                .eq(McpServerEntity::getTenantId, tenantId)
                .eq(McpServerEntity::getServerCode, req.getCode()));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "MCP Server 编码已存在");
        }

        McpServerEntity entity = new McpServerEntity();
        entity.setTenantId(tenantId);
        entity.setServerCode(req.getCode());
        entity.setName(req.getName());
        entity.setTransport(req.getTransport());
        entity.setCommand(req.getCommand());
        entity.setArgsJson(req.getArgs() == null ? null : writeJson(req.getArgs()));
        entity.setUrl(req.getUrl());
        entity.setHeadersJson(req.getHeaders() == null ? null : writeJson(req.getHeaders()));
        entity.setStatus(1);
        mcpServerMapper.insert(entity);
        return toServerResponse(entity);
    }

    public List<McpServerResponse> listServers(Long tenantId) {
        return mcpServerMapper.selectList(new LambdaQueryWrapper<McpServerEntity>()
                        .eq(McpServerEntity::getTenantId, tenantId)
                        .orderByDesc(McpServerEntity::getId))
                .stream().map(this::toServerResponse).toList();
    }

    private ToolEntity requireTool(Long id, Long tenantId) {
        ToolEntity entity = toolMapper.selectOne(new LambdaQueryWrapper<ToolEntity>()
                .eq(ToolEntity::getId, id)
                .eq(ToolEntity::getTenantId, tenantId));
        if (entity == null) throw new BizException(ErrorCode.TOOL_NOT_FOUND);
        return entity;
    }

    private McpServerEntity requireServer(Long id, Long tenantId) {
        McpServerEntity entity = mcpServerMapper.selectOne(new LambdaQueryWrapper<McpServerEntity>()
                .eq(McpServerEntity::getId, id)
                .eq(McpServerEntity::getTenantId, tenantId));
        if (entity == null) throw new BizException(ErrorCode.PARAM_INVALID, "MCP Server 不存在");
        return entity;
    }

    private void validateServerConfig(McpServerCreateRequest req) {
        if ("stdio".equals(req.getTransport()) && (req.getCommand() == null || req.getCommand().isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "stdio MCP Server 必须配置 command");
        }
        if (!"stdio".equals(req.getTransport()) && (req.getUrl() == null || req.getUrl().isBlank())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "网络 MCP Server 必须配置 url");
        }
    }

    private ToolDescriptor toDescriptor(ToolEntity entity) {
        McpServerEntity server = entity.getMcpServerId() == null
                ? null : mcpServerMapper.selectById(entity.getMcpServerId());
        return new ToolDescriptor(
                entity.getId(),
                entity.getToolCode(),
                entity.getName(),
                entity.getDescription(),
                readMap(entity.getInputSchema()),
                readNullableMap(entity.getOutputSchema()),
                entity.getMcpServerId() == null ? "builtin" : "mcp",
                server == null ? null : server.getTransport(),
                toExecutorConfig(server),
                entity.getTimeoutMs(),
                entity.getStatus() == 1
        );
    }

    private Map<String, Object> toExecutorConfig(McpServerEntity server) {
        if (server == null) return Map.of();
        java.util.LinkedHashMap<String, Object> config = new java.util.LinkedHashMap<>();
        if (server.getCommand() != null) config.put("command", server.getCommand());
        if (server.getArgsJson() != null) config.put("args", readList(server.getArgsJson()));
        if (server.getUrl() != null) config.put("url", server.getUrl());
        if (server.getHeadersJson() != null) config.put("headers", readMap(server.getHeadersJson()));
        return config;
    }

    private McpServerResponse toServerResponse(McpServerEntity entity) {
        return new McpServerResponse(
                entity.getId(), entity.getServerCode(), entity.getName(), entity.getTransport(),
                entity.getCommand(), entity.getUrl(), entity.getStatus(), entity.getLastPingAt(),
                entity.getCreatedAt(), entity.getUpdatedAt());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.JSON_PARSE_ERROR, e);
        }
    }

    private Map<String, Object> readMap(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.JSON_PARSE_ERROR, e);
        }
    }

    private Map<String, Object> readNullableMap(String value) {
        return value == null || value.isBlank() ? null : readMap(value);
    }

    private List<Object> readList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.JSON_PARSE_ERROR, e);
        }
    }
}
