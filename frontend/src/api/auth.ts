/**
 * 认证接口 —— 与后端 AuthController 端点一一对应
 * 返回类型全部来自 @/types（契约层），路径以 /api/auth 开头
 */
import { http } from '@/utils/request'
import type {
  CaptchaImageResponse,
  LoginRequest,
  RegisterRequest,
  ResetPasswordRequest,
  SendEmailCodeRequest,
  SendSmsCodeRequest,
  TokenResponse,
  UserInfoResponse,
} from '@/types'

/** POST /api/auth/login —— 三种凭证合一登录 */
export function login(data: LoginRequest) {
  return http.post<TokenResponse>('/auth/login', data)
}

/** POST /api/auth/register —— 注册 */
export function register(data: RegisterRequest) {
  return http.post<TokenResponse>('/auth/register', data)
}

/** POST /api/auth/refresh —— 刷新双 token（由请求层内部调用，一般业务不直接用） */
export function refresh(data: { refreshToken: string }) {
  return http.post<TokenResponse>('/auth/refresh', data)
}

/** POST /api/auth/logout —— 登出（吊销 refresh token） */
export function logout() {
  return http.post<null>('/auth/logout')
}

/** GET /api/auth/me —— 当前用户完整信息（角色 + 权限码） */
export function getMe() {
  return http.get<UserInfoResponse>('/auth/me')
}

/** GET /api/auth/captcha/image —— 图形验证码（登录失败超限后强制） */
export function getCaptchaImage() {
  return http.get<CaptchaImageResponse>('/auth/captcha/image')
}

/** POST /api/auth/code/sms —— 发送短信验证码 */
export function sendSmsCode(data: SendSmsCodeRequest) {
  return http.post<null>('/auth/code/sms', data)
}

/** POST /api/auth/code/email —— 发送邮件验证码 */
export function sendEmailCode(data: SendEmailCodeRequest) {
  return http.post<null>('/auth/code/email', data)
}

/** POST /api/auth/password/reset —— 重置密码（验证码 + 新密码同提交） */
export function resetPassword(data: ResetPasswordRequest) {
  return http.post<null>('/auth/password/reset', data)
}
