package com.agentforge.auth.impl.service;

/**
 * 邮件发送通道抽象
 * 设计理由同 SmsSender: 换服务商/环境切换不改业务代码
 */
public interface EmailSender {

    /**
     * 发送邮件
     * @param to      收件人邮箱
     * @param subject 主题
     * @param content 正文(纯文本; 生产可扩展为 HTML)
     */
    void send(String to, String subject, String content);
}
