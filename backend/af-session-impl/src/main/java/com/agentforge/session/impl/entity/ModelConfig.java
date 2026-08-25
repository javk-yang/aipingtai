package com.agentforge.session.impl.entity;

import com.agentforge.common.api.BaseEntity;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/** 模型配置实体 —— 平台可配置的真实 LLM 供应商（密钥 AES 加密存储）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("model_config")
public class ModelConfig extends BaseEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String name;
    private String provider;
    private String model;
    private String baseUrl;
    private String apiKey;
    private BigDecimal temperature;
    private Integer maxTokens;
    private Integer enabled;
    private Integer isDefault;
    private String description;
    private Long createdBy;
}
