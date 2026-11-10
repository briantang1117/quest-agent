# 学习资料与 RocketMQ 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans 实施本计划；各任务采用先红后绿的测试闭环。

**Goal:** 将本人单次对话经 LLM 提炼为私人 Markdown 学习资料，以 RocketMQ 异步投递／消费驱动，在对话文件卡和“学习资料”页预览下载。

**Architecture:** API 同事务保存快照、任务与 mq_outbox。Worker 经 HMAC 内部 API 领取并确认 Outbox 投递，发送 RocketMQ，消费时调用 HMAC 任务执行接口；API 认领、调用 Python 并保存结果，业务表写入不下放 Worker。资料独立于来源会话存活；浏览器只创建与轮询任务，不等待模型长请求。

**Tech Stack:** Java27／Spring Boot4／JdbcTemplate／RocketMQ classic client 5.3.1、Python FastAPI/httpx、Vue3/Arco、PostgreSQL。

**Spec:** `docs/superpowers/specs/2026-11-10-learning-materials-design.md`

## Global Constraints

- 用户已批准总体功能及 RocketMQ 方向；未授权真实新迁移、Topic创建、真实消息投递或真实私聊整理调用。
- 不改已执行 V1–V12；新增 V13 双副本，仅本地测试；不重启用户 IDE 服务、不部署 NAS。
- Java API 是业务库唯一写入方，Admin 无资料读取权限；Worker 不拿 owner 或私人对话数据库写入能力。
- 默认快照100万字符／8MiB，输出32768tokens／10万字符；普通聊天预算不全局放开。所有资料调用独立长超时：Python600s、API650s、Worker700s、任务租约15min；无额外较短Future截断。
- 无自动付费重试；Topic和消费开关默认关闭；人工重试generation隔离旧结果。
- 后续代码不自动提交，已提交基线为2aaed62。

## Review Focus

- 重复或延迟 MQ 消息不能重复调用模型或覆盖新generation。
- source会话删除与资料生成交错：已创建资料继续完成，不复活源会话。
- 从历史response_json重建100条以上公开消息时去重、应用最新判分且不引入隐藏答案。
- HMAC签名含原始body、nonce防重放，Worker内部调用不开放成浏览器权限绕过。
- 模型截断／无DONE／输出超限必须失败，下载只能返回完整已保存资料，客户端关闭不丢任务。

## 固定跨模块接口

### 公共资料 API（本人鉴权）

- `POST /api/v1/coach/sessions/{sessionId}/materials`：`{requestId, instructions?}` → Material（立即返回任务）。
- `GET /api/v1/study/materials` → `{list:Material[]}`。
- `GET /api/v1/study/materials/{id}` → Material（ready时含markdown）。
- `POST /api/v1/study/materials/{id}/retry`：`{requestId}` → Material。
- `GET /api/v1/study/materials/{id}/download` → UTF-8 text/markdown，attachment。
- Material：`{id,sessionId,sourceTitle,sourceDeleted,subject,title,status:'queued'|'processing'|'ready'|'failed',format:'markdown',createdAt,updatedAt,error:string|null,markdown?:string}`。
- CoachSession 新增可选 `materials:Material[]`，前端独立卡片按资料创建顺序显示；不向state_json写卡片，不依赖普通SSE维持任务状态。
- 自然语言模型route支持action=`material`；Java直接创建任务，不二次普通explain。界面按钮不走模型意图识别。

### Worker → API 内部接口

HMAC头 `X-Service-Token`、`X-Timestamp`、`X-Nonce`、`X-Signature`，算法同Python：timestamp\nnonce\nPOST\npath\nsha256(rawBody)。API验证5分钟、单进程nonce，独立限额16KiB（内部仅ID小消息，私人8MiB快照不经过Worker）；沿用INTERNAL_SERVICE_TOKEN／INTERNAL_HMAC_SECRET，测试不得打印。仅这些精确POST路径可走内部服务身份，不创建用户身份。

- `POST /internal/materials/outbox/claim`，body=`{}` → envelope data `{list:[{eventId,leaseToken,taskId,generation,topic,tag,msgKey,payload}]}`；最多8条，有界租约，payload JSON字符串为 `{taskId,generation,eventId}`。
- `POST /internal/materials/outbox/ack`，body=`{eventId,leaseToken,success:boolean}` → envelope data=null；失败backoff重试投递，不重做模型。
- `POST /internal/materials/execute`，body=`{taskId,generation,eventId}` → envelope data `{status:'ready'|'failed'|'processing'|'ignored'}`；已领取重复消息不再调用模型，执行失败应保存failed并返回，内部基础设施错误可非200重投。
- Worker endpoint `QUEST_API_SERVICE_URL`默认http://127.0.0.1:8080；开关 `QUEST_MATERIAL_MQ_ENABLED=false`，NameServer沿用`ROCKETMQ_NAME_SERVER`，Topic=`quest-learning-materials`，producerGroup=`quest-material-publisher`，consumerGroup=`quest-material-worker`，tag=`generate`；不自动创建Topic。

