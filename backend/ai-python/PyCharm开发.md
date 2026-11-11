# PyCharm 本地开发

核对日期：2026-11-11。依据 `start.sh`、`pyproject.toml`、`app/core/config.py` 整理；未据此断言当前机器的解释器或供应商已配置成功。

服务能力和边界见 [Python README](README.md)。项目上下文见[根 README](../../README.md)、[项目现状](../../docs/项目现状.md)、[架构说明](../../docs/架构说明.md)；跨服务启动顺序见[本地开发启动](../../docs/本地开发启动.md)，工具流细节见[陪练工具技术说明](../../docs/陪练工具技术说明.md)。

## 安装解释器与依赖

在 PyCharm 打开 `backend/ai-python`，或将其作为仓库内的独立 Python 内容根。项目需要 Python >=3.11；Docker 基线是 Python 3.12，不能据此假定本机版本。终端在本目录执行：

```bash
uv sync --frozen
cp .env.example .env
```

复制仅用于首次创建配置，已有 `.env` 不要覆盖。在 IDE 选择本目录 `.venv/bin/python`；如果尚无解释器，先安装满足版本要求的 Python，再同步依赖。依赖版本以 `uv.lock` 为准。

## 运行配置

推荐建立 Shell Script 配置运行本目录 `start.sh`，工作目录为 `backend/ai-python`。脚本会查找 PATH 中的 `uv`，其次查找 `~/.local/bin/uv`；需要时在运行配置设置 `QUEST_UV_BIN` 为实际可执行路径。

脚本显式加载本目录 `.env`，关闭外部 tracing，运行：

```bash
uv run --env-file .env uvicorn app.main:app --host 127.0.0.1 --port 8000 --reload --no-access-log
```

如使用 Python 模块方式调试，模块名设为 `uvicorn`，参数为 `app.main:app --host 127.0.0.1 --port 8000 --reload --no-access-log`，工作目录仍为本目录；通过 IDE 环境变量加载配置，或在 Uvicorn 参数中显式使用 `--env-file .env`。`Settings` 不会自行加载 `.env`。需要单进程断点时可在 IDE 去掉 `--reload`。

## 必需配置与检查

`.env` 按 `.env.example` 创建，内部认证使用 `INTERNAL_SERVICE_TOKEN`、`INTERNAL_HMAC_SECRET`。生产模式二者分别至少 32 字符；即使本地模式未配置内部认证，也不能调用内部业务路由。不要将密钥放进共享 IDE 配置或提交仓库。

文本能力使用完整 `TEXT_LLM_BASE_URL`、`TEXT_LLM_API_KEY`、`TEXT_LLM_MODEL` 组；任一字段存在即整组选用，不与旧 `LLM_*` 混补。OCR、Embedding、Search、Fetch 使用各自配置，不借用文本密钥。示例地址和模型仍需真实接口联调，不能将模板注释当作当前接入状态。

启动后访问 `http://127.0.0.1:8000/health/live`，预期为 `{"status":"alive"}`。这是存活检查，不代表外部模型可用；服务未开放 Swagger/OpenAPI。业务请求由 Java 后端签名发送，直接浏览器请求 `/internal/*` 缺少签名会失败。

`COACH_TOOLS_ENABLED` 默认 true，但实际工具使用还取决于供应商和题卡状态；关闭开关后仍可能存在旧关键词检索路径。仅做离线开发时不要配置搜索/抓取供应商。PDF 预检不依赖 OCR 或模型；OCR 图片适配器尚未接入 PDF 工作流。

## 测试与常见定位

```bash
uv run pytest
uv run ruff check .
```

pytest 配置已将项目根加入 Python path，测试目录为 `tests/`。IDE 测试配置同样选择本项目解释器与工作目录。本轮文档核对未重跑整套测试，主线最近记录 665 项通过；不代表真实供应商验证。

- 启动报生产密钥错误：检查本目录配置和 IDE 是否显式加载环境，不打印密钥值。
- 内部请求 401：检查 Java/Python 两端内部密钥是否一致、时间戳、nonce、签名路径及原始请求体是否一致。
- 503：区分内部认证未配置、文本或特定工具未配置，不凭存活接口推断供应商正常。
- 导入异常：确认解释器、工作目录和锁文件同步；不要在服务启动代码中临时修改 `sys.path`。
- PDF 超时或超限：拆分文件；Darwin RSS 看门狗不是完整安全沙箱，不能直接升级为公网上传保证。

本机调试保持单 worker。nonce 重放存储为进程内实现，多进程或多副本部署需要先改为共享存储。
