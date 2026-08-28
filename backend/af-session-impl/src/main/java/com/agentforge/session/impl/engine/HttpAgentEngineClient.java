package com.agentforge.session.impl.engine;

import com.agentforge.common.skill.SkillStreamEvent;
import com.agentforge.common.tool.ToolStreamEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Python LangGraph HTTP 客户端 —— 逐行消费 NDJSON，不缓存完整回复。
 * Java 仍负责鉴权、SSE 中继、增量落库；Python 只负责无状态计算。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.engine", name = "provider", havingValue = "http")
public class HttpAgentEngineClient implements AgentEngineClient {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String streamUrl;
    private final long timeoutSeconds;

    public HttpAgentEngineClient(
            ObjectMapper objectMapper,
            @Value("${agent.engine.base-url:http://127.0.0.1:8000}") String baseUrl,
            @Value("${agent.engine.timeout:300}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.streamUrl = baseUrl.replaceAll("/+$", "") + "/v1/chat/stream";
        this.timeoutSeconds = Math.max(1L, timeoutSeconds);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(Math.min(this.timeoutSeconds, 30)))
                .build();
    }

    @Override
    public StreamResult stream(String prompt, String conversationId, String traceId, Long tenantId,
                               Map<String, Object> llmConfig,
                               Map<String, Object> agentConfig,
                               Consumer<String> onDelta,
                               Consumer<ToolStreamEvent> onToolEvent,
                               Consumer<SkillStreamEvent> onSkillEvent,
                               Consumer<String> onReasoning) throws Exception {
        Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
        bodyMap.put("prompt", prompt);
        bodyMap.put("conversation_id", conversationId);
        bodyMap.put("trace_id", traceId == null ? "" : traceId);
        bodyMap.put("tenant_id", tenantId);
        if (llmConfig != null) bodyMap.put("llm_config", llmConfig);
        if (agentConfig != null) bodyMap.put("agent_config", agentConfig);
        String body = objectMapper.writeValueAsString(bodyMap);

        HttpRequest request = HttpRequest.newBuilder(URI.create(streamUrl))
                .timeout(Duration.ofSeconds(this.timeoutSeconds))
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("Accept", "application/x-ndjson")
                .header("X-Trace-Id", traceId == null ? "" : traceId)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<java.io.InputStream> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Agent engine HTTP " + response.statusCode());
        }

        String model = "unknown";
        int tokenInput = 0;
        int tokenOutput = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), java.nio.charset.StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonNode event = objectMapper.readTree(line);
                String type = event.path("type").asText();
                JsonNode data = event.path("data");
                if ("message_start".equals(type)) {
                    model = data.path("model").asText(model);
                } else if ("content_delta".equals(type)) {
                    onDelta.accept(data.path("delta").asText(""));
                } else if (ToolStreamEvent.TYPE_START.equals(type)
                        || ToolStreamEvent.TYPE_RESULT.equals(type)
                        || ToolStreamEvent.TYPE_ERROR.equals(type)) {
                    onToolEvent.accept(parseToolEvent(type, data));
                } else if (SkillStreamEvent.TYPE_START.equals(type)
                        || SkillStreamEvent.TYPE_RESULT.equals(type)
                        || SkillStreamEvent.TYPE_ERROR.equals(type)) {
                    onSkillEvent.accept(parseSkillEvent(type, data));
                } else if ("reasoning".equals(type)) {
                    onReasoning.accept(data.path("content").asText(""));
                } else if ("message_done".equals(type)) {
                    model = data.path("model").asText(model);
                    tokenInput = data.path("token_input").asInt(0);
                    tokenOutput = data.path("token_output").asInt(0);
                } else if ("error".equals(type)) {
                    throw new IllegalStateException(data.path("message").asText("Agent engine error"));
                }
            }
        }
        return new StreamResult(model, tokenInput, tokenOutput);
    }

    private ToolStreamEvent parseToolEvent(String type, JsonNode data) {
        JsonNode toolIdNode = data.path("tool_id");
        return new ToolStreamEvent(
                type,
                data.path("call_id").asText(),
                toolIdNode.isNumber() ? toolIdNode.asLong() : null,
                data.path("tool_code").asText(),
                data.path("tool_name").asText(),
                nodeValue(data.path("arguments")),
                nodeValue(data.path("result")),
                data.path("status").asText(),
                data.path("error_code").isMissingNode() || data.path("error_code").isNull()
                        ? null : data.path("error_code").asText(),
                data.path("error_message").isMissingNode() || data.path("error_message").isNull()
                        ? null : data.path("error_message").asText(),
                data.path("duration_ms").asLong(0)
        );
    }

    private Object nodeValue(JsonNode node) {
        return node.isMissingNode() || node.isNull()
                ? null : objectMapper.convertValue(node, Object.class);
    }

    private SkillStreamEvent parseSkillEvent(String type, JsonNode data) {
        JsonNode skillIdNode = data.path("skill_id");
        return new SkillStreamEvent(
                type,
                data.path("call_id").asText(),
                skillIdNode.isNumber() ? skillIdNode.asLong() : null,
                data.path("skill_code").asText(),
                data.path("skill_name").asText(),
                data.path("skill_version").isMissingNode() || data.path("skill_version").isNull()
                        ? null : data.path("skill_version").asText(),
                nodeValue(data.path("call_args")),
                nodeValue(data.path("result")),
                data.path("status").asText(),
                data.path("error_code").isMissingNode() || data.path("error_code").isNull()
                        ? null : data.path("error_code").asText(),
                data.path("error_message").isMissingNode() || data.path("error_message").isNull()
                        ? null : data.path("error_message").asText(),
                data.path("duration_ms").asLong(0)
        );
    }
}
