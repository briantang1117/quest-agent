# 个人Agent陪练联调与验收

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

默认入口是PC Agent页面→`StudentCoachController/Service`→`CoachAiClient/PublicAiClient`→Python`coach-workflow` LangGraph。图通过受限Java业务工具推进题目、判分或创建资料，讲解复用既有工具循环。`quest.coach.harness.enabled=false`可显式回退原`coach-route`/`coach-explain`路径；旧同步`turns`与Python`coach-turn`保留兼容。详见[编排技术说明](做题Agent编排技术说明.md)。

个人偏好可选择科目和可选考试年份。会话支持列表/创建/读取/软删除，具有题目快照、判分、建议和结构化来源；`requestId`/处理中认领防止重复轮次。学习空间首句可进入对话，意图区分chat/next/answer/clarify/material；明确点击提交直接进入固定`submit_answer`图节点，不先询问模型是否判题；文本识别的答案仍只产生候选选项并等待确认。Java选项校验/判分保持权威。

只从已发布未删除题跨试卷选择；答前不下发答案/原解析，不允许模型以工具搜答案。判分后可讲解和显示反馈。快捷建议由模型提候选，Java根据状态过滤。来源单独存messages.sources，刷新可恢复，不把原始工具参数或JSON字段渲染成正文。

工具默认开启，详[工具技术说明](陪练工具技术说明.md)。按已有配置只注册search/fetch；无工具能力保留文本路径。外部引用不是核验结论，不能改变判分。

SSE：浏览器fetch POST；Java发state/delta/tool_status/reset/done，Python内部还有sources/suggestions/tool_audit。最终回复验证完成后保存；截断/断网/取消不保存半截成功回答。判分已提交而讲解失败时保留判分并给降级说明；连接已断也要清理处理中状态。

验收：跨用户隔离、重复requestId不重复计费、删除会话后迟到回复不能复活、未作答不泄密、流式多字节/CRLF及JSON转义正确、工具阶段无原始JSON、刷新恢复来源、超时不自动重试。新编排的完整轮次还含意图、Java回调与持久化；旧路径95秒讲解截止不代表新工作流完整预算。新增验收：同一授权重复调用业务工具只生效一次；过期、旧登录、错误轮次的授权不能提交；图检查点文件可跨Python重启读取，但不会自动重放付费调用。

已有本地测试不证明真实供应商稳定；准确学情统计、类似题与自适应策略、长期记忆和MCP仍未实现。
