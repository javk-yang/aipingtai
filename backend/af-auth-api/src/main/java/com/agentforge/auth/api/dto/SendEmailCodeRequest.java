package com.agentforge.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送邮箱验证码请求
 * scene 语义同 SendSmsCodeRequest(跨场景隔离, 防重放)
 */
@Data
public class SendEmailCodeRequest {

    /** 场景: register / reset / bind */
    @NotBlank(message = "缺少场景标识")
    @Pattern(regexp = "^(register|reset|bind)$", message = "场景标识不合法")
    private String scene;

    /** 邮箱 */
    @NotBlank(message = "请输入邮箱")
    @Pattern(regexp = "^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$", message = "邮箱格式不正确")
    private String email;
}
