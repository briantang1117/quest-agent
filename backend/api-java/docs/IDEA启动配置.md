# API Java 的 IDEA 启动配置

更新日期：2026-11-11。

总览：[根 README](../../../README.md)、[项目现状](../../../docs/项目现状.md)、[架构说明](../../../docs/架构说明.md)、[本地开发启动](../../../docs/本地开发启动.md)。

导入 `backend/pom.xml`，JDK 和 Maven Runner 使用 27，主类选择 `com.quest.api.ApiApplication`，模块选择 api-java，Working directory 指向仓库的 `backend`。

默认 local 禁用 DataSource、Flyway、MyBatis-Plus 自动配置，仅可检查健康状态。部署模式显式设置 `SPRING_PROFILES_ACTIVE=deployment`；不要同时启用 local。复制 `backend/.env.api.properties.example` 为同目录 `.env.api.properties` 并自行填写，不在文档或 IDE 共享配置中保存真实密钥。

部署模式必需 PostgreSQL 连接、应用账号、JWT 密钥（至少32个 UTF-8 字节）；Flyway 凭据由部署配置占位符引用，日常 `SPRING_FLYWAY_ENABLED=false`。应用账号与迁移 owner 应分离。AI 调用需 AI_SERVICE_URL、INTERNAL_SERVICE_TOKEN、INTERNAL_HMAC_SECRET，Python 端保持一致。样例不是服务已经可用的证据。

公共导入由 `quest.public-import.enabled` 控制；学习资料由 `quest.materials.enabled` 控制，默认均关闭。资料还需 Worker 独立设置 `QUEST_MATERIAL_MQ_ENABLED=true`，以及提前准备固定 Topic `quest-learning-materials`。旧 `OUTBOX_ENABLED` 必须关闭。Worker 无业务数据库凭据需求。

API 默认监听 8080；仅开放健康 GET 和登录 POST，其他业务接口需要 Bearer 会话。`/actuator/health/readiness` 在 deployment 包含数据库，在 local 仅表明进程就绪。

从 backend 可执行 `bash scripts/start-api.sh`，脚本强制 deployment 并跳过测试打包；其执行成功不代表测试通过。测试命令为 `bash mvnw -B -ntp clean verify`，本次未运行。
