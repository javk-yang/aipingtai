package com.agentforge.auth.impl.service;

import com.agentforge.common.constant.CommonConst;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 验证码服务 —— 手机/邮箱验证码的统一入口
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么用 Redis 存验证码而不是 MySQL?
 *    验证码是"一次性短命凭证": 5 分钟过期 + 用一次即废。
 *    MySQL 存它 = 每个验证码一行 + 定时任务清理过期行 + 查询都要走网络。
 *    Redis 天然 TTL 过期, 内存读写微秒级, 而且 P1 的 GETDEL 原子取删就是为它设计的。
 *
 * 2. 为什么发送前检查冷却, 冷却 key 和验证码 key 分开?
 *    - 验证码 key: af:captcha:{scene}:{target}   (5 分钟 TTL, 存验证码本身)
 *    - 冷却 key:   af:captcha:limit:{target}     (60 秒 TTL, 只做频率限制)
 *    分开的原因是 TTL 不同: 验证码 5 分钟, 冷却 60 秒。
 *    如果共用 key, 验证码过期 = 冷却也过期, 攻击者每 5 分钟能刷一次, 冷却失效。
 *
 * 3. 为什么验证用 GETDEL?
 *    验证码验证成功 = 必须立即作废(防重放)。
 *    GET + DELETE 两步有间隙: 攻击者可以并发重放, 两步之间代码还有效。
 *    GETDEL 是 Redis 原子命令, 取和删一步完成, 重放攻击在第一个请求就被干掉。
 *
 * 4. 为什么比较用 MessageDigest.isEqual 而不是 equals?
 *    equals 是"短路径比较": 第一位不同立刻返回 false, 攻击者可测出"第一位对不对"。
 *    isEqual 是恒定时间比较, 无论对错耗时相同, 抵御时序攻击。
 *    对 6 位验证码影响有限(有次数限制), 但企业级习惯要养。
 *
 * 5. 为什么验证失败不区分"过期"和"错误"?
 *    GETDEL 取到 null 可能是过期, 也可能是从没发过或已用过。
 *    统一提示"验证码错误或已过期"——不泄露"这个手机号是否发过验证码"的信息。
 *    账号枚举攻击就是这么一点点挖出来的。
 *
 * 6. 验证码生成用什么随机数?
 *    ThreadLocalRandom 足够(6 位数字), 不要用 Math.random()。
 *    Math.random() 是 0-1 的 double 乘 1000000 后取整, 有分布偏差, 且不是加密安全。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaptchaService {

    private final StringRedisTemplate redis;

    /**
     * 发送通道(接口注入, 开发环境是 LogSmsSender/LogEmailSender,
     * 生产切换真实通道只改配置, 这里零改动 —— P3.2 接入)
     */
    private final SmsSender smsSender;
    private final EmailSender emailSender;

    /** 验证码有效期: 5 分钟 */
    private static final long CAPTCHA_TTL_SECONDS = 300L;

    /** 发送冷却: 60 秒 */
    private static final long COOLDOWN_SECONDS = 60L;

    /** 验证码长度: 6 位 */
    private static final int CODE_LENGTH = 6;

    /** 场景: 注册 / 找回密码 / 更换绑定 */
    public static final String SCENE_REGISTER = "register";
    public static final String SCENE_RESET = "reset";
    public static final String SCENE_BIND = "bind";

    /**
     * 生成并"发送"验证码(开发环境打印到日志, 生产环境由 P3.2 的短信/邮件 Provider 接管)
     *
     * @param scene  场景: register / reset / bind
     * @param target 目标: 手机号 或 邮箱
     * @param subject 发送对象描述(仅日志用): "手机 138****8000" 或 "邮箱 u***@qq.com"
     */
    public void sendCode(String scene, String target, String subject) {
        // 1. 冷却检查: 60 秒内不能重复发
        String cooldownKey = CommonConst.REDIS_KEY_CAPTCHA + "limit:" + target;
        Boolean firstSend = redis.opsForValue()
                .setIfAbsent(cooldownKey, "1", COOLDOWN_SECONDS, TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(firstSend)) {
            // 冷却 key 已存在 → 60 秒内发过
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "验证码发送过于频繁, 请 60 秒后再试");
        }
        // setIfAbsent 成功但后续生成失败时, 删掉冷却 key, 不误伤
        try {
            // 2. 生成 6 位随机码
            String code = generateCode();

            // 3. 走真实通道发送: 手机号走短信, 其他(邮箱)走邮件
            if (target.matches("^1\\d{10}$")) {
                smsSender.send(target, "【AgentForge】您的验证码是 " + code
                        + ", 5 分钟内有效。请勿泄露给他人。");
            } else {
                emailSender.send(target, "AgentForge 账号验证",
                        "您的验证码是 " + code + ", 5 分钟内有效。如非本人操作请忽略。");
            }
            log.info("[验证码] 场景={} | 目标={} | 验证码={}", scene, subject, code);

            // 4. 存 Redis: 5 分钟 TTL
            String captchaKey = CommonConst.REDIS_KEY_CAPTCHA + scene + ":" + target;
            redis.opsForValue().set(captchaKey, code, CAPTCHA_TTL_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            redis.delete(cooldownKey);  // 失败要回滚冷却, 否则用户 60 秒内不能重试
            throw e;
        }
    }

    /**
     * 验证验证码 —— GETDEL 原子取删, 无论成败验证码都作废
     *
     * @param scene  场景
     * @param target 目标(手机号/邮箱)
     * @param input  用户输入的验证码
     * @return 验证码是否匹配
     */
    public boolean verify(String scene, String target, String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String key = CommonConst.REDIS_KEY_CAPTCHA + scene + ":" + target;
        // GETDEL: 取 + 删一步完成, 防重放
        String stored = redis.opsForValue().getAndDelete(key);
        if (stored == null) {
            // 过期 / 从未发送 / 已用过 → 统一返回失败, 不泄露状态
            return false;
        }
        // 恒定时间比较, 防时序攻击
        return MessageDigest.isEqual(
                stored.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * 生成 6 位随机数字验证码
     * 100000-999999: 不用 000000 开头的, 避免前端输入歧义
     */
    private String generateCode() {
        return String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1000000));
    }
}
