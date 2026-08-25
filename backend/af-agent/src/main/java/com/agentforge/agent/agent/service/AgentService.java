package com.agentforge.agent.agent.service;

import com.agentforge.agent.agent.dto.AgentResponse;
import com.agentforge.agent.agent.dto.AgentStatusRequest;
import com.agentforge.agent.agent.dto.AgentUpsertRequest;
import com.agentforge.agent.agent.entity.AgentEntity;
import com.agentforge.agent.agent.entity.AgentVersionEntity;
import com.agentforge.agent.agent.mapper.AgentMapper;
import com.agentforge.agent.agent.mapper.AgentVersionMapper;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 智能体管理服务：定义 CRUD + 草稿/发布版本快照。 */
@Service
@RequiredArgsConstructor
public class AgentService {
    private final AgentMapper agentMapper;
    private final AgentVersionMapper versionMapper;
    private final ObjectMapper objectMapper;

    public List<AgentResponse> list(Long tenantId) {
        return agentMapper.selectList(new LambdaQueryWrapper<AgentEntity>()
                        .eq(AgentEntity::getTenantId, tenantId)
                        .orderByDesc(AgentEntity::getIsDefault)
                        .orderByDesc(AgentEntity::getUpdatedAt))
                .stream().map(agent -> toResponse(agent, latest(agent.getId()))).toList();
    }

    public AgentResponse get(Long tenantId, Long id) {
        AgentEntity agent = require(id, tenantId);
        return toResponse(agent, latest(agent.getId()));
    }

    @Transactional
    public AgentResponse create(Long tenantId, Long userId, AgentUpsertRequest req) {
        assertCodeAvailable(tenantId, req.getCode(), null);
        AgentEntity agent = new AgentEntity();
        agent.setTenantId(tenantId);
        agent.setAgentCode(req.getCode().trim());
        agent.setName(req.getName().trim());
        agent.setDescription(blankToNull(req.getDescription()));
        agent.setAgentType(defaultType(req.getAgentType()));
        agent.setStatus(Boolean.TRUE.equals(req.getEnabled()) ? 2 : 1);
        agent.setIsDefault(Boolean.TRUE.equals(req.getDefaultAgent()) ? 1 : 0);
        agent.setCreatedBy(userId);
        if (agent.getIsDefault() == 1) clearDefault(tenantId);
        agentMapper.insert(agent);
        AgentVersionEntity version = saveVersion(agent, userId, req, false);
        return toResponse(agent, version);
    }

    @Transactional
    public AgentResponse update(Long tenantId, Long userId, Long id, AgentUpsertRequest req) {
        AgentEntity agent = require(id, tenantId);
        assertCodeAvailable(tenantId, req.getCode(), id);
        agent.setAgentCode(req.getCode().trim());
        agent.setName(req.getName().trim());
        agent.setDescription(blankToNull(req.getDescription()));
        agent.setAgentType(defaultType(req.getAgentType()));
        if (req.getEnabled() != null) agent.setStatus(req.getEnabled() ? 2 : 3);
        if (req.getDefaultAgent() != null) {
            agent.setIsDefault(req.getDefaultAgent() ? 1 : 0);
            if (agent.getIsDefault() == 1) clearDefault(tenantId);
        }
        agentMapper.updateById(agent);
        AgentVersionEntity version = saveVersion(agent, userId, req, false);
        return toResponse(agent, version);
    }

    @Transactional
    public void delete(Long tenantId, Long id) {
        AgentEntity agent = require(id, tenantId);
        agentMapper.deleteById(agent.getId());
    }

    @Transactional
    public AgentResponse setStatus(Long tenantId, Long id, AgentStatusRequest req) {
        AgentEntity agent = require(id, tenantId);
        agent.setStatus(req.getEnabled() ? 2 : 3);
        agentMapper.updateById(agent);
        return toResponse(agent, latest(agent.getId()));
    }

    @Transactional
    public AgentResponse publish(Long tenantId, Long userId, Long id) {
        AgentEntity agent = require(id, tenantId);
        AgentVersionEntity current = latest(agent.getId());
        if (current == null) throw new BizException(ErrorCode.PARAM_INVALID, "智能体没有可发布的配置");
        current.setPublished(1);
        versionMapper.updateById(current);
        agent.setStatus(2);
        agentMapper.updateById(agent);
        return toResponse(agent, current);
    }

    /** 引擎调用使用：只允许已发布且启用的智能体。 */
    public Map<String, Object> resolveRuntime(Long tenantId, Long id) {
        AgentEntity agent = require(id, tenantId);
        if (agent.getStatus() != 2) throw new BizException(ErrorCode.AGENT_DISABLED);
        AgentVersionEntity version = versionMapper.selectOne(new LambdaQueryWrapper<AgentVersionEntity>()
                .eq(AgentVersionEntity::getAgentId, id)
                .eq(AgentVersionEntity::getPublished, 1)
                .orderByDesc(AgentVersionEntity::getId).last("LIMIT 1"));
        if (version == null) throw new BizException(ErrorCode.AGENT_DISABLED, "智能体尚未发布");
        Map<String, Object> result = readMap(version.getModelConfig());
        result.put("system_prompt", version.getSystemPrompt() == null ? "" : version.getSystemPrompt());
        result.put("tool_ids", readLongList(version.getToolsJson(), "tool_ids"));
        result.put("skill_ids", readLongList(version.getToolsJson(), "skill_ids"));
        result.put("knowledge_doc_ids", readStringList(version.getToolsJson(), "knowledge_doc_ids"));
        result.put("agent_id", id);
        result.put("agent_code", agent.getAgentCode());
        return result;
    }

