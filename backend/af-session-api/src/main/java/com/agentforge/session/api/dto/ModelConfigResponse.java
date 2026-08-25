package com.agentforge.session.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 模型配置响应 —— apiKey 已脱敏 */
@Data
public class ModelConfigResponse {

    private Long id;
    private String name;
    private String provider;
    private String model;
    private String baseUrl;
    /** 脱敏后的密钥，如 sk-****abcd */
    private String apiKey;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer enabled;
    private Integer isDefault;
    private String description;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
