#!/usr/bin/env bash
# Java 独立启动入口，不调用 Python/uv，不读取父目录凭据。
set -euo pipefail
BASE=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
case "${1:-}" in
  api) MODULE=api-java; CONFIG=.env.api.properties ;;
  worker) MODULE=worker-java; CONFIG=.env.worker.properties ;;
  *) printf '用法：%s api|worker\n' "$0" >&2; exit 2 ;;
esac
cd "$BASE"
if [[ ! -f "$BASE/$CONFIG" ]]; then
  printf '请先复制 %s.example 为 %s 并填写本工程配置。\n' "$CONFIG" "$CONFIG" >&2
  exit 1
fi
JAVA_HOME=${QUEST_JAVA_HOME:-${JAVA_HOME:-}}
if [[ -z "${QUEST_JAVA_HOME:-}" && -x "$BASE/tools/jdk/Contents/Home/bin/java" ]]; then
  JAVA_HOME="$BASE/tools/jdk/Contents/Home"
fi
export JAVA_HOME
if [[ -z "$JAVA_HOME" || ! -x "$JAVA_HOME/bin/java" ]]; then
  printf '请设置 QUEST_JAVA_HOME 指向 JDK27。\n' >&2; exit 1
fi
if ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -Eq 'version "27([.\"]|$)'; then
  printf '需要 JDK27；不会修改系统默认 Java。\n' >&2; exit 1
fi
# 从 backend 一次编译 common/API/Worker，避免 spring-boot:run 卡在非可运行common模块。
# 默认打包并跳过测试；完整验收另执行 ./mvnw clean verify。
bash "$BASE/mvnw" -B -ntp -DskipTests package
exec "$JAVA_HOME/bin/java" -Xms128m -Xmx512m -jar "$BASE/$MODULE/target/$MODULE-0.1.0.jar" \
  --spring.profiles.active=deployment
