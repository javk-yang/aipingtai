package com.agentforge.common.audit;

import com.agentforge.common.audit.mapper.AuditLogMapper;
import com.agentforge.common.constant.CommonConst;
import com.agentforge.common.security.LoginUser;
import com.agentforge.common.security.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 通用审计写入器 —— 全平台唯一写 audit_log 的入口
 *
 * 设计决策:
 * 1. 为什么所有埋点都走这一个类?
 *    审计字段很多(租户/用户/traceId/IP/UA/快照), 每个业务埋点自己拼容易漏。
 *    AuditService 把"上下文自动提取"收敛到一处:
 *    - 用户/租户: 从 UserContext 取(登录后自动有)
 *    - traceId: 从 MDC 取(TraceIdFilter 已在请求入口写入)
 *    - IP/UA: 从 RequestContextHolder 取(无需调用方传 request)
 *    业务代码只需要声明"发生了什么", 不需要关心"从哪拿这些字段"。
 *
 * 2. 为什么失败静默降级而不是抛异常?
 *    审计是旁路逻辑: 审计写库失败不应该让主流程(登录/下单/聊天)失败。
 *    记录 warn 日志即可, 生产环境用日志采集兜底。
 *
 * 3. 为什么 detail 接受 Object 而不是 String?
 *    调用方传业务对象(Map/POJO), 内部统一序列化为 JSON,
 *    避免每个调用方各自 try-catch JSON 序列化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    /** 便捷重载: 默认成功(status=1) */
    public void record(String action, String resource, String resourceId, Object detail) {
        record(action, resource, resourceId, detail, 1);
    }

    /**
     * 写入一条审计日志。所有上下文(用户/租户/traceId/IP/UA)自动提取。
     *
     * @param action     操作码 如 user.login / chat.message.complete
     * @param resource   资源类型 如 message / tool / skill
     * @param resourceId 资源 ID(可为 null)
     * @param detail     业务快照(任意可序列化对象, 可为 null)
     * @param status     1成功 0失败
     */
    public void record(String action, String resource, String resourceId, Object detail, Integer status) {
        try {
            AuditLog audit = new AuditLog();
            fillContext(audit);
            audit.setAction(action);
            audit.setResource(resource);
            audit.setResourceId(resourceId);
            audit.setDetailJson(detail == null ? null : objectMapper.writeValueAsString(detail));
            audit.setStatus(status == null ? 1 : status);
            auditLogMapper.insert(audit);
        } catch (Exception e) {
            // 审计旁路失败不阻断主流程(合规降级: 记日志, 生产用日志采集兜底)
            log.warn("audit record failed | action={} | resource={} | resourceId={}",
                    action, resource, resourceId, e);
        }
    }

    /**
     * 显式指定租户/用户写入审计 —— 用于认证前场景(登录/注册),
     * 此时 UserContext 还没有用户, 但业务侧已查到用户, 显式传参保证归属正确。
     */
    public void recordExplicit(Long tenantId, Long userId, String action,
                               String resource, String resourceId, Object detail, Integer status) {
        try {
            AuditLog audit = new AuditLog();
            fillContext(audit);
            if (tenantId != null) audit.setTenantId(tenantId);
            if (userId != null) audit.setUserId(userId);
            audit.setAction(action);
            audit.setResource(resource);
            audit.setResourceId(resourceId);
            audit.setDetailJson(detail == null ? null : objectMapper.writeValueAsString(detail));
            audit.setStatus(status == null ? 1 : status);
            auditLogMapper.insert(audit);
        } catch (Exception e) {
            log.warn("audit record failed | action={} | resource={} | resourceId={}",
                    action, resource, resourceId, e);
        }
    }

    // ==================== 上下文自动提取 ====================

    private void fillContext(AuditLog audit) {
        // 1. 用户/租户: 登录态从 UserContext 取; 未登录(如登录失败埋点)用默认租户
        LoginUser user = UserContext.get();
        if (user != null) {
            audit.setTenantId(user.getTenantId());
            audit.setUserId(user.getUserId());
        } else {
            audit.setTenantId(CommonConst.DEFAULT_TENANT_ID);
        }

        // 2. traceId: 与日志/响应头同源
        audit.setTraceId(MDC.get("traceId"));

        // 3. IP / UA: 从请求上下文取, 无需调用方透传 request
        HttpServletRequest req = currentRequest();
        if (req != null) {
            audit.setIp(getClientIp(req));
            String ua = req.getHeader("User-Agent");
            audit.setUserAgent(ua == null ? null : truncate(ua, 255));
        }
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    /** 客户端真实 IP(X-Forwarded-For 取第一个, 兼容 Nginx 反代) */
    private String getClientIp(HttpServletRequest req) {
        String ip = req.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
            return ip.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }

    /** 截断超长字段, 防止超长 UA 撑爆表字段 */
    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