### API → Python

- `POST /internal/material-generate`：`{scope:'student',subject,source_title,instructions,messages:[公开消息对象]}` → envelope data `{title,markdown}`。
- `messages`公开对象为Java CoachMessage现有camelCase结构`id,role,text,question?,result?`；Python严格校验文本/题干/选项/已揭示判分，无内部snapshot字段。
- 资料专用Provider方法`generate_material(messages:list[dict[str,str]]) -> str`返回完整JSON文本 `{title,markdown}`，600s独立有界stream聚合并验证stop/DONE，8MiB输入、1MiB输出预算；不改变旧Provider假替身必须实现的现有调用签名。
- 自然语言`material`扩展只允许route/旧coach-turn意图，其他安全约束不变。

## Task 1：Python 资料整理契约与长输出

**Files:** 新增`app/api/materials.py`、`app/agent/materials.py`及测试；修改config、Provider、main、coach_graph/route提示词。
- [x] 写HMAC/权限、长上下文、私题投影、内容JSON/截断、material意图测试并观察失败。
- [x] 实现独立长调用、自然教学资料提示词、严格边界和原文/AI归纳区分，不调用搜索/工具，不重试付费。
- [x] 全Python pytest616与ruff通过。

## Task 2：Java 持久任务、Outbox与执行

**Files:** 新增`materials/`服务/控制器/内部安全边界与测试、V13双副本；修改coach、PublicAiClient与迁移测试。
- [x] H2先测本人权限/快照/幂等/源删除/消息重复/超期/重试generation与HMAC失败。
- [x] 同事务任务+快照+Outbox，Outbox租约可重投，执行只由MQ精确generation认领；只维护expired processing，不扫描queued执行（30分钟未消费转failed）。
- [x] PublicAiClient资料调用独立650s（覆盖响应体）与响应预算，审计仅元数据/哈希，不保存私人全文到Admin日志。
- [x] CoachSession合成materials、自然语言material路由与按钮共用创建服务，防普通消息写回覆盖资料卡。
- [x] 全Java189项与迁移静态38项通过，不执行远程迁移。

## Task 3：Worker真实RocketMQ适配

**Files:** 修改worker pom/配置与OutboxBoundary，新增MaterialMqWorker、内部HMAC客户端及测试。
- [x] 先测payload仅ID、签名、响应校验、发送成功才ack、消费重复契约、失败/关闭与开关隔离。
- [x] classic client发送Outbox事件，消费有界并发调用execute；ready/failed/processing/ignored可ack，网络错误重投不自动新建generation。
- [x] Worker不写数据库、不读私人资料、不启用旧未实现通用outbox开关；关闭时清理自己线程和MQ客户端。
- [x] Worker20项及Java编译验证通过；配置样例和文档明确Topic/启动/权限/外部验收未完成。

## Task 4：PC资料页与对话卡

**Files:** shared/materials-api.ts、CoachSession协议，PC App/router、LearningMaterialsView、MaterialCard、AgentView及测试。
- [x] 先测顶部入口、按钮只创建一次、自然语言返回资料卡、queued→ready轮询/卸载清理、预览与Markdown下载、失败人工重试、源删不可跳转。
- [x] 列表与卡片共用MaterialCard，下载沿用鉴权blob与全局错误反馈；任务轮询仅在活动任务存在且页面可见时启动，不绑定普通busy。
- [x] 全前端325项及lint/typecheck/format/build通过，真实浏览器用合成接口验收布局与下载。

## Task 5：集成与交付

- [ ] 核对Python/Java/Worker/前端字段、状态、超时预算一致，新增集成契约回归，不以mock测试冒充真实MQ。
- [ ] 运行完整Java/Python/前端/迁移静态门禁，独立review权限、重复消费和长任务恢复。
- [ ] 更新设计/验收/能力清单；准确列出需要用户授权的V13、Topic与真实消息验收动作。
- [ ] 用户另行授权后才真实部署结构／Topic并端到端验收；未授权则保持功能开关关闭。
