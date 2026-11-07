# AI 事业编 / 考公刷题平台 —— 第一期技术方案（v1.4）

> 版本：v1.4
> 范围：客观题（单选题、多选题）+ Agent 题库导入 + 真题试卷 + 统计与智能分析
> 技术栈：Java（业务）+ Python（AI）+ Vue3 + TS + **Arco Design Vue（PC）/ Vant 4（移动）** + **PostgreSQL 16 + pgvector + RocketMQ 5.x + 局域网 NAS（MinIO，方案 A）**
> 部署形态：**纯局域网**；PC 端二合一；移动端仅 Agent

## v1.4 变更说明

| 项 | v1.3 | v1.4（当前） | 说明 |
|---|---|---|---|
| PC 组件库 | Element Plus | **Arco Design Vue（最新 2.x）** | 表单/表格/布局更贴合中后台 |
| 移动组件库 | Vant 4 | **Vant 4**（不变） | Arco 无官方 Vue 移动端库 |
| 对话组件 | 未明确 | **手搓**，不引第三方聊天 SDK | SSE + 消息块 + 题目卡片全部自研 |
| 后端 | Java + Python | 不变 | 已确认 |
| 前提 | 假设 | **已确认**：服务器可出网调 LLM；移动端只做 Agent；终端与 NAS 同网段 | 风险项关闭 |

**本轮结论**：

- **数据库仍选 PostgreSQL**：JSONB + pgvector + 物化视图，题库、向量、统计一库搞定。
- **NAS 用方案 A**：MinIO 直接跑在 NAS 的 Docker 里，应用只走 S3 接口，不挂 NFS。
- **PC 二合一**：一个 Vue 应用，Arco Design Vue，按路由分 `/practice`、`/admin`、`/admin/agent`。
- **移动端只做 Agent**：Vant 4，登录后直接进对话；刷题通过对话完成，没有管理、没有表单页。
- **对话组件全部自研**：PC 与移动共用 `shared` 里的 SSE 解析、消息模型与 Markdown 渲染，UI 各自实现。
- **局域网限制**：不能做微信小程序（需公网 HTTPS 域名），移动端用 H5/PWA。

---

## 1. 项目背景与目标

### 1.1 产品定位

面向**事业单位招聘考试 / 公务员考试**备考人群的 AI 刷题平台。第一期聚焦**客观题**（单选、多选），核心差异点不在"题库大"，而在：

1. **题库可自建、可 AI 生成**：用户/运营把零散真题（文本、截图、PDF）丢给 Agent，自动结构化成标准题目，缺答案的由 AI 补答案和解析。
2. **题库是结构化本体**：不是一堆富文本，而是可检索、可组卷、可统计的结构化数据。
3. **数据闭环**：做题数据 → 知识点掌握度 → 智能推荐 → 提分。

### 1.2 第一期范围（MVP）

| 模块 | 第一期做 | 第一期不做（预留） |
|---|---|---|
| 客户端 | PC 二合一（刷题 + 管理 + Agent 页）；移动端仅 Agent | 小程序、原生 App、移动端传统刷题页 |
| 题型 | 单选、多选 | 判断、不定项、填空、简答、申论 |
| 题库 | 结构化题库、知识点树、来源标注、版本快照 | 多租户题库市场 |
| 导入 | 文本粘贴、图片 OCR、Word/PDF、Agent 对话导入 | 视频/音频题目 |
| AI | 切题抽取、答案补全、解析生成、去重、相似题检索 | 主观题批改、AI 出题（可做小范围） |
| 刷题 | 章节练习、随机练习、错题本、收藏 | 组队 PK、排行榜赛事 |
| 试卷 | 真题试卷管理、整卷模考、自动判分 | 智能组卷（第二期重点） |
| 统计 | 运营看板、学员学情、题目质量分析 | 预测分模型（先用规则） |
| 后台 | 题库编辑器、审核流、导入任务、用户/权限 | 复杂 CMS、营销系统 |
| 商业化 | 预留会员/订单表结构，不做支付 | 支付、分销、课程 |

### 1.3 核心用户与场景

| 角色 | 场景 |
|---|---|
| 学员 | PC 刷题/模考看解析；手机通过 Agent 对话刷题、答疑、看薄弱点 |
| 运营/教研 | 后台录题、批量导入真题、审核 AI 生成内容、组卷 |
| 管理员 | 看数据看板、管用户权限、管题库质量 |
| Agent（系统角色） | 对话式导入、答疑、生成解析、质检建议 |

### 1.4 关键设计原则

1. **结构化数据是唯一事实源（Single Source of Truth）**，Markdown 与向量都是派生索引。
2. **Java 单写业务库**：Python 只写"索引表"（如 `question_embedding`）与草稿区，业务表只读。
3. **AI 产出必须可审核**：AI 生成的答案/解析默认 `draft`，低置信度强制人工复核。
4. **后台管理与 Agent 对话分离**：两套入口、两套权限、两套 Prompt 与工具集，互不污染。
5. **一切可追溯**：题目有版本，AI 有任务记录，人工修改有审计日志。
6. **异步链路可重放**：消息可重试、可死信、可人工重投。

### 1.5 技术练手目标（本项目的额外价值）

| 能力 | 落点 |
|---|---|
| 索引设计与慢查询优化 | 10 万级题库列表、多条件筛选、GIN/HNSW 索引 |
| 事务与锁 | 题目发布、试卷组卷、乐观锁、死锁重试 |
| JSONB 建模 | 题目本体、快照、消息 payload |
| 消息可靠性 | 本地消息表、投递重试、消费幂等 |
| 顺序消息 | 同一题目的索引同步不能乱序 |
| 延迟消息 | 错题复习提醒、模考超时 |
| 削峰填谷 | 答题事件异步聚合，保护 PostgreSQL |
| 文件存储 | NAS 挂载、S3 网关、预签名 URL |
| 可观测 | 消息堆积、消费 RT、死信告警、磁盘容量 |

---

## 2. 总体架构

### 2.1 架构图

```mermaid
flowchart TB
    subgraph Client["客户端层（局域网）"]
        PC["PC Web 二合一<br/>Vue3 + TS + Arco Design<br/>刷题 / 管理 / Agent 页"]
        MB["移动端 H5<br/>Vue3 + Vant<br/>仅 Agent 对话"]
    end

    NG["Nginx<br/>静态资源 / 反代 / SSE 透传"]

    subgraph JavaApp["Java 业务域 · Spring Boot 3"]
        AUTH["认证授权<br/>JWT + RBAC"]
        QB["题库服务"]
        PAPER["试卷 / 模考"]
        PRACTICE["练习 / 判题"]
        STAT["统计服务"]
        IMP["导入编排"]
        MQC["MQ 消费者组<br/>索引 / 统计 / 导入 / 通知"]
    end

    subgraph MQ["消息层"]
        RMQ["RocketMQ 5.x<br/>NameServer + Broker"]
    end

    subgraph PyApp["Python AI 域 · FastAPI 无状态"]
        AGW["Agent Gateway"]
        OCR["OCR & 版面解析"]
        EXT["结构化抽取"]
        GEN["答案 & 解析生成"]
        RAG["混合检索 RAG"]
        IDX["向量化 / MD 导出"]
    end

    subgraph Data["数据层"]
        PG[("PostgreSQL 16<br/>+ pgvector")]
        RD[("Redis 7")]
        NAS[("局域网 NAS<br/>MinIO · S3")]
    end

    LLM["LLM / Embedding / OCR 服务"]

    PC --> NG
    MB --> NG
    NG --> AUTH
    AUTH --> QB & PAPER & PRACTICE & STAT & IMP

    QB --> PG
    PAPER --> PG
    PRACTICE --> PG
    PRACTICE --> RD
    STAT --> PG
    IMP --> PG
    QB --> NAS
    IMP --> NAS

    PG -. "Outbox Relay<br/>worker-java 定时扫描" .-> RMQ
    RMQ --> MQC
    MQC -->|"HTTP"| IDX
    MQC -->|"HTTP"| AGW
    MQC -->|"HTTP"| OCR

    AGW --> EXT & GEN & RAG
    OCR --> EXT
    EXT --> GEN
    GEN --> LLM
    RAG --> PG
    IDX --> PG
    IDX --> NAS

    PyApp -. "内部 API：只写草稿/索引" .-> JavaApp
```

### 2.2 服务划分

| 服务 | 语言 | 职责 | 部署 |
|---|---|---|---|
| `web-pc` | Vue3 + TS + Arco Design Vue | PC 二合一：刷题 + 管理 + Agent 页 | 静态资源 |
| `web-mobile` | Vue3 + TS + Vant 4 | 移动端：仅 Agent 对话 | 静态资源 |
| `api-java` | Java 17 / Spring Boot 3 | 业务主服务：题库、试卷、练习、统计、权限 | 容器 |
| `worker-java` | Java | Outbox Relay、定时聚合、延迟补偿 | 容器 |
| `ai-service` | Python 3.11 / FastAPI | Agent、OCR、抽取、生成、检索（无状态） | 容器 |
| `postgres` | — | 主库 + pgvector | 容器 / 独立机 |
| `rocketmq` | — | NameServer + Broker + Dashboard | 容器 / 独立机 |
| `redis` | — | 缓存、会话、限流、实时计数 | 容器 |
| `nas` | — | 文件存储（MinIO on NAS，方案 A） | 局域网 NAS |
| `nginx` | — | 静态资源、API 反代、SSE | 容器 / 宿主机 |

### 2.3 技术选型

| 层次 | 选型 | 理由 |
|---|---|---|
| 前端框架 | Vue 3 + TypeScript + Vite | 生态成熟、团队熟悉 |
| UI（PC） | **Arco Design Vue 2.x（最新）** | 中后台表格/表单/布局完整，TypeScript 友好 |
| UI（移动） | **Vant 4** | 触控组件成熟；Arco 无官方 Vue 移动端库 |
| 按需引入 | `unplugin-vue-components` + `ArcoResolver` | 减小包体，避免全量注册 |
| 图表 | ECharts 5 + `vue-echarts` | Arco 不含图表，看板/学情趋势用 |
| 对话组件 | **自研**（SSE + 消息块 + 卡片） | 不引第三方聊天 SDK，可控、可定制 |
| 部署形态 | 纯局域网（内网 DNS + HTTP） | 无公网入口、无备案；移动端只能 H5/PWA |
| 状态管理 | Pinia | 官方推荐 |
| 请求层 | Axios + 统一拦截器 | Token 刷新、错误码统一 |
| 流式对话 | SSE（`text/event-stream`） | 比 WebSocket 简单，穿透性好 |
| Java 框架 | Spring Boot 3.x + Spring Security | 标准企业栈 |
| ORM | MyBatis-Plus + JSONB TypeHandler | 复杂查询可控，JSON 映射方便 |
| 数据库 | **PostgreSQL 16 + pgvector 0.7+** | JSONB / 向量 / 全文 / 物化视图一库搞定 |
| 消息队列 | **RocketMQ 5.3.x** | 顺序/延迟/堆积能力，适合练后端 |
| 对象存储 | **NAS + MinIO**（S3 兼容） | 无云 OSS，NAS 提供容量，MinIO 提供接口 |
| 缓存 | Redis 7 | 热点题目、会话、限流、计数 |
| AI 框架 | FastAPI + LangGraph（或自研编排） | 工具调用、多步流程清晰 |
| LLM | OpenAI 兼容接口，抽象 Provider | 可切 DeepSeek / Qwen / GPT / Claude |
| OCR | PaddleOCR（本地）+ 云 OCR 兜底 | 成本与准确率平衡 |
| Embedding | bge-m3（1024 维）或云 embedding | 中文语义检索效果好、可私有化 |
| 可观测 | Prometheus + Grafana + Loki + Sentry | 指标/日志/异常 |
| RocketMQ 客户端 | `rocketmq-spring-boot-starter` 2.3.x（remoting） | 与 5.x Broker 兼容，资料多 |

### 2.4 为什么 Java + Python 双栈

- **Java 负责"确定性"**：权限、事务、题库 CRUD、判分、组卷、统计口径。这些要强一致、可审计。
- **Python 负责"不确定性"**：OCR、LLM 抽取、生成、检索、Agent 编排。模型迭代快、生态在 Python。
- **边界清晰**：Java 是业务库唯一写入口；Python 通过内部 API 提交草稿，或由 Java 消费者调用后回传结果。

### 2.5 为什么消费端全部放 Java

| 方案 | 问题 |
|---|---|
| Python 直接消费 RocketMQ | `rocketmq-client-python` 维护弱、依赖 C++ SDK、容器镜像重、问题难排查 |
| Java 消费 + HTTP 调 Python | 消费逻辑集中在 Java，事务/幂等/重试/监控统一；Python 可水平扩容 |

因此：

- **RocketMQ 生产与消费都在 Java**。
- Python 只暴露 `/internal/*` HTTP 接口，由 Java 消费者调用。
- Python 数据库账号只读业务表，只写索引表（`question_embedding`、`kb_doc`）。
- 长耗时任务（整卷 OCR）用大超时 + 幂等键，必要时演进为"Python 异步 Job + Java 轮询"。

### 2.6 调用关系约定

