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

| POST | `/internal/pdf-inspect` | [pdf_import.py](../backend/ai-python/app/api/pdf_import.py) |

| POST | `/internal/public-import` | [public_import.py](../backend/ai-python/app/api/public_import.py) |

| POST | `/internal/extract` | [routes.py](../backend/ai-python/app/api/routes.py) |

| POST | `/internal/syllabus-inspect` | [syllabus.py](../backend/ai-python/app/api/syllabus.py) |

| POST | `/internal/syllabus-review` | [syllabus.py](../backend/ai-python/app/api/syllabus.py) |

| POST | `/internal/syllabus-embed` | [syllabus.py](../backend/ai-python/app/api/syllabus.py) |

## 流式与身份

Java公开Coach SSE事件为state/delta/tool_status/reset/done及error；Python内部还有sources/suggestions/tool_audit，Java校验后保存或审计，不能原样透传网页全文。业务会话/资料所有者从Java已鉴权身份取得，不接受自由用户ID绕过。Java仅三个精确资料POST及陪练`workflow-step`使用对应HMAC过滤器，其余internal默认拒绝。Python新图另发内部`workflow_state`与`workflow_result`，Java读取权威状态并消费终态，不将这两个事件原样传给浏览器。
