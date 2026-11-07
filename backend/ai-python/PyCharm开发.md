# PyCharm：独立打开 AI 工程

Open选择 **backend/ai-python/**，不是仓库根目录。该目录包含pyproject.toml、uv.lock、app、tests，不需要Java/前端/父目录配置才能启动。

## 首次准备

在本工程终端：

```bash
uv sync --group dev --frozen
cp .env.example .env
```

已有.env时不要覆盖。填INTERNAL_SERVICE_TOKEN、INTERNAL_HMAC_SECRET（各>=32字符，与Java一致），LLM三个字段可暂空。生产级内部鉴权可用不等于真实模型已配置。

2026-11-07经用户明确授权，已创建本工程.env（0600），仅含APP_ENV、两项服务间密钥和关闭tracing配置，不含SSH或数据库管理员凭据。已使用本工程.env独立启动并验证健康200/alive；当前无需再复制模板。原配置未提供模型字段，因此真实模型仍未配置。

## 方法一：Shell Run

顶栏选 **Quest AI**（本工程.run/Quest-AI.run.xml），使用Shell Script插件和/bin/bash；或右键start.sh → Run。

```bash
bash start.sh
```

脚本在本工程目录运行`uv run --env-file 本工程.env uvicorn ... --reload`。不读取父目录.env。IDE找不到uv时在运行配置设置QUEST_UV_BIN为`command -v uv`的绝对路径；本机默认还会查找~/.local/bin/uv。

## 方法二：PyCharm原生Python Run/Debug

1. Interpreter选本工程`.venv/bin/python`。
2. 新建Python运行配置，选择**Module name：uvicorn**。
3. 参数：`app.main:app --host 127.0.0.1 --port 8000 --no-access-log`。
4. Working directory设本工程根。
5. 通过运行配置的Paths to .env files / EnvFile插件加载**本工程.env**；若IDE版本不支持，可手动在Environment variables中填写，但不要提交真实值。
6. 直接Run/Debug。调试时先不加--reload，避免子进程断点问题；日常Shell入口已带reload。

Settings本身不自动读取.env，原生Python配置必须显式加载，不能只放文件就认为会生效。不要同时运行两种配置争抢8000。

健康：http://127.0.0.1:8000/health/live。停止：IDE红色Stop/终端Ctrl+C。没有模型时抽取503正常；不能把健康成功当作真实LLM已联调。

## 测试

```bash
uv run pytest -q
uv run ruff check --no-cache .
uv run ruff format --no-cache --check .
```

当前90项测试通过（1条Starlette/anyio上游弃用警告）。LangGraph仅用于受控抽取工作流，非完整聊天Agent；不引入Deep Agents。默认关闭LangSmith外部tracing，避免原文外发。
