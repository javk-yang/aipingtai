package com.agentforge.common.security;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Set;

/**
 * 当前登录用户模型 —— JWT 解析后的用户快照
 *
 * 为什么住在 af-common 而不是 af-auth-impl?
 * 所有业务模块(会话/Agent/计费)都要拿"当前用户", 而它们只能依赖 af-common。
 * 如果放在 auth-impl, session-impl 依赖 auth-impl 会形成模块环(铁律第 3 条)。
 * 这是 P3.2 做的架构修正: 把跨模块的身份载体沉到公共底座。
 *
 * 设计决策:
 * 1. 为什么叫 LoginUser 而不是 User?
 *    它承载的是"一次请求里的登录态", 不是完整的用户实体。
 *    只放鉴权需要的最小字段: 谁(userId) + 什么角色(roles) + 什么权限(permissions) + 哪个租户。
 *
 * 2. 为什么 permissions 用 Set 而不是 List?
 *    鉴权判断是 contains() 高频查询, Set 是 O(1), List 是 O(n)。
 *    而且权限天然不重复, Set 语义更准。
 *
 * 3. 为什么 permissions 默认 null(懒加载)?
 *    大多数接口没有权限注解, 不需要权限列表。
 *    首次真正鉴权时才从 Redis/DB 加载(见 PermissionAspect), 零额外开销。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser {

    private Long userId;

    private String username;

    /** 角色编码列表: 如 ["admin", "agent_builder"] */
    private List<String> roles;

    /**
     * 权限编码集合: 如 {"agent:tool:call", "agent:agent:create"}
     * null = 未加载(懒加载), 由 PermissionAspect 首次鉴权时填充
     */
    private Set<String> permissions;

    private Long tenantId;

    /** 是否管理员: 所有权限判定里优先级最高, admin 不需要逐个权限点 */
    public boolean isAdmin() {
        return roles != null && roles.contains("admin");
    }
}
