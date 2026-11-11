# 前端维护约定

更新日期：2026-11-11。

先读[根 README](../README.md)、[项目现状](../docs/项目现状.md)、[架构说明](../docs/架构说明.md)、[本地开发启动](../docs/本地开发启动.md)。前端目录与命令见 [README](README.md)，样式见 [UI 规范](docs/UI规范.md)，路由缓存见[页面单向缓存](docs/页面单向缓存.md)。本文件只补充代码维护约束，避免复制项目状态。

- 在 `frontend/` 安装和运行依赖；共享包 exports 直接指向源码，无单独构建步骤。严格类型设置位于 `tsconfig.base.json`。
- PC 路由入口为 `pc-web/src/router/index.ts`；权限策略为 `shared/src/auth-policy.ts`。PC 已是真实账号登录，不要恢复为手填 Token 页，不要新增 `/admin/agent` 旧入口。开发诊断保留在 `/dev/connection`。
- `shared/src/auth.ts` 管理真实会话；`connection.ts` 管理诊断连接。真实会话持久化后通过 `/auth/me` 恢复身份，勿用本地缓存角色代替服务端身份；到期、401、主动退出和地址变更须清理对应状态。
- 请求契约按域集中于 `shared/src/*-api.ts`，页面不绕过认证请求封装；Coach 流使用 `coach-api.ts` 和 `sse.ts`，保留终止、错误与不完整流处理。
- AI 文本通过 `SafeMarkdown.vue` 和共享 Markdown 消毒渲染。工具状态与来源是服务端结果，不在浏览器实现搜索或抓取工具。
- 移动仍是 Vant 预览及连接诊断骨架。共享层具备 API 不表示移动页面已接入，PC 流程变更不要顺手宣称双端完成。
- Arco/Vant 由 `unplugin-vue-components` 按需导入；PC Less 主题在 `pc-web/theme.ts`。共享字体入口为 `shared/src/fonts.css`。
- 列表、异步任务及会话切换注意卸载/停用后的迟到响应与轮询清理。新增缓存页保持路由名与组件名一致，并核对身份缓存作用域。
- 检查命令为 `pnpm lint`、`pnpm format:check`、`pnpm test`、`pnpm typecheck`、`pnpm build`；`test` 涵盖 shared 与 PC，不能再写成“仅 shared”。jsdom 和构建结果不能替代真实浏览器、真实模型和服务联调。
- 不读取或记录真实密钥；前端环境变量公开可见。验证不应默认调用付费模型或修改运行中服务。
