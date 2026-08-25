package com.agentforge.common.security;

import java.util.List;

/**
 * 权限加载 SPI —— 接口在 af-common, 实现留在业务模块
 *
 * 为什么这样拆?
 * PermissionAspect(af-common) 做鉴权时, 权限数据在 MySQL 里,
 * 而查 DB 的 Mapper 属于 af-auth-impl。af-common 不能反向依赖 auth-impl。
 * 所以定义这个接口: 谁能查权限, 谁实现它。
 * af-auth-impl 提供实现(用 SysRoleMapper 查), Spring 自动注入。
 *
 * 这就是"依赖倒置"的教科书案例:
 * 高层(鉴权切面)依赖抽象(接口), 不依赖底层实现(Mapper)。
 */
public interface PermissionProvider {

    /** 加载用户拥有的全部权限编码(直接查库, 不带缓存) */
    List<String> loadPermissions(Long userId);
}
