# 公共题库 Java/Python/后台首段集成计划

## 目标与本轮边界

用户已要求继续对接Java和后台。先交付真实持久化公共库、单题文本导入任务、Python原答案保护/缺答案搜索、人工修正确认发布；不使用假数据或内存持久化。PDF/图片自动导入在文件存储与独立进程隔离后接入，当前不开放不可信文件解析HTTP入口，不把文本闭环说成文件导入完成。

沿用现有Spring/JDBC事务、数据库会话、Vue/Arco和内部HMAC。公共域用独立表，不复用历史无scope题表；Admin只增加明确公共域路由白名单，仍不能练习或读取私人内容。单题文本明确由导入人确定边界；一次任务只处理一道题，最多一次检索、两次结构修正模型调用，不做整卷批量外发。

## 数据与接口

新增V5准备文件（不执行NAS）：public_bank、public_import_task、public_question。UUID字符串主键、UTC时间；原材料/来源/候选草稿为私有管理数据。任务数据库持久化queued→processing→reviewing/failed，处理先持久化领取再调用Python，不在数据库事务内等待网络；失败不自动付费重试。异常中断processing保留待人工处理，不默认重跑。调度默认关闭，只有显式配置并具备Python内部密钥才开启，不因IDE reload擅自触发外发。

`/api/v1/admin/public-banks` GET/POST；`/{bankId}/imports` POST（单题text）与GET；`/api/v1/admin/public-imports/{taskId}` GET；`/api/v1/admin/public-questions/{id}` GET/PUT，`/{id}/confirm` POST、`/{id}/publish` POST。编辑带version乐观锁，任何修改重置确认；确认必须由导入人执行，记录身份/时间/版本，发布再次校验版本和确认，默认不发布。普通用户不能访问全部管理端点；学生已发布内容读取另段开发，避免泄漏答案。

Python新增HMAC `/internal/public-import`，只接受admin、单题文本。复用候选图与独立搜索配置，返回草稿、警告和来源（允许imported答案），不扩大旧`/internal/extract`契约。缺配置503，无自动网络重试，无数据库写入。

前端新增内容管理一级菜单→题库管理二级→公共题库三级，与用户管理路由驱动切换。公共库列表/创建、单题文本导入、任务列表/状态、草稿原文与来源查看、编辑/确认/发布。文件能力未接前不提供可点击的假上传按钮；错误Message，显示真实空状态，不放演示题。

## 执行顺序与验证

1. Python单题接口：先写HMAC/admin/原答案/个人答案/缺答案/生命周期测试RED，再实现GREEN，全量pytest/ruff。
2. JavaV5+JDBC服务/受限路由+HMAC客户端/有界调度：H2集成与本地HTTP替身先RED，再实现；验证权限、锁版本、编辑撤销确认、非导入人拒绝确认、未确认拒发布、调用失败脱敏、不重复领取、local缺库503。SQL副本一致，不改V1–V4。
3. Arco页面/shared契约：前端测试先RED再实现，覆盖菜单路由、API请求、审核状态门禁/错误；全量test/typecheck/lint/format/build。
4. 一次独立审查、确认问题RED→GREEN修复、全量复验，更新文档。不开真实付费调用，不执行NAS迁移、不重启用户服务、不自动提交。

## 决策记录

- Ruling: 在当前非默认分支续作且不自动commit/worktree — 用户要求保留大量未提交实现并直接继续 — 代价是隔离弱于独立worktree，须每次先核对git status。
- Ruling: 先单题文本闭环再文件入口 — PDF资源沙箱和对象存储授权尚未实现，贸然开放会暴露解析风险 — 代价是本轮还不能通过后台上传真实PDF/图片。
- Ruling: 暂用API进程领取数据库任务，默认关闭且无自动失败重试，不接未验收MQ — 先打通可控链路，不虚报消息可靠性 — 代价是处理进程崩溃后须人工处置processing任务。

本文件是可执行设计和进度记录，用户的继续开发授权不包含NAS写入、真实材料外发或自动提交。

## 执行记录

- Python公共单题接口3测试RED（404）→GREEN；完整275通过、2警告，ruff全绿。
- Java公共库接口先RED（403），实现显式白名单/JDBC服务后GREEN；HMAC客户端和任务处理器各先缺类RED再GREEN。全reactor clean verify：66 tests/0失败/0错误，JAR打包通过。新增V5未执行NAS，pglast语法8语句、两份副本一致。
- 前端3测试RED（页面缺失）→GREEN；补真实组件所需matchMedia测试替身，修正Arco Textarea DOM选择器，修正Vue代理structuredClone不可克隆问题。96测试、typecheck/lint/format/build通过；602KiB chunk warning保留。
- 静态部署/迁移配置29测试通过。独立审查5项问题全部补回归RED→GREEN修复（路由复用/编辑竞态/解析类型/冲突证据展示/分页）；另补旧前端授权白名单遗漏的新路由回归RED→GREEN。最终Java68、Python275、前端103通过，前端typecheck/lint/format/build通过。无重要问题延后。并行会话对用户管理页/测试的修改已保留，不回滚。
- 后续用户单独明确授权在192.168.3.40:15432/quest执行V5。迁移配置新增V5目标/强制关闭任务处理回归RED→GREEN，静态测试30项通过；真实只读确认原版本1–4、三表不存在、SQL两副本/JAR一致后执行V5并重复执行。最终1–5五条成功记录，三表/索引/属主符合预期、三表空、quest_app实际读取成功、quest_ai三表访问被拒、vector(1024)不变。临时18081迁移进程已退出。
- 未启动/重启任何用户IDE服务、不开任务处理开关、不外发真实样本、不提交Git。后续联调门槛见`docs/公共题库联调与验收.md`。
