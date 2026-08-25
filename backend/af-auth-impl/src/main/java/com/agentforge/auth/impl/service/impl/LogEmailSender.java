package com.agentforge.auth.impl.service.impl;

import com.agentforge.auth.impl.service.EmailSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 开发环境邮件实现 —— 只打日志, 不真发
 * 生产切 SMTP: notify.email.provider=smtp, 新建 SmtpEmailSender(JavaMailSender) 接管
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "notify.email.provider", havingValue = "log", matchIfMissing = true)
public class LogEmailSender implements EmailSender {

    @Override
    public void send(String to, String subject, String content) {
        log.info("[EMAIL][开发环境-仅日志] to={} | subject={} | content={}", to, subject, content);
    }
}
