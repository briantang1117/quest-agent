# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 工程范围与命令

`frontend/` 是独立的 pnpm workspace，包含两个 Vue 3 应用和一个共享源码包。所有下列命令均在本目录执行，不依赖仓库根目录的 `package.json` 或启动脚本。要求 Node.js 22.13+（22.x）或 >=24，包管理器为 `pnpm@10.33.0`。

```bash
pnpm install --frozen-lockfile
pnpm dev:pc                     # http://localhost:5173/practice
pnpm dev:mobile                 # http://localhost:5174/m/sessions
pnpm lint                       # ESLint，警告也视为失败
pnpm lint:fix                   # 自动修复可修复的问题
pnpm format:check               # Prettier 格式检查
pnpm format                     # 格式化 frontend/ 内的文件
pnpm test                       # shared 的 Vitest 测试，单次运行
pnpm typecheck                  # 三个包分别执行 vue-tsc --noEmit
pnpm build                      # 双端 Vite 构建，不包含类型检查

# 单文件 / 单用例；文件路径相对于 shared/
pnpm --filter @quest/shared test tests/sse.test.ts
pnpm --filter @quest/shared test tests/sse.test.ts -t 'rejects EOF without a done event as incomplete'

# 单应用构建与产物预览
pnpm --filter @quest/pc-web build
pnpm --filter @quest/mobile-agent build
pnpm --filter @quest/pc-web preview       # 4173
pnpm --filter @quest/mobile-agent preview # 4174，入口 /m/
```

ESLint 10 使用 `eslint.config.mjs` flat config，检查 JS、TypeScript 和 Vue SFC；Vue/TypeScript 采用 recommended 规则，浏览器源码和 Node 配置分别设置 globals，`eslint-config-prettier` 最后关闭格式冲突规则。Prettier 3 使用 `.prettierrc.json`：无分号、单引号、两空格、100 列、LF；`.prettierignore` 排除依赖、产物、锁文件和环境文件。工具依赖只在本 workspace，主项目根目录的同名脚本仅转发至此，不检查或格式化 Java/Python。

当前没有双端组件测试或 E2E 测试配置。`pnpm test` 只覆盖共享层，不表示业务联调通过。完整前端检查为 `pnpm lint && pnpm format:check && pnpm test && pnpm typecheck && pnpm build`，不需要数据库、NAS 或真实模型。

WebStorm 可直接打开本目录，使用 `Quest PC` / `Quest Mobile` Run 配置或 `bash start-pc.sh` / `bash start-mobile.sh`。启动脚本支持 `QUEST_PNPM_BIN`，并查找 macOS 的 `~/Library/pnpm/pnpm`。开发服务监听 `0.0.0.0`，仅用于可信网络；端口设置了 `strictPort`，冲突时不会自动换端口。

## 架构与代码流

- `pc-web`（`@quest/pc-web`）：Vue 3 + Vue Router + Pinia + Arco Design Vue。当前 `/practice` 使用练习布局，`/admin` 与 `/admin/agent` 使用管理布局，`/login` 是服务连接与手动 Token 验证页，不是真实登录流程。
- `mobile-agent`（`@quest/mobile-agent`）：Vue 3 + Vue Router + Pinia + Vant 4，面向移动学习 Agent。Vite `base` 为 `/m/`；路由定义写 `/sessions`、`/login`、`/chat/:id`，外部访问需带 `/m/` 前缀。
- `shared`（`@quest/shared`）：双端通过 `workspace:*` 引用的 TypeScript **源码包**，`package.json` 的 exports 直接指向 `src/`，无需单独打包。提供 HTTP 契约、连接状态、SSE 和 Markdown 渲染，不放双端专属页面或 UI 组件。

双端 `src/main.ts` 都先安装 Pinia，再以 `VITE_API_BASE_URL` 初始化 `useConnectionStore`，最后安装路由并挂载。视图从共享 store 获取配置、健康与凭据状态。两个 Vite 配置通过 `unplugin-vue-components` 的 Arco/Vant resolver 按需引入 UI 组件及样式，模板中的组件不要求逐一手动导入。路由页面使用动态导入，HTML5 history 的 base 来自 `import.meta.env.BASE_URL`。

共享包的根导出见 `shared/src/index.ts`；也提供 `@quest/shared/connection`、`@quest/shared/markdown` 与 `@quest/shared/markdown.css` 子路径。TypeScript 的公共配置在 `tsconfig.base.json`，开启 strict、noUnusedLocals 和 noUnusedParameters。

