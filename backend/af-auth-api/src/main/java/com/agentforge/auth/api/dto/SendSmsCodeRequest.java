package com.agentforge.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送短信验证码请求
 *
 * 设计决策:
 * 1. 为什么短信和邮件拆两个接口而不是一个 /code/send?
 *    前端表单场景不同: 手机号注册/找回走短信, 邮箱注册/找回走邮件。
 *    拆开让前端不用传"type 字段", 接口语义自解释, 校验也更精确。
 *
 * 2. scene 为什么是必填?
 *    同一手机号在"注册"和"找回密码"两个场景各有一个验证码 key
 *    (af:captcha:register:{phone} vs af:captcha:reset:{phone})。
 *    场景隔离: 注册页拿的验证码不能用于找回密码——防跨场景重放。
 */
@Data
public class SendSmsCodeRequest {

    /** 场景: register / reset / bind */
    @NotBlank(message = "缺少场景标识")
    @Pattern(regexp = "^(register|reset|bind)$", message = "场景标识不合法")
    private String scene;

    /** 手机号: 11 位, 1 开头 */
    @NotBlank(message = "请输入手机号")
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;
}
