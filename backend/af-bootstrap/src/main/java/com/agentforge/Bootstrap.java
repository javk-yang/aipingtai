package com.agentforge;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AgentForge 启动类 —— 整个平台的唯一入口
 *
 * 为什么 @SpringBootApplication 不加 scanBasePackages?
 *    默认扫描规则: Bootstrap 类所在包 com.agentforge 及其所有子包。
 *    我们的包结构: com.agentforge.common.* / com.agentforge.auth.* / com.agentforge.session.* / com.agentforge.agent.*
 *    全部在 com.agentforge 下, 默认扫描就够, 不用显式声明。
 *
 * 为什么 @MapperScan 用 "com.agentforge.**.mapper"?
 *    各 impl 模块的 Mapper 接口在 com.agentforge.auth.impl.mapper / com.agentforge.session.impl.mapper 等,
 *    用 ** 通配一把全扫进来, 不需要每个模块单独声明。
 */
@SpringBootApplication
@MapperScan("com.agentforge.**.mapper")
public class Bootstrap {

    public static void main(String[] args) {
        SpringApplication.run(Bootstrap.class, args);
    }
}
