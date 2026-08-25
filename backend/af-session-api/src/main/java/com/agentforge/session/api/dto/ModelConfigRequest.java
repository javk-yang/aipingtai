package com.agentforge.session.api.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 模型配置新增/编辑请求 */
@Data
public class ModelConfigRequest {

    /** 配置名称 */
    private String name;
    /** 供应商: openai / openai-compatible / deepseek / qwen / deterministic */
    private String provider;
    /** 模型名 */
    private String model;
    /** OpenAI 兼容端点(留空用默认) */
    private String baseUrl;
    /** API Key（明文入参；若传脱敏串 **** 表示不修改原值） */
    private String apiKey;
    /** 采样温度 */
    private BigDecimal temperature;
    /** 最大生成 token */
    private Integer maxTokens;
    /** 1启用 0禁用 */
    private Integer enabled;
    /** 是否默认模型 */
    private Boolean isDefault;
    /** 备注 */
    private String description;
}
