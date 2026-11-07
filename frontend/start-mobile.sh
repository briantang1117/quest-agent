#!/usr/bin/env bash
set -euo pipefail
BASE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd "$BASE"
PNPM=${QUEST_PNPM_BIN:-$(command -v pnpm || true)}
if [[ -z "$PNPM" && -x "$HOME/Library/pnpm/pnpm" ]]; then PNPM="$HOME/Library/pnpm/pnpm"; fi
if [[ -z "$PNPM" ]]; then printf '找不到pnpm，请配置IDE的PATH或QUEST_PNPM_BIN。\n' >&2; exit 1; fi
exec "$PNPM" dev:mobile
