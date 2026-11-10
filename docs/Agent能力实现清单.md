# Agent 能力实现清单

更新日期：2026-11-09。

本文是持续维护的能力台账：按能力点记录项目做了什么、如何实现、证据在哪里、还有哪些缺口。未来开发完成一项，就更新对应条目，而不是另起一份无法对照的功能宣传。

## 1. 记录规则

每项分开记录三个维度：

- **实现状态**：未实现／仅基础设施／部分实现／已实现。
- **验证状态**：未验证／纯本地通过／真实环境部分验证／真实环境验收通过。
- **交付状态**：工作区未提交／已提交（填写 commit）／已部署或本机加载。

“已有表或适配器”不等于“业务接线完成”；“测试通过”不等于“真实模型稳定”；“用了 LangGraph”不等于“实现 ReAct”；“服务间 HTTP 调用”不等于“模型 Tool Use”或 MCP。

当前统一流式链路仍在工作区，**真实使用曾出现连续降级**；本地已修复协议警告及推理元数据兼容问题，但实际两次失败的确切根因尚未确认，不能标记整条 Agent 链路真实验收通过。

## 2. 能力总览

| 编号 | 能力 | 当前实现状态 | 当前验证与边界 |
| --- | --- | --- | --- |
| A01 | 学习意图识别与动作路由 | 已实现基础路由 | 纯本地通过，真实稳定性待验收 |
| A02 | 受控工作流／状态编排 | 已实现固定流程 | 有确定性分支，不是开放式自主规划 |
| A03 | 流式交互与结构化输出 | 已实现，持续修复中 | 本地跨语言通过，真实使用失败待复验 |
| A04 | 身份、判分与行动边界 | 已实现基础控制 | 纯本地权限、幂等、取消回归通过 |
| A05 | 会话状态与记忆 | 部分实现 | 有会话持久化和科目偏好，无长期学习记忆 |
| A06 | 模型提供的快捷建议 | 已实现基础动作 | chat／next；类似题等高级策略未实现 |
| A07 | 外部能力适配与 Tool Use | 部分实现 | 有程序控制的适配器，无通用模型选工具循环 |
| A08 | RAG 与版本化知识引用 | 已接基础完整链路，待真环境验收 | AI整理、异步Embedding、向量召回及固定版本引用本地通过；V10/V11未执行 |
| A09 | 类似题与自适应选题 | 未实现 | 已列本周任务，当前仍为基础选题 |
| A10 | ReAct 有界行动循环 | 未实现 | 意图路由后固定执行不能称为完整ReAct |
| A11 | 应用级 Skill | 未实现 | 尚无能力包注册、加载和执行体系 |
| A12 | MCP | 未实现 | 项目业务服务尚未实现MCP客户端／服务端 |
| A13 | 可观测性与AI调用审计 | 部分实现 | 有查询与记录，逐模型尝试的完整审计仍有缺口 |
| A14 | 会话知识整理与导出 | 本地实现／联调中 | Markdown资料、RocketMQ投递消费已接代码；V13／真实MQ与模型验收待授权，PDF预留 |
| A15 | 私人题目池与对话式导入 | 未实现，产品方向已确认 | 一人一套、正常题自动入库、疑点走问答 |

## 3. 已有部分逐点登记

### A01. 学习意图识别与动作路由

- **作用**：区分知识提问、开始／继续练习、自然语言答案及含糊作答。
- **实现**：Python短结构化路由输出 `next / answer / chat / clarify`；Java检查动作，明确的按钮提交与下一题不重复调用模型识别。
- **入口**：[coach API](../backend/ai-python/app/api/coach.py)、[轮次服务](../backend/api-java/src/main/java/com/quest/api/coach/StudentCoachService.java)。
- **证据**：[路由测试](../backend/ai-python/tests/test_coach_route.py)、[Java陪练测试](../backend/api-java/src/test/java/com/quest/api/auth/StudentCoachTest.java)。
- **缺口**：相似、同考点、难一点等意图尚无结构化策略；不能宣称已智能安排学习。

### A02. 受控工作流／状态编排

