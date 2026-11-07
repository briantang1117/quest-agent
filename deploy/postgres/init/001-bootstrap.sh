#!/usr/bin/env bash
set -euo pipefail

# 仅新数据库首次初始化执行；密码由 psql 变量转义，禁止拼接 SQL。
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=owner_password="$PG_OWNER_PASSWORD" \
  --set=app_password="$PG_APP_PASSWORD" \
  --set=ai_password="$PG_AI_PASSWORD" <<'SQL'
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
CREATE ROLE quest_owner LOGIN PASSWORD :'owner_password';
CREATE ROLE quest_app LOGIN PASSWORD :'app_password';
CREATE ROLE quest_ai LOGIN PASSWORD :'ai_password';
GRANT CONNECT ON DATABASE quest TO quest_owner, quest_app, quest_ai;
ALTER DATABASE quest OWNER TO quest_owner;
ALTER SCHEMA public OWNER TO quest_owner;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
SQL