| 场景 | 路径 | 同步/异步 |
|---|---|---|
| 学员答题判分 | PC / 移动 → Java | 同步 |
| 学员问 AI 答疑 | PC / 移动 → Java（鉴权/配额）→ Python SSE | 流式 |
| 后台单题 AI 补解析 | Admin → Java → Python | 同步（可轮询） |
| 批量导入 1000 题 | Admin → Java 建批次 → RocketMQ → Java 消费者 → Python | 异步 |
| 题目发布后建索引 | Java 事务 + Outbox → RocketMQ → Python | 异步、顺序 |
| 答题统计 | Java 写库 + 发事件 → RocketMQ → 聚合消费者 | 异步 |
| 图片上传 | 浏览器 → MinIO 预签名直传 | 同步 |
| Python 写题目 | Python → Java 内部 API（HMAC 签名） | 同步 |

> 内部 API 使用独立 service token + 签名，且只允许写"草稿区"接口。

### 2.7 客户端形态（PC 二合一 + 移动端仅 Agent）

| 端 | 技术 | 功能范围 | 访问方式 |
|---|---|---|---|
| PC Web | Vue3 + TS + Arco Design Vue | 刷题、错题、模考、管理后台、Agent 页 | `http://quest.lan` |
| 移动 H5 | Vue3 + TS + Vant 4 | 仅 Agent 对话（对话式刷题/答疑/学情） | `http://quest.lan/m` |

**PC 二合一**

- 单应用、单登录，路由级隔离：
  - `/practice/*`：学员刷题（默认首页）
  - `/admin/*`：管理后台
  - `/admin/agent`：题库 Agent 页
- 路由守卫按角色放行；无权限直接 403，不渲染菜单。
- 两套 Layout（PracticeLayout / AdminLayout），共用登录态、请求层、题目渲染组件。
- 路由懒加载，管理端代码不打进刷题首屏。

**移动端仅 Agent**

- 路由只有：`/login`、`/sessions`、`/chat/:id`。
- 没有题库列表、没有后台、没有 CRUD 表单。
- 刷题通过 Agent 工具完成：抽题 → 卡片作答 → 判分 → 解析 → 下一题。
- H5/PWA，局域网 Wi-Fi 访问；不做小程序（公网域名限制）。

**边界**

- 两端共用 `shared` 包：TS 类型、API client、Markdown/KaTeX 渲染、SSE 解析。
- UI 组件不强行共用：Arco Design Vue 与 Vant 4 设计语言差异大，共用逻辑不共用皮肤。
- 对话组件自研：PC 用 Arco 的输入框/弹层/上传，移动用 Vant 的 Field/Uploader，但消息模型、SSE 解析、Markdown 渲染放在 `shared`。

---

## 3. 题库本体设计（核心）

### 3.1 设计思路

题目 = **结构化的题干 + 选项 + 答案 + 解析 + 元数据**。文本统一用 Markdown 存储（支持图片、公式、表格），但**字段级结构化**，保证：

- 可判分（答案与选项 key 对应）
- 可检索（知识点、来源、难度、关键词）
- 可组卷（按知识点/难度/题型筛选）
- 可统计（题目维度、知识点维度）
- 可版本化（真题卷引用历史版本）

### 3.2 题目 JSON 本体

```json
{
  "schema_version": "1.0",
  "type": "single_choice",
  "stem_md": "根据《民法典》，下列哪一情形构成无因管理？",
  "stem_images": [],
  "options": [
    { "key": "A", "text_md": "甲替邻居修缮被台风掀翻的屋顶", "image": null },
    { "key": "B", "text_md": "乙主动为朋友偿还赌债", "image": null },
    { "key": "C", "text_md": "丙误将他人事务当作自己事务处理", "image": null },
    { "key": "D", "text_md": "丁为流浪猫投喂食物", "image": null }
  ],
  "answer": {
    "keys": ["A"],
    "source": "ai_generated",
    "confidence": 0.86
  },
  "explanation_md": "**【考点】** 无因管理\n\n**【解析】** 无因管理要求……\n\n**【选项分析】**\n- A：……",
  "knowledge_points": ["GK/FL/MF/0003"],
  "difficulty": 3,
  "source": {
    "exam_type": "事业单位",
    "exam_name": "2024 年某省事业单位联考",
    "year": 2024,
    "region": "省直",
    "paper_id": null,
    "question_no": 12
  },
  "tags": ["民法典", "无因管理"],
  "status": "published",
  "quality": {
    "ai_confidence": 0.86,
    "review_status": "approved",
    "dup_group": null
  }
}
```

**字段约束**

| 字段 | 约束 |
|---|---|
| `type` | `single_choice` / `multiple_choice`（枚举，后续扩展） |
| `options` | 2–8 个，key 唯一，按 A–H 顺序 |
| `answer.keys` | 单选恰好 1 个；多选 ≥ 2 个；必须是 options 子集 |
| `stem_md` | 非空，长度上限 5000 字符 |
| `knowledge_points` | ≥ 1，使用知识点编码 |
| `difficulty` | 1–5 整数（1 最易） |
| `status` | `draft` / `pending_review` / `published` / `archived` |

> PostgreSQL 的 `JSONB` 只保证 JSON 合法，业务约束靠 Jakarta Validation + JSON Schema（networknt）双重校验，校验失败不允许写库。

### 3.3 核心表结构（PostgreSQL）

**题库域**

```sql
-- 题目主表
CREATE TABLE question (
    id              BIGSERIAL PRIMARY KEY,
    type            VARCHAR(32)  NOT NULL,
    stem_md         TEXT         NOT NULL,
    stem_images     JSONB        NOT NULL DEFAULT '[]',
    answer_keys     JSONB        NOT NULL,          -- ["A"] / ["A","C"]
    answer_source   VARCHAR(16)  NOT NULL DEFAULT 'manual',
    answer_conf     NUMERIC(4,3),
    explanation_md  TEXT,
    expl_source     VARCHAR(16),
    difficulty      SMALLINT     NOT NULL DEFAULT 3,
    exam_type       VARCHAR(32),
    exam_name       VARCHAR(128),
    exam_year       SMALLINT,
    region          VARCHAR(64),
    question_no     INTEGER,
    status          VARCHAR(24)  NOT NULL DEFAULT 'draft',
    quality_score   NUMERIC(4,3),
    tags            JSONB        NOT NULL DEFAULT '[]',
    content_hash    CHAR(64),
    simhash         BIGINT,
    current_version INTEGER      NOT NULL DEFAULT 1,
    index_status    SMALLINT     NOT NULL DEFAULT 0,  -- 0待建 1成功 2失败
    created_by      BIGINT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ
);
CREATE INDEX idx_q_status_type ON question (status, type);
CREATE INDEX idx_q_exam ON question (exam_type, exam_year);
CREATE INDEX idx_q_hash ON question (content_hash);
CREATE INDEX idx_q_updated ON question (updated_at);
CREATE INDEX idx_q_tags ON question USING gin (tags);

-- 选项表
CREATE TABLE question_option (
    id          BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    opt_key     CHAR(1) NOT NULL,
    text_md     TEXT NOT NULL,
    image_url   VARCHAR(512),
    sort_no     SMALLINT NOT NULL,
    UNIQUE (question_id, opt_key)
);

-- 知识点树
CREATE TABLE knowledge_point (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(64) NOT NULL UNIQUE,   -- GK/FL/MF/0003
    parent_id   BIGINT REFERENCES knowledge_point(id),
    name        VARCHAR(128) NOT NULL,
    level       SMALLINT NOT NULL,
    path        VARCHAR(512) NOT NULL,         -- 物化路径，便于子树查询
    exam_type   VARCHAR(32),
    sort_no     INTEGER DEFAULT 0,
    status      SMALLINT DEFAULT 1
);
CREATE INDEX idx_kp_path ON knowledge_point (path);

-- 题目-知识点 多对多
CREATE TABLE question_knowledge (
    question_id BIGINT NOT NULL REFERENCES question(id) ON DELETE CASCADE,
    kp_id       BIGINT NOT NULL REFERENCES knowledge_point(id),
    PRIMARY KEY (question_id, kp_id)
);
CREATE INDEX idx_qk_kp ON question_knowledge (kp_id);

-- 题目版本快照（发布即冻结，供试卷引用）
CREATE TABLE question_version (
    id          BIGSERIAL PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES question(id),
    version     INTEGER NOT NULL,
    snapshot    JSONB NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (question_id, version)
);
```

**导入域**

```sql
CREATE TABLE import_batch (
    id           BIGSERIAL PRIMARY KEY,
    source_type  VARCHAR(16) NOT NULL,   -- text|image|docx|pdf|chat
    source_name  VARCHAR(256),
    file_url     VARCHAR(512),
    raw_text     TEXT,
    status       VARCHAR(24) NOT NULL,   -- parsing|extracting|reviewing|done|failed
    total_count  INTEGER DEFAULT 0,
    ok_count     INTEGER DEFAULT 0,
    fail_count   INTEGER DEFAULT 0,
    created_by   BIGINT NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ib_status ON import_batch (status, created_at);

CREATE TABLE import_item (
    id           BIGSERIAL PRIMARY KEY,
    batch_id     BIGINT NOT NULL REFERENCES import_batch(id) ON DELETE CASCADE,
    seq          INTEGER NOT NULL,
    raw_text     TEXT,
    parsed_json  JSONB,
    confidence   NUMERIC(4,3),
    errors       JSONB NOT NULL DEFAULT '[]',
    status       VARCHAR(24) NOT NULL,   -- pending|valid|invalid|merged|imported|ignored
    question_id  BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (batch_id, seq)
);
CREATE INDEX idx_ii_status ON import_item (status);

-- AI 任务记录（成本、可追溯）
CREATE TABLE ai_job (
    id                BIGSERIAL PRIMARY KEY,
    job_type          VARCHAR(32) NOT NULL,   -- extract|answer|explain|dedupe|embed
    biz_ref           VARCHAR(64),
    provider          VARCHAR(32),
    model             VARCHAR(64),
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    cost_usd          NUMERIC(10,6),
    status            VARCHAR(16) NOT NULL,
    error             TEXT,
    latency_ms        INTEGER,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_aj_type_time ON ai_job (job_type, created_at);
CREATE INDEX idx_aj_biz ON ai_job (biz_ref);
```

**练习与统计域**

```sql
CREATE TABLE practice_session (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT NOT NULL,
    mode        VARCHAR(24) NOT NULL,   -- chapter|random|wrong|paper|favorite
    ref_id      BIGINT,
    total       INTEGER DEFAULT 0,
    answered    INTEGER DEFAULT 0,
    correct     INTEGER DEFAULT 0,
    started_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);
CREATE INDEX idx_ps_user ON practice_session (user_id, started_at DESC);

CREATE TABLE answer_record (
    id           BIGSERIAL PRIMARY KEY,
    user_id      BIGINT NOT NULL,
    question_id  BIGINT NOT NULL,
    session_id   BIGINT,
    paper_id     BIGINT,
    user_keys    JSONB NOT NULL,
    is_correct   BOOLEAN NOT NULL,
    duration_ms  INTEGER,
    answered_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ar_user_time ON answer_record (user_id, answered_at DESC);
CREATE INDEX idx_ar_question ON answer_record (question_id);
-- 数据量上来后改为按月 RANGE 分区（见 12.2）

CREATE TABLE question_stat (
    question_id     BIGINT PRIMARY KEY REFERENCES question(id) ON DELETE CASCADE,
    attempt_count   INTEGER DEFAULT 0,
    correct_count   INTEGER DEFAULT 0,
    p_value         NUMERIC(5,4),
    discrimination  NUMERIC(5,4),
    avg_duration_ms INTEGER,
    updated_at      TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE stat_daily (
    stat_date    DATE NOT NULL,
    exam_type    VARCHAR(32) NOT NULL DEFAULT '',
    new_questions INTEGER DEFAULT 0,
    answer_count  INTEGER DEFAULT 0,
    active_users  INTEGER DEFAULT 0,
    correct_rate  NUMERIC(5,4),
    PRIMARY KEY (stat_date, exam_type)
);

CREATE TABLE user_knowledge_mastery (
    user_id      BIGINT NOT NULL,
    kp_id        BIGINT NOT NULL,
    mastery      NUMERIC(5,2) NOT NULL DEFAULT 0,
    sample_count INTEGER NOT NULL DEFAULT 0,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, kp_id)
);
CREATE INDEX idx_ukm_kp ON user_knowledge_mastery (kp_id);
```

**消息域（Outbox + 消费幂等）**

```sql
CREATE TABLE mq_outbox (
    id            BIGSERIAL PRIMARY KEY,
    topic         VARCHAR(64) NOT NULL,
    tag           VARCHAR(64) NOT NULL,
    msg_key       VARCHAR(64) NOT NULL,
    payload       JSONB NOT NULL,
    status        SMALLINT NOT NULL DEFAULT 0, -- 0待投递 1已投递 2失败放弃
    retry_count   INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_outbox_pending ON mq_outbox (status, next_retry_at);

CREATE TABLE mq_consume_log (
    id             BIGSERIAL PRIMARY KEY,
    msg_id         VARCHAR(64) NOT NULL,
    consumer_group VARCHAR(64) NOT NULL,
    status         SMALLINT NOT NULL DEFAULT 1,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (msg_id, consumer_group)
);
```

