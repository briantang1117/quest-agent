# 学习资料与RocketMQ技术及验收

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

已实现资料任务完整代码：PC会话整理意图/按钮 → `LearningMaterialService`存不可变快照、资料行及事务outbox → `MaterialMqWorker`中继发布MQ → 同Worker消费者HMAC调用API → `MaterialTaskExecutor`认领并调用Python`/internal/material-generate` → 保存Markdown/状态 → PC轮询、资料页、下载和手工重试。

资料用generation/eventId/lease防重，模型调用失败保存failed，不由消息重投触发无限付费重试。消费者仅发送taskId/generation/eventId，不发全文。Worker没有业务数据库也不直接调用模型；API是执行与持久化边界。

| 配置                               | 代码默认/固定值                                                  |
| ---------------------------------- | ---------------------------------------------------------------- |
| API `quest.materials.enabled`      | false                                                            |
| Worker `QUEST_MATERIAL_MQ_ENABLED` | false                                                            |
| Topic / tag                        | quest-learning-materials / generate                              |
| producer / consumer group          | quest-material-publisher / quest-material-worker                 |
| API并发生成                        | 2                                                                |
| outbox每批 / consumer线程          | 8 / 2                                                            |
| Worker消息最大体积                 | 4096字节                                                         |
| 资料输出                           | Markdown，最多100000字符；模型最长600秒，Java/Worker另留传输预算 |

旧`OUTBOX_ENABLED`不是本功能开关，true仍明确拒绝通用outbox未实现。Topic必须显式准备，broker配置关闭自动建Topic；Worker启动先检查Topic，失败不装成已工作。

真实联调需同时确认数据库V13所需结构、API开关、Worker开关、Topic、NameServer/Broker可达、两端HMAC和文本模型。仓库只证明这些代码存在，本轮未创建Topic/开启远端开关/发真实消息。

验收：重复消费不重复生成；失败保存可见原因且必须显式重试；超时任务可收敛；源会话删除后资料仍按所有者授权读取；不同用户不能读列表/快照/下载；下载仅ready；进程重启/租约失效不得让旧generation覆盖新任务。前端与API测试并非真实RocketMQ发收证据。
