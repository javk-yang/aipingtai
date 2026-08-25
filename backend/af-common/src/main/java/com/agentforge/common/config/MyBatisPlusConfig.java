package com.agentforge.common.config;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.dialects.MySqlDialect;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;

/**
 * MyBatis-Plus 全局配置
 *
 * 设计决策（先讲原理）:
 *
 * 1. 为什么用 MyBatis-Plus 而不是 JPA/Hibernate?
 *    Agent 平台有大量复杂 SQL(消息分页 + 会话过滤 + 全文检索 + 聚合统计),
 *    JPA 的方法名拼查(Query By Method Name)能表达的 SQL 很有限, 复杂查询要写 JPQL 或 native SQL。
 *    MyBatis-Plus: 简单 CRUD 用 LambdaQueryWrapper 零 XML, 复杂 SQL 写 XML Mapper 全控制。
 *    两者结合 = 简单的用注解/Wrapper, 复杂的用 XML, 不互相绑架。
 *
 * 2. 分页插件为什么手动指定 MySqlDialect?
 *    不指定时 MP 会探测数据库方言, 但探测有时不准(尤其多数据源场景)。
 *    手动指定 = 确定性, 不会因为驱动版本变化而生成错误的 LIMIT 语法。
 *
 * 3. 自动填充 MetaObjectHandler 干嘛用?
 *    所有表都有 created_at / updated_at (P1 数据库设计),
 *    如果每次 INSERT/UPDATE 都手动 set, 必忘。MetaObjectHandler 在拦截层自动填, 业务代码零侵入。
 *
 *    关键: INSERT 时填 created_at + updated_at, UPDATE 时只填 updated_at。
 *    不能在 UPDATE 时也填 created_at——会把创建时间覆盖成当前时间, 审计数据全毁。
 */
@Slf4j
@Configuration
public class MyBatisPlusConfig {

    /**
     * 分页拦截器
     * maxLimit: 防止前端传 size=10000 拖垮数据库, 超过 500 强制截断
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pageInterceptor = new PaginationInnerInterceptor(new MySqlDialect());
        // 单页最大 500 条, 防止恶意大分页拖垮数据库
        pageInterceptor.setMaxLimit(500L);
        // overflow=false: 超出总页数不返回最后一页, 直接返回空(避免无效数据)
        pageInterceptor.setOverflow(false);
        interceptor.addInnerInterceptor(pageInterceptor);
        log.info("MyBatis-Plus 分页拦截器已注册 | maxLimit=500");
        return interceptor;
    }

    /**
     * 字段自动填充处理器
     * INSERT → created_at + updated_at 一起填
     * UPDATE → 只填 updated_at
     */
    @Bean
    public MetaObjectHandler metaObjectHandler() {
        return new MetaObjectHandler() {
            @Override
            public void insertFill(MetaObject metaObject) {
                LocalDateTime now = LocalDateTime.now();
                // strictInsertFill: 字段不存在不报错, 空值才填
                this.strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
                this.strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
            }

            @Override
            public void updateFill(MetaObject metaObject) {
                // 更新时只填 updatedAt, 绝不动 createdAt
                this.strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
            }
        };
    }
}
