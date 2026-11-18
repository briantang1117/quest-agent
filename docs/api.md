# 接口目录

更新：2026-11-11。实现事实依据当前源码、依赖清单和测试核对；不以旧文档推定运行状态。

所有业务普通JSON使用 `{code,message,data,traceId}`，下载与SSE除外。具体字段以Controller DTO及shared协议为准。本目录从当前路由声明整理，不宣称已经真实环境联调。

## UserController

源码：[UserController.java](../backend/api-java/src/main/java/com/quest/api/auth/UserController.java)

| 方法  | 路径                                      |
| ----- | ----------------------------------------- |
| POST  | `/api/v1/auth/login`                      |
| GET   | `/api/v1/auth/me`                         |
| POST  | `/api/v1/auth/logout`                     |
| POST  | `/api/v1/auth/change-password`            |
| GET   | `/api/v1/admin/users`                     |
| POST  | `/api/v1/admin/users`                     |
| PATCH | `/api/v1/admin/users/{id}/status`         |
| POST  | `/api/v1/admin/users/{id}/reset-password` |

## AiCallAuditController

源码：[AiCallAuditController.java](../backend/api-java/src/main/java/com/quest/api/coach/AiCallAuditController.java)

| 方法 | 路径                          |
| ---- | ----------------------------- |
| GET  | `/api/v1/admin/ai-calls`      |
| GET  | `/api/v1/admin/ai-calls/{id}` |

## StudentCoachController

源码：[StudentCoachController.java](../backend/api-java/src/main/java/com/quest/api/coach/StudentCoachController.java)

| 方法   | 路径                                       |
| ------ | ------------------------------------------ |
| GET    | `/api/v1/me/study-preference`              |
| PUT    | `/api/v1/me/study-preference`              |
| GET    | `/api/v1/coach/sessions`                   |
| POST   | `/api/v1/coach/sessions`                   |
| GET    | `/api/v1/coach/sessions/{id}`              |
| DELETE | `/api/v1/coach/sessions/{id}`              |
| POST   | `/api/v1/coach/sessions/{id}/turns`        |
| POST   | `/api/v1/coach/sessions/{id}/turns/stream` |

## LearningMaterialController

源码：[LearningMaterialController.java](../backend/api-java/src/main/java/com/quest/api/materials/LearningMaterialController.java)

| 方法 | 路径                                    |
| ---- | --------------------------------------- |
| POST | `/api/v1/coach/sessions/{id}/materials` |
| GET  | `/api/v1/study/materials`               |
| GET  | `/api/v1/study/materials/{id}`          |
| POST | `/api/v1/study/materials/{id}/retry`    |
| GET  | `/api/v1/study/materials/{id}/download` |

下载默认返回 Markdown；`?format=pdf` 返回本地排版的 A4 PDF，均只允许本人已完成资料，使用附件响应与 `private, no-store`。PDF转换不调用模型，具体支持范围与字体配置见 [Python说明](../backend/ai-python/README.md)。

## MaterialInternalController

源码：[MaterialInternalController.java](../backend/api-java/src/main/java/com/quest/api/materials/MaterialInternalController.java)

| 方法 | 路径                               |
| ---- | ---------------------------------- |
| POST | `/internal/materials/outbox/claim` |
| POST | `/internal/materials/outbox/ack`   |
| POST | `/internal/materials/execute`      |

## CoachWorkflowInternalController

`POST /internal/coach/workflow-step`为Python图的受限Java业务工具入口。需要内部HMAC及Java签发的短期轮次授权，不能作为浏览器接口。步骤、输入和幂等约束详见[编排技术说明](做题Agent编排技术说明.md)。

## PublicBankController

源码：[PublicBankController.java](../backend/api-java/src/main/java/com/quest/api/publicbank/PublicBankController.java)

| 方法   | 路径                                               |
| ------ | -------------------------------------------------- |
| GET    | `/api/v1/admin/public-banks`                       |
| GET    | `/api/v1/admin/public-banks/{id}`                  |
| POST   | `/api/v1/admin/public-banks`                       |
| POST   | `/api/v1/admin/public-banks/{id}/imports`          |
| GET    | `/api/v1/admin/public-banks/{id}/imports`          |
| POST   | `/api/v1/admin/public-banks/{id}/pdf-imports`      |
| GET    | `/api/v1/admin/public-banks/{id}/documents`        |
| GET    | `/api/v1/admin/public-banks/{id}/questions`        |
| GET    | `/api/v1/admin/public-banks/{id}/question-summary` |
| POST   | `/api/v1/admin/public-questions/{id}/withdraw`     |
| DELETE | `/api/v1/admin/public-questions/{id}`              |
| POST   | `/api/v1/admin/public-banks/{id}/publish`          |
| POST   | `/api/v1/admin/public-documents/{id}/start`        |
| GET    | `/api/v1/admin/public-documents/{id}/file`         |
| GET    | `/api/v1/admin/public-imports/{id}`                |
| GET    | `/api/v1/admin/public-questions/{id}`              |
| PUT    | `/api/v1/admin/public-questions/{id}`              |
| POST   | `/api/v1/admin/public-questions/{id}/confirm`      |
| POST   | `/api/v1/admin/public-questions/{id}/publish`      |

