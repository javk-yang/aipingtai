# skillzip 上传闭环验证结果

## 功能结果
- 合法 `demo.skillzip` 导入成功：HTTP 200，业务码 0，技能编码 `demo_skill`，版本 `1.0.0`，路径 `tenant-1/demo_skill/SKILL.md`。
- 重复编码再次上传被拒绝：HTTP 409，业务码 3319。
- 缺少 `SKILL.md`：HTTP 400，业务码 3318。
- 含 `../` 路径：HTTP 400，业务码 3317。
- 含绝对路径：HTTP 400，业务码 3317。
- 含危险脚本扩展 `.sh`：HTTP 400，业务码 3317。
- 包含两个 `SKILL.md`：HTTP 400，业务码 3318。
- 上传成功后 `/api/skills` 列表中 `demo_skill` 数量为 1。
- `/internal/skills` 元数据发现和 `/internal/skills/demo_skill` 全文加载均成功，说明租户目录路径与现有技能仓库读取逻辑兼容。

## 期间发现并处理
- 首次测试使用旧 Java 进程，上传接口返回空 500；日志显示旧 fat JAR 的 Spring shutdown hook 兼容性异常。重新启动最新构建后接口恢复正常。
- 全量构建通过：Java Maven 和 Vue `npm run build` 均成功。

## 当前状态
- `POST /api/skills/upload` 已可直接接收 `.skillzip`。
- 前端工具与技能页已提供“导入 skillzip”按钮、上传进度和自动刷新。
- 安全校验、租户隔离、重复编码拒绝、审计、失败清理已完成并通过回归。
