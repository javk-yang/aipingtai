package com.agentforge.session.impl.usage.controller;

import com.agentforge.common.api.R;
import com.agentforge.common.security.RequirePermission;
import com.agentforge.common.security.UserContext;
import com.agentforge.session.impl.usage.dto.UsageStatsResponse;
import com.agentforge.session.impl.usage.service.UsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用量与配额接口 —— 可观测性看板的数据源
 */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final UsageService usageService;

    /** 用量统计: 今日累计 + 配额状态 + 最近 7 天趋势 */
    @GetMapping("/stats")
    @RequirePermission("agent:usage:read")
    public R<UsageStatsResponse> stats(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long ignoredTenantId) {
        Long tenantId = UserContext.getRequired().getTenantId();
        return R.ok(usageService.stats(tenantId));
    }
}
