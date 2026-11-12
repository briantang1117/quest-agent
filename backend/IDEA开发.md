# Java 开发入口

更新日期：2026-11-11。

在 IDEA 中以 Maven 工程导入 `backend/pom.xml`，Project SDK、Maven Runner JRE、Run Configuration JRE 均使用 JDK 27。依赖版本以 POM 为准。项目全貌见[项目现状](../docs/项目现状.md)、[架构说明](../docs/架构说明.md)，统一启动流程见[本地开发启动](../docs/本地开发启动.md)及[根 README](../README.md)。

API 主类为 `com.quest.api.ApiApplication`，Worker 主类为 `com.quest.worker.WorkerApplication`。工作目录必须设为 `backend`，因为应用通过相对路径导入 `.env.api.properties` 或 `.env.worker.properties`。这些文件是 Java Properties，不能 shell source；不要把真实凭据写入 Run Configuration 或提交版本库。

Worker 原生调试配置不传启动参数，profile 与业务配置统一读取 `backend/.env.worker.properties`。实际资料消费需在该文件设置 `SPRING_PROFILES_ACTIVE=deployment`、`OUTBOX_ENABLED=false`、`QUEST_MATERIAL_MQ_ENABLED=true`、`QUEST_API_SERVICE_URL=http://127.0.0.1:8080`，并配置 NameServer 与匹配 API 的内部凭据；默认模板仍关闭 MQ。修改后重启 Worker 生效。

仅查看健康状态时显式选 `local`；它禁用数据库业务。业务开发选择 `deployment`，按对应 example 准备配置；不要把 local 与 deployment/production 混用。API 默认端口 8080，Python 默认地址为 127.0.0.1:8000。Worker 为非 Web 常驻进程，没有 HTTP 健康端口。

脚本入口为 `bash scripts/start-api.sh`、`bash scripts/start-worker.sh`。脚本要求配置副本，使用 `QUEST_JAVA_HOME`/`JAVA_HOME` 或本地 tools 下的 JDK27，先执行跳过测试的 Maven package，再启动 deployment 包。IDE 直接运行主类可以避免每次重复打包。

构建验证在 backend 执行 `bash mvnw -B -ntp clean verify`。这可能下载依赖；本次文档核对没有执行该命令。连接真实服务、执行迁移或启用模型/MQ前，按统一启动文档核验环境。详见[API 启动配置](api-java/docs/IDEA启动配置.md)。
