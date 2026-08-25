package com.agentforge.common.constant;

/**
 * 全局常量 —— 跨模块共享的静态值
 *
 * 设计决策:
 * 1. 为什么用 class 而不是 enum?
 *    这些值是"配置常量"不是"可枚举的选择集", 用 class + static final 最直接。
 *    用 enum 要 .getValue() 多一层, 对纯常量来说没必要。
 *
 * 2. 为什么是 public?
 *    常量天生该被访问, private + getter 是过度封装。
 *    这些值编译期就固定, 不存在被运行时篡改的风险。
 */
public final class CommonConst {

    private CommonConst() {}

    // ========== Redis Key 前缀 (和 P1 Redis 设计文档对齐) ==========

    /** 验证码: af:captcha:phone:{phone} / af:captcha:email:{email} */
    public static final String REDIS_KEY_CAPTCHA = "af:captcha:";

    /** 登录限流: af:rate:login:{ip} */
    public static final String REDIS_KEY_RATE_LOGIN = "af:rate:login:";

    /** 用户权限缓存: af:perm:{userId} (P3.2, 懒加载 + 30min TTL) */
    public static final String REDIS_KEY_PERM = "af:perm:";

    /** 图形验证码: af:captcha:img:{captchaId} (P3.2, 5min TTL) */
    public static final String REDIS_KEY_IMG_CAPTCHA = "af:captcha:img:";

    /** 接口限流: af:rate:api:{userId}:{uri} */
    public static final String REDIS_KEY_RATE_API = "af:rate:api:";

    /** 分布式锁: af:lock:{bizType}:{bizId} */
    public static final String REDIS_KEY_LOCK = "af:lock:";

    /** 会话缓存: af:session:conv:{conversationId} */
    public static final String REDIS_KEY_SESSION = "af:session:conv:";

    /** Agent 检查点: af:checkpoint:{conversationId} */
    public static final String REDIS_KEY_CHECKPOINT = "af:checkpoint:";

    /** 配额计数: af:quota:{userId}:{date} */
    public static final String REDIS_KEY_QUOTA = "af:quota:";

    /** JWT 黑名单(主动登出): af:blacklist:token:{jti} */
    public static final String REDIS_KEY_TOKEN_BLACKLIST = "af:blacklist:token:";

    /**
     * Refresh Token 白名单: af:jwt:refresh:{jti}
     * 注意: 是"白名单"不是"黑名单"——key 存在 = 该 refresh token 有效。
     * 刷新轮换时删除旧 jti = 旧 token 失效; 登出删除 = 断掉续期通道。
     * 用"存在即有效"而不是"存在即失效"的好处:
     * 无 token 记录 = 从未签发, 语义与"签发后才记录"天然对齐, 不需要预热。
     */
    public static final String REDIS_KEY_REFRESH_WHITELIST = "af:jwt:refresh:";

    // ========== JWT 相关 ==========

    /** JWT 的 jti (JWT ID) claim key */
    public static final String JWT_CLAIM_JTI = "jti";

    /** JWT 的用户 ID claim key */
    public static final String JWT_CLAIM_USER_ID = "userId";

    /** JWT 的用户名 claim key */
    public static final String JWT_CLAIM_USERNAME = "username";

    /** JWT 的角色列表 claim key */
    public static final String JWT_CLAIM_ROLES = "roles";

    /** JWT 的租户 ID claim key */
    public static final String JWT_CLAIM_TENANT_ID = "tenantId";

    // ========== 通用 ==========

    /** 默认租户 ID (单租户期间固定为 1) */
    public static final long DEFAULT_TENANT_ID = 1L;

    /** 请求头: TraceId */
    public static final String HEADER_TRACE_ID = "X-Trace-Id";

    /** 请求头: Authorization */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** 请求头: Bearer 前缀 */
    public static final String BEARER_PREFIX = "Bearer ";

    /** MDC 中 traceId 的 key */
    public static final String MDC_TRACE_ID = "traceId";
}
