package com.agentforge.auth.impl.service;

import com.agentforge.auth.impl.mapper.SysRoleMapper;
import com.agentforge.common.security.PermissionProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限加载实现 —— 接口在 af-common, 实现在 auth-impl
 *
 * PermissionAspect(af-common) 鉴权需要查用户权限,
 * 而查库的 Mapper 在 auth-impl。通过实现 PermissionProvider,
 * 高层切面只认接口, 不依赖具体实现(依赖倒置)。
 */
@Service
@RequiredArgsConstructor
public class PermissionProviderImpl implements PermissionProvider {

    private final SysRoleMapper roleMapper;

    @Override
    public List<String> loadPermissions(Long userId) {
        return roleMapper.selectPermissionCodesByUserId(userId);
    }
}
