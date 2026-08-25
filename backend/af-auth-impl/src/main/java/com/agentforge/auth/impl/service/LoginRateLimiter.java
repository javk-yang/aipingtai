package com.agentforge.auth.impl.service;

import com.agentforge.common.constant.CommonConst;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 登录限流器 —— IP 维度短时频控 + 图形验证码触发
 *
 * 设计决策:
 * 1. 为什么用固定窗口(INCR + EXPIRE)而不是 ZSET 滑动窗口?
 *    滑动窗口精确到每次请求时间戳, 固定窗口只有"窗口内计数"。
 *    登录防撞库场景, 60 秒的粒度误差对攻击者意义不大(晚 59 秒无感),
 *    而固定窗口一次 INCR 搞定、内存恒定, 性能好一个量级。
 *    需要精确 QPS 控制的场景(接口限流)才用滑动窗口, 那是 P13 的事。
 *
 * 2. 为什么计数和"是否触发图形码"都在这一个 key 上?
 *    af:rate:login:{ip} 存"60 秒内失败次数"。
 *    次数 >= 3 → 前端必须带图形验证码(脚本识别图形码的成本远高于重试)。
 *    次数 >= 10 → 直接拒绝(429), 该 IP 这 60 秒内别想再试。
 *    一个计数器, 两个阈值, 分层防御。
 *
 * 3. 为什么登录成功要 clear?
 *    计数是"连续失败"的语义, 成功一次就清零,
 *    否则用户正常登录后, 之前的失败记录还挂着, 下次输错一次就弹图形码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginRateLimiter {

    private final StringRedisTemplate redis;

    /** 窗口: 60 秒 */
    private static final long WINDOW_SECONDS = 60L;

    /** 达到该次数 → 必须通过图形验证码 */
    private static final int CAPTCHA_THRESHOLD = 3;

    /** 达到该次数 → 直接拒绝登录 */
    private static final int REJECT_THRESHOLD = 10;

    /**
     * 记录一次登录失败(INCR + 首次设 TTL)
     * @return 当前窗口内累计失败次数
     */
    public long recordFailure(String ip) {
        String key = CommonConst.REDIS_KEY_RATE_LOGIN + ip;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 首次计数: 设置窗口过期时间(INCR 不会自动带 TTL)
            redis.expire(key, WINDOW_SECONDS, TimeUnit.SECONDS);
        }
        return count == null ? 0 : count;
    }

    /** 该 IP 是否被直接拒绝(次数超上限) */
    public boolean isRejected(String ip) {
        return count(ip) >= REJECT_THRESHOLD;
    }

    /** 该 IP 是否必须过图形验证码 */
    public boolean needCaptcha(String ip) {
        return count(ip) >= CAPTCHA_THRESHOLD;
    }

    /** 登录成功: 清除计数 */
    public void clear(String ip) {
        redis.delete(CommonConst.REDIS_KEY_RATE_LOGIN + ip);
    }

    /** 当前窗口失败次数 */
    private long count(String ip) {
        String v = redis.opsForValue().get(CommonConst.REDIS_KEY_RATE_LOGIN + ip);
        return v == null ? 0 : Long.parseLong(v);
    }
}
