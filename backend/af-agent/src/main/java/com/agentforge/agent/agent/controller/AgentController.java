package com.agentforge.agent.agent.controller;

import com.agentforge.agent.agent.dto.AgentResponse;
import com.agentforge.agent.agent.dto.AgentStatusRequest;
import com.agentforge.agent.agent.dto.AgentUpsertRequest;
import com.agentforge.agent.agent.service.AgentService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 智能体管理端接口。 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {
    private final AgentService agentService;

    @GetMapping
    @RequirePermission("agent:agent:read")
    public R<List<AgentResponse>> list() {
        return R.ok(agentService.list(UserContext.getRequired().getTenantId()));
    }

    @GetMapping("/{id}/runtime")
    @RequirePermission("agent:agent:read")
    public R<java.util.Map<String, Object>> runtime(@PathVariable Long id) {
        return R.ok(agentService.resolveRuntime(UserContext.getRequired().getTenantId(), id));
    }

    @GetMapping("/{id}")
    @RequirePermission("agent:agent:read")
    public R<AgentResponse> get(@PathVariable Long id) {
        return R.ok(agentService.get(UserContext.getRequired().getTenantId(), id));
    }

    @PostMapping
    @RequirePermission("agent:agent:write")
    public R<AgentResponse> create(@Valid @RequestBody AgentUpsertRequest req) {
        var u = UserContext.getRequired();
        return R.ok(agentService.create(u.getTenantId(), u.getUserId(), req));
    }

    @PutMapping("/{id}")
    @RequirePermission("agent:agent:write")
    public R<AgentResponse> update(@PathVariable Long id, @Valid @RequestBody AgentUpsertRequest req) {
        var u = UserContext.getRequired();
        return R.ok(agentService.update(u.getTenantId(), u.getUserId(), id, req));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("agent:agent:write")
    public R<Void> delete(@PathVariable Long id) {
        agentService.delete(UserContext.getRequired().getTenantId(), id);
        return R.ok();
    }

    @PatchMapping("/{id}/status")
    @RequirePermission("agent:agent:write")
    public R<AgentResponse> status(@PathVariable Long id, @Valid @RequestBody AgentStatusRequest req) {
        return R.ok(agentService.setStatus(UserContext.getRequired().getTenantId(), id, req));
    }

    @PostMapping("/{id}/publish")
    @RequirePermission("agent:agent:write")
    public R<AgentResponse> publish(@PathVariable Long id) {
        var u = UserContext.getRequired();
        return R.ok(agentService.publish(u.getTenantId(), u.getUserId(), id));
    }
}
