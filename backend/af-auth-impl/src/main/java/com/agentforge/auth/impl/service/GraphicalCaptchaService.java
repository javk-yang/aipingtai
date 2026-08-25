package com.agentforge.auth.impl.service;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import com.agentforge.auth.api.dto.CaptchaImageResponse;
import com.agentforge.common.constant.CommonConst;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 图形验证码服务 —— 登录失败超限后的"人机校验"
 *
 * 设计决策:
 * 1. 为什么用 Hutool 的 LineCaptcha 而不是自己画?
 *    线条干扰码是最经典的图形验证码方案(简单、识别率友好、足够挡脚本)。
 *    Hutool 封装了生成 + 干扰线 + 扭曲, 20 行搞定。
 *    真到需要高级人机校验(点选/滑块)再上专业服务, 接口不变。
 *
 * 2. 为什么答案存 Redis 而不存会话?
 *    会话(HttpSession)依赖单机内存, 集群部署后 Session 不共享;
 *    Redis 是所有实例共享的, 任一节点生成的验证码, 其他节点能验证。
 *
 * 3. 为什么验证也用 GETDEL?
 *    和短信验证码同理: 用一次即废, 原子取删防并发重放。
 *
 * 4. 为什么答案用 MessageDigest.isEqual 比较?
 *    恒定时间比较, 防时序攻击(细节见 CaptchaService 注释)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GraphicalCaptchaService {

    private final StringRedisTemplate redis;

    /** 有效期: 5 分钟 */
    private static final long TTL_SECONDS = 300L;

    /**
     * 生成一张图形验证码
     * @return captchaId(提交时带回) + 图片 base64
     */
    public CaptchaImageResponse generate() {
        // Hutool 线条验证码: 宽 140 / 高 48 / 4 位 / 干扰线 30 条
        LineCaptcha captcha = CaptchaUtil.createLineCaptcha(140, 48, 4, 30);

        String captchaId = UUID.randomUUID().toString().replace("-", "");
        String answer = captcha.getCode();   // 4 位答案(数字+字母)

        // 答案只存 Redis, 前端永远看不到
        redis.opsForValue().set(
                CommonConst.REDIS_KEY_IMG_CAPTCHA + captchaId,
                answer,
                TTL_SECONDS, TimeUnit.SECONDS);

        return new CaptchaImageResponse(captchaId, captcha.getImageBase64Data());
    }

    /**
     * 校验图形验证码(GETDEL 原子取删, 无论成败都作废)
     * @param captchaId 生成时返回的 ID
     * @param input     用户输入
     * @return 是否匹配; 校验失败抛 CAPTCHA_ERROR(调用方无需再判空)
     */
    public void verifyOrThrow(String captchaId, String input) {
        if (captchaId == null || input == null || input.isBlank()) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR);
        }
        String key = CommonConst.REDIS_KEY_IMG_CAPTCHA + captchaId;
        String stored = redis.opsForValue().getAndDelete(key);
        if (stored == null) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR);
        }
        // 忽略大小写(图形码字母有大小写歧义, 统一小写比较)
        boolean ok = MessageDigest.isEqual(
                stored.toLowerCase().getBytes(StandardCharsets.UTF_8),
                input.trim().toLowerCase().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR);
        }
    }
}
