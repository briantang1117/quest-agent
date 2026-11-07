# WebStorm：独立前端工程

**Open选择frontend/**。该目录现在是完整pnpm workspace根，拥有package.json、pnpm-workspace.yaml、pnpm-lock.yaml，不需要根仓库package.json。

两个应用共用shared，所以推荐打开frontend整体，不只单独拷贝pc-web；这属于前端内部共享，不依赖Java/Python/根启动脚本。

## 首次安装

在frontend目录：

```bash
pnpm install --frozen-lockfile
```

Node 22.13+（22.x）或 >=24，pnpm10.33.0。WebStorm Settings → Node.js选择本机Node，Package manager选pnpm。桌面IDE的PATH不同于终端时配置QUEST_PNPM_BIN；Shell脚本额外检查macOS常用~/Library/pnpm/pnpm。

## 本地配置

各应用单独管理，不读取仓库根.env：

```text
pc-web/.env.local
mobile-agent/.env.local
```

开发内容：

```dotenv
VITE_API_BASE_URL=/gateway
DEV_API_PROXY_TARGET=http://127.0.0.1:8080
```

本次已生成非敏感代理配置；若目标文件原已存在则保留不覆盖。只看页面不需要启动Java，但健康/系统信息联调需要本地Java8080。前端环境变量公开，不能放S3密钥、JWT签名secret或SSH密码。

## Run方式一：npm配置

WebStorm打开本工程package.json，点scripts旁运行按钮：

- dev:pc → PC
- dev:mobile → 移动
- lint / format:check / test / typecheck / build → 验证
- lint:fix / format → 自动修复与格式化

也可Add Configuration → npm → package.json选本工程文件，Command=run，Script=dev:pc或dev:mobile。

## Run方式二：Shell配置

顶栏 **Quest PC / Quest Mobile**，或右键start-pc.sh/start-mobile.sh → Run（Shell Script插件，/bin/bash）。

终端：

```bash
bash start-pc.sh
bash start-mobile.sh
# 等价
pnpm dev:pc
pnpm dev:mobile
```

PC：http://localhost:5173/practice，管理/admin，Agent/admin/agent。
移动：http://localhost:5174/m/sessions。

Vite热更新，改.env后重启。当前dev脚本监听0.0.0.0，用于手机经Mac局域网IP访问，仅在可信网络运行。启动页不是群晖IP；后端代理仍在Mac转发到127.0.0.1:8080。

停止：WebStorm红色Stop/终端Ctrl+C；不要同时重复启动同一脚本，strictPort会对冲突报错。

## 测试构建

```bash
pnpm lint
pnpm format:check
pnpm test
pnpm typecheck
pnpm build

# 修改文件后自动修复代码问题与格式
pnpm lint:fix
pnpm format
```

ESLint 10 flat config 位于 `eslint.config.mjs`，启用 JavaScript、TypeScript 与 Vue recommended 规则，警告也使检查失败。Prettier 3 位于 `.prettierrc.json`，统一无分号、单引号、两空格、100 列和 LF；`eslint-config-prettier` 关闭与格式化冲突的 lint 规则。依赖、构建产物、锁文件和环境文件不参与格式化。类型问题继续用 `pnpm typecheck` 检查。

外层 `/Users/brian/Desktop/quest-agent/` 是主项目，不是前端依赖根。可在主项目根目录执行同名命令（如 `pnpm lint`、`pnpm format`），根脚本会切换到 `frontend/`，不会扫描或格式化后端及主项目文档。依赖只安装在 `frontend/`，版本锁定在本目录 `pnpm-lock.yaml`。

WebStorm 打开 `frontend/` 时，在 Settings → Languages & Frameworks → JavaScript → Code Quality Tools → ESLint 选择 Automatic ESLint configuration；Prettier 选择本目录 `node_modules/prettier`，可启用 Run on save。若打开整个主项目，ESLint 工作目录使用 `frontend/`，Prettier 包仍指向 `frontend/node_modules/prettier`，文件范围限定 `frontend/`，不要对整个主项目启用格式化。

单元测试当前覆盖 shared；构建产物为 pc-web/dist 和 mobile-agent/dist。

## 生产配置

Nginx同源部署使用VITE_API_BASE_URL=/，不是开发/gateway。Vite变量在构建时嵌入，不能靠改Nginx容器环境变量替换已有静态产物。

当前页面仍是骨架，没有假题库、AI回复或真实登录。Token仅内存保存，刷新清除；shared含Axios错误、SSE和安全Markdown。
