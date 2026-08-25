package com.agentforge.agent.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 知识库检索结果（含溯源）。 */
@Data
@AllArgsConstructor
public class KnowledgeSearchResult {

    private String query;
    private Integer count;
    private List<ChunkHit> results;

    @Data
    @AllArgsConstructor
    public static class ChunkHit {
        private String docId;
        private String title;
        private String chunkId;
        private String text;
        private Double score;
    }
}
