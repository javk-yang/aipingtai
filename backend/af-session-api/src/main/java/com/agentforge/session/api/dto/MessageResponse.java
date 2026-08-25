package com.agentforge.session.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 消息响应 —— 与 message 表字段对齐
 *
 * 设计决策:
 * 1. content 流式场景中前端不直接查单条, 而是订阅 SSE; 但断线重连/历史回看走此结构
 * 2. status 暴露给前端: 0流式中 / 1完成 / 2失败 / 3中断 —— 前端据此渲染"正在输入"或失败态
 * 3. seq 是会话内有序号, 前端用它排序且去重(同 seq 的 content_delta 乱序也能排)
 */
@Data
public class MessageResponse implements Serializable {

    private Long id;

    /** 所属会话 UUID */
    private String conversationId;

    /** user / assistant / tool / system */
    private String role;

    /** 会话内序号: 排序+去重 */
    private Integer seq;

    /** 文本内容(MEDIUMTEXT, 流式增量更新此字段) */
    private String content;

    /** text / markdown / json */
    private String contentType;

    /** 0流式中 1完成 2失败 3中断 */
    private Integer status;

    /** 生成模型 */
    private String model;

    /** 输入 token(计费用) */
    private Integer tokenInput;

    /** 输出 token(计费用) */
    private Integer tokenOutput;

    /** 父消息(工具调用链路溯源) */
    private Long parentId;

    private LocalDateTime createdAt;
}
