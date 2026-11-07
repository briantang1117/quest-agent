# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 当前产品约定与优先资料

沟通、文档和新增注释使用简体中文，代码标识符保持原有风格。

原始需求为`AI刷题平台-第一期方案.md`，但后续用户决策已修订部分范围。**业务开发优先读`docs/superpowers/plans/2026-11-07-private-question-banks.md`**，不能照搬旧“运营统一管理题库”假设：

- Admin创建普通用户，不开放自助注册。Admin仅管理账号、查看用户用量/数量统计，不查看题库、题目、附件、作答或对话内容，不具备刷题能力；不要实现成绕过所有内容权限的超级学员。
- 普通用户可拥有多个私有题库，本期无公共题库、跨用户共享或题库市场。平台考试/科目分类可共享，题目不可共享。
- 当前方向：事业编A类/职测、上海公务员市考/行测。本期只做单选、多选；综应、申论仅为未来方向，不增加主观题功能。
- 私有隔离必须覆盖数据库查询/关联、Agent工具、检索/向量、缓存、MQ事件、S3预签名授权和统计接口，不能只隐藏前端菜单。
- 用户模块第一阶段已进入本地实现/测试：登录、强制改密、Admin开户/启停/重置和数据库会话；最新状态见`docs/用户模块开发记录.md`和`docs/用户模块接口与验收.md`。**NAS V4已在明确授权后执行并核验，首个Admin尚未创建**，用户现有IDE服务需要自行重启后才能加载新代码；个人题库模型/CRUD/用量统计仍未实现。

全局架构见`docs/架构说明.md`；启动入口见`docs/本地开发启动.md`；中间件验证见`docs/群晖服务验证.md`。旧交接/进度中的历史限制可能已解除，以当前代码、最新局部文档和真实命令为准。

## 三个独立开发工程

应用在Mac本机运行，群晖仅运行PG、Redis、SILO和RocketMQ。**不要默认向群晖部署应用，也不要启动根deploy Compose重复创建中间件。**

- Java为`backend/` Maven多模块：common提供统一响应和纯领域契约；api-java是唯一业务HTTP入口/业务库写入方；worker-java是独立后台进程。用户IDEA实际只打开`backend/api-java/`并关联common；必须遵守更具体的`backend/api-java/CLAUDE.md`和`docs/IDEA启动配置.md`。
- Python独立工程为`backend/ai-python/`，PyCharm打开此目录。FastAPI内部服务+LangGraph受控编排，Provider保持OpenAI-compatible供应商中立，不强制换Anthropic SDK。Python不直接写业务表，未来仅写派生索引/经Java提交草稿。
- 前端独立工程为`frontend/`，WebStorm打开此目录。pnpm workspace包含PC（Vue3/Arco）、移动Agent（Vue3/Vant4）及shared协议/HTTP/SSE/Markdown安全渲染。根package.json仅为历史命令转发，实际依赖根和锁文件在frontend。

IDEA、PyCharm、WebStorm会话可能同时修改不同子工程。先看git status，尊重其他会话变更，别覆盖/回滚它们；跨子工程改动先明确接口边界。不要批量停止用户正在IDE里运行的进程。

## 构建、测试和启动

以下命令从仓库根执行，除非注释另有说明。

### Java

Java27（非Preview）、Maven3.10.0、Spring Boot4.1.2、MyBatis-Plus Boot4 starter；实际版本以POM为准。Jackson3包为tools.jackson.*，不要照抄Boot3包路径。

```bash
./backend/mvnw -v
./backend/mvnw -B -ntp clean verify
# API及其依赖，安装父POM/common，适用于IDE只导入API的情形
./backend/mvnw -B -ntp -pl api-java -am clean install
# 单测试类；其他reactor模块允许没有匹配测试
./backend/mvnw -B -ntp -pl api-java -am -Dtest=ApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test
# 单测试方法
./backend/mvnw -B -ntp -pl api-java -am '-Dtest=ApiContractTest#anonymousBusinessRequestIsDeniedWithEnvelope' -Dsurefire.failIfNoSpecifiedTests=false test
# 本地启动（先打包，跳过测试，不代替verify）
bash backend/scripts/start-api.sh
bash backend/scripts/start-worker.sh
```

wrapper固定父POM、项目user/global settings与tools/repository缓存，隔离公司~/.m2/settings.xml；不要修改公司全局设置。项目JDK位于backend/tools/jdk/Contents/Home，外部JDK通过QUEST_JAVA_HOME指定。IDE导入器不保证执行wrapper，配置见API局部文档。当前无独立Java lint目标。

原生IDEA Run/Debug：ApiApplication，JDK27，工作目录为backend（只打开api-java时是$PROJECT_DIR$/..），参数`--spring.profiles.active=deployment --spring.flyway.enabled=false`。common不能运行，不在整个reactor直接执行spring-boot:run。