### 3.4 JSONB 使用规范

PG 的 JSONB 支持 GIN 索引与丰富操作符，但**不能滥用**：

| 场景 | 做法 |
|---|---|
| 高频筛选（题型/状态/年份/难度） | **提成普通列**，不要放 JSONB |
| 标签、知识点列表 | JSONB + GIN，或关联表（推荐关联表） |
| 快照、图片列表、错误详情 | 整体读取，不参与筛选，放 JSONB |
| 答案 key 数组 | JSONB，判分在应用层比较，不查库 |

**GIN 索引用法**

```sql
-- 标签包含查询
CREATE INDEX idx_q_tags ON question USING gin (tags);
SELECT id, stem_md FROM question
WHERE tags @> '["民法典"]' AND status = 'published';

-- 只关心 key 是否存在时用 jsonb_path_ops，索引更小
CREATE INDEX idx_q_stem_ops ON question USING gin (stem_images jsonb_path_ops);
```

**JDBC 坑**：`?` 是 JDBC 占位符，也是 JSONB 的"键存在"操作符。MyBatis 中直接写 `tags ? 'x'` 会报参数错误，改用 `jsonb_exists(tags, ?)` 或 `tags ?? 'x'`（PG JDBC 转义）。

### 3.5 Markdown 索引

**用途**：① RAG 检索的可读语料；② 题库导出/备份/审阅；③ Git 版本对比。

**目录结构**

```
kb/
├── index.md                      # 总索引（按考试类型 → 模块 → 知识点）
├── 事业单位/
│   ├── 公基/
│   │   ├── 法律/
│   │   │   ├── _index.md         # 该知识点下题目清单 + 统计摘要
│   │   │   ├── q_10001.md
│   │   │   └── q_10002.md
│   │   └── 政治/
│   └── 职测/
└── 公务员/
    └── 行测/
```

**单题文件格式**

```markdown
---
id: 10001
type: single_choice
answer: [A]
difficulty: 3
kp: [GK/FL/MF/0003]
source: 2024年某省事业单位联考
year: 2024
status: published
---

# 根据《民法典》，下列哪一情形构成无因管理？

A. 甲替邻居修缮被台风掀翻的屋顶
B. 乙主动为朋友偿还赌债
C. 丙误将他人事务当作自己事务处理
D. 丁为流浪猫投喂食物

## 答案
A

## 解析
**【考点】** 无因管理
**【解析】** ……
```

**生成与同步**：题目 `published` 后写 Outbox → RocketMQ → Java 消费者调 Python 重建该题 MD，并更新所属 `_index.md`。MD 只读，人工不直接改文件（避免回写冲突）；如需批量修正走后台编辑器。

### 3.6 向量索引（pgvector）

| 项 | 设计 |
|---|---|
| 存储 | `question_embedding` 表，`vector(1024)` |
| 索引 | HNSW（`vector_cosine_ops`），`m=16, ef_construction=64` |
| 切分 | 单题一条向量：`题干 + 选项 + 解析摘要 + 知识点`，≤ 512 token |
| 检索 | 余弦相似度 + SQL 过滤（状态/题型/知识点） |
| 去重 | 余弦 > 0.95 进入疑似重复队列 |
| 重建 | 按 `exam_type` / 时间范围批量重建 |

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE question_embedding (
    question_id BIGINT PRIMARY KEY REFERENCES question(id) ON DELETE CASCADE,
    embedding   vector(1024) NOT NULL,
    model       VARCHAR(64) NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_qe_hnsw ON question_embedding
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);
```

**检索（带业务过滤）**

```sql
SELECT q.id, q.stem_md, 1 - (e.embedding <=> $1) AS score
FROM question_embedding e
JOIN question q ON q.id = e.question_id
WHERE q.status = 'published'
  AND q.type = $2
  AND q.exam_type = $3
ORDER BY e.embedding <=> $1
LIMIT 20;
```

- 建索引时临时调大 `maintenance_work_mem`，否则 10 万级会很慢。
- `ef_search` 会话级可调（`SET LOCAL hnsw.ef_search = 100`），召回与延迟权衡。
- 向量与题目同库同备份，**不会出现"题库回滚了、向量没回滚"**，这是相比外置向量库的最大优势。

### 3.7 双索引一致性

**写入链路（最终一致，秒级）**

```
Java 事务提交（question + version + outbox，同一 PG 事务）
  → worker-java 扫描 mq_outbox（FOR UPDATE SKIP LOCKED）
  → 投递 RocketMQ（顺序消息，key = questionId）
  → Java 消费者调用 Python /internal/index/upsert
  → Python 拉题目 → 向量化 → 写 question_embedding + 重写 MD
  → 回写 question.index_status
```

- 顺序消息保证同一题的"发布 v2"不会晚于"发布 v1"被消费。
- 后台提供"索引重建"按钮与一致性巡检（对比 `updated_at` 与 `question_embedding.updated_at`）。
- 检索侧容忍短暂延迟；后台编辑页展示"索引状态"标识。

### 3.8 去重策略（三级）

| 级别 | 方法 | 处理 |
|---|---|---|
| L1 精确 | 归一化（去空白/标点/全半角）后 `sha256` | 直接拒绝，提示已存在 |
| L2 近似 | SimHash（64 位）+ 汉明距离 ≤ 3 | 进入疑似重复，人工合并 |
| L3 语义 | 题干向量余弦 > 0.95 | 进入疑似重复，人工合并 |

- L1 用 `content_hash` 唯一性检查。
- L2 用 Java 侧 SimHash + BK 树分桶，10 万级够用；也可用 `pg_trgm` 做文本相似度兜底。
- 合并策略：保留最早/质量最高的一条为主记录，其余标记 `merged_into`，作答记录迁移到主记录。

### 3.9 版本与快照

- 题目每次 `published` 时写 `question_version` 快照。
- 真题试卷的 `paper_question` 引用 `question_version_id`，保证历史试卷不受题库后续修改影响。
- 学员作答记录同时存 `question_version_id`，解析展示按当时版本。

---

## 4. Agent 导入（核心亮点）

### 4.1 端到端流程

```mermaid
sequenceDiagram
    participant U as 运营/管理员
    participant W as Agent 工作台
    participant J as Java 导入编排
    participant Q as RocketMQ
    participant P as Python AI
    participant M as 模型/OCR

    U->>W: 粘贴文本 / 上传图片/PDF
    W->>J: 创建导入批次
    J->>J: 原始文件入 NAS + 落库 + 写 outbox
    J->>Q: import-task（Relay 投递）
    Q->>J: 消费者拉取
    J->>P: HTTP /internal/import/parse
    P->>M: OCR / 版面分析
    M-->>P: 文本 + 坐标
    P->>M: LLM 结构化抽取
    M-->>P: 候选题目
    P->>P: 校验 + 去重 + 知识点匹配
    P-->>J: 结构化结果（JSON）
    J->>J: 写 import_item（草稿）
    J-->>W: SSE 推送进度
    U->>W: 审核台逐题确认
    W->>J: 批量采纳
    J->>J: 写 question + version + outbox
    J->>Q: question-event（顺序）
    Q->>P: 消费者触发建索引
```

### 4.2 输入形态

| 形态 | 处理 |
|---|---|
| 纯文本粘贴 | 直接切题（正则 + LLM） |
| 图片（截图/拍照） | OCR → 版面还原 → 切题 |
| Word（.docx） | python-docx 抽段落/表格，保留图片 |
| PDF | pdfplumber 抽文本；扫描版走 OCR |
| 多文件批量 | 每文件一个子任务，汇总到一个批次 |
| 对话式逐题录入 | Agent 对话中一问一答补全字段 |

### 4.3 OCR 与版面解析

1. **图像预处理**：去噪、纠偏、对比度增强。
2. **OCR**：PaddleOCR（中文识别 + 方向分类）；手写/低质量图片走云 OCR 兜底。
3. **版面还原**：
   - 按行聚合，保留阅读顺序；
   - 识别题号模式（`1.` `1、` `（1）` `第1题`）作为切分锚点；
   - 识别选项模式（`A.` `A、` `A．`），处理跨行；
   - 图片题：截取题目区域图片存 NAS/MinIO，题干保留 `![](url)`。
4. **质量门禁**：OCR 平均置信度 < 0.75 的题目标记"低质 OCR"，优先人工。

### 4.4 切题与结构化抽取

**策略：规则打底 + LLM 兜底**

- 规则层：题号/选项正则切块，处理 80% 格式规整的文本，成本低、稳定。
- LLM 层：对切块结果做字段抽取与纠错，使用 **JSON Schema 约束输出**（function calling / response_format）。

抽取 Prompt 要点：

```
你是考试题库结构化助手。将给定文本转换为 JSON 数组，每个元素符合 schema。
规则：
1. 只抽取客观题（单选/多选）；无法判断题型时按选项与答案推断，仍无法判断则 type=unknown。
2. 保留题干原文，不要改写；公式用 LaTeX，图片保留占位 [[IMG:n]]。
3. 选项必须完整；缺失选项不要臆造，写入 errors。
4. 原文有答案则 answer.source=imported；无答案则 answer.keys=[] 且 source=missing。
5. 解析原文存在则保留，不要润色。
6. 输出严格 JSON，不要 Markdown 代码块。
```

**输出示例**

```json
[{
  "type": "multiple_choice",
  "stem_md": "...",
  "options": [{"key":"A","text_md":"..."}],
  "answer": {"keys": ["A","C"], "source": "imported"},
  "explanation_md": "...",
  "knowledge_points": ["GK/FL/MF/0003"],
  "difficulty": 3,
  "errors": []
}]
```

### 4.5 校验规则（入库前必过）

| 校验 | 处理 |
|---|---|
| 选项数量 2–8 且 key 连续 | 不通过 → `invalid` |
| 单选答案恰好 1 个 | 不通过 → 修正建议 |
| 多选答案 ≥ 2 且 ⊆ 选项 | 不通过 → 修正建议 |
| 题干非空、无乱码比例 > 10% | 标记低质 |
| 知识点可匹配 | 未匹配 → 待人工标注 |
| 重复检测 L1/L2/L3 | 命中 → `merged` 待确认 |
| 答案与解析矛盾（LLM 复核） | 标记冲突，人工优先 |

### 4.6 答案与解析生成

**触发条件**：`answer.source == missing` 或 `explanation_md` 为空。

**生成流程（Self-Consistency + RAG）**

```
1. 检索：同知识点相似题 top-5 + 知识点讲义片段 + 相关法条/教材
2. 生成：LLM 输出 {answer_keys, confidence, reasoning}
3. 采样：temperature=0.3 采样 3 次，多数投票
4. 一致性：
   - 3/3 一致 → confidence 0.9+，可直接进"待审核"
   - 2/3 一致 → confidence 0.6~0.8，标记"建议复核"
   - 分歧     → confidence < 0.6，强制人工
5. 解析生成：基于最终答案，输出固定结构（考点/解析/选项分析/易错点）
6. 落库：answer_source=ai_generated，写 ai_job 审计
```

**解析模板**

```markdown
**【答案】** A

**【考点】** 无因管理的构成要件

**【解析】**
无因管理是指没有法定或约定义务，为避免他人利益受损而管理他人事务的行为。
构成要件包括：① 管理他人事务；② 无法定或约定义务；③ 为他人谋利益。
本案中甲的行为同时满足三项要件，故构成无因管理。

**【选项分析】**
- A：正确，符合无因管理构成要件。
- B：错误，赌债非法，且不属于"为避免他人利益受损"。
- C：错误，误将他人事务当作自己事务，属于不当得利/侵权范畴。
- D：错误，投喂流浪猫不构成"管理他人事务"。

**【易错点】** 注意区分无因管理与见义勇为、不当得利。
```

**质量红线**

- AI 生成的答案与解析**一律不允许直接发布**，必须经过审核台（可批量通过高置信度项）。
- 置信度 < 0.7 的题目在审核台置顶并标红。
- 同一题目 AI 答案与导入答案冲突时，**以导入答案为准**，冲突记入质检报告。

### 4.7 审核工作流

```
draft ──提交──> pending_review ──通过──> published
                    │                      │
                    ├──驳回──> draft        └──下架──> archived
                    └──合并──> merged_into
