# 工程架构底座实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development. 使用下列复选框记录实际状态。

**Goal:** 根据 v1.4 方案建立可构建、可测试、可扩展的多服务工程底座，而非声称完成全部一期业务。

**Architecture:** Maven 多模块承载 Java 业务 API 与 Worker，共享纯契约模块；Python FastAPI 仅暴露内部 AI 接口；pnpm workspace 承载 Vue 双端与共享协议。业务单写 PostgreSQL，事件使用事务 Outbox，所有终端 API 包括 Agent 均经过 Java。

**Tech Stack:** Java 17 / Spring Boot 3 / MyBatis-Plus；Python 3.11+ / FastAPI；Vue3 / TypeScript / Vite / Arco Design Vue 2 / Vant 4；PostgreSQL16 / pgvector / Redis7 / RocketMQ5.3 / MinIO / Nginx。

**Spec:** `AI刷题平台-第一期方案.md`，重点 §1.4、§2、§3、§9、§11–14。

## Global Constraints
- Java 单写业务库，Python 只写索引和提交草稿，不允许直接发布题目。
- 管理与学员 Agent scope 隔离；缺少 LLM 配置返回明确不可用，不伪装真实模型回答。
- API 前缀 `/api/v1`，响应 `{code,message,data,traceId}`，时间 UTC。
- 纯局域网、MinIO 独立 NAS；开发 Compose 可独立启动本地替代存储。
- 不提交密钥，不修改系统默认 JDK，不初始化或提交 Git，不自动启动宿主机虚拟机。
- 用户已要求不中断任务；缺失外部资源及暂定决策写入 Markdown，不等待审批。

## Review Focus
- 匿名请求不得访问业务接口；内部签名需覆盖原始请求体、时间戳、nonce 并拒绝重放。
- SSE 支持 UTF-8 跨包、CRLF、多行 data、注释与中止，不能吞错误。
- 单选/多选答案、选项唯一性、知识点及难度边界在服务端校验。
- 未配置 AI 不得返回假生成结果；健康检查不得假报外部依赖正常。
- Compose 服务凭据区分管理员、业务账号与 AI 账号，公共入口不得绕过 Java。

## Task 1：Java 服务底座
**Files:** `backend/pom.xml`、`backend/common/`、`backend/api-java/`、`backend/worker-java/`、`backend/mvnw`。
**Interfaces:** API 健康 `/actuator/health`；Java API `/api/v1/system/info` 受认证保护；纯领域题目校验及判分函数；Worker 不提供外部业务端点。
- [x] 题目边界、无序多选判分、匿名访问/统一错误/traceId/应用上下文测试已全部执行通过；后续公共仓库授权解除依赖限制。
- [x] 实现 Maven 模块、安全默认拒绝、配置 profile、契约和健康检查；local 关闭持久化，不以假内存业务代替生产数据库。
- [x] 后续用户授权独立公共仓库并要求最新Java/Maven后，升级Java27/Maven3.10.0/Boot4.1.2，全量clean verify通过33项测试与打包；JAR健康/匿名401冒烟通过。未改公司配置。

## Task 2：双端与共享协议
**Files:** `package.json`、`pnpm-workspace.yaml`、`frontend/shared/`、`frontend/pc-web/`、`frontend/mobile-agent/`。
**Interfaces:** shared 导出 ApiResponse、Question、AgentMessage、SSE parser 和 fetch stream client；PC `/practice`、`/admin`、`/admin/agent`；移动 `/m/` 仅 Agent。
- [x] 先为 SSE、API 错误与 Markdown 安全编写测试并确认失败。
- [x] 实现协议和双端独立 UI 壳、路由、代理、Pinia、按需组件；页面清楚标记未接入功能，禁止假统计。
- [x] 执行 shared 测试、双端 typecheck 与生产构建；shared 最终 36 passed，审查的 SSE/同源问题已回归覆盖。

## Task 3：Python 内部 AI 服务
**Files:** `backend/ai-python/pyproject.toml`、`app/core/`、`app/api/`、`app/llm/`、`tests/`。
**Interfaces:** `/health/live`；`POST /internal/extract`；X-Service-Token + HMAC timestamp/nonce/body 鉴权；Provider 抽象，未配置 503。
- [x] 先测健康、签名篡改/过期/重放、scope、无模型及题目校验。
- [x] 实现配置、签名、题目 Pydantic 模型、OpenAI-compatible Provider 边界和抽取入口；暂不引入重型 OCR/模型。
- [x] uv 同步、pytest、lint 验证；最终 84 passed，1 条第三方弃用警告。

## Task 4：基础设施、迁移与交付文档
**Files:** `deploy/docker-compose.yml`、`deploy/postgres/init/`、`deploy/migrations/`、`deploy/nginx/`、`deploy/rocketmq/`、`deploy/nas-minio/`、`.env.example`、`README.md`、`docs/架构说明.md`、`docs/待提供事项.md`、`docs/开发进度.md`。
**Interfaces:** PostgreSQL16 扩展及分权；版本化 SQL 包含方案核心域；Nginx `/api/` → Java，`/m/` 移动 SPA；AI 仅内部网络；资源未提供时仍可构建测试。
- [x] 编写基础设施静态校验，验证配置与安全边界；6 项静态测试通过。
- [x] 建立数据库迁移、账号分权与服务 Compose/Dockerfile；本地 MinIO 使用单独 override，NAS 模式不绑定本地盘。
- [x] 写清配置、运行命令、验证证据、待实现内容与用户需提供资源。
- [x] 运行所有可用测试及构建，独立审查并回归修复；Java 全量构建已在后续公共仓库授权后通过；容器集成未执行，已在进度与资源清单中明确记录，不标记为全栈验收完成。
