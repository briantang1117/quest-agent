# IDEA：独立打开 Java 工程

## 打开与工具链

**当前用户选择只打开 `backend/api-java/`，无需重开整个backend。** API仍继承`../pom.xml`并依赖兄弟模块`../common`，不是完全独立的Maven工程。也可选择打开backend并导入全部Java模块，但不是本次修复的前提。

### 只打开api-java时（当前方式）

- Maven Home：`../tools/distributions/apache-maven-3.10.0`；User settings：`../.mvn/settings.xml`；独立本地仓库：`../tools/repository`。IDEA设置中使用对应绝对路径，不改公司`~/.m2/settings.xml`。
- Project SDK、Maven Importing的JDK for importer、Runner JRE均选择JDK27；仅设置Project SDK不会保证导入器已切换。2026-11-07日志曾显示导入器使用Java25，需另行核对。
- settings中的仓库变量由wrapper传入；Importer/Runner VM options保留`-Dquest.maven.repo=本机backend绝对路径/tools/repository`。当前没有`.mvn/maven.config`，不依赖该选项。
- API单模块导入不会自动构建common。首次或common源码变更后，在api-java终端执行：

  ```bash
  ../mvnw -B -ntp -pl api-java -am clean install
  ```

  wrapper固定选择父POM，`-am`构建依赖并install到同一项目仓库。随后在IDEA执行Reload All Maven Projects。另一种方式是在Maven窗口额外关联`../common/pom.xml`，仍可保持IDEA项目根为api-java。
- 若出现`com.quest:common:jar:0.1.0 was not found ... cached ... quest-public-central`，先检查同一仓库是否有common JAR/POM和quest-backend父POM。`-U`只重新查询远程，不会生成尚未安装的本项目模块。2026-11-07本机已完成上述install，修复前同参数单模块构建失败、修复后16项测试通过。
- backend下的`.run/`不会自动出现在此子工程；手工创建Application，main=`com.quest.api.ApiApplication`，classpath选API模块，JRE27，Working directory=`$PROJECT_DIR$/..`（backend），Program arguments=`--spring.profiles.active=deployment`。
- 不要在api-java复制或覆盖真实properties，已有`../.env.api.properties`由backend工作目录加载。Shell入口可使用`bash ../scripts/start-api.sh`。

**2026-11-07验证：** 全模块`../mvnw -B -ntp clean verify`共33项测试通过；API JAR在backend工作目录、备用端口18081、显式`--spring.flyway.enabled=false`下readiness200/UP、health200/UP、匿名业务401，临时进程已停止。IDEA Reload及原生Run/Debug仍待实际确认，不能用CLI结果代替。

下文路径和`$PROJECT_DIR$`默认按打开backend说明；只打开api-java时以本节的`../`路径及Working directory为准。

- Project SDK与语言级别：JDK27，不启用Preview。
- 本机JDK：`backend/tools/jdk/Contents/Home`（IDEA打开backend后显示为tools/jdk/Contents/Home）。其他机器安装JDK27并设置QUEST_JAVA_HOME。
- Maven优先使用本工程mvnw；IDEA内建Maven导入需要手动指定user settings=`.mvn/settings.xml`，并设置Maven importer/runner VM options `-Dquest.maven.repo=本工程绝对路径/tools/repository`；若安装级Maven有公司镜像也需选本工程Maven安装并检查effective-settings。
- 本工程Maven安装：`tools/distributions/apache-maven-3.10.0`。不要把全局公司settings改掉；命令行mvnw已显式隔离user/global settings。

## 本工程配置（首次一次）

复制两个模板：

```bash
cp .env.api.properties.example .env.api.properties
cp .env.worker.properties.example .env.worker.properties
```

填写配置，**不要覆盖已填写的文件**。这是Java Properties格式，不用shell source，不用uv/Python。密码不用引号；反斜杠必须转义成两个反斜杠。文件被忽略，禁止提交。

API读取`.env.api.properties`，Worker读取`.env.worker.properties`；只在工作目录backend查找，不读取父目录.env或deploy目录。通过Spring Boot配置导入，环境变量/命令参数可覆盖。API运行账号quest_app，Worker不得包含quest_owner/管理员/SSH密码。JWT与服务间密钥至少32字符；内部HMAC密钥与Python工程保持一致。

**2026-11-07已在用户明确授权后创建两份真实properties文件，权限0600；当前本机无需再复制模板。** API配置已通过备用端口真实启动，数据库readiness200/UP、Hikari连接成功且无迁移执行。Worker配置不含owner密码；未复制SSH/PG超级用户/MinIO root凭据，不打印任何密钥。后续改密码需分别更新各工程配置，不再自动向父目录读取。

## 方法一：Run Shell Script（最简单）

IDEA顶栏应显示已提交到本工程`.run/`的配置：

- **Quest API** → scripts/start-api.sh
- **Quest Worker** → scripts/start-worker.sh

需要启用IDEA **Shell Script**插件，Interpreter选`/bin/bash`。也可打开sh文件点绿色运行按钮。工作目录由脚本自己定位，路径含空格或从其他目录启动也可；不需要uv。

终端等效命令（当前目录backend）：

```bash
bash scripts/start-api.sh
bash scripts/start-worker.sh
```

每次先执行Maven package（跳过测试），再exec java -jar，因此重新Run会编译最新Java代码。完整测试另运行：

```bash
./mvnw -B -ntp clean verify
```

Shell入口不会提供Java断点调试；需要断点时用方法二。

## 方法二：原生 Java Run / Debug（推荐日常Java编码）

配置：**Quest API Native Debug**、**Quest Worker Native Debug**，类型Application，底层仍然是Spring Boot。

| 字段 | API | Worker |
|---|---|---|
| Main class | com.quest.api.ApiApplication | com.quest.worker.WorkerApplication |
| Module classpath | api-java | worker-java |
| JRE | Project SDK 27 | Project SDK 27 |
| Working directory | backend工程根（$PROJECT_DIR$） | 同左 |
| Program arguments | --spring.profiles.active=deployment | 同左 |

IDEA导入时模块名若不同，在配置中重新选择实际api-java/worker-java模块。直接点Run/Debug，IDEA先Make再启动main；不需要先打JAR、不经过Python，也不需要把密码塞进IDE配置。Spring Boot自动从上述工作目录导入本工程properties。

当前没有DevTools热重启；修改Java后重新Run/Debug。断点支持来自原生Application/Debug，不来自Shell脚本。

## 服务与停止

- API：http://127.0.0.1:8080/actuator/health
- 数据库就绪：http://127.0.0.1:8080/actuator/health/readiness
- Worker没有Web端口，Outbox当前禁用。
- 停止：IDEA红色Stop，或脚本终端Ctrl+C。端口被旧进程占用时先停止旧的本项目进程，不要批量杀所有java。
- 不要同时运行同一服务的Shell与Native配置，API会争抢8080。

## 迁移

NAS的Flyway V1–V3已执行成功，日常配置`SPRING_FLYWAY_ENABLED=false`。迁移SQL现随API打包在classpath:db/migration，Java工程不再需要上级deploy目录才能运行。仓库中deploy/migrations保留部署镜像配置兼容副本，修改必须保持一致，已用测试核验。

不提供自动新迁移入口；新增版本应单独审查授权，不能通过日常Run悄悄改NAS数据库。
