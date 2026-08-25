package com.agentforge.auth.impl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 认证配置 —— BCrypt 密码编码器
 *
 * 设计决策:
 *
 * 1. 为什么 BCrypt cost 用默认 10 而不是更大?
 *    cost=10 单次验证约 80ms, 对用户无感, 对攻击者已是天堑(GPU 每秒只能试几万个)。
 *    cost 每 +1, 时间翻倍。cost=12 约 320ms, 用户体验开始有感知(登录慢)。
 *    企业级选择: cost=10 起步, 硬件升级后调 12, 老哈希兼容(BCrypt 从哈希串里读 cost)。
 *
 * 2. 为什么用 @Bean 而不是 new BCryptPasswordEncoder() 散落各处?
 *    单例 + 统一配置: 后续要调 cost 只改这一处。
 *    而且 BCryptPasswordEncoder 内部维护缓存, 单例更省内存。
 *
 * 3. 为什么不用加盐 MD5 / SHA-256?
 *    MD5/SHA 是快速哈希, 专为速度设计, GPU 并行破解极快。
 *    BCrypt 是刻意慢的(Blowfish 迭代 2^cost 次), 暴力破解成本暴涨百万倍。
 *    盐随机生成内嵌哈希串, 无彩虹表, 相同密码不同哈希。
 */
@Configuration
public class AuthConfig {

    /**
     * BCrypt 密码编码器
     * BCryptPasswordEncoder 默认 strength=10
     * 底层使用 SecureRandom 生成 16 字节盐, 每次加密结果不同
     */
    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
