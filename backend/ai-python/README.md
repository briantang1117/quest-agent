# 内部 AI 服务

**独立开发入口见 [PyCharm开发.md](PyCharm开发.md)**：只打开本目录，使用自己的.env/.venv，运行bash start.sh，不依赖父目录配置。

Python 3.11+ / FastAPI + **LangGraph 1.2.20**。文本抽取实际使用StateGraph，不使用Deep Agents；保留供应商中立Provider。不包含OCR、RAG、向量化、会话持久化或Agent SSE。

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
- `LLM_BASE_URL`：OpenAI-compatible 前缀，如 `https://provider.example/v1`。
- `LLM_API_KEY`、`LLM_MODEL`：任一缺失/为空即视为未配置，抽取接口返回 503，不伪造结果。
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

已通过 90 项测试和 ruff/格式检查，新增6项覆盖图节点、重试上限、异常传播、并发隔离；仅使用 httpx MockTransport 验证上游交互，未请求真实模型。Starlette TestClient 有 1 条 anyio 弃用别名警告，未屏蔽。容器未构建/运行，原因见根目录开发进度。
