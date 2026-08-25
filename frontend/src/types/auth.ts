/**
 * 认证域契约 —— 与后端 af-auth-api 的 DTO 一一对应
 * 每个接口的前端类型必须与 Java DTO 字段完全一致
 */

/** 图形验证码响应（CaptchaImageResponse）
 *  imageBase64 形如 data:image/png;base64,xxx，可直接放 <img src>
 */
export interface CaptchaImageResponse {
  captchaId: string
  imageBase64: string
}

/** 发送短信验证码请求（SendSmsCodeRequest）
 *  scene 场景隔离：register / reset / bind，防跨场景重放
 */
export interface SendSmsCodeRequest {
  scene: 'register' | 'reset' | 'bind'
  phone: string
}

/** 发送邮件验证码请求（SendEmailCodeRequest） */
export interface SendEmailCodeRequest {
  scene: 'register' | 'reset' | 'bind'
  email: string
}

/** 登录请求（LoginRequest）：identifier 三种凭证合一 */
export interface LoginRequest {
  identifier: string
  password: string
  /** 图形验证码 ID：登录失败超限后必填 */
  captchaId?: string
  /** 图形验证码答案 */
  captchaCode?: string
}

/** 注册请求（RegisterRequest）：密码 8-64 位含字母+数字，邮箱/手机二选一 */
export interface RegisterRequest {
  username: string
  password: string
  email?: string
  phone?: string
  emailCode?: string
  phoneCode?: string
}

/** 刷新令牌请求（RefreshTokenRequest） */
export interface RefreshTokenRequest {
  refreshToken: string
}

/** 重置密码请求（ResetPasswordRequest）：验证码 + 新密码同提交 */
export interface ResetPasswordRequest {
  account: string
  code: string
  newPassword: string
}

/** 用户概要（TokenResponse.UserInfo）：登录成功顺手带上的快照 */
export interface TokenUserInfo {
  id: number
  username: string
  nickname: string
  avatarUrl: string | null
  email: string | null
  phone: string | null
  roles: string[]
}

/** Token 响应体（TokenResponse）：双 Token + 用户概要 */
export interface TokenResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  /** Access Token 剩余秒数：前端做主动刷新倒计时 */
  expiresIn: number
  user: TokenUserInfo
}

/** 当前用户完整信息（UserInfoResponse）：GET /api/auth/me
 *  permissions 是按钮级权限码，前端 v-perm 指令消费
 */
export interface UserInfoResponse {
  userId: number
  username: string
  nickname: string
  avatarUrl: string | null
  email: string | null
  phone: string | null
  roles: string[]
  permissions: string[]
}
