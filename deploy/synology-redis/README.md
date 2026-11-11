# 群晖 Redis 配置

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

本目录Compose固定redis:7.4.12-alpine/linux/amd64，容器6379，示例NAS端口16379。加载`redis.conf`，环境变量显式传入requirepass；配置含AOF、持久化data目录、512MiB容器限制与PONG健康检查。Redis配置文件本身不做.env替换。

只在已有.env缺失时由模板创建，不输出真实密码。部署前`docker compose config --quiet`检查；容器运行可查`docker compose ps`。本轮未执行启动或连接。

Java业务目前没有完整Redis缓存/分布式限流接线，登录限流与Pythonnonce仍是进程内机制，不能因Redis配置存在就宣称分布式能力完成。地址变化需同步运行配置，详[群晖核验](../../docs/群晖服务验证.md)。
