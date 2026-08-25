package com.agentforge.common.exception;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 全局错误码枚举 —— 平台所有业务异常的唯一真相源
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么用枚举而不是常量类?
 *    常量类是散落的 int, 写代码时 IDE 不提示有哪些码可用, 新人随便编一个 5007 也不知道跟 5006 重复了。
 *    枚举有命名空间: ErrorCode.USER_NOT_FOUND 一眼看出含义, 而且 switch 能穷举检查。
 *
 * 2. 为什么 0 是成功码放在这里?
 *    让"成功"也成为一个 ErrorCode, 所有地方只认这一个枚举, 不需要记 0 是成功但不在枚举里。
 *
 * 3. 码段怎么分?
 *    0       = 成功
 *    1xxx    = 通用参数/校验类 (400 Bad Request 语义)
 *    2xxx    = 认证授权类    (401/403 语义)
 *    3xxx    = 业务逻辑类    (用户/会话/Agent 各占一段)
 *    5xxx    = 系统内部错误  (500 语义)
 *    分段的好处: 前端拿到 code, 看千位就知道是哪类错误, 做不同拦截策略。
 *
 * 4. 为什么每个码带 HttpStatus?
 *    GlobalExceptionHandler 用它翻译成对应 HTTP 状态码。
 *    参数错误返回 400, 未认证返回 401, 服务器错误返回 500——
 *    前端拦截器看 HTTP 码就能区分"重试"还是"重新登录"还是"报修"。
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // ========== 成功 ==========
    SUCCESS(0, "成功", 200),

    // ========== 1xxx 通用参数/校验 ==========
    PARAM_MISSING(1001, "缺少必填参数", 400),
    PARAM_INVALID(1002, "参数格式不正确", 400),
    PARAM_VALIDATE_FAILED(1003, "参数校验未通过", 400),
    JSON_PARSE_ERROR(1004, "JSON 解析失败", 400),
    REQUEST_METHOD_NOT_SUPPORTED(1005, "不支持的请求方法", 405),
    TOO_MANY_REQUESTS(1006, "请求过于频繁, 请稍后再试", 429),

    // ========== 2xxx 认证授权 ==========
    UNAUTHORIZED(2001, "未登录或登录已过期", 401),
    TOKEN_EXPIRED(2002, "令牌已过期, 请重新登录", 401),
    TOKEN_INVALID(2003, "令牌无效", 401),
    REFRESH_TOKEN_EXPIRED(2004, "刷新令牌已过期, 请重新登录", 401),
    ACCESS_DENIED(2005, "无权限访问该资源", 403),
    ACCOUNT_LOCKED(2006, "账号已被锁定, 请联系管理员", 403),
    ACCOUNT_DISABLED(2007, "账号已被禁用", 403),
    CAPTCHA_ERROR(2008, "验证码错误或已过期", 400),
    LOGIN_FAILED(2009, "用户名或密码错误", 401),
    LOGIN_TOO_MANY_FAILS(2010, "登录失败次数过多, 请 15 分钟后再试", 429),

    // ========== 3xxx 业务逻辑 ==========
    // --- 用户 31xx ---
    USER_NOT_FOUND(3101, "用户不存在", 404),
    USERNAME_EXISTS(3102, "用户名已被注册", 409),
    PHONE_EXISTS(3103, "手机号已被注册", 409),
    EMAIL_EXISTS(3104, "邮箱已被注册", 409),
    OLD_PASSWORD_ERROR(3105, "原密码不正确", 400),
    // --- 会话 32xx ---
    CONVERSATION_NOT_FOUND(3201, "会话不存在", 404),
    MESSAGE_NOT_FOUND(3202, "消息不存在", 404),
    CONVERSATION_ACCESS_DENIED(3204, "无权访问该会话", 403),
    CONVERSATION_LIMIT_EXCEEDED(3203, "会话数量超过上限", 403),
    // --- Agent 33xx ---
    AGENT_NOT_FOUND(3301, "智能体不存在", 404),
    AGENT_DISABLED(3302, "智能体已被禁用", 403),
    AGENT_INVOKE_ERROR(3303, "智能体调用失败", 502),
    TOOL_NOT_FOUND(3304, "工具不存在", 404),
    TOOL_EXECUTION_ERROR(3305, "工具执行失败", 500),
    SKILL_NOT_FOUND(3306, "技能不存在", 404),
    SKILL_EXECUTION_ERROR(3307, "技能执行失败", 500),
    SKILL_CONTENT_TOO_LARGE(3308, "技能内容超过 8KB 上限", 400),
    SKILL_FILE_READ_ERROR(3309, "技能文件读取失败", 500),
    SKILL_PACKAGE_INVALID(3315, "技能包格式不正确", 400),
    SKILL_PACKAGE_TOO_LARGE(3316, "技能包超过大小限制", 400),
    SKILL_PACKAGE_UNSAFE(3317, "技能包包含不安全内容", 400),
    SKILL_PACKAGE_MISSING_MANIFEST(3318, "技能包缺少唯一的 SKILL.md", 400),
    SKILL_PACKAGE_DUPLICATE(3319, "技能编码已存在", 409),
    KNOWLEDGE_DOC_NOT_FOUND(3310, "知识库文档不存在", 404),
    KNOWLEDGE_ENGINE_ERROR(3311, "知识库引擎调用失败", 502),
    MODEL_NOT_FOUND(3312, "模型配置不存在", 404),
    MODEL_DISABLED(3314, "模型配置已禁用", 403),
    MODEL_TEST_FAILED(3313, "模型连通性测试失败", 502),
    // --- 配额 34xx ---
    QUOTA_EXCEEDED(3401, "额度已用尽, 请联系管理员", 403),

    // ========== 5xxx 系统内部 ==========
    INTERNAL_ERROR(5000, "系统内部错误", 500),
    SERVICE_UNAVAILABLE(5001, "服务暂不可用", 503),
    GATEWAY_TIMEOUT(5002, "上游服务超时", 504),
    REMOTE_CALL_ERROR(5003, "远程调用失败", 502),
    UNKNOWN_ERROR(9999, "未知异常", 500);

    /** 业务码: 对外暴露, 前端和日志都用它定位问题 */
    private final int code;

    /** 提示消息: 直接展示给用户看, 不含技术细节(不暴露堆栈给前端) */
    private final String msg;

    /** 对应的 HTTP 状态码: GlobalExceptionHandler 用它设置 response status */
    private final int httpStatus;

    /**
     * 根据 code 反查枚举, 全局异常处理器里用到
     * 如果没找到, 返回 UNKNOWN_ERROR (防御性编程)
     */
    public static ErrorCode fromCode(int code) {
        for (ErrorCode e : values()) {
            if (e.code == code) return e;
        }
        return UNKNOWN_ERROR;
    }
}