- **作用**：让模型生成、校验、人工审核和业务写入各自有明确边界。
- **实现**：题目抽取有 LangGraph prepare／generate／validate／repair；候选图有来源门禁、条件分支、检索与原答案／解析协调。个人陪练新流式轮次由Java管理认领、判分、生成、完成状态；旧同步coach图保留兼容。
- **入口**：[抽取图](../backend/ai-python/app/agent/extraction_graph.py)、[候选图](../backend/ai-python/app/agent/candidate_graph.py)、[同步coach图](../backend/ai-python/app/agent/coach_graph.py)、[轮次服务](../backend/api-java/src/main/java/com/quest/api/coach/StudentCoachService.java)。
- **边界**：不能把LangGraph内部状态当成已有持久化checkpoint；当前不是Deep Agents，也没有开放式计划执行循环。

### A03. 流式交互与结构化输出

- **作用**：本人消息立即展示，回答逐段输出，完整结果可恢复且不重复生成。
- **实现**：PC一次 `POST /api/v1/coach/sessions/{id}/turns/stream`；Java `state / delta / reset / done / error`；Python增量提取模型JSON中的顶层reply，完整校验后返回建议。
- **入口**：[PC Agent](../frontend/pc-web/src/views/AgentView.vue)、[共享协议](../frontend/shared/src/coach-api.ts)、[SSE解析](../frontend/shared/src/sse.ts)、[Java流入口](../backend/api-java/src/main/java/com/quest/api/coach/StudentCoachController.java)、[JSON增量解析](../backend/ai-python/app/llm/json_reply_stream.py)。
- **证据**：[PC交互测试](../frontend/pc-web/src/tests/student-coach.test.ts)、[流协议测试](../frontend/shared/tests/coach-stream.test.ts)、[Python正文测试](../backend/ai-python/tests/test_coach_explain.py)、[上游分片测试](../backend/ai-python/tests/test_stream_text.py)。
- **已覆盖**：缺done／截断不成功、跨会话取消、已保存判分不回滚、完整回答与建议一起落库；已修Java h2c升级警告和reasoning_content元数据兼容。
- **验证**：Java→真实本地Uvicorn→真实适配器／解析器→模拟供应商分片通过；不代表真实供应商验收通过。真实使用两次降级尚需新日志定位。

### A04. 身份、判分与行动边界

- **实现**：用户／会话绑定；Admin不能陪练；答案按Java快照集合比较；答前DTO和模型上下文不提供标准答案；自然语言答案需卡片确认；requestId幂等、会话互斥、有界并发和取消善后。
- **入口与证据**：[轮次服务](../backend/api-java/src/main/java/com/quest/api/coach/StudentCoachService.java)、[取消控制](../backend/api-java/src/main/java/com/quest/api/coach/CoachStreamControl.java)、[Java陪练测试](../backend/api-java/src/test/java/com/quest/api/auth/StudentCoachTest.java)。
- **边界**：模型不拿数据库凭据、不自行执行SQL；提示词限制无法证明任意模型绝不会推断答案，不能将其夸大为确定性语义防泄露。

### A05. 会话状态与记忆

- **实现**：PG保存科目偏好、会话消息、题目快照、已出题ID、判分与轮次；模型上下文读取最近的有限条历史。
- **入口**：[轮次服务](../backend/api-java/src/main/java/com/quest/api/coach/StudentCoachService.java)、[V8结构](../backend/api-java/src/main/resources/db/migration/V8__student_coach.sql)。
- **边界**：会话消息有数量上限，上下文也被截取；不是无限历史。尚无跨会话摘要、长期记忆召回、掌握度画像或自适应复习。
- **后续**：记忆应区分用户确认事实和模型推断，限定归属，提供更新／清除策略；不是把所有聊天无限塞进prompt。

### A06. 模型提供的快捷建议

- **实现**：模型生成候选建议；Python逐条过滤，Java校验动作及可用状态；完整消息保存建议；PC在最新回答下循环展示，点击发送对应意图。
- **入口**：[coach API](../backend/ai-python/app/api/coach.py)、[轮次服务](../backend/api-java/src/main/java/com/quest/api/coach/StudentCoachService.java)、[PC Agent](../frontend/pc-web/src/views/AgentView.vue)。
- **证据**：[PC交互测试](../frontend/pc-web/src/tests/student-coach.test.ts)、[Java陪练测试](../backend/api-java/src/test/java/com/quest/api/auth/StudentCoachTest.java)。
- **边界**：基础动作仅chat／next，尚不支持“练同考点／提高难度”等策略动作；不能将全部建议能力标成未做，也不能将基础动作冒充智能选题完成。

### A07. 外部能力适配与 Tool Use

