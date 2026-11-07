# 部署配置说明

本目录为局域网集成环境底座，尚未完成容器集成验收。入口命令见根 README。

## 初始化顺序

1. 新 PostgreSQL 数据卷首次启动执行 `postgres/init/001-bootstrap.sh`，创建 `quest_owner`、`quest_app`、`quest_ai` 和扩展。
2. API 以独立 Flyway 账号 `quest_owner` 依次执行 `migrations/V*__*.sql`。
3. Java 运行期连接使用 `quest_app`；AI 索引账号仅预留，不给当前 Python 服务传数据库管理员密码。
4. Worker 当前仅架构骨架，未启动 Relay/消费者，不依赖一次 `depends_on` 就宣称 MQ 可靠就绪。

初始化脚本仅适用于新卷。改 `.env` 不会自动变更已有数据库账号密码；已有环境应通过受控凭据轮换，不要删除数据卷来“修复”认证。

## 网络

- Nginx 默认仅绑定 `127.0.0.1:8088`。
- PostgreSQL、Redis、RocketMQ、Python 不发布宿主机端口。
- `/api/` 仅转发 Java，关闭 SSE 缓冲；`/internal/` 不公开。
- NAS MinIO 独立部署，S3:9000 要求终端和应用均可达。NAS 控制台默认只绑定 NAS localhost。
- 正式局域网放行与后台 IP 白名单需上线前配置，当前 Compose 不等于完成生产安全加固。

## 迁移限制

- 迁移 SQL 已通过 PostgreSQL parser 静态语法检查，尚未在真实 PostgreSQL16/pgvector 实例执行。
- `answer_record` 建立分区结构和默认分区。上线前实现月分区滚动创建、默认分区数据迁移、归档与告警。
- 初始迁移不得在有业务数据的环境随意重写；后续变化应增加 V4、V5 等迁移。
- 未安装 `pg_stat_statements`，这是可选运维扩展，需要 `shared_preload_libraries` 和数据库管理员配置，后续独立接入。

## 真实验收清单

- [ ] `docker compose config` 与镜像版本可拉取
- [ ] 全新数据库迁移成功，API 二次重启不重复建表
- [ ] `quest_ai` 写 `question` 被拒绝，读 `app_user` / `agent_message` 被拒绝
- [ ] `quest_app` 不能 UPDATE/DELETE `question_version`、`audit_log`，不能修改 Flyway 历史
- [ ] 未配置 LLM 时进程可存活，抽取返回明确 503
- [ ] 浏览器无法访问 Python 内部端口
- [ ] 消息 Topic 显式创建，后续完成发收和失败重试测试
- [ ] NAS 桶策略、CORS、预签名上传下载、备份恢复演练

没有编写任何自动删除数据卷或清空 NAS 的脚本。
