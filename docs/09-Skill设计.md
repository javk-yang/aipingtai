# P9 Skill 技能系统设计

> 阶段：P9 ｜ 状态：设计稿 ｜ 前置：P8 MCP 工具集成层（已完成）
> 原则：先设计、后分块实现，不一次性生成全部代码

---

## 0. 目标与边界

### 做什么
1. **元数据规范**：Skill 的 `triggers_json` / `content_json` 语义正式化，定义可校验的 JSON Schema。
2. **渐进式加载**：三态披露（L0 元数据 → L1 全文 → L2 执行），命中技能前不加载全文，控制 token 与延迟。
3. **权限边界**：技能不放大工具权限（只收窄），内容写入静态校验，执行域隔离，双轨审计。
4. **验证链路**：2 个内置技能走通「意图命中 → 全文注入 → 工具受限调用 → SSE 展示 → MySQL 审计」。

### 不做什么（留到后续阶段）
- **技能内执行任意代码**：P10 代码沙箱的职责，P9 的 Skill 是「提示词技能」——把受信指令 + 模板注入 system prompt，自身不执行代码。
- **技能市场 / 跨租户分发**：只做租户内注册、启停、发现。
- **语义匹配（embedding）**：`triggers` 只支持 keyword / regex，semantic 类型预留，P12 RAG 后再启用。

---

## 1. Skill 元数据规范

### 1.1 表结构（P1 已建，无需 DDL 变更）

`skill` 表两个 JSON 列即渐进式披露的物理载体：

| 列 | 内容 | 披露时机 |
| --- | --- | --- |
| `triggers_json` | 触发规则（轻量，可下发全量） | L0 每次会话 |
| `content_json` | 技能全文（指令 + 步骤 + 模板） | L1 命中后按需拉取 |

### 1.2 triggers_json Schema

```json
{
  "triggers": [
    { "type": "keyword", "values": ["帮我算", "计算", "等于多少"] },
    { "type": "regex",   "pattern": "\\d+\\s*[+\\-*/()]{1,}" }
  ]
}
```

- 同一技能内多条 trigger 为 **OR** 关系，任一中即命中。
- `keyword`：子串包含匹配（大小写不敏感）。
- `regex`：Java/Python 双端同一正则（仅允许安全子集：数字、四则、括号、中文、`[` `]` `|` 等字符类）。
- `semantic`：预留类型，写入时校验拒绝（`invalid trigger type`），P12 解锁。

### 1.3 内容存储：SKILL.md 文件为主（决策 2026-08）

**技能全文以 SKILL.md 文件为单一事实源**（贴近 Anthropic Agent Skills 标准），DB 只存元数据 + `skill_file_url` 相对路径；`content_json` 保留为 API 内联创建的兼容通道。

- 文件位置：`{repo-dir}/{skill_code}/SKILL.md`，`repo-dir` 由 `app.skill.repo-dir` 配置（默认 `${user.dir}/skill-repo`，env `SKILL_REPO_DIR` 可覆盖）。
- 文件格式：YAML frontmatter + Markdown 正文。

```markdown
---
name: 单位换算
description: 把自然语言里的单位换算成标准值
version: 1.0.0
allowed_tools:
  - unit_converter
---

当用户请求单位换算（长度/重量/温度/面积）时使用本技能……
```

- 解析后的 content 扁平 Map：`{name, description, version, allowed_tools[], markdown}`。
- 加载优先级：`skill_file_url` 非空 → 读文件；否则回退 `content_json`（API 内联）。
- 路径安全：`SkillFileRepository` 拒绝绝对路径与 `..` 穿越，只允许 `{repo-dir}` 白名单内读取。
- `allowed_tools`：技能可使用的工具编码白名单。**技能只收窄、不放宽**——LangGraph 可用工具 = 全局启用工具 ∩ 技能白名单。
- 全文总长限制 8KB（防止技能内容本身成为 prompt 注入载体），文件加载时校验。

### 1.4 内置技能种子

| skill_code | 名称 | 触发 | 白名单工具 |
| --- | --- | --- | --- |
| `unit_converter` | 单位换算 | keyword: 换算/转换/单位 + regex 单位模式 | `unit_converter`（新增内置工具） |
| `text_polish` | 文案润色 | keyword: 润色/改写/翻译/美化 | 无（纯提示词技能） |

> 两技能各验一条路径：`unit_converter` 验证「技能命中 → 受限工具调用」闭环；`text_polish` 验证技能层独立于工具层的价值（allowed_tools 为空 = 纯指令增强）。

---

## 2. 渐进式加载流程（三态披露）

```
┌─────────────┐  L0 元数据清单(无全文)  ┌──────────────────┐
│ Java 8080   │ ◄────────────────────── │ Python 引擎 8000 │
│ /internal/  │                        │ SkillMatcher      │
│ skills      │ ──────────────────────► │ 每次会话预取,     │
│             │                        │ 关键词/regex 命中 │
└─────────────┘                        └────────┬─────────┘
      ▲                                        │ 命中
      │ L1 全文拉取                            ▼
      │ /internal/skills/{code}/content  ┌─────────────┐
      │ 命中才请求, 8KB 上限              │ 注入 system │
      └────────────────────────────────── │ prompt + 按 │
                                          │ allowed_    │
                                          │ tools 收窄  │
                                          │ 工具列表    │
                                          └─────────────┘
```

