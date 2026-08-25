package com.agentforge.agent.skill.controller;

import com.agentforge.agent.skill.service.SkillRegistryService;
import com.agentforge.common.api.R;
import com.agentforge.common.skill.SkillDescriptor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Python SkillEngine 使用的内部技能发现接口。
 *
 * 渐进式披露：
 * - GET /internal/skills          只返回元数据层（content=null，轻量路由）
 * - GET /internal/skills/{code}   命中后拉全文（含 instructions/steps/prompt）
 *
 * 当前 P9 开发环境使用 tenant header；生产必须改为服务身份认证/mTLS，不能暴露到公网。
 */
@RestController
@RequestMapping("/internal/skills")
@RequiredArgsConstructor
public class InternalSkillController {

    private final SkillRegistryService registryService;

    @GetMapping
    public R<List<SkillDescriptor>> listMeta(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long tenantId) {
        return R.ok(registryService.listMeta(tenantId));
    }

    @GetMapping("/{code}")
    public R<SkillDescriptor> detail(
            @PathVariable String code,
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long tenantId) {
        return R.ok(registryService.getDetail(tenantId, code));
    }
}
