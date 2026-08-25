package com.agentforge.auth.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 当前用户信息响应 —— GET /api/auth/me
 *
 * 设计决策:
 * 1. 为什么单独一个 me 接口而不是把用户信息塞进登录响应?
 *    登录响应的 UserInfo 是"登录那一刻"的快照(签发 token 用),
 *    me 是"随时刷新"的完整资料(侧边栏头像、权限变化、菜单渲染)。
 *    前端刷新页面后调 me 恢复登录态, 拿最新资料。
 *
 * 2. 为什么 email/phone 不带脱敏?
 *    这里返回的是"本人"的信息, 前端要展示"我的邮箱/手机号",
 *    脱敏(138****8000)反而影响编辑回显。脱敏只用于"别人的信息"场景。
 */
@Data
@AllArgsConstructor
public class UserInfoResponse {

    private Long userId;

    private String username;

    private String nickname;

    private String avatarUrl;

    private String email;

    private String phone;

    /** 角色编码: 前端菜单/功能开关靠它 */
    private List<String> roles;

    /** 权限编码: 前端按钮级权限控制 */
    private List<String> permissions;
}
