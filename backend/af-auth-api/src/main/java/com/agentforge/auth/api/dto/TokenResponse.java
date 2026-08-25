package com.agentforge.auth.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Token 响应体 —— 登录/刷新成功后返回
 *
 * 设计决策:
 *
 * 1. 为什么 accessToken 和 refreshToken 分开两个字段而不是一个?
 *    前端需要区分两者的存储和携带方式:
 *    - accessToken: 内存 + 每次请求 Authorization 头 (不落 localStorage, 防 XSS 窃取)
 *    - refreshToken: 也可放内存, 或 httpOnly Cookie (防 JS 读取)
 *    分开字段前端才好分别处理。
 *
 * 2. 为什么同时返回 tokenType + expiresIn?
 *    tokenType="Bearer" 是 HTTP 规范要求, 前端拼头时用;
 *    expiresIn 让前端在"剩余 2 分钟"时主动调刷新接口, 而不是等 401 才刷。
 *    主动刷新 → 用户无感; 被动刷新 → 用户看到白屏闪一下。
 *
 * 3. 为什么带上 user 概要?
 *    登录成功前端马上要渲染用户信息(头像/昵称/角色),
 *    如果只给 token, 前端还要再发一次 /user/info 请求。
 *    顺手带上是"一次请求一次渲染", 减少首屏网络往返。
 *    角色列表用于前端菜单权限控制(后端下发的权限模型)。
 */
@Data
@AllArgsConstructor
public class TokenResponse {

    /** 访问令牌: 15 分钟有效, 每次请求携带 */
    private String accessToken;

    /** 刷新令牌: 7 天有效, 轮换制, 只用于 /auth/refresh */
    private String refreshToken;

    /** 令牌类型: 固定 "Bearer", HTTP 规范 */
    private String tokenType;

    /** Access Token 剩余秒数: 前端做主动刷新倒计时 */
    private long expiresIn;

    /** 用户概要 */
    private UserInfo user;

    @Data
    @AllArgsConstructor
    public static class UserInfo {
        private Long id;
        private String username;
        private String nickname;
        private String avatarUrl;
        private String email;
        private String phone;
        /** 角色编码列表: 如 ["admin", "agent_builder"], 前端据此渲染菜单 */
        private List<String> roles;
    }
}
