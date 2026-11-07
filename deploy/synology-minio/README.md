# 群晖 x86 S3 存储：PGSTY SILO（MinIO 社区 Fork）

根据用户提供的PGSTY社区维护方案更新。当前项目已从 `pgsty/minio` 更名为 **pgsty/silo**（维护者README注明2026-08-06更名）。不是MinIO公司产品，也不是Coollabs重构建。

## 已核实的镜像

```text
pgsty/silo:RELEASE.2026-10-26T00-00-00Z
sha256:7bb2aff6b89efe1cb1303c10fc21d579447434ed06649ac9ec05a5e2dbf30ad7
```

- 匿名读取此固定版本远端manifest成功，包含linux/amd64、linux/arm64；Compose固定amd64及清单摘要，不在ARM Mac构建/导出镜像。
- 维护者当前仓库为https://github.com/pgsty/silo，未归档。
- 当前发布说明：Console v2.5.1；标准镜像内置mcli与静态curl；已检查对应release Dockerfile。
- 维护者文档明确保留MINIO_*配置、S3接口、/minio/*路径与数据格式兼容。恢复管理控制台不是通过倒退到2025年4月旧官方版本实现。
- 本次仅确认公开镜像清单及发布来源；未拉取全部layer/启动容器，不能证明你的NAS到DockerHub网络一定畅通，也不等同于完整供应链/安全审计。

## 更新已上传的项目

**只替换docker-compose.yml，保留已有.env及data。** 新Compose不读取MINIO_IMAGE，旧.env中该行删除或保留都不会再切回Quay。IP、端口、root凭据保持原值。

Container Manager重新构建/部署项目，或SSH：

```bash
cd /volume1/docker/quest-minio
sudo docker compose config --quiet
sudo docker compose pull minio
sudo docker compose up -d minio
sudo docker compose ps
sudo docker compose logs --tail=100 minio
```

不需要先down，不删除data。若data已有对象，切换任何Fork/版本前先备份数据、IAM、配置和加密密钥，不承诺任意版本之间可无风险来回切换。不要在升级失败后盲目降级。

## 新部署目录与配置

```text
/volume1/docker/quest-minio/
├── docker-compose.yml
├── .env
└── data/                 # 独立全新空目录
```

.env.example复制为.env并填独立强密码；不要覆盖已有真实.env：

```dotenv
MINIO_BIND_IP=192.168.3.40
MINIO_API_PORT=19000
MINIO_CONSOLE_PORT=19001
MINIO_ROOT_USER=questadmin
MINIO_ROOT_PASSWORD=独立随机强密码
```

不要将密码贴到聊天或放回模板。镜像固定在Compose，不需要额外配置镜像变量。

## 地址和验证

| 用途 | 地址 |
|---|---|
| S3 API | http://192.168.3.40:19000 |
| 完整管理控制台 | http://192.168.3.40:19001 |

环境变量沿用MINIO_ROOT_USER/PASSWORD，服务仍叫minio，便于现有Compose操作；镜像可执行文件已更名silo，由自带entrypoint处理server参数，无须在command写minio二进制名。

健康检查用内置mcli对localhost进行认证/就绪检查，临时配置在/tmp/.mc-health。手动验证：

```bash
sudo docker compose exec minio sh -ec 'mcli alias set local http://127.0.0.1:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mcli admin info local'
```

后续创建quest-public、quest-private（先均保持私有），配置仅限这两个桶的独立应用用户/访问密钥，不给业务root。图片匿名读和上传CORS按业务需求另行确认，不开放匿名写。

## 安全边界

- 群晖防火墙：19000仅允许应用服务器和必要终端，19001仅管理员设备；LAN绑定不是IP白名单，需从其他设备实测。
- HTTP仅可信局域网/VPN，不开放公网、不做路由器端口转发。
- data不共用、不删除、不设777/privileged；单节点单目录无高可用，NAS快照/RAID不替代独立备份恢复。
- 社区Fork属于新的维护/供应链信任，不承诺商业SLA；上线前核对发布说明、安全公告、备份和恢复。
- 旧官方Quay返回401，旧DockerHub minio/minio返回denied；不要用反复docker login或改ARM平台解决仓库不可用。

## 来源

- https://github.com/pgsty/silo
- https://github.com/pgsty/silo/releases/tag/RELEASE.2026-10-26T00-00-00Z
- https://hub.docker.com/r/pgsty/silo