## 配置与请求边界

每个应用读取自己的 `.env.local`（模板为各自的 `.env.example`），不读取仓库根 `.env`。本地代理配置：

```dotenv
VITE_API_BASE_URL=/gateway
DEV_API_PROXY_TARGET=http://127.0.0.1:8080
```

Vite 将 `/gateway` 请求转发给 Java，并去掉该前缀。因此 base URL 是服务根或代理根，**不要额外包含 `/api/v1`**；调用端自己传 `/api/v1/...`，健康检查使用 `/actuator/health`。修改环境配置后需重启 Vite。仅查看页面不必启动 Java；连接验证需要本地 Java 服务。

生产 Nginx 同源部署使用 `VITE_API_BASE_URL=/`。`VITE_*` 在构建时嵌入静态产物，修改容器运行时环境不能重配已有构建。产物分别在 `pc-web/dist` 和 `mobile-agent/dist`；仓库的 `../deploy/nginx/default.conf` 为 PC 与 `/m/` 配置 history fallback，并将 API 转发至 Java。

浏览器业务请求只能经过 Java，不能直连 Python `/internal/*`。权限由 Java 判定；不能把客户端提交的 Agent scope 或路由可访问性当作权限证明。

## 共享层的关键契约

- `shared/src/api.ts`：`createApiClient` 使用 Axios，为每次请求读取当前 Token 并添加 Bearer header。业务响应必须符合 `{ code, message, data, traceId }`，只有 `code === 0` 才解包 `data`；失败统一为 `ApiError`，保留业务码、traceId 和 HTTP 状态。健康探针不是这个 envelope，store 单独通过 fetch 调用。
- `shared/src/connection.ts`：`useConnectionStore` 的 Token 仅存内存，刷新清除；健康检查和系统信息读取均由用户手动触发，不在启动时探测、不轮询、不自动重试。公开健康请求不附带凭据；`/api/v1/system/info` 成功只证明凭据可用，**不代表管理角色已验证**。配置或 Token 变化通过 revision 防止旧请求回写状态。
- `shared/src/sse.ts`：`streamPost` 通过 fetch POST 传 JSON，支持 Bearer Token 和 AbortSignal；使用增量 UTF-8 解码与有状态 SSE 分帧，收到 `done` 才算成功，提前 EOF 抛 `IncompleteStreamError`。不要自动重连或重发 POST，以免重复产生付费模型操作。此模块已有单元测试，但双端 Agent 页面尚未接入它。
- `shared/src/markdown.ts`：渲染链为 MarkdownIt（禁用原始 HTML）→ KaTeX（`trust: false`）→ DOMPurify 最终清洗。不要在清洗后拼接不可信 HTML。配套样式由 `@quest/shared/markdown.css` 提供。
- `shared/src/types.ts`：题目 DTO 使用与第一期 JSON schema 对齐的 snake_case 字段，目前仅单选、多选；Agent 消息采用 text/card/tool blocks。不要因前端命名习惯擅自改动 wire 字段。

共享层测试在 `shared/tests/`，覆盖 API 错误、内存连接状态、Markdown 安全与 SSE。DOM 测试用文件头 `@vitest-environment jsdom` 选择环境。`shared/vitest.config.ts` 使用 forks 并关闭 Node 原生 experimental webstorage，避免 Node 25+ 与 jsdom 存储对象冲突。

## 当前实现与后续方向

当前是工程底座：双端页面大多是明确标注“未接入”的骨架，不包含真实题库、练习、账号登录、会话或 AI 对话；不要用伪造数据、统计或回复掩盖尚未接入的业务。现有路由没有认证/角色守卫。

后续业务范围以 `../docs/superpowers/plans/2026-11-07-private-question-banks.md` 为准，该文档修订了原统一运营题库假设，但尚未实现：

- Admin 创建用户，不开放自助注册；普通用户拥有多个私有题库，本期无公共题库或跨用户共享。
- Admin PC 后台只做账号管理和用量/数量统计，不查看用户私有内容，也不提供刷题。个人题目编辑、导入与 Agent 属于普通用户工作区。
- 本期只做职测/行测的单选、多选，不做综应/申论等主观题能力。
- 当前 `/admin`、`/admin/agent` 是旧骨架，不能据此延续“平台 Admin 可访问所有用户题目”的权限设计。

具体启动细节见本目录 `README.md`；跨服务边界及已实现/待实现状态见 `../docs/架构说明.md`，本地全栈启动见 `../docs/本地开发启动.md`。
