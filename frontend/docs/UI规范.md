# 前端 UI 规范

更新日期：2026-11-11。此文维护设计约定和复用入口，功能状态见[项目现状](../../docs/项目现状.md)；工程入口见[根 README](../../README.md)，边界见[架构说明](../../docs/架构说明.md)，运行方式见[本地开发启动](../../docs/本地开发启动.md)。

## 布局与品牌

PC 使用 Arco，移动使用 Vant；管理与学习场景采用独立外壳。管理外壳 `pc-web/src/layouts/AdminLayout.vue` 与 `admin-layout.css` 使用 58px 顶栏、220px 展开/48px 折叠侧栏、左右 16px 全宽内容及正常流 Footer。保持响应式折叠与用户手动选择，不给内容叠加固定最大宽度。

后台纯文字品牌“知序｜管理后台”；一级和三级菜单不放图标，二级分组可保留图标，折叠操作使用 Arco 图标。头像下拉承载改密、退出。仅展示实际可用导航。学习页面使用 `PracticeLayout.vue`，不套管理侧栏。

登录和改密共用纸张式布局、纯文字顶栏、2px 卡片圆角、细边框与克制阴影。桌面分栏、小屏单列，保持无水平溢出。学习与移动现有单字标识不因管理品牌调整而替换。

## 主题与字体

PC 在 `pc-web/theme.ts` 用 Less `modifyVars` 设置 `arcoblue-6=#087d84`，由 Arco 生成色阶；组件使用按需 Less 样式。修改主题、Vite 配置或依赖后重启开发服务，不用 `!important` 抢组件样式。

字体统一从 `shared/src/fonts.css` 引入在线 Google Fonts，不打包字体文件。正文使用 `--font-sans`：Noto Sans SC → Noto Sans → PingFang SC → Microsoft YaHei → system-ui → sans-serif。品牌及原宋体区域使用 `--font-serif`：Noto Serif SC → Noto Serif → Songti SC → STSong → SimSun → system-ui → serif。Arco/Vant 字体 token 与共享变量一致；Footer 品牌使用衬线，宣传语使用正文黑体。网络失败允许本地字体降级，构建通过不能证明在线字体加载成功。

## 列表、表单与反馈

后台列表沿用 `AdminUsersView.vue` 与 `admin-layout.css`：2px 圆角白卡、响应式筛选、查询/清空、分隔线、工具栏、表格和分页。按字段长度分配列宽，不预建无人使用的通用容器。登录与操作日志、使用统计与 AI 调用记录共用上述卡片、筛选和工具栏样式；统计在同一卡片内分区展示 AI 与接口数据，数值列右对齐，空数据与加载失败分开呈现。统计页通过查询重新加载，不重复放刷新按钮；用户授权与额度页使用同款白卡分区，额度摘要、模型表格和栅格充值表单保持统一间距。充值仅填写统一 Token 数量和选填原因，所有模型共用余额；校验警告和操作成功使用顶部 Message，不能用远离操作区域的提示条。

Modal 使用标准 `#footer`，右下按“取消 → 主提交”排列。先 `form.validate` 再请求；请求中展示 loading、防重复提交并限制关闭。成功后关闭和刷新；失败保留输入与弹窗。内容受视口限制并可滚动，不能挤出操作按钮。

陪练消息输入区的发送按钮靠右，不展示额外的提交/意图确认说明文案。

登录只验证必填，不显示开户密码强度或平台权限架构说明。新建、重置、修改密码继续执行完整规则。登录失败不枚举账号状态；401 使用统一凭据错误，429 与网络/服务失败分别给出可理解提示，不直接显示英文异常或 traceId。操作结果用 Arco 顶部 Message，字段规则留在表单项内；不要在登录卡片插入会话到期条或重复错误段。

错误反馈沿用 `shared/src/feedback.ts` 与各端 `feedback/index.ts`，保留诊断关联信息但不将内部信息直接展示给用户。AI/考纲/资料正文走 `SafeMarkdown.vue`；外部来源标明未核验并以安全新窗口链接显示。加载、失败、空数据、任务处理状态应来自真实请求，不用模拟内容替代。

## 复用与验证入口

| 用途                 | 文件（相对 frontend）                                                                          |
| -------------------- | ---------------------------------------------------------------------------------------------- |
| 后台外壳和公共尺寸   | `pc-web/src/layouts/AdminLayout.vue`、`admin-layout.css`                                       |
| 学习外壳             | `pc-web/src/layouts/PracticeLayout.vue`                                                        |
| 题目与资料卡片       | `pc-web/src/components/CoachQuestionCard.vue`、`MaterialCard.vue`                              |
| 考纲表单与章节弹窗   | `pc-web/src/components/SyllabusMetadataFields.vue`、`SyllabusSectionDialog.vue`                |
| 面包屑与缓存出口     | `pc-web/src/components/AdminBreadcrumb.vue`、`DepthRouterView.vue`                             |
| 字体、主题、Markdown | `shared/src/fonts.css`、`pc-web/theme.ts`、`shared/src/markdown.ts`                            |
| 样式与交互回归       | `pc-web/src/tests/arco-theme.test.ts`、`admin-menu.test.ts`、`user-module.test.ts`、各业务测试 |

单元测试和 Less 编译检查验证组件交互、主题及字体声明；真实浏览器需另行检查断点、溢出、滚动、字体网络加载和键盘操作。当前全检查记录见前端 README，不在此维护重复测试计数或单次实施日志。
