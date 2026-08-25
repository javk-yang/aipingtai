package com.agentforge.common.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 权限注解 —— 声明式鉴权, 标注在 Controller 方法上
 *
 * 用法:
 * <pre>
 * &#64;RequirePermission("agent:tool:call")
 * public R<Void> callTool(...) { ... }
 * </pre>
 *
 * 设计决策:
 * 1. 为什么用自定义注解 + AOP, 而不是 Spring Security 的 @PreAuthorize?
 *    我们没引 Spring Security 框架(太重), @PreAuthorize 是它的注解。
 *    自研注解 30 行搞定, 语义完全够, 且不绑定框架。
 *    P14 若上 Spring Security, 注解保留, 只换切面实现。
 *
 * 2. 为什么权限编码用 "资源:动作" 格式(如 agent:tool:call)?
 *    和 sys_permission 表的 resource + action 字段一一对应,
 *    前端菜单、后端注解、数据库权限点三处对同一套编码,
 *    避免"前端叫 tool:call, 后端叫 tool_execute"的对不上。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** 权限编码, 如 "agent:tool:call" */
    String value();

    /** 未授权时的提示(覆盖默认的 ACCESS_DENIED 消息) */
    String message() default "";
}
