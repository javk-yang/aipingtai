package com.agentforge.auth.impl.service;

import com.agentforge.auth.api.dto.ResetPasswordRequest;
import com.agentforge.auth.impl.entity.SysUser;
import com.agentforge.auth.impl.mapper.SysUserMapper;
import com.agentforge.common.constant.CommonConst;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 密码服务 —— 找回密码的完整闭环
 *
 * 设计决策(先讲原理):
 *
 * 1. 找回密码为什么是"两步"而不是一步?
 *    第一步发验证码(证明"你持有该手机/邮箱"), 第二步验证码+新密码一起提交。
 *    两步之间的验证码存在 Redis(5 分钟有效), 任何人拿到你的手机就能重置——
 *    所以"验证码 = 身份凭证", 它和密码同等敏感, 所有防重放手段(60 秒冷却/GETDEL)都生效。
 *
 * 2. 为什么重置成功后必须吊销该用户所有 refresh token?
 *    密码可能被攻击者重置(盗号场景), 重置成功后旧会话必须全部失效,
 *    否则攻击者改完密码还能用旧 refresh token 继续续期——吊销等于"断掉所有后门"。
 *
 * 3. 为什么 refresh 白名单 key 结构是 af:jwt:refresh:{userId}:{jti}?
 *    jti 是随机值, 如果 key 只含 jti, 想"吊销某用户的所有 token"只能全库扫描。
 *    把 userId 放 key 前段, 吊销 = 删除 af:jwt:refresh:{userId}:* 前缀, 一次搞定。
 *    这就是 Redis key 设计铁律: key 结构要按"最可能的批量操作维度"组织。
 *
 * 4. 为什么用 redis.keys() 而不是 SCAN?
 *    keys 在单机 Redis 上可用, 但生产大 key 库会阻塞(全库遍历)。
 *    生产应换 SCAN 游标分批删。这里 keys 是开发期简化, 注释里标注了替换方案。
 *    (先有正确性, 再有规模优化。)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordService {

    private final SysUserMapper userMapper;
    private final CaptchaService captchaService;
    private final StringRedisTemplate redis;
    private final BCryptPasswordEncoder passwordEncoder;

    /**
     * 找回密码: 验证码 + 新密码一起提交
     * (发送验证码直接复用 CaptchaService.sendCode + scene=reset,
     *  前端调 /code/sms 或 /code/email 即可, 不单独开接口)
     */
    @Transactional
    public void resetPassword(ResetPasswordRequest req) {
        // 1. 验证验证码(GETDEL 原子取删, 无论成败一次性作废)
        boolean ok = captchaService.verify(CaptchaService.SCENE_RESET, req.getAccount(), req.getCode());
        if (!ok) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR);
        }

        // 2. 找用户: account 可能是邮箱或手机号
        SysUser user = findByAccount(req.getAccount());
        if (user == null) {
            // 不泄露"该账号是否存在": 验证码已过, 报业务失败即可
            throw new BizException(ErrorCode.USER_NOT_FOUND, "账号不存在");
        }

        // 3. 更新密码(BCrypt 新盐) + 清空登录风控状态
        user.setPasswordHash(passwordEncoder.encode(req.getNewPassword()));
        user.setLoginFailCount(0);
        user.setLockedUntil(null);
        if (user.getStatus() != null && user.getStatus() == 3) {
            user.setStatus(1);   // 解锁
        }
        userMapper.updateById(user);

        // 4. 吊销该用户所有 refresh token(旧会话全部失效)
        revokeAllSessions(user.getId());

        log.info("密码重置成功 | userId={}", user.getId());
    }

    /** 吊销用户全部 refresh token: 删除 af:jwt:refresh:{userId}:* 前缀 */
    private void revokeAllSessions(Long userId) {
        String prefix = CommonConst.REDIS_KEY_REFRESH_WHITELIST + userId + ":";
        Set<String> keys = redis.keys(prefix + "*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
            log.info("密码重置后吊销会话 | userId={} | revoked={}", userId, keys.size());
        }
        // 生产规模: keys 换 SCAN 游标分批删(keys 会阻塞大库)
    }

    /** 按邮箱或手机号查用户 */
    private SysUser findByAccount(String account) {
        return userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(account.contains("@"), SysUser::getEmail, account)
                .eq(!account.contains("@"), SysUser::getPhone, account)
                .last("LIMIT 1"));
    }
}