- **已有**：OCR、Embedding、Search、Fetch供应商适配；公共导入候选图在缺原答案且配置允许时程序控制地检索，保留来源与时间，仍需人工确认。
- **入口**：[适配器目录](../backend/ai-python/app/providers/)、[候选图](../backend/ai-python/app/agent/candidate_graph.py)、[适配器测试](../backend/ai-python/tests/test_provider_tools.py)、[候选图测试](../backend/ai-python/tests/test_candidate_graph.py)。
- **准确表述**：具备部分受控工具集成基础，不是模型自主Tool Use循环。个人Agent尚没有通用工具schema注册、tool call执行器及工具观察回传。
- **未来登记**：每个工具填写参数schema、身份来源、读写副作用、超时、幂等与调用证据；有适配器但未接业务入口的明确标出。

### A13. 可观测性与AI调用审计

- **已有**：元数据与请求／响应分层保存；后台“系统管理→系统日志”查询；流式成功／失败记录；Java失败阶段、Python安全校验位置等日志。
- **入口**：[AI客户端](../backend/api-java/src/main/java/com/quest/api/publicbank/PublicAiClient.java)、[审计写入](../backend/api-java/src/main/java/com/quest/api/coach/AiCallAudit.java)、[审计查询](../backend/api-java/src/main/java/com/quest/api/coach/AiCallAuditQuery.java)、[后台页面](../frontend/pc-web/src/views/AdminAiLogsView.vue)。
- **证据**：[Java客户端测试](../backend/api-java/src/test/java/com/quest/api/auth/PublicAiClientTest.java)，含真实本地Uvicorn回归。
- **缺口**：当前主要记录Java→Python业务调用，不保证Python内部每次模型重试均有独立记录；部分记录模型名为空、attempts固定，流式响应记录是已提取正文而非供应商完整原始分片。失败详情也不能承诺已收录全部上游原因。
- **边界**：审计可查范围按已授权后台功能执行，但仍不得写入密钥、令牌、完整签名／预签名URL或模型推理内容。

## 4. 待建设能力与验收点

### A08. RAG 与版本化知识引用

- **当前**：已实现上传离线提取→AI整理／疑点→人工启用发布→持久化异步切片Embedding→PG向量存储→按版本／科目／模型／维度过滤召回→Agent有界引用。支持状态、人工重试、归档重建、明确关键词降级及首页“纲”导览；纯本地通过，V10/V11未执行、真实供应商／pgvector验收未完成。见[考纲验收记录](考纲管理联调与验收.md)。
- **入口**：[Java考纲服务](../backend/api-java/src/main/java/com/quest/api/syllabus/SyllabusService.java)、[Python提取](../backend/ai-python/app/api/syllabus.py)、[PC列表](../frontend/pc-web/src/views/AdminSyllabiView.vue)、[Java测试](../backend/api-java/src/test/java/com/quest/api/auth/SyllabusTest.java)。
- **需完成**：真实PG与供应商验收、可选重排／混合排名和更大规模ANN。当前新考纲vector字段不固定1024维，首版在同版本有界候选中Java精确余弦；不复用旧question_embedding索引，不宣称已具备HNSW召回或高并发性能证据。
- **完成证据**：同一问题在不同目标年份引用正确适用版本；无资料时明确不足；他人内容／未发布资料不能被召回；索引更新与失效可验证。
- **约束**：先用现有PG＋pgvector评估，不为了名词新加数据库；不同Embedding模型／版本不能因为维度相同就混用。新迁移及共享环境写入仍须授权。

### A09. 类似题与自适应选题

- **当前**：按科目从已发布未删除题中排除seen后基础选题，无类似题检索策略。
- **需完成**：模型识别同考点／同解法／易错点与难度要求，受控候选检索与排序，Java最终校验；普通下一题可沿用已确定策略。
- **完成证据**：“再来一道类似但难一点”给出符合条件的真实候选，无候选不编造，跨试卷且不重复。
- **非同一能力**：AI新编变式题须单独标记、校验与存储，不冒充真题，不自动发布。

### A10. ReAct 有界行动循环

- **需完成**：模型提出行动→执行授权工具→回传观察→根据结果决定下一步，有最大步数、总耗时／调用预算、终止与澄清规则。
- **完成证据**：例如查考纲→找类似题→无候选后合理调整或向用户澄清；有工具轨迹可查，不能靠固定脚本冒充自主调整。
- **边界**：不展示内部思维链；展示工具名称、执行状态、来源与简洁行动摘要。高副作用动作仍需确认。

