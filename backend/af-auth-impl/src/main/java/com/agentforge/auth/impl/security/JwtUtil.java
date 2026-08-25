package com.agentforge.auth.impl.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * JWT 工具 —— 签发/解析/校验 Access + Refresh 双 Token
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么用 jjwt 0.12.x 而不是 hutool 的 JWTUtil?
 *    jjwt 是 JWT 标准实现, 0.12 重写了 API(Jwts.builder() 链式),
 *    与最新 RFC 7519 对齐, 生态最全。Hutool 的 JWTUtil 适合快速验证, 企业级还是 jjwt。
 *    0.12 的 API 和 0.11 完全不同: builder().claims() / verifyWith() 是新写法。
 *
 * 2. Access 和 Refresh 的 claims 差异?
 *    相同: userId / username / tenantId / jti / iat / exp
 *    差异: Access 额外带 roles(每次鉴权要用, 省一次查库);
 *          Refresh 不带 roles(它只用来换新 token, 不参与鉴权), 但必带 jti(吊销追踪)。
 *
 * 3. 为什么 jti (JWT ID) 是强制字段?
 *    没有 jti, 服务端无法精确吊销某一个 token(只能按 userId 全灭)。
 *    jti 是 token 的唯一身份证:
 *    - 登出时把 jti 加黑名单 → 该 token 立即失效
 *    - 刷新轮换时把旧 jti 作废 → 防重放
 *    - 安全审计能按 jti 追踪单次签发的生命周期
 *
 * 4. 为什么解析失败返回 null 而不是抛异常?
 *    调用方(过滤器/刷新接口)要区分"token 无效"和"token 过期"做不同处理。
 *    抛异常会把控制流搞乱。这里约定: 解析成功返回 Claims, 失败返回 null,
 *    具体原因由调用方通过捕获 JwtException 的 message 区分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtUtil {

    private final JwtProperties props;

    /** HS256 密钥对象: 由 secret 字符串派生, 只初始化一次 */
    private SecretKey secretKey;

    @PostConstruct
    public void init() {
        this.secretKey = Keys.hmacShaKeyFor(
                props.getSecret().getBytes(StandardCharsets.UTF_8));
        log.info("JWT 初始化完成 | issuer={} | accessTtl={}s | refreshTtl={}s",
                props.getIssuer(), props.getAccessTokenTtl(), props.getRefreshTokenTtl());
    }

    /**
     * 签发 Access Token
     *
     * @param userId   用户 ID
     * @param username 用户名
     * @param roles    角色编码列表 (鉴权用)
     * @return JWT 字符串
     */
    public String createAccessToken(Long userId, String username, List<String> roles, Long tenantId) {
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())          // jti: 吊销追踪的身份证
                .claim("username", username)
                .claim("roles", roles)
                .claim("tenantId", tenantId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + props.getAccessTokenTtl() * 1000))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 签发 Refresh Token
     * Refresh 不带 roles(不参与鉴权), 但必须有 jti(刷新轮换/吊销用)
     */
    public String createRefreshToken(Long userId, Long tenantId) {
        return Jwts.builder()
                .issuer(props.getIssuer())
                .subject(String.valueOf(userId))
                .id(UUID.randomUUID().toString())
                .claim("tenantId", tenantId)
                .claim("tokenType", "refresh")             // 区分 token 类型, 防止把 access 当 refresh 用
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + props.getRefreshTokenTtl() * 1000))
                .signWith(secretKey)
                .compact();
    }

    /**
     * 解析并校验 JWT, 成功返回 Claims, 失败返回 null
     * 注意: 只验签名和有效期, 不验吊销状态(吊销状态由调用方查 Redis)
     */
    public Claims parse(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .requireIssuer(props.getIssuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // 签名错误 / 过期 / 格式非法 / 发行者不符 都走这里
            log.debug("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 校验 token 是否过期(供 refresh 流程判断)
     * parse 已经会校验过期并返回 null, 但如果调用方想知道"是不是过期导致的失败", 用这个
     */
    public boolean isExpired(String token) {
        try {
            Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token);
            return false;
        } catch (JwtException e) {
            return e.getMessage() != null && e.getMessage().contains("expired");
        }
    }
}
