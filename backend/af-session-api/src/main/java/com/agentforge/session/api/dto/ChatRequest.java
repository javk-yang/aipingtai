package com.agentforge.session.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 聊天/流式生成请求
 *
 * 设计决策:
 * 1. conversationId 可空 —— 空则后端新建会话(首轮对话零摩擦); 非空则追加到已有会话
 * 2. content 是用户"本轮"输入, 历史上下文由后端从 message 表装配(铁律: 单一数据源)
 * 3. agentId 可空 —— 不传用默认助手; 传则优先用会话绑定的(会话级 > 请求级)
 * 4. 注意: 这是 POST 请求体, 前端用 fetch 发(而非 EventSource, EventSource 不支持 body)
 */
@Data
public class ChatRequest implements Serializable {

    /** 用户本轮输入 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 20000, message = "消息过长")
    private String content;

    /** 会话 ID: 空=新建 */
    private String conversationId;

    /** 指定智能体: 可选 */
    private Long agentId;

    /** 指定模型配置: 可选（null=平台默认模型） */
    private Long modelConfigId;
}
