# 试卷详情页布局整理

## 用户指定五项

1. 题目按题号数字升序，无题号置后，分页前排序。
2. 去掉“题库内容”标题，导入PDF/下拉单题录入、批量发布、刷新等合并一行操作栏，窄屏允许换行。
3. PDF批次移到右侧Arco Drawer，主页面不再堆叠批次表；保留批次审核/生成状态和分页、付费开始确认。
4. 标准筛选表单：题干模糊匹配和状态（全部/未确认/已确认/已发布），输入和已提交查询条件分离，查询/清空回第一页，翻页保留条件，清勾选。
5. 试卷页头优化长名称排版，科目、创建时间及整份试卷题数/状态计数；不使用营销hero、大统计卡或新字体，延续青绿Arco与2px规整后台。

## 接口与实现

GET /admin/public-banks/{id}/questions增加keyword/status参数，大小page/size原约定不变。后端先按bank/deleted_at/status约束候选，在Java统一来源题号和结构化题干后contains匹配（不是JSON全文匹配），正整数排序/null最后、ID稳定次序后分页；一库一试卷的首版避免JSON方言差异，未来大型库再做索引化模型迁移。百分号/下划线是普通字符，不作为SQL通配符。

新增GET /admin/public-banks/{id}/question-summary返回totalCount/draftCount/confirmedCount/publishedCount，仅统计当前库未删题，不受搜索条件影响；仍只有Admin白名单，学生403。无DDL/远程写入。

## 记录

后端新增回归先RED（未排序/缺接口）后GREEN：数字2/10/null排序跨页，题干%字面匹配+状态，跨库/软删排除，摘要不受筛选影响，参数非法400与学生403。全Java clean verify通过。

前端单独实现，保留缓存/原文录入/批量发布与版本/生命周期安全回归；不修改真实配置、不重启用户服务，不调用模型，不提交。最终Java85、前端197（shared74/PC123）、Python308、静态部署32通过，typecheck/lint/format/build/ruff和diff-check通过。用户进一步指定header只标题、辅助信息移body，已修复Card固定高度导致的标题溢出；独立刷新按钮移除，查询覆盖刷新三路内容。新增DOM回归先红后绿确认辅助信息在body而非header，错误查询后仍可操作且无刷新按钮。隔离真实Chrome1440/768/390视口几何均无重叠/水平溢出，不调z-index、不强改header为多行auto高度；长标题单行省略并提供title原文。API拦截替身，不访问NAS，截图/tmp/quest-paper-detail-{width}.png。Java需用户重启加载新查询/摘要接口，不执行迁移。
