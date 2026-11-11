# 知序 · AI 学习陪练

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

知序目前是一套以 **PC 对话陪练** 为核心的本地学习系统：管理员维护公共真题和考纲，普通用户通过自然语言提问、练习客观题、获取讲解，并可把会话整理为 Markdown 资料。目标科目由代码定义为事业编 A 类职测、上海市考行测；题型为单选、多选。

已实现公共题库文本/PDF导入审核、账号权限、个人陪练流式交互、考纲引用与向量流水线、搜索/网页抓取工具、学习资料异步任务及审计。移动端仍是诊断和会话骨架，私人多题库、类似题、自适应选题、完整模考及商业化尚未接业务链路。

应用在本机运行：Java API 8080、Python 8000、PC 5173、移动骨架 5174。NAS 中间件配置与应用运行分离；根 Compose 是可选部署样例，不是日常启动入口。

| 层         | 当前技术                                                                                                     |
| ---------- | ------------------------------------------------------------------------------------------------------------ |
| Java       | Java 27、Spring Boot 4.1.2、Security/JWT、JdbcTemplate事务、Flyway、Maven wrapper；依赖含MyBatis-Plus 3.5.18 |
| Python     | Python >=3.11、FastAPI、Pydantic、HTTPX、LangGraph、pypdf、uv                                                |
| PC / 移动  | Vue 3、TypeScript、Router、Pinia、Arco / Vant、Vite、pnpm workspace                                          |
| 存储与队列 | PostgreSQL16+pgvector；学习资料专用RocketMQ5.3.1；Redis/S3服务有部署配置，未接完整业务适配                   |
| 模型       | OpenAI-compatible文本接口；OCR/Embedding/Search/Fetch各自独立适配和凭据                                      |

日常启动（在仓库根目录，沿用已配置的各工程环境）：

```bash
bash backend/scripts/start-api.sh
bash backend/ai-python/start.sh
bash frontend/start-pc.sh
# 学习资料MQ流程按需运行Worker
bash backend/scripts/start-worker.sh
```

不要复制模板覆盖已有环境，也不要把脚本打包成功当作测试通过。工具调用默认开启；学习资料 API 和 MQ Worker 各有默认关闭的独立开关，详见专项文档。

- [文档导航](docs/README.md)：各主题唯一入口。
- [项目现状](docs/项目现状.md)：做了什么、证据和缺口。
- [架构说明](docs/架构说明.md)：数据流、存储与权限。
- [本地开发启动](docs/本地开发启动.md)：配置、命令、验证。
- [下一阶段开发路线](docs/下一阶段开发路线.md)：建议优先级。
- [接口目录](docs/api.md)：从Controller与Python路由整理。

本次文档整理没有连接NAS、执行数据库迁移或调用真实模型；本地测试通过不代表共享环境已升级。数据库脚本已提供V1–V13，实际执行版本应查询运行环境的Flyway历史。
