package com.agentforge.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新 Token 请求
 *
 * 设计决策:
 *
 * 1. 为什么刷新只带 refreshToken 一个字段?
 *    Access Token 已经过期了, 带了也没法验(签名能验, 但过期的语义就是"该换了")。
 *    刷新接口的职责: 用 refreshToken 换新的 token 对。
 *    服务端从 refreshToken 里解出 userId, 不需要客户端再传。
 *
 * 2. 为什么是独立接口而不是挂在登录接口上?
 *    登录是"首次认证", 刷新是"续期"。两者频率、失败处理、审计级别都不同:
 *    刷新失败不该触发登录风控(锁账号), 只是静默让前端跳登录页。
 *    分开才方便各自做监控。
 */
@Data
public class RefreshTokenRequest {

    @NotBlank(message = "缺少刷新令牌")
    private String refreshToken;
}
