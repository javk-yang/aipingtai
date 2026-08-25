package com.agentforge.agent.skill.controller;

import com.agentforge.agent.skill.dto.SkillCreateRequest;
import com.agentforge.agent.skill.dto.SkillResponse;
import com.agentforge.agent.skill.dto.SkillStatusRequest;
import com.agentforge.agent.skill.dto.SkillUploadResponse;
import com.agentforge.agent.skill.service.SkillPackageService;
import com.agentforge.agent.skill.service.SkillRegistryService;
import com.agentforge.common.api.R;
import com.agentforge.common.security.RequirePermission;
import com.agentforge.common.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** 管理端技能治理接口。 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    private final SkillRegistryService registryService;
    private final SkillPackageService packageService;

    @GetMapping
    @RequirePermission("agent:skill:read")
    public R<List<SkillResponse>> list() {
        return R.ok(registryService.listAll(UserContext.getRequired().getTenantId()));
    }

    @PostMapping
    @RequirePermission("agent:skill:write")
    public R<SkillResponse> create(@Valid @RequestBody SkillCreateRequest req) {
        return R.ok(registryService.createSkill(UserContext.getRequired().getTenantId(), req));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission("agent:skill:write")
    public R<SkillUploadResponse> upload(@RequestPart("file") MultipartFile file) {
        return R.ok(packageService.importPackage(
                UserContext.getRequired().getTenantId(), file));
    }

    @PatchMapping("/{id}")
    @RequirePermission("agent:skill:write")
    public R<SkillResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody SkillCreateRequest req) {
        return R.ok(registryService.updateSkill(
                UserContext.getRequired().getTenantId(), id, req));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("agent:skill:write")
    public R<Void> delete(@PathVariable Long id) {
        registryService.deleteSkill(UserContext.getRequired().getTenantId(), id);
        return R.ok();
    }


    @PatchMapping("/{id}/status")
    @RequirePermission("agent:skill:write")
    public R<SkillResponse> setStatus(
            @PathVariable Long id,
            @Valid @RequestBody SkillStatusRequest req) {
        return R.ok(registryService.setEnabled(
                UserContext.getRequired().getTenantId(), id, req.getEnabled()));
    }
}
