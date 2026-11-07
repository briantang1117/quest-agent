# MinIO / SILO 建桶与应用账号

## 已完成的只读验证

2026-11-07从当前开发机实际访问：

- `192.168.3.40:19000`、`:19001` TCP连接成功。
- `/minio/health/live`、`/minio/health/ready` 均HTTP200。
- 控制台页面HTTP200。
- 使用本机.env凭据通过S3签名认证，ListBuckets成功，返回空桶列表。
- `quest-public`、`quest-private` 均尚未创建；未执行任何远程写入/策略变更。

这证明连通性和S3认证正常，不代表实际上传下载、桶隔离、CORS、备份恢复已验证。不能从这些接口单独确认服务具体构建版本。

## 1. 登录控制台

打开 `http://192.168.3.40:19001`，用部署.env内root用户名和密码登录。不同Console版本可能显示中文/英文，以下以菜单功能为准。

## 2. 创建两个桶

进入 **Buckets / 存储桶 → Create Bucket / 创建存储桶**，分别创建：

| 桶 | 用途 | 初始访问权限 |
|---|---|---|
| `quest-public` | 允许对学员展示的题目图片等静态资源 | **Private / 私有** |
| `quest-private` | 原始PDF/Word、导入材料、草稿附件、Markdown索引导出 | **Private / 私有** |

两桶初期设置：

- 名称严格使用上述小写与连字符，不另加大写或下划线。
- Versioning / 版本控制：先关闭，避免初期删除/容量语义复杂化；备份策略明确后再评估。
- Object Lock / 对象锁：关闭；没有WORM保留需求，不应随手开启。
- 不添加匿名策略，尤其不要启用匿名写入。
- 不配置自动过期删除规则；先保留源材料，等业务与备份策略明确后再设置。
- 服务端加密：尚未部署KMS，不启用要求外部KMS的选项。

**quest-public只是资源用途命名，不意味着必须匿名公开。** 原始方案允许图片桶匿名只读，但当前部署采取保守默认值，先用预签名URL。若后续确定所有该桶图片都可公开，再单独配置匿名GetObject，不开放ListBucket/PutObject。局域网可达也不等于内容可对所有用户公开。

建完后列表应恰好包含两个名称；不需要为每个用户或每个知识点建桶，使用对象key前缀管理。

建议对象key：

```text
quest-public/questions/{questionId}/{随机ID}.png
quest-private/imports/{batchId}/source.pdf
quest-private/drafts/{batchId}/{随机ID}.png
quest-private/kb/{questionId}/v{version}.md
```

路径仅为后续业务约定，现在不用手工创建空文件夹。

## 3. 创建应用权限策略

**不要把root账号给Java或Python。**

进入 **Identity / 身份 → Policies / 策略 → Create Policy**：

- 策略名：`quest-app-storage`
- 内容：复制 `deploy/synology-minio/quest-app-policy.json`。

此策略只允许两个项目桶的列举/位置查询与对象读写删除、分片上传；不允许创建/删除桶、改桶策略、用户/账号管理或服务器管理。应用有删除项目对象的权限，这是业务所需，不等于可以删除桶；后续按实际场景可进一步拆分只读/上传账号。

## 4. 创建独立应用身份

进入 **Identity / Users / 用户 → Create User**：

- 用户名可用 `quest-app`；密码使用独立随机强密码。
- 只绑定 `quest-app-storage`，不要附加 `consoleAdmin`、`readwrite` 等全局策略。
- 若通过Access Keys菜单生成应用密钥，选择从这个受限用户派生；不要误用root派生且没有收紧策略的全局密钥。
- 使用该用户凭据作为AccessKey/SecretKey，或使用其派生应用密钥；凭据写本地.env，不发聊天。

控制台菜单因版本而异；如果创建用户时没有策略入口，可先建用户再Assign Policy。若只显示Access Keys而无身份管理，把菜单截图给开发侧，不随便授予root权限来绕过。

## 5. 应用配置

接入时填写根目录被忽略的.env：

```dotenv
STORAGE_ENDPOINT=http://192.168.3.40:19000
STORAGE_PUBLIC_ENDPOINT=http://192.168.3.40:19000
MINIO_ACCESS_KEY=受限应用AccessKey
MINIO_SECRET_KEY=对应SecretKey
```

两种endpoint必须使用S3端口19000，不是控制台19001。客户端、服务器都要能访问预签名URL里的地址。当前Java对象存储实现尚未接通，这里是接入配置，不表示已经完成上传功能。

## 6. CORS与后续验证

CORS先不配置。只有确定PC/H5真实origin后才添加精确允许来源；服务端上传不受浏览器CORS约束，浏览器预签名直传需要CORS。

创建后可让开发侧只读检查桶与策略；若要验证真实上传/下载/删除，会使用专用测试key并事先明确，不在只读验证中偷偷写对象。

本轮仅生成本地说明和策略JSON，没有替你创建桶/用户或修改远程权限。