```

| 能力 | 说明 |
|---|---|
| 单题审核 | 左右分栏：原文 / 结构化结果 / AI 建议 |
| 批量审核 | 按批次、置信度、知识点筛选，支持批量通过 |
| 差异对比 | 原题 vs AI 改写，高亮差异 |
| 快捷修正 | 键盘操作（A–D 设答案、Tab 切字段） |
| 审核意见 | 记录驳回原因，用于优化 Prompt |

### 4.8 对话式 Agent 设计

**工具集（管理端 Agent）**

| 工具 | 作用 | 副作用 |
|---|---|---|
| `search_questions` | 按关键词/知识点检索已有题 | 只读 |
| `get_question` | 取题目详情 | 只读 |
| `parse_text_to_questions` | 文本 → 结构化草稿 | 写草稿区 |
| `ocr_image_to_questions` | 图片 → 结构化草稿 | 写草稿区 |
| `check_duplicate` | 查重 | 只读 |
| `generate_answer` | 生成答案 | 写草稿 |
| `generate_explanation` | 生成解析 | 写草稿 |
| `create_import_batch` | 建批次 | 写 |
| `submit_for_review` | 提交审核 | 写 |
| `get_batch_status` | 查进度 | 只读 |
| `suggest_knowledge_point` | 知识点推荐 | 只读 |

**对话流程示例**

```
用户：把这张图片里的题录进去，没有答案的帮我生成。
Agent：识别到 12 道题（单选 9、多选 3），其中 4 道缺少答案。
       [卡片：12 道题预览，可展开]
       是否按以下策略处理？① 有答案的保留原文 ② 无答案的生成并标记待审核
用户：可以，第 7 题知识点归到"行政处罚"。
Agent：已调整。第 7 题原知识点"法律/其他"→"法律/行政法/行政处罚"。
       已生成 4 道题的答案与解析，置信度分别为 0.92/0.88/0.71/0.55。
       置信度 0.55 的题目建议人工确认，是否现在查看？
