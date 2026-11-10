# 考纲管理与版本化引用 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox tracking.

**Goal:** 管理多年份考纲原件及章节，审核后按考试／年份／科目向陪练提供可追溯资料。
**Architecture:** Python独立进程离线提取，Java唯一写入与版本事实源，PC沿用现有后台列表／详情；不新增向量依赖。首版同时覆盖上传、审核、发布、查询及Agent按年份绑定的最小闭环。
**Tech Stack:** Java27/Spring Boot4/JdbcTemplate、Python3.12/FastAPI/Pydantic/pypdf、Vue3/Arco/Pinia。
**Spec:** ../specs/2026-11-09-syllabus-management-design.md

## Global Constraints

- 上海行测、事业单位A类职测可启用，其余原文保留，不进陪练；页码／年份不可混淆。
- 仅本地测试；V10只写不执行；不调用真实模型，不写NAS，不自动提交样例或代码。
- 保留现有dirty工作区，不更改已执行V1–V9与路由缓存机制。
- 公共考纲Admin审核发布；student只读有范围的资料，不扩大Admin私有访问权限。

## Review Focus

- 合并文档内科目标题、类别标题／示例题／页码噪声导致误启用，必须保守默认false并允许管理员更正。
- 同考试同年份并发发布与编辑revision冲突，由事务及数据库约束防止混版。
- 归档不影响历史会话绑定版本的引用，新会话不能自动fallback其他年份。
- PDF文件解析资源上限／原件下载授权，不把真实样例当可执行脚本。
- UI新增菜单路由必须同步精确auth-policy与后端白名单，不破坏原菜单和缓存。

### Task 1: PDF离线提取

Files: app/parser/syllabus_*.py、app/api/syllabus.py、app/main.py、tests/test_syllabus*.py。
Produces: /internal/syllabus-inspect，返回Spec camelCase字段；CoachRequest支持可选syllabus资料。
- [x] 合成多科目／跨页／印刷页码／资源上限测试先失败。
- [x] 独立进程提取、结果严格校验、HMAC admin接线。
- [x] pytest全量、ruff检查与format通过；样例只做离线人工核对。

### Task 2: Java版本管理与查询

Files: syllabus包、V10及deploy副本、PublicAiClient、SessionSecurityFilter、StudentCoachService，测试SyllabusTest。
Consumes: Task1协议。
Produces: Spec列出外部API；偏好examYear可空与会话固定版本引用。
- [x] H2接口、权限、版本、章节、revision、归档、会话绑定测试先失败。
- [x] 表／事务管理／上传提取／查询及上下文接线，SQL只写不执行。
- [x] Java clean verify；确认迁移副本一致与旧迁移无改动。

### Task 3: PC后台与个人中心

Files: shared/syllabus-api.ts、AdminSyllabiView.vue、AdminSyllabusDetailView.vue、router、auth-policy、AdminLayout、ProfileView，相关tests。
Consumes: Task2 DTO和API。
- [x] 菜单可达、筛选、上传标准Modal、审核编辑、发布／归档、原件、个人年份测试先失败。
- [x] 沿用用户管理样式、Arco upload，详情独立页面。
- [x] lint/typecheck/test/format/build纯本地通过。

### Task 4: 集成与记录

- [x] 三端字段及版本绑定核对，六份案例离线提取无付费调用。
- [x] 一次独立受限审查，重要问题补回归并修复。
- [x] 更新能力台账与考纲验收文档，区分本地通过／真实环境未验收。

## 执行记录

- 用户已明确开始设计开发；继续在现有功能分支保留全部未提交改动，不额外worktree避免丢失开发基线。
- 默认实现选择：首版原件沿用PG受限存储，不新接S3；不做OCR／向量／语义diff；不放开专业科目。

- 完成：Python449项、Java116项（common15/API99/worker2）、前端254项及静态检查／构建通过，V10副本与9条SQL语法核验通过。
- 复核修复：自然语言“介绍考纲范围”整句不匹配，补测试RED→GREEN，同版本概览兜底，不放宽科目或年份。
- 运行限制：V10未执行，目标白名单未开放10；部署前必须另获授权迁移，当前不要重启API到未迁移数据库。
- 限制：仅文本PDF、规则章节／关键词检索，非向量RAG；尚未真实浏览器上传／PG／模型验收。六案例只读离线结果见考纲验收文档。
