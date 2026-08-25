package com.agentforge.agent.knowledge.service;

import com.agentforge.agent.knowledge.dto.KnowledgeSearchResult;
import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Java → Python 知识库引擎客户端。
 *
 * 负责把文档索引、检索转发给 agent-engine（向量索引的唯一事实源在 Python 侧
 * data/knowledge/，MySQL 只存元数据）。降级方案：生产切换 pgvector 后，
 * 本类改为直连 PG，Python 侧仅保留 embedding 服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeEngineClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Value("${app.knowledge.engine-url:http://127.0.0.1:8000}")
    private String engineUrl;

    /** 覆盖索引文档，返回分块数。 */
    public int index(String docId, String title, String text) {
        JsonNode body = post("/api/knowledge/index", Map.of(
                "doc_id", docId,
                "title", title,
                "text", text));
        return body.path("chunk_count").asInt(0);
    }

    /** 检索，返回溯源结果。 */
    public KnowledgeSearchResult search(String query, int topK) {
        JsonNode body = post("/api/knowledge/search", Map.of(
                "query", query,
                "top_k", topK));
        List<KnowledgeSearchResult.ChunkHit> hits = new ArrayList<>();
        for (JsonNode node : body.path("results")) {
            hits.add(new KnowledgeSearchResult.ChunkHit(
                    node.path("doc_id").asText(),
                    node.path("title").asText(),
                    node.path("chunk_id").asText(),
                    node.path("text").asText(),
                    node.path("score").asDouble()));
        }
        return new KnowledgeSearchResult(query, body.path("count").asInt(0), hits);
    }

    /** 删除文档索引。 */
    public void delete(String docId) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(engineUrl + "/api/knowledge/docs/" + docId))
                    .timeout(Duration.ofSeconds(3))
                    .DELETE()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("knowledge delete failed docId={} status={}", docId, response.statusCode());
            }
        } catch (Exception exc) {
            log.warn("knowledge delete error docId={}", docId, exc);
        }
    }

    private JsonNode post(String path, Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(engineUrl + path))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.error("knowledge engine {} failed status={} body={}", path, response.statusCode(),
                        response.body().substring(0, Math.min(300, response.body().length())));
                throw new BizException(ErrorCode.KNOWLEDGE_ENGINE_ERROR);
            }
            return objectMapper.readTree(response.body());
        } catch (BizException exc) {
            throw exc;
        } catch (Exception exc) {
            log.error("knowledge engine {} error", path, exc);
            throw new BizException(ErrorCode.KNOWLEDGE_ENGINE_ERROR);
        }
    }
}
