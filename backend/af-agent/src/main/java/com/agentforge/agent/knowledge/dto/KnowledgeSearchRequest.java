package com.agentforge.agent.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 知识库检索请求。 */
@Data
public class KnowledgeSearchRequest {

    @NotBlank(message = "检索问题必填")
    @Size(max = 512, message = "检索问题不能超过 512 字符")
    private String query;

    @Min(value = 1, message = "topK 最小为 1")
    @Max(value = 10, message = "topK 最大为 10")
    private Integer topK = 3;
}
