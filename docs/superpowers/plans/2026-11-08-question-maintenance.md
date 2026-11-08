# 题库维护布局、单题录入与下架删除

## 用户决定

优先PDF导入；采用Arco `a-dropdown-button`，主按钮“导入 PDF”，右箭头菜单“单题录入”。移除单题文本导入记录区域，录入后的题目和PDF题目统一展示在题库内容中，draft显示未确认。保留PDF批次进度和来源。

已发布题先下架，再编辑或删除；未发布draft/confirmed可删除。编辑/下架清确认，重新确认后才能发布。版本、角色与导入人确认边界继续生效。删除为软删除，不删除原件/来源/历史任务。

## 后端边界

- V7仅准备：public_question新增deleted_at/deleted_by、成对约束和可见性索引，不改已执行V1–V6；执行NAS另获授权。
- 用户后续纠正：单题录入是粘贴非结构化文本，由模型解析，不是手填结构化题目。已移除本轮误加的结构化createQuestion接口/组件，沿用POST /public-banks/{id}/imports {text}，但改由PublicBatchExecutor.startText预留容量后入库、只执行该taskId，调度开关仍false。GET /public-imports/{id}跟踪解析；生成结果进入统一题目列表为draft/未确认，不显示独立记录表。失败不自动重试，不自动发布；可空题号/原答案保护沿用既有候选图。
- POST /public-questions/{id}/withdraw {version}：仅published，version+1，回draft，清confirmed/published元数据，不重置来源。
- DELETE /public-questions/{id} {version}：仅未发布；锁定当前版本并软删除，清确认且version+1，题目列表/读取/审核/发布均不可再次访问。
- PDF批次按题目deleted_at单独统计deletedCount，不把已删除误算待审核或自动重建。

## 本轮工作记录

Java新增TDD回归：新接口原403红灯后实现，下架版本冲突/清确认、已发布不可直接删除、学生拒绝、软删除后读取/编辑/确认/单条和批量发布404、列表排除、PDF deletedCount；单题异步接口缺失红灯后接线，模拟原文录入→仅taskId执行→统一未确认题，旧任务不重跑。最终Java84测试通过。前端由独立任务实现标准DropdownButton/原文Modal/状态操作和回归；主线程不覆盖其文件。未执行NAS迁移、未重启用户服务、未删除/下架真实题目、未调用模型，未自动Git提交。

## 待完成

本轮最终Java84、前端190、Python308、静态部署31项通过；typecheck/lint/format/build、ruff、git diff --check通过。V7 SQL两副本一致、pglast4语句有效。前端已保留单题失败/缓存/轮询边界：最多40次GET后手工查询，恢复不重发POST；标准Modal原生footer。后续用户已明确授权在192.168.3.40:15432/quest执行V7，已执行及重复核验：历史V1–V7成功，1库/1批次/13任务/13题原字段摘要不变，10 confirmed/3 published保持，删除字段全空，约束/索引/权限及vector(1024)核验通过。真实quest_app可读、quest_ai被拒、临时18081关闭；迁移配置新增V7回归RED→GREEN后静态测试32通过。用户可自行重启API，不编辑已执行V1–V7、不自动下架/删除真实题目。
