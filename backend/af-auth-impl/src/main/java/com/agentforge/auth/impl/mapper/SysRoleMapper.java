package com.agentforge.auth.impl.mapper;

import com.agentforge.auth.impl.entity.SysRole;
import com.agentforge.auth.impl.entity.SysUserRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 角色相关 Mapper —— 一个接口管两张表(角色表 + 关联表)
 *
 * 设计决策:
 *
 * 1. 为什么角色和用户角色关联放同一个 Mapper?
 *    它们是一对概念: 查角色必查关联, 写关联必写角色。
 *    拆两个 Mapper 接口会让调用方混乱(SysRoleMapper 查角色列表,
 *    SysUserRoleMapper 查用户角色), 各自半吊子。
 *    合在一起, 这个接口完整表达"用户和角色的所有关系查询"。
 *
 * 2. 为什么 @Select 直接写 SQL 而不是用 Wrapper?
 *    三表 join(用户-角色-关联)用 LambdaQueryWrapper 表达非常别扭,
 *    MyBatis-Plus 的 Wrapper 只擅长单表查询。这种 join 场景,
 *    一个 @Select 注解 + 原生 SQL, 直白且性能可控。
 *    注意 MyBatis 的 #{} 是预编译参数(防注入), 不是 ${} 拼接。
 *
 * 3. 为什么 @Insert 里用 INSERT IGNORE?
 *    sys_user_role 有 UNIQUE KEY uk_user_role (user_id, role_id),
 *    重复插入会报 DuplicateKeyException。INSERT IGNORE 直接跳过已存在的,
 *    "确保用户有某角色"的幂等操作不需要先查一次。
 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /**
     * 查用户的角色编码列表(三表 join)
     * @param userId 用户 ID
     * @return 角色编码列表, 如 ["admin", "agent_builder"]
     */
    @Select("""
            SELECT r.role_code
            FROM sys_role r
            INNER JOIN sys_user_role ur ON ur.role_id = r.id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
              AND r.deleted_at IS NULL
            ORDER BY r.sort_order
            """)
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);

    /**
     * 查用户的权限编码列表(四表 join: 用户-角色-权限)
     * 权限点去重(DISTINCT), 一个用户多个角色可能持有同一权限
     * @param userId 用户 ID
     * @return 权限编码列表, 如 ["agent:tool:call", "agent:agent:create"]
     */
    @Select("""
            SELECT DISTINCT p.perm_code
            FROM sys_role r
            INNER JOIN sys_user_role ur ON ur.role_id = r.id
            INNER JOIN sys_role_permission rp ON rp.role_id = r.id
            INNER JOIN sys_permission p ON p.id = rp.permission_id
            WHERE ur.user_id = #{userId}
              AND r.status = 1
              AND r.deleted_at IS NULL
            ORDER BY p.perm_code
            """)
    List<String> selectPermissionCodesByUserId(@Param("userId") Long userId);

    /**
     * 给用户绑定角色(幂等)
     * INSERT IGNORE: 已存在则跳过, 不报 DuplicateKeyException
     */
    @Insert("""
            INSERT IGNORE INTO sys_user_role (user_id, role_id, created_at)
            VALUES (#{userId}, #{roleId}, NOW(3))
            """)
    int bindUserRole(@Param("userId") Long userId, @Param("roleId") Long roleId);
}
