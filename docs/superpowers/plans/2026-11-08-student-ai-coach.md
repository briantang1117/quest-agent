# PC个人中心与AI陪练首版

## 已批准范围

用户已批准：PC先做，移动端不考虑；个人中心单选科目全局保存；学习空间按钮带首句进入个人Agent（只发送一次）；个人Agent选取该科目跨试卷已审核发布题，一题一答、讲解和追问，不生成新题。文本LLM允许调用，其他模型节约，首版不调用OCR/Embedding/Search。后台布局不再扩展。

## 本轮契约与边界

- 科目值：vocational_aptitude（事业编A类职测）、administrative_aptitude（上海市考行测），可未设置；只读已发布且deleted_at为空的公共题。
- Java拥有偏好/会话/消息/题目快照/答案/判分；Python内部HMAC受控LangGraph只做意图和自然语言讲解，不能写业务表或更改判分。Admin拒绝所有学习接口，用户不能读取别人的会话。
- 当前题目在服务端保留完整快照，答前DTO绝不返回答案/解析/原文来源。判分使用确定性答案集合；AI可解释已提交结果但不能覆盖。聊天未答前只获得题干选项，不得推断答案替用户提交。含糊回答明确追问。
- 请求UUID幂等；会话创建UUID作为快捷首句的一次性键。每会话同一时刻仅一条处理请求；先事务存processing，再调用Python，最后事务写结果。网络/校验失败无自动付费重试；结果未知保持失败且不重复自动执行。每次用户主动发送最多一次LLM请求，不做工具自治循环。
- 新会话冻结当前科目；用户改科目只影响新会话，不悄悄串科目。最近会话列表/刷新恢复由后端保存，不信URL/浏览器自报身份。

## 实现顺序（本人逐项实施，TDD）

1. Python /internal/coach-turn：受HMAC保护，student scope；prepare→generate→validate图，输入用户消息/有界历史/当前题干与答后结果，输出action(next/answer/chat/clarify)、keys、reply；无工具、无重试。Mock测试先红后绿，全pytest/ruff。
2. V8仅准备偏好与学生陪练会话/轮次表，私有user_id与请求幂等约束，兼容迁移副本；Java API /api/v1/me/study-preference GET/PUT、/api/v1/coach/sessions GET/POST、/{id} GET、/{id}/turns POST。Java HMAC客户端和事务服务，本地H2/HTTP替身权限/幂等/科目隔离/答前脱敏/判分/已下架排除回归。
3. PC个人中心、学习空间真实快捷入口、个人Agent对话/题目卡片/选项作答/下一题/追问/会话列表。shared协议无UI依赖，不动移动入口，不破坏Admin/缓存/全局Message。SSE真实流式另段推进，首版明确请求等待状态，不用伪流式动画。
4. 完整Java/Python/前端检查和一次独立审查，补重要问题RED→GREEN，记录验证。V8 NAS执行另请授权；不因LLM授权自动开迁移，不重启用户服务，不自动提交。

## 取舍记录

- Ruling: 当前非默认开发分支原地实施，不创建worktree — 用户将登录现有本机工程看效果，当前工作区干净且已提交6cc19ba — 代价为并行隔离弱，逐次核对git status。
- Ruling: 首版HTTP有界请求而非立即做跨服务SSE — 先确保状态/权限/幂等/真实模型闭环，不把整段返回包装成假流式 — 代价是回复生成期间等待，后续复用shared SSE扩展。
- Ruling: 只使用TEXT_LLM，其他模型不调用 — 当前题库已审核，首版选题答疑无需OCR/向量/联网 — 代价是复杂检索和动态难度策略暂不自动化，明确无题/需澄清而非伪造。

## 执行记录

- Python：`/internal/coach-turn` HMAC/student/契约/无重试回归TDD；新增5+项测试，完整313 passed、2 warnings，ruff通过。
- 真实文本LLM最小合成验证：一次请求HTTP 200、`action=chat`、回复76字；未发真实题目/会话，未调用其他模型。
- Java：StudentCoachService/Controller/CoachAiClient、V8；TDD覆盖科目必需、跨试卷选题、答前不泄露、判分一次、私有权属、软删/草稿/异科目排除、消息哈希存储。完整91 tests
- 前端：个人中心、学习空间、个人Agent、SafeMarkdown、coach-api；TDD 4项先红后绿，完整201 tests（shared74/PC127），typecheck/lint/format/build通过。
- V8获用户明确授权，已在NAS执行并重复核验：V1–V8成功，三表空、权限隔离、旧业务表逐行摘要不变、向量仍1024维；临时18081关闭，未重启服务。
- 首版为有界同步请求而非SSE；流式、记忆、自适应、错题本、移动端未做。真实浏览器端到端需用户重启服务后自行验收。
