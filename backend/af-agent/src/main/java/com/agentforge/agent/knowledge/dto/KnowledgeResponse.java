package com.agentforge.agent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** 知识库文档响应。 */
@Data
@AllArgsConstructor
public class KnowledgeResponse {

    private String docId;
    private String title;
    private Integer chunkCount;
    private Integer status;
    private LocalDateTime createdAt;
}