## SyllabusController

源码：[SyllabusController.java](../backend/api-java/src/main/java/com/quest/api/syllabus/SyllabusController.java)

| 方法 | 路径                                       |
| ---- | ------------------------------------------ |
| GET  | `/api/v1/admin/syllabi`                    |
| POST | `/api/v1/admin/syllabi`                    |
| GET  | `/api/v1/admin/syllabi/{id}`               |
| PUT  | `/api/v1/admin/syllabi/{id}`               |
| POST | `/api/v1/admin/syllabi/{id}/publish`       |
| POST | `/api/v1/admin/syllabi/{id}/archive`       |
| POST | `/api/v1/admin/syllabi/{id}/retry-review`  |
| POST | `/api/v1/admin/syllabi/{id}/rebuild-index` |
| GET  | `/api/v1/admin/syllabi/{id}/file`          |
| GET  | `/api/v1/study/syllabus`                   |

## SystemController

源码：[SystemController.java](../backend/api-java/src/main/java/com/quest/api/system/SystemController.java)

| 方法 | 路径                  |
| ---- | --------------------- |
| GET  | `/api/v1/system/info` |

## Python内部能力

均由Java经内部鉴权调用；不对浏览器开放。`GET /health/live`为存活检查，Swagger/OpenAPI路由关闭。

| 方法 | 路径 | 定义 |
| ---- | ---- | ---- |

| POST | `/internal/coach-workflow` | Python LangGraph练习流程，协议见[编排技术说明](做题Agent编排技术说明.md) |

| POST | `/internal/coach-turn` | [coach.py](../backend/ai-python/app/api/coach.py) |

| POST | `/internal/coach-route` | [coach.py](../backend/ai-python/app/api/coach.py) |

| POST | `/internal/coach-explain` | [coach.py](../backend/ai-python/app/api/coach.py) |

| POST | `/internal/material-generate` | [materials.py](../backend/ai-python/app/api/materials.py) |
| POST | `/internal/material-pdf` | [materials.py](../backend/ai-python/app/api/materials.py) |

| POST | `/internal/pdf-inspect` | [pdf_import.py](../backend/ai-python/app/api/pdf_import.py) |

| POST | `/internal/public-import` | [public_import.py](../backend/ai-python/app/api/public_import.py) |

| POST | `/internal/extract` | [routes.py](../backend/ai-python/app/api/routes.py) |

| POST | `/internal/syllabus-inspect` | [syllabus.py](../backend/ai-python/app/api/syllabus.py) |

| POST | `/internal/syllabus-review` | [syllabus.py](../backend/ai-python/app/api/syllabus.py) |

| POST | `/internal/syllabus-embed` | [syllabus.py](../backend/ai-python/app/api/syllabus.py) |

## 流式与身份

Java公开Coach SSE事件为state/delta/tool_status/reset/done及error；Python内部还有sources/suggestions/tool_audit，Java校验后保存或审计，不能原样透传网页全文。业务会话/资料所有者从Java已鉴权身份取得，不接受自由用户ID绕过。Java仅三个精确资料POST及陪练`workflow-step`使用对应HMAC过滤器，其余internal默认拒绝。Python新图另发内部`workflow_state`与`workflow_result`，Java读取权威状态并消费终态，不将这两个事件原样传给浏览器。

## PrivateQuestionController（学生本人）

- `POST /api/v1/coach/attachments`：认证multipart附件上传；不会触发OCR/导入。
- `GET /api/v1/coach/attachments/{id}/file`：本人原件下载。
- `GET /api/v1/study/private-imports/{id}`：本人任务实际进度与逐题结果。
- `POST /api/v1/study/private-imports/{id}/actions`：`retry/cancel`。
- `POST /api/v1/study/private-items/{id}/decision`：携带`sessionId`与`use_suggestion/keep_original/exclude`。
- `GET /api/v1/study/private-questions`：本人科目/状态/关键词/分页查询。

Coach轮次新增可选`attachments`原件ID列表；会话返回附件元数据和`privateImports`卡片；助手消息通过可选`privateImportId`关联任务，前端将进度卡固定内嵌在该AI回复内。创建任务与聊天决策走受控业务动作。Python签名内部接口`POST /internal/private-source`、`POST /internal/private-check`仅处理计算，不写业务表。需要V16；详见[私人导入](私人题目池与Agent导入.md)。


陪练失败回复可包含可选的 `retryText`（非空字符串，最多 2000 字），用于前端末尾失败回复的手动重试入口。客户端用新 `requestId`、`action=chat` 发送该文本；已保存作答的失败只重试讲解，不重放 `answer`。重试属于新的模型调用，正常计量；不会因页面刷新或错误自动触发。旧会话无该字段时，客户端仅兼容已有的固定失败文案。


学习资料卡片随 AI 消息内联显示。创建通知可携带 `materialId` / `materialNotice`；资料返回可选 `sourceMessageId`，从创建时不可变快照中恢复历史位置。顶部按钮创建会在同一事务内写入一条关联资料的 assistant 通知，不调用聊天模型；前端创建后读回会话。消息已裁剪的历史资料在“更早的学习资料”中展示，不追加到最新消息下方。无需数据库升级。
