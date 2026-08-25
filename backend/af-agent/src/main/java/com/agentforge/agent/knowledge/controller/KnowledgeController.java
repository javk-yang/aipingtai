package com.agentforge.agent.knowledge.controller;

import com.agentforge.agent.knowledge.dto.KnowledgeCreateRequest;
import com.agentforge.agent.knowledge.dto.KnowledgeDetailResponse;
import com.agentforge.agent.knowledge.dto.KnowledgeResponse;
import com.agentforge.agent.knowledge.dto.KnowledgeSearchRequest;
import com.agentforge.agent.knowledge.dto.KnowledgeSearchResult;
import com.agentforge.agent.knowledge.dto.KnowledgeUpdateRequest;
import com.agentforge.agent.knowledge.service.KnowledgeService;
import com.agentforge.common.api.R;
import com.agentforge.common.security.RequirePermission;
import com.agentforge.common.security.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 知识库管理接口（管理端）。 */
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    @PostMapping
    @RequirePermission("agent:knowledge:write")
    public R<KnowledgeResponse> create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId,
            @Valid @RequestBody KnowledgeCreateRequest request) {
        Long tenantId = UserContext.getRequired().getTenantId();
        return R.ok(knowledgeService.create(tenantId, request));
    }

    @PutMapping("/{docId}")
    @RequirePermission("agent:knowledge:write")
    public R<KnowledgeResponse> update(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId,
            @PathVariable String docId,
            @Valid @RequestBody KnowledgeUpdateRequest request) {
        Long tenantId = UserContext.getRequired().getTenantId();
        return R.ok(knowledgeService.update(tenantId, docId, request));
    }

    @PostMapping("/{docId}/reindex")
    @RequirePermission("agent:knowledge:write")
    public R<KnowledgeResponse> reindex(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId,
            @PathVariable String docId) {
        Long tenantId = UserContext.getRequired().getTenantId();
        return R.ok(knowledgeService.reindex(tenantId, docId));
    }


    @GetMapping
    @RequirePermission("agent:knowledge:read")
    public R<List<KnowledgeResponse>> list(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId) {
        Long tenantId = UserContext.getRequired().getTenantId();
        return R.ok(knowledgeService.list(tenantId));
    }

    @GetMapping("/{docId}")
    @RequirePermission("agent:knowledge:read")
    public R<KnowledgeDetailResponse> detail(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId,
            @PathVariable String docId) {
        Long tenantId = UserContext.getRequired().getTenantId();
        return R.ok(knowledgeService.getDetail(tenantId, docId));
    }


    @DeleteMapping("/{docId}")
    @RequirePermission("agent:knowledge:write")
    public R<Void> delete(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId,
            @PathVariable String docId) {
        Long tenantId = UserContext.getRequired().getTenantId();
        knowledgeService.delete(tenantId, docId);
        return R.ok(null);
    }

    @PostMapping("/search")
    @RequirePermission("agent:knowledge:read")
    public R<KnowledgeSearchResult> search(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId,
            @Valid @RequestBody KnowledgeSearchRequest request) {
        Long tenantId = UserContext.getRequired().getTenantId();
        return R.ok(knowledgeService.search(tenantId, request));
    }
}
