package com.agentforge.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 重置密码请求 —— 找回密码流程的最后一步
 *
 * 流程: 忘记密码 → POST /code/sms|email (scene=reset) → 收到验证码
 *      → POST /password/reset 提交本请求 → 密码重置 + 全端会话吊销
 *
 * 设计决策:
 * 1. 为什么重置要验证码而不是"邮箱链接点击即重置"?
 *    短信/邮箱验证码是"你持有该手机/邮箱"的证明, 一次有效。
 *    链接方案(如很多平台的 reset link)有个坑: 邮件里的链接可能被转发,
 *    且"点击即重置"容易被自动化脚本批量触发。
 *    验证码 + 新密码同请求提交, 一个往返完成, 体验不比链接差, 安全性更可控。
 *
 * 2. 为什么新密码复用注册的复杂度规则?
 *    密码规则必须全平台一致(注册/重置/改密同一套),
 *    否则会出现"注册时强密码, 重置时弱密码"的漏洞。
 */
@Data
public class ResetPasswordRequest {

    /** 账号: 邮箱 或 手机号(找回时用的哪种, 这里就传哪种) */
    @NotBlank(message = "请输入账号")
    private String account;

    /** 验证码: 上一步收到的短信/邮箱验证码 */
    @NotBlank(message = "请输入验证码")
    private String code;

    /** 新密码: 与注册同规则, 8-64 位含字母和数字 */
    @NotBlank(message = "请输入新密码")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
            message = "密码需 8-64 位, 且包含字母和数字")
    private String newPassword;
}
