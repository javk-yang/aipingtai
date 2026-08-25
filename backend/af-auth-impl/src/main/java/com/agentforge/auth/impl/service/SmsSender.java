package com.agentforge.auth.impl.service;

/**
 * 短信发送通道抽象
 *
 * 设计决策(为什么是接口而不是直接在 CaptchaService 里发?):
 * 1. 换供应商不改业务代码: 今天用阿里云, 明天换腾讯云,
 *    新建一个实现类 + 改一行配置, CaptchaService 零改动。
 * 2. 开发/生产环境切换: 开发用日志实现(不真发短信), 生产切真实通道,
 *    同一个 CaptchaService 代码, 纯配置驱动。
 * 3. 可测试性: 单元测试注入 mock 实现, 不发真短信。
 */
public interface SmsSender {

    /**
     * 发送短信
     * @param phone   目标手机号
     * @param content 短信内容(纯文本, 已拼接好模板)
     */
    void send(String phone, String content);
}
