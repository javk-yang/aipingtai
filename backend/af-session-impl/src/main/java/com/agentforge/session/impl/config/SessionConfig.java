package com.agentforge.session.impl.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 会话模块线程池配置
 *
 * 设计决策:
 * 1. chatStreamExecutor: 每个聊天流一个线程跑引擎循环, 不阻塞 Tomcat 工作线程
 *    (SSE 必须异步输出, 否则 servlet 线程被长连接占满 → 整个服务不可响应)
 *    用 daemon 线程, JVM 退出不阻塞
 * 2. chatScheduler: 节流落库(每 500ms 写一次累积内容) + 心跳(每 15s 发 ping 防代理断流)
 *    复用少量线程服务所有流, 不是每流一个(避免百级连接开百个定时器线程)
 */
@Configuration
public class SessionConfig {

    @Bean(destroyMethod = "shutdown")
    @Primary
    public ExecutorService chatStreamExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "chat-stream");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService chatScheduler() {
        return Executors.newScheduledThreadPool(4, r -> {
            Thread t = new Thread(r, "chat-sched");
            t.setDaemon(true);
            return t;
        });
    }
}
