package com.agentforge.session.api.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 创建会话请求
 *
 * 设计决策:
 * 1. title 可空 —— 首条消息发出后由后端自动摘要生成, 不强迫用户起名(对齐 ChatGPT/Claude 体验)
 * 2. agentId 可空 —— 空 = 平台默认助手; 指定 = 绑定某个已发布智能体
 * 3. 不含 userId —— 用户身份从 token(UserContext) 取, 不在请求体里带(防越权伪造)
 */
@Data
public class ConversationCreateRequest implements Serializable {

    /** 会话标题: 可选, 不传则首条消息后自动生成 */
    @Size(max = 128, message = "标题过长")
    private String title;

    /** 绑定的智能体 ID: 可选 */
    private Long agentId;
}
