package com.agentforge.auth.impl.controller;

import com.agentforge.auth.api.dto.CaptchaImageResponse;
import com.agentforge.auth.api.dto.LoginRequest;
import com.agentforge.auth.api.dto.RefreshTokenRequest;
import com.agentforge.auth.api.dto.RegisterRequest;
import com.agentforge.auth.api.dto.ResetPasswordRequest;
import com.agentforge.auth.api.dto.SendEmailCodeRequest;
import com.agentforge.auth.api.dto.SendSmsCodeRequest;
import com.agentforge.auth.api.dto.TokenResponse;
import com.agentforge.auth.api.dto.UserInfoResponse;
import com.agentforge.auth.impl.service.AuthService;
import com.agentforge.auth.impl.service.CaptchaService;
import com.agentforge.auth.impl.service.GraphicalCaptchaService;
import com.agentforge.auth.impl.service.PasswordService;
import com.agentforge.common.api.R;
import com.agentforge.common.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器 —— /api/auth/**
 *
 * 设计决策:
 *
 * 1. 为什么这些接口返回 R<T> 而不是裸对象?
 *    全平台统一响应格式(R 里带 traceId), 前端拦截器统一处理。
 *
 * 2. 为什么 @Valid 在 @RequestBody 前?
 *    Spring 参数校验的约定, 校验失败抛 MethodArgumentNotValidException
 *    → GlobalExceptionHandler 转成字段级错误。
 *
 * 3. 公开接口 vs 私有接口怎么区分?
 *    这里的公开接口(login/register/refresh/captcha/code/password)由
 *    SecurityProperties 白名单管理; me/logout 需要登录,
 *    由 AuthInterceptor 拦截(未登录 401)。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final CaptchaService captchaService;
    private final GraphicalCaptchaService graphicalCaptchaService;
    private final PasswordService passwordService;

    /** 注册 */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody RegisterRequest req) {
        authService.register(req);
        return R.ok();
    }

    /** 登录: 返回 accessToken + refreshToken + 用户概要 */
    @PostMapping("/login")
    public R<TokenResponse> login(@Valid @RequestBody LoginRequest req,
                                  HttpServletRequest httpReq) {
        return R.ok(authService.login(req, httpReq));
    }

    /** 刷新 Token: 轮换机制, 旧 refresh token 立即失效 */
    @PostMapping("/refresh")
    public R<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest req) {
        return R.ok(authService.refresh(req));
    }

    /** 登出: 吊销 refresh token(需要登录) */
    @PostMapping("/logout")
    public R<Void> logout(@RequestBody RefreshTokenRequest req) {
        authService.logout(req.getRefreshToken());
        return R.ok();
    }

    /** 当前用户信息: 前端刷新页面后恢复登录态(需要登录) */
    @GetMapping("/me")
    public R<UserInfoResponse> me() {
        return R.ok(authService.getUserInfo(UserContext.getUserId()));
    }

    /** 图形验证码: 登录失败超限后强制(P3.2) */
    @GetMapping("/captcha/image")
    public R<CaptchaImageResponse> captchaImage() {
        return R.ok(graphicalCaptchaService.generate());
    }

    /** 发送短信验证码(P3.2): 注册/找回密码前置步骤 */
    @PostMapping("/code/sms")
    public R<Void> sendSmsCode(@Valid @RequestBody SendSmsCodeRequest req) {
        captchaService.sendCode(req.getScene(), req.getPhone(),
                "手机 " + req.getPhone().replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2"));
        return R.ok();
    }

    /** 发送邮箱验证码(P3.2) */
    @PostMapping("/code/email")
    public R<Void> sendEmailCode(@Valid @RequestBody SendEmailCodeRequest req) {
        captchaService.sendCode(req.getScene(), req.getEmail(),
                "邮箱 " + maskEmail(req.getEmail()));
        return R.ok();
    }

    /** 找回密码: 发送验证码复用 /code/sms|email(scene=reset), 这里只做重置 */
    @PostMapping("/password/reset")
    public R<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordService.resetPassword(req);
        return R.ok();
    }

    /** 健康检查(供前端判断后端是否就绪) */
    @GetMapping("/health")
    public R<String> health() {
        return R.ok("auth-up");
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 1) return email;
        return email.charAt(0) + "***" + email.substring(at);
    }
}
