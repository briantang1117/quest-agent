# WebStorm：独立前端工程

**Open选择frontend/**。该目录现在是完整pnpm workspace根，拥有package.json、pnpm-workspace.yaml、pnpm-lock.yaml，不需要根仓库package.json。

两个应用共用shared，所以推荐打开frontend整体，不只单独拷贝pc-web；这属于前端内部共享，不依赖Java/Python/根启动脚本。

## 首次安装

在frontend目录：

```bash
pnpm install --frozen-lockfile
```

Node>=22.12，pnpm10.33.0。WebStorm Settings → Node.js选择本机Node，Package manager选pnpm。桌面IDE的PATH不同于终端时配置QUEST_PNPM_BIN；Shell脚本额外检查macOS常用~/Library/pnpm/pnpm。

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
- test / typecheck / build → 验证

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
pnpm test
pnpm typecheck
pnpm build
```

独立根实测：36tests、3包类型检查、双端Vite构建通过，产物pc-web/dist和mobile-agent/dist。

## 生产配置

Nginx同源部署使用VITE_API_BASE_URL=/，不是开发/gateway。Vite变量在构建时嵌入，不能靠改Nginx容器环境变量替换已有静态产物。

当前页面仍是骨架，没有假题库、AI回复或真实登录。Token仅内存保存，刷新清除；shared含Axios错误、SSE和安全Markdown。
