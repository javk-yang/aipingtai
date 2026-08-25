# AgentForge 开发进度看板

> 最后更新：2026-08-20 ｜ 当前阶段：**P10–P14 全部完成（沙箱 + 工作台 + RAG 知识库 + 可观测计费 + 一键部署），五端全链路启动演示通过，前端工作台可在线体验**

---

## 总体进度

```
██████████████████████████████  15 / 15 阶段    100.0%
```

| 维度 | 已完成 | 剩余 |
| --- | --- | --- |
| 阶段数 | 15 | 0 |
| 后端模块 | 7 / 7 | 0（认证、会话、LangGraph、工具注册与审计已运行验证） |
| 前端工程 | ✅ 构建通过 | 0（vue-tsc + vite build 双绿） |
| 前端页面 | 8 / 8 | 0（登录/注册/找回 + 工作台四页：聊天/知识库/工具技能/可观测） |
| Agent 能力 | 4 / 4 | 0（LangGraph + MCP 工具层 + Skill 技能 + 沙箱闭环） |
| 数据表 | 16 / 16 | 0 |

---

## 阶段清单

| # | 阶段 | 核心产出 | 状态 |
| --- | --- | --- | --- |
| P0 | 架构设计与技术选型 | 五层架构、选型理由、层间契约、关键链路 | ✅ 已完成 |
| P0.5 | UI 原型设计基线 | 高保真原型、设计 token、双主题（已锁定为开发方向） | ✅ 已完成 |
| P1 | 数据库三件套设计 | MySQL 16 张表 DDL、PG 向量库、Redis key 规范 | ✅ 已完成 |
| P2 | 后端工程骨架与基建 | 多模块 pom、统一响应体、全局异常、TraceId、MyBatis-Plus、Redis、CORS | ✅ 已完成 |
| P3 | 认证授权模块 | BCrypt、JWT 双 Token、手机/邮箱验证、RBAC、登录风控 | ✅ 已完成 |
| P4 | 前端工程与设计系统 | 双主题 Token、路由守卫、请求层、基础组件 | ✅ 已完成 |
| P5 | 登录注册页面 | 登录/注册/找回密码三页，接 P3 五层防御 | ✅ 已完成 |
| P6 | 会话与消息 + SSE | 会话 CRUD、流式中继、增量落库、断线重连 | ✅ 已完成 |
| P7 | Agent 引擎 | FastAPI + LangGraph 状态图、checkpoint、Java HTTP 流式接入 | ✅ 已完成 |
| P8 | MCP 工具集成层 | 协议对接、注册中心、动态发现、调用审计 | ✅ 已完成 |
| P9 | Skill 技能系统 | 元数据规范、渐进式加载、技能市场 | ✅ 已完成 |
| P10 | 代码执行沙箱 | 进程隔离、资源限额、文件白名单、逃逸防护 | ✅ 已完成 |
| P11 | 前端 Agent 工作台 | 流式渲染、工具过程可视化、代码高亮、知识库/可观测页 | ✅ 已完成 |
| P12 | RAG 知识库 | 文档解析、分块、向量检索、溯源 | ✅ 已完成 |
| P13 | 可观测性与计费 | 全链路追踪、Token 配额、审计、成本看板 | ✅ 已完成 |
| P14 | 部署与压测 | 一键启动脚本、五端拉起、全链路演示 | ✅ 已完成 |

---

## P0 阶段交付明细

### 已确认的技术决策

| 决策项 | 结论 | 关键理由 |
| --- | --- | --- |
| 视觉基调 | 冷静工程感（中性灰 + 单一强调色） | 开发者工具需长时间注视，低刺激优先；黑白双主题都成立 |
| 后端形态 | 模块化单体起步，P14 演进微服务 | 团队 < 10 人时单体更优；接口按微服务契约预先设计 |
| Agent 引擎 | Python FastAPI + LangGraph 独立进程 | 生命周期、变更频率、故障域三重隔离需求 |
| 编排框架 | LangGraph 显式编排，不用 LangChain 高层封装 | AgentExecutor 黑盒难调试，只取其稳定底层抽象 |
| 向量库 | PostgreSQL + pgvector | 支持向量检索与元数据过滤同 SQL 完成，省一个中间件 |
| 流式协议 | SSE | 标准 HTTP 穿透性好，原生重连；WebSocket 在单向流场景收益为零 |
| UI 组件 | 自建约 15 个基础组件 | 视觉识别度是产品资产，不套用现成组件库 |

### 铁律清单（后续所有阶段必须遵守）

1. 业务数据**单一写入口**：只有 Java 侧能写 MySQL，Python 引擎不直连业务库
2. 智能层**无状态**：运行状态存 checkpoint，进程可随时 kill 重启
3. 模块间**只依赖 api 包**：`impl` 包禁止跨模块引用，为拆分留出缝
4. traceId **贯穿三语言**：前端生成 → Java 透传 → Python 携带 → 日志可串联
5. 流式消息**增量落库**：任何时刻中断都能恢复现场

---

## UI 设计基线（P0.5 已锁定）

原型位置：`frontend/prototype/index.html`（可交互高保真原型，黑白双主题一键切换）
后续 P4 / P5 / P11 的真实 Vue3 代码严格按此设计系统还原。

**核心设计 Token：**

| 维度 | 浅色 | 深色 |
| --- | --- | --- |
| 背景 bg | `#fafafa` | `#0a0a0b` |
| 表面 surface | `#ffffff` | `#111113` |
| 正文 text | `#09090b` | `#fafafa` |
| 边框 border(hairline) | `#ececef` | `#222225` |
| 主色 primary | `#09090b`（近黑按钮） | `#fafafa`（近白按钮） |

