package com.agentforge.auth.impl.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置 —— 绑定 application.yml 的 agentforge.jwt.*
 *
 * 设计决策:
 *
 * 1. 为什么用 @ConfigurationProperties 而不是 @Value?
 *    一组相关的配置用一个 POJO 绑定, 语义清晰、类型安全、可整体校验。
 *    @Value 逐个注入, 5 个字段写 5 行, 且没有编译期类型检查。
 *    SpringBoot 还会为 @ConfigurationProperties 提供 IDE 提示和元数据校验。
 *
 * 2. 为什么 secret 用环境变量兜底默认值?
 *    生产环境 secret 必须来自环境变量/密钥管理服务, 不能写死在 yml 提交到 Git。
 *    本地开发用默认值能跑, 部署时注入 JWT_SECRET 覆盖。
 *
 * 3. 为什么 HS256 密钥要 32 字节以上?
 *    HS256 是 HMAC-SHA256, 密钥强度决定安全性。
 *    少于 32 字节的密钥可被暴力枚举。默认值 48 字符, 生产要求 64 字节随机串。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agentforge.jwt")
public class JwtProperties {

    /** 签名密钥: HS256, 生产必须 >= 32 字节, 通过环境变量注入 */
    private String secret = "agentforge-dev-secret-key-0123456789abcdef0123456789";

    /** Access Token 有效期(秒): 默认 15 分钟 */
    private long accessTokenTtl = 900;

    /** Refresh Token 有效期(秒): 默认 7 天 */
    private long refreshTokenTtl = 604800;

    /** 签发者标识: 用于多环境/多服务间区分 token 来源 */
    private String issuer = "agentforge";
}