### Python

```bash
uv --directory backend/ai-python sync --group dev --frozen
uv --directory backend/ai-python run pytest -q
uv --directory backend/ai-python run pytest tests/test_extraction_graph.py -q
uv --directory backend/ai-python run pytest tests/test_service.py -k liveness -q
uv --directory backend/ai-python run ruff check --no-cache .
uv --directory backend/ai-python run ruff format --no-cache --check .
bash backend/ai-python/start.sh
```

本工程.env由启动脚本显式加载，Settings本身不自动读取.env。LangGraph抽取图由FastAPI lifespan编译：prepare→generate→validate，失败repair一次，最多两次模型调用；网络错误不自动重试。全程强制草稿，不自动发布。未实现完整聊天Agent、checkpoint持久化、人工中断恢复、OCR/RAG/Embedding；不使用Deep Agents。

### 前端

```bash
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend test
pnpm --dir frontend typecheck
pnpm --dir frontend build
pnpm --dir frontend --filter @quest/shared exec vitest run tests/sse.test.ts
pnpm --dir frontend dev:pc
pnpm --dir frontend dev:mobile
```

也可用frontend/start-pc.sh、start-mobile.sh和各工程.run配置。PC5173，移动5174/m/；各应用.env.local配置/gateway代理本机8080。dev监听0.0.0.0，局域网设备可通过前端代理访问后端；仅可信网络使用。Java/Python默认127.0.0.1，Python8000。正式同源配置VITE_API_BASE_URL=/，不要用开发代理路径。

### 本地部署/启动配置测试（不改NAS）

```bash
uv run --with python-dotenv --with pyyaml --with pglast python -m unittest discover -s scripts/tests -v
```

此测试只验证配置/SQL语法与工具替身，不代表容器、消息发收、真实对象上传或数据库升级通过。

## 跨服务契约和安全边界

终端所有业务请求最终必须经Java鉴权；Python/internal/*不能直接对浏览器开放。当前Java内部路径默认denyAll，Java→Python业务代理尚未实现。

Java业务响应为`{code,message,data,traceId}`，Actuator保留原生格式。Security过滤器和MVC异常处理各有错误输出路径；trace由TraceFilter贯穿响应/MDC。local profile关闭持久化，deployment readiness包含真实PG；local健康UP不能证明PG已接通。

Python内部HMAC固定覆盖timestamp、nonce、HTTP method、path、原始body SHA256，5分钟窗口。当前nonce仅进程内、单worker，不能直接扩成多worker/副本就称安全。AI草稿必须验证题型/答案/知识点，平台Admin与个人导入的scope将在用户模块设计时重构，不能信任前端自报身份。

SSE用fetch POST，不自动重试；done结束，error传播，无done的EOF视为截断。shared含UTF8分包/CRLF及Markdown XSS测试。修改协议同时考虑两个前端。

存储/接口时间UTC；页面与按日使用统计采用Asia/Shanghai业务日边界。Embedding表当前1024维；模型/维度变更需新索引与重建，不能混用不同模型向量。

## 本地配置、迁移与共享环境

Java从backend工作目录导入.env.api.properties / .env.worker.properties；Python用自身.env；两端前端用各自.env.local。真实配置已生成，本机不要再复制模板覆盖；日常启动不得依赖父目录统一.env加载，也不要恢复Python包装Java的旧架构。SSH、PG超级用户与SILO root凭据不属于应用运行配置，勿读取整份秘密文件回显。

群晖地址192.168.3.40：PG15432/quest、Redis16379、S3 19000、Console19001、MQ NameServer9876/Broker10911。账号运行使用quest_app，迁移使用quest_owner；Worker无owner凭据，Python无业务写权限。

Flyway V1–V3已在用户逐项明确授权后执行成功，并验证权限/重复迁移。**不编辑已执行旧迁移，不重建数据库；新增版本或共享资源写入另说明并获得授权**。SQL打包于API resources/db/migration，deploy/migrations仍有兼容副本，修改需同步检查。日常显式关闭Flyway；临时启动冒烟也禁用迁移。

RocketMQ已注册，但NAS磁盘90%告警用户要求暂不处理，不提高阈值或删除文件；未验收消息发收。SILO两桶私有、应用认证通过，但ListAllMyBuckets仍允许，完整最小权限与真实对象写入尚待验证。Redis/MQ/S3地址配置不等于Java业务适配器已经接通。

## 当前协作与下一阶段

用户已自行启动API与PC；本次只读确认readiness及PC代理健康均200。后续修改不可因此自行终止/重启这些进程。

下一阶段为用户模块，先细化Admin安全初始化/开户、登录/首次改密/禁用/会话失效与Admin/普通用户权限矩阵，再实现私有题库；以新产品计划为准，不沿用原Admin可看全库的设计。
