# 群晖 SILO 对象存储配置

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

当前Compose实际使用`pgsty/silo:RELEASE.2026-10-26T00-00-00Z`及固定sha256摘要，linux/amd64；目录名仍保留synology-minio。运行端口S3 9000/Console9001，示例NAS映射19000/19001，数据在本目录data。健康检查使用镜像内mcli。

`.env.example`末尾旧MINIO_IMAGE变量不被当前Compose读取；实际镜像看Compose，不能据旧模板的latest判断当前部署。环境仅用于管理员和绑定地址/端口，已有.env不覆盖。

`quest-app-policy.json`样例只允许quest-public/quest-private桶元数据及对象读写/删除/分片；不是实际绑定策略、匿名权限或最小权限已验收证据。桶名public也不应解释为匿名公共读取。真实应用账号与root分开。

先`docker compose config --quiet`再按环境选择部署；本轮未建桶、改策略或写对象。目前公共PDF和考纲原件在PG，Java没有完整S3业务适配与预签名下载链路。旧`deploy/nas-minio`和本机storage overlay使用不同MinIO镜像，不应混用数据目录或当成此配置的运行依据。

核验方法见[群晖核验](../../docs/群晖服务验证.md)。
