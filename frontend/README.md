# 前端工程

更新日期：2026-11-11。项目总览见[根 README](../README.md)，跨端状态见[项目现状](../docs/项目现状.md)，服务边界见[架构说明](../docs/架构说明.md)，完整启动步骤见[本地开发启动](../docs/本地开发启动.md)。本页只维护前端入口与开发方式。

`frontend/` 是独立 pnpm workspace，可由 WebStorm 单独打开；`pc-web` 使用 Arco Design Vue，`mobile-agent` 使用 Vant，`shared` 是双端直接引用的 TypeScript 源码包。

## 当前功能

| 入口                                                       | 代码已实现的行为                                                                                                       |
| ---------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| PC `/login`、`/change-password`                            | 账号密码登录、真实身份恢复、强制改密、角色路由白名单、退出与到期清理                                                   |
| `/admin/users`                                             | 用户查询、创建、启停与重置密码                                                                                         |
| `/admin/banks`、`/admin/banks/:id`、`/admin/questions/:id` | 公共题库列表/创建、单题文本解析、PDF 上传及显式启动、任务状态轮询、题目证据审核与编辑、确认、发布/批量发布、撤回和删除 |
| `/admin/syllabi`、`/admin/syllabi/:id`                     | 考纲 PDF 与元数据、章节编辑、审核状态、发布/归档、审核重试、RAG 索引状态及重建、源文件下载                             |
| `/admin/logs`                                              | AI 调用日志查询与详情                                                                                                  |
| `/practice`、`/practice/agent`                             | 学习偏好与最近会话、练习/聊天会话创建、历史读取/删除、SSE 回答、题目卡片作答/下一题、快捷建议、外部工具状态与来源展示  |
| `/practice/syllabus`                                       | 按科目/年份读取已发布考纲及章节，引导 Agent 解释                                                                       |
| `/practice/materials`                                      | 资料列表、生成状态轮询、Markdown 预览、失败重试和下载；资料由 Agent 对话页发起生成                                     |
| `/practice/profile`                                        | 学习科目、可选考试年份设置，展示账号信息                                                                               |
| `/dev/connection`                                          | 开发连接诊断，非业务登录入口                                                                                           |
| 移动 `/m/sessions`、`/m/chat/:id`                          | 页面骨架与禁用输入，尚未接入真实会话或 PC Coach 流程                                                                   |
| 移动 `/m/login`                                            | 手动内存 Token、健康检查和系统信息诊断，尚未接入账号登录、身份与角色守卫                                               |

以上是源码接线状态，不等同于数据库、对象存储、PDF 解析、向量检索或真实模型的端到端验收。PC 工具展示契约目前明确支持 `search_web`、`fetch_page`，前端不直接执行这些工具。`AdminView.vue` 未被当前路由引用，不作为可用入口。

## 安装与运行

Node 要求 `^22.13.0 || >=24.0.0`，pnpm 为 `10.33.0`。锁文件实际版本：Vue 3.5.45、Vue Router 4.6.4、Arco 2.59.0、Vant 4.10.2、Vite 7.3.7、TypeScript 5.9.3、Vitest 3.2.7；以 `pnpm-lock.yaml` 为准，不将依赖声明范围当成安装版本。

```bash
cd frontend
pnpm install --frozen-lockfile
pnpm dev:pc
pnpm dev:mobile
```

PC：`http://localhost:5173/practice`；移动：`http://localhost:5174/m/sessions`。也可运行 `bash start-pc.sh` / `bash start-mobile.sh`。开发服务监听 `0.0.0.0`，使用 strictPort；修改环境变量后重启。

每个应用使用自己的 `.env.local`，模板见各自 `.env.example`，不读取根目录 `.env`。常用开发配置：

```dotenv
VITE_API_BASE_URL=/gateway
DEV_API_PROXY_TARGET=http://127.0.0.1:8080
```

`VITE_*` 会公开到浏览器，不放密钥。代理通过 `proxy-client-ip.ts` 覆盖 `X-Quest-Client-IP` 为 TCP 来源并移除客户端 XFF；后端可信代理配置与生产入口见根开发文档。生产同源部署使用 `VITE_API_BASE_URL=/`，需重新构建；移动静态资源和 history 回退保留 `/m/` 前缀。

## 验证与维护

```bash
pnpm lint
pnpm format:check
pnpm test
pnpm typecheck
pnpm build
```

`test` 同时运行 shared 与 PC；移动端没有独立测试脚本。最新主线记录为 shared 120、PC 208，共 328 项测试通过，且 lint、格式、类型与双端构建通过；该记录不代表真实服务联调。本次文档整理不重复执行重检查。测试入口为 `shared/tests/`、`pc-web/src/tests/`，产物为两应用的 `dist/`。

页面约定见 [UI 规范](docs/UI规范.md)，缓存机制见[页面单向缓存](docs/页面单向缓存.md)。新增行为先核对当前源码和接口，不以旧计划推断完成度。
