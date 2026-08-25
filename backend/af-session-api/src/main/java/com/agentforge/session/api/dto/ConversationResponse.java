package com.agentforge.session.api.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 会话响应 —— 与 conversation 表字段对齐, 对外只暴露 UUID 主键(id)
 *
 * 为什么 user_id 仍返回?
 * 前端工作台需要显示"这是谁的会话", 但写操作一律忽略请求体里的 userId(从 token 取)。
 * 读返回 ≠ 写信任, 这是防越权的边界。
 */
@Data
public class ConversationResponse implements Serializable {

    /** 对外 ID: CHAR(32) UUID, 不可枚举 */
    private String id;

    /** 所属用户 */
    private Long userId;

    /** 绑定的智能体 */
    private Long agentId;

    /** 标题 */
    private String title;

    /** 状态: 1活跃 2归档 3已删除 */
    private Integer status;

    /** 消息数: 冗余计数, 避免 count(*) 全表扫 */
    private Integer messageCount;

    /** 最后消息时间: 列表排序用 */
    private LocalDateTime lastMessageAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
