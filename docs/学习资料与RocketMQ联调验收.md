# 学习资料与 RocketMQ 联调验收

更新：2026-11-10。本地实现及自动化验证已接线，最终并发审查中；本文件的配置说明不代表已完成真实 NAS 迁移或 MQ 收发。

## 功能范围

- 顶部“学习资料”统一查看本人所有会话生成的资料。
- 对话顶部按钮或明确自然语言“整理本次对话为复习材料”创建任务；立即返回，不等待模型。
- 内容经模型提炼为知识点、问答、已讨论例题、易错点等，非聊天记录拼接。
- 本期 Markdown，PDF 仅预留。Markdown 持久保存在 PG，下载不重新计费。
- 资料与来源会话独立，删除源会话不删除资料，也不中断已创建资料任务。未答题不向整理模型发送隐藏答案。

## 实际链路

```text
浏览器 → Java API 创建任务／冻结公开消息／同事务写 mq_outbox
                   ↓
本机 Worker → HMAC API 领取待投递事件 → RocketMQ Topic
                   ↓                      ↓
         API 确认投递成功         本机 Worker 消费
                                          ↓
                       HMAC Java execute → Python → LLM
                                          ↓
                           Java 保存完整 Markdown 和状态
```

PG 是任务状态、快照与资料事实源，RocketMQ 是生成任务的投递／消费通道，不使用 PG 轮询代替 MQ 消费。Worker 不写业务数据库，不接收 owner 凭据；MQ 消息只有 taskId、generation、eventId，均为 UUID 字符串，不包含私聊正文。

发送至少一次，Outbox ACK 丢失可重投；API 按 taskId／generation／eventId 和行锁领取，重复消息不二次调用模型。无法确认是否已计费的超时 processing 标 failed，用户人工重试产生新 generation，不自动重复付费。若消息因重投耗尽进入死信，API维护将超过30分钟未消费的queued任务标failed，提示检查MQ后手动重试，旧generation消息不能执行；维护不自动调用模型。资料模型调用审计仅保存大小／SHA256摘要和结果元数据，不向 Admin 日志详情暴露私人快照或完整 Markdown。

## 开关与本机启动

不要先开启：新数据库结构与 Topic 的真实写入仍需单独授权。

- API：`quest.materials.enabled=true`（环境 `QUEST_MATERIALS_ENABLED=true`），默认 false。关闭时禁止新建／重试／领取投递／领取生成，不取消已经开始的调用；已有完成资料仍可列表、预览及下载。
- Worker：`QUEST_MATERIAL_MQ_ENABLED=true`，默认 false；普通 `OUTBOX_ENABLED` 保持 false，不使用旧占位开关。
- Worker API 地址：`QUEST_API_SERVICE_URL=http://127.0.0.1:8080`。
- NameServer：沿用项目 `ROCKETMQ_NAME_SERVER`，群晖9876；Broker实际地址需要开发机可达，配置不等于真实消息已验收。
- Topic：`quest-learning-materials`；Tag：`generate`。
- Producer Group：`quest-material-publisher`；Consumer Group：`quest-material-worker`。
- Worker／API／Python 内部HMAC配置需匹配，复用现有内部token/secret；不把值复制到文档、日志或命令行。
- 应用全部在Mac运行；NAS只运行已有中间件；不运行根deploy Compose，不部署API/Worker/Python至NAS。

用户IDE进程不由开发助手自动重启。完成验收准备后用户分别重启API/Python，并启动独立Worker工程。

## 长上下文预算

按用户确认的项目1M上下文模型配置：资料默认输入有效内容100万字符、序列化8MiB，输出32768tokens、Markdown10万字符；不是承诺100万字符等于100万tokens。超过预算或供应商拒绝窗口应明确失败，不静默裁切成最近若干条。

资料Python调用600秒，API请求与响应体总截止650秒，Worker内部执行700秒，任务租约15分钟；独立于普通对话95秒正文流和140秒轮次。普通聊天限制不全局放开。

快照从公开的历史完成轮次response_json和当前已持久化消息合并，按消息ID去重／更新已揭示判分；不使用临时SSE片段、不取current.snapshot内部答案。当前历史数据如果缺少可恢复的公开轮次，不能承诺恢复从未持久化的内容。

## 本地验证记录

- 完整 Java `verify`：189项、0失败／错误／跳过，包含API资料任务19项、资料开关3项、HTTP长响应／审计3项及Worker20项。一次全量运行暴露Worker短期限测试异常包装竞争，已严格限定两种timeout异常后重跑全量通过，没有放宽生产超时。
- 独立审查后补强并有回归：超过30分钟未消费的排队任务转failed（MQ死信不再永久queued）；滚出100条消息的历史题按公开顶层判分补回；流式自然语言整理失败保持failed并传播原业务码，不伪装成功；删除来源后原创建请求仍幂等返回；创建与重试统一来源→资料锁顺序；HMAC时间窗改为显式上下界比较。
- Python：616项通过，ruff check／format通过，保留现有2条依赖弃用warning。
- 前端：325项（shared117／PC208）通过，lint／typecheck／format／双端build通过；资料专用业务码覆盖HTTP与SSE安全中文反馈，保留既有大包warning。
- 迁移／部署静态测试38项通过；V13双副本一致，未执行。
- 浏览器使用独立Chrome和合成业务响应验证1440／560px布局、预览安全及真实Markdown下载落盘；没有以此宣称真实NAS／MQ／模型链路通过。

## V13 迁移（2026-11-10 用户明确授权后执行并核验）

- 使用项目现有 Flyway 12.4.0 与 JDBC 依赖独立执行，未启动 API、Worker、调度器或模型服务，未重启用户 IDE 进程。
- V1–V12 校验通过；V13 成功新增 `learning_material`、`learning_material_request` 两表及 `mq_outbox.lease_token/lease_until` 字段，成功标记 `success=true`，checksum `1532680943`；迁移历史版本序列为 1–13，重复执行迁移数为 0。
- 既有业务表仍为 49 张，新表 2 张；两张新表均为 0 行，`mq_outbox` 为 0 行。
- 权限以实际账号核验：`quest_app` 可读取资料表、无 DELETE、无 quest_ai 授权；`quest_ai` 读取被拒绝。
- 既有数据计数：会话 13（其中 8 个为用户自行软删除）、轮次 47、用户 3、公共题库 1、公共题目 13（13 已发布）。
- 首次执行后的自建摘要校验把“新增两张表”误判为既有表变化并中止了后续断言，迁移本身已成功；随后改用只读脚本按既有 49 表范围复核，未回滚、未重复写入。该误报来自校验脚本口径，不是数据异常。
- 已执行的 V13 SQL 不可再编辑；后续变更须新增版本。

## 待授权真实验收

1. ~~执行V13~~ 已于 2026-11-10 授权执行并核验，见上一节。
2. 在现有RocketMQ创建专用Topic／验证Group，发送明确合成任务消息，核验生产确认、消费、重复消息及状态；不删除或清空其他Topic。
3. 配置并重启本机服务，先用合成会话通过真实MQ＋假模型验证，再按用户授权范围调用真实模型验收资料质量。
4. 验证浏览器离开后任务完成、来源会话删除后资料保留、跨用户/Admin拒绝、重复消息只调用一次、失败人工重试、真实Markdown下载。

当前未获上述远程结构／MQ写入授权；本地自动化测试通过也不代表真实RocketMQ已验收。
