# API Java → IDEA 内 Claude 交接

更新时间：2026-11-07。目标：让IDEA内的Claude接续Java工程，**先诊断IDEA Maven导入/运行问题，再继续业务开发**。本文件不包含任何密码、Token或Access Key。

## 优先阅读：用户实际打开的是 api-java 子目录

**本文件已归档到 `backend/api-java/docs/`；IDEA当前工程根仍为 `backend/api-java/`。实际目录名称是 `api-java`（带连字符），不是 `apijava`。** 以下命令和`../`路径均以api-java工程根为工作目录，不以docs目录为基准；未注明的工程级路径沿用backend基准。日常指导见根目录[CLAUDE.md](../CLAUDE.md)，启动配置见[IDEA启动配置.md](IDEA启动配置.md)。

用户确认只打开API子项目，不要求本次把它改造成完全独立仓库。父POM为`../pom.xml`，共享依赖为`../common`；Maven可从相对路径读取父POM，但common需要作为IDEA模块导入或先通过父工程install进入项目独立本地仓库。

优先排查：用户只导入api-java，是否导致common不可解析、父工程Maven设置未发现、Run工作目录错误。不要把“应打开backend”当成用户必须重开的前提；在此打开方式下提供可行设置。

从当前api-java终端验证/安装父工程及依赖（同一独立仓库，无需公司Nexus）：

```bash
../mvnw -B -ntp -pl api-java -am clean install
```

此命令只选择API及其依赖common/父POM，不启动服务、不执行数据库迁移。之前仅执行verify不保证common已install到本地仓库。若IDEA单模块解析common失败，这是一条可验证的处理路径，不是当前已确认根因。

IDEA当前项目为api-java时：

- Maven Home：`../tools/distributions/apache-maven-3.10.0`的绝对路径。
- User/global settings：`../.mvn/settings.xml` / `../.mvn/global-settings.xml`。
- Local repository：`../tools/repository`的绝对路径；Importer/Runner继续传`-Dquest.maven.repo=.../backend/tools/repository`。
- backend/.run配置不会自动在子工程里出现，需手工建立Application：main=`com.quest.api.ApiApplication`，classpath=实际API模块，JDK27。
- **Working directory必须设 `$PROJECT_DIR$/..`（backend）**，才能导入现有`.env.api.properties`；不能照抄backend打开方式的`$PROJECT_DIR$`。
- Program arguments：`--spring.profiles.active=deployment`。
- Shell入口可直接使用 `bash ../scripts/start-api.sh`，脚本自动定位backend，Java不经过Python。
- 配置/文档在父目录：`../IDEA开发.md`、`../.env.api.properties`（秘密文件勿回显）。没有把密钥复制进API目录。

如果IDEA Claude权限仅限api-java而不能读取父目录，应按权限提示请求用户批准访问所需父POM/common/.mvn，不借其他会话绕过限制；不要先把整个秘密文件附进聊天。

## 本轮排查结果（2026-11-07 20:06）

### 已确认根因与修复

- 用户实际错误：`com.quest:common:jar:0.1.0 was not found ... This failure was cached ... quest-public-central`。
- IDEA 的 `api-java/.idea/misc.xml` 只关联本目录 `pom.xml`，未关联 `../common/pom.xml`；父 POM 的相对路径继承不会自动把兄弟模块加入 reactor。
- IDEA 已保存 Maven Home=`../tools/distributions/apache-maven-3.10.0`、user settings=`../.mvn/settings.xml`、local repository=`../tools/repository`、`useMavenConfig=false`。CLI effective-settings 同样是该项目仓库及公共 Central；本次不是公司 Nexus 故障。
- 修复前 `tools/repository/com/quest/common/0.1.0` 只有 `.lastUpdated`，缺少 JAR/POM，父 POM 也未安装。使用项目 Maven、同一 settings/仓库、`-f api-java/pom.xml test` 已精确复现用户缓存报错。
- 已从 `api-java/` 执行 `../mvnw -B -ntp -pl api-java -am clean install`：父 POM、common、API 全部成功，31 项测试通过，父 POM 和 common JAR/POM 已安装到项目独立仓库。未修改 POM/Maven settings，未删除缓存，不需要 `-U`。
- 再执行与修复前相同的 API 单模块命令，16 项测试通过、`BUILD SUCCESS`，证明单模块已能从本地仓库解析 common。
- `../mvnw -B -ntp clean verify` 全模块回归：common 15、API 16、Worker 2，共33项，零失败/错误/跳过，`BUILD SUCCESS`。

### API 冒烟与仍待确认项

