-- ============================================================
-- AgentForge · MySQL 业务库 DDL（17 张表）
-- Engine: InnoDB / Charset: utf8mb4 / Collation: utf8mb4_0900_ai_ci
-- ID 策略：内部表 BIGINT 自增；对外业务 ID（会话）CHAR(32) UUID
-- 多租户：每表预留 tenant_id（单租户期恒为 1，零改表演进）
-- ============================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 一、认证域 Auth（5 张表）· RBAC 权限模型
-- ============================================================

-- 1. 用户：登录主体，密码存 BCrypt 哈希（含 cost），绝不存明文/可逆加密
CREATE TABLE sys_user (
  id                BIGINT        NOT NULL AUTO_INCREMENT,
  tenant_id         BIGINT        NOT NULL DEFAULT 1          COMMENT '租户（单租户期恒为1）',
  username          VARCHAR(64)   NOT NULL                     COMMENT '登录名',
  email             VARCHAR(128)  NULL                         COMMENT '邮箱（可作为登录凭证）',
  phone             VARCHAR(20)   NULL                         COMMENT '手机号 E.164（可作为登录凭证）',
  password_hash     VARCHAR(100)  NOT NULL                     COMMENT 'BCrypt $2a$10$... 含盐与 cost',
  nickname          VARCHAR(64)   NULL,
  avatar_url        VARCHAR(255)  NULL,
  status            TINYINT       NOT NULL DEFAULT 1           COMMENT '1正常 2停用 3锁定',
  email_verified    TINYINT       NOT NULL DEFAULT 0           COMMENT '邮箱是否验证',
  phone_verified    TINYINT       NOT NULL DEFAULT 0           COMMENT '手机是否验证',
  last_login_at     DATETIME(3)   NULL,
  last_login_ip     VARCHAR(45)   NULL                         COMMENT '兼容 IPv6',
  login_fail_count  INT           NOT NULL DEFAULT 0           COMMENT '连续登录失败次数（风控防刷）',
  locked_until      DATETIME(3)   NULL                         COMMENT '锁定到期时间（指数退避）',
  created_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at        DATETIME(3)   NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_username (tenant_id, username),
  UNIQUE KEY uk_tenant_email (tenant_id, email),
  KEY idx_phone (phone),
  KEY idx_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户';

-- 2. 角色：RBAC 的 R，聚合权限的容器
CREATE TABLE sys_role (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL DEFAULT 1,
  role_code   VARCHAR(64)  NOT NULL             COMMENT '角色编码（程序判定用，如 admin/agent_builder）',
  role_name   VARCHAR(64)  NOT NULL             COMMENT '显示名',
  description VARCHAR(255) NULL,
  sort_order  INT          NOT NULL DEFAULT 0,
  status      TINYINT      NOT NULL DEFAULT 1   COMMENT '1启用 0禁用',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_rolecode (tenant_id, role_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

-- 3. 权限：最小粒度操作点，resource + action 二元组
CREATE TABLE sys_permission (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT       NOT NULL DEFAULT 1,
  perm_code     VARCHAR(128) NOT NULL          COMMENT '权限编码 如 agent:tool:call',
  resource      VARCHAR(64)  NOT NULL          COMMENT '资源类型 如 agent/tool/skill',
  action        VARCHAR(32)  NOT NULL          COMMENT '动作 read/write/call/delete',
  description   VARCHAR(255) NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_perm (tenant_id, perm_code),
  KEY idx_resource (resource)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='权限点';

-- 4. 角色-权限关联：N:N，硬删除（有审计兜底）
CREATE TABLE sys_role_permission (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  role_id       BIGINT      NOT NULL,
  permission_id BIGINT      NOT NULL,
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_role_perm (role_id, permission_id),
  KEY idx_perm (permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限关联';

-- 5. 用户-角色关联：N:N，硬删除
CREATE TABLE sys_user_role (
  id         BIGINT      NOT NULL AUTO_INCREMENT,
  user_id    BIGINT      NOT NULL,
  role_id    BIGINT      NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_user_role (user_id, role_id),
  KEY idx_role (role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户角色关联';


-- ============================================================
-- 二、会话域 Conversation（3 张表）· 高写入
-- ============================================================

-- 6. 会话：对外业务 ID 用 CHAR(32) UUID（不可枚举，防业务量级泄露）
CREATE TABLE conversation (
  id              CHAR(32)     NOT NULL                      COMMENT 'UUID 无连字符，对外暴露',
  pk_id           BIGINT       NOT NULL AUTO_INCREMENT       COMMENT '内部自增主键（分表路由用）',
  tenant_id       BIGINT       NOT NULL DEFAULT 1,
  user_id         BIGINT       NOT NULL,
  agent_id        BIGINT       NULL                          COMMENT '绑定的智能体（可空，空=默认助手）',
  title           VARCHAR(128) NULL                          COMMENT '会话标题（首条消息摘要生成）',
  status          TINYINT      NOT NULL DEFAULT 1            COMMENT '1活跃 2归档 3已删除',
  message_count   INT          NOT NULL DEFAULT 0            COMMENT '冗余计数，避免 count(*) 全表扫',
  last_message_at DATETIME(3)  NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at      DATETIME(3)  NULL,
  PRIMARY KEY (pk_id),
  UNIQUE KEY uk_id (id),
  KEY idx_user_updated (tenant_id, user_id, updated_at),
  KEY idx_agent (agent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话';

-- 7. 消息：内部 BIGINT 自增（量大、索引紧凑）；查询必带 conversation_id（分表预留）
CREATE TABLE message (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id       BIGINT       NOT NULL DEFAULT 1,
  conversation_id CHAR(32)     NOT NULL                     COMMENT '外键到 conversation.id（分表键）',
  role            VARCHAR(16)  NOT NULL                     COMMENT 'user/assistant/tool/system',
  seq             INT          NOT NULL                     COMMENT '会话内序号（流式排序+断点续传）',
  content         MEDIUMTEXT   NULL                         COMMENT '消息内容（流式增量更新此字段）',
  content_type   VARCHAR(32)   NOT NULL DEFAULT 'text'      COMMENT 'text/markdown/json',
  status          TINYINT       NOT NULL DEFAULT 0           COMMENT '0流式中 1完成 2失败 3中断',
  model           VARCHAR(64)  NULL                         COMMENT '生成该消息的模型',
  token_input    INT           NOT NULL DEFAULT 0           COMMENT '输入 token（计费用）',
  token_output   INT           NOT NULL DEFAULT 0           COMMENT '输出 token（计费用）',
  parent_id       BIGINT       NULL                         COMMENT '父消息（工具调用链路溯源）',
  created_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_conv_seq (conversation_id, seq),
  KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='消息';

-- 8. 工具调用记录：一条 assistant 消息可触发多次工具调用，独立成表
CREATE TABLE message_tool_call (
  id              BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id       BIGINT       NOT NULL DEFAULT 1,
  message_id      BIGINT       NOT NULL                      COMMENT '触发的 assistant 消息',
  call_id         VARCHAR(32)  NOT NULL                      COMMENT '跨 Python/Java/SSE 的工具调用关联 ID',
  tool_id         BIGINT       NULL                          COMMENT '调用的工具（可空=临时工具）',
  tool_name       VARCHAR(128) NOT NULL,
  call_args       JSON         NULL                         COMMENT '入参（schema 校验后存）',
  call_result     MEDIUMTEXT   NULL                         COMMENT '结果（超长截断）',
  status          TINYINT      NOT NULL DEFAULT 0           COMMENT '0调用中 1成功 2失败 3超时',
  duration_ms     INT          NULL                         COMMENT '耗时（审计+降级依据）',
  error_msg       VARCHAR(512) NULL,
  started_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at     DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_call (tenant_id, call_id),
  KEY idx_msg (message_id),
  KEY idx_tool_status (tool_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具调用记录';

-- 8.5 技能调用记录：技能级审计（技能内部工具调用仍走 message_tool_call，双轨可溯源）
CREATE TABLE message_skill_call (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT        NOT NULL DEFAULT 1,
  message_id    BIGINT        NOT NULL                       COMMENT '触发的 assistant 消息',
  call_id       VARCHAR(32)   NOT NULL                       COMMENT '跨 Python/Java/SSE 的技能调用关联 ID',
  skill_id      BIGINT        NULL                           COMMENT '技能 ID（可空=临时技能）',
  skill_code    VARCHAR(128)  NOT NULL,
  skill_name    VARCHAR(128)  NOT NULL,
  skill_version VARCHAR(32)   NULL,
  call_args     JSON          NULL                          COMMENT '技能入参（触发上下文）',
  call_result   MEDIUMTEXT    NULL                          COMMENT '技能执行结果',
  status        TINYINT       NOT NULL DEFAULT 0            COMMENT '0执行中 1成功 2失败 3超时',
  duration_ms   INT           NULL,
  error_msg     VARCHAR(1024) NULL,
  started_at    DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at   DATETIME(3)   NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_call (tenant_id, call_id),
  KEY idx_msg (message_id),
  KEY idx_skill_status (skill_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技能调用记录';


-- ============================================================
-- 三、智能体域 Agent（2 张表）· 编排图快照
-- ============================================================

-- 9. 智能体：定义与配置
CREATE TABLE agent (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT       NOT NULL DEFAULT 1,
  agent_code    VARCHAR(64)  NOT NULL               COMMENT '编码（程序引用）',
  name          VARCHAR(128) NOT NULL,
  description   VARCHAR(512) NULL,
  agent_type    VARCHAR(32)  NOT NULL DEFAULT 'chat' COMMENT 'chat/workflow/autonomous',
  status        TINYINT      NOT NULL DEFAULT 1      COMMENT '1草稿 2发布 3下线',
  is_default    TINYINT      NOT NULL DEFAULT 0      COMMENT '是否默认助手',
  created_by    BIGINT       NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at    DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_agentcode (tenant_id, agent_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体';

-- 10. 智能体版本：每次发布存一份编排图快照，可回滚
CREATE TABLE agent_version (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  agent_id      BIGINT       NOT NULL,
  version       VARCHAR(32)  NOT NULL               COMMENT '语义版本 1.0.0',
  graph_json    JSON         NOT NULL               COMMENT 'LangGraph 状态机定义（节点/边/条件）',
  system_prompt MEDIUMTEXT   NULL                   COMMENT '系统提示词（随版本走）',
  model_config  JSON         NULL                   COMMENT '模型/温度/top_p 等',
  tools_json    JSON         NULL                   COMMENT '绑定的工具列表快照',
  change_log    VARCHAR(512) NULL,
  published     TINYINT      NOT NULL DEFAULT 0     COMMENT '0草稿 1已发布',
  created_by    BIGINT       NOT NULL,
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_agent_version (agent_id, version),
  KEY idx_published (agent_id, published)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='智能体版本快照';


-- ============================================================
-- 四、能力域 Capability（3 张表）· 可复用资源
-- ============================================================

-- 11. 工具：MCP 工具与本地工具统一注册
CREATE TABLE tool (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT       NOT NULL DEFAULT 1,
  tool_code     VARCHAR(128) NOT NULL               COMMENT '工具唯一编码',
  name          VARCHAR(128) NOT NULL,
  description   VARCHAR(512) NULL,
  mcp_server_id BIGINT       NULL                   COMMENT '来源 MCP 服务（空=本地内置工具）',
  input_schema  JSON         NOT NULL               COMMENT '入参 JSON Schema（调用前校验）',
  output_schema JSON         NULL,
  is_async      TINYINT      NOT NULL DEFAULT 0     COMMENT '是否异步工具',
  timeout_ms    INT          NOT NULL DEFAULT 30000 COMMENT '默认超时',
  status        TINYINT      NOT NULL DEFAULT 1     COMMENT '1启用 0禁用',
  created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at    DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_toolcode (tenant_id, tool_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='工具';

-- 12. MCP 服务端点：注册的外部 MCP server 连接信息
CREATE TABLE mcp_server (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id    BIGINT       NOT NULL DEFAULT 1,
  server_code  VARCHAR(64)  NOT NULL,
  name         VARCHAR(128) NOT NULL,
  transport    VARCHAR(16)  NOT NULL DEFAULT 'stdio' COMMENT 'stdio/sse/http',
  command      VARCHAR(512) NULL                  COMMENT 'stdio 启动命令',
  args_json    JSON         NULL                  COMMENT 'stdio 参数',
  url          VARCHAR(512) NULL                  COMMENT 'sse/http 端点',
  headers_json JSON         NULL                  COMMENT '鉴权头',
  status       TINYINT      NOT NULL DEFAULT 1    COMMENT '1在线 0离线 2异常',
  last_ping_at DATETIME(3)  NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at   DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_servercode (tenant_id, server_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='MCP 服务端点';

-- 13. 技能：渐进式披露的元数据
CREATE TABLE skill (
  id             BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id      BIGINT       NOT NULL DEFAULT 1,
  skill_code     VARCHAR(128) NOT NULL,
  name           VARCHAR(128) NOT NULL,
  description    VARCHAR(512) NULL,
  triggers_json  JSON         NOT NULL             COMMENT '触发词/意图匹配规则',
  content_json   JSON         NULL                 COMMENT '技能全文（指令+步骤+模板），命中后才拉取',
  skill_file_url VARCHAR(512) NULL                 COMMENT 'SKILL.md 内容地址（外部托管时用）',
  version        VARCHAR(32)  NOT NULL DEFAULT '1.0.0',
  enabled        TINYINT      NOT NULL DEFAULT 1,
  is_builtin    TINYINT      NOT NULL DEFAULT 0    COMMENT '是否内置技能',
  created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at     DATETIME(3)  NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_skillcode (tenant_id, skill_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技能';


-- ============================================================
-- 五、审计计费域 Audit（3 张表）· 只追加，永不删
-- ============================================================

-- 14. 审计日志：全操作留痕，合规要求（按时间分区归档）
CREATE TABLE audit_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL DEFAULT 1,
  trace_id    VARCHAR(64)  NULL                  COMMENT '链路 ID（三语言串联）',
  user_id     BIGINT       NULL,
  action      VARCHAR(64)  NOT NULL             COMMENT '操作 如 user.login/agent.publish',
  resource    VARCHAR(64)  NULL,
  resource_id VARCHAR(64)  NULL,
  detail_json JSON         NULL                  COMMENT '变更前后快照',
  ip          VARCHAR(45)  NULL,
  user_agent  VARCHAR(255) NULL,
  status      TINYINT      NOT NULL DEFAULT 1    COMMENT '1成功 0失败',
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_tenant_created (tenant_id, created_at),
  KEY idx_user (user_id),
  KEY idx_action (action)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计日志';

-- 15. API 用量：按调用计的 token 与成本（计费依据）
CREATE TABLE api_usage (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id    BIGINT       NOT NULL DEFAULT 1,
  user_id      BIGINT       NOT NULL,
  conversation_id CHAR(32)  NULL,
  model        VARCHAR(64)  NOT NULL,
  token_input  INT          NOT NULL DEFAULT 0,
  token_output INT          NOT NULL DEFAULT 0,
  cost         DECIMAL(12,6) NOT NULL DEFAULT 0   COMMENT '本调用成本（元）',
  latency_ms   INT          NULL,
  created_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  KEY idx_tenant_user_time (tenant_id, user_id, created_at),
  KEY idx_model_time (model, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='API 用量与计费';

-- 16. 配额：租户/用户的用量上限与周期
CREATE TABLE api_quota (
  id            BIGINT      NOT NULL AUTO_INCREMENT,
  tenant_id     BIGINT      NOT NULL DEFAULT 1,
  scope         VARCHAR(16) NOT NULL              COMMENT 'tenant/user',
  scope_id      BIGINT      NOT NULL              COMMENT '租户ID或用户ID',
  period        VARCHAR(16) NOT NULL DEFAULT 'day' COMMENT 'day/month',
  token_limit   BIGINT      NOT NULL             COMMENT 'token 上限',
  cost_limit    DECIMAL(12,2) NOT NULL            COMMENT '金额上限（元）',
  soft_threshold TINYINT    NOT NULL DEFAULT 80   COMMENT '软告警阈值（百分比）',
  created_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_scope_period (scope, scope_id, period)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='配额';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- 初始化：默认租户 + 超管角色 + 超管账号
-- 注：password_hash 为 BCrypt("Admin@2026")，正式环境务必替换
-- ============================================================
INSERT INTO sys_role (tenant_id, role_code, role_name, description, sort_order)
VALUES (1, 'admin', '系统管理员', '拥有全部权限', 0),
       (1, 'agent_builder', '智能体开发者', '可编排/发布智能体', 10),
       (1, 'end_user', '终端用户', '仅可使用已发布智能体', 20);

INSERT INTO sys_user (tenant_id, username, email, phone, password_hash, nickname, status, email_verified)
VALUES (1, 'admin', 'admin@agentforge.local', NULL,
        '$2a$10$hfDwZ3vWLxmdQ2.KDqk5JubnNsb2znXTWtkFe2vwL/p8vLmcsyEci', -- BCrypt("Admin@2026")
        '管理员', 1, 1);

INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- P8 内置工具：由 Python 安全执行器执行，Java 负责启停、租户和审计治理
INSERT INTO tool (
  tenant_id, tool_code, name, description, mcp_server_id,
  input_schema, output_schema, is_async, timeout_ms, status
) VALUES
(1, 'calculator', '计算器', '计算基础算术表达式，支持加减乘除、取模、幂和括号。', NULL,
 JSON_OBJECT(
   'type', 'object',
   'properties', JSON_OBJECT('expression', JSON_OBJECT(
     'type', 'string', 'minLength', 1, 'maxLength', 256,
     'description', '仅包含数字和算术运算符的表达式')),
   'required', JSON_ARRAY('expression'),
   'additionalProperties', FALSE
 ),
 JSON_OBJECT(
   'type', 'object',
   'properties', JSON_OBJECT(
     'expression', JSON_OBJECT('type', 'string'),
     'value', JSON_OBJECT('type', 'string')),
   'required', JSON_ARRAY('expression', 'value')
 ), 0, 3000, 1),
(1, 'get_current_time', '获取当前时间', '获取指定 IANA 时区的当前时间，默认 Asia/Shanghai。', NULL,
 JSON_OBJECT(
   'type', 'object',
   'properties', JSON_OBJECT('timezone', JSON_OBJECT(
     'type', 'string', 'default', 'Asia/Shanghai', 'maxLength', 64)),
   'additionalProperties', FALSE
 ),
 JSON_OBJECT(
   'type', 'object',
   'properties', JSON_OBJECT(
     'timezone', JSON_OBJECT('type', 'string'),
     'iso', JSON_OBJECT('type', 'string'),
     'display', JSON_OBJECT('type', 'string')),
   'required', JSON_ARRAY('timezone', 'iso', 'display')
 ), 0, 3000, 1),
(1, 'unit_converter', '单位换算', '单位换算：长度/重量/温度/面积，支持常见公制与英制单位。', NULL,
 JSON_OBJECT(
   'type', 'object',
   'properties', JSON_OBJECT(
     'value', JSON_OBJECT('type', 'number', 'description', '数值'),
     'from_unit', JSON_OBJECT('type', 'string', 'description', '源单位标准名'),
     'to_unit', JSON_OBJECT('type', 'string', 'description', '目标单位标准名'),
     'category', JSON_OBJECT('type', 'string', 'enum', JSON_ARRAY('length', 'weight', 'temperature', 'area'), 'description', '单位歧义时必填')),
   'required', JSON_ARRAY('value', 'from_unit', 'to_unit'),
   'additionalProperties', FALSE
 ),
 JSON_OBJECT(
   'type', 'object',
   'properties', JSON_OBJECT(
     'value', JSON_OBJECT('type', 'number'),
     'from_unit', JSON_OBJECT('type', 'string'),
     'to_unit', JSON_OBJECT('type', 'string'),
     'result', JSON_OBJECT('type', 'number'),
     'display', JSON_OBJECT('type', 'string')),
   'required', JSON_ARRAY('value', 'from_unit', 'to_unit', 'result', 'display')
 ), 0, 3000, 1),
(1, 'code_exec', '代码执行沙箱', '在受控沙箱中执行 Python 代码（进程隔离、资源限额、仅允许纯计算库）。', NULL,
 JSON_OBJECT(
   'type', 'object',
   'properties', JSON_OBJECT(
     'code', JSON_OBJECT('type', 'string', 'description', '要执行的 Python 代码，禁止 IO/网络/进程操作')),
   'required', JSON_ARRAY('code'),
   'additionalProperties', FALSE
 ),
 JSON_OBJECT(
   'type', 'object',
   'properties', JSON_OBJECT(
     'status', JSON_OBJECT('type', 'string'),
     'stdout', JSON_OBJECT('type', 'string'),
     'stderr', JSON_OBJECT('type', 'string'),
     'error_code', JSON_OBJECT('anyOf', JSON_ARRAY(JSON_OBJECT('type', 'string'), JSON_OBJECT('type', 'null'))),
     'exit_code', JSON_OBJECT('anyOf', JSON_ARRAY(JSON_OBJECT('type', 'integer'), JSON_OBJECT('type', 'null'))),
     'duration_ms', JSON_OBJECT('type', 'integer')),
   'required', JSON_ARRAY('status', 'stdout', 'stderr', 'duration_ms')
 ), 0, 5000, 1);

-- P8 工具治理权限；超管在 PermissionAspect 中按 admin 角色直接放行
INSERT INTO sys_permission (tenant_id, perm_code, resource, action, description)
VALUES (1, 'agent:tool:read', 'tool', 'read', '查看工具与 MCP Server'),
       (1, 'agent:tool:write', 'tool', 'write', '注册、修改和启停工具');

-- P9 内置技能：元数据层渐进式披露；全文以 SKILL.md 文件为准（skill_file_url），命中后才读文件
INSERT INTO skill (
  tenant_id, skill_code, name, description,
  triggers_json, content_json, skill_file_url, version, enabled, is_builtin
) VALUES
(1, 'unit_converter', '单位换算', '把自然语言中的单位换算（长度/重量/温度/面积）转为精确结果。',
 JSON_ARRAY(
   JSON_OBJECT('type', 'keyword', 'values', JSON_ARRAY('换算', '转换', '单位')),
   JSON_OBJECT('type', 'regex', 'pattern', '\\d+(\\.\\d+)?\\s*(米|厘米|千米|公里|公斤|千克|克|斤|磅|摄氏度|华氏度|度|平方米|平方千米|亩|公顷)')
 ),
 NULL, 'unit_converter/SKILL.md', '1.0.0', 1, 1),
(1, 'text_polish', '文案润色', '对用户提供的文案进行翻译、润色或改写，保持原意并提升表达质量。',
 JSON_ARRAY(
   JSON_OBJECT('type', 'keyword', 'values', JSON_ARRAY('润色', '改写', '翻译', '美化', '润一下'))
 ),
 NULL, 'text_polish/SKILL.md', '1.0.0', 1, 1);

-- P9 技能治理权限；超管在 PermissionAspect 中按 admin 角色直接放行
INSERT INTO sys_permission (tenant_id, perm_code, resource, action, description)
VALUES (1, 'agent:skill:read', 'skill', 'read', '查看技能与技能内容'),
       (1, 'agent:skill:write', 'skill', 'write', '注册、修改和启停技能');

-- P12 知识库文档元数据（降级方案：向量索引在 Python 侧 data/knowledge/，本表存元数据与状态）
CREATE TABLE knowledge_doc (
  id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT      NOT NULL DEFAULT 1,
  doc_id      CHAR(32)    NOT NULL              COMMENT '对外文档 ID',
  title       VARCHAR(128) NOT NULL             COMMENT '文档标题',
  content     MEDIUMTEXT   NULL                 COMMENT '原始正文，用于编辑与重索引',
  chunk_count INT         NOT NULL DEFAULT 0    COMMENT '分块数',
  status      TINYINT     NOT NULL DEFAULT 0    COMMENT '0 索引中 1 就绪 2 失败',
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3) NULL DEFAULT NULL COMMENT '软删除标记',
  PRIMARY KEY (id),
  UNIQUE KEY uk_tenant_doc (tenant_id, doc_id),
  KEY idx_tenant_created (tenant_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='知识库文档元数据';

-- ============================================================
-- 五、模型配置域 ModelConfig（1 张表）· 平台可配置的真实 LLM 供应商
-- ============================================================
CREATE TABLE model_config (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   BIGINT       NOT NULL DEFAULT 1,
  name        VARCHAR(128) NOT NULL               COMMENT '配置名称',
  provider    VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT 'openai/openai-compatible/deepseek/qwen/deterministic',
  model       VARCHAR(128) NOT NULL DEFAULT ''     COMMENT '模型名(如 gpt-4o-mini)',
  base_url    VARCHAR(512) NULL                   COMMENT 'OpenAI 兼容端点(留空用默认)',
  api_key     VARCHAR(1024) NULL                   COMMENT '加密存储(AES)',
  temperature DECIMAL(4,2) NOT NULL DEFAULT 0.70   COMMENT '采样温度',
  max_tokens  INT          NOT NULL DEFAULT 1024   COMMENT '最大生成 token',
  enabled     TINYINT      NOT NULL DEFAULT 1      COMMENT '1启用 0禁用',
  is_default  TINYINT      NOT NULL DEFAULT 0      COMMENT '是否默认模型(聊天默认选中)',
  description VARCHAR(512) NULL                   COMMENT '备注',
  created_by  BIGINT       NOT NULL DEFAULT 1,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted_at  DATETIME(3)  NULL,
  PRIMARY KEY (id),
  KEY idx_tenant_enabled (tenant_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='模型配置（供应商/密钥/参数）';

-- 内置确定性模型：无外部 Key 的离线演示回退
INSERT INTO model_config (tenant_id, name, provider, model, base_url, api_key, enabled, is_default, description, created_by)
VALUES (1, '内置确定性模型(离线演示)', 'deterministic', 'agentforge-dev-model', NULL, NULL, 1, 1,
        '无外部 API Key 的确定性回退模型，用于离线演示与工具/技能编排验证', 1);

-- 模型配置权限；超管在 PermissionAspect 中按 admin 角色直接放行
INSERT INTO sys_permission (tenant_id, perm_code, resource, action, description)
VALUES (1, 'agent:model:read', 'model', 'read', '查看模型配置'),
       (1, 'agent:model:write', 'model', 'write', '新增/编辑/删除模型配置');

-- P12 知识库治理权限；超管在 PermissionAspect 中按 admin 角色直接放行
INSERT INTO sys_permission (tenant_id, perm_code, resource, action, description)
VALUES (1, 'agent:knowledge:read', 'knowledge', 'read', '查看知识库文档'),
       (1, 'agent:knowledge:write', 'knowledge', 'write', '上传、删除知识库文档');

-- Agent 管理权限；超管在 PermissionAspect 中按 admin 角色直接放行
INSERT INTO sys_permission (tenant_id, perm_code, resource, action, description)
VALUES (1, 'agent:agent:read', 'agent', 'read', '查看智能体定义与配置'),
       (1, 'agent:agent:write', 'agent', 'write', '创建、编辑、发布和删除智能体');

-- ============================================================
-- P13 可观测性与计费种子
-- ============================================================

-- P13 配额种子: 租户 1 每日 token 上限 100 万、金额上限 ¥100, 软告警 80%
INSERT INTO api_quota (tenant_id, scope, scope_id, period, token_limit, cost_limit, soft_threshold)
VALUES (1, 'tenant', 1, 'day', 1000000, 100.00, 80)
ON DUPLICATE KEY UPDATE token_limit = VALUES(token_limit), cost_limit = VALUES(cost_limit);

-- P13 可观测性权限；超管在 PermissionAspect 中按 admin 角色直接放行
INSERT INTO sys_permission (tenant_id, perm_code, resource, action, description)
VALUES (1, 'agent:usage:read', 'usage', 'read', '查看用量统计与配额'),
       (1, 'agent:audit:read', 'audit', 'read', '查看审计日志');