**设计原则：**
1. 纯单色，禁用饱和强调色——克制即高级（Vercel/Stripe/Linear 路线）
2. hairline 发丝边 + 留白驱动层级，不靠色块
3. 字重 + 字距建立信息层级（标题 `-0.02em` 收紧、分组用 11px 大写小标签）
4. 激活态用 2px 左指示条 + 文字加重，不用背景高亮
5. 全部 1.5 描边线性 SVG 图标，禁用 emoji
6. 用户气泡右对齐浅灰 + hairline，助手消息全宽扁平，无头像

---

## P1 阶段交付明细

### 已完成的交付物

| 交付物 | 路径 | 内容 |
| --- | --- | --- |
| MySQL 业务库 | `backend/sql/01-mysql-schema.sql` | 16 张表五域分组（用户/会话消息/智能体/工具技能/审计），含分表预留、软删分层、双主键方案 |
| PG 向量库 | `backend/sql/02-postgres-vector.sql` | knowledge_base / document / doc_chunk / embed_job，HNSW 调参 + 混合检索 SQL 示例 |
| Redis 规范 | `docs/03-Redis设计.md` | 四级 key 命名空间、六类缓存策略、穿透/击穿/雪崩应对、监控基线、key 速查表 |

### P1 关键设计决策

| 决策项 | 结论 |
| --- | --- |
| ID 策略 | 内部表 BIGINT 自增 + 对外表 CHAR(32) UUID，双主键（pk_id 聚簇 + id 唯一索引） |
| 多租户 | 全表 `tenant_id DEFAULT 1` 预留，联合唯一键带上，将来零改表 |
| 消息分表 | 查询强制带 `conversation_id` 前缀，索引 `(conversation_id, seq)`，hash 分表无痛生效 |
| 删除策略 | 软删（用户/会话/智能体）+ 硬删（关联表/工具调用）+ 永不删（审计，分区+归档） |
| 时间类型 | 全库 `DATETIME(3)` 毫秒精度，弃用 TIMESTAMP（2038 问题 + 时区坑） |
| 字符集 | `utf8mb4` + `utf8mb4_0900_ai_ci` |
| HNSW 参数 | m=32 / ef_construction=128（默认 16/64 仅"能跑"），ef_search 为会话级参数不在建表语句 |
| 向量维度 | 列定义固定 `vector(1024)`，embed_model 记入库表，换模型=重建（设计层面规避） |
| Redis 原子性 | 验证码 GETDEL、限流 INCR+EXPIRE(Lua)、释放锁比对 value(Lua)、配额 INCRBY |
| 计费双写 | Redis 实时预检 + MySQL 每日对账兜底，Redis 内存数据不作计费依据 |

---

## P2 阶段交付明细

### P2.1 已完成：Maven 骨架

| 交付物 | 路径 | 内容 |
| --- | --- | --- |
| 父 POM | `backend/pom.xml` | 继承 SpringBoot 3.2.4，7 模块声明，dependencyManagement 版本仲裁 |
| 7 模块 POM | `backend/af-*/pom.xml` | common / auth-api+impl / session-api+impl / agent / bootstrap |
| 统一响应体 | `af-common/.../api/R.java` | R\<T\> 泛型响应，5 条设计决策注释 |
| 启动类 | `af-bootstrap/.../Bootstrap.java` | @SpringBootApplication + @MapperScan，零业务逻辑 |
| 健康检查 | `af-bootstrap/.../HealthController.java` | 验证骨架可运行的探针接口 |
| 主配置 | `af-bootstrap/.../application.yml` | Jackson/MyBatis-Plus/Tomcat/文件上传全局配置 |
| 开发环境配置 | `af-bootstrap/.../application-dev.yml` | MySQL/Redis/Agent 引擎地址，环境变量优先 |
| 日志配置 | `af-bootstrap/.../logback-spring.xml` | %X{traceId} MDC 模式，dev/prod 分 profile |

**编译验证：** `mvn clean compile` 8 模块全部 SUCCESS，耗时 27s。

### P2 模块依赖关系（铁律落地）

- `af-bootstrap` → `af-auth-impl` + `af-session-impl` + `af-agent`（聚合）
- `af-auth-impl` → `af-auth-api` → `af-common`（只依赖自己 api）
- `af-session-impl` → `af-session-api` + `af-auth-api` → `af-common`（跨模块只认 api）
- `af-agent` → `af-common`（不拆 api/impl，自给自足）
- **禁止：** impl 模块之间互相 import（铁律第 3 条的物理边界）

---

## P2 交付物清单（af-common 基建层 11 个类）

| 包 | 类 | 职责 |
| --- | --- | --- |
| common.api | R&lt;T&gt; | 统一响应体（code/msg/data/traceId），静态工厂 ok()/fail() |
| common.api | PageResult&lt;T&gt; | 分页响应体（page/size/total/records） |
| common.api | BaseEntity | 实体基类（tenantId/createdAt/updatedAt/逻辑删除） |
| common.exception | ErrorCode | 38 个错误码枚举（1xxx 参数 / 2xxx 认证 / 3xxx 业务 / 5xxx 系统） |
| common.exception | BizException | 业务异常，带 ErrorCode 抛出，事务自动回滚 |
| common.exception | GlobalExceptionHandler | 全局异常处理器，翻译所有异常为 R&lt;T&gt;，4xx/5xx HTTP 码分离 |
| common.trace | TraceIdFilter | 请求入口生成/透传 traceId，写 MDC，finally 清理防串链路 |
| common.config | MyBatisPlusConfig | 分页插件(maxLimit=500) + 自动填充(createdAt/updatedAt) |
| common.config | RedisConfig | String key + JSON value 序列化，跨语言可读 |
| common.config | WebMvcConfig | CORS 跨域 + X-Trace-Id 响应头暴露 |
| common.constant | CommonConst | Redis key 前缀 + JWT claim key + 通用常量 |