    private AgentVersionEntity saveVersion(AgentEntity agent, Long userId, AgentUpsertRequest req, boolean published) {
        AgentVersionEntity v = new AgentVersionEntity();
        v.setAgentId(agent.getId());
        v.setVersion(nextVersion(agent.getId()));
        v.setGraphJson("{}");
        v.setSystemPrompt(blankToNull(req.getSystemPrompt()));
        v.setModelConfig(writeJson(modelConfig(req.getModelConfigId())));
        Map<String, Object> bindings = new LinkedHashMap<>();
        bindings.put("tool_ids", req.getToolIds() == null ? List.of() : req.getToolIds());
        bindings.put("skill_ids", req.getSkillIds() == null ? List.of() : req.getSkillIds());
        bindings.put("knowledge_doc_ids", req.getKnowledgeDocIds() == null ? List.of() : req.getKnowledgeDocIds());
        v.setToolsJson(writeJson(bindings));
        v.setChangeLog("管理端保存配置");
        v.setPublished(published ? 1 : 0);
        v.setCreatedBy(userId);
        versionMapper.insert(v);
        return v;
    }

    private Map<String, Object> modelConfig(Long id) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (id != null) m.put("model_config_id", id);
        return m;
    }

    private AgentVersionEntity latest(Long agentId) {
        return versionMapper.selectOne(new LambdaQueryWrapper<AgentVersionEntity>()
                .eq(AgentVersionEntity::getAgentId, agentId)
                .orderByDesc(AgentVersionEntity::getId).last("LIMIT 1"));
    }

    private String nextVersion(Long agentId) {
        int count = Math.toIntExact(versionMapper.selectCount(new LambdaQueryWrapper<AgentVersionEntity>()
                .eq(AgentVersionEntity::getAgentId, agentId)));
        return "1.0." + count;
    }

    private AgentEntity require(Long id, Long tenantId) {
        AgentEntity agent = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getId, id).eq(AgentEntity::getTenantId, tenantId));
        if (agent == null) throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        return agent;
    }

    private void assertCodeAvailable(Long tenantId, String code, Long currentId) {
        AgentEntity found = agentMapper.selectOne(new LambdaQueryWrapper<AgentEntity>()
                .eq(AgentEntity::getTenantId, tenantId).eq(AgentEntity::getAgentCode, code));
        if (found != null && !found.getId().equals(currentId)) throw new BizException(ErrorCode.PARAM_INVALID, "智能体编码已存在");
    }

    private void clearDefault(Long tenantId) {
        AgentEntity update = new AgentEntity();
        update.setIsDefault(0);
        agentMapper.update(update, new LambdaQueryWrapper<AgentEntity>().eq(AgentEntity::getTenantId, tenantId).eq(AgentEntity::getIsDefault, 1));
    }

    private AgentResponse toResponse(AgentEntity a, AgentVersionEntity v) {
        Map<String, Object> model = readMap(v == null ? null : v.getModelConfig());
        Map<String, Object> bindings = readMap(v == null ? null : v.getToolsJson());
        return new AgentResponse(a.getId(), a.getAgentCode(), a.getName(), a.getDescription(), a.getAgentType(), a.getStatus(), a.getIsDefault(),
                v == null ? null : v.getVersion(), v == null ? null : v.getSystemPrompt(),
                numberValue(model.get("model_config_id")), longList(bindings.get("tool_ids")), longList(bindings.get("skill_ids")), stringList(bindings.get("knowledge_doc_ids")), a.getCreatedAt(), a.getUpdatedAt());
    }

    private Long numberValue(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private List<Long> longList(Object value) { if (!(value instanceof List<?> list)) return List.of(); return list.stream().filter(Number.class::isInstance).map(v -> ((Number) v).longValue()).toList(); }
    private List<String> stringList(Object value) { if (!(value instanceof List<?> list)) return List.of(); return list.stream().filter(String.class::isInstance).map(String.class::cast).toList(); }
    private List<Long> readLongList(String json, String key) { return longList(readMap(json).get(key)); }
    private List<String> readStringList(String json, String key) { return stringList(readMap(json).get(key)); }
    private Map<String, Object> readMap(String json) { if (json == null || json.isBlank()) return new LinkedHashMap<>(); try { return objectMapper.readValue(json, new TypeReference<>() {}); } catch (JsonProcessingException e) { throw new BizException(ErrorCode.JSON_PARSE_ERROR, e); } }
    private String writeJson(Object value) { try { return objectMapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new BizException(ErrorCode.JSON_PARSE_ERROR, e); } }
    private String blankToNull(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private String defaultType(String v) { return v == null || v.isBlank() ? "chat" : v.trim(); }
}
