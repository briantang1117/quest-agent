# Java 业务底座

**会话交接：[API-Java交接.md](api-java/API-Java交接.md)**（IDEA Maven排查、当前已验证状态、后续业务边界；不含密钥）。

**日常开发请用 [IDEA独立启动说明](IDEA开发.md)**：IDEA直接打开backend，Java使用自身.env.api.properties/.env.worker.properties与Shell/原生Run/Debug，不依赖根目录Python脚本。数据库迁移默认关闭。下文为构建与技术栈记录。

当前工具链（2026-11-07 查询官方稳定版本）：

| 组件 | 版本 |
|---|---|
| Maven | **3.10.0**，正式稳定版，不使用 Maven4 RC |
| Java | **Temurin 27.0.1.0.2+1**，Java27 正式版，不启用 preview |
| Spring Boot | **4.1.2**，官方支持 Java17–27 |
| MyBatis-Plus | **3.5.18**，使用 boot4 starter |

原方案的 Java17 / Spring Boot3 基线已按用户要求升级；原始方案保留作为需求来源，实际构建版本以 POM 为准。

## 一条命令构建

在项目根目录：

```bash
./backend/mvnw -B -ntp clean verify
```

当前本机已经安装项目专属 JDK，wrapper 自动选择，不受 shell 中公司项目遗留 `JAVA_HOME` 影响。其他机器可以指定：

```bash
QUEST_JAVA_HOME=/path/to/jdk-27 ./backend/mvnw -B -ntp clean verify
```

没有项目本地 JDK 时也接受外部 `JAVA_HOME`，但必须是27，否则明确报错。不修改系统默认 Java，也不改公司其他项目。

## Maven 完全隔离

- `backend/.mvn/settings.xml`：公共 HTTPS Maven Central，mirrorOf=`*`，无私有仓库和凭据。
- `backend/.mvn/global-settings.xml`：空的安装级配置，避免合并公司镜像。
- wrapper 始终显式传 `--settings` 与 `--global-settings`，**不读取/修改 `~/.m2/settings.xml`**。
- 依赖缓存：`backend/tools/repository`，不读写公司 `~/.m2/repository`。
- Maven 工具：`backend/tools/distributions/apache-maven-3.10.0`，ZIP 下载后校验固定 SHA512。
- 本机 JDK：`backend/tools/jdks/temurin-27.0.1`，`backend/tools/jdk` 为项目内软链接，下载包已核验官方 SHA256。
- `tools/` 整体被忽略，不提交二进制或依赖缓存。

请使用本项目 wrapper，而不是直接运行系统 `mvn`；IDE 导入项目也应指定上述 user settings、Maven版本、项目缓存及 JDK27。IDE 直接使用系统 Maven 仍可能加载公司配置。

验证最终配置：

```bash
./backend/mvnw -B -ntp help:effective-settings -Doutput=/absolute/path/to/effective-settings.xml
```

应只有 `https://repo.maven.apache.org/maven2`，本地仓库为项目 tools/repository，不出现公司 Nexus。

## JDK 来源与复现

本机 macOS arm64 下载地址：

`https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.0.2%2B1/OpenJDK27U-jdk_aarch64_mac_hotspot_27.0.1.0.2_1.tar.gz`

SHA256：`c2619cb73ff1d296edd0644d92ae8ed94039bc1bdd43282398835b7f88134fb6`。

其他平台从 Adoptium 官方选择 Java27 正式版，安装到项目工具目录或通过 `QUEST_JAVA_HOME` 指定。不要把 macOS arm64 包用于 Linux/x64。

官方版本依据：
- https://maven.apache.org/download.cgi
- https://api.adoptium.net/v3/info/available_releases
- https://docs.spring.io/spring-boot/4.1/system-requirements.html

## 验证结果

实际运行 `./backend/mvnw -B -ntp clean verify`：

```text
common:      Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
api-java:    Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
worker-java: Tests run:  2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

测试期间 Mockito/Byte Buddy 在Java27上产生动态加载Java agent的未来兼容性警告；当前测试全部通过，未屏蔽警告。后续可采用Mockito启动期agent方式消除动态加载依赖。

生成 common、API、Worker JAR。真实启动 API JAR 冒烟：`/actuator/health` 返回200/UP，匿名访问 `/api/v1/system/info` 返回401与统一 envelope，临时进程已停止。尚未连接真实 PostgreSQL、RocketMQ、NAS，也没有构建/运行 Docker 镜像。

升级时实际复现并修复：Boot4 自动配置/MockMvc 包迁移、Jackson3 命名空间及已移除的序列化配置、Flyway 独立 starter。未跳过测试，未用旧离线测试代替全量验证。

## 配置与安全

- local 关闭持久化，不提供假内存题库；默认不配置 JWT 密钥时业务接口全部锁定。
- deployment 使用 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`，运行账号 `quest_app`。
- API 用 `SPRING_FLYWAY_USER/PASSWORD` 迁移，路径默认 `filesystem:/opt/quest/migrations`；Worker关闭Flyway，不持有owner密钥。
- `JWT_SECRET` 至少32个UTF-8字节，HS256，issuer=`quest-api`、audience=`quest-web`；没有默认管理员或 token 颁发接口。
- Java→Python、Refresh Token/RBAC、业务 CRUD、审计、MinIO和MQ可靠链路仍是后续开发。

## 容器

Dockerfile 使用 `eclipse-temurin:27-jre`，构建上下文 `backend/`，复制本地 verify 生成的 JAR，非 root 运行。Java27基础镜像尚未实际拉取验证；容器编排和真实数据库迁移仍待集成验收。
