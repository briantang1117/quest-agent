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
# 构建共享common时也要串行，避免API/Worker同时启动互相改写target。
# macOS自带shlock；Linux通常由util-linux提供flock。
umask 077
RUNTIME="$BASE/runtime/java"
mkdir -p "$RUNTIME"
LOCK_FILE="$RUNTIME/build.lock"
LOCK_MODE=
RUN_DIR=
CHILD_PID=
release_build_lock() {
  if [[ "$LOCK_MODE" == fd ]]; then
    flock -u 9 || true
    exec 9>&-
  elif [[ "$LOCK_MODE" == pid && -f "$LOCK_FILE" ]]; then
    local owner
    owner=$(cat "$LOCK_FILE")
    if [[ "$owner" == "$$" ]]; then rm -f "$LOCK_FILE"; fi
  fi
  LOCK_MODE=
}
cleanup() {
  release_build_lock
  if [[ -n "$RUN_DIR" ]]; then
    rm -f "$RUN_DIR/app.jar"
    rmdir "$RUN_DIR" 2>/dev/null || true
  fi
}
stop_child() {
  trap '' INT TERM
  if [[ -n "$CHILD_PID" ]]; then
    kill -TERM "$CHILD_PID" 2>/dev/null || true
    wait "$CHILD_PID" 2>/dev/null || true
  fi
  exit "$1"
}
trap cleanup EXIT
trap 'stop_child 130' INT
trap 'stop_child 143' TERM
run_child() {
  local status
  "$@" &
  CHILD_PID=$!
  set +e
  wait "$CHILD_PID"
  status=$?
  set -e
  CHILD_PID=
  return "$status"
}
if command -v flock >/dev/null 2>&1; then
  LOCK_MODE=fd
  exec 9>"$LOCK_FILE"
  run_child flock 9
elif command -v shlock >/dev/null 2>&1; then
  LOCK_MODE=pid
  until shlock -f "$LOCK_FILE" -p "$$"; do sleep 1; done
else
  printf '串行构建需要shlock（macOS自带）或flock（Linux util-linux）。\n' >&2
  exit 1
fi
# 只打包当前服务及其依赖；默认跳过测试，完整验收另执行mvnw verify。
run_child bash "$BASE/mvnw" -B -ntp -pl "$MODULE" -am -DskipTests package
# 每次运行使用独立副本。后续打包、IDE编译或启动另一服务不改动已打开的JAR。
RUN_DIR=$(mktemp -d "$RUNTIME/$MODULE.XXXXXX")
cp "$BASE/$MODULE/target/$MODULE-0.1.0.jar" "$RUN_DIR/app.jar"
release_build_lock
printf '启动 %s（独立运行副本）\n' "$MODULE"
run_child "$JAVA_HOME/bin/java" -Xms128m -Xmx512m -jar "$RUN_DIR/app.jar" \
  --spring.profiles.active=deployment
