# 群晖 x86_64 Redis 部署

项目约定 Redis7，本包固定 `redis:7.4.12-alpine`，平台 `linux/amd64`。默认连接 `192.168.3.40:16379`，容器内部仍6379；如16379已占用，自行调整 `.env` 中端口。

## 1. 上传完整目录

```text
/volume1/docker/quest-redis/
├── docker-compose.yml
├── redis.conf
├── .env                 # 从 .env.example 复制，填写密码
└── data/                # 预先创建的全新空目录
```

`redis.conf` 必须作为文件上传，不能是空目录。data不得与其他Redis实例共用，不要指向现有数据库或设置777权限；官方entrypoint会初始化目录权限并降权运行。不要手动加user/PUID/privileged来绕过群晖ACL问题。

## 2. 配置 .env

```dotenv
REDIS_BIND_IP=192.168.3.40
REDIS_PORT=16379
REDIS_PASSWORD=填写独立随机强密码
```

建议运行 `openssl rand -hex 32` 或用密码管理器生成；不复用PG密码，不贴聊天。文件要叫 `.env`，不是 `.env.txt`。避免 `$` 插值歧义可使用hex密码；复杂密码可用dotenv单引号包裹。

注意：Redis官方镜像不会因为设置REDIS_PASSWORD环境变量就自动启用认证。本Compose已显式将密码传给redis-server；直接删掉command会失去这条保障。环境/命令中的密码对Docker管理员可见，因此仅让受信任管理员访问DSM/Docker，不将此方案视为硬件级密钥隔离。

## 3. 启动

Container Manager → 项目 → 新增 → 选择该目录 → 使用已有Compose并启动。

SSH命令：

```bash
cd /volume1/docker/quest-redis
sudo docker compose config --quiet
sudo docker compose up -d
sudo docker compose ps
sudo docker compose logs --tail=100 redis
```

老版本群晖若只有docker-compose，将命令中的docker compose换为docker-compose。项目仅部署Redis，不会操作已有PG容器。

## 4. 验证

先确认无密码被拒绝：

```bash
sudo docker compose exec redis redis-cli ping
# 预期：(error) NOAUTH Authentication required.
```

然后验证密码、持久化及策略（不必在命令行直接写密码）：

```bash
sudo docker compose exec redis sh -ec 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
# 预期：PONG
sudo docker compose exec redis sh -ec 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli CONFIG GET appendonly appendfsync maxmemory maxmemory-policy'
# 预期：yes / everysec / 268435456 / noeviction
```

容器健康探针使用同样的密码认证并要求返回PONG，健康不等于网络/应用端到端已验收。

## 5. 接入信息

| 项 | 值 |
|---|---|
| Host | 192.168.3.40 |
| Port | 16379 |
| Username | default（客户端可不填） |
| Password | 你在.env设置的REDIS_PASSWORD |
| Database | 0 |

Java/Python最终连接由后续业务接入配置。目前Java Redis集成和Python共享nonce仍未实现，部署成功不代表这些代码已经接通。

## 6. 持久化、内存和安全

- AOF开启，everysec模式异常断电可能丢约1秒最近写入；RDB为辅助快照，不是独立备份。认证/防重放上线前仍需评估重启与故障窗口。
- 数据内存限额256MB，容器额度512MB，给连接、AOF缓冲和进程开销留空间；fork重写高峰可能需要更多资源，观察NAS内存后调整，不能把maxmemory与容器额度设为相同。
- 使用noeviction，防止JWT失效状态/防重放键被内存策略提前删掉；内存满会拒绝新写入，需要监控并在业务中失败关闭，不能默默当作缓存miss放行。
- 一个实例同时放安全状态和缓存只适合当前规模；大规模时应拆分实例与ACL，而不是仅分DB号码。
- 默认用户为密码认证管理员，不是应用最小ACL方案。上线前按实际命令/键前缀另配ACL；先保证只有可信应用服务器和开发机能访问。
- 群晖防火墙仅放行指定客户端IP访问16379；绑定LAN IP不是访问白名单，Docker发布端口需从其他设备验证防火墙效果。
- 无TLS，仅可信局域网/VPN；不要路由器端口转发，不暴露公网，不关闭protected-mode。
- 改密码后执行 `docker compose up -d` 重建并同步修改应用凭据；不要执行CONFIG REWRITE，配置文件以只读挂载。
- 不删除data，不执行FLUSHALL/FLUSHDB；重建容器与备份恢复是不同操作。

## 验证边界

已核实官方7.4镜像清单支持amd64，当前指向7.4.12；部署文件通过YAML与静态契约检查。尚未在你的群晖实际启动，也没有把静态检查当成真实Redis认证/AOF恢复测试。
