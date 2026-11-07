# API Java：IDEA 开发与启动配置

适用方式：IDEA 只打开 `backend/api-java/`，同时关联 `../common/pom.xml`。无需重开整个 backend，不将 API 改造成独立 Maven 工程。

## 1. 当前初始化状态

2026-11-07：用户已确认 Maven 依赖不再标红；当前 `.idea/misc.xml` 已关联 `api-java/pom.xml` 和 `common/pom.xml`。

此前已完成父 POM/common/API 的 install、全模块33项测试，以及关闭 Flyway 的 API JAR 备用端口冒烟。**IDEA 原生 Run/Debug 的启动结果尚未确认**，以本文末尾的检查为准。

## 2. Maven 配置

打开 **设置 → 构建、执行、部署 → 构建工具 → Maven**。

以下为当前本机路径；其他机器按 backend 实际位置调整，不修改公司全局配置。

| 配置项 | 当前值 |
|---|---|
| Maven 主路径 | `/Users/brian/Desktop/quest-agent/backend/tools/distributions/apache-maven-3.10.0` |
| 用户设置文件 | `/Users/brian/Desktop/quest-agent/backend/.mvn/settings.xml` |
| 本地仓库 | `/Users/brian/Desktop/quest-agent/backend/tools/repository` |
| 脱机工作 | 不勾选 |
| 使用 `.mvn/maven.config` 中的设置 | 不勾选，当前无该文件 |

用户设置文件和本地仓库如有 **重写 / Override**，需勾选。不要选择公司的 `~/.m2/settings.xml` 或 `~/.m2/repository`。

**Maven → 正在导入 / Importing：**

- 导入程序的 JDK：使用项目 SDK，Temurin 27，不启用 Preview。
- 导入程序的虚拟机选项：保留其他需要的参数，加入：

  ```text
  -Dquest.maven.repo=/Users/brian/Desktop/quest-agent/backend/tools/repository
  ```

**Maven → 运行程序 / Runner：**

- JRE：项目 JDK27。
- VM Options：同样加入上述 `-Dquest.maven.repo=...`。

### common 模块关联

右侧 **Maven 工具窗口 → + / 添加 Maven 项目**，选择：

```text
/Users/brian/Desktop/quest-agent/backend/common/pom.xml
```

保存设置并 **重新加载所有 Maven 项目**。窗口中应包含 API 和 common；不要通过手工添加 JAR、`systemPath` 或删除依赖来绕过解析。

首次安装依赖，或只导入 API、需要更新本地 common 时，从 api-java 目录执行：

```bash
../mvnw -B -ntp -pl api-java -am clean install
```

wrapper 固定使用父工程 POM和项目独立仓库。`-U` 只能重新请求远程仓库，不能生成未安装的本项目 common。

## 3. 原生 Run / Debug 配置

右上角 **编辑配置 → + → 应用程序 / Application**：

| 配置项 | 填写内容 |
|---|---|
| 名称 | `Quest API` |
| 主类 | `com.quest.api.ApiApplication` |
| 模块 classpath | 当前工程中 `api-java` 对应模块，不是 common |
| JRE | 项目 JDK27 |
| 工作目录 | `$PROJECT_DIR$/..`，即 `/Users/brian/Desktop/quest-agent/backend` |
| 程序实参 | `--spring.profiles.active=deployment --spring.flyway.enabled=false` |
| 启动前 | Build / Make |

如果主类、工作目录或程序实参字段未显示，通过 **修改选项 / Modify options** 显示对应字段。

**工作目录必须是 backend，而不是 api-java。** Spring Boot 从工作目录导入已有 `.env.api.properties`。不要复制模板覆盖已填写配置，不把密钥粘贴到共享运行配置中。

日常启动显式禁用 Flyway；已有数据库不需要重新初始化。新增迁移需单独审查和授权。

点击 **运行 ▶** 启动，或 **调试 🐞** 使用 Java 断点。修改代码后重新运行；当前未配置 DevTools 热重启。

### 终端替代入口

在 api-java 目录执行：

```bash
SPRING_FLYWAY_ENABLED=false bash ../scripts/start-api.sh
```

脚本自动定位 backend，先打包再运行 JAR，不经过 Python/uv；其构建跳过测试，不能代替完整测试验收。终端入口和原生运行配置二选一，不要同时抢占8080。

## 4. 启动验证与停止

默认端口8080；本地环境配置或运行参数可覆盖端口。

```bash
curl -i http://127.0.0.1:8080/actuator/health
curl -i http://127.0.0.1:8080/actuator/health/readiness
curl -i http://127.0.0.1:8080/api/v1/system/info
```

预期：

- health：HTTP 200，`status=UP`。
- deployment 下 readiness：HTTP 200，`status=UP`，含数据库连接检查。
- 匿名业务接口：HTTP 401，正常鉴权行为；目前没有默认管理员或登录发 Token 接口。

只看到 `Started ApiApplication` 或 POM 不再标红，不等于数据库就绪。默认 local profile 关闭持久化，其 UP 也不能替代 deployment 的数据库验证。

停止：IDEA 红色 Stop，或脚本终端 Ctrl+C。端口冲突时确认占用者，不批量终止 Java/IDE 进程。

## 5. 相关说明

- [CLAUDE.md](../CLAUDE.md)：根目录的工程约束、架构和验证命令。
- [API-Java交接.md](API-Java交接.md)：归档的本轮根因、验证证据和后续业务边界。
- [父工程 IDEA 开发说明](../../IDEA开发.md)：其他 Java 模块及 backend 打开方式。

本文中的终端命令均从 `api-java/` 执行，不是从 `docs/` 执行。
