# 群晖 PostgreSQL 配置

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

以本目录`docker-compose.yml`、`init/001-bootstrap.sh`和`.env.example`为准：镜像pgvector/pgvector:0.8.2-pg16、linux/amd64、UTC、SCRAM认证，数据为本目录data卷；默认示例NAS192.168.3.40:15432/quest，容器端5432。端口可由PG_PORT覆盖。

POSTGRES管理员、quest_owner迁移、quest_app业务和quest_ai受限账号分开。初始化脚本仅在空数据目录首次执行，不能当作对已有数据库补升级的工具。API使用runtime账号，Worker无业务库需求，Python不写业务表。

环境变量名与模板对应，已有.env勿覆盖，不在命令行/聊天回显密码。校验：

```bash
docker compose config --quiet
docker compose ps
```

仓库迁移在`../../backend/api-java/src/main/resources/db/migration`及`../migrations`双副本，V1–V13并不意味着NAS已执行。本次未启动容器、连接PG或执行迁移。服务健康、应用连接、schema版本和角色权限需要分别核验，详[群晖核验](../../docs/群晖服务验证.md)。
