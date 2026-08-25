package com.agentforge.session.impl.audit.controller;

import com.agentforge.common.audit.AuditLog;
import com.agentforge.common.audit.mapper.AuditLogMapper;
import com.agentforge.common.api.PageResult;
import com.agentforge.common.api.R;
import com.agentforge.common.security.RequirePermission;
import com.agentforge.common.security.UserContext;
import com.agentforge.session.impl.audit.dto.AuditLogResponse;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 审计日志查询接口 —— 合规留痕的可视化入口
 *
 * 只读接口: audit_log 不可修改/删除, 这里也只暴露分页查询。
 * 过滤维度: action(操作码) + userId(操作用户), 时间排序倒序(最新在前)。
 */
@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditLogMapper auditLogMapper;

    /** 分页查询审计日志 */
    @GetMapping("/logs")
    @RequirePermission("agent:audit:read")
    public R<PageResult<AuditLogResponse>> logs(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        Long tenantId = UserContext.getRequired().getTenantId();
        long safePage = Math.max(page, 1);
        long safeSize = Math.min(Math.max(size, 1), 100);

        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(AuditLog::getTenantId, tenantId)
                .eq(StringUtils.hasText(action), AuditLog::getAction, action)
                .eq(userId != null, AuditLog::getUserId, userId)
                .orderByDesc(AuditLog::getId);

        Page<AuditLog> p = auditLogMapper.selectPage(new Page<>(safePage, safeSize), wrapper);
        List<AuditLogResponse> records = p.getRecords().stream()
                .map(this::toResponse)
                .toList();
        return R.ok(PageResult.of(safePage, safeSize, p.getTotal(), records));
    }

    private AuditLogResponse toResponse(AuditLog a) {
        return new AuditLogResponse(
                a.getId(), a.getTenantId(), a.getTraceId(), a.getUserId(),
                a.getAction(), a.getResource(), a.getResourceId(), a.getDetailJson(),
                a.getIp(), a.getUserAgent(), a.getStatus(), a.getCreatedAt());
    }
}
