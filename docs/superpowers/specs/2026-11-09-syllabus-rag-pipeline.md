# 考纲 AI 整理与异步向量索引增量

2026-11-09 用户明确要求补完整流程，不停留在离线提取。只本地实现测试，不执行V10/V11、不连接NAS或真实付费供应商、不自动提交。

## 流程与状态

上传离线提取成功保存草稿及持久化review任务→后台有界异步AI整理核对→草稿章节建议／问题→管理员启用并发布→事务内排队index任务→后台切片Embedding并写PG→index ready后学员向量召回。failed可由Admin明确重试，进程重启恢复queued和过期processing，不自动重试已发出的失败模型请求。状态分开reviewStatus/indexStatus=not_started/queued/processing/ready/failed；error安全固定信息，attempt独立，旧lease不可覆盖新任务。

## 内部接口（Java→Python）

1. POST /internal/syllabus-review admin HMAC：
`{scope:'admin',examType,sections:[{ordinal,title,path,kind,scope,enabled:false,pageStart,pageEnd,printedPages,text}]}` 最多20段、每段<=12000、总文本<=24000字。
返回envelope.data `{sections:[{ordinal,title,path,kind,scope,enabled:false,pageStart,pageEnd,printedPages,text}],issues:[string]}`。
只让模型提议每个原ordinal的title/path/kind/scope及issues，不接受生成正文/页码。Python合回原文和页码，强制enabled false，ordinal集合完全相同。模型输出不合规明确失败不重试。examType对应career_a/shanghai_civil，仅受支持范围可建议非excluded，仍需管理员启用，不自动发布。

2. POST /internal/syllabus-embed admin或student HMAC：
`{scope:'admin'|'student',texts:[string]}` 1..8条、每条<=3000、总<=12000字。
用现有ArkEmbeddingProvider逐条调用，无付费自动重试；一次请求内必须模型/维度一致、数值有限且非零，维度由真实配置/响应校验而非写死1024。返回envelope.data `{model:string,dimensions:int,vectors:[float[]]}`。模型标识包含配置model，模型升级必须重建，不跨model/dim比较。密钥不入日志，Python不写数据库。

## Java持久化与检索

新增V11（只写不执行）包含syllabus_job、syllabus_chunk，任务对应versionId+类型+revision，包含status、leaseToken、leaseUntil、进度／安全error、model/dim等。PG向量使用pgvector `vector`，不要动旧question_embedding(1024)。MVP对单版本有界候选精确余弦排序即可，不伪造HNSW，WHERE先限定版本/subject/model/dim/enabled。

上传后自动排review任务；编辑与processing任务互斥，revision变化时旧任务不可覆盖；发布审核结果不自动enabled。发布后原子排index任务；切片<=1200字、适当重叠<=150且保留章节页码，Embedding input精确对应chunk文本。唯一缓存键用内容hash+model+dimensions，未变化可复用；模型配置变更主动重建并原子切换新generation，旧索引不混用。ready之前不返回半套结果。

Admin POST /admin/syllabi/{id}/retry-review 与 /rebuild-index body {revision}；已有活动任务返回409。Detail/Row增reviewStatus,indexStatus,reviewError,indexError,indexedCount,chunkCount,embeddingModel,embeddingDimensions；后台轮询单详情有界，离开清理。原件下载仍binary。

student get /study/syllabus keyword为空用于浏览章节；keyword非空或Agent context查询若ready用向量检索；否则明确retrievalMode=keyword,indexStatus，不宣称向量就绪。无资料不换年。embedding服务异常可有标记地keyword降级，不隐藏错误；Python拿不到私人内容。query向量model/dim必须匹配索引否则拒绝混用并提示重建。有界返回8段/12000字，保留版本/section/page。

## 本地验证

模拟Provider+H2、本地Postgres如可用只能新隔离临时库且不读取NAS配置；没有本地PG则明确pgvector SQL真实执行待验收。覆盖上传任务→AI保原文→确认发布→异步向量→问句召回、失败重试、过期lease恢复、版本/科目/模型维度过滤、ready原子性、无模型无假成功。不要把测试替身余弦实现当真实PG验证。
