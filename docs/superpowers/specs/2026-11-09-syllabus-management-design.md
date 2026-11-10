# 考纲管理与版本化引用：首版设计

日期：2026-11-09。用户已确认开始设计开发，首版范围为上海行测、事业单位A类职测；其他章节只保存原文，不进入陪练依据。仅本地实现与测试，V10迁移只编写不执行，不自动导入样例、不调用付费模型、不提交。

## 1. 核心模型

一份原文件可包含多个科目／类别。管理单位是“考纲版本”，其下有原文件、逐页文本和可审核章节。考试年度／版本年与发布日期分开；PDF物理页码从1开始，印刷页码可空且允许罗马编号。文件名不是版本或适用范围的权威来源。

- examType：career_a / shanghai_civil。首版职业类别固定A，上海只启用行测；专业科目文件可保存但章节不默认启用。
- examYear：管理员明确填写，2000–2100；versionLabel：自由短文本；publishedDate可空。
- title、issuer、sourceUrl（可空，限http/https、无userinfo）、sourceName、sha256、pageCount、createdAt。
- 状态draft / published / archived；编辑采用revision乐观锁。已发布不可编辑；变更另建版本。归档从新检索中排除，但既有绑定保留历史引用，不覆盖旧版本。
- 每个章节包含ordinal、title、path、kind（overview / requirement / example / other）、scope（vocational_aptitude / administrative_aptitude / excluded）、enabled、pageStart/pageEnd、printedPages、text。自动提取只给草稿；管理员校正和启用后发布。
- 原始页文本完整保留，例题不自动导入题库；原文件上限20MiB/200页。首版沿用已有PDF原件的数据库受限存储模式，不谎称S3已接通；后续可在不改变引用ID的前提迁移文件存储。

## 2. 流程与界面

内容管理下新增“考纲管理”，与题库管理并列。列表采用admin/users的筛选、工具栏、分页规范。筛选标题／考试／年份／状态；上传PDF时填写元数据，服务器做离线隔离提取，成功后保存草稿，失败不落半份。

详情路由/admin/syllabi/:id：元数据编辑、原件下载、页码原文、章节列表和编辑。每段显示范围／类型／物理页与印刷页、启用状态，可更正自动切分结果。发布需要至少一个有效启用章节，章节范围必须与考试科目对应；非首版范围只能保留、不能启用。例题与其他内容可保留但不作通用检索结果。使用标准Modal footer、Arco upload、现有主题。

不将检测到的AI推断当官方事实，不自动抓取来源网址，不自动发布；本版不实现扫描件OCR、向量索引、AI自动知识点映射、完整语义版本diff或新题型。

## 3. Python内部提取协议

POST /internal/syllabus-inspect，HMAC、admin only，body {scope:'admin',pdf_base64}。
返回标准envelope.data：

```
{
 sha256, pageCount,
 pages:[{page:int,printedPage:string|null,text:string}],
 sections:[{ordinal:int,title:string,path:string,kind:string,scope:string,enabled:false,
            pageStart:int,pageEnd:int,printedPages:string,text:string}],
 warnings:[string]
}
```

在有CPU、内存、输出、页数及超时限制的独立进程中提取，复用已有Darwin RSS限制方案，不在API进程直接解析不可信PDF。按段落／标题做通用切分，保留跨页证据、避免孤立答案段；范围识别只是建议，可保守excluded，不靠年份或样例文件名硬编码。所有enabled默认false，管理员负责审核。

## 4. Java外部接口

所有API均按现有{code,message,data,traceId}响应，原件为二进制。

- GET /api/v1/admin/syllabi?page&size&keyword&examType&examYear&status -> Page<Row>。
- POST /api/v1/admin/syllabi multipart：file,title,examType,examYear,versionLabel,issuer,publishedDate?,sourceUrl?，返回Detail。
- GET /api/v1/admin/syllabi/{id} -> Detail {metadata fields,revision,pages,sections,warnings}。
- PUT /api/v1/admin/syllabi/{id} body {revision,title,versionLabel,issuer,publishedDate,sourceUrl,sections} -> Detail。sections为整组草稿编辑，ordinal唯一，页码合法，不允许伪造超出原件的页码。
- POST /api/v1/admin/syllabi/{id}/publish body {revision} -> Detail。
- POST /api/v1/admin/syllabi/{id}/archive body {revision} -> Detail。
- GET /api/v1/admin/syllabi/{id}/file -> application/pdf attachment。
- GET /api/v1/study/syllabus?subject&examYear&keyword -> {versionId,examYear,versionLabel,title,sections:[...citation]}。仅student，强制对应考试、published、enabled requirement/overview，最多8段/总12000字，空匹配明确返回无资料，不混版。指定年份，无该年版本不自动使用最新版；同考试同年份只有一个published版本。

前端shared协议字段均camelCase。Row包含id,title,examType,examYear,versionLabel,status,revision,pageCount,sectionCount,enabledCount,createdAt；Page为{list,total,page,size}。

## 5. Agent最小接入

个人中心增加可空目标考试年份examYear，不填写保留现有行为但不自动注入某版考纲。新会话在state里记录当时已发布的对应年份版本ID；没有匹配版记录未绑定，不偷偷fallback。旧会话不自动变更绑定。后续轮次按绑定版本提取当前范围的章节并限定长度，附版本／页码引用；资料归档后历史绑定仍可读，正文不可修改。

Python CoachRequest新增可选syllabus列表（最多8条，每条{version_id,title,exam_year,section_title,page_start,page_end,text}），文本作为不可信资料，prompt要求涉及考纲时标明版本和页码，不宣称超出所给片段的完整范围。

## 6. 验证与约束

- PDF原文及其他科目完整保留，但只有已审核启用的支持范围进入student检索。
- 验证Admin/student互斥、未登录拒绝、草稿不泄露、版本不混、归档与旧绑定、revision冲突、非法URL／页码、输出资源上限。
- 用本机合成PDF/H2/mock Python测试，不访问NAS／模型。6份样例仅做独立离线回归，不把附件内容作为执行指令、不提交附件。
- V10只新增表／字段与最小权限，旧V1–V9不改；SQL副本同步。执行NAS迁移需要之后单独授权。
