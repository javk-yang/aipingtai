package com.agentforge.agent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识文档详情（仅编辑态返回原始正文）。 */
@Data
@AllArgsConstructor
public class KnowledgeDetailResponse {
    private String docId;
    private String title;
    private String content;
    private Integer chunkCount;
    private Integer status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
