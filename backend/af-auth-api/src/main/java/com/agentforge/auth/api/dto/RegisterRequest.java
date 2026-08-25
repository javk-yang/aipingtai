package com.agentforge.auth.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 注册请求
 *
 * 设计决策:
 *
 * 1. 为什么注册强制要求用户名 + 密码, 邮箱/手机号二选一?
 *    用户名是账号主体(唯一且不可变), 密码是凭证。
 *    邮箱/手机是"可绑定凭证"(用于登录/找回密码), 二选一即可。
 *    都填都验, 都不填也能注册(只是以后只能用户名登录 + 无法找回密码)。
 *    这是"最小可用注册"——不强迫用户填所有字段, 降低注册门槛。
 *
 * 2. 为什么密码校验在这里, 登录时不校验?
 *    注册是密码的"出生地", 规则在这里一次性立好(复杂度 + 长度)。
 *    登录只防攻击不防弱密码。改密时复用同一套规则(见 P3.2)。
 *
 * 3. 密码复杂度为什么这么定?
 *    8-64 位 + 至少 1 字母 + 至少 1 数字: 平衡安全与易用。
 *    强制大写/小写/特殊字符全都要 = 用户记不住 → 写在便利贴上 = 更不安全。
 *    长度上限 64: BCrypt 只取前 72 字节, 超长密码会被静默截断, 必须显式限制。
 *
 * 4. 为什么用两个 @NotBlank 而不是一个 @NotBlank(groups=...)?
 *    邮箱和手机"至少一个"是跨字段约束, 单字段注解表达不了。
 *    这个校验写在 Service 层(见 AuthService.register), 用 if 判断更清晰。
 */
@Data
public class RegisterRequest {

    /** 用户名: 3-32 位, 字母开头, 可含字母/数字/下划线 */
    @NotBlank(message = "请输入用户名")
    @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9_]{2,31}$",
            message = "用户名需以字母开头, 3-32 位字母/数字/下划线")
    private String username;

    /** 密码: 8-64 位, 至少 1 字母 + 1 数字 */
    @NotBlank(message = "请输入密码")
    @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,64}$",
            message = "密码需 8-64 位, 且包含字母和数字")
    private String password;

    /** 邮箱(可选): 标准邮箱格式 */
    @Pattern(regexp = "^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$", message = "邮箱格式不正确")
    private String email;

    /** 手机号(可选): 11 位, 1 开头 */
    @Pattern(regexp = "^1\\d{10}$", message = "手机号格式不正确")
    private String phone;

    /** 邮箱验证码: 填了 email 就必须填 */
    private String emailCode;

    /** 手机验证码: 填了 phone 就必须填 */
    private String phoneCode;
}