### A11. 应用级 Skill

- **需完成**：类似题强化、错题复盘、考纲解读、会话知识整理等能力包，明确触发条件、所需上下文、可用工具、流程规则、输出及版本。
- **完成证据**：同一能力可复用、可独立测试，注册／加载／执行路径明确，而非仅添加一段prompt。
- **边界**：开发工具自身的skill配置不算应用已实现Skill；可用自有模块设计，不预先绑定某家SDK。

### A12. MCP

- **当前**：没有项目业务MCP实现；开发IDE或编程助手拥有MCP工具不算本项目能力。
- **按需建设**：当工具需要多客户端复用或接外部服务时，引入MCP客户端／服务端，登记工具／资源、协议实现、认证授权和调用路径。
- **完成证据**：真实协议发现与调用测试、断开／超时处理、跨用户隔离；普通HTTP接口包装不能直接称为已接MCP。
- **优先级**：工具内部HTTP契约可以先做；不为技术展示强行增加协议层。

### A14. 会话知识整理与导出

- **当前**：2026-11-10 按用户确认首版只做 Markdown，PDF预留；已接顶部学习资料、对话按钮／自然语言创建、文件卡、预览下载、PG持久化任务／Outbox、独立Worker RocketMQ客户端与HMAC执行入口。代码本地实现不等于真实MQ或模型效果已验收，见[联调与验收](学习资料与RocketMQ联调验收.md)。
- **需完成**：授权后执行V13、创建专用Topic并进行真实生产消费与模型资料质量验收；后续PDF独立实现。区分原文与AI归纳，保留出处。
- **完成证据**：纯答疑及刷题均可导出；公式／中文／分页正确；半截生成不进入完整文档；不泄露未答题答案或其他会话。
- **后续归类**：导出可以成为Tool；知识整理可以成为Skill，但须在实现对应机制后再登记。

### A15. 私人题目池与对话式导入

- **当前**：未实现；[产品设计已记录](superpowers/specs/2026-11-09-private-question-pool-design.md)，不自动纳入本周交付。
- **需完成**：Agent上传文本／扫描PDF／截图后，明确导入指令启动任务；每位用户一套题目池，不分试卷。正常题自动可练习，疑点通过聊天建议与决策处理，不要求逐题审核或跳管理页。
- **辅助入口**：简化的用户侧“我的题库”，用于查找、查看来源、修改和删除，不是必经步骤。
- **完成证据**：多来源证据可追溯、图文不丢失、AI答案标记、部分成功与断网恢复、范围明确的批量问答决策、跨用户权限及幂等回归。
- **关联能力**：受控工作流、OCR／多模态、Tool Use、人工交互恢复；应用级Skill、ReAct、私人RAG须实现对应机制后分别更新，不因导入功能存在就一并标完成。

## 5. 后续每项开发的填写模板

```markdown
### Axx. 能力名称／本轮增量

- 实现状态：
- 验证状态：
- 交付状态：工作区／commit／运行环境
- 用户场景与作用：
- 输入、输出与执行流程：
- 代码／API／工具入口：
- 关联能力：RAG／Tool Use／ReAct／Skill／MCP／Memory（只填实际涉及的）
- 权限、版本、成本、重试与副作用边界：
- 自动化证据：测试文件、命令、结果、验证日期
- 真实验收证据：环境、操作、结果；未验收写“未验收”
- 已知限制与下一步：
```

维护约定：修改状态时同时更新入口与证据；失败复现未关闭时保留限制说明。新能力不能只补术语，不补具体用户行为、代码和测试。

## 6. 已有验证记录与相关资料

- 2026-11-09 本地最新回归：Java common15／API91／worker2，共108项；Python402项；最近前端全量shared88／PC152，共240项。各项对应不同轮次的验证，不冒充同一次真实全链路验收。
- Python/Java最新离线修复只涉及后端；未连接NAS或调用真实模型。真实使用降级需后续结合安全日志再验收。
- [个人Agent陪练联调与验收](个人Agent陪练联调与验收.md)
- [统一轮次流式修复记录](superpowers/plans/2026-11-09-coach-stream-repair.md)
- [本周任务：类似题与会话导出](本周任务-2026-11-09.md)
- [多来源导入证据层设计](多来源导入证据层设计.md)
- [架构说明](架构说明.md)
