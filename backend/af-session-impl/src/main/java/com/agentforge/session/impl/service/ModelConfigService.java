package com.agentforge.session.impl.service;

import com.agentforge.common.exception.BizException;
import com.agentforge.common.exception.ErrorCode;
import com.agentforge.session.api.dto.ModelConfigRequest;
import com.agentforge.session.api.dto.ModelConfigResponse;
import com.agentforge.session.impl.entity.ModelConfig;
import com.agentforge.session.impl.mapper.ModelConfigMapper;
import com.agentforge.session.impl.util.CryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 模型配置服务：CRUD + 密钥加密/脱敏 + 连通性测试 + 聊天配置解析。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ModelConfigService {

    private final ModelConfigMapper mapper;
    private final CryptoUtil crypto;
    private final ObjectMapper objectMapper;

    public List<ModelConfigResponse> list(Long tenantId) {
        LambdaQueryWrapper<ModelConfig> w = new LambdaQueryWrapper<>();
        w.eq(ModelConfig::getTenantId, tenantId)
                .orderByDesc(ModelConfig::getIsDefault).orderByAsc(ModelConfig::getId);
        return mapper.selectList(w).stream().map(this::toResponse).toList();
    }

    public ModelConfigResponse get(Long id, Long tenantId) {
        return toResponse(require(id, tenantId));
    }

    public ModelConfigResponse create(ModelConfigRequest req, Long tenantId, Long userId) {
        ModelConfig e = new ModelConfig();
        e.setTenantId(tenantId);
        e.setCreatedBy(userId);
        applyRequest(e, req, tenantId, true);
        mapper.insert(e);
        return toResponse(e);
    }

    public ModelConfigResponse update(Long id, ModelConfigRequest req, Long tenantId, Long userId) {
        ModelConfig e = require(id, tenantId);
        applyRequest(e, req, tenantId, false);
        mapper.updateById(e);
        return toResponse(e);
    }

    public void delete(Long id, Long tenantId) {
        mapper.deleteById(require(id, tenantId).getId());
    }

    public Map<String, Object> test(Long id, Long tenantId) {
        ModelConfig e = require(id, tenantId);
        String ak = e.getApiKey() == null ? null : crypto.decrypt(e.getApiKey());
        return doTest(e.getProvider(), e.getModel(), e.getBaseUrl(), ak);
    }

    public Map<String, Object> test(ModelConfigRequest req) {
        String ak = req.getApiKey();
        if (ak != null && isMasked(ak)) ak = null;
        return doTest(req.getProvider(), req.getModel(), req.getBaseUrl(), ak);
    }

    /** 聊天内部用：解析出明文 config（传给 Python 引擎的 llm_config） */
    public Map<String, Object> resolveConfig(Long modelConfigId, Long tenantId) {
        if (modelConfigId == null) return null;
        ModelConfig e = mapper.selectById(modelConfigId);
        if (e == null || !tenantId.equals(e.getTenantId())) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND);
        }
        if (e.getEnabled() != null && e.getEnabled() == 0) {
            throw new BizException(ErrorCode.MODEL_DISABLED);
        }
        Map<String, Object> m = new HashMap<>();
        m.put("provider", e.getProvider());
        m.put("model", e.getModel());
        if (e.getBaseUrl() != null && !e.getBaseUrl().isBlank()) m.put("base_url", e.getBaseUrl());
        if (e.getApiKey() != null && !e.getApiKey().isBlank())
            m.put("api_key", crypto.decrypt(e.getApiKey()));
        if (e.getTemperature() != null) m.put("temperature", e.getTemperature().doubleValue());
        if (e.getMaxTokens() != null) m.put("max_tokens", e.getMaxTokens());
        return m;
    }

    public Long defaultConfigId(Long tenantId) {
        // 开发/受限网络环境下优先使用内置确定性模型，避免一个不可达的真实 Provider
        // 让所有未显式选择模型的聊天请求直接失败。真实模型仍可由请求显式指定。
        LambdaQueryWrapper<ModelConfig> offline = new LambdaQueryWrapper<>();
        offline.eq(ModelConfig::getTenantId, tenantId)
                .eq(ModelConfig::getEnabled, 1)
                .eq(ModelConfig::getProvider, "deterministic")
                .orderByAsc(ModelConfig::getId)
                .last("LIMIT 1");
        ModelConfig offlineModel = mapper.selectOne(offline);
        if (offlineModel != null) return offlineModel.getId();

        LambdaQueryWrapper<ModelConfig> w = new LambdaQueryWrapper<>();
        w.eq(ModelConfig::getTenantId, tenantId).eq(ModelConfig::getEnabled, 1)
                .eq(ModelConfig::getIsDefault, 1).last("LIMIT 1");
        ModelConfig e = mapper.selectOne(w);
        return e == null ? null : e.getId();
    }

    // ---------------- private ----------------
    private void applyRequest(ModelConfig e, ModelConfigRequest req, Long tenantId, boolean isCreate) {
        e.setName(req.getName());
        e.setProvider(req.getProvider());
        e.setModel(req.getModel() == null ? "" : req.getModel());
        e.setBaseUrl(req.getBaseUrl());
        e.setTemperature(req.getTemperature());
        e.setMaxTokens(req.getMaxTokens());
        e.setEnabled(req.getEnabled() == null ? 1 : req.getEnabled());
        e.setDescription(req.getDescription());
        if (req.getApiKey() != null && !req.getApiKey().isBlank() && !isMasked(req.getApiKey())) {
            e.setApiKey(crypto.encrypt(req.getApiKey()));
        } else if (isCreate) {
            e.setApiKey(null);
        }
        if (Boolean.TRUE.equals(req.getIsDefault())) {
            clearDefault(tenantId);
            e.setIsDefault(1);
        } else {
            e.setIsDefault(0);
        }
    }

    private void clearDefault(Long tenantId) {
        LambdaQueryWrapper<ModelConfig> w = new LambdaQueryWrapper<>();
        w.eq(ModelConfig::getTenantId, tenantId).eq(ModelConfig::getIsDefault, 1);
        ModelConfig upd = new ModelConfig();
        upd.setIsDefault(0);
        mapper.update(upd, w);
    }

    private ModelConfig require(Long id, Long tenantId) {
        ModelConfig e = mapper.selectById(id);
        if (e == null || !tenantId.equals(e.getTenantId())) {
            throw new BizException(ErrorCode.MODEL_NOT_FOUND);
        }
        return e;
    }

    private ModelConfigResponse toResponse(ModelConfig e) {
        ModelConfigResponse r = new ModelConfigResponse();
        r.setId(e.getId());
        r.setName(e.getName());
        r.setProvider(e.getProvider());
        r.setModel(e.getModel());
        r.setBaseUrl(e.getBaseUrl());
        r.setApiKey(mask(e.getApiKey()));
        r.setTemperature(e.getTemperature());
        r.setMaxTokens(e.getMaxTokens());
        r.setEnabled(e.getEnabled());
        r.setIsDefault(e.getIsDefault());
        r.setDescription(e.getDescription());
        r.setCreatedAt(e.getCreatedAt());
        r.setUpdatedAt(e.getUpdatedAt());
        return r;
    }

    private boolean isMasked(String s) {
        return s != null && s.contains("*");
    }

    private String mask(String cipher) {
        if (cipher == null) return null;
        try {
            String plain = crypto.decrypt(cipher);
            if (plain == null) return null;
            if (plain.length() <= 8) return "****";
            return plain.substring(0, Math.min(4, plain.length())) + "****"
                    + plain.substring(plain.length() - 4);
        } catch (Exception ex) {
            return "****";
        }
    }

    private Map<String, Object> doTest(String provider, String model, String baseUrl, String apiKey) {
        Map<String, Object> res = new HashMap<>();
        if ("deterministic".equalsIgnoreCase(provider)) {
            res.put("ok", true);
            res.put("message", "内置确定性模型无需连通性测试");
            return res;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            res.put("ok", false);
            res.put("message", "缺少 base_url");
            return res;
        }
        try {
            String url = baseUrl.replaceAll("/+$", "") + "/chat/completions";
            String body = objectMapper.writeValueAsString(Map.of(
                    "model", model == null ? "" : model,
                    "messages", List.of(Map.of("role", "user", "content", "ping")),
                    "max_tokens", 5, "stream", false));
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                    .timeout(java.time.Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .proxy(new java.net.ProxySelector() {
                        @Override
                        public List<java.net.Proxy> select(java.net.URI uri) {
                            return List.of(java.net.Proxy.NO_PROXY);
                        }
                        @Override
                        public void connectFailed(java.net.URI uri, java.net.SocketAddress sa, java.io.IOException ioe) {
                        }
                    })
                    .connectTimeout(java.time.Duration.ofSeconds(15))
                    .build();
            java.net.http.HttpResponse<String> resp = client
                    .send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() / 100 == 2;
            res.put("ok", ok);
            res.put("status", resp.statusCode());
            res.put("message", ok ? "连通成功" : ("HTTP " + resp.statusCode()));
            if (!ok) res.put("detail", sanitizeRemoteDetail(resp.body()));
        } catch (Exception ex) {
            res.put("ok", false);
            res.put("message", "连通失败: " + safeExceptionMessage(ex));
        }
        return res;
    }

    private String sanitizeRemoteDetail(String detail) {
        if (detail == null || detail.isBlank()) return null;
        String sanitized = detail
                .replaceAll("(?i)(\\\"?(?:api[_-]?key|authorization|token|password|secret)\\\"?\\s*[:=]\\s*\\\")([^\\\"]+)(\\\")", "$1[REDACTED]$3")
                .replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]")
                .replaceAll("(?i)(sk-[A-Za-z0-9_-]{4})[A-Za-z0-9_-]+", "$1[REDACTED]");
        return sanitized.length() <= 512 ? sanitized : sanitized.substring(0, 512) + "…";
    }

    private String safeExceptionMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) return "上游请求失败";
        return sanitizeRemoteDetail(message);
    }
}
