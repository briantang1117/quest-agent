# Python 内部 AI 服务

核对日期：2026-11-11。本文依据 `app/`、`tests/`、`pyproject.toml`、`uv.lock`、启动脚本与 Dockerfile 整理，不代表真实供应商、本机环境或 NAS 已通过本轮验收。

项目全貌见[根 README](../../README.md)、[项目现状](../../docs/项目现状.md)、[架构说明](../../docs/架构说明.md)；启动顺序见[本地开发启动](../../docs/本地开发启动.md)，陪练工具协议见[陪练工具技术说明](../../docs/陪练工具技术说明.md)。

## 服务职责与接口

入口为 `app/main.py:create_app`。服务接受 Java 签名的内部请求，返回题目草稿、意图、讲解、考纲结果或资料内容；不连接业务数据库，不承担用户鉴权、选题、判分、发布或资料持久化。`scope` 是受信 Java 提供的上下文，不能作为浏览器自报权限的公开接口。

| 路由                               | 实装行为与权限                                                               |
| ---------------------------------- | ---------------------------------------------------------------------------- |
| `GET /health/live`                 | 进程存活检查，无鉴权；不验证供应商或数据库就绪                               |
| `POST /internal/extract`           | 管理员文本题目抽取，返回草稿                                                 |
| `POST /internal/public-import`     | 管理员单题导入，重新检查题目边界、来源文本及原答案，返回草稿、警告与来源证据 |
| `POST /internal/pdf-inspect`       | 管理员本地 PDF 预检与候选切分，不调用模型、OCR 或网络                        |
| `POST /internal/coach-turn`        | 普通用户陪练意图与回复，使用编译后的 LangGraph                               |
| `POST /internal/coach-route`       | 普通用户短 JSON 意图识别，不执行选题、判分或资料任务                         |
| `POST /internal/coach-explain`     | 普通用户讲解 SSE，可使用只读搜索与网页抓取工具                               |
| `POST /internal/syllabus-inspect`  | 管理员考纲 PDF 离线预检                                                      |
| `POST /internal/syllabus-review`   | 管理员考纲 AI 整理，不启用、不发布                                           |
| `POST /internal/syllabus-embed`    | 内部批量文本嵌入，契约接受 admin/student；返回向量，不写库                   |
| `POST /internal/material-generate` | 普通用户从 Java 冻结的公开对话整理私人资料，返回标题与 Markdown              |

路由与输入契约在 `app/api/`。内部接口统一要求 `X-Service-Token`、`X-Timestamp`、`X-Nonce`、`X-Signature`；HMAC-SHA256 签名原文依次为时间戳、nonce、大写 HTTP 方法、URL path、原始请求体 SHA256，以换行连接。时间窗口为 300 秒，拒绝重复鉴权头、重放及 query 参数。响应带 `X-Trace-Id`，错误不回显原始输入与密钥。内部认证未配置时拒绝服务；生产模式要求两项内部密钥分别至少 32 字符。Swagger/OpenAPI 路由关闭。详见 `app/core/security.py`、`app/core/config.py`。

nonce 仅在进程内存保存，当前 Docker 启动单 worker；扩为多 worker/多副本前需要共享重放存储。

## 工作流与工具边界

`app/agent/extraction_graph.py`、`candidate_graph.py`、`coach_graph.py` 使用真实 LangGraph `StateGraph`。其中抽取图包含准备、生成、校验及修复分支；候选图执行来源检查、抽取与原答案协调。考纲整理、资料整理与讲解工具循环不能统称为 LangGraph Agent。

`app/agent/coach_tools.py` 是普通异步有界循环。`COACH_TOOLS_ENABLED` 默认 `true`，还需要文本供应商支持 `stream_chat` 且至少配置一种工具；存在未作答题卡时禁止搜索与抓取。未进入工具循环时，非未作答场景仍可能通过 `coach_search.retrieve` 执行关键词检索，因此关闭此开关不等于禁用全部联网。

本地时间、`search_web` 与 `fetch_page` 共用单轮预算：默认最多 12 次工具调用、6 轮工具来回、24 KiB 累计观察数据、128 KiB 序列化历史、90 秒总时限，常规最多 7 次模型调用，最后一轮强制禁用工具；单工具执行限 8 秒。重复参数使用请求内缓存，但仍计调用与观察预算。`recency` 契约包含 `year`，执行器当前仅支持 `any`，其他值返回工具错误。明确工具协议错误或不支持时允许一次无工具降级；网络、鉴权和限流错误不触发该重试。

SSE 包含 `delta`、工具状态/审计、来源、建议及 `done` 或 `error`。正文可能已发送部分片段，只有最终 JSON 与契约完整验证后才发成功收尾；接收方遇 `error` 必须丢弃部分回复。工具返回是未核验数据，不得改变 Java 判分或覆盖系统指令。

## PDF、考纲与供应商

