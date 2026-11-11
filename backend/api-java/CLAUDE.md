# API Java 开发约定

更新日期：2026-11-11。适用于本模块的后续维护。

以源码、POM、迁移和测试为事实来源。先阅读[根 README](../../README.md)、[项目现状](../../docs/项目现状.md)、[架构说明](../../docs/架构说明.md)、[本地开发启动](../../docs/本地开发启动.md)。

- 入口：`com.quest.api.ApiApplication`；Java 27、Spring Boot 4.1.2、Jackson 3（`tools.jackson`）。主要持久化使用 JdbcTemplate。
- `local` 禁用业务持久化；不得增加伪造成功或内存 CRUD 以掩盖缺失的数据库。`deployment` 使用真实 PostgreSQL，Flyway 默认关闭。
- HTTP 契约在各 Controller；业务身份来自 `SessionSecurityFilter`，不能直接信任请求中的用户 ID。管理员/学生角色和会话归属仍在业务服务校验。
- 公共导入由 `publicbank` 包实现，考纲由 `syllabus` 包实现，教练由 `coach` 包实现，资料由 `materials` 包实现。不要将这些流程全部描述成 Worker 功能。
- AI 外发统一经 `PublicAiClient`，带内部签名与调用审计。教练工具状态由上游 Python 发出，Java 转发并校验来源，不能宣称 Java 自带联网搜索器。
- 学习资料默认关闭；事务写入资料任务与 Outbox，专用 Worker 通过三个 HMAC 内部接口执行。旧通用 Outbox 仍未实现。
- 保留请求幂等、版本并发校验、租约及失败后手动重试语义；新增迁移不要回写已部署迁移。
- 不读取或输出真实密钥，不把配置样例中出现的 Redis/MinIO 当作已接入能力。重测试、真实数据库/模型/MQ 的结果必须与静态检查分开报告。

相关细节：[用户模块](docs/用户模块与安全初始化.md)、[公共题库接口](docs/公共题库接口.md)、[IDEA 配置](docs/IDEA启动配置.md)。
