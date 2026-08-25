package com.agentforge.auth.impl.service;

import com.agentforge.auth.api.dto.LoginRequest;
import com.agentforge.auth.api.dto.RefreshTokenRequest;
import com.agentforge.auth.api.dto.RegisterRequest;
import com.agentforge.auth.api.dto.TokenResponse;
import com.agentforge.auth.api.dto.UserInfoResponse;
import com.agentforge.auth.impl.entity.SysRole;
import com.agentforge.auth.impl.entity.SysUser;
import com.agentforge.auth.impl.mapper.SysRoleMapper;
import com.agentforge.auth.impl.mapper.SysUserMapper;
import com.agentforge.auth.impl.security.JwtProperties;
import com.agentforge.auth.impl.security.JwtUtil;
import com.agentforge.common.audit.AuditService;
import com.agentforge.common.constant.CommonConst;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.agentforge.common.security.LoginUser;
import com.agentforge.common.security.UserContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 认证服务 —— 注册 / 登录 / 登出 / 刷新, 认证模块的心脏
 *
 * 设计决策（先讲原理）:
 *
 * 1. 登录凭证智能识别
 *    前端只传一个 identifier, 服务端识别类型:
 *    含 @ → 邮箱; 11 位 1 开头纯数字 → 手机号; 其他 → 用户名
 *    识别错类型(比如把用户名当邮箱查)返回"不存在", 会泄露账号注册情况(枚举攻击)。
 *    所以所有失败统一报 LOGIN_FAILED("用户名或密码错误"), 不区分"用户不存在"还是"密码错"。
 *
 * 2. 登录风控: 持久状态(MySQL) + 短时频控(Redis) 两层
 *    - MySQL: login_fail_count + locked_until。连续失败 5 次锁 15 分钟,
 *      之后每次失败锁定时间指数退避(15min → 30min → 1h → 2h...), 封顶 24h。
 *      为什么指数退避? 攻击者脚本越锁越慢, 最后一天只能试几次, 撞库成本爆炸。
 *    - Redis: 短时频控(IP 维度)在 P3.2 的滑动窗口做, 这里是账号维度持久状态。
 *
 * 3. 为什么登录成功要重置 loginFailCount?
 *    不重置的话, 用户之前累计了 4 次失败, 这次成功了, 下次输错 1 次就锁 15 分钟。
 *    重置 = "连续失败"的正确语义: 成功一次, 连续失败计数归零。
 *
 * 4. Refresh Token 为什么存 Redis 白名单 + 轮换?
 *    见 TokenResponse 注释。这里补充: 白名单 key 是 af:jwt:refresh:{jti},
 *    value 是 userId, TTL = refreshTokenTtl。
 *    刷新时: 校验 Redis 里有这个 jti → 删除旧 jti → 签发新对。
 *    旧 refresh token 在刷新后被删 = 轮换生效, 盗用者用旧 token 刷不出新 token。
 *
 * 5. 为什么 register 用 @Transactional?
 *    注册要两步: 插入用户 + 绑定默认角色。两步间任何一步失败,
 *    事务回滚, 不会出现"用户建了但角色没绑"的孤儿数据。
 *    @Transactional 默认只回滚 RuntimeException——BizException 正是, 天然匹配。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final JwtUtil jwtUtil;
    private final JwtProperties jwtProps;
    private final StringRedisTemplate redis;
    private final CaptchaService captchaService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final LoginRateLimiter loginRateLimiter;             // P3.2: IP 维度短时频控
    private final GraphicalCaptchaService graphicalCaptchaService; // P3.2: 图形验证码
    private final AuditService auditService;                       // P13: 审计埋点

    /** 登录失败锁定阈值 */
    private static final int MAX_LOGIN_FAILS = 5;
    /** 首次锁定时长(分钟) */
    private static final long FIRST_LOCK_MINUTES = 15;
    /** 锁定封顶(小时) */
    private static final long MAX_LOCK_HOURS = 24;

    // ==================== 注册 ====================

    /**
     * 注册新用户
     * 邮箱/手机号"至少一个"的跨字段校验在这里做(注解表达不了)
     */
    @Transactional
    public void register(RegisterRequest req) {
        // 1. 邮箱/手机号至少提供一个(否则无法找回密码)
        if (req.getEmail() == null && req.getPhone() == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "邮箱和手机号至少填写一个");
        }

        // 2. 唯一性校验(联合租户, 单租户期恒为 1)
        checkUnique(req);

        // 3. 验证码校验: 填了邮箱就验邮箱码, 填了手机就验手机码
        if (req.getEmail() != null && !captchaService.verify(
                CaptchaService.SCENE_REGISTER, req.getEmail(), req.getEmailCode())) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR, "邮箱验证码错误或已过期");
        }
        if (req.getPhone() != null && !captchaService.verify(
                CaptchaService.SCENE_REGISTER, req.getPhone(), req.getPhoneCode())) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR, "手机验证码错误或已过期");
        }

        // 4. BCrypt 加密密码(每次随机盐, 相同密码不同哈希)
        String hash = passwordEncoder.encode(req.getPassword());

        // 5. 组装实体, 插入(createdAt/updatedAt 由自动填充处理)
        SysUser user = new SysUser();
        user.setTenantId(CommonConst.DEFAULT_TENANT_ID);
        user.setUsername(req.getUsername());
        user.setEmail(req.getEmail());
        user.setPhone(req.getPhone());
        user.setPasswordHash(hash);
        user.setNickname(req.getUsername());       // 默认昵称 = 用户名
        user.setStatus(1);                          // 1正常
        user.setEmailVerified(req.getEmail() != null ? 1 : 0);
        user.setPhoneVerified(req.getPhone() != null ? 1 : 0);
        userMapper.insert(user);

        // 6. 绑定默认角色: agent_builder(普通用户)
        assignDefaultRole(user.getId());

        // 6.5 P13 审计: 注册成功
        auditService.recordExplicit(user.getTenantId(), user.getId(),
                "user.register", "user", String.valueOf(user.getId()),
                Map.of("username", req.getUsername()), 1);

        log.info("用户注册成功 | userId={} | username={}", user.getId(), req.getUsername());
    }

    /**
     * 唯一性校验: 用户名/邮箱/手机号 三个唯一键逐一检查
     * 注意: 邮箱/手机号为空时跳过(MySQL 的 UNIQUE 索引对 NULL 不生效, 允许多个 NULL)
     */
    private void checkUnique(RegisterRequest req) {
        Long count;
        count = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, req.getUsername()));
        if (count > 0) throw new BizException(ErrorCode.USERNAME_EXISTS);

        if (req.getEmail() != null) {
            count = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getEmail, req.getEmail()));
            if (count > 0) throw new BizException(ErrorCode.EMAIL_EXISTS);
        }
        if (req.getPhone() != null) {
            count = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getPhone, req.getPhone()));
            if (count > 0) throw new BizException(ErrorCode.PHONE_EXISTS);
        }
    }

    /** 绑定默认角色: agent_builder(普通用户)。角色不存在则跳过(不阻塞注册) */
    private void assignDefaultRole(Long userId) {
        SysRole role = roleMapper.selectOne(Wrappers.<SysRole>lambdaQuery()
                .eq(SysRole::getRoleCode, "agent_builder")
                .last("LIMIT 1"));
        if (role != null) {
            roleMapper.bindUserRole(userId, role.getId());
        } else {
            log.warn("默认角色 agent_builder 不存在, 跳过角色绑定 | userId={}", userId);
        }
    }

    // ==================== 登录 ====================

    /**
     * 登录: 识别凭证 → 查用户 → 锁状态检查 → 验密码 → 风控 → 发 Token
     */
    public TokenResponse login(LoginRequest req, HttpServletRequest httpReq) {
        String ip = getClientIp(httpReq);

        // 0. IP 风控第一层: 60 秒内失败超上限, 直接拒绝(429)
        if (loginRateLimiter.isRejected(ip)) {
            throw new BizException(ErrorCode.LOGIN_TOO_MANY_FAILS);
        }
        // 0.5 IP 风控第二层: 失败超阈值后, 必须通过图形验证码(人机校验)
        if (loginRateLimiter.needCaptcha(ip)) {
            graphicalCaptchaService.verifyOrThrow(req.getCaptchaId(), req.getCaptchaCode());
        }

        // 1. 识别凭证类型并查用户
        SysUser user = findByIdentifier(req.getIdentifier());

        // 2. 统一失败提示, 防枚举攻击
        if (user == null) {
            loginRateLimiter.recordFailure(ip);
            auditService.record("user.login", "user", null,
                    Map.of("identifier", maskIdentifier(req.getIdentifier()), "ip", ip, "reason", "user_not_found"),
                    0);   // P13 审计: 登录失败
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }

        // 3. 状态检查: 停用 / 锁定
        checkUserStatus(user);

        // 4. 密码校验(BCrypt)
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            handleLoginFail(user);
            loginRateLimiter.recordFailure(ip);   // IP 计数 +1
            auditService.recordExplicit(user.getTenantId(), user.getId(),
                    "user.login", "user", String.valueOf(user.getId()),
                    Map.of("ip", ip, "reason", "bad_password"), 0);   // P13 审计: 密码错误
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }

        // 5. 密码对了但之前锁过 → 解锁 + 清零(防御: 锁定期过了但计数还在)
        if (user.getStatus() == 3 || user.getLockedUntil() != null) {
            user.setStatus(1);
            user.setLockedUntil(null);
        }
        user.setLoginFailCount(0);

        // 6. 更新最后登录信息
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(ip);
        userMapper.updateById(user);

        // 6.5 登录成功: 清除 IP 失败计数(连续失败的语义, 成功一次归零)
        loginRateLimiter.clear(ip);

        // 7. 查角色 → 签发双 Token
        List<String> roles = roleMapper.selectRoleCodesByUserId(user.getId());
        TokenResponse resp = issueTokens(user, roles);

        // 7.5 P13 审计: 登录成功
        auditService.recordExplicit(user.getTenantId(), user.getId(),
                "user.login", "user", String.valueOf(user.getId()),
                Map.of("username", user.getUsername(), "ip", ip), 1);
        return resp;
    }

    /**
     * 凭证智能识别: 邮箱 / 手机号 / 用户名
     * 识别规则:
     * - 含 @ → 邮箱
     * - 11 位 1 开头纯数字 → 手机号
     * - 其他 → 用户名
     */
    private SysUser findByIdentifier(String identifier) {
        String idf = identifier.trim();
        if (idf.contains("@")) {
            return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getEmail, idf).last("LIMIT 1"));
        }
        if (idf.matches("^1\\d{10}$")) {
            return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                    .eq(SysUser::getPhone, idf).last("LIMIT 1"));
        }
        return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, idf).last("LIMIT 1"));
    }

    /** 状态检查: 2停用 / 3锁定(锁定中提前返回) */
    private void checkUserStatus(SysUser user) {
        if (user.getStatus() != null && user.getStatus() == 2) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (user.getStatus() != null && user.getStatus() == 3) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
        // 锁定时间未到也视为锁定(状态可能没来得及改成 3)
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(LocalDateTime.now())) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }
    }

    /**
     * 登录失败处理: 累计失败计数 + 指数退避锁定
     *
     * 退避公式: 锁定时长 = min(15min * 2^(failCount - 5), 24h)
     * 第 5 次失败 → 15min; 第 6 次 → 30min; 第 7 次 → 1h; ... 封顶 24h
     */
    private void handleLoginFail(SysUser user) {
        int fails = (user.getLoginFailCount() == null ? 0 : user.getLoginFailCount()) + 1;
        user.setLoginFailCount(fails);

        if (fails >= MAX_LOGIN_FAILS) {
            long minutes = FIRST_LOCK_MINUTES * (1L << Math.min(fails - MAX_LOGIN_FAILS, 5));
            minutes = Math.min(minutes, MAX_LOCK_HOURS * 60);
            user.setLockedUntil(LocalDateTime.now().plusMinutes(minutes));
            user.setStatus(3);  // 锁定状态
            log.warn("账号锁定 | userId={} | fails={} | lockMinutes={}", user.getId(), fails, minutes);
        }
        userMapper.updateById(user);
    }

    // ==================== 刷新 / 登出 ====================

    /**
     * 刷新 Token: 校验 refresh token + Redis 白名单 → 轮换
     *
     * 轮换流程:
     * 1. 解析 refresh token(签名+过期校验)
     * 2. 检查 jti 在不在 Redis 白名单(在 = 有效, 不在 = 已吊销/已轮换/伪造)
     * 3. 删除旧 jti(轮换生效: 旧 token 立即失效)
     * 4. 重新查用户角色(角色可能变了), 签发新 token 对
     */
    public TokenResponse refresh(RefreshTokenRequest req) {
        var claims = jwtUtil.parse(req.getRefreshToken());
        if (claims == null) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }
        // 防混淆: 必须是 refresh 类型 token(防止拿 access token 来刷)
        if (!"refresh".equals(claims.get("tokenType", String.class))) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        Long userId = Long.valueOf(claims.getSubject());
        String jti = claims.getId();

        // Redis 白名单校验: 存在则删除(轮换), 不存在 → token 已用/已吊销
        // key 结构 af:jwt:refresh:{userId}:{jti}: 前段是用户维度,
        // 密码重置/封号时能按 userId 前缀批量吊销(见 PasswordService.revokeAllSessions)
        String whitelistKey = CommonConst.REDIS_KEY_REFRESH_WHITELIST + userId + ":" + jti;
        Boolean deleted = redis.delete(whitelistKey);
        // delete 返回 true = key 存在且已删除(轮换生效); false = key 不存在 → 无效 token
        if (!Boolean.TRUE.equals(deleted)) {
            throw new BizException(ErrorCode.REFRESH_TOKEN_EXPIRED);
        }

        // 查用户 + 角色, 重签新对
        SysUser user = userMapper.selectById(userId);
        if (user == null || user.getStatus() == 2) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        return issueTokens(user, roles);
    }

    /**
     * 登出: 吊销 refresh token(access token 短命, 等它自己过期)
     * 为什么 access 不主动吊销? 15 分钟 TTL 已经足够短, 为它维护黑名单不值。
     * 真正长命的是 refresh token, 登出吊销它 = 断掉续期通道, 会话自然结束。
     */
    public void logout(String refreshToken) {
        var claims = jwtUtil.parse(refreshToken);
        if (claims != null && "refresh".equals(claims.get("tokenType", String.class))) {
            String jti = claims.getId();
            String key = CommonConst.REDIS_KEY_REFRESH_WHITELIST + claims.getSubject() + ":" + jti;
            redis.delete(key);
            log.info("用户登出 | userId={}", claims.getSubject());
        }
        // token 解析失败: 忽略(登出是幂等操作, 已失效的 token 登出无副作用)
    }

    // ==================== 当前用户信息 ====================

    /**
     * 查询当前用户完整信息(me 接口)
     * 前端刷新页面后调它恢复登录态: 拿最新资料 + 角色 + 权限。
     * 权限从这里返回给前端做按钮级控制, 与后端的 PermissionAspect 同源。
     */
    public UserInfoResponse getUserInfo(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        List<String> perms = roleMapper.selectPermissionCodesByUserId(userId);
        return new UserInfoResponse(
                user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatarUrl(), user.getEmail(), user.getPhone(), roles, perms);
    }

    // ==================== 私有工具 ====================

    /**
     * 签发双 Token: 存 refresh 白名单 → 组装响应
     */
    private TokenResponse issueTokens(SysUser user, List<String> roles) {
        String accessToken = jwtUtil.createAccessToken(
                user.getId(), user.getUsername(), roles, user.getTenantId());
        String refreshToken = jwtUtil.createRefreshToken(user.getId(), user.getTenantId());

        // refresh token 进 Redis 白名单: key=af:jwt:refresh:{userId}:{jti}, value=userId, TTL=7天
        // key 前段带 userId: 密码重置/封号时按前缀批量吊销(见 PasswordService)
        var refreshClaims = jwtUtil.parse(refreshToken);
        if (refreshClaims != null) {
            redis.opsForValue().set(
                    CommonConst.REDIS_KEY_REFRESH_WHITELIST + user.getId() + ":" + refreshClaims.getId(),
                    String.valueOf(user.getId()),
                    jwtProps.getRefreshTokenTtl(), TimeUnit.SECONDS);
        }

        TokenResponse.UserInfo userInfo = new TokenResponse.UserInfo(
                user.getId(), user.getUsername(), user.getNickname(),
                user.getAvatarUrl(), user.getEmail(), user.getPhone(), roles);

        return new TokenResponse(
                accessToken, refreshToken, "Bearer",
                jwtProps.getAccessTokenTtl(), userInfo);
    }

    /** 获取客户端真实 IP(处理 X-Forwarded-For, Nginx 反代场景) */
    private String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            // XFF 格式: client, proxy1, proxy2 → 取第一个(真实客户端)
            return ip.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /**
     * 凭证脱敏(审计留痕时不落明文账号, 防日志泄露):
     * 邮箱 → a***@domain.com; 手机 → 138****8000; 用户名 → 首尾保留 + 中间 ***
     */
    private String maskIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        String idf = identifier.trim();
        if (idf.contains("@")) {
            int at = idf.indexOf('@');
            String local = idf.substring(0, at);
            String domain = idf.substring(at);
            return maskLocal(local) + domain;
        }
        return maskLocal(idf);
    }

    private String maskLocal(String s) {
        if (s.length() <= 2) {
            return "*".repeat(s.length());
        }
        return s.charAt(0) + "*".repeat(Math.min(s.length() - 2, 3)) + s.charAt(s.length() - 1);
    }
}
