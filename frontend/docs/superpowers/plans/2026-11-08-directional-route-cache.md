# PC 页面单向缓存实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans 按任务执行。步骤使用复选框追踪；未经用户要求不提交 Git。

**Goal:** PC 页面通过路由深度实现向深层进入时保留浅层页面、返回时销毁深层页面，并使用用户指定的 Vue Router 滚动恢复。

**Architecture:** 在业务布局内部使用独立的缓存 RouterView，通过 `meta.pageDeepth` 和 Vue `KeepAlive` 管理页面实例。仅保留当前访问链；同层切换销毁旧实例，返回浅层复用原实例。缓存与布局/认证身份生命周期绑定，不写入浏览器持久化存储。

**Tech Stack:** Vue 3、Vue Router 4、TypeScript、现有 Vitest 与 Vue Test Utils；按用户后续要求沿用旧项目 mitt（3.0.1，已有锁文件包，新增 PC 直接依赖）。

**后续简化决策：** 用户要求能沿用旧项目就沿用。最终使用 mitt 路由事件 + 原生 KeepAlive/include/fullPath key，删除 route-cache.ts 动态包装与路由快照；路由 name 对齐 SFC 文件名。保留认证作用域清理和有失败测试支持的 bindInstance 守卫兼容修正。最终使用方式见 `docs/页面单向缓存.md`；以下文件规划记录的是最初计划。

**Spec:** 本轮对话中已确认的规则；参考 `/Users/brian/Desktop/思亮/super-order-admin/src/layout/page-layout.vue` 与 `src/router/index.ts`。

## Global Constraints

- 只修改 PC 缓存基础设施、必要路由/布局接入及对应测试；保留另一个 Agent 的业务修改。
- 列表页深度 1，题目审核页深度 2，通用机制支持深度 3；学习工作区/个人 Agent 当前均设为深度 1。
- 保留参考字段名 `pageDeepth`，未配置深度的页面不缓存；登录、改密、诊断、404 不接入缓存。
- 滚动严格使用 `savedPosition ?? { top: 0 }`：只恢复浏览器历史提供的位置，普通 push 回到顶部，不另建位置 Map。
- 登出、身份变化、会话失效、强制改密不得保留可再次激活的旧业务实例。
- 不启动、停止或重启用户 IDE 服务，不变更 NAS、后端和移动端，不提交 Git。

## Review Focus

- 1→2→3→2→1 保留正确实例，返回后再次进入深层必须是新实例。
- 同层页面切换及同组件不同参数/查询切换不能复用已离开的旧实例。
- 导航取消或权限重定向不得提前删除当前页面缓存。
- 缓存组件名称必须和 KeepAlive include 对齐，不能要求所有业务组件手工命名。
- 身份清理、布局销毁后，旧页面实例必须卸载；缓存只存在于当前布局的内存中。

## 文件边界

- 新增 `pc-web/src/components/DepthRouterView.vue`：统一 RouterView/KeepAlive 容器，维护访问链与页面实例身份。
- 新增 `pc-web/src/route-cache.ts`：缓存条目及深度转移规则；如状态规模很小，可将纯规则保留在此文件、渲染留在组件。
- 新增 `pc-web/src/route-meta.d.ts`：为 `pageDeepth?: number` 添加路由类型声明。
- 修改 `pc-web/src/router.ts`：只增加深度元数据与 scrollBehavior，不改现有鉴权和公共库路由。
- 修改 `pc-web/src/layouts/AdminLayout.vue`、`PracticeLayout.vue`：只替换业务 RouterView、引入缓存组件及身份生命周期边界。
- 新增 `pc-web/src/route-cache.test.ts`：真实内存路由/组件实例生命周期回归，补充滚动配置和认证边界验证。

### Task 1：访问链规则与缓存容器

**Interfaces:** 路由 `meta.pageDeepth?: number`；组件 `DepthRouterView` 提供无额外 UI 包裹的业务出口。缓存身份按完整地址区分，不依赖业务组件 name。

- [x] 编写失败测试：用计数器与可修改输入区分实例，验证 1→2→3→2→1、重新进入深层、同层切换、动态参数与 query 切换、未声明深度时不缓存。
- [x] 运行 `pnpm --filter @quest/pc-web test src/route-cache.test.ts`，确认因未实现行为而失败。
- [x] 实现独立容器和必要纯规则；只在成功导航后更新访问链，确保返回目标实例不会先被 include 排除再重建。
- [x] 补充取消导航、命名匹配及宿主卸载测试，运行同一测试文件直至通过。

### Task 2：PC 接入、滚动及身份清理

**Interfaces:** 使用 Task 1 的 `DepthRouterView`，保留业务组件已有数据加载/提交逻辑。

- [x] 编写失败测试：真实 router 配置包含业务页深度；scrollBehavior 对历史位置原样返回，对空历史返回 `{ top: 0 }`；宿主身份变化使旧实例卸载。
- [x] 在布局接入容器，为实例作用域绑定认证身份；增加 `pageDeepth` 类型、路由值和用户指定的 scrollBehavior。
- [x] 验证浏览器 back 返回时浅层状态恢复；普通 push 回到顶部，不额外承诺恢复历史外的位置。
- [x] 执行 `pnpm test`、`pnpm typecheck`、`pnpm lint`、`pnpm format:check`、`pnpm build`。另一个 Agent 的修改若导致失败，只报告，不擅自修改。
- [x] 检查本次 diff，确认无业务内容覆盖；文档中记录路由深度的使用规则及滚动恢复边界。
