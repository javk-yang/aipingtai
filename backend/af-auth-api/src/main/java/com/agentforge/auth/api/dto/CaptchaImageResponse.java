package com.agentforge.auth.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 图形验证码响应
 *
 * 设计决策:
 * 1. 为什么返回 captchaId + 图片 base64, 而不是直接返回答案?
 *    答案永远只存 Redis, 前端拿 captchaId 作为"这张图的凭证",
 *    提交登录时带 captchaId + 用户输入, 后端从 Redis 取答案比对。
 *    返回图片 base64: data:image/png;base64,xxx, 前端直接放 <img src>。
 *
 * 2. 为什么 captchaId 用 UUID?
 *    它是 Redis key 的一部分(af:captcha:img:{captchaId}),
 *    必须不可预测(防止攻击者猜出别人的验证码 key), UUID 天然满足。
 */
@Data
@AllArgsConstructor
public class CaptchaImageResponse {

    /** 图形验证码 ID: 提交登录时原样带回 */
    private String captchaId;

    /** 图片 base64(data:image/png;base64,xxx), 前端直接渲染 */
    private String imageBase64;
}
