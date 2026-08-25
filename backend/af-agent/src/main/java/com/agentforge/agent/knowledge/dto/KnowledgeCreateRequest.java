package com.agentforge.agent.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 知识库文档创建请求（文本粘贴形态，前端演示用）。 */
@Data
public class KnowledgeCreateRequest {

    @NotBlank(message = "文档标题必填")
    @Size(max = 128, message = "标题不能超过 128 字符")
    private String title;

    @NotBlank(message = "文档内容必填")
    @Size(max = 200_000, message = "文档内容不能超过 200KB")
    private String text;
}
