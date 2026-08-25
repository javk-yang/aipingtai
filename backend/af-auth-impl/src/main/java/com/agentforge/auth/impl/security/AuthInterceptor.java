package com.agentforge.auth.impl.security;

import com.agentforge.common.api.R;
import com.agentforge.common.exception.ErrorCode;
import com.agentforge.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * 认证拦截器 —— /api/** 的登录门卫(白名单之外的接口必须登录)
 *
 * 设计决策(先讲原理):
 *
 * 1. 为什么用拦截器而不是过滤器做登录校验?
 *    - 过滤器(Servlet 层)拿不到"这个请求要调哪个 Controller 方法",
 *      做白名单只能按 URL 字符串硬匹配, 而且静态资源也过过滤器。
 *    - 拦截器(SpringMVC 层)能拿到 HandlerMethod,
 *      还可以做"注解驱动"的精细化控制(比如 @Public 标注的接口自动放行)。
 *    分工: JwtAuthFilter 只做"解析 token 装填 UserContext"(无脑),
 *           AuthInterceptor 做"要不要登录"的判定(有脑)。
 *
 * 2. 为什么白名单是精确匹配而不是 /api/auth/** 前缀?
 *    /api/auth 下同时有公开接口(login/register/captcha)和私有接口(me/logout)。
 *    前缀放行会把 me 也漏出去。白名单列的是"确切路径", 一失一得都显式可见。
 *
 * 3. 为什么未登录返回 401 + R 格式而不是 302 跳登录页?
 *    这是前后端分离架构: 前端拿 401 后由路由守卫统一跳登录页。
 *    后端不关心页面跳转, 只负责告诉前端"这个请求没身份"。
 *
 * 4. 为什么 401 响应体还是 R 格式(带 traceId)?
 *    前端 axios 拦截器统一按 R 格式解析错误, 保持一致,
 *    出问题时用户截图里的 traceId 能直接定位日志(铁律第 4 条)。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper;

    private final SecurityProperties securityProperties;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        // 1. 非 Controller 方法(如静态资源/错误页)直接放行
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // 2. 白名单直接放行(登录/注册/验证码/健康检查等公开接口)
        if (securityProperties.isWhitelisted(request.getRequestURI())) {
            return true;
        }

        // 3. 其余 /api/** 必须已登录
        if (UserContext.get() == null) {
            writeUnauthorized(response);
            return false;
        }
        return true;
    }

    /** 写 401: R 格式 JSON, 前端 axios 拦截器统一处理跳登录 */
    private void writeUnauthorized(HttpServletResponse response) throws Exception {
        response.setStatus(ErrorCode.UNAUTHORIZED.getHttpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
                R.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMsg())));
    }
}
