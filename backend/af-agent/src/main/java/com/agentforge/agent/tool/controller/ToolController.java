package com.agentforge.agent.tool.controller;

import com.agentforge.agent.tool.dto.McpServerCreateRequest;
import com.agentforge.agent.tool.dto.McpServerResponse;
import com.agentforge.agent.tool.dto.ToolCreateRequest;
import com.agentforge.agent.tool.dto.ToolResponse;
import com.agentforge.agent.tool.dto.ToolStatusRequest;
import com.agentforge.agent.tool.service.ToolRegistryService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 平台管理端工具注册接口。 */
@RestController
@RequestMapping("/api/tools")
@RequiredArgsConstructor
public class ToolController {

    private final ToolRegistryService registryService;

    @GetMapping
    @RequirePermission("agent:tool:read")
    public R<List<ToolResponse>> list() {
        return R.ok(registryService.listAll(UserContext.getRequired().getTenantId())
                .stream().map(ToolResponse::from).toList());
    }

    @PostMapping
    @RequirePermission("agent:tool:write")
    public R<ToolResponse> create(@Valid @RequestBody ToolCreateRequest req) {
        return R.ok(ToolResponse.from(
                registryService.createTool(UserContext.getRequired().getTenantId(), req)));
    }

    @PatchMapping("/{id}")
    @RequirePermission("agent:tool:write")
    public R<ToolResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody ToolCreateRequest req) {
        return R.ok(ToolResponse.from(registryService.updateTool(
                UserContext.getRequired().getTenantId(), id, req)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission("agent:tool:write")
    public R<Void> delete(@PathVariable Long id) {
        registryService.deleteTool(UserContext.getRequired().getTenantId(), id);
        return R.ok();
    }


    @PatchMapping("/{id}/status")
    @RequirePermission("agent:tool:write")
    public R<ToolResponse> setStatus(
            @PathVariable Long id,
            @Valid @RequestBody ToolStatusRequest req) {
        return R.ok(ToolResponse.from(registryService.setEnabled(
                UserContext.getRequired().getTenantId(), id, req.getEnabled())));
    }

    @GetMapping("/mcp-servers")
    @RequirePermission("agent:tool:read")
    public R<List<McpServerResponse>> listServers() {
        return R.ok(registryService.listServers(UserContext.getRequired().getTenantId()));
    }

    @PostMapping("/mcp-servers")
    @RequirePermission("agent:tool:write")
    public R<McpServerResponse> createServer(@Valid @RequestBody McpServerCreateRequest req) {
        return R.ok(registryService.createServer(UserContext.getRequired().getTenantId(), req));
    }
}
