# 用户模块第一阶段实施计划

> **For agentic workers:** 使用 superpowers:test-driven-development；按下列交付边界实现与验证，不修改已执行迁移，不写NAS数据库，不停止用户IDE进程。

**Goal:** 实现Admin开户、普通用户登录/首次改密、用户启停/重置与会话撤销的PC闭环，不实现题库或伪统计。

**Architecture:** Java业务服务维护app_user与服务端会话；JWT负责签名验证，数据库会话/用户状态负责即时失效；PC使用Arco和内存认证store。数据库V4仅生成文件及本机隔离测试，不在NAS执行。Admin与student账号类型互斥，无自助注册。

**Tech Stack:** Java27 / SpringBoot4 / Spring Security / JDBC / bcrypt / PostgreSQL16；Vue3 / Pinia / Arco / Vitest。

**Spec:** docs/superpowers/plans/2026-11-07-private-question-banks.md。

## Global Constraints
- Admin不能访问普通用户学习/题库/Agent工作区，student不能用户管理；后端校验不能仅靠路由守卫。
- 不提供默认账号/密码或匿名HTTP初始化Admin；显式一次性命令初始化，凭据不输出。
- 所有认证会话在数据库有状态，登出/禁用/重置/改密撤销旧token；本期无Refresh Token，session有效期2h；按用户后续要求持久化token，刷新后经me核验恢复，不缓存密码/角色。
- 用户名小写ASCII字母开头，3–32位[a-z0-9_]；口令12–64字符且bcrypt UTF8字节<=72，不随意trim；展示名1–64去首尾空格。
- 身份DTO role=admin|student，status=active|disabled，mustChangePassword；仅Admin创建student，不通过API创建新Admin。
- 日期UTC；当前没有可靠私有库统计，用户列表不返回伪造题库数量或内容。
- 本轮不自动提交，不改其他会话的格式配置或启动用户服务。

## Review Focus
- 用户禁用/密码重置后旧JWT立即失效，无法通过旧token或伪造role绕过。
- 初始改密账号除me/change-password/logout外均拒绝，不能借路由直接进入业务。
- 登录失败通用提示、无账号枚举；限流按账号和来源限制且不吞服务端错误。
- 跨账号管理与Admin内容端点拒绝，任何DTO/日志/审计不暴露password_hash/token/原文。
- 过期请求/并发登出后UI不得恢复旧身份，未授权请求明确显示错误。

## 契约（前后端共同遵守）
前缀/api/v1，统一{code,message,data,traceId}。

UserProfile={id:string,username,displayName,role:'admin'|'student',status:'active'|'disabled',mustChangePassword:boolean,createdAt:ISO,lastLoginAt:ISO|null}。
TokenResult={accessToken:string,expiresAt:ISO,user:UserProfile}。

- POST /auth/login {username,password} -> TokenResult，匿名允许。
- GET /auth/me -> UserProfile。
- POST /auth/logout {} -> null，当前会话撤销。
- POST /auth/change-password {currentPassword,newPassword} -> TokenResult，撤销旧会话并返回新会话；首次改密也走此接口。
- GET /admin/users?page=1&size=20&keyword=&status= -> {list:UserProfile[],total,page,size}，仅普通用户列表，可筛选username/displayName，size<=100。
- POST /admin/users {username,displayName,initialPassword} -> UserProfile（student，mustChangePassword=true）。
- PATCH /admin/users/{id}/status {status:'active'|'disabled'} -> UserProfile。
- POST /admin/users/{id}/reset-password {newPassword} -> UserProfile，mustChangePassword=true并撤销全部会话。

错误：40100登录/会话无效；40300权限不足；40310必须先改密；40900用户名冲突；40000参数无效；40400用户不存在；42900登录限流；50300用户持久化模块未接入（local profile无DB时）。

## Task 1：Java业务与本地数据库测试
- [x] 测试先行覆盖bcrypt验证、JWT+数据库会话、角色分流、强制改密、撤销、Admin开户/分页/状态/重置与敏感字段。
- [x] 新增V4迁移（API资源与deploy兼容副本），明确account_type/must_change_password/session结构与已有数据处理；不修改V1–V3。
- [x] JDBC用户/会话存储、事务服务、认证控制器与安全链；local无DB提供拒绝而非假账号；保留现有健康/系统契约回归。
- [x] 显式Admin初始化命令（仅非Web、需显式环境/确认），无默认秘密；文档记录但不运行NAS。
- [x] 全Java测试/打包通过，使用H2/本机测试隔离远端NAS。

## Task 2：PC真实认证与Admin用户管理
- [x] shared认证DTO/API/client、内存auth store、测试状态过期与权限/改密路由规则。
- [x] Arco真实登录/首次改密/登出，Admin用户列表/创建/启停/重置，防重复提交与错误显示。
- [x] 路由区分Admin/student，旧后台Agent路由不再归Admin，普通用户界面保持业务未接入提示。
- [x] 完整lint/format/test/typecheck/build；不启动额外长期服务。

## Task 3：集成与操作文档
- [x] 更新全局契约、实施状态、V4迁移和安全Admin初始化手册，不包含真实密码。
- [x] 新增无远端写入的契约/迁移副本静态测试，回归各工程。
- [x] 独立审查关键安全流程，重要问题以回归用例修复。
- [x] 真实NAS升级/Admin创建/用户端到端需要用户后续明确批准，未执行则不得声称已可在现有IDE服务立即使用。

## 实际交付与未执行项

代码阶段完成并验证：Java61项、前端75项、配置27项通过；Python原90项保持通过。独立审查两项限流缺陷已修并回归。PC主包599.88kB有构建体积警告，不影响构建但后续需评估拆包；未屏蔽警告。

**未执行，仍待授权/操作**：NAS V4迁移、历史用户禁用实际影响核验、首个Admin创建、可信代理真实配置、用户IDE重启及真实浏览器E2E。这些不在上述“完成”勾选范围内。操作说明见backend/api-java/docs/用户模块与安全初始化.md、docs/用户模块浏览器验收.md。

## 范围裁决
- 初版2h短期JWT+数据库会话、无Refresh Token；用户后续要求登录持久化，现以localStorage保存token/expiresAt/baseURL，刷新经me核验后恢复，过期/401/登出清除。记录XSS可读风险，不保存密码/信任缓存角色；HttpOnly Cookie/刷新轮换/CSRF另行设计。
- Admin只展示账号元数据，私有题库归属表未落地之前不实现内容统计，绝不跨用户返回题干/附件/对话。
- 后端存在旧super_admin/student角色字典，V4建立明确账号类型，以数据库实际类型发权限，不依赖旧角色叠加授权。历史账号遇到不明确角色不得自动升级管理员。
