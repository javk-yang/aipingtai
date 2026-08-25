package com.agentforge.agent.skill.service;

import com.agentforge.agent.skill.dto.SkillCreateRequest;
import com.agentforge.agent.skill.dto.SkillResponse;
import com.agentforge.agent.skill.entity.SkillEntity;
import com.agentforge.agent.skill.mapper.SkillMapper;
import com.agentforge.agent.skill.repo.SkillFileRepository;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.agentforge.common.skill.SkillDescriptor;
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
 * Java 技能注册中心。
 *
 * 职责：技能元数据、触发规则、全文内容（渐进式披露）、租户隔离、启停治理。
 * 执行编排留给 Python SkillEngine，Java 只做"注册 + 发现 + 审计"治理。
 */
@Service
@RequiredArgsConstructor
public class SkillRegistryService {

    private final SkillMapper skillMapper;
    private final SkillFileRepository skillFileRepository;
    private final ObjectMapper objectMapper;

    /** 管理端：全部技能（含停用），完整内容用于编辑。 */
    public List<SkillResponse> listAll(Long tenantId) {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                        .eq(SkillEntity::getTenantId, tenantId)
                        .orderByAsc(SkillEntity::getId))
                .stream().map(this::toResponse).toList();
    }

    /** 内部发现：仅启用技能，只返回元数据层（content=null，渐进式披露）。 */
    public List<SkillDescriptor> listMeta(Long tenantId) {
        return skillMapper.selectList(new LambdaQueryWrapper<SkillEntity>()
                        .eq(SkillEntity::getTenantId, tenantId)
                        .eq(SkillEntity::getEnabled, 1)
                        .orderByAsc(SkillEntity::getId))
                .stream().map(this::toDescriptorWithoutContent).toList();
    }

    /** 内部发现：命中技能后拉取全文（含 content）。 */
    public SkillDescriptor getDetail(Long tenantId, String code) {
        SkillEntity entity = requireSkillByCode(code, tenantId);
        if (entity.getEnabled() != 1) {
            throw new BizException(ErrorCode.SKILL_NOT_FOUND, "技能已禁用: " + code);
        }
        return toDescriptor(entity);
    }

    @Transactional
    public SkillResponse createSkill(Long tenantId, SkillCreateRequest req) {
        long count = skillMapper.selectCount(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getTenantId, tenantId)
                .eq(SkillEntity::getSkillCode, req.getCode()));
        if (count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "技能编码已存在");
        }

        SkillEntity entity = new SkillEntity();
        entity.setTenantId(tenantId);
        entity.setSkillCode(req.getCode());
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());
        entity.setTriggersJson(writeJson(req.getTriggers()));
        entity.setContentJson(req.getContent() == null ? null : writeJson(req.getContent()));
        entity.setSkillFileUrl(req.getSkillFileUrl());
        entity.setVersion(req.getVersion());
        entity.setEnabled(1);
        entity.setIsBuiltin(0);
        skillMapper.insert(entity);
        return toResponse(entity);
    }

    @Transactional
    public SkillResponse updateSkill(Long tenantId, Long id, SkillCreateRequest req) {
        SkillEntity entity = requireSkill(id, tenantId);
        entity.setSkillCode(req.getCode());
        entity.setName(req.getName());
        entity.setDescription(req.getDescription());
        entity.setTriggersJson(writeJson(req.getTriggers()));
        entity.setContentJson(req.getContent() == null ? null : writeJson(req.getContent()));
        entity.setSkillFileUrl(req.getSkillFileUrl());
        entity.setVersion(req.getVersion());
        skillMapper.updateById(entity);
        return toResponse(entity);
    }

    @Transactional
    public void deleteSkill(Long tenantId, Long id) {
        SkillEntity entity = requireSkill(id, tenantId);
        if (entity.getIsBuiltin() == 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "内置技能不可删除，请先停用");
        }
        skillMapper.deleteById(entity.getId());
    }
    @Transactional
    public SkillResponse setEnabled(Long tenantId, Long id, boolean enabled) {
        SkillEntity entity = requireSkill(id, tenantId);
        entity.setEnabled(enabled ? 1 : 0);
        skillMapper.updateById(entity);
        return toResponse(entity);
    }

    private SkillEntity requireSkill(Long id, Long tenantId) {
        SkillEntity entity = skillMapper.selectOne(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getId, id)
                .eq(SkillEntity::getTenantId, tenantId));
        if (entity == null) throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        return entity;
    }

    private SkillEntity requireSkillByCode(String code, Long tenantId) {
        SkillEntity entity = skillMapper.selectOne(new LambdaQueryWrapper<SkillEntity>()
                .eq(SkillEntity::getSkillCode, code)
                .eq(SkillEntity::getTenantId, tenantId));
        if (entity == null) throw new BizException(ErrorCode.SKILL_NOT_FOUND);
        return entity;
    }

    private SkillDescriptor toDescriptorWithoutContent(SkillEntity entity) {
        return new SkillDescriptor(
                entity.getId(),
                entity.getSkillCode(),
                entity.getName(),
                entity.getDescription(),
                readTriggers(entity.getTriggersJson()),
                null,
                entity.getVersion(),
                entity.getEnabled() == 1,
                entity.getIsBuiltin() == 1);
    }

    private SkillDescriptor toDescriptor(SkillEntity entity) {
        return new SkillDescriptor(
                entity.getId(),
                entity.getSkillCode(),
                entity.getName(),
                entity.getDescription(),
                readTriggers(entity.getTriggersJson()),
                loadContent(entity),
                entity.getVersion(),
                entity.getEnabled() == 1,
                entity.getIsBuiltin() == 1);
    }

    private SkillResponse toResponse(SkillEntity entity) {
        return new SkillResponse(
                entity.getId(),
                entity.getSkillCode(),
                entity.getName(),
                entity.getDescription(),
                readTriggers(entity.getTriggersJson()),
                loadContent(entity),
                entity.getVersion(),
                entity.getEnabled() == 1,
                entity.getIsBuiltin() == 1,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** 全文加载：SKILL.md 文件优先，DB content_json 兼容回退（渐进式披露 L1）。 */
    private Map<String, Object> loadContent(SkillEntity entity) {
        if (entity.getSkillFileUrl() != null && !entity.getSkillFileUrl().isBlank()) {
            Map<String, Object> fromFile = skillFileRepository.load(entity.getSkillFileUrl());
            if (fromFile != null) {
                return fromFile;
            }
        }
        return readNullableMap(entity.getContentJson());
    }

    private List<Map<String, Object>> readTriggers(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.JSON_PARSE_ERROR, e);
        }
    }

    private Map<String, Object> readNullableMap(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.JSON_PARSE_ERROR, e);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.JSON_PARSE_ERROR, e);
        }
    }
}
