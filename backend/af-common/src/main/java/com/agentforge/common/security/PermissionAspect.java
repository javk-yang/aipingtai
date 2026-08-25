package com.agentforge.common.security;

import com.agentforge.common.constant.CommonConst;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 权限切面 —— @RequirePermission 注解的鉴权执行器
 *
 * 鉴权流程:
 * 1. 未登录 → 401
 * 2. admin 角色 → 直接放行(不查权限, 管理员拥有一切)
 * 3. 权限懒加载: LoginUser.permissions 为 null 时, 从 Redis 读,
 *    Redis 没有则查 DB 并回填 Redis(缓存 30 分钟)
 * 4. 权限编码不在集合里 → 403
 *
 * 设计决策:
 * 1. 为什么权限走 Redis 缓存而不塞进 JWT?
 *    - JWT 会变大: 权限几十条时, 每个请求的 Authorization 头膨胀
 *    - 权限变更实时性: 塞 JWT 里, 管理员收回权限要等 token 过期(15分钟)才生效
 *    Redis 缓存: key=af:perm:{userId}, value=逗号分隔权限编码, TTL=30min
 *    权限变更时删 key, 下一个请求立即读到新权限。
 *
 * 2. 为什么懒加载?
 *    大多数接口没有 @RequirePermission, 不需要权限列表。
 *    只有首次真正鉴权时才查 Redis/DB, 零额外开销。
 *
 * 3. 为什么 @Around 而不是 @Before?
 *    鉴权通过后要执行原方法(@Before 做不到"放行"), @Around 持有整个调用链。
 *    同时把权限回填到 LoginUser, 同一次请求后续多次鉴权不重复查。
 *
 * 4. 为什么 Redis 读不到要回源 DB 而不是直接 403?
 *    Redis 可能因过期/清缓存/重启丢数据, 直接 403 会把合法用户挡在门外。
 *    DB 是唯一真相源, 回源后回填缓存, 下次命中。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final StringRedisTemplate redis;

    /**
     * ObjectProvider: Spring 的"可选延迟依赖"。
     * PermissionProvider 的实现(在 auth-impl)可能不在容器里
     * (比如只跑 session 模块的测试), 构造器直接注入会启动失败。
     * ObjectProvider 允许"没有就返回 null", 切面降级为拒绝。
     */
    private final ObjectProvider<PermissionProvider> provider;

    /** 权限缓存 TTL: 30 分钟。权限变更后删 key 立即生效, 30 分钟是兜底 */
    private static final Duration PERM_CACHE_TTL = Duration.ofMinutes(30);

    @Around("@annotation(com.agentforge.common.security.RequirePermission)")
    public Object checkPermission(ProceedingJoinPoint pjp) throws Throwable {
        // 1. 未登录直接 401
        LoginUser user = UserContext.get();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        // 2. admin 拥有全部权限, 直接放行(跳过查库, 最热路径)
        if (user.isAdmin()) {
            return pjp.proceed();
        }

        // 3. 取出注解上的权限编码
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        RequirePermission rp = method.getAnnotation(RequirePermission.class);
        if (rp == null) {
            // 注解在类上(罕见), 退化到从类上取
            rp = pjp.getTarget().getClass().getAnnotation(RequirePermission.class);
        }
        if (rp == null) {
            return pjp.proceed();
        }

        // 4. 懒加载权限集合
        Set<String> perms = user.getPermissions();
        if (perms == null) {
            perms = loadPermissions(user.getUserId());
            user.setPermissions(perms);   // 回填, 同请求内后续鉴权零开销
        }

        // 5. 权限判定
        if (!perms.contains(rp.value())) {
            log.warn("权限不足 | userId={} | need={} | has={}",
                    user.getUserId(), rp.value(), perms);
            String msg = StringUtils.hasText(rp.message())
                    ? rp.message() : ErrorCode.ACCESS_DENIED.getMsg();
            throw new BizException(ErrorCode.ACCESS_DENIED, msg);
        }

        return pjp.proceed();
    }

    /** 权限加载: Redis 缓存 → 回源 DB → 回填缓存 */
    private Set<String> loadPermissions(Long userId) {
        String key = CommonConst.REDIS_KEY_PERM + userId;

        // 1. 先读 Redis
        String cached = redis.opsForValue().get(key);
        if (cached != null && !cached.isEmpty()) {
            return new HashSet<>(Arrays.asList(cached.split(",")));
        }

        // 2. 回源 DB: 查 PermissionProvider 实现(af-auth-impl 提供的)
        PermissionProvider provider = this.provider.getIfAvailable();
        if (provider == null) {
            log.error("PermissionProvider 实现未注册, 鉴权降级为拒绝 | userId={}", userId);
            return Set.of();
        }
        Set<String> perms = new HashSet<>(provider.loadPermissions(userId));

        // 3. 回填缓存(空权限也缓存, 防穿透; 权限变更删 key 即可)
        redis.opsForValue().set(key, String.join(",", perms), PERM_CACHE_TTL);
        return perms;
    }
}
