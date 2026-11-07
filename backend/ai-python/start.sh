#!/usr/bin/env bash
set -euo pipefail
BASE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$BASE"
if [[ ! -f "$BASE/.env" ]]; then
  printf '请先复制 .env.example 为本工程 .env 并填写配置。\n' >&2; exit 1
fi
UV=${QUEST_UV_BIN:-}
if [[ -z "$UV" ]]; then
  UV=$(command -v uv || true)
fi
if [[ -z "$UV" && -x "$HOME/.local/bin/uv" ]]; then
  UV="$HOME/.local/bin/uv"
fi
if [[ -z "$UV" ]]; then
  printf '找不到uv；安装uv或在IDE运行配置设置QUEST_UV_BIN。\n' >&2; exit 1
fi
# 不开启外部追踪；--env-file只读取本工程.env，不查找父目录。
export LANGSMITH_TRACING=false LANGCHAIN_TRACING_V2=false
exec "$UV" run --env-file "$BASE/.env" uvicorn app.main:app \
  --host 127.0.0.1 --port "${AI_PORT:-8000}" --reload --no-access-log
