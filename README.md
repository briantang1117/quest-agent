# Quest Agent · AI 考公 / 事业编刷题平台

根据 [`AI刷题平台-第一期方案.md`](AI刷题平台-第一期方案.md) v1.4 建设。**当前阶段为工程架构底座，不是可上线的一期完整产品。**

- **[本地开发启动手册](docs/本地开发启动.md)** ← 日常启动/停止、首次迁移、配置和日志；本机应用连接群晖中间件
- [架构说明与模块边界](docs/架构说明.md)
- [需要你提供的资源与配置](docs/待提供事项.md) ← 数据库、NAS、模型等统一记在这里，无需立即提供
- [开发进度与验证记录](docs/开发进度.md)
- **[下一阶段开发路线](docs/下一阶段开发路线.md)** ← 接下来优先私有题库模型与“我的题库”，再做手工录题和练习闭环
- **[用户模块第一阶段实施计划](docs/superpowers/plans/2026-11-07-user-module.md)** · [接口与验收约定](docs/用户模块接口与验收.md) ← 已实现，NAS V4已执行，真实浏览器验收继续完善
- **[用户体系与私有题库后续计划](docs/superpowers/plans/2026-11-07-private-question-banks.md)** ← Admin开户/只看用量、个人多题库、职测与行测；本期不做主观题
- [工程底座实施计划](docs/superpowers/plans/2026-11-07-foundation.md)

## 工程结构

| 目录 | 职责 |
|---|---|
| `backend/common` | Java 共享领域契约、统一响应 |
| `backend/api-java` | Java 业务入口、安全与配置分层 |
| `backend/worker-java` | 独立异步 Worker 骨架 |
| `backend/ai-python` | Python 内部 AI 服务及 Provider 抽象 |
| `frontend/pc-web` | Vue3 + Arco：刷题、管理、管理 Agent |
| `frontend/mobile-agent` | Vue3 + Vant4：移动 Agent |
| `frontend/shared` | HTTP 类型、SSE、Markdown 安全渲染 |
| `deploy` | 数据迁移、容器编排、网关、消息队列、NAS 配置 |

## 环境要求

- Node.js 22+、pnpm；Python 3.11+、uv。
- JDK **27**、Maven **3.10.0**、Spring Boot **4.1.2**；使用 `backend/mvnw` 的项目独立公共仓库与缓存，不读取公司 Maven settings，不修改全局 Java。
- 集成环境需要 Docker Engine 与 Docker Compose v2。没有 Docker 仍可运行单元测试、前端构建和本地服务骨架。

## 快速验证（不需要数据库 / NAS / LLM）

```bash
pnpm install
pnpm test
pnpm typecheck
pnpm build

# 本机自动使用项目专属 JDK27；其他机器可设置 QUEST_JAVA_HOME。
./backend/mvnw -B -ntp clean verify

cd backend/ai-python
uv sync --group dev
uv run pytest
uv run ruff check .
cd ../..

# 静态基础设施契约 + PostgreSQL parser，不连接数据库。
uv run --with pyyaml --with pglast python -m unittest discover -s scripts/tests -v
bash -n deploy/postgres/init/001-bootstrap.sh
```

Java 已按用户授权脱离公司 Maven 配置，独立使用公共 Central 和项目缓存；Java27 下全量 **33 项测试与打包通过**，API JAR 健康/匿名鉴权冒烟通过。配置隔离及工具版本详见 [Java 说明](backend/README.md)。

双端与服务的具体启动命令见 [前端说明](frontend/README.md)、[Java 说明](backend/README.md)、[Python 说明](backend/ai-python/README.md) 和 [开发进度](docs/开发进度.md)。页面中未接入的功能会明确说明，不提供伪造题库、统计或 AI 回复。

## 集成环境配置

1. 复制 `.env.example` 为 `.env`，填入**独立的随机密钥**。生产密钥不要放进版本控制或聊天记录。
2. 先构建双端静态文件：`pnpm build`；再执行 `./backend/mvnw -B -ntp clean verify`（JDK27）。Java Dockerfile 复制本地构建的 JAR，不在容器内解析 Maven 依赖；没有 JAR 时不能构建 Java 镜像。
3. 有可用 Docker/Compose 后校验配置：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml config
# 仅启动新建的开发数据库、缓存和 RocketMQ，不连接已有实例。
docker compose --env-file .env -f deploy/docker-compose.yml up -d \
  postgres redis rocketmq-namesrv rocketmq-broker
# 构建并启动服务；API 以 quest_owner 执行 Flyway，再以 quest_app 访问业务表。
docker compose --env-file .env -f deploy/docker-compose.yml up -d --build
```

默认 Nginx 只监听 **127.0.0.1:8088**。访问 PC `http://localhost:8088`，移动入口 `http://localhost:8088/m/`。正式开放局域网前需配置监听地址、后台允许网段、防火墙及真实认证。

> 容器编排当前只完成静态校验。真实镜像拉取、应用启动、数据库迁移及端到端联调未在本机执行；不要直接用于正式环境。

### 本地存储与 NAS

默认应用配置使用 NAS S3 地址。仅开发时可以叠加本地 MinIO：

```bash
docker compose --env-file .env -f deploy/docker-compose.yml \
  -f deploy/docker-compose.local-storage.yml up -d minio
```

NAS 自身独立部署，先确认 `.env` 中的 `NAS_DATA_DIR` 确实是要使用的新目录，再在 NAS 上执行：

```bash
docker compose --env-file .env -f deploy/nas-minio/docker-compose.yml config
# 校验目录和配置后，再执行 up -d。
```

桶初始化、应用权限策略、CORS 和对象存储业务接口仍需后续联调。不要把 MinIO root 管理账号配置给业务应用。任何数据库重置、卷删除、NAS 覆盖都不属于自动开发步骤。

### RocketMQ Topic 初始化

Broker 禁用自动建 Topic。基础设施启动后，可由管理员显式创建：

```bash
for topic in qbank-question-event qbank-import-task qbank-ai-task qbank-answer-event qbank-notify; do
  docker compose --env-file .env -f deploy/docker-compose.yml exec rocketmq-broker \
    sh mqadmin updateTopic -n rocketmq-namesrv:9876 -c QuestLocalCluster -t "$topic" -r 4 -w 4
done
```

本轮不宣称已完成可靠 Relay、顺序消费、死信重放或消息发收测试。

## 安全约定

- 终端始终经过 Java，Nginx 不转发 Python `/internal/*`。
- 业务账号、迁移账号、AI 索引账号分离，AI 无用户隐私表读取权限。
- 不创建默认管理员或固定密码；无 LLM 配置时返回明确不可用，不生成假答案。
- 内部调用签名覆盖方法、路径、原始请求体、时间戳与 nonce；多副本防重放存储需后续接入共享 Redis。
- HTTP 局域网仅保证 H5；PWA 离线缓存需要可信 HTTPS。

## 后续开发

优先顺序：认证权限 → 题库与知识点 → 练习闭环 → 文本导入审核 → 可靠事件与索引 → Agent 端到端 → 模考与统计。见 [架构说明](docs/架构说明.md)。
