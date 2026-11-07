# 三个独立开发工程入口

目标：IDEA打开backend，PyCharm打开backend/ai-python，WebStorm打开frontend。各工程有自己的依赖根、配置、Shell入口和运行说明，日常启动不得向父目录读取凭据。Java不依赖Python/uv，前端不依赖根pnpm workspace。

用户已要求直接改造，不等待额外方案批准。只修改本地文件；现有运行进程不主动终止，远程中间件和数据库不修改。

- [x] Java：Spring Boot从backend工作目录导入被忽略的.env.properties；IDEA原生Application配置+Shell脚本，脚本构建后exec java；Flyway日常关闭；迁移SQL打包到classpath，去掉绝对父目录依赖。
- [x] Python：自身.env、uv.lock、start.sh、PyCharm配置；Uvicorn从本工程.env加载；LangSmith tracing默认关闭。
- [x] 前端：frontend成为pnpm workspace根，移动lockfile并保留根命令转发兼容；双端各自.env.local，两个Shell入口/WebStorm配置。
- [x] 用户明确授权后，允许字段仅在目标不存在时迁入三个配置文件，权限0600，不输出值；不含SSH/PG超级用户/MinIO root，Worker无owner密码。模板无密钥。旧根级dev命令仅兼容委托。
- [x] 测试：工具替身验证任意cwd/路径空格/退出码，前端独立lockfile/构建通过；Java独立工作目录备用端口健康200，33tests通过；Python90tests通过，24项配置测试通过。授权迁入配置后，Java独立工作目录连接NAS readiness200/UP、Python本工程.env健康200/alive，未迁移/修改远程数据，不终止既有服务。
- [x] 文档：分别写三工程IDE设置、首次依赖、启动/停止/热更新；根手册只做导航。
