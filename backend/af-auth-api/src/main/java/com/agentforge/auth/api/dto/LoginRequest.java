package com.agentforge.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 登录请求 —— 支持三种登录凭证: 用户名 / 邮箱 / 手机号
 *
 * 设计决策:
 *
 * 1. 为什么一个接口支持三种凭证而不是三个接口?
 *    登录入口的本质是"验证身份", 凭证类型只是前置解析差异。
 *    三个接口会让前端维护三套表单 + 三套错误处理, 而校验逻辑 90% 相同。
 *    identifier 字段让前端传什么都行, 后端做智能识别(见 AuthService)。
 *
 * 2. 为什么加 @Pattern 限制?
 *    防注入和超长输入: 登录接口是攻击者的第一目标,
 *    超长字符串进数据库 LIKE 查询会拖垮索引, 特殊字符是注入尝试的探测。
 *    限制在"合法输入的最小超集", 不误伤正常用户。
 *
 * 3. 为什么不校验密码复杂度?
 *    登录时密码可能来自老用户(规则改了之后注册的), 复杂度校验只应在注册/改密时做。
 *    登录只校验非空 + 长度上限, 防的是"攻击性输入", 不是"弱密码"。
 */
@Data
public class LoginRequest {

    /** 登录凭证: 用户名 或 邮箱 或 手机号 */
    @NotBlank(message = "请输入账号")
    @Size(max = 128, message = "账号长度不能超过 128 位")
    private String identifier;

    /** 密码: 明文传输, HTTPS 保证传输安全 */
    @NotBlank(message = "请输入密码")
    @Size(max = 64, message = "密码长度不能超过 64 位")
    private String password;

    /** 图形验证码 ID (登录失败次数超限时必填, 前端先调 /captcha/image 获取) */
    private String captchaId;

    /** 图形验证码答案 */
    private String captchaCode;
}