---

## P3.1 交付物清单（认证核心 13 个文件）

| 模块 | 类 | 职责 |
| --- | --- | --- |
| af-auth-api | LoginRequest | 登录请求（三种凭证合一 + 图形验证码字段） |
| af-auth-api | RegisterRequest | 注册请求（用户名/密码/邮箱/手机 + 验证码） |
| af-auth-api | RefreshTokenRequest | 刷新令牌请求 |
| af-auth-api | TokenResponse | 双 Token + 用户概要响应 |
| af-auth-impl | SysUser / SysRole / SysUserRole | 实体（继承 BaseEntity，自动填充） |
| af-auth-impl | SysUserMapper / SysRoleMapper | Mapper（三表 join 查角色、INSERT IGNORE 绑角色） |
| af-auth-impl | JwtProperties | JWT 配置绑定（secret/TTL/issuer） |
| af-auth-impl | JwtUtil | jjwt 0.12 签发/解析，jti 强制字段 |
| af-auth-impl | LoginUser / UserContext | ThreadLocal 登录上下文，getRequired() 强制登录 |
| af-auth-impl | JwtAuthFilter | 请求认证过滤器（只装填不拦截，Order=HIGHEST+2） |
| af-auth-impl | CaptchaService | Redis GETDEL 验证码，60s 冷却，恒定时间比较 |
| af-auth-impl | AuthService | 注册/登录/刷新轮换/登出吊销 + 指数退避风控 |
| af-auth-impl | AuthConfig / AuthController | BCrypt Bean + /api/auth/** 四个端点 |

---

## P3.2 交付物清单（认证收尾 +17 个文件）

### 架构修正
- **UserContext / LoginUser 从 af-auth-impl 迁至 af-common.common.security**：跨模块身份载体必须沉底，P6/P7 模块取当前用户不再依赖 auth-impl（防模块环）

### 新增文件

| 模块 | 类 | 职责 |
| --- | --- | --- |
| af-common | RequirePermission | 权限注解（`agent:tool:call` 式编码，与 sys_permission 表对齐） |
| af-common | PermissionAspect | AOP 鉴权：admin 放行 → 懒加载权限 → 403；Redis 缓存 30min |
| af-common | PermissionProvider | 权限加载 SPI（依赖倒置：接口在 common，实现留 auth-impl） |
| af-auth-api | SendSmsCodeRequest / SendEmailCodeRequest | 发码请求（scene 场景隔离防跨场景重放） |
| af-auth-api | CaptchaImageResponse | 图形验证码响应（captchaId + base64） |
| af-auth-api | ResetPasswordRequest | 重置密码（验证码 + 新密码同提交） |
| af-auth-api | UserInfoResponse | me 接口（资料 + 角色 + 权限，前端按钮级控制） |
| af-auth-impl | SmsSender / EmailSender | 通道抽象接口 |
| af-auth-impl | LogSmsSender / LogEmailSender | 开发环境降级实现（@ConditionalOnProperty 条件装配） |
| af-auth-impl | LoginRateLimiter | IP 固定窗口计数，3 次弹图形码 / 10 次直接拒 |
| af-auth-impl | GraphicalCaptchaService | Hutool 线条验证码，GETDEL 原子取删，UUID captchaId |
| af-auth-impl | PasswordService | 找回密码：验证码校验 → 重置 → 吊销该用户全部 refresh token |
| af-auth-impl | PermissionProviderImpl | 权限 SPI 实现（四表 join 查 perm_code） |
| af-auth-impl | AuthInterceptor | /api/** 登录门卫，白名单精确匹配，未登录 401 + R 格式 |
| af-auth-impl | SecurityProperties | 白名单集中管理（内置兜底 + yml 扩展） |
| af-auth-impl | WebConfig | 注册拦截器，只拦 /api/** |

### 关键设计决策（P3.2）

| 决策项 | 结论 |
| --- | --- |
| 通道切换 | 接口 + @ConditionalOnProperty，换供应商只改配置零改业务代码 |
| refresh key 结构 | `af:jwt:refresh:{userId}:{jti}`，前段按用户维度组织 → 密码重置/封号可按前缀批量吊销 |
| 找回密码 | 两步：/code/sms\|email(scene=reset) 发码 → /password/reset 验证码+新密码同提交，重置后全端会话吊销 |
| 图形验证码 | 失败超限才强制（3 次/60s），正常用户永远看不到；captchaId 用 UUID 防猜 key |
| IP 限流 | 固定窗口 INCR+EXPIRE（登录场景粒度够用），滑动窗口留给 P13 接口级限流 |
| RBAC 鉴权 | 权限缓存在 Redis 不塞 JWT：token 精简 + 权限变更实时生效；admin 直接放行 |
| 拦截器 vs 过滤器 | 过滤器只装填 UserContext，拦截器做白名单判定（能拿 HandlerMethod） |

**编译验证：** 8 模块全部 SUCCESS，15.6s。

---

## P4 阶段交付明细

### 交付物清单（25 个文件，构建验证通过）

**工程骨架（frontend/）**

| 文件 | 职责 |
| --- | --- |
| package.json | Vue3.4 / Vite5 / TS5.4 / Pinia / VueRouter4 / Axios，`build` = 类型检查 + 构建 |
| vite.config.ts | `@` 别名、dev 代理 `/api → localhost:8080` |
| tsconfig.json | strict 全开、`@/*` 路径映射 |
| index.html | **首屏前同步定主题**（内联脚本防 FOUC 白屏闪烁） |
| src/main.ts | 只组装：pinia → router → guards → 组件注册（对应 af-bootstrap 定位） |

**设计令牌（P4 核心）**

| 文件 | 职责 |
| --- | --- |
| styles/tokens.css | **唯一颜色事实源**：形状令牌（字号/间距/圆角）双主题共享；颜色令牌浅色默认、`[data-theme='dark']` 只覆盖颜色。全部语义命名（bg/surface/text/border），组件禁裸色值 |
| styles/base.css | reset + 排版基线 + label-group/mono/hairline 工具类 |
| styles/index.css | 汇总入口 |

**契约层（与后端 DTO 逐字段对齐）**

| 文件 | 职责 |
| --- | --- |
| types/api.ts | `R<T>` / `PageResult<T>` / 错误码分段，对应 af-common R.java |
| types/auth.ts | 9 个认证 DTO（LoginRequest/TokenResponse/UserInfoResponse/...），对应 af-auth-api |

**请求层（五件事一次封装）**

| 文件 | 职责 |
| --- | --- |
| utils/request.ts | traceId 注入（铁律4前端端）、token 内存态、**401 刷新队列**（并发只刷一次）、R.code 唯一成功判据、ApiError 统一错误 |
| api/auth.ts | 9 个认证接口，与 AuthController 端点一一对应 |

**状态层 / 路由 / 组件**

| 文件 | 职责 |
| --- | --- |
| stores/theme.ts | 主题唯一入口：改 html[data-theme] + localStorage，CSS 变量联动 |
| stores/user.ts | 登录态 + hasPerm（admin 通配，对应 PermissionAspect）；restoreSession 刷新后恢复会话 |
| router/index.ts | meta 驱动权限模型：requiresAuth（对应白名单）/ perm（对应 @RequirePermission） |
| router/guards.ts | 三段式守卫：白名单直放 → 惰性恢复会话（进程内一次）→ 权限检查 |
| components/ | AfIcon（23 个线性 SVG 图标）/ AfButton / AfInput / AfCard / AfModal，全 `--token` 变量 |
| views/ | LoginView 占位 + WorkspaceView 最小壳子（验证主题切换/登出闭环）+ ForbiddenView |

### P4 关键设计决策

| 决策项 | 结论 |
| --- | --- |
| 双主题机制 | CSS 变量 + `html[data-theme]`，**首屏内联脚本同步定主题**防 FOUC；组件零感知 |
| 令牌命名 | 语义命名（bg/surface/text）而非颜色命名（white/black）——换主题不穿帮 |
| token 存储 | accessToken 只存内存防 XSS 窃取；refreshToken 落 localStorage 供刷新恢复会话 |
| 401 刷新 | **队列化**：首个 401 触发刷新，其余挂队列等新 token 统一重放，避免并发刷新互相作废 |
| 刷新接口 | 用裸 axios 直调不走拦截器，避免刷新请求自身 401 陷入递归 |
| 守卫 | 会话恢复进程内只执行一次（模块级布尔），避免每次路由跳转白调 /auth/refresh |
| 前端权限 | 前端 hasPerm 是"藏"不是"拦"——安全边界永远在后端 |
| 组件策略 | 自建 5 个基础组件起步（P0 决策），不套 Element/Ant，保住视觉辨识度 |

**构建验证：** `vue-tsc --noEmit` 0 错误 + `vite build` 828ms，113 模块，主包 gzip 64.6kB，三视图按路由懒加载分包。

---

## P5 阶段交付明细

### 交付物清单（3 个页面 + 1 路由 + 1 图标，构建验证通过）

| 文件 | 职责 |
| --- | --- |
| views/login/LoginView.vue | 登录页：单字段 identifier 透传，图形验证码靠 2008 触发，登录成功自动跳工作台 |
| views/login/RegisterView.vue | 注册页：邮箱/手机至少一项，密码预校验，发码 60s 冷却，注册成功自动登录 |
| views/login/ForgotPasswordView.vue | 找回密码页：两步流(发码→重置)，成功清本地 token 跳登录 |
| router/index.ts | 新增 /register /forgot-password 白名单路由（requiresAuth:false） |
| components/icon/AfIcon.vue | 新增 eye / eye-off 图标（密码可见性切换） |

### P5 关键设计决策

| 决策项 | 结论 |
| --- | --- |
| 多凭证登录 | 前端只传一个 identifier，后端智能识别邮箱/手机/用户名（LoginRequest.identifier） |
| 图形码触发 | 靠错误码 2008 触发而非前端计数——"失败≥3次"在服务端 Redis(IP 维度)，前端看不到真实次数，错误码是唯一可靠信号 |
| 发码冷却 | 后端 setIfAbsent 冷却 key(60s) + 前端倒计时 UX 两层；429(1006) 提示由后端 msg 直出 |
| 注册自动登录 | register 不返 token，用刚填密码调 login 一次往返进入工作台，体验顺滑 |
| 找回密码两步流 | 发码(scene=reset) → 验证码+新密码同提交；后端吊销全部 refresh token，前端 clearTokens 跳登录 |
| 密码可见性 | AfInput icon+iconClick 切换 type，纯前端 UX，不降级安全性 |
| 错误展示 | 直接用后端 ErrorCode.msg（不含技术细节），请求层 ApiError 暴露 code/msg |

**构建验证：** `vue-tsc --noEmit` 0 错误 + `vite build` 1.6s，119 模块，三视图独立分包（Login/Register/Forgot 各 ~2KB gzip JS），主包 gzip 65.1kB。

---

## P6 阶段交付明细

### 后端交付物清单（af-session-api + af-session-impl，共 15 个类/接口）

| 模块 | 类 | 职责 |
| --- | --- | --- |
| af-session-api | ConversationCreateRequest / ConversationUpdateRequest / ConversationResponse | 会话 DTO |
| af-session-api | MessageResponse / ChatRequest / ChatStreamEvent | 消息响应 / 聊天请求 / **SSE 事件协议(平台级)** |
| af-session-impl | Conversation / Message / MessageToolCall | 实体（Conversation 继承 BaseEntity；Message/MessageToolCall 按各自真实列显式声明，避免逻辑删除字段漂移） |
| af-session-impl | ConversationMapper / MessageMapper / MessageToolCallMapper | Mapper（selectMaxSeq / updateContent / updateStatus / incrementMessageCount） |
| af-session-impl | ConversationService | 会话 CRUD + 消息历史（断线重连恢复点）+ 归属校验 |
| af-session-impl | ChatService | **灵魂**：落 user 消息→建流式中 assistant 空壳→异步调引擎→增量落库+中继 SSE+心跳 |
| af-session-impl | AgentEngineClient（接口）/ MockAgentEngineClient | 引擎依赖倒置：Mock 造假流让端到端今天跑通，P7 换真实 HTTP 引擎 |
| af-session-impl | SessionConfig | 流式线程池 + 心跳/节流调度器 |
| af-session-impl | ConversationController / ChatController | /api/conversations/** + POST /api/chat/stream(SSE) |

### 前端交付物清单（4 个文件）

| 文件 | 职责 |
| --- | --- |
| types/chat.ts | 会话/消息/SSE 事件类型，逐字段对齐后端 DTO |
| api/session.ts | 会话 CRUD + chatStream(fetch 读 text/event-stream，SSE 解析) |
| views/demo/ChatDemoView.vue | 最小 SSE 验证页：会话列表+流式打字机+断线重连 |
| utils/request.ts + router/index.ts | 补 getAccessToken / patch；新增 /demo/chat 路由 |

### P6 关键设计决策

| 决策项 | 结论 |
| --- | --- |
| SSE 中继 | Java 在前后端间当二传手：鉴权+持久化(铁律1)+审计+配额统一收口；引擎可替换 |
| 事件协议 | 6 类事件(message_start/content_delta/tool_call/message_done/error/ping)，前端 P11 按此渲染 |
| 增量落库 | assistant 先插 status=0 空壳，节流 500ms 覆盖式写累积内容(铁律5: 任意中断 DB 有半成品) |
| 断线重连 | GET /conversations/{id}/messages 拉回已落库半消息即恢复，无需 resumption token |
| 引擎倒置 | AgentEngineClient 接口 + Mock 实现，P7 换 HttpAgentEngineClient 零改 ChatService |
| 白名单升级 | SecurityProperties 由精确匹配升级为 Ant 通配(支持 /api/conversations/** 含 {id}) |
| 上下文装配 | 每轮从 message 表拉最近 20 条拼 prompt(单一数据源，不缓存副本) |

**编译验证：** 后端 7 模块 `mvn compile` 全 SUCCESS；前端 `vue-tsc --noEmit` 0 错误 + `vite build` 3.33s，ChatDemoView 独立分包。

**runtime 验证：** 已在项目隔离环境完成：MySQL 9.7（127.0.0.1:3308，16 张表）+ Redis 7.4（6379）+ SpringBoot 8080。管理员登录成功；SSE `message_start/content_delta/message_done` 全事件通过；user/assistant 消息及模型/token 元数据真实落库。

**联调修复：** JDBC 编码参数改为 `UTF-8`；修正种子 BCrypt；会话 ID 改为 32 位 UUID；Message 实体按真实表列映射；Java HttpClient 强制 HTTP/1.1 兼容 Uvicorn。

---

## P7 阶段交付明细

### Python Agent 引擎（`agent-engine/`）

| 文件/模块 | 职责 |
| --- | --- |
| `pyproject.toml` | FastAPI / Uvicorn / LangGraph / Pydantic Settings / pytest 依赖与 Python 3.13 基线 |
| `app/config.py` | `AGENT_ENGINE_` 前缀集中配置，启动期类型校验 |
| `app/graph/state.py` | AgentState + `add_messages` reducer |
| `app/graph/agent_graph.py` | LangGraph `START → agent → END` 状态图 + MemorySaver checkpoint，conversationId 作 thread_id |
| `app/model/` | 模型工厂 seam + 确定性开发模型；后续切 OpenAI-compatible 不改图结构 |
| `app/main.py` | `/health` + `/v1/chat/stream` NDJSON 流，traceId 贯通、断开检测、错误事件 |
| `tests/test_engine.py` | 健康检查与流式事件契约测试（2 passed） |

### Java 接入

| 文件 | 职责 |
| --- | --- |
| `AgentEngineClient` | 流式接口返回 `StreamResult(model/tokenInput/tokenOutput)` 元数据 |
| `HttpAgentEngineClient` | HTTP/1.1 调 Python、逐行解析 NDJSON、只向 ChatService 推 content_delta |
| `MockAgentEngineClient` | `provider=mock` 条件装配，作为无 Python 环境的开发降级 |
| `ChatService` | 保持原编排，只接收引擎元数据并写入 SSE message_done + MySQL |
| `application-dev.yml` | `AGENT_ENGINE_PROVIDER=mock|http` + `AGENT_ENGINE_URL` 配置切换 |

**构建与全链路验证：** Python `pytest` 2 passed；Java 全模块 `mvn compile/install` 通过；Vue `vite build` 123 modules、2.19s。真实链路 `前端/客户端 → Java 8080 → Python LangGraph 8000 → Java SSE → MySQL` 通过，最终模型名 `agentforge-dev-model`、tokenInput=10、tokenOutput=64 均落库。

---

## P8 阶段交付明细

### Java 工具治理与内部契约

| 文件/模块 | 职责 |
| --- | --- |
| `af-common/common/tool/*` | `ToolDescriptor` / `ToolCallRequest` / `ToolCallResult` / `ToolStreamEvent`，统一 Java/Python/SSE 工具契约 |
| `af-agent/tool/*` | tool、mcp_server 实体/Mapper/DTO/Service/Controller，租户隔离、注册、启停、动态发现 |
| `InternalToolController` | `GET /internal/tools`，按 `X-Tenant-Id` 返回启用工具的内部执行契约 |
| `ToolResponse` | 管理端安全 DTO，不返回 MCP command/args/headers 等执行凭据 |
| `backend/sql/01-mysql-schema.sql` | calculator/current_time 种子、工具治理权限、`message_tool_call.call_id` 唯一审计键 |

### Python Tool Gateway 与 LangGraph 工具回路

| 文件/模块 | 职责 |
| --- | --- |
| `app/tools/registry.py` | 从 Java 注册中心动态发现租户工具，Java 不可用时开发期降级为内置描述 |
| `app/tools/gateway.py` | JSON Schema 入参/出参校验、超时隔离、builtin/MCP 分发、统一错误结果 |
| `app/tools/builtin.py` | AST 白名单安全计算器 + IANA 时区当前时间；拒绝 eval 与危险语法 |
| `app/tools/mcp_client.py` | MCP Python SDK v2 适配，支持 stdio 与 Streamable HTTP 客户端路径 |
| `app/graph/agent_graph.py` | `START → agent → tool_start → tools → agent → END`，最大轮数与 recursion_limit 防循环 |
| `app/main.py` | 工具开始/结果/错误事件即时输出 NDJSON，最终回复继续拆为 content_delta |

### Java SSE、前端过程展示与审计

- `AgentEngineClient.stream(...)` 显式传入 `tenantId`，Java 请求体使用 `tenant_id`，多租户工具发现不再依赖 Python 默认值。
- `HttpAgentEngineClient` 解析 `tool_call_start/result/error`，`ChatService` 立即中继前端并写 `message_tool_call`。
- `callId` 同时贯穿 Python、Java、SSE、MySQL，并用 `(tenant_id, call_id)` 唯一索引完成更新，不再依赖进程内临时映射。
- `ChatDemoView.vue` 展示工具名称、调用中/成功/失败/超时、参数、结果、耗时。
- 工具异常统一为业务错误；除零返回“除数不能为 0”，不泄露 Python/Decimal 内部异常类型。

### P8 真实验证结果

- Java：8 个 Maven reactor 模块 `BUILD SUCCESS`，最终 `install` 通过。
- Python：`pytest` **7 passed**，含安全表达式、危险语法、除零脱敏、calculator/current_time 流事件测试。
- 前端：`vue-tsc --noEmit` 通过；Vite **123 modules**，生产构建 952ms。
- 工具发现：`GET /internal/tools` 返回 calculator 与 get_current_time；`GET /api/tools` 已确认无 `executorConfig` 敏感字段。
- 成功路径：`12 * (3 + 4) = 84`、`Asia/Shanghai` 当前时间均产生 `message_start → tool_call_start → tool_call_result → content_delta → message_done`。
- 失败路径：`1 / 0` 产生 `tool_call_error(INVALID_EXPRESSION)`，前端收到稳定文案，MySQL 审计状态为 2。
- 审计：参数、结果/错误、状态、耗时、started_at、finished_at 与 callId 均真实落库。

**已知边界：** 本阶段已完成 MCP 能力层和 SDK 适配代码，但尚未连接第三方真实 MCP Server 做 stdio/Streamable HTTP 生产兼容性验收；旧式 SSE MCP transport 也未单独验证。该项作为后续外部集成增强，不影响内置工具与平台工具链路完成判定。

---

## P9 阶段交付明细

### P9 设计（docs/09-Skill设计.md）

- Skill = 受信提示词技能包：`triggers_json`（keyword/regex，OR 命中）+ 全文（渐进式披露）。
- 三态披露：L0 元数据（每次会话）→ L1 命中拉全文 → L2 注入 system 域 + 工具白名单收窄。
- 权限边界：技能只收窄不放宽（交集语义）；全文 8KB 上限；system 域与用户输入显式隔离；`message_skill_call` 双轨审计。
- **内容存储（用户决策 2026-08-20）：SKILL.md 文件为主**，DB 只存元数据 + `skill_file_url`；`content_json` 仅作 API 内联兼容。

### P9 块 1 交付物清单（Java 侧，已运行验证）

| 文件/模块 | 职责 |
| --- | --- |
| `af-common/common/skill/SkillDescriptor` | 技能发现契约（L0 content=null / L1 含全文），对齐 ToolDescriptor 模式 |
| `af-common/.../ErrorCode` | 新增 3308 内容超限 / 3309 文件读取失败 |
| `af-agent/skill/entity|mapper|dto/*` | SkillEntity / SkillMapper / CreateRequest(+skillFileUrl) / Response / StatusRequest |
| `af-agent/skill/service/SkillRegistryService` | 管理 CRUD + 租户隔离 + 渐进式披露 + 文件优先全文加载 |
| `af-agent/skill/repo/SkillFileRepository` | SKILL.md 解析（YAML frontmatter + markdown）、路径白名单防穿越、8KB 上限 |
| `af-agent/skill/controller/SkillController` | 管理端 `/api/skills`（@RequirePermission agent:skill:read/write） |
| `af-agent/skill/controller/InternalSkillController` | 内部发现 `/internal/skills`（L0）+ `/internal/skills/{code}`（L1） |
| `backend/skill-repo/{unit_converter,text_polish}/SKILL.md` | 种子技能文件（文件为全文单一事实源） |
| `backend/sql/01-mysql-schema.sql` | 种子替换为 unit_converter/text_polish（skill_file_url 指向文件）+ unit_converter 工具行 |
| `application.yml` | `app.skill.repo-dir`（默认 `${user.dir}/skill-repo`，env `SKILL_REPO_DIR` 可覆盖） |

### P9 块 1 真实验证结果

- 构建：8 模块 `mvn package -DskipTests` SUCCESS（42s，fat-jar 47MB）。
- L0：`GET /internal/skills` 返回两个技能，content=null（渐进式披露生效），triggers 完整。
- L1：`GET /internal/skills/unit_converter` 返回 frontmatter 解析的 allowed_tools + markdown 正文（583 字符）。
- 安全：`/internal/skills/../etc/passwd` 被 Tomcat 400 拒；不存在技能返回 3306；未登录访问 `/api/skills` 返回 401。
- 管理端：admin 登录后 `/api/skills` 返回技能含全文（编辑回显），builtin=true。
- 服务已回 8080 端口（沙箱 `SERVER__PORT=0` 会覆盖端口，启动时 `unset` 处理）。

**下一步：块 3 SSE 技能事件 + Java 双轨审计（message_skill_call）联调 + 全链路回归。**

### P9 块 2 交付物清单（Python 侧，已运行验证）

| 文件/模块 | 职责 |
| --- | --- |
| `agent-engine/app/skills/schemas.py` | SkillContent 改造为 SKILL.md 形态（markdown + allowed_tools），steps 保留为兼容通道 |
| `agent-engine/app/skills/registry.py` | SkillRegistryClient：L0 元数据 / L1 全文，TTL 缓存，Java 不可用降级空列表 |
| `agent-engine/app/skills/matcher.py` | keyword/regex OR 命中匹配 |
| `agent-engine/app/skills/engine.py` | 双通道执行：通道 A steps 编排（DB 兼容）/ 通道 B SKILL.md 提示词注入 + allowed_tools 收窄（只收窄不放大） |
| `agent-engine/app/tools/builtin.py` | 新增 unit_converter 内置工具（长度/重量/温度/面积，基准换算 + 温度偏移），注册 handler + descriptor |
| `agent-engine/app/model/deterministic.py` | plan_tool 扩展中文单位换算启发式（"5 公斤等于多少斤"→unit_converter 参数）；build_tool_reply 模板；build_reply 支持技能注入 |
| `agent-engine/app/graph/agent_graph.py` | SkillEngine 注入 model；skill 事件流（skill_call_start/result/error + tool_events 转发） |
| `agent-engine/tests/test_skills.py` | 重写为 SKILL.md 技能用例：命中→受限调用闭环、纯提示词零工具、只收窄不放大 |
| `agent-engine/tests/test_tools.py` | +unit_converter 单测（重量/温度偏移/跨类别拒/未知单位拒） |

### P9 块 2 真实验证结果

- 单测：`pytest` 21 passed（含收窄语义：技能白名单不含 calculator 时即使输入含算式也不得调用）。
- 全链路 ① 单位换算技能：`skill_call_start(unit_converter)` → `tool_call_start(tool_id=3, 5kg→jin)` → `tool_call_result(10)` → `skill_call_result("换算结果：5 kg = 10 jin")` → content_delta/message_done。
- 全链路 ② 文案润色技能（纯提示词）：`skill_call_start(text_polish)` → `skill_call_result`（零工具事件，全文注入上下文）。
- 全链路 ③ 正则触发："6 米是多少厘米" 命中 regex → `6 m = 600 cm`（tool_id 正确回传）。
- 修复：技能内工具事件 tool_id 原硬编码 null，改为从工具描述符回传（审计链路需要）。

### P9 块 3 交付物清单（Java 技能事件接收 + 双轨审计，已运行验证）

| 文件/模块 | 职责 |
| --- | --- |
| `af-common/.../common/skill/SkillStreamEvent.java` | 技能生命周期事件 record（skill_call_start/result/error），与 ToolStreamEvent 对齐 |
| `af-session-impl/.../entity/MessageSkillCall.java` | message_skill_call 实体（审计型，不继承 BaseEntity） |
| `af-session-impl/.../mapper/MessageSkillCallMapper.java` | insert + finishByCallId（uk_tenant_call 唯一键收尾） |
| `af-session-impl/.../engine/AgentEngineClient.java` | 接口签名 + `Consumer<SkillStreamEvent> onSkillEvent` 回调 |
| `af-session-impl/.../engine/HttpAgentEngineClient.java` | NDJSON 流解析 skill_call_* 事件 → SkillStreamEvent |
| `af-session-impl/.../engine/MockAgentEngineClient.java` | 适配新签名（mock 不产技能事件） |
| `af-session-impl/.../service/ChatService.java` | handleSkillEvent：start 落库(status=0, call_args 存触发上下文) / result/error 更新(status=1/2/3) + SSE 中继；技能内工具仍走 handleToolEvent 双轨 |
| `agent-engine/app/graph/agent_graph.py` | skill_call_start 事件补 call_args（触发 prompt 摘要，供审计） |

### P9 块 3 真实验证结果

- 编译：8 模块 `mvn compile` 零错误；`pytest` 21 passed。
- Java 启动模式：需 `AGENT_ENGINE_PROVIDER=http AGENT_ENGINE_BASE_URL=http://127.0.0.1:8000`（默认 mock 引擎不产生真实技能事件）。
- 场景 ① 技能+工具（"5 公斤等于多少斤"）：SSE `skill_call_start(callArgs={"prompt":...})` → `tool_call_start(toolId=3)` → `tool_call_result(10)` → `skill_call_result("换算结果：5 kg = 10 jin")`；落库 `message_skill_call`(status=1, 3ms, 含 prompt 入参与结果) + `message_tool_call`(status=1, 同 message_id 关联)。
- 场景 ② 纯提示词技能（"帮我润色…"）：SSE 仅 skill 轨（零 tool 事件）；落库 `message_skill_call`(text_polish, status=1) 且该 message_id 下 `message_tool_call` 计数 = 0。
- 场景 ③ 技能内工具失败（"5 米等于多少摄氏度"）：SSE `tool_call_error` → `skill_call_error`；双轨 status=2，error_msg 均为 "单位类别不兼容: m(length) → celsius(temperature)"。
- 双轨关联：技能 call_id 与工具 call_id 各自独立，通过 message_id 关联——满足"技能级审计 + 工具级审计"双轨设计。

---

## P10–P14 阶段交付明细（终版）

### P10 代码执行沙箱（已完成）
- `agent-engine/app/sandbox/executor.py`：AST 预检（import 白名单 + 调用黑名单 + 魔术属性拦截）+ 进程组隔离（`start_new_session` + `killpg` 强杀）+ 资源限额（CPU 2s / 内存 256MB / fd 32 / 栈 8MB）+ 输出截断 8KB。
- `code_exec` 内置工具闭环：`tool_call_start` → 沙箱执行 → `tool_call_result`（stdout/stderr/exit_code/duration_ms）。
- 修复：DB 种子中 code_exec `output_schema` 的 `error_code`/`exit_code` 改为 `anyOf null` 可空，解决成功执行时结果校验报 `None is not of type 'string'`。

### P11 前端 Agent 工作台（已完成）
- 路由重构：`WorkspaceView` 壳（侧栏 248px + 顶栏 52px）+ 子路由 `/workspace/chat|knowledge|tools|obs`，导航高亮 + `meta.perm` 权限守卫。
- `ChatView`：会话列表 + 消息流式渲染（AfMarkdown 代码高亮/行号/复制）+ 工具/技能调用时间线卡片 + 停止按钮（AbortController）+ 建议 chips。
- `AfMarkdown`：自实现轻量渲染器（v-html + 手写转义杜绝 XSS；块级：标题/列表/引用/hr/代码块；行内：粗体/code/链接）。
- `KnowledgeView`：检索（topK 可选）+ 结果命中高亮 + 文档列表 + 新建弹窗。
- `ToolsView`：工具网格（executorType/transport/timeoutMs）+ 技能网格（版本/triggers/builtin）。
- `ObservabilityView`：今日调用/输入/输出/成本四卡片 + 配额进度条（softAlert/exceeded 态）+ 7 天趋势 SVG 图 + 审计日志筛选分页。
- 构建：`vue-tsc --noEmit` 零错误，`vite build` 通过（index 177KB / gzip 68.6KB）。

### P12 RAG 知识库（已完成）
- 降级方案：向量索引在 Python 侧 `data/knowledge/`（无需 PG），MySQL `knowledge_doc` 表存元数据。
- `/api/knowledge/index`（标题+正文分块+向量化）与 `/api/knowledge/search`（相似度排序 + 溯源）。
- 修复：`knowledge_doc` 缺 `deleted_at` 列导致 BaseEntity 逻辑删除异常 → ALTER TABLE + 同步种子 DDL。

### P13 可观测性与计费（已完成）
- `audit_log` 公共审计底座（AuditService 统一写入，登录/聊天/工具/技能全埋点）。
- `api_usage` 用量记账 + Redis 配额预检 + `/api/usage/stats`（今日用量/配额/软阈值告警）。
- `/api/audit/logs` 查询接口（action 过滤 + 分页）。

### P14 部署与一键启动（已完成）
- `scripts/start-all.sh`：幂等五端启动（MySQL 3308 / Redis 6379 / Agent Engine 8000 / Java 8080 / Vite 5173），日志统一入 `logs/`。
- 联调修复：
  - 登录字段 `accessToken`（非 token）、聊天请求字段 `content`（非 message）。
  - unit_converter 技能触发词移除"等于多少"（避免劫持计算器意图），正则支持小数 → "计算 (15+7)*3-2 等于多少"正确路由 calculator 工具，"5.5 kg 转换成斤"仍命中技能。
- 演示验证（curl E2E）：计算器 `(15+7)*3-2=64` ✅、当前时间 ✅、code_exec 沙箱 `sum(1..100)=5050` ✅、unit_converter 技能 ✅、知识库检索 ✅、配额统计 ✅。

---

## 环境检查结果

| 依赖 | 状态 | 版本 |
| --- | --- | --- |
| JDK | ✅ | 17.0.2（满足 SpringBoot 3 基线） |
| Maven | ✅ | 3.9.16 |
| MySQL | ✅ 项目隔离实例，agentforge 库 16 张表已初始化 | 9.7.0，127.0.0.1:3308 |
| Node.js | ✅ | 22.x |
| Python | ✅ 隔离 venv，LangGraph/FastAPI 已安装 | 3.13 |
| PostgreSQL | ❌ 待安装 | 需 16+ 及 pgvector 扩展（P12 用） |
| Redis | ✅ 项目源码编译实例，PING/读写通过 | 7.4.0，127.0.0.1:6379 |
| Docker | ❌ 未安装 | 沙箱改用进程级隔离方案 |

> P1 阶段会给出 PostgreSQL + pgvector 与 Redis 的本地安装步骤。Docker 缺失不阻塞进度：沙箱采用进程级隔离（fork + rlimit + chroot 风格白名单），反而更贴近真实生产中「不允许 DinD」的受限环境。
