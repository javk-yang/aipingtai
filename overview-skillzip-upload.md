# AgentForge skillzip 上传功能概览

## 已完成
- 新增 `POST /api/skills/upload` multipart 上传接口，沿用 `agent:skill:write` 权限。
- 新增 `SkillPackageService`：扩展名/大小/条目数/解压总量校验，ZIP 路径穿越和绝对路径拒绝，危险扩展拒绝，唯一 `SKILL.md` 校验，YAML front matter 校验，`name`/`description`/`version`/`allowed_tools` 校验，租户目录隔离，原子移动，数据库事务回滚，审计记录。
- 新增错误码：`SKILL_PACKAGE_INVALID`、`SKILL_PACKAGE_TOO_LARGE`、`SKILL_PACKAGE_UNSAFE`、`SKILL_PACKAGE_MISSING_MANIFEST`、`SKILL_PACKAGE_DUPLICATE`。
- 前端技能页增加 `.skillzip` 选择导入入口、上传进度和自动刷新；API 增加上传方法和结果类型。

## 构建验证
- Java：在 `backend` 目录执行 Maven 构建通过。
- Vue：在 `frontend` 目录执行 `npm run build` 通过。
- 期间修复了前端 API 中遗漏 `skillsApi.list()` 以及上传代码插入导致的 TypeScript 语法错误。

## 当前注意事项
- 仍需在运行中的 8090 服务上执行真实上传冒烟和恶意 ZIP 回归。
- 上传后的 `skill_file_url` 使用 `tenant-{tenantId}/{code}/SKILL.md`，用于租户隔离；需要确认现有运行时技能仓库路径和已内置技能路径兼容。
- `allowed_tools` 若声明工具，导入时要求该租户已有同编码且启用的工具。
