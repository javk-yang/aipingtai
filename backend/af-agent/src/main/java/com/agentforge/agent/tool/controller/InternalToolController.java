package com.agentforge.agent.tool.controller;

import com.agentforge.agent.tool.service.ToolRegistryService;
import com.agentforge.common.api.R;
import com.agentforge.common.tool.ToolDescriptor;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Python 引擎使用的内部工具发现接口。
 *
 * 当前 P8 开发环境使用 tenant header；生产必须改为服务身份认证/mTLS，不能暴露到公网。
 */
@RestController
@RequestMapping("/internal/tools")
@RequiredArgsConstructor
public class InternalToolController {

    private final ToolRegistryService registryService;

    @GetMapping
    public R<List<ToolDescriptor>> listEnabled(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "1") Long tenantId) {
        return R.ok(registryService.listEnabled(tenantId));
    }
}
