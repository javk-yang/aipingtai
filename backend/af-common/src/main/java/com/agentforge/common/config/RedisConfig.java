package com.agentforge.common.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 配置 —— 重写序列化, 拒绝 JDK 默认序列化
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么必须重写序列化?
 *    SpringBoot 默认用 JdkSerializationRedisSerializer:
 *    - key 变成 \xac\xed\x00\x05t\x00\x06my-key 乱码
 *    - value 变成 Java 类名 + 序列化二进制
 *    - redis-cli 查不了、MONITOR 看不懂、Python Agent 完全读不了
 *    重写后:
 *    - key 是纯文本, redis-cli 直接读
 *    - value 是 JSON, 任何语言都能解析
 *    这是跨语言协作的前提(Java 写 checkpoint, Python Agent 要读)
 *
 * 2. 为什么 key 用 StringRedisSerializer?
 *    Redis key 都是字符串, 不需要序列化对象。String 序列化 = 原样存储, 最小体积。
 *
 * 3. 为什么 value 用 Jackson JSON 而不是 Fastjson?
 *    Fastjson 有历史漏洞(autoType), Spring 生态默认 Jackson, 安全审计更容易过。
 *    而且 Jackson 的 JavaTimeModule 支持 LocalDateTime, 不用写 @JsonFormat 注解。
 *
 * 4. 为什么额外暴露 StringRedisTemplate?
 *    StringRedisTemplate 适合纯字符串操作(验证码、限流计数、分布式锁),
 *    RedisTemplate<Object> 适合存对象(checkpoint、会话缓存)。
 *    两者共存, 各用各的场景, 不强行统一。
 *
 * 5. ObjectMapper 为什么开启 default typing?
 *    存 checkpoint 时, 存的是 LinkedHashMap + List 嵌套结构,
 *    反序列化时需要保留类型信息, 否则全变成 LinkedHashMap 查不了字段。
 *    LaissezFaireTypeVerifier 是安全宽松策略, 允许常见集合类型。
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // ---- 序列化器 ----
        StringRedisSerializer stringSerializer = new StringRedisSerializer();

        // JSON 序列化器, 带时间模块支持 LocalDateTime
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 允许序列化未知属性(向前兼容), 不影响反序列化
        om.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 开启类型信息, 反序列化时能恢复原始类型
        // BasicPolymorphicTypeValidator: Jackson 2.15+ 替代废弃的 LaissezFaireSubTypeValidator
        // allowIfBaseType(Object.class): 允许所有基类型(宽松策略, 内网环境安全)
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType(Object.class)
                .build();
        om.activateDefaultTyping(
                ptv,
                ObjectMapper.DefaultTyping.NON_FINAL,
                com.fasterxml.jackson.annotation.JsonTypeInfo.As.PROPERTY
        );

        GenericJackson2JsonRedisSerializer jsonSerializer =
                new GenericJackson2JsonRedisSerializer(om);

        // ---- 设置序列化策略 ----
        // key: 纯字符串, redis-cli 可读
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        // value: JSON, 跨语言可读
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // ---- 事务支持 ----
        template.setEnableTransactionSupport(false);
        // 关闭事务: Redis 事务和 @Transactional 的数据库事务不同步,
        // 混用会导致 Redis 提交了但 DB 回滚了的数据不一致。
        // 需要原子性时用 Lua 脚本, 不用 Redis 事务。

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 纯字符串操作的 RedisTemplate
     * 用于: 验证码(getdel)、限流(incr)、分布式锁(set nx)、会话缓存
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        // SpringBoot 已自动配置 StringRedisTemplate, 这里显式声明确保可用
        return new StringRedisTemplate(factory);
    }
}
