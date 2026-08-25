package com.agentforge;

import com.agentforge.common.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 健康检查接口 —— 骨架阶段验证: 启动后访问 /api/health 能拿到 R 格式响应
 * P13 阶段会替换为带数据库/Redis/Python 引擎连通性的完整健康检查
 */
@RestController
public class HealthController {

    @GetMapping("/health")
    public R<Map<String, String>> health() {
        return R.ok(Map.of(
                "status", "UP",
                "app", "AgentForge",
                "version", "1.0.0-SNAPSHOT"
        ));
    }
}