| 态 | 数据 | 时机 | 成本 |
| --- | --- | --- | --- |
| L0 元数据 | code/name/description/triggers/allowed_tools | 会话初始化（复用 P8 发现节奏） | 低，可全量缓存 |
| L1 全文 | instruction/steps/templates | 命中后单次拉取 | 中，只拉命中项 |
| L2 执行 | 注入 prompt + 工具过滤 + 审计 | 请求执行期 | 与普通工具调用一致 |

**三端分工**（对齐铁律「智能层无状态」）：

- **Java**：管理端 CRUD + 静态校验；`/internal/skills`（L0 清单）+ `/internal/skills/{code}/content`（L1 全文）。数据唯一入口（铁律 1）。
- **Python**：`SkillRegistryClient`（对齐 `ToolRegistryClient`，Java 不可用降级内置技能）→ `SkillMatcher`（keyword/regex 命中）→ graph 内注入。
- **SSE**：新增 `skill_start` / `skill_result` / `skill_error` 三事件，前端渲染技能卡片；技能内工具调用仍走既有 `tool_call_*` 事件（双轨可见）。

---

## 3. LangGraph 注入设计

- `AgentState` 新增 `active_skills: list[str]` 字段（`operator.add` reducer）。
- `agent` 节点前插 `skill_router` 节点：matcher 命中 → 拉 L1 全文 → 全文以固定 `[SKILL:{code}] ... [/SKILL]` 块拼入 system prompt 尾部（技能域与用户输入域用显式分隔符隔离，防内容越权）。
- 工具列表构造：`tools = [t for t in all_tools if t.code in allowed_tools]`（命中技能时）或保持全局（未命中时）。**多技能命中取白名单并集**。
- 技能调用的工具结果仍走 `tool_call_result` 回流 agent，最终回复携带技能摘要。

---

## 4. 权限边界

| 维度 | 机制 |
| --- | --- |
| 工具权限 | allowed_tools 白名单收窄，技能永远无法调用未声明工具（交集语义） |
| 内容注入 | 技能全文静态校验：`forbidden_patterns`（提示词泄露诱导、危险指令如"删除数据库/rm -rf"）；写入时拒绝，执行时双保险 |
| 执行域隔离 | 技能内容只进 system 域，与用户消息、工具结果分离，分隔符包夹 |
| 管理 RBAC | 管理端沿用 `agent:skill:read/write`（P1 已建）；执行端命中即用（技能由租户管理员发布，属受信内容） |
| 审计 | 技能级 → `message_skill_call`（表已建，双轨）；技能内工具调用 → `message_tool_call`（不丢失） |
| 租户隔离 | `/internal/skills` 按 `X-Tenant-Id` 过滤（对齐 P8 工具发现） |

---

## 5. 分块实施计划

| 块 | 内容 | 交付验证 |
| --- | --- | --- |
| 块 1 | Java Skill 管理域：实体/Mapper/DTO/Service/Controller + `SkillFileRepository`（SKILL.md 加载、路径白名单）+ `/internal/skills` 元数据发现 + `/internal/skills/{code}` 全文拉取 + 种子技能文件与 SQL | `mvn compile` + `GET /internal/skills` 与详情接口 |
| 块 2 | Python：`SkillRegistryClient` + `SkillMatcher` + graph `skill_router` 注入 + allowed_tools 过滤 + `unit_converter` 内置工具实现 | `pytest`（matcher/注入/换算工具单测） |
| 块 3 | SSE `skill_start/result/error` 事件 + `message_skill_call` 落库 + 前端技能卡片 | 端到端 curl / 前端 demo |
| 块 4 | 种子技能（math/time）+ 全链路验证（命中/未命中/审计/降级）+ PROGRESS 更新 | 全链路 curl + 落库核对 |

**每块独立可验证，块间无阻塞依赖，可连续推进。**

---

## 6. 验证用例（块 4）

| 用例 | 输入 | 预期 |
| --- | --- | --- |
| 命中+工具 | 「帮我算一下 12*(3+4)」 | skill_start(math_skill) → tool_call_start(calculator) → tool_call_result → skill_result，SSE 顺序正确 |
| 命中未用工具 | 「现在重庆几点了」 | skill_start(time_skill) → tool_call(get_current_time) → skill_result |
| 未命中 | 「写一首关于火锅的诗」 | 无 skill 事件，普通 content_delta |
| 白名单收窄 | 手工构造技能 allowed_tools 不含某工具 | 引擎工具列表被过滤，越权工具不可见 |
| 降级 | 停 Java 后提问 | 引擎回落到内置技能（math/time），不报错 |
| 审计 | 上述任一命中用例 | message_skill_call + message_tool_call 双轨落库，call_id 一致 |

---

## 7. 风险与对策

| 风险 | 对策 |
| --- | --- |
| 关键词误命中（如「时间」泛用） | regex 收紧 + 未命中回落全局工具；语义匹配留给 P12 |
| 技能内容 prompt 注入 | 8KB 上限 + forbidden_patterns 静态校验 + system 域分隔隔离 |
| Java 不可用 | Python 内置技能降级（对齐 P8 降级策略） |
| 多技能叠加冲突 | 白名单并集 + 全文按命中顺序拼接，单轮最多 3 个技能 |
