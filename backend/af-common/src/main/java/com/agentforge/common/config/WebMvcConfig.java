package com.agentforge.common.config;

import jakarta.servlet.DispatcherType;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置 —— CORS 跨域 + 过滤器注册
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么用 CorsFilter 而不是 @CrossOrigin?
 *    @CrossOrigin 要在每个 Controller 上加注解, 漏一个就跨域失败。
 *    CorsFilter 在全局拦截所有请求, 统一管理 CORS 策略, 不依赖注解。
 *    而且 CorsFilter 能处理 preflight OPTIONS 请求, @CrossOrigin 有时拦截器会先返回 403。
 *
 * 2. 为什么不 allowAllOrigins("*")?
 *    带 credentials(cookies) 时, 浏览器不允许 origin=*。
 *    生产环境必须指定域名, 开发环境用 localhost 各端口。
 *    这里 dev 环境放开 localhost, 生产通过环境变量注入白名单。
 *
 * 3. 为什么 TraceIdFilter 不在这里注册?
 *    TraceIdFilter 标了 @Component + @Order, Spring 会自动注册。
 *    这里只注册需要手动控制顺序的 CorsFilter——它必须在所有业务 Filter 之前处理 preflight。
 *
 * 4. 为什么 FilterRegistrationBean 而不是直接 @Bean CorsFilter?
 *    FilterRegistrationBean 能精确控制执行顺序(setOrder)。
 *    CORS 必须 HIGHEST_PRECEDENCE, 否则 TraceIdFilter 先执行时,
 *    OPTIONS 请求拿不到 CORS 头, 前端报跨域错误。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * CORS 过滤器: 必须最高优先级, 在所有过滤器之前
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        // 开发环境放行 localhost 各端口, 生产通过 env 注入
        config.addAllowedOriginPattern("http://localhost:*");
        config.addAllowedOriginPattern("http://127.0.0.1:*");
        // 本地局域网开发：允许 Vite 使用当前机器 IP 提供页面时正常调用 Java API。
        // 仅允许 HTTP 内网来源，不使用 *，避免 credentials 场景失去边界。
        config.addAllowedOriginPattern("http://10.*.*.*:*");
        config.addAllowedOriginPattern("http://192.168.*.*:*");
        config.addAllowedOriginPattern("http://172.16.*.*:*");
        config.addAllowedOriginPattern("http://172.17.*.*:*");
        config.addAllowedOriginPattern("http://172.18.*.*:*");
        config.addAllowedOriginPattern("http://172.19.*.*:*");
        config.addAllowedOriginPattern("http://172.2*.*.*:*");
        config.addAllowedOriginPattern("http://172.30.*.*:*");
        config.addAllowedOriginPattern("http://172.31.*.*:*");
        // 生产环境: config.addAllowedOrigin(System.getenv("ALLOWED_ORIGIN"));

        // 允许的请求方法
        config.addAllowedMethod("*");
        // 允许的请求头
        config.addAllowedHeader("*");
        // 暴露给前端的响应头(前端 JS 要读的)
        config.addExposedHeader("X-Trace-Id");
        // 允许携带 Cookie
        config.setAllowCredentials(true);
        // preflight 预检缓存 1 小时, 减少 OPTIONS 请求
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>();
        bean.setFilter(new CorsFilter(source));
        bean.setOrder(org.springframework.core.Ordered.HIGHEST_PRECEDENCE);
        bean.setDispatcherTypes(DispatcherType.REQUEST);
        return bean;
    }
}
