package com.agentforge.session.impl.controller;

import com.agentforge.common.api.R;
import com.agentforge.common.security.LoginUser;
import com.agentforge.common.security.RequirePermission;
import com.agentforge.common.security.UserContext;
import com.agentforge.session.api.dto.ModelConfigRequest;
import com.agentforge.session.api.dto.ModelConfigResponse;
import com.agentforge.session.impl.service.ModelConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 模型配置管理 —— /api/models */
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelConfigController {

    private final ModelConfigService service;

    @GetMapping
    @RequirePermission("agent:model:read")
    public R<List<ModelConfigResponse>> list() {
        LoginUser u = UserContext.getRequired();
        return R.ok(service.list(u.getTenantId()));
    }

    @GetMapping("/{id}")
    @RequirePermission("agent:model:read")
    public R<ModelConfigResponse> get(@PathVariable Long id) {
        LoginUser u = UserContext.getRequired();
        return R.ok(service.get(id, u.getTenantId()));
    }

    @PostMapping
    @RequirePermission("agent:model:write")
    public R<ModelConfigResponse> create(@RequestBody ModelConfigRequest req) {
        LoginUser u = UserContext.getRequired();
        return R.ok(service.create(req, u.getTenantId(), u.getUserId()));
    }

    @PutMapping("/{id}")
    @RequirePermission("agent:model:write")
    public R<ModelConfigResponse> update(@PathVariable Long id, @RequestBody ModelConfigRequest req) {
        LoginUser u = UserContext.getRequired();
        return R.ok(service.update(id, req, u.getTenantId(), u.getUserId()));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("agent:model:write")
    public R<Void> delete(@PathVariable Long id) {
        LoginUser u = UserContext.getRequired();
        service.delete(id, u.getTenantId());
        return R.ok();
    }

    @PostMapping("/test")
    @RequirePermission("agent:model:write")
    public R<Map<String, Object>> test(@RequestBody ModelConfigRequest req) {
        return R.ok(service.test(req));
    }

    @PostMapping("/{id}/test")
    @RequirePermission("agent:model:read")
    public R<Map<String, Object>> testId(@PathVariable Long id) {
        LoginUser u = UserContext.getRequired();
        return R.ok(service.test(id, u.getTenantId()));
    }
}
