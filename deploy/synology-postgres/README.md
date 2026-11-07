# 群晖 x86_64 PostgreSQL 部署

独立数据库项目，适用于群晖 DSM7 的 Container Manager / 支持Compose的Docker环境。镜像为 `pgvector/pgvector:0.8.2-pg16`，已通过远端镜像清单确认支持 **linux/amd64**。保持平台方案的PostgreSQL16基线，包含pgvector、pg_trgm、pg_stat_statements。

## 1. 上传目录

将**整个 `synology-postgres` 目录**上传至例如：

```text
/volume1/docker/quest-postgres/
├── docker-compose.yml
├── .env                 # 从 .env.example 复制并填写
├── init/
│   └── 001-bootstrap.sh
└── data/                # 预先创建的全新空目录，存储数据库
```

如果Docker共享目录在volume2，换为对应位置；Compose使用相对路径，不需改文件。**不要指向已有数据库或其他版本的数据目录。** 不用手动设user、PUID、PGID，不要给data设777权限；官方entrypoint负责初始化与权限调整。若群晖ACL阻止初始化，应检查该目录ACL和容器日志，不要通过privileged或全盘放权修复。

## 2. 填 `.env`

`.env.example` 复制/改名为 `.env`，确保没有被文件管理器隐藏或保存成 `.env.txt`：

```dotenv
PG_BIND_IP=你的群晖固定局域网IPv4
PG_PORT=5432
PG_ADMIN_PASSWORD=独立随机密码1
PG_OWNER_PASSWORD=独立随机密码2
PG_APP_PASSWORD=独立随机密码3
PG_AI_PASSWORD=独立随机密码4
```

- 若5432已占用，把PG_PORT改为15432，客户端也使用15432。
- 四个密码分别生成；可用密码管理器或运行四次 `openssl rand -hex 32`，将结果保存到自己的密钥记录。
- 建议hex密码，避免Compose对 `$` 等字符插值的歧义；复杂密码可用dotenv单引号包裹。
- 环境文件限制为管理员可读，不在聊天中发送真实密码。

## 3. Container Manager 创建项目

1. 打开 **Container Manager → 项目 → 新增**。
2. 项目名称 `quest-postgres`，路径选择 `/volume1/docker/quest-postgres`。
3. 选择使用目录已有 `docker-compose.yml`，构建并启动。
4. 查看项目/容器日志，首次应出现初始化完成，健康状态随后变为healthy。

通过SSH可先验证再启动：

```bash
cd /volume1/docker/quest-postgres
sudo docker compose config --quiet
sudo docker compose up -d
sudo docker compose ps
sudo docker compose logs --tail=100 postgres
```

群晖老版本若只有 `docker-compose`，将命令中的 `docker compose` 换为 `docker-compose`。若系统无法拉取DockerHub镜像，需要处理NAS镜像网络，不要改成来源不明镜像。

配置按小型开发环境保守设置，建议至少为PostgreSQL留约1GB可用内存（不是硬性限额）。宿主还有其他服务时观察内存/交换使用。局域网HTTP不影响PostgreSQL协议，但当前数据库链路没有TLS，只用于可信局域网/VPN。

## 4. 验证

```bash
sudo docker compose exec postgres psql -U postgres -d quest \
  -c 'SELECT version();'
sudo docker compose exec postgres psql -U postgres -d quest \
  -c 'SELECT extname, extversion FROM pg_extension ORDER BY extname;'
sudo docker compose exec postgres psql -U postgres -d quest -c '\du'
```

应看到vector、pg_trgm、pg_stat_statements，以及三个非超级用户账号：

| 账号 | 用途 |
|---|---|
| `postgres` | 初始化/数据库维护，仅你保管 |
| `quest_owner` | Java Flyway迁移，拥有schema |
| `quest_app` | Java业务运行账号 |
| `quest_ai` | 后续Python只读题库/写索引账号 |

本包只建库、扩展、角色，**不自动创建业务表**。表结构和运行权限由Java启动时执行 `deploy/migrations/V*__*.sql`，首次迁移前quest_app/quest_ai没有业务表可操作。这是正常状态。

## 5. 接入应用

部署好后只需告诉开发侧：**群晖内网IP、映射端口**。密码填本机/服务器被忽略的环境配置，不贴聊天。

Java连接变量示例：

```dotenv
SPRING_PROFILES_ACTIVE=deployment
SPRING_DATASOURCE_URL=jdbc:postgresql://192.168.1.20:5432/quest
SPRING_DATASOURCE_USERNAME=quest_app
SPRING_DATASOURCE_PASSWORD=对应PG_APP_PASSWORD
SPRING_FLYWAY_USER=quest_owner
SPRING_FLYWAY_PASSWORD=对应PG_OWNER_PASSWORD
```

此外还需JWT_SECRET等应用配置，不是只填数据库就算业务上线。应用Docker Compose原本包含本地postgres并硬编码本地容器URL；改接NAS时需另行调整/覆盖服务配置，不要误以为上述环境文件能自动覆盖所有Compose内部固定值。

## 6. 安全与维护

- 群晖防火墙仅允许应用服务器/开发机IP访问PG_PORT。绑定LAN地址不是客户端访问白名单；Docker映射端口也需要用其他局域网设备实测防火墙效果。
- 禁止路由器端口转发5432，禁止直接暴露公网，不给业务应用postgres超级用户。
- 数据保存在data目录，重建容器不会丢失。初始化脚本只在空目录首次执行，改.env不会自动修改已有角色密码。
- 初始化失败后不要反复清空目录重试；先看日志。已有数据的目录绝不能随意删除。
- 快照不是完整备份。至少定期导出 `pg_dump -Fc` 到独立备份目标并做恢复演练；升级大版本前必须备份，不直接换镜像标签。
- 本轮已做YAML/脚本静态契约检查、远端镜像amd64清单校验；**尚未在你的群晖实际启动或验证端口/权限/磁盘**。
