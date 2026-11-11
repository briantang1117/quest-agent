# Java 后端与 AI 服务

更新日期：2026-11-11。本文依据源码和配置整理；总览见[根 README](../README.md)、[项目现状](../docs/项目现状.md)、[架构说明](../docs/架构说明.md)、[本地开发启动](../docs/本地开发启动.md)。

`pom.xml` 聚合 `common`、`api-java`、`worker-java`。POM 声明 Java 27、Spring Boot 4.1.2、MyBatis-Plus 3.5.18；Worker 显式依赖 RocketMQ Client 5.3.1。API 使用 Spring MVC、Security OAuth2 Resource Server、Validation、Actuator、PostgreSQL、Flyway 和 Jackson 3；主要业务 SQL 通过 JdbcTemplate 执行，不能因依赖 MyBatis-Plus 就称已采用 Mapper 层。Python AI 为独立工程，参见其[说明](ai-python/README.md)。

## 已有代码

- `common`：API 响应模型、题目结构和选择题判分。
- `api-java`：用户与会话、管理员管理、公共题库与 PDF 分批导入、考纲版本及异步整理/向量检索、学生教练普通响应与 SSE、学习资料任务与下载、AI 调用审计。
- `worker-java`：学习资料专用 Outbox 转发和 RocketMQ 消费。通过签名内部 API 认领、确认、执行任务，不直接连接业务数据库；模型生成仍由 API 调 Python 完成。

考纲索引将向量存入 PostgreSQL pgvector；当前检索在 Java 中对同版本、科目和模型维度匹配的候选计算余弦相似度，并提供同版本关键词降级，不应描述为已使用数据库 ANN 搜索。教练会话绑定创建时的考纲版本，工具执行在 Python，Java 接收工具状态并校验来源。

资料 MQ 的代码已经存在；旧通用 Outbox 未实现，`OUTBOX_ENABLED=true` 会明确拒绝启动。不要把两者混为一谈。公共题库 PDF 分批执行及考纲调度位于 API 进程，不由 Worker 统一承接。

## 启动边界

默认 `local` 禁用数据源和 Flyway，只用于进程健康检查，不提供内存业务替身。`deployment` 使用 PostgreSQL，readiness 纳入数据库。迁移文件已有 V1–V13，日常迁移默认关闭。

[IDEA 开发](IDEA开发.md)说明 Java 启动；脚本 `scripts/start-api.sh` 和 `scripts/start-worker.sh` 从 backend 打包并强制 deployment，默认跳过测试，不能作为测试通过的证据。配置使用各自 `.env.*.properties.example` 的副本，不读取 Python 配置。

## 后续验证

本次仅静态核对，未执行 Java 测试、迁移、NAS 连接、真实模型或 MQ 投递。测试源码覆盖用户、安全、公共题库、考纲、教练、资料和 Worker；存在测试不等于本次通过。后续应先在隔离环境运行 `bash mvnw -B -ntp clean verify`，再核验 PostgreSQL 迁移/权限、Python 签名契约、预建 MQ Topic、故障恢复及真实模型成本。Redis、MinIO 出现在配置样例，不能据此认定 Java 已实现缓存或对象存储接入。
