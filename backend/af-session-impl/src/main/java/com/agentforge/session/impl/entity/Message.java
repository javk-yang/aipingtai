package com.agentforge.session.impl.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体 —— message 表
 *
 * 设计决策:
 * 1. 主键 id(BIGINT 自增): 量大、索引紧凑, 对外不暴露(只用 conversation_id+seq 定位)
 * 2. conversation_id 是分表键: 所有查询必带它(铁律: 单一数据源 + 分表预留)
 * 3. status: 0流式中/1完成/2失败/3中断 —— 断线重连时按它判断"半成品"需续渲染
 * 4. 不继承 BaseEntity: message 表只有 tenant_id/created_at/updated_at，没有 deleted_at；
 *    显式声明真实列，避免 MyBatis-Plus 自动拼出不存在的逻辑删除字段。
 */
@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属会话(UUID, 分表键) */
    private String conversationId;

    /** user / assistant / tool / system */
    private String role;

    /** 会话内序号: 排序+去重 */
    private Integer seq;

    /** 文本(MEDIUMTEXT, 流式增量 UPDATE 此字段) */
    private String content;

    /** text / markdown / json */
    private String contentType;

    /** 0流式中 1完成 2失败 3中断 */
    private Integer status;

    /** 生成模型 */
    private String model;

    /** 输入 token */
    private Integer tokenInput;

    /** 输出 token */
    private Integer tokenOutput;

    /** 父消息(工具链路溯源) */
    private Long parentId;

    /** 租户隔离 */
    private Long tenantId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 最后一次增量落库时间 */
    private LocalDateTime updatedAt;
}
