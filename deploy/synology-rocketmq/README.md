# 群晖 RocketMQ 配置

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

Compose固定apache/rocketmq:5.3.1/linux/amd64；NameServer9876，Broker10911及10909；`broker.conf`固定brokerIP1=192.168.3.40、listenPort=10911，地址变更不能仅改端口映射或.env。单broker ASYNC_MASTER、ASYNC_FLUSH，不具备高可用承诺。

当前开发配置不启用ACL，`aclEnable=false`，仅用于受防火墙限制的可信局域网，不可公网暴露。`autoCreateTopicEnable=false`：资料Topic必须显式准备，Worker不能偷偷依赖默认Topic创建。

普通启动不自动修已有卷权限；只有显式setup profile的init-permissions才用root对MQ命名卷chown。不要在不了解已有数据时执行初始化/清理。日志轮转与store保留参数不代表磁盘已健康；本次未检查或处理NAS磁盘。

资料功能已有专用中继/消费者代码，Topic=`quest-learning-materials`，tag=`generate`，groups=`quest-material-publisher`/`quest-material-worker`；API资料开关和Worker的QUEST_MATERIAL_MQ_ENABLED都默认关闭。旧OUTBOX_ENABLED不启用该流程。

```bash
docker compose config --quiet
docker compose ps
```

上述是只读检查说明，本次未调用。真实Topic准备、消息发布/消费/重投、API HMAC回调与任务幂等需分别验收，见[资料与MQ](../../docs/学习资料与RocketMQ联调验收.md)。
