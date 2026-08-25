package com.agentforge.auth.impl.config;

import com.agentforge.auth.impl.security.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 配置 —— 注册认证拦截器
 *
 * 为什么只拦 /api/**?
 * 平台所有业务接口都在 /api/ 下。静态资源、错误页、健康检查不走拦截器,
 * 避免"未登录访问 /error 也被 401"的死循环(401 → 前端跳转 → 静态资源 401)。
 *
 * CORS 配置在 af-common 的 WebMvcConfig 里(P2.2), 两个 WebMvcConfigurer 会合并生效。
 */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**");
    }
}
