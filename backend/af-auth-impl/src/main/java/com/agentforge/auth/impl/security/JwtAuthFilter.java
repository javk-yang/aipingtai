package com.agentforge.auth.impl.security;

import com.agentforge.common.constant.CommonConst;
import com.agentforge.common.security.LoginUser;
import com.agentforge.common.security.UserContext;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器 —— 每个请求的"看门人"
 *
 * 设计决策（先讲原理）:
 *
 * 1. 过滤器职责边界: 只解析和放行, 不拦截?
 *    这个过滤器只做一件事: 如果请求带了合法 token, 就把 LoginUser 放进 UserContext。
 *    token 缺失/无效时, 它什么都不做(不返回 401)——因为有些接口是公开的。
 *    具体接口要不要登录, 由两个机制决定:
 *    a) 拦截器校验: 对 /api/** 的接口, 如果 UserContext 是空且接口非白名单 → 401
 *    b) Controller 里调 UserContext.getRequired(): 未登录直接抛 401
 *    这种"过滤器只装填、业务决定要不要"的设计, 让公开接口和私有接口并存。
 *
 * 2. 为什么不在这里直接 401?
 *    如果过滤器直接 401, 公开接口(登录/注册/验证码/健康检查)也要在过滤器里配白名单。
 *    白名单散落在过滤器里, 和业务接口耦合, 改一个接口就要改过滤器。
 *    把判定下沉到拦截器(见 SecurityInterceptor, P3.2 或注册在 WebMvcConfig),
 *    白名单用配置管理, 过滤器保持"无脑装填"的单一职责。
 *
 * 3. 为什么 @Order(HIGHEST_PRECEDENCE + 2)?
 *    TraceIdFilter 是 +1(最先), CORS 是 HIGHEST_PRECEDENCE。
 *    JwtAuthFilter 排在 TraceIdFilter 之后(需要 traceId 已进 MDC),
 *    在任何业务逻辑之前(确保 Controller 执行时 UserContext 已就绪)。
 *
 * 4. 为什么 try-finally 清 UserContext?
 *    和 TraceIdFilter 清理 MDC 同理, 线程池复用线程,
 *    不清会导致下一个请求读到上一个用户的身份(数据串号事故)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            // 1. 从 Authorization 头取 token
            String auth = request.getHeader(CommonConst.HEADER_AUTHORIZATION);
            String token = extractToken(auth);

            // 2. 有 token 才解析; 没有 token 直接放行(公开接口场景)
            if (token != null) {
                Claims claims = jwtUtil.parse(token);
                if (claims != null) {
                    // 3. 构造 LoginUser 放入 ThreadLocal
                    @SuppressWarnings("unchecked")
                    List<String> roles = claims.get("roles", List.class);
                    LoginUser user = LoginUser.builder()
                            .userId(Long.valueOf(claims.getSubject()))
                            .username(claims.get("username", String.class))
                            .roles(roles)
                            .tenantId(Long.valueOf(String.valueOf(claims.get("tenantId"))))
                            .build();
                    UserContext.set(user);
                }
                // token 无效: 不设置 UserContext, 让拦截器/业务层决定 401 还是放行
            }

            filterChain.doFilter(request, response);
        } finally {
            // 4. 必须清理! 线程池复用, 防串号
            UserContext.clear();
        }
    }

    /**
     * 从 "Bearer xxx" 里提取 token
     * 格式不对返回 null(不会抛异常, 让调用方按无 token 处理)
     */
    private String extractToken(String auth) {
        if (auth == null || !auth.startsWith(CommonConst.BEARER_PREFIX)) {
            return null;
        }
        String token = auth.substring(CommonConst.BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