- 从 backend 工作目录，以 JDK27、`deployment` profile、`--spring.flyway.enabled=false`、本机备用端口18081启动本轮构建的 API JAR；readiness=200/UP（含数据库）、health=200/UP、匿名 `/api/v1/system/info`=401。Hikari连接及关闭成功，日志无 Flyway 迁移执行记录。
- 本轮临时 API 已停止，`lsof` 确认18081无 LISTEN，TCP连接被拒绝；未停止用户IDE进程。停止后直接 `bind` 曾报 Address already in use，后改为监听/连接检查，不能把该 bind 结果误报成残留服务。
- IDEA日志最近一次 Maven importer 连接（19:52:50）使用项目 Maven3.10.0，但 JDK 是Java25；`.idea/misc.xml` 的项目SDK为temurin-27。需在 Settings → Build Tools → Maven → Importing 将 JDK for importer 设为Project JDK27，并核对Runner JRE27。Java25不是本次缺少common的直接原因。
- 后续用户已在IDEA关联`../common/pom.xml`、设置Importer为项目JDK27，并确认Maven依赖不再标红；当前`.idea/misc.xml`已记录API与common两份POM。IDEA原生Run/Debug启动仍未确认，不要将依赖恢复或JAR冒烟写成原生调试已验收。
- 完整工程指导保存在根目录`CLAUDE.md`，不再通过docs中的副本加载。`docs/IDEA启动配置.md`保存Maven及原生运行字段、健康检查，本文归档为`docs/API-Java交接.md`。
- 下一步：保持只打开api-java，Reload All Maven Projects，确认common解析错误消失。若仍显示旧错误，再使用强制更新重新加载，并核对IDE实际仓库；不需要改公司settings或初始化数据库。
- common后续源码变更时，单模块IDE不会自动重编译兄弟模块；重新运行上述父工程install命令，或在Maven窗口额外关联`../common/pom.xml`。无需强制改为打开整个backend。

验证日志（均在backend/tools）：`idea-common-install.log`、`idea-api-single-module-test.log`、`idea-maven-clean-verify.log`、`idea-maven-api-smoke.log`；生效配置摘要来源`idea-effective-settings.xml`，不要回显其他可能带凭据的settings。

以下章节保留初始交接背景；“尚未获得当前错误”等表述以本轮排查结果为准。

## 0. 给接手会话的任务

1. 读取本文、`../IDEA开发.md`、当前`pom.xml`、`../pom.xml`、`../.mvn/`及API非秘密配置。
2. **拿到IDEA当前实际报错和生效设置**，不要把此前公司Nexus故障直接当成本次根因；当前IDEA具体错误尚未提供。
3. 对比“项目wrapper命令行构建成功”和“IDEA内失败”的配置差异，修复当前项目的Maven导入/运行；不修改全局公司Maven配置。
4. 保持Java/Python/前端分工程开发，不恢复根目录统一Python启动Java的方式。
5. 完成后真实执行构建/测试与本地API健康检查，并更新本文/IDEA开发.md，不凭IDE红线消失就宣称修好。

用户只要求本次生成交接MD，当前会话没有为IDEA问题修改POM/Maven设置，也没有擅自启动服务。

## 1. 用户最终选择（不要重新询问或推翻）

