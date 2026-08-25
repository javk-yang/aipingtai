package com.agentforge.common.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * TraceId 过滤器 —— 全链路追踪的入口
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么用 Filter 而不是 HandlerInterceptor?
 *    Filter 在 Servlet 层, 比 Spring MVC 的拦截器更早执行:
 *    Tomcat → [Filter] → DispatcherServlet → [Interceptor] → Controller
 *    如果用拦截器, 404 请求、静态资源请求都进不了 preHandle, 那些请求就没 traceId。
 *    Filter 挡在所有人前面, 一个都不漏。
 *
 * 2. 为什么继承 OncePerRequestFilter?
 *    普通 Filter 在 forward/include 时可能执行多次, traceId 会被重新生成。
 *    OncePerRequestFilter 保证每个请求只执行一次, 不受 forward 影响。
 *
 * 3. traceId 从哪来?
 *    优先读上游传来的 X-Trace-Id(微服务链路, 网关已经生成了),
 *    没有 → 自己生成。这样跨服务调用时 traceId 能贯穿整条链路。
 *
 * 4. 为什么放进 MDC 而不是 RequestAttribute?
 *    MDC(ThreadLocal) 是 SLF4J 的上下文, logback 的 %X{traceId} 直接读它。
 *    放 RequestAttribute 的话, 每条日志要手动 req.getAttribute("traceId") 传进去, 忘传就没有。
 *    MDC 全局生效: 这个线程内所有日志自动带 traceId。
 *
 * 5. 为什么 @Order(Ordered.HIGHEST_PRECEDENCE + 1)?
 *    必须最先执行——在 CharacterEncodingFilter 之后(它优先级最高), 在所有业务 Filter 之前。
 *    如果业务 Filter 里打了日志, traceId 还没进 MDC, 那条日志就丢了。
 *
 * 6. 为什么 try-finally 清理 MDC?
 *    Tomcat 用线程池, 线程复用。如果不清理, 下一个请求复用这个线程时,
 *    会读到上一个请求的 traceId, 日志串了——这是生产事故的经典来源。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_KEY = "traceId";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        try {
            // 1. 优先读上游传来的 traceId(网关/上游微服务), 没有 → 生成一个新的
            String traceId = request.getHeader(TRACE_ID_HEADER);
            if (traceId == null || traceId.isBlank()) {
                // 32位无连字符 UUID: 足够唯一, 而且不带 '-' 方便日志 grep
                traceId = java.util.UUID.randomUUID().toString().replace("-", "");
            }

            // 2. 写入 MDC: 本线程所有日志自动带 traceId
            MDC.put(TRACE_ID_KEY, traceId);

            // 3. 回写响应头: 前端拿到 traceId, 出 bug 截图就能定位
            response.setHeader(TRACE_ID_HEADER, traceId);

            // 4. 执行后续链路
            filterChain.doFilter(request, response);
        } finally {
            // 5. 必须清理! Tomcat 线程复用, 不清理会串链路
            MDC.remove(TRACE_ID_KEY);
        }
    }
}
