# 内部 AI 服务

**独立开发入口见 [PyCharm开发.md](PyCharm开发.md)**：只打开本目录，使用自己的.env/.venv，运行bash start.sh，不依赖父目录配置。

Python 3.11+ / FastAPI + **LangGraph 1.2.20**。文本抽取实际使用StateGraph，不使用Deep Agents；保留供应商中立Provider。已有独立OCR/Embedding/Search/Fetch适配器，但尚未接完整导入HTTP工作流；无RAG索引、会话持久化或Agent SSE。

多来源证据、无题号截图、原答案/原解析保护及缺答案联网建议见[证据层设计](../../docs/多来源导入证据层设计.md)。候选图所有结果都需导入人确认，搜索命中不等于正确性已经核验。已新增HMAC单题文本`/internal/public-import`及Java/PC首段接口，见[联调验收](../../docs/公共题库联调与验收.md)；NAS V5已获授权执行核验、任务处理仍默认关闭，尚未真实端到端联调，PDF/图片HTTP未开放。

## LangGraph 流程

`START → prepare → generate → validate → END`；校验失败分支为 `validate → repair → generate → validate`，最多2次模型调用。网络/供应商错误直接失败，不自动重试。

图在FastAPI生命周期中编译一次，请求状态独立；结果强制草稿且待人工审核。保留原 `/internal/extract` 的HMAC、admin scope、请求/响应及错误码契约。没有发布/数据库写入工具，没有引入子代理或通用文件操作。

本次未实现checkpoint持久化、人工interrupt/resume或聊天Agent，不能把引入框架等同于这些能力已经可用。不配置外部LangSmith tracing，部署时也不要擅自启用可能上传题目原文的追踪环境变量。

## 本地启动与验证

在本目录：

```bash
uv sync --group dev --frozen
uv run pytest -q
uv run ruff check --no-cache .
uv run ruff format --no-cache --check .
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000 --workers 1
```

`GET /health/live` 返回 `{"status":"alive"}`，只表示进程存活，不表示模型已配置。

## 配置

通过环境变量注入，不自动读取 `.env`：

- `APP_ENV=local|production`，默认 local。
- `INTERNAL_SERVICE_TOKEN`、`INTERNAL_HMAC_SECRET`：生产必须分别提供至少 32 字符的独立随机值，否则拒绝启动。local 未配置允许健康启动，但所有内部接口拒绝处理。
- 文本模型独立配置：`TEXT_LLM_BASE_URL`、`TEXT_LLM_API_KEY`、`TEXT_LLM_MODEL`。当前预填灵思亮界文档站点的 `/v1` 地址和 `deepseek-v4.1-flash`，实际API Host仍需联调确认；Key留空由操作者填写，不发聊天。
- 任一 `TEXT_LLM_*` 出现即选用完整文本配置组，缺项不会借用旧 `LLM_*` 或图片/向量/搜索密钥；缺配置返回503，不伪造结果。完全没有新配置组时保留旧 `LLM_*` 兼容。
- 方舟调试OCR配置：`OCR_API_BASE_URL=https://ark.cn-beijing.volces.com/api/plan/v3`、`OCR_MODEL=doubao-seed-2.1-pro`、独立`OCR_API_KEY`。
- 方舟调试向量配置：`EMBEDDING_BASE_URL`同上、`EMBEDDING_MODEL=doubao-embedding-vision`、独立`EMBEDDING_API_KEY`。严格使用用户给定无版本后缀别名；`EMBEDDING_DIMENSIONS`暂未设，实际输出维度要验证，现有PG为vector(1024)，不自动改表。
- `SEARCH_API_BASE_URL/KEY`与`FETCH_API_BASE_URL/KEY`为DeepSeek独立工具配置占位。
- 五项Provider已实现并做过最小合成/公开内容真实连通性验证，见[验证记录](../../docs/模型服务连通性验证.md)；向量实际2048维，不写现有1024维列。方舟Files/Responses/PDF原文件上传尚未验证，不静默换正式付费地址，不重复连通性调用。真实材料OCR/模型请求仍须明确内容范围与预算。
- 可选 `LLM_TIMEOUT_SECONDS=30`、`LLM_TEMPERATURE=0`、`LLM_MAX_TOKENS=4096`。

## 内部 API

`POST /internal/extract`，请求：

```json
{"scope":"admin","text":"原始题目文本"}
```

请求必须携带四个 HMAC headers，协议见 [`docs/api.md`](../../docs/api.md)（项目根目录的 `docs/api.md`）。`scope=student` 禁止调用导入工具，非法 scope 拒绝；Java 必须先根据真实用户身份授权，不得把服务凭据交给终端。

响应遵循 `{code,message,data,traceId}`。AI 内容强制 `draft`，即使模型声称已发布也不会直接发布。上游响应按题目 schema 校验，最多一次格式修正重试；网络错误不自动重试。

## 部署边界

Docker build context 为本目录，非 root 用户，固定单 worker。当前 nonce 防重放是进程内加锁存储：**不得直接扩成多 worker / 多副本**，重启也会清空 nonce。上线前接共享 Redis 原子 `SET NX EX`，并验证跨副本与重启的重放行为。未配置模型时 production 服务仍可存活，以便先完成资源部署。

当前本地完整pytest为272 passed、2 warnings，ruff/格式检查通过，覆盖Provider、PDF预检、证据组装和候选图；测试不请求真实模型。两条警告为Starlette/AnyIO弃用别名和PDF测试fixture的add_js弃用，未屏蔽。此前五项最小真实连通性验证另有记录，不等同真实PDF/截图抽取验收。容器未构建/运行。
