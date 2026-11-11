# 部署配置导航

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

日常路径是本机应用+NAS中间件，见[本地启动](../docs/本地开发启动.md)。本目录提供部署材料；本轮未执行容器部署、远程写入或迁移。

| 路径                             | 用途与边界                                                  |
| -------------------------------- | ----------------------------------------------------------- |
| synology-postgres                | NAS独立PG16+pgvector，角色初始化与持久化                    |
| synology-redis                   | NAS独立Redis7，密码/AOF/卷                                  |
| synology-minio                   | 当前NAS对象存储方案：pgsty/silo固定版与摘要                 |
| synology-rocketmq                | NAS单broker RocketMQ5.3.1，开发网络配置                     |
| migrations                       | V1–V13兼容SQL副本，正式打包副本在API resources/db/migration |
| docker-compose.yml               | 可选整栈样例，包含应用和中间件，不是默认开发启动方式        |
| docker-compose.local-storage.yml | 旧本机对象存储叠加样例，使用不同MinIO发布版                 |
| nas-minio                        | 旧MinIO模板，不是当前synology-minio/SILO配置                |
| nginx/default.conf               | PC和/m静态资源、Java网关及代理来源头                        |

已有环境不要重复创建中间件或覆盖数据卷。日常Flyway关闭；真实环境执行到了哪个版本必须查询Flyway历史，不从V13文件存在推断。本地SQL镜像副本需字节一致，不修改已执行旧迁移。角色/密码只放各自环境文件，运行账号不得借用owner/root。

根Compose没有完整映射所有新增Search/Fetch/Embedding及资料Worker选项，不能把它视为已验收生产编排；若选择容器化需按实际功能补齐环境并验证。S3/Redis部署存在也不等于Java业务已接入它们。

先使用各目录`docker compose config --quiet`检查配置；真正启动、建Topic、初始化卷权限、数据库升级和对象写入需单独执行，不能从“文档更新”推定完成。
