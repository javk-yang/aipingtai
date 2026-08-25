package com.agentforge.session.impl.audit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志查询响应 —— GET /api/audit/logs 的单条记录
 */
@Data
@AllArgsConstructor
public class AuditLogResponse {

    private Long id;
    private Long tenantId;
    private String traceId;
    private Long userId;
    private String action;
    private String resource;
    private String resourceId;
    private String detailJson;
    private String ip;
    private String userAgent;
    private Integer status;
    private LocalDateTime createdAt;
}
