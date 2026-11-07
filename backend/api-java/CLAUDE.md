# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 工程范围与入口

沟通、文档和新增注释使用简体中文，代码标识符保留原有命名。

用户在 IDEA 中只打开 `backend/api-java/`，同时关联 `../common/pom.xml`，不要要求重开整个 backend 或擅自拆除父 POM/common 依赖。启动配置见 [IDEA启动配置.md](docs/IDEA启动配置.md)；排障背景与业务边界见 [API-Java交接.md](docs/API-Java交接.md)。旧文档中的待排查项可能已解决，先核对当前文件和用户确认。

API 属于父目录 `backend` 的 Maven 多模块工程：`../pom.xml` 聚合 common、api-java、worker-java。父 POM 提供版本与依赖管理；common 提供共享领域契约和统一响应；API 是 HTTP 业务入口；Worker 是独立进程。Python AI 服务、前端各自开发，本目录不是它们的统一启动入口。

## 工具链与命令

基线以 POM 为准：Java27（非 Preview）、Maven3.10.0、Spring Boot4.1.2、MyBatis-Plus3.5.18 的 `mybatis-plus-spring-boot4-starter`。使用 Jackson3 的 `tools.jackson.*`，不要照搬 Boot3/Jackson2 的包名与配置。

以下命令均从 **api-java 目录** 执行：

```bash
# 核对项目工具链
../mvnw -v

# API及其依赖：构建、测试、安装父POM和common到项目仓库
../mvnw -B -ntp -pl api-java -am clean install

# 全部Java模块回归，包含Worker
../mvnw -B -ntp clean verify

# 仅验证API及其依赖
../mvnw -B -ntp -pl api-java -am verify

# 单个API测试类；common没有该测试，需允许其他模块无匹配测试
../mvnw -B -ntp -pl api-java -am -Dtest=ApiContractTest -Dsurefire.failIfNoSpecifiedTests=false test

# 单个测试方法
../mvnw -B -ntp -pl api-java -am '-Dtest=ApiContractTest#anonymousBusinessRequestIsDeniedWithEnvelope' -Dsurefire.failIfNoSpecifiedTests=false test

# 从backend工作目录启动API，显式禁用迁移；此脚本打包时跳过测试
SPRING_FLYWAY_ENABLED=false bash ../scripts/start-api.sh
```

`../mvnw` 是自定义项目 wrapper，不是简单的系统 mvn 别名：始终选 `backend/pom.xml`，显式指定项目 user/global settings，并注入 `quest.maven.repo` 和项目 JDK。因此即使从子目录调用，不加 `-pl` 仍会构建整个 Java reactor；不要用 `-f` 随意覆盖它来改变构建范围。外部 JDK可通过 `QUEST_JAVA_HOME` 指向27。

仓库与工具在 `../tools/`；缓存是 `../tools/repository`，user/global settings 分别为 `../.mvn/settings.xml`、`../.mvn/global-settings.xml`。不要修改公司 `~/.m2/settings.xml`、系统默认 Java 或切回公司私服。IDE importer 不保证执行 wrapper，需单独核对 JDK、settings、本地仓库与 `-Dquest.maven.repo`，具体路径见启动说明。

当前 POM 未配置独立 lint/格式化目标；不要虚构 lint 命令。Surefire 报告在各模块 `target/surefire-reports`；测试默认不要求真实数据库，API契约测试显式使用 local profile。33项测试是初始化时的基准数量，不是后续新增测试的固定上限。

## 运行与配置边界

- 原生 Application：主类 `com.quest.api.ApiApplication`，classpath选API模块，JDK27，**工作目录为backend，即 `$PROJECT_DIR$/..`**，程序实参 `--spring.profiles.active=deployment --spring.flyway.enabled=false`。
- `application.yml` 从工作目录导入 `optional:file:./.env.api.properties`，不是从Java源码目录或仓库根加载。已有真实properties，不复制模板覆盖，不回显文件内容。
- 默认 local profile 排除数据库、Flyway、MyBatis自动配置，持久化 disabled，不提供假内存CRUD。deployment启用PostgreSQL/Hikari，readiness含数据库。不要将local健康UP解释为数据库已连通。
- 应用本机运行、中间件在NAS；不擅自部署应用到NAS。运行账号为quest_app，迁移使用独立账号。已有Flyway V1–V3，不重新初始化数据库；新增迁移、共享数据库写入和远程资源修改需要明确授权。
- 迁移SQL随API打包在 `src/main/resources/db/migration`，另有 `../../deploy/migrations` 兼容副本，改动需要同步核对。deployment的Flyway开启默认值可能为true，日常运行保留显式关闭参数。
- 启动后检查 `/actuator/health/readiness` 返回200/UP；匿名 `/api/v1/system/info` 返回401正常。临时冒烟使用备用端口、禁用迁移，并只停止自己启动的进程。

## 架构与契约

API启动入口为 `ApiApplication`。当前重点是安全、响应协议与工程底座，不能把它当作已实现完整刷题业务的平台。

请求先经过高优先级 `TraceFilter`，校验/生成 `X-Trace-Id`，写入请求属性、响应头及MDC，并在请求结束恢复MDC。业务响应使用common的 `ApiResponse<T>`：`{code,message,data,traceId}`；Actuator保留原生健康协议。`ApiExceptionHandler`统一处理MVC异常，不向客户端暴露内部异常；Spring Security在过滤器链内单独写相同响应格式，修改错误协议时要同时考虑两条路径。

`SecurityConfiguration`是无状态JWT Resource Server：仅GET健康端点公开，业务接口默认认证，内部路径显式denyAll，启用方法级权限。HS256 secret至少32个UTF-8字节，验证issuer、audience、sub、exp。deployment缺密钥启动失败；local无密钥时业务仍锁定；禁止local与deployment/production混用。当前不提供默认管理员、登录/发Token、完整RBAC或已完成的内部HMAC链路，不绕过安全配置来“跑通”接口。

API的 `common` 包是HTTP适配层的异常与trace处理，不等同于兄弟Maven模块 `../common`。后者提供可复用的 `ApiResponse`、不可变题目契约及选择题判分，不是可启动的Spring Boot应用；不要在整个reactor上直接执行 `spring-boot:run`。

测试主要分为：common领域测试、API的MockMvc契约测试 `ApiContractTest`、安全配置测试 `SecurityConfigurationTest`。API契约覆盖鉴权、统一响应、trace传播、异常隐藏；安全失败场景测试可能主动打印ERROR日志，以Surefire断言与构建退出码判断，不凭日志级别认定构建失败。

尚未完成真实登录/注册、题库CRUD、练习/模考及可靠MQ/S3/AI业务链路。后续顺序和约束参考交接文档，不提供伪造题库、统计或AI回答。