- PDF：`app/parser/pdf_runner.py` 在独立子进程解析，输入最多 20 MiB、200 页，20 秒超时，输出最多 6 MiB；另有文本、解压内容与资源深度/数量限制。Darwin 使用 RSS 看门狗（2 GiB 阈值），采样存在窗口；这不是完整 OS 沙箱，也不是公网任意不可信上传安全保证。`pdf_inspection.py` 不渲染、不 OCR、不联网；扫描页和复杂排版需要人工处理。
- 考纲：`syllabus_runner.py` 与 `syllabus_extraction.py` 提取离线页文与章节；`agent/syllabus_review.py` 做模型整理与契约检查。嵌入入口一次 1–8 段，每段最多 3000 字、合计最多 12000 字，并校验有限非零向量、模型及维度一致性。Python 不负责版本激活或检索数据库。
- 文本模型：`app/llm/openai_compatible.py` 通过 HTTPX 实现兼容接口、JSON/文本/工具流；配置任一 `TEXT_LLM_*` 字段即整组选用，不从旧 `LLM_*` 组补密钥。
- OCR：`app/providers/ark.py:ArkOCRProvider` 已有图片适配器，限定 PNG/JPEG/WebP、10 MiB；`main.py` 尚未将它接入服务生命周期或 PDF 路由。
- Embedding：`ArkEmbeddingProvider` 已接入考纲嵌入。维度必须以供应商实际输出为准，不能由模型名推断，不能假定能写入数据库的 `vector(1024)`。
- 搜索/抓取：`app/providers/deepseek.py` 调用独立 `/tools/search`、`/tools/fetch`，能力各自使用独立配置。抓取前校验公开 HTTP(S) 地址和 DNS，校验显式返回的最终 URL，限制正文类型与响应大小。客户端不能控制远端工具再次解析 DNS 或逐跳行为，不能把这些校验等同远端 SSRF 沙箱。

以上是代码与替身测试覆盖的接口能力；本轮未调用真实文本、OCR、Embedding、搜索、抓取供应商，示例地址/模型不构成连通性承诺。

## 私人学习资料

`app/api/materials.py` 校验公开消息、题卡及已揭示判分的一致性；只接受普通用户。`app/agent/materials.py` 做一次长模型调用，不检索、不调用工具、不自动重试；未作答题只能保留公开题干和选项。Java 负责冻结对话范围、持久化与后续读取权限。

默认输入正文字符上限 1,000,000、原始请求体上限 8 MiB（验签前有界读取）；输出 Markdown 最多 100,000 字符、模型返回最多 1 MiB。资料单独配置最多 32768 输出 token、600 秒超时，不扩大普通聊天预算。

## 本地运行与检查

要求 Python >=3.11；Docker 使用 Python 3.12。依赖范围以 `pyproject.toml` 为准，安装使用 `uv.lock`：FastAPI、Starlette、HTTPX、Pydantic Settings、Uvicorn、LangGraph、pypdf；开发工具为 pytest、Ruff。

```bash
cd backend/ai-python
cp .env.example .env
# 编辑本目录 .env，填入内部密钥及需要启用的供应商配置
uv sync --frozen
bash start.sh
```

`start.sh` 必须存在本目录 `.env`，通过 `uv run --env-file` 显式加载，监听 `127.0.0.1:8000`，支持 `AI_PORT` 和 `QUEST_UV_BIN`。`Settings` 本身不自动查找 `.env`。脚本关闭 LangSmith/LangChain tracing，启动器关闭 access log。PyCharm 配置见 [PyCharm开发](PyCharm开发.md)。

```bash
uv run pytest
uv run ruff check .
```

测试位于 `tests/`，覆盖内部安全、契约、图、PDF 子进程、考纲、资料、供应商替身与工具流。主线最近记录 Python 665 项通过；该记录不等同本轮重新执行或真实供应商验收。`tests/local_*` 是显式本地辅助入口，运行前检查其配置和副作用，不能视为默认离线验收步骤。

### 本地时间工具

Tool Use启用时始终提供get_current_time，无需网页服务配置。实现为app/agent/local_time.py，使用datetime、zoneinfo和Pydantic，默认Asia/Shanghai。它返回服务器当前时间/自然年份，供模型解释“今年、明年”等相对时间；不修改搜索词，不把考纲年度当当前年，不产生外部网页来源。Java审计ai.tool_time，PC显示“正在确认日期…”。时间工具同样受单轮预算与去重约束，未作答题原有工具禁用边界保持。详细契约见[工具技术说明](../../docs/陪练工具技术说明.md)。


工具预算可通过本工程环境变量配置：`COACH_MAX_TOOL_CALLS=12`（1–32）和`COACH_MAX_TOOL_ROUNDS=6`（1–12）。总时限仍为Python90秒/Java讲解95秒，观察仍为24KiB。达到轮次/次数或新批次超过剩余次数时，用已有结果强制直接回答，不把正常预算收敛变成50203；强制回答阶段仍请求工具则拒绝。Java内部传输最多接受32条工具审计与96个网页来源（来源元数据32KiB），与Python可配置上限兼容。修改环境后需重启Python；本次Java传输上限也有变更，需要Java加载新代码。