用户：先提交，低置信度的我自己看。
Agent：已创建批次 #B20241107-003，12 道题进入待审核，3 道高置信度可批量通过。
```

**关键交互原则**

1. **写操作必须二次确认**：任何落库动作前弹确认卡片（数量、影响范围）。
2. **结构化卡片优先于纯文本**：题目预览、置信度、校验错误以卡片渲染。
3. **可中断、可回滚**：批次可撤回，草稿可删除。
4. **上下文隔离**：管理端会话与学员端会话完全分开存储。

### 4.9 学员端 Agent（答疑）

- **只读工具**：`search_questions`、`get_question`、`get_user_mastery`、`explain_concept`。
- **不写题库**，不暴露后台工具。
- 回答带引用（题目 id / 知识点），避免幻觉。
- 每次答疑计入配额，防刷。

### 4.10 后台与 Agent 的分离设计

| 维度 | 管理后台 | Agent 工作台 |
|---|---|---|
| 入口 | `/admin/*` | `/admin/agent`（独立路由）/ 独立域名 |
| 认证 | 后台账号 + RBAC | 复用登录，但独立会话 scope |
| 交互 | 表单、表格、编辑器 | 对话 + 卡片 + 工具调用 |
| 写权限 | 直接 CRUD（有审计） | 仅草稿区 + 待审核，需确认 |
| 服务 | Java `api-java` | Python `ai-service`（Java 只做鉴权与配额） |
| 数据 | 全量 | 仅授权范围 |
| 审计 | `audit_log` | `agent_session` + `agent_tool_call` |
| 模型 | 不涉及 | 独立模型配置、独立限流 |

**技术实现**：Agent 工作台是独立前端子应用（qiankun 或独立部署），通过 `ai-service` 的 `/agent/*` 网关访问；Java 侧只提供 `/internal/agent/*` 鉴权与数据接口。这样 Agent 的迭代不会影响后台 CRUD 的稳定性。

---

## 5. 后台管理

### 5.1 题库管理

| 功能 | 说明 |
|---|---|
| 列表 | 多条件筛选（题型/状态/知识点/来源/年份/置信度）、批量操作 |
| 新建 | 富文本题干（Markdown + 图片上传 + LaTeX）、动态选项、答案选择 |
| 编辑 | 同新建；保存生成新版本；支持"另存为新题" |
| 预览 | 学员视角预览（含答案解析） |
| 版本 | 历史版本对比、回滚 |
| 批量 | 批量改知识点/难度/来源、批量提交审核、批量下架 |
| 导入 | 文本/文件/Agent 三种入口 |

### 5.2 编辑器技术要点

- 题干与解析使用 **Markdown 编辑器**（如 `md-editor-v3` / `bytemd`），支持：
  - 图片粘贴上传（预签名直传 NAS/MinIO）
  - LaTeX 公式（KaTeX 渲染）
  - 表格
- 选项为动态表单，key 自动生成 A/B/C/D，支持拖拽排序。
- 答案选择与选项联动：删除选项时校验答案合法性。
- 自动保存草稿（localStorage + 服务端 30s 定时）。
- 编辑冲突：乐观锁（`current_version`），冲突时提示合并。

### 5.3 试卷管理

见第 6 章。

### 5.4 权限模型（RBAC）

| 角色 | 权限 |
|---|---|
| `super_admin` | 全部 |
| `content_admin` | 题库 CRUD、导入、审核、试卷 |
| `reviewer` | 审核、驳回、合并（不可删除） |
| `operator` | 只读 + 统计看板 |
| `agent_user` | Agent 工作台（受限于草稿区） |
| `student` | 学员端 |

权限点粒度：`question:read` / `question:write` / `question:review` / `question:publish` / `paper:write` / `stat:read` / `agent:use`。

### 5.5 审计

- 所有写操作记录 `audit_log(actor, action, target_type, target_id, before, after, ip, ua)`。
- AI 生成内容额外记录 `ai_job_id`，可追溯"哪次调用、哪个模型、多少成本"。

### 5.6 后台内的 Agent 页面

位置：PC 管理端 `/admin/agent`，与题库管理、试卷管理并列的一级菜单。

| 区域 | 内容 |
|---|---|
| 左栏 | 会话列表（按批次/时间），新建会话 |
| 中栏 | 对话流：文本、图片、文件上传；流式输出 |
| 右栏 | 结构化结果：题目预览、置信度、校验错误、批次进度 |

能力：

- 粘贴文本 / 拖入图片 / 上传 Word/PDF → 切题 → 草稿
- 批量生成答案与解析，按置信度排序
- 查重、合并、批量提交审核
- 查看批次进度与失败原因，支持重试

与移动端 Agent 的区别：

| 维度 | 后台 Agent | 移动端 Agent |
|---|---|---|
| 用户 | 运营/教研 | 学员 |
| 工具 | 导入/生成/审核类（可写草稿） | 抽题/判分/解析/学情（只读 + 作答） |
| 落库 | 草稿区，需人工确认 | 只写作答记录 |
| 模型 | 可用高配模型 | 中高配，重体验与成本 |

实现上复用同一个 `ai-service` 的 `/agent/*` 网关，通过 `scope` 参数切换系统提示词、工具白名单与限流策略；前端是两个壳（Arco Design Vue / Vant 4），共用 `shared` 里的逻辑层，不共享皮肤。

### 5.7 对话组件自研要点

不引第三方聊天 SDK，核心是四件事：SSE 流、消息模型、Markdown 渲染、题目卡片。

**1. SSE 用 fetch，不用 EventSource**

`EventSource` 不支持 POST、不能带 `Authorization` 头，所以用 `fetch` + `ReadableStream` 手写解析：

```ts
async function streamChat(url: string, body: unknown, onEvent: (e: SSEEvent) => void) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: JSON.stringify(body),
  })
  const reader = res.body!.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    const parts = buf.split('\n\n')
    buf = parts.pop() ?? ''
    for (const part of parts) onEvent(parseSSE(part))
  }
}
```

- 必须处理：分包（一条事件被拆成两次 read）、`\r\n`、空行、注释行（`: ping`）。
- 断线重连由用户点"重试"触发，不做自动重连（避免重复扣费）。

**2. 消息模型**

```ts
type Block =
  | { type: 'text'; text: string }
  | { type: 'card'; card: QuestionCard | BatchPreview }
  | { type: 'tool'; name: string; status: 'running' | 'done' | 'failed' }

interface Message {
  id: string
  role: 'user' | 'assistant'
  blocks: Block[]
  status: 'streaming' | 'done' | 'error'
}
```

- 流式期间只追加最后一个 text block，避免整表重渲染。
- 卡片与文本分离，卡片数据到达后替换占位块。

**3. Markdown 渲染**

- `markdown-it` + `katex` + `dompurify`；题干里的 LaTeX、表格、图片都要支持。
- 流式渲染做节流（`requestAnimationFrame` 或 50ms），不要每个 token 全量 parse。
- 增量渲染：按 `\n\n` 分块，已完成的块缓存 HTML，只重渲染最后一块。

**4. 题目卡片**

- 单选/多选交互、提交、判分结果、解析折叠。
- 选项图片走 MinIO 公网桶 URL；加载失败占位。
- 提交后禁用选项，防止重复作答。

**5. 移动端适配**

- `100dvh` + `env(safe-area-inset-bottom)`，输入框不被键盘顶飞。
- 键盘弹起用 `visualViewport` 监听，滚动到底部。
- 中文输入法 composition 期间不触发发送（`compositionstart/end`）。

**6. 消息列表**

- 超过 50 条用虚拟列表（`vue-virtual-scroller` 或自研窗口化）。
- "贴底"逻辑：用户上滑后停止自动滚动，出现"回到底部"按钮。

---

## 6. 真题试卷

### 6.1 模型

```sql
CREATE TABLE paper (
    id             BIGSERIAL PRIMARY KEY,
    title          VARCHAR(256) NOT NULL,
    paper_type     VARCHAR(16) NOT NULL,   -- real|mock|custom
    exam_type      VARCHAR(32),
    exam_year      SMALLINT,
    region         VARCHAR(64),
    total_score    NUMERIC(6,2),
    duration_min   INTEGER,
    question_count INTEGER,
    status         VARCHAR(16) DEFAULT 'draft',
    created_by     BIGINT,
    created_at     TIMESTAMPTZ DEFAULT now()
);
CREATE INDEX idx_paper_type ON paper (paper_type, exam_year);

CREATE TABLE paper_section (
    id       BIGSERIAL PRIMARY KEY,
    paper_id BIGINT NOT NULL REFERENCES paper(id) ON DELETE CASCADE,
    name     VARCHAR(128),
    sort_no  INTEGER DEFAULT 0,
    score    NUMERIC(6,2)
);

CREATE TABLE paper_question (
    id                  BIGSERIAL PRIMARY KEY,
    paper_id            BIGINT NOT NULL REFERENCES paper(id) ON DELETE CASCADE,
    section_id          BIGINT,
    question_version_id BIGINT NOT NULL REFERENCES question_version(id),
    question_no         INTEGER NOT NULL,
    score               NUMERIC(5,2) DEFAULT 1,
    UNIQUE (paper_id, question_no)
);
CREATE INDEX idx_pq_version ON paper_question (question_version_id);

CREATE TABLE exam_attempt (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL,
    paper_id      BIGINT NOT NULL,
    status        VARCHAR(16) NOT NULL,   -- ongoing|submitted|timeout
    score         NUMERIC(6,2),
    correct_count INTEGER,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at  TIMESTAMPTZ,
    duration_sec  INTEGER
);
CREATE INDEX idx_ea_user ON exam_attempt (user_id, started_at DESC);
CREATE INDEX idx_ea_paper ON exam_attempt (paper_id);

CREATE TABLE exam_answer (
    id                  BIGSERIAL PRIMARY KEY,
    attempt_id          BIGINT NOT NULL REFERENCES exam_attempt(id) ON DELETE CASCADE,
    question_id         BIGINT NOT NULL,
    question_version_id BIGINT NOT NULL,
    user_keys           JSONB,
    is_correct          BOOLEAN,
    score               NUMERIC(5,2),
    answered_at         TIMESTAMPTZ,
    UNIQUE (attempt_id, question_id)
);
```

**关键点**：试卷引用 `question_version_id` 而非 `question_id`，题库改题不影响历史试卷；模考作答记录也带版本。

### 6.2 真题导入

- 整卷导入：Agent 识别"试卷 → 大题（section）→ 小题"三级结构。
- 元数据抽取：年份、地区、考试类型、总分、时限。
- 题目按题号排序，自动挂载 section。
- 缺失答案的整卷：先入库为 `draft`，AI 批量补答案后统一审核。
- 支持"从题库选题组卷"：按知识点、难度、题型筛选后拖拽排序。

### 6.3 模考流程

```
开始模考 → 生成 exam_attempt（快照题目顺序）
  → 前端本地计时 + 服务端 started_at 校验（防作弊/防篡改）
  → 逐题保存答案（自动保存，防丢失）
  → 交卷 / 超时自动交卷（延迟消息兜底）
  → 判分（客观题即时）
  → 生成成绩报告（得分、正确率、知识点分布、用时、排名百分位）
```

- 答案保存在 `exam_answer` 表（与 `answer_record` 分开，避免污染练习统计）。
- 判分规则：单选全对得分；多选**全对得满分，少选得部分分，错选不得分**（可配置，默认按考试规则）。
- 排名：同试卷同批次，Redis ZSet 实时榜 + 落库。
- 超时自动交卷：启动时发一条延迟消息（`duration + 5min`），消费者检查 `status=ongoing` 则强制交卷；防止用户关页面逃避。

### 6.4 智能组卷（第二期，先留接口）

- 约束：总分、题型配比、知识点覆盖、难度分布。
- 算法：贪心 + 回溯 / 遗传算法；或 LLM 辅助生成配比方案。
- 第一期提供"手动组卷 + 按筛选条件一键填充"。

---

## 7. 刷题端

### 7.1 练习模式

| 模式 | 说明 |
|---|---|
| 章节练习 | 按知识点树逐层进入，显示掌握度 |
| 随机练习 | 按考试类型/模块随机抽题 |
| 错题本 | 自动收集错题，支持"错题重练""移出" |
| 收藏 | 手动收藏题目 |
| 每日一练 | 定时任务生成，按用户薄弱点抽题 |
| 模考 | 见第 6 章 |

### 7.2 答题交互

- 单选：点击选项即选中，需点"确认"提交（避免误触）。
- 多选：多选切换，提交前校验至少选 2 项。
- 提交后即时反馈：正确/错误、正确答案、解析展开。
- 解析中 AI 生成内容标注"AI 生成，仅供参考"。
- 支持"纠错"入口：学员可反馈题目问题（题干错误/答案错误/解析错误），进入后台质检队列。

### 7.3 判题服务

```
POST /api/v1/practice/answer
{
  "sessionId": 123,
  "questionId": 10001,
  "userKeys": ["A"],
  "durationMs": 15200
}
→
{
  "correct": true,
  "answerKeys": ["A"],
  "explanationMd": "...",
  "knowledgePoints": [...],
  "stats": {"pValue": 0.72, "attemptCount": 1024}
}
```

- 判题逻辑在 Java，纯内存比较，QPS 高。
- 防重复提交：Redis `SETNX` + 幂等键。
- 作答落库后发 `answer-event`，异步更新统计（不阻塞判题响应）。

### 7.4 错题本与复习

- 错题本按知识点分组，显示错误次数。
- 复习算法（第一期）：艾宾浩斯简化版——错后 1 天、3 天、7 天提醒。
- 提醒用 RocketMQ 延迟消息实现（见 9.7），不依赖定时扫全表。
- 第二期引入 FSRS / 半衰期回归。

---

## 8. 统计与智能分析

### 8.1 指标体系

**运营看板（管理后台首页）**

| 指标 | 口径 |
|---|---|
| 题库总量 | `published` 题目数，按题型/考试类型分组 |
| 今日新增 | 当日 `created_at` |
| 待审核 | `pending_review` 数量 |
| AI 生成占比 | `answer_source=ai_generated` / 总数 |
| 导入批次 | 近 7 天批次数、成功率、平均耗时 |
| 活跃用户 | DAU / WAU，按练习会话去重 |
| 答题量 | 当日 `answer_record` 数 |
| 平均正确率 | `correct / total` |
| 试卷完成率 | `submitted / started` |
| 题目异常 | p 值 < 0.15 或 > 0.98 的题（疑似答案错误/过易） |
| 用户反馈 | 待处理纠错数 |

**学员学情**

| 指标 | 说明 |
|---|---|
| 累计答题 / 正确率 | 总览 |
| 知识点掌握度 | `mastery = 加权正确率（近期权重高）`，0–100 |
| 能力雷达 | 按模块（言语/数量/判断/资料/常识）聚合 |
| 学习时长 | 会话时长累计 |
| 错题分布 | 按知识点/题型 |
| 进步曲线 | 周维度正确率趋势 |
| 预测分 | 基于最近 N 次模考 + 练习正确率的线性/规则模型（第二期换 ML） |

### 8.2 知识点掌握度算法

```
mastery(kp) = Σ(w_i * correct_i) / Σ(w_i)
w_i = 0.5^(days_ago / 14) * difficulty_weight
difficulty_weight = 0.8 + 0.1 * difficulty   -- 难题答对权重更高
```

- 样本 < 5 时标记"数据不足"，不展示具体分数。
- 每日离线计算，写 `user_knowledge_mastery`。

### 8.3 题目质量分析（CTT）

| 指标 | 公式 | 用途 |
|---|---|---|
| 难度 P | 正确人数 / 作答人数 | P < 0.2 太难，> 0.95 太易 |
| 区分度 D | 高分组（前 27%）正确率 − 低分组（后 27%）正确率 | D < 0.2 题目区分度差 |
| 作答时长 | 中位数 | 异常短 → 疑似泄题/蒙对；异常长 → 题干不清 |
| 选项分布 | 各选项选择比例 | 某干扰项 0 选择 → 选项无效 |

- 每周离线跑一次，生成"题目体检报告"，推送给教研。
- 低质量题目自动进入"待优化"列表。

### 8.4 智能分析（AI 层）

| 能力 | 实现 |
|---|---|
| 薄弱点诊断 | 掌握度 + 错题聚合 → LLM 生成自然语言诊断 |
| 错题归因 | 错题 + 解析 → 判断"概念不清/审题失误/计算错误/蒙错" |
| 学习建议 | 基于薄弱点推荐知识点 + 题量 + 顺序 |
| 相似题推荐 | pgvector 检索同知识点、同难度题目 |
| 试卷分析报告 | 得分分布、知识点失分、时间分配建议 |
| 题目质检 | LLM 复核答案与解析一致性、题干歧义检测 |

**输出约束**：AI 分析结果落 `analysis_report` 表，前端展示时标注生成时间与模型，避免"看起来像事实"。

### 8.5 统计实现（PostgreSQL 版）

PG 有物化视图，统计比 MySQL 省事：

| 手段 | 适用 | 说明 |
|---|---|---|
| 实时计数 | 日活、当日答题数 | Redis Hash 自增，每 5 min 落 `stat_daily` |
| 物化视图 | 题目质量（p 值/区分度） | 定时 `REFRESH ... CONCURRENTLY` |
| 汇总表 | 学情、知识点掌握度 | 每日跑批写 `user_knowledge_mastery` |
| 即席查询 | 后台多维筛选 | 走索引 + 限时间范围 |

**题目统计物化视图**

```sql
CREATE MATERIALIZED VIEW mv_question_stat AS
SELECT question_id,
       count(*)                                        AS attempt_count,
       count(*) FILTER (WHERE is_correct)              AS correct_count,
       round(avg(CASE WHEN is_correct THEN 1 ELSE 0 END)::numeric, 4) AS p_value,
       percentile_cont(0.5) WITHIN GROUP (ORDER BY duration_ms)       AS median_duration
FROM answer_record
GROUP BY question_id;

-- 必须有唯一索引才能 CONCURRENTLY 刷新
CREATE UNIQUE INDEX idx_mv_qs ON mv_question_stat (question_id);

REFRESH MATERIALIZED VIEW CONCURRENTLY mv_question_stat;
```

- 每小时刷新一次，刷新期间不阻塞读。
- 区分度需要"高分组/低分组"，用窗口函数或单独 SQL 算，结果写回 `question_stat`。
- **答题量削峰**：判题接口只写 `answer_record` + 发消息，聚合由消费者批量处理，避免每次答题都更新 `question_stat` 造成行锁竞争。
- **大数据量预案**：`answer_record` 按月分区；超千万后引入 ClickHouse/Doris 做 OLAP，PG 只保留明细查询。

---

## 9. 消息与异步架构（RocketMQ）

### 9.1 为什么需要 MQ

| 诉求 | 场景 |
|---|---|
| 解耦 | 题目发布后要建向量、写 MD、清缓存，不能阻塞发布事务 |
| 削峰 | 答题事件瞬时高，统计更新不能压垮 PostgreSQL |
| 最终一致 | 题库（PG）与向量/MD（派生索引）跨形态 |
| 延迟任务 | 错题复习提醒、模考超时交卷 |
| 可重放 | 索引重建、统计重算 |

### 9.2 部署形态

- **RocketMQ 5.3.x**：NameServer（2 节点）+ Broker（主从）+ Dashboard。
- 学习/单机阶段可 Docker Compose 起单 NameServer + 单 Broker。
- 客户端用 `rocketmq-spring-boot-starter` 2.3.x（remoting 协议），与 5.x Broker 兼容；不建议直接上 gRPC 客户端，资料少、踩坑多。
- 生产开启 `enableMsgTrace=true`，消息轨迹可查。

### 9.3 Topic 规划

| Topic | Tag | 生产者 | 消费者组 | 说明 |
|---|---|---|---|---|
| `qbank-question-event` | `published` / `updated` / `archived` / `merged` | 题库服务（Outbox） | `cg-index-sync`、`cg-cache` | 索引与缓存同步，**顺序** |
| `qbank-import-task` | `parse` / `ocr` / `retry` | 导入编排 | `cg-import-worker` | 调 Python 解析 |
| `qbank-ai-task` | `answer` / `explain` / `analyze` | 题库/分析服务 | `cg-ai-worker` | 调 Python 生成 |
| `qbank-answer-event` | `submitted` | 练习服务 | `cg-stat` | 统计聚合 |
| `qbank-notify` | `review-reminder` / `exam-timeout` | 练习/试卷服务 | `cg-notify` | **延迟**消息 |

**命名规范**：`qbank-{domain}-{type}`；消费组 `cg-{consumer}`；消息 key 用业务主键（`questionId` / `batchId`），便于排查与幂等。

### 9.4 消息信封

```json
{
  "eventId": "01J8Z3K9V6Q2M4T7",
  "eventType": "question.published",
  "occurredAt": "2026-11-07T02:10:11.123Z",
  "schemaVersion": 1,
  "traceId": "a1b2c3d4",
  "payload": {
    "questionId": 10001,
    "version": 3,
    "indexRequired": true
  }
}
```

- `eventId`：雪花/UUID，消费幂等键。
- `occurredAt`：UTC，避免时区歧义。
- `schemaVersion`：兼容字段变更。

### 9.5 可靠性：本地消息表（Outbox）

**为什么不用 RocketMQ 事务消息**：

| 维度 | 事务消息 | 本地消息表（选用） |
|---|---|---|
| Broker 是否在核心链路 | 是（半消息发不出去就无法提交） | 否（Broker 挂了业务照常） |
| 实现复杂度 | 低（框架封装） | 中（Relay + 重试） |
| 可重放/审计 | 弱 | 强（表里可查、可重投） |
| 适用 | 强一致要求、调用链短 | 业务与消息解耦、可容忍秒级延迟 |

题目发布是后台低频操作，**可靠性 > 实时性**，选 Outbox。事务消息作为备选在附录对比。

**写入**：业务事务内同时写业务表和 `mq_outbox`（同一 PG 事务，天然原子）。

**投递**：`worker-java` 每 500ms 扫一批：

```sql
SELECT id, topic, tag, msg_key, payload
FROM mq_outbox
WHERE status = 0
  AND (next_retry_at IS NULL OR next_retry_at <= now())
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED;
```

```java
for (OutboxRow row : rows) {
    Message msg = new Message(row.getTopic(), row.getTag(),
            row.getMsgKey(), row.getPayload().getBytes(UTF_8));
    try {
        producer.send(msg);
        markSent(row.getId());                 // status=1
    } catch (Exception e) {
        markRetry(row.getId(), backoff(row));  // retry_count+1, next_retry_at
    }
}
```

- `SKIP LOCKED` 支持多实例并行 Relay，不会重复投递。
- 失败指数退避：1s → 2s → 4s … 上限 5min，超过 10 次置 `status=2` 并告警。
- 消费者侧仍需幂等（至少一次语义）。

### 9.6 顺序消息（索引同步必须）

**问题**：同一题连续发布 v2、v3，若消费者乱序处理，v3 的向量可能被 v2 覆盖。

**方案**：

```java
// 生产：按 questionId 选队列
producer.send(msg, (mqs, m, arg) -> {
    int idx = Math.abs(arg.hashCode()) % mqs.size();
    return mqs.get(idx);
}, questionId);

// 消费：顺序监听
@RocketMQMessageListener(
    topic = "qbank-question-event",
    consumerGroup = "cg-index-sync",
    consumeMode = ConsumeMode.ORDERLY,
    consumeThreadMax = 4)
```

- 只对 `index-sync` 用顺序消息；统计、通知类用并发消息，吞吐更高。
- 顺序消费失败会**阻塞当前队列**（`SUSPEND_CURRENT_QUEUE_A_MOMENT`），所以消费逻辑要短、要快，重活异步化。
- 另一层保险：Python 侧 upsert 前比较 `version`，小于已存版本直接丢弃——**业务幂等 + 消息顺序双保险**。

### 9.7 延迟消息

| 场景 | 延迟 | 实现 |
|---|---|---|
| 错题复习提醒 | 1 / 3 / 7 天 | 5.x `setDeliveryTimestamp`（任意时间） |
| 模考超时交卷 | 考试时长 + 5min | 同上 |
| 审核超时提醒 | 24h | 同上 |
| 会员到期（预留） | 按天 | 同上 |

```java
Message msg = new Message("qbank-notify", "review-reminder",
        userId.toString(), payload);
msg.setDeliveryTimestamp(System.currentTimeMillis() + 3L * 24 * 3600 * 1000);
producer.send(msg);
```

> **版本坑**：RocketMQ 4.x 只支持 18 个固定延迟级别，最长 2 小时，做不了"3 天后提醒"。要么用 5.x 的定时消息，要么落一张 `notify_schedule` 表 + 每分钟扫描。**建议直接上 5.x**，这也是本项目选 5.x 的原因之一。

### 9.8 消费幂等

三层防护：

1. **消息去重表**：

```sql
INSERT INTO mq_consume_log (msg_id, consumer_group, status)
VALUES (?, ?, 1)
ON CONFLICT (msg_id, consumer_group) DO NOTHING;
-- 影响行数 = 0 表示已消费，直接 ACK
```

2. **业务幂等**：索引 upsert 按 `question_id` 覆盖写；统计用 `ON CONFLICT ... DO UPDATE`；导入用 `(batch_id, seq)` 唯一键。
3. **Redis 短期去重**：`SETNX consume:{groupId}:{msgId} 1 EX 86400`，挡住重复投递的瞬时冲击。

### 9.9 重试与死信

- 并发消费失败返回 `RECONSUME_LATER`，默认重试 16 次，间隔递增。
- 超过次数进死信 Topic：`%DLQ%cg-index-sync`。
- 死信处理：
  - Dashboard 告警（钉钉/企微）；
  - 后台提供"死信重放"入口（按 msgId 重新投递）；
  - 定期归档死信，避免无限堆积。
- **不要**在消费里吞异常返回 SUCCESS，否则消息静默丢失。

### 9.10 消费线程与背压

| 参数 | 建议 | 说明 |
|---|---|---|
| `consumeThreadMin/Max` | 20 / 64 | 按下游 Python 并发能力调 |
| `consumeMessageBatchMaxSize` | 1（顺序）/ 16（并发） | 顺序消费必须单条 |
| `pullBatchSize` | 32 | 拉取批量 |
| Python 连接池 | 与消费线程匹配 | 避免把 Python 打挂 |

- 监控 `Diff Total`（堆积量）、消费 TPS、消费 RT。
- 堆积告警阈值：> 10 万条或持续 10 min 增长。
- 下游限流：Java 消费者调用 Python 前走 Redis 令牌桶，拿不到令牌则 `RECONSUME_LATER`（延迟重试，避免雪崩）。

### 9.11 与 RabbitMQ 的差异（为什么换）

| 维度 | RocketMQ | RabbitMQ |
|---|---|---|
| 顺序消息 | 原生支持（队列级） | 需单消费者/单队列，弱 |
| 延迟消息 | 5.x 任意时间 | 需插件，或 TTL+DLX 组合 |
| 堆积能力 | 强（磁盘顺序写，亿级） | 一般（内存压力大） |
| 事务消息 | 原生 | 无（需 publisher confirm + 本地表） |
| 路由灵活度 | 一般（Topic+Tag） | 强（Exchange 多种） |
| 运维复杂度 | 中高（NameServer/Broker） | 低 |
| 适用 | 大规模、顺序、延迟、削峰 | 业务路由复杂、量中等 |

本项目要练顺序/延迟/堆积处理，RocketMQ 更合适；代价是运维多学一套。

### 9.12 练手清单

- [ ] Outbox Relay + `SKIP LOCKED` 多实例并发不重投
- [ ] 顺序消息按 `questionId` 分区，验证 v2/v3 不乱序
- [ ] 延迟消息实现 1/3/7 天复习提醒
- [ ] 消费幂等：重复投递 10 次，数据不重复
- [ ] 死信队列：故意抛异常，观察重试 16 次后进 DLQ
- [ ] 堆积演练：停消费者 10 min，恢复后自动追平
- [ ] 消息轨迹排查：按 msgKey 查一条消息的全链路

---

## 10. AI 服务设计

### 10.1 模型路由

| 任务 | 推荐模型档位 | 说明 |
|---|---|---|
| 结构化抽取 | 中档（性价比） | JSON 输出稳定即可 |
| 答案生成 | 高档（推理强） | 准确性优先 |
| 解析生成 | 中高档 | 可读性 + 准确性 |
| OCR | PaddleOCR / 云 OCR | 按图片质量路由 |
| Embedding | bge-m3 | 本地部署，成本低 |
| 答疑对话 | 中高档 + 流式 | 体验优先 |

- 统一 `LLMProvider` 接口，支持 OpenAI 兼容协议，配置化切换。
- 按任务配置 `temperature`、`max_tokens`、超时、重试。
- 失败降级：主模型超时 → 备用模型 → 返回错误并记录。

### 10.2 RAG 检索（pgvector + 全文混合）

```
Query（题干/知识点/用户问题）
  → 结构化过滤（exam_type, kp, status=published）
  → 向量召回 top-20（pgvector）
  → 关键词召回 top-20（PG 全文 / pg_trgm）
  → RRF 融合 → Rerank（bge-reranker）top-5
  → 组装上下文（题目 + 解析 + 知识点摘要）
```

**全文检索的中文问题**：

- PG 自带分词器不识别中文，`to_tsvector('simple', ...)` 只能按空格/标点切。
- 方案 A：装 `zhparser` 或 `pg_jieba` 扩展，建 `zh` 配置：
  ```sql
  CREATE EXTENSION zhparser;
  CREATE TEXT SEARCH CONFIGURATION zh (PARSER = zhparser);
  ALTER TEXT SEARCH CONFIGURATION zh ADD MAPPING FOR n,v,a,i,e,l WITH simple;
  ```
- 方案 B：不装扩展，用 `pg_trgm` 做子串匹配兜底：
  ```sql
  CREATE EXTENSION pg_trgm;
  CREATE INDEX idx_q_stem_trgm ON question USING gin (stem_md gin_trgm_ops);
  SELECT id, stem_md FROM question
  WHERE stem_md ILIKE '%无因管理%' AND status = 'published'
  LIMIT 20;
  ```
- 第一期建议 **pgvector 为主 + pg_trgm 兜底**，中文分词扩展按需再加。

> 关键词检索只做"兜底召回"，主力仍是向量；两者 RRF 融合后 rerank。

### 10.3 Prompt 管理

- Prompt 存 `prompt_template` 表，支持版本、灰度、A/B。
- 变量注入：`{{question}}`、`{{context}}`、`{{knowledge_point}}`。
- 输出校验：JSON Schema 校验失败自动重试 1 次（附错误信息）。
- Prompt 变更记录审计，效果对比看板（准确率/成本/耗时）。

### 10.4 任务编排（Java 驱动）

```
Java 消费者（cg-ai-worker）
  → 读 ai_job（不存在则创建，幂等键 type+bizRef+version）
  → 组装请求（题目 + 检索上下文）
  → POST Python /internal/generate/answer（timeout 60s）
  → 结果写回 Java（草稿区）
  → 更新 ai_job 状态与 token 成本
```

- Python 侧不建任务队列，**并发由 RocketMQ 消费线程数控制**。
- 单题任务短（< 10s），同步 HTTP 足够；整卷 OCR 走导入链路，超时设 5min。
- 限流：Redis 令牌桶，按用户 + 任务类型；超限则 `RECONSUME_LATER` 延迟重试。

### 10.5 评测（离线）

构建 **200 题金标集**（人工标注答案 + 解析要点），每次 Prompt/模型变更跑评测：

| 指标 | 目标 |
|---|---|
| 答案准确率 | ≥ 95% |
| 解析要点覆盖率 | ≥ 85% |
| JSON 解析成功率 | ≥ 99% |
| 平均单题成本 | < ¥0.05 |
| P95 延迟 | < 8s |

---

## 11. API 设计

### 11.1 规范

- RESTful，前缀 `/api/v1`。
- 统一响应：`{code, message, data, traceId}`。
- 错误码：`0` 成功；`4xxxx` 客户端；`5xxxx` 服务端；`6xxxx` 业务。
- 分页：`page`, `size`，返回 `{list, total, page, size}`。
- 认证：`Authorization: Bearer <jwt>`；内部服务 `X-Service-Token` + HMAC。
- 时间：接口层统一 ISO-8601 UTC，前端转本地时区。

### 11.2 核心接口

**题库**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/questions` | 分页查询（多条件） |
| GET | `/admin/questions/{id}` | 详情（含版本） |
| POST | `/admin/questions` | 新建 |
| PUT | `/admin/questions/{id}` | 更新（乐观锁） |
| DELETE | `/admin/questions/{id}` | 软删除 |
| POST | `/admin/questions/{id}/publish` | 发布 |
| POST | `/admin/questions/{id}/generate` | AI 补答案/解析 |
| POST | `/admin/questions/batch` | 批量操作 |

**导入**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/import/batches` | 创建批次（multipart） |
| GET | `/admin/import/batches/{id}` | 批次详情 + 进度 |
| GET | `/admin/import/items` | 待审核条目 |
| PUT | `/admin/import/items/{id}` | 修正条目 |
| POST | `/admin/import/items/accept` | 批量采纳入库 |
| POST | `/admin/import/batches/{id}/retry` | 重试失败项 |

**Agent**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/agent/sessions` | 创建会话（scope=admin/student） |
| POST | `/agent/sessions/{id}/messages` | 发送消息（SSE 返回） |
| GET | `/agent/sessions/{id}` | 会话历史 |
| POST | `/agent/tools/{name}` | 直接调用工具（调试用） |

**练习**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/practice/sessions` | 开始练习 |
| GET | `/practice/sessions/{id}/next` | 下一题 |
| POST | `/practice/sessions/{id}/answers` | 提交答案 |
| GET | `/practice/wrong-book` | 错题本 |
| POST | `/practice/favorites` | 收藏 |

**试卷**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/papers` | 试卷列表 |
| POST | `/papers/{id}/attempts` | 开始模考 |
| PUT | `/attempts/{id}/answers` | 保存答案 |
| POST | `/attempts/{id}/submit` | 交卷 |
| GET | `/attempts/{id}/report` | 成绩报告 |

**统计**

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/stats/overview` | 运营看板 |
| GET | `/admin/stats/questions` | 题目质量 |
| GET | `/analysis/me/overview` | 学员学情 |
| GET | `/analysis/me/knowledge` | 知识点掌握度 |
| GET | `/analysis/me/report` | AI 诊断报告 |

**文件**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/storage/presign/upload` | 获取上传预签名 URL |
| GET | `/storage/presign/download` | 获取私有文件下载 URL |

**运维**

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/ops/index/rebuild` | 重建索引 |
| GET | `/admin/ops/mq/backlog` | 消息堆积 |
| POST | `/admin/ops/dlq/replay` | 死信重放 |

### 11.3 SSE 协议

```
event: token
data: {"delta":"根据"}

event: tool_call
data: {"name":"parse_text_to_questions","status":"running"}

event: card
data: {"type":"question_preview","payload":{...}}

event: done
data: {"messageId":"...","usage":{"tokens":1234}}
```

---

## 12. 非功能设计

### 12.1 性能目标

| 场景 | 目标 |
|---|---|
| 题目详情 | P95 < 100ms |
| 判题 | P95 < 50ms |
| 题库列表（10 万题） | P95 < 300ms |
| Agent 首 token | < 2s |
| 批量导入 100 题 | < 3min |
| 模考交卷判分 | < 2s |
| 消息端到端延迟 | P99 < 2s（非 AI 任务） |

### 12.2 PostgreSQL 专项

| 项 | 约定 |
|---|---|
| 版本 | 16.x，扩展：`vector`、`pg_trgm`、`pg_stat_statements`（可选 `zhparser`） |
| 连接池 | HikariCP 20–30；连接数吃紧时前置 PgBouncer（transaction 模式） |
| 慢查询 | `log_min_duration_statement = 200ms`，`pg_stat_statements` 周度 Review |
| JSONB | 高频筛选字段提列；GIN 索引按需建；避免整表 `->>` 过滤 |
| 分区 | `answer_record` 按月 RANGE 分区（PG 11+ 原生分区） |
| 膨胀 | `answer_record` 写多，关注 autovacuum；定期 `REINDEX CONCURRENTLY` |
| 死锁 | 捕获 `DeadlockLoserDataAccessException`，重试 3 次（幂等操作） |
| 大字段 | `stem_md`/`explanation_md` 用 `TEXT`，查询避免 `SELECT *` |
| 深分页 | 游标分页（`WHERE id < ? ORDER BY id DESC LIMIT ?`） |
| 备份 | `pg_dump` 每日 + WAL 归档，恢复演练每季度 |

**分区示例**

```sql
CREATE TABLE answer_record (
    ...
) PARTITION BY RANGE (answered_at);

CREATE TABLE answer_record_2026_11 PARTITION OF answer_record
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
```

- 分区表主键必须包含分区键：`PRIMARY KEY (id, answered_at)`。
- 用 `pg_partman` 自动建分区、自动归档。

**索引规范**

- 联合索引遵循最左前缀；区分度高的列在前。
- 避免在索引列上做函数运算（`date(created_at)` → 改范围查询）。
- 列表页查询走覆盖索引，`EXPLAIN (ANALYZE, BUFFERS)` 看实际行数。
- 单表索引不超过 6 个，写多读少的表谨慎加索引。

### 12.3 安全

- JWT 短期（2h）+ Refresh Token（7d），Redis 黑名单。
- 密码 bcrypt；登录失败限流；敏感操作二次验证。
- 后台 IP 白名单（可配）。
- SQL 注入：MyBatis 参数化；XSS：Markdown 渲染白名单过滤。
- 文件上传：类型/大小校验、病毒扫描（ClamAV 可选）、私有桶 + 预签名 URL。
- 内部 API：服务间 mTLS 或 HMAC 签名 + 时间戳防重放。
- 数据脱敏：日志不打印手机号/身份证。

### 12.4 合规与版权

- 真题来源需标注；建议只收录**授权/公开**题目，或用户自建私有题库。
- AI 生成内容显著标注"AI 生成"，答案仅供参考。
- 用户数据：隐私政策、注销、导出；未成年人保护（如涉及）。
- 生成内容留存审计，满足监管要求。

### 12.5 可观测

- 链路：`traceId` 贯穿 Java → RocketMQ → Python → LLM，OpenTelemetry 上报。
- 指标：QPS、P95、错误率、LLM token 消耗、**MQ 堆积/消费 RT/重试数/死信数**、**NAS 容量/挂载状态**、导入成功率。
- 日志：结构化 JSON，Loki 收集。
- 告警：错误率 > 1%、堆积 > 10 万、死信 > 0、NAS 使用率 > 85%、挂载失联、LLM 成本日超阈值。

### 12.6 高可用

- 无状态服务多副本，Nginx 负载均衡。
- PostgreSQL 流复制主从 + 定时备份（WAL 归档），读请求可走从库。
- RocketMQ 双 NameServer + Broker 主从；Producer 重试，Consumer 集群消费。
- Redis 哨兵/集群。
- NAS 是单点：RAID + 快照 + 异地备份；应用侧对文件读取失败做降级（返回占位图、不阻塞刷题）。
- 降级策略：AI 服务不可用 → 关闭 AI 入口，保留刷题核心链路；向量检索失败 → 降级为关键词检索；MQ 不可用 → Outbox 积压，恢复后自动补投。

---

## 13. 部署架构（局域网 + NAS 方案 A）

### 13.1 部署前提

| 项 | 约定 |
|---|---|
| 网络 | 纯局域网，无公网入口 |
| 域名 | 内网 DNS：`quest.lan`、`nas.quest.lan`（或终端 hosts） |
| 协议 | 内网 HTTP 即可；HTTPS 需自签/内部 CA，终端要装根证书 |
| 用户 | PC 浏览器 + 手机 Wi-Fi，同一网段（已确认） |
| 外网 | 服务器可出网调用 LLM/OCR（已确认） |
| 不做 | 微信小程序、公网 CDN、ICP 备案 |

> **为什么不能做小程序**：微信要求 HTTPS + 已备案域名 + 公网可达，纯局域网无法满足。移动端用 H5/PWA。

### 13.2 网络拓扑

```
┌─────────────┐   ┌─────────────┐
│  PC 浏览器   │   │  手机 H5    │
└──────┬──────┘   └──────┬──────┘
       │                 │  Wi-Fi（关闭 AP 隔离）
       └────────┬────────┘
                │  千兆 / 2.5G 交换机
       ┌────────┴─────────┐
       │                  │
┌──────▼───────┐   ┌──────▼────────────┐
│ 应用服务器    │   │  NAS              │
│ 192.168.1.10 │   │ 192.168.1.20      │
│ nginx :80    │   │ MinIO :9000       │
│ api-java     │   │ 控制台 :9001       │
│ ai-service   │   │ /volume1/quest-oss │
│ postgres     │   └───────────────────┘
│ rocketmq     │
│ redis        │
└──────────────┘
```

- 应用服务器与 NAS 接同一交换机；**图片直传要求终端也能访问 NAS:9000**。
- NAS 控制台 9001 只给管理员网段。
- 手机连 Wi-Fi 时关闭 AP 隔离（Client Isolation），否则访问不到内网服务。

### 13.3 NAS 侧部署（方案 A：MinIO on NAS）

前置：NAS 支持 Docker（群晖 Container Manager / 威联通 Container Station / TrueNAS）。

1. 新建共享目录 `quest-oss`，配额 1TB 起。
2. Docker Compose：

```yaml
services:
  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: questadmin
      MINIO_ROOT_PASSWORD: "替换为强密码"
      MINIO_SERVER_URL: "http://nas.quest.lan:9000"
    volumes:
      - /volume1/quest-oss:/data
    ports:
      - "9000:9000"
      - "9001:9001"
    restart: always
```

3. 初始化桶（在任意一台有 `mc` 的机器上）：

```bash
mc alias set nas http://nas.quest.lan:9000 questadmin '密码'
mc mb -p nas/quest-public
mc mb -p nas/quest-private
mc anonymous set download nas/quest-public
```

4. NAS 开启每日快照；再用 Hyper Backup / rsync 备份到 USB 硬盘或第二台 NAS。

**注意**：

- NAS 内存建议 ≥ 4GB；MinIO 缓存会占一部分。
- 单节点单盘或 RAID 均可，**不要**在 NAS 上做 MinIO 纠删码多盘模式。
- 关闭 SMBv1 与公网访问；管理账号独立。

### 13.4 应用服务器部署

```yaml
services:
  nginx:
    image: nginx:1.27
    ports: ["80:80"]
    volumes:
      - ./nginx/conf.d:/etc/nginx/conf.d:ro
      - ../frontend/pc-web/dist:/usr/share/nginx/html:ro
      - ../frontend/mobile-agent/dist:/usr/share/nginx/html/m:ro

  api-java:
    build: ../backend/api-java
    environment:
      STORAGE_ENDPOINT: http://nas.quest.lan:9000
      STORAGE_PUBLIC_ENDPOINT: http://nas.quest.lan:9000
    depends_on: [postgres, redis]

  worker-java:
    build: ../backend/worker-java
    depends_on: [postgres, rocketmq-broker]

  ai-service:
    build: ../backend/ai-python
    environment:
      DATABASE_URL: postgresql://ai_ro:...@postgres:5432/quest
      STORAGE_ENDPOINT: http://nas.quest.lan:9000
    depends_on: [postgres]

  postgres:
    image: pgvector/pgvector:pg16
    environment:
      POSTGRES_DB: quest
      POSTGRES_USER: quest
      POSTGRES_PASSWORD: ${PG_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
      - ./postgres/init:/docker-entrypoint-initdb.d:ro

  rocketmq-namesrv:
    image: apache/rocketmq:5.3.1
    command: sh mqnamesrv
    ports: ["9876:9876"]

  rocketmq-broker:
    image: apache/rocketmq:5.3.1
    command: sh mqbroker -n rocketmq-namesrv:9876 -c /home/rocketmq/conf/broker.conf
    volumes:
      - ./rocketmq/broker.conf:/home/rocketmq/conf/broker.conf:ro
      - rocketmq-store:/home/rocketmq/store
    ports: ["10911:10911", "10909:10909"]

  rocketmq-dashboard:
    image: apacherocketmq/rocketmq-dashboard:latest
    environment:
      JAVA_OPTS: "-Drocketmq.namesrv.addr=rocketmq-namesrv:9876"
    ports: ["8082:8080"]

  redis:
    image: redis:7-alpine
    volumes: ["redisdata:/data"]

volumes:
  pgdata:
  rocketmq-store:
  redisdata:
```

- MinIO 不在应用 Compose 里，独立在 NAS 生命周期。
- 内网 DNS 在路由器/NAS 上配置；终端多时不要用 hosts。

### 13.5 Nginx（局域网）

```nginx
server {
    listen 80;
    server_name quest.lan;

    root /usr/share/nginx/html;
    index index.html;

    location / { try_files $uri $uri/ /index.html; }
    location /m/ { try_files $uri $uri/ /m/index.html; }

    location /api/ {
        proxy_pass http://api-java:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    location /agent/ {
        proxy_pass http://ai-service:8000;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_buffering off;          # SSE 必须
        proxy_read_timeout 300s;
    }
}
```

- 局域网可不做 TLS；自签证书要在每台终端装根证书，第一期不建议。
- `proxy_buffering off` 是 SSE 的关键，否则 token 会被攒着一起发。

### 13.6 终端接入

| 端 | 地址 | 说明 |
|---|---|---|
| PC | `http://quest.lan` | 刷题 + 管理 |
| 手机 | `http://quest.lan/m` | 仅 Agent |
| 图片 | `http://nas.quest.lan:9000/quest-public/...` | 公共读桶 |
| 私有文件 | 预签名 URL（15min） | 由 Java 签发 |

- 预签名 URL 里是 `nas.quest.lan`，局域网内能解析即可。
- 手机若连访客 Wi-Fi，通常被隔离，需用主网络。

### 13.7 备份与容量

| 项 | 做法 |
|---|---|
| PG | 每日 `pg_dump` + WAL 归档，保留 14 天 |
| 附件 | NAS 快照每日 + `mc mirror` 到 USB/第二台 NAS |
| 配置 | Compose、Nginx、`.env` 纳入 Git（密钥除外） |
| 容量 | 10 万题 × 2 图 × 150KB ≈ 30GB；加原始文件预留 1TB |
| 演练 | 每季度恢复一次 |

### 13.8 服务器建议

| 规格 | 数量 | 用途 |
|---|---|---|
| 8C16G | 1 | 应用 + AI（不跑本地大模型） |
| 4C8G | 1 | PostgreSQL（可与应用合并） |
| 4C8G | 1 | RocketMQ + Redis（可与应用合并） |
| NAS 4 盘位 | 1 | MinIO + 备份 |

> 全部合并到一台 8C32G 也能跑（学习/小规模），但数据库与 MQ 抢内存，建议至少给 PG 足够 `shared_buffers`。

### 13.9 演进

- 需要外网访问：加 WireGuard VPN（约 2 人天），或公网反代 + 备案。
- 迁云 OSS：改 `storage.endpoint` 与 `path-style=false`，代码不动。
- 多校区：NAS 同步 + 应用多副本。

---

## 14. 工程结构

```
quest-agent/
├── backend/
│   ├── api-java/                 # Spring Boot 主服务
│   │   ├── src/main/java/com/quest/
│   │   │   ├── common/           # 通用：响应、异常、常量
│   │   │   ├── auth/             # 认证授权
│   │   │   ├── question/         # 题库域
│   │   │   ├── paper/            # 试卷域
│   │   │   ├── practice/         # 练习域
│   │   │   ├── stat/             # 统计域
│   │   │   ├── importjob/        # 导入编排
│   │   │   ├── storage/          # ObjectStorage 抽象 + MinIO 实现
│   │   │   ├── mq/               # 生产者/消费者/Outbox
│   │   │   └── internal/         # 内部 API（给 Python）
│   │   └── src/main/resources/
│   ├── worker-java/              # Outbox Relay、定时聚合、延迟补偿
│   └── ai-python/
│       ├── app/
│       │   ├── api/              # 路由：agent/ocr/extract/generate/index
│       │   ├── agent/            # LangGraph 编排、工具
│       │   ├── llm/              # Provider 抽象
│       │   ├── rag/              # 检索、rerank
│       │   ├── parser/           # 切题、OCR、docx/pdf
│       │   ├── indexer/          # 向量化、MD 导出、pgvector 访问
│       │   └── core/             # 配置、鉴权、日志
│       └── tests/
├── frontend/
│   ├── pc-web/                   # Vue3 + TS + Arco Design：刷题 + 管理 + Agent 页
│   ├── mobile-agent/             # Vue3 + TS + Vant 4：仅 Agent 对话
│   └── shared/                   # 类型、API client、SSE、Markdown 渲染
├── deploy/
│   ├── docker-compose.yml
│   ├── nginx/
│   ├── postgres/init/            # 建库、扩展、初始数据
│   ├── rocketmq/
│   └── nas-minio/                # NAS 上的 MinIO compose + 备份脚本
├── docs/
│   ├── AI刷题平台-第一期方案.md
│   ├── api.md
│   └── schema/
└── README.md
```

**存储抽象**（为将来迁云 OSS 留口子）：

```java
public interface ObjectStorage {
    String upload(InputStream in, String key, String contentType);
    String presignUpload(String key, Duration ttl);
    String presignGet(String key, Duration ttl);
    void delete(String key);
    String publicUrl(String key);
}
```

```yaml
storage:
  endpoint: http://nas.quest.lan:9000       # 局域网直连
  public-endpoint: http://nas.quest.lan:9000
  access-key: ${MINIO_ACCESS_KEY}
  secret-key: ${MINIO_SECRET_KEY}
  bucket-public: quest-public
  bucket-private: quest-private
  path-style: true                          # MinIO 必须开启
  presign-ttl: 15m
```

> `path-style=true` 是 MinIO 的硬要求；换云 OSS 时改成 `false`（virtual-hosted style）即可。

---

## 15. 工程量评估与排期

### 15.1 评估口径

- 1 人天 = 1 人 1 个有效工作日（按 6 小时计）
- 含开发自测，不含跨团队等待、不含需求反复
- 三档：乐观（复用充分、AI 顺利）/ 现实 / 悲观（OCR 与 LLM 反复调优）

### 15.2 WBS 分解

| # | 模块 | 主要内容 | 人天 | 主责 |
|---|---|---|---|---|
| 0 | 产品与设计 | 需求细化、原型、UI（PC 双端 + 移动）、验收 | 18 | PM/UI |
| 1 | 基础设施 | 服务器、NAS/MinIO、PG、RocketMQ、Nginx、CI、备份 | 14 | Java/运维 |
| 2 | Java 后端 | 认证 RBAC、题库、导入审核、练习判题、试卷模考、统计、MQ、存储、内部 API | 54 | Java |
| 3 | Python AI | Provider/Prompt、切题抽取、OCR、答案解析、RAG、Agent、索引、评测 | 52 | Python |
| 4 | PC 前端（Arco） | 工程骨架、刷题、错题学情、模考、题库编辑器、审核台、试卷、统计、Agent 页 | 48 | 前端 |
| 5 | 移动端（Vant） | 登录、会话、自研对话流、题目卡片、答题判分、解析、适配 | 14 | 前端 |
| 6 | 测试上线 | 集成测试、AI 评测、压测、UAT、上线 | 18 | QA |
| | **合计** | | **218** | |

> 对话组件自研已计入：PC 的 Agent 页约 5 人天，移动端约 6 人天（含 SSE、消息模型、Markdown、卡片）。若还要语音输入、图片批注、多轮引用定位，另加 3–5 人天。

### 15.3 三档估算

| 档位 | 人天 | 说明 |
|---|---|---|
| 乐观 | 185 | 组件复用高、LLM 一次调通、OCR 场景干净 |
| 现实 | 218 | 按上表 |
| 悲观 | 255 | OCR 多种版式、AI 答案反复、审核台交互重做 |

### 15.4 团队与工期

| 角色 | 人数 | 投入人天 |
|---|---|---|
| Java | 1 | 54 |
| Python/AI | 1 | 52 |
| 前端 | 1.5 | 62 |
| 测试 | 0.5 | 18 |
| 产品/UI | 0.5 | 18 |
| 合计 | 4.5 FTE | 204（基础设施 14 由 Java 兼） |

- 12 周 × 5 天 × 4.5 人 = 270 人天容量，需求 218，利用率约 81%，留出联调与返工。
- **现实排期 12 周**；建议加 1 周缓冲（13 周）应对 AI 调优。
- 若只有 3.5 人（前端 1），约 15–16 周。

### 15.5 关键路径

```
题库模型 → 题库 CRUD → 导入链路（文本）→ AI 答案解析 → 审核台 → 试卷 → 统计
                     ↘ 索引同步（pgvector/MD）↗
```

- 前端关键路径：题库编辑器 → 审核台 → 试卷管理。
- 移动端可并行：W8 后 Agent 网关稳定即可开工，不阻塞主线。
- **AI 质量是最大不确定项**，W6 就要开始跑金标集，不要留到最后。

### 15.6 里程碑（12 周）

| 周次 | 交付 | 验收 |
|---|---|---|
| W1 | 基础设施、PG/RocketMQ/NAS、登录权限 | 图片能传能取，消息能发能收 |
| W2 | 题库 CRUD、知识点树、PC 骨架 | 手工录题、发布、预览 |
| W3 | 刷题闭环（练习/判题/错题） | 学员可完整刷一套 |
| W4 | 题库编辑器、图片直传 | 富文本题目可用 |
| W5 | 文本导入 + 审核台 | 100 题导入准确率 ≥ 90% |
| W6 | 索引同步 + 后台 Agent 页 | 发布 5s 内可检索；对话可导入 |
| W7 | OCR 图片导入 | 清晰截图 ≥ 92% |
| W8 | AI 答案/解析 + 移动端开工 | 金标集准确率 ≥ 95% |
| W9 | 真题试卷、模考判分 | 整卷导入 + 模考闭环 |
| W10 | 统计看板、学情 | 核心指标正确 |
| W11 | 移动端 Agent 完成、智能分析 | 对话刷题闭环 |
| W12 | 压测、UAT、上线 | P95 达标，试用 7 天 |

### 15.7 成本估算

**硬件（若需新购）**

| 项 | 估算 |
|---|---|
| NAS 4 盘位 + 4×4TB | ¥5,000–7,000 |
| 应用服务器 8C16G | ¥6,000–9,000 |
| UPS + 2.5G 交换机 | ¥1,500 |
| 合计 | **¥12,500–17,500** |

**AI 调用（按 DeepSeek 量级）**

| 项 | 单价 | 1 万题 | 10 万题 |
|---|---|---|---|
| 答案解析生成 | ¥0.02–0.05/题 | ¥200–500 | ¥2,000–5,000 |
| OCR（云） | ¥0.01–0.02/页 | ¥100–200 | ¥1,000–2,000 |
| Embedding | 本地免费 | — | — |

**人力**：218 人天。按外包 ¥800–1,500/人天估算，约 ¥17–33 万；自研按内部成本另算。

**持续**：0.5 人维护 + AI 调用费 + 电费。

### 15.8 敏感性

| 变更 | 增量 |
|---|---|
| 移动端也要传统刷题页 | +6–8 人天 |
| 完全离线（本地 LLM） | +30% 工期，硬件 +¥15k 起 |
| 需要外网访问（VPN） | +2 人天 |
| 题库 > 50 万 / 多校区 | +10 人天（分区、ES 预研） |
| 小程序端 | 不可行（局域网限制），需先公网化 |

### 15.9 不建议压缩的部分

- **审核台**：这是 AI 质量的唯一闸门。
- **索引一致性巡检**：否则"发布了搜不到"会反复出现。
- **金标评测集**：没有它，Prompt 改动就是盲改。
- **备份与恢复演练**：NAS 是单点，丢数据不可接受。

---

## 16. 风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| OCR 准确率不足 | 导入质量差 | 规则 + LLM 纠错；低置信度强制人工；云 OCR 兜底 |
| LLM 答案幻觉 | 答案错误 | 多采样投票 + RAG + 强制审核 + 金标评测 |
| 题库版权 | 法律风险 | 只收授权/公开来源；用户自建题库；标注来源 |
| 成本失控 | 预算超支 | 分级模型、缓存、批量、日限额告警 |
| 双栈协作成本 | 联调慢 | 契约优先（OpenAPI）、内部 API 稳定、Mock |
| **NAS 单点故障** | 图片/附件不可用 | RAID + 快照 + 异地备份；应用降级占位图 |
| **终端访问不到 NAS** | 图片裂图、上传失败 | 关闭 Wi-Fi AP 隔离；同网段；图片走应用代理兜底 |
| **局域网不能出网** | LLM/OCR 不可用 | 确认出口；不可出网则本地模型，工期 +30% |
| **移动端只有 Agent** | 部分用户想按列表刷题 | 先看数据；第二期补传统刷题页 |
| **NAS 容量写满** | 新题无法上传 | 配额与 85% 告警；冷数据归档 |
| **RocketMQ 运维复杂** | 环境不稳定 | 先用单机 Compose；Dashboard 监控；死信告警；文档化启停 |
| **消息重复消费** | 数据重复/统计翻倍 | 去重表 + 业务幂等 + Redis SETNX |
| **索引不一致** | 检索不到新题 | Outbox + 顺序消息 + 巡检 + 手动重建 |
| **PG 大表膨胀** | 查询变慢 | 分区、autovacuum 调参、物化视图、ClickHouse 预案 |
| 审核人力不足 | 上线慢 | 高置信度批量通过；审核工作量看板 |

---

## 17. 附录

### 17.1 枚举字典

| 枚举 | 取值 |
|---|---|
| `question.type` | `single_choice`, `multiple_choice` |
| `question.status` | `draft`, `pending_review`, `published`, `archived` |
| `answer.source` | `manual`, `imported`, `ai_generated` |
| `import_batch.status` | `parsing`, `extracting`, `reviewing`, `done`, `failed` |
| `paper.paper_type` | `real`, `mock`, `custom` |
| `exam_attempt.status` | `ongoing`, `submitted`, `timeout` |
| `practice.mode` | `chapter`, `random`, `wrong`, `favorite`, `paper` |
| `mq_outbox.status` | `0` 待投递, `1` 已投递, `2` 失败放弃 |

### 17.2 知识点编码规范

```
{考试类型}/{模块}/{子模块}/{序号}
GK  = 公基（事业单位）
XC  = 行测
例：GK/FL/MF/0003  公基/法律/民法/第 3 个知识点
    XC/YY/LC/0012  行测/言语/逻辑填空/第 12 个知识点
```

- 编码一经使用不复用；废弃知识点置 `status=0`。
- 知识点树最多 4 级，叶子节点挂题。

### 17.3 单题 MD 示例（完整）

```markdown
---
id: 10001
type: multiple_choice
answer: [A, C]
difficulty: 4
kp: [XC/PD/TL/0021]
source: 2023年国考副省级
year: 2023
status: published
ai_confidence: 0.93
---

# 下列关于行政处罚的说法，正确的有：

A. 行政处罚由违法行为发生地的行政机关管辖
B. 限制人身自由的行政处罚权只能由公安机关行使
C. 违法行为在二年内未被发现的，不再给予行政处罚
D. 行政处罚决定书应当当场交付当事人

## 答案
A、C

## 解析
**【考点】** 行政处罚的管辖、时效与程序

**【解析】**
A 正确：……

**【易错点】** B 项注意"法律另有规定"的例外。
```

### 17.4 关键决策记录（ADR）

| 决策 | 结论 | 理由 |
|---|---|---|
| 关系库 | **PostgreSQL 16** | JSONB + pgvector + 物化视图，一库覆盖题库全部形态；少一个组件 |
| 向量库 | **pgvector（库内）** | 与题目同事务、同备份，无跨存储一致性问题 |
| 消息队列 | RocketMQ 5.x | 顺序/延迟/堆积能力，符合练手目标 |
| 消费端位置 | 全部 Java | Python RocketMQ 客户端弱；Java 统一事务/幂等/监控 |
| 一致性 | 本地消息表 + Relay | Broker 不进核心链路，可重放可审计 |
| 题目存储 | JSONB + 结构化列 | 兼顾灵活与可索引 |
| 谁写题库 | 仅 Java | 单一写入方，防双写；Python 只写索引表 |
| AI 内容发布 | 强制审核 | 控制错误答案风险 |
| 后台与 Agent | 独立路由 + 独立服务 | 迭代解耦、权限隔离 |
| 对象存储 | NAS + MinIO | 无云 OSS；S3 接口保证可迁移 |
| NAS 接法 | **方案 A（MinIO on NAS）** | 不挂 NFS，少一层故障点；应用只走 S3 |
| 部署形态 | **纯局域网** | 无公网域名/备案；移动端用 H5 而非小程序 |
| PC 端 | **二合一单应用** | 共用登录/组件，部署简单；路由级拆分控制权限 |
| 移动端 | **仅 Agent** | 对话即刷题，避免重复做一套表单页 |
| PC 组件库 | **Arco Design Vue（最新 2.x）** | 中后台组件完整、TS 友好；团队指定 |
| 移动组件库 | **Vant 4** | Arco 无官方 Vue 移动库；Vant 触控组件成熟 |
| 对话组件 | **自研** | 不引聊天 SDK；SSE/消息模型/卡片可控，便于接题目卡片与工具调用 |

### 17.5 PostgreSQL vs MySQL（选型复盘）

| 能力 | PostgreSQL 16 | MySQL 8.4 | 本项目影响 |
|---|---|---|---|
| JSON | JSONB + GIN | JSON + 生成列/多值索引 | 题目本体大量 JSON，PG 明显更顺手 |
| 向量 | pgvector + HNSW | 无（需外挂） | PG 省掉 Qdrant |
| 全文 | tsvector + GIN（中文需 zhparser） | FULLTEXT + ngram | 中文分词两者都要额外配置 |
| 物化视图 | 原生 | 无 | 题目质量统计 PG 更省事 |
| Upsert | `ON CONFLICT` | `ON DUPLICATE KEY UPDATE` | 都可 |
| 分区 | 声明式 | RANGE/LIST/HASH | 都可，PG 更灵活 |
| 运维熟悉度 | 需学习 | 团队可能更熟 | MySQL 的唯一优势项 |

**结论**：本项目选 PG。若团队 MySQL 经验明显更强、且愿意接受 Qdrant/ES 外挂，MySQL 方案同样成立，差的不是"能不能"，是"少一个组件"。

### 17.6 RocketMQ 事务消息 vs 本地消息表

| 维度 | 事务消息 | 本地消息表 |
|---|---|---|
| 发送时机 | 先发半消息，再执行本地事务 | 本地事务内写表，异步投递 |
| Broker 故障影响 | 无法提交（半消息发不出） | 无影响，恢复后补投 |
| 消息不丢 | 依赖 Broker 回查 | 依赖 Relay 重试 |
| 实现成本 | 低 | 中 |
| 可观测/可重放 | 弱 | 强 |
| 适用 | 强一致、低频、短链路 | 业务解耦、允许秒级延迟 |

> 若未来某条链路要求"业务成功则消息必达且立即可见"，可局部切事务消息；当前题目发布用 Outbox 足够。

### 17.7 第二期展望

- 判断题、不定项、填空题
- 智能组卷（约束求解）
- IRT 能力评估 + 预测分
- 学习计划与打卡
- 移动端传统刷题页（若对话刷题数据证明需要）
- 小程序 / App（需先公网化）
- 题库市场与多人协作
- 主观题 AI 批改（申论）

---

*文档结束。如需配套的 OpenAPI 契约、PostgreSQL 初始化脚本、RocketMQ Topic 创建脚本、NAS 部署脚本或前端页面原型，可在本方案基础上继续细化。*
