package com.agentforge.common.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志实体 —— audit_log 表（只追加，永不删）
 *
 * 设计决策:
 * 1. 为什么放在 af-common 而不是某个业务模块?
 *    登录(auth)、会话(session)、工具/技能/知识库(agent)都要写审计,
 *    它必须是公共底座, 不能只属于某一个模块(和 UserContext 同理)。
 *
 * 2. 为什么是"只追加"数据?
 *    合规要求: 操作留痕不可篡改。本表没有任何 UPDATE/DELETE 方法,
 *    Mapper 只暴露 BaseMapper 的 insert 和查询能力, 业务侧不得更新历史。
 *
 * 3. detail_json 存什么?
 *    变更前后快照 / 请求摘要, 序列化后存入。不同 action 结构不同,
 *    JSON 是唯一能容纳异构结构的字段类型(MySQL 8 原生支持)。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 租户(隔离维度) */
    private Long tenantId;

    /** 链路 ID: 与日志 MDC 中的 traceId 同源, 三语言串联 */
    private String traceId;

    /** 操作用户(未登录场景如登录失败可为 null) */
    private Long userId;

    /** 操作码: user.login / agent.publish / chat.message.complete ... */
    private String action;

    /** 资源类型: user / message / tool / skill / knowledge / quota ... */
    private String resource;

    /** 资源 ID(业务侧 ID, 如 message.id / doc_id) */
    private String resourceId;

    /** 变更前后快照(JSON 字符串) */
    private String detailJson;

    /** 客户端 IP(处理 X-Forwarded-For) */
    private String ip;

    /** 客户端 UA */
    private String userAgent;

    /** 1成功 0失败 */
    private Integer status;

    private LocalDateTime createdAt;
}
