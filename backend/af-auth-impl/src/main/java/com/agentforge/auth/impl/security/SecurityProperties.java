package com.agentforge.auth.impl.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.springframework.util.AntPathMatcher;

/**
 * 安全配置 —— 白名单路径的集中管理
 *
 * 为什么白名单不写死在拦截器里?
 * 新增一个公开接口(比如开放 API 文档)要改拦截器代码 + 重新编译。
 * 放到配置文件(application.yml 的 security.whitelist), 运维改配置即可。
 *
 * 白名单 = 内置兜底 + yml 扩展:
 * - 内置兜底: 核心登录链路(login/register/captcha/...) 即使 yml 忘配也不会锁死
 * - yml 扩展: security.whitelist 追加额外公开路径(如 /api/open/**)
 * 构造时合并, 之后只读。
 */
@Slf4j
@Component
public class SecurityProperties {

    /** yml 注入的额外白名单(逗号分隔), 默认空 */
    @Value("${security.whitelist:}")
    private String[] extraWhitelist;

    /** 内置兜底: 核心认证链路必须公开 */
    private static final Set<String> BUILTIN = new HashSet<>(Arrays.asList(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/refresh",
            "/api/auth/health",
            "/api/auth/captcha/image",
            "/api/auth/code/sms",
            "/api/auth/code/email",
            "/api/auth/password/reset",
            "/error"
    ));

    /** 合并后的完整白名单(不可变) */
    private final Set<String> whitelist = new HashSet<>();

    @PostConstruct
    void merge() {
        whitelist.addAll(BUILTIN);
        if (extraWhitelist != null) {
            // 过滤空串(yml 里默认空值时注入的 [""] 不应进白名单)
            Arrays.stream(extraWhitelist)
                    .filter(s -> s != null && !s.isBlank())
                    .forEach(whitelist::add);
        }
        log.info("认证白名单已加载 | 公开接口数={}", whitelist.size());
    }

    /** Ant 风格路径匹配器: 白名单支持 /** 通配(精确路径仍精确匹配, 向后兼容) */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** 判断 URI 是否公开(无需登录): 精确或 ant 通配 */
    public boolean isWhitelisted(String uri) {
        return whitelist.stream().anyMatch(p -> MATCHER.match(p, uri));
    }
}
