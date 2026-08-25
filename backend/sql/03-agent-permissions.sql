-- Agent 管理权限增量迁移（已有数据库执行一次）
INSERT INTO sys_permission (tenant_id, perm_code, resource, action, description)
VALUES (1, 'agent:agent:read', 'agent', 'read', '查看智能体定义与配置'),
       (1, 'agent:agent:write', 'agent', 'write', '创建、编辑、发布和删除智能体')
ON DUPLICATE KEY UPDATE description = VALUES(description);