- **用户最新确认IDEA只打开backend/api-java/**。Java整体仍为backend下的Maven多模块，不在本次擅自拆除父POM/common依赖。优先遵循本文顶部子目录说明；后文backend视角配置是历史验证基准，不是要求用户重开整个backend。
- PyCharm单独打开backend/ai-python；WebStorm单独打开frontend。
- 应用在Mac本机运行，中间件在群晖；**不要部署应用到群晖**。
- Java、Maven使用正式稳定新版，已确定Java27/Maven3.10.0/Boot4.1.2，不随意回退Java17或Boot3。
- 公共Maven Central，不依赖公司私有仓库；不改用户公司项目的`~/.m2/settings.xml`或系统默认Java8。
- 缺资源记文档，不阻塞可独立开发；涉及共享数据库/远程容器写操作需明确确认，不能绕权限拒绝。
- 文档和用户沟通使用简体中文。

## 2. 当前工作区和工具链

```text
/Users/brian/Desktop/quest-agent/backend
├── pom.xml                     Maven父工程
├── common/                     共享库，不启动
├── api-java/                   Spring Boot HTTP API
├── worker-java/                Spring Boot无Web Worker
├── ai-python/                  单独Python工程，本交接不修改
├── .mvn/settings.xml           项目user settings，公共Central
├── .mvn/global-settings.xml    空安装级settings
├── .mvn/wrapper/               Maven3.10.0下载信息与SHA512
├── mvnw                        自定义项目本地bootstrap
├── scripts/start-api.sh
├── scripts/start-worker.sh
├── scripts/start-java.sh       纯Bash+Maven+Java，无Python/uv
├── .env.api.properties         已配置，0600，禁止读取后回显
├── .env.worker.properties      已配置，0600，无owner密码
├── .run/                       Shell及原生Application配置
├── IDEA开发.md
└── tools/                      被忽略的工具、依赖缓存、日志
```

当前目录未识别为Git仓库（交接检查git status退出128）；不自动初始化Git或提交。

版本：
- Java：Temurin `27.0.1.0.2+1`（JDK27，非preview）。
- Maven：`3.10.0`。
- Spring Boot：`4.1.2`，Spring MVC/Tomcat、Spring Security JWT Resource Server、Actuator。
- MyBatis-Plus：`3.5.18`，**mybatis-plus-spring-boot4-starter**。
- PostgreSQL16/pgvector、Flyway；Jackson3（`tools.jackson.*`），不要改回Jackson2命名空间。

本机绝对路径：

```text
JDK Home：/Users/brian/Desktop/quest-agent/backend/tools/jdk/Contents/Home
Maven Home：/Users/brian/Desktop/quest-agent/backend/tools/distributions/apache-maven-3.10.0
User settings：/Users/brian/Desktop/quest-agent/backend/.mvn/settings.xml
Global settings：/Users/brian/Desktop/quest-agent/backend/.mvn/global-settings.xml
Local repository：/Users/brian/Desktop/quest-agent/backend/tools/repository
```

本机是macOS ARM/aarch64；群晖是x86。**本地JDK用ARM版本正确**，不要为本机开发下载Linux x86 JDK。

## 3. 当前确认通过的证据（本次交接重新执行）

从大仓库根执行：

```bash
./backend/mvnw -v
./backend/mvnw -B -ntp clean verify
```

在backend目录下等效（当前IDEA打开api-java时，使用前述../mvnw）：

```bash
./mvnw -v
./mvnw -B -ntp clean verify
```

本次真实结果：

```text
Apache Maven 3.10.0
Java version: 27.0.1.0.2, vendor: Eclipse Adoptium
common       Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
api-java     Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
worker-java  Tests run:  2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

完整日志（本机忽略目录）：`tools/api-handoff-build.log`。Surefire报告在各模块target/surefire-reports。测试中可能出现Mockito/ByteBuddy动态加载agent警告，当前不是构建失败原因，不屏蔽失败或跳过测试。

因此：**目前没有命令行Maven依赖解析/编译失败的证据，用户报告的问题发生在IDEA设置/导入/运行环节，具体报错待获取。**

此前独立配置真实冒烟：从backend工作目录加载自身properties，Java备用端口readiness200/UP，Hikari连接NAS成功且未执行迁移。不是仅在local无数据库profile测试。临时冒烟已停止。

此前用户要求停止所有本项目本地进程，已执行并确认8080/8000/5173/5174释放。**本次交接检查8080无监听**，用户可能稍后自行启动，接手时重新检查，不能批量杀进程。

## 4. IDEA Maven问题：已知上下文与排查顺序

### 用户截图里的旧设置

- Maven主路径：IDEA Bundled Maven3，显示版本3.9.16。
- user settings：`/Users/brian/.m2/settings.xml`（公司的）。
- local repo：`/Users/brian/.m2/repository`。
- “使用.mvn/maven.config中的设置”原本勾选。

公司settings此前指向私有Nexus `http://172.16.41.245:48081/repository/maven-public/`；首轮连接超时。用户随后明确授权项目使用公共Maven并独立配置，wrapper已经解决命令行问题。**不要打印公司settings可能包含的凭据。**

### 已给用户的IDEA设置建议（实际是否应用尚未验证）

Settings → Build Tools → Maven：

1. Maven Home改为上面的项目Maven3.10.0目录。
2. User settings勾选“重写”，选择项目`.mvn/settings.xml`。
3. Local repository勾选“重写”，选择项目tools/repository。
4. Importing JDK与Runner JRE都设Project SDK27。
5. Importer/Runner VM options追加：

```text
-Dquest.maven.repo=/Users/brian/Desktop/quest-agent/backend/tools/repository
```

6. 点击Apply并Reload All Maven Projects。
7. “使用.mvn/maven.config中的设置”建议取消勾选，因为**当前不存在.mvn/maven.config**。别假定创建过或已有自动设置。

`.mvn/settings.xml`写的是：

```xml
<localRepository>${quest.maven.repo}</localRepository>
<mirror>
  <id>quest-public-central</id>
  <url>https://repo.maven.apache.org/maven2</url>
  <mirrorOf>*</mirrorOf>
</mirror>
```

wrapper会传`-Dquest.maven.repo`，IDEA不一定调用wrapper脚本，故IDE导入器必须单独确认此变量；只改本地仓库UI是否足够需通过effective-settings核实。不要未验证就说IDE必然遵循脚本。

### wrapper与IDE的差异（最值得查）

自定义mvnw固定传：

```text
--global-settings <backend>/.mvn/global-settings.xml
--settings <backend>/.mvn/settings.xml
-Dquest.maven.repo=<backend>/tools/repository
-f <backend>/pom.xml
```

并选择项目JDK27。IDEA可能绕过这些参数：

- 实际Maven Home是否仍Bundled/公司安装？
- Importing JDK、Runner JRE和编译Project SDK是否全部27？
- User/global settings实际各加载了哪个文件？是否仍合并公司mirror？
- `quest.maven.repo`是否真传进Maven importer进程？是否出现未展开占位符路径？
- 已知用户只打开api-java：是否未安装/关联common模块、未发现父目录Maven设置、工作目录误设成api-java？按顶部子目录方案核实，不强制重开backend。
- POM是否被忽略、Offline Mode是否开启、是否仍有旧导入缓存？
- IDEA版本是否支持JDK27语言级别/索引？仅JDK下载成功不代表IDE兼容。
- 模块名是否与`.run`内api-java一致？

这些都是**待验证假设**，当前没有确定哪个是根因。先拿Build/Sync错误和Maven日志，不要开始切镜像、降版本、删整库缓存或删.idea。

必要时用IDEA实际选择的Maven执行help:effective-settings（不要把带凭据的全部输出发聊天），只汇报mirror URL、localRepository、JDK/Maven版本。命令行可作为对照：

```bash
./mvnw -B -ntp help:effective-settings -Doutput="$PWD/tools/idea-effective-settings.xml"
```

如需简化IDE配置（例如消除自定义变量、添加maven.config），先明确设计并补测试；保留相对/跨目录可运行，不把本机绝对路径固化进共享文件。不要对修改后的配置未经完整测试就交付。

## 5. API启动：IDEA原生Run/Debug

历史配置文件位于backend的`.run/Quest-API-Native.run.xml`，名称 **Quest API Native Debug**，类型Application。当前仅打开api-java时该配置不会自动导入，手工创建并将Working directory改为`$PROJECT_DIR$/..`，其他字段参考下表。

```text
Main class：com.quest.api.ApiApplication
Module classpath：api-java（导入模块名不同需选实际模块）
JRE：Project SDK 27
Working directory：/Users/brian/Desktop/quest-agent/backend
Program arguments：--spring.profiles.active=deployment
Before launch：Make
```

Spring Boot从当前工作目录加载`optional:file:./.env.api.properties`。**工作目录不能设成api-java或仓库根，否则配置找不到。** 不用Python、不用uv，不用把密码粘进共享Run XML。

Shell入口（backend终端）：

```bash
bash scripts/start-api.sh
```

先Maven package（skipTests仅缩短启动构建，不等于验收通过），再exec Java JAR。Native Debug才是断点调试入口；Shell不带JDWP。

端点：
- GET http://127.0.0.1:8080/actuator/health
- GET http://127.0.0.1:8080/actuator/health/readiness（含真实DB连接）
- GET /api/v1/system/info：必须有效JWT，匿名401正常。

## 6. 安全与配置（严禁回显真实文件）

`.env.api.properties`/`.env.worker.properties`已经在用户明确批准下由旧文件允许字段复制完成，权限0600，均被忽略。现在不需要用户重新提供密码，更不要cp模板覆盖。

- API运行：quest_app。
- 迁移：quest_owner；只在显式批准的迁移中使用。
- 日常：`SPRING_FLYWAY_ENABLED=false`。
- `SPRING_FLYWAY_LOCATIONS=classpath:db/migration`，SQL随API打包。
- JWT HS256，secret>=32UTF8字节，默认issuer quest-api、audience quest-web，必须sub/exp。
- 内部Java路由当前denyAll；HMAC客户端/服务端业务接入尚未实现。
- Worker无owner密码、Outbox=false，不提供HTTP。
- Java子工程无SSH/PG超级用户/MinIO root凭据。

读取真实配置时仅确认字段存在/长度/权限，不用Read整份秘密文件进聊天，不输出环境全集、签名Token或完整预签名URL。

## 7. 群晖服务状态与数据库迁移

| 服务 | 地址 | 结果/边界 |
|---|---|---|
| PostgreSQL16.14/quest | 192.168.3.40:15432 | 四账号认证、扩展与权限已验证 |
| Redis7.4.12 | 192.168.3.40:16379 | 密码PING、AOF everysec、noeviction验证 |
| SILO S3 | http://192.168.3.40:19000 | 两项目桶、应用认证通过 |
| SILO Console | http://192.168.3.40:19001 | 独立社区Fork，非原官方MinIO |
| RocketMQ NameServer | 192.168.3.40:9876 | 注册查询通过 |
| Broker5.3.1 | 192.168.3.40:10911 | 单机开发、ACL关闭，仅可信LAN |

用户明确同意后Flyway V1–V3已执行：全部success=true，public35表（含历史/分区），重复迁移返回up to date，无需初始化第二次。

权限断言：quest_app题库CRUD，question_version/audit_log仅SELECT/INSERT，不能访问Flyway表；quest_ai只读题库，仅写question_embedding/kb_doc，无app_user/agent_message权限。不要为了IDE报错给运行账号超级权限。

RocketMQ曾因两个卷root:root/755而UID3000不可写退出255；在明确授权后只改两个卷根目录属主3000:3000，Broker已恢复并注册。**磁盘90%警告仍在，用户要求暂不处理**；不擅自清理NAS或提高阈值，不宣称消息写入验收通过。

SILO应用读取桶策略/管理信息被拒绝，但ListAllMyBuckets仍允许，完整最小权限隔离未验证；不要写成所有S3安全测试已通过。

## 8. API目前实现了什么

主要文件：
- `api-java/src/main/java/com/quest/api/ApiApplication.java`
- `auth/SecurityConfiguration.java`：默认认证、JWT校验、健康公开、内部路由拒绝。
- `common/TraceFilter.java`：traceId/MDC清理。
- `common/ApiExceptionHandler.java`：统一异常，隐藏内部错误。
- `system/SystemController.java`：受保护system/info。
- `common/src/main/java/com/quest/common/question/Question.java`、ChoiceGrader：不可变题目契约与判分。
- `api-java/src/test/java/com/quest/api/ApiContractTest.java`：12项集成。
- `api-java/src/test/java/com/quest/api/auth/SecurityConfigurationTest.java`：4项安全配置。

统一响应`{code,message,data,traceId}`；Actuator保留原生健康格式。UTC存储/接口，页面与每日统计需按Asia/Shanghai业务日期边界。

**尚未实现**：真实登录/注册/Refresh Token/RBAC管理、管理员初始化、题库CRUD/知识点API、练习/模考业务、Java Redis适配、RocketMQ Outbox/消费者、S3预签名/上传业务、Java→Python SSE/HMAC调用及持久化审计。

当前没有默认管理员或登录密码。不要把NAS/PG账号当业务登录账号，不伪造数据/统计/AI回复。

Python已经迁移LangGraph1.2.20，FastAPI内部抽取工作流、90tests通过；不是Deep Agents。Java后续调用既有内部协议时再读Python文档，不在这轮IDEA Maven排查擅改Python。LLM未配置，向量表1024维，Embedding调用尚未实现。

## 9. 后续业务顺序

**先解决本次IDEA Maven问题。** 然后：安全管理员初始化/登录 → RBAC → 知识点树 → 题库CRUD/审核发布/版本快照 → 练习判分 → LangGraph文本导入审核 → 可靠事件与索引 → Agent端到端。

新增远程迁移/写入不要假定以前V1–V3授权延伸到下一版本；先说明影响，获取相应批准。测试本机临时服务用备用端口并停止自己启动的进程，不终止用户IDE进程。

## 10. 交接验收清单

- [ ] 已记录具体报错、项目根及已保存Maven/SDK/user settings/仓库；IDEA实际global settings、最新Importer/Runner JDK仍待完整核对。
- [x] 找到CLI reactor与IDE单模块导入差异，安装父POM/common后同参数单模块构建成功，未动公司全局设置。
- [ ] IDEA Reload Maven成功，模块/依赖正确（本轮IDE诊断超时，待实际重载确认）。
- [x] 项目wrapper clean verify仍33项全绿，不跳过测试。
- [ ] IDEA Native API Run/Debug启动（本轮仅JAR冒烟确认数据库readiness=UP、匿名业务401，未替代原生调试验收）。
- [x] 无自动Flyway迁移、无密码回显、无远程资源未授权修改；临时进程已停止。
- [x] 更新IDEA开发.md、本文和实际未完成项。
