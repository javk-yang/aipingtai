package com.agentforge.auth.impl.service.impl;

import com.agentforge.auth.impl.service.SmsSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发环境短信实现 —— 只打日志, 不真发
 *
 * @ConditionalOnProperty(name="notify.sms.provider", havingValue="log", matchIfMissing=true):
 * 配置 notify.sms.provider=log(或未配置)时, 这个实现生效。
 * 生产切真实通道: 把配置改成 aliyun, 新建 AliyunSmsSender 实现 SmsSender 并标注
 * @ConditionalOnProperty(...havingValue="aliyun"), 自动切换, 业务代码零改动。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notify.sms.provider", havingValue = "log", matchIfMissing = true)
public class LogSmsSender implements SmsSender {

    @Override
    public void send(String phone, String content) {
        log.info("[SMS][开发环境-仅日志] to={} | content={}", phone, content);
    }
}
