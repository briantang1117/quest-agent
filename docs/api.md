# 接口契约与实现范围

本文件定义跨服务约定；实际已实现接口以各模块路由和测试为准。完整一期接口列表见原方案 §11，不能将列表当作当前可调用接口。

## Java 通用响应

```json
{"code":0,"message":"成功","data":{},"traceId":"请求链路标识"}
```

业务接口前缀 `/api/v1`，时间使用 ISO-8601 UTC。分页 `{list,total,page,size}`。生产业务接口需要 `Authorization: Bearer <jwt>`。健康探针遵循 Spring Actuator 原生格式，不套业务 envelope。

错误分层：4xxxx 请求或权限、5xxxx 服务故障、6xxxx 业务规则。HTTP 状态也必须正确，不把所有失败返回 200。

## 服务间 HMAC

Python `/internal/*` 不对浏览器开放。Java 调用时发送：

| Header | 值 |
|---|---|
| `X-Service-Token` | 独立服务身份 token |
| `X-Timestamp` | UTC Unix 秒的十进制字符串 |
| `X-Nonce` | 每次请求唯一的随机字符串 |
| `X-Signature` | 以下签名的小写十六进制结果 |

```text
bodyHash = SHA256(实际发送的原始请求字节).hexdigest()
canonical = timestamp + "\n" + nonce + "\n" + UPPER(method) + "\n" + path + "\n" + bodyHash
signature = HMAC-SHA256(INTERNAL_HMAC_SECRET, canonical).hexdigest()
```

path 为路由路径，不含 host。JSON 序列化后再签名，不能接收方重排 JSON 后验签。修改请求体、方法或路径均应拒绝。5 分钟有效窗口，nonce 在窗口内不得复用。多副本必须使用共享 Redis 原子 nonce 存储；当前进程内防重放不能作为多副本部署的充分条件。

## Agent SSE 目标协议

业务路径最终为 `POST /api/v1/agent/sessions/{id}/messages`，当前架构底座不宣称已打通此链路。浏览器用 fetch POST，不用 EventSource；无自动重试，用户主动重试避免重复付费。

```text
event: token
data: {"delta":"根据"}

event: tool_call
data: {"name":"parse_text_to_questions","status":"running"}

event: card
data: {"type":"question_preview","payload":{}}

event: done
data: {"messageId":"...","usage":{"tokens":1234}}

```

客户端应正确处理 UTF-8 分包、CRLF、空行、注释和多行 data；中途断流与收到 done 不同，不能静默标记回答成功。

## 题目不变量

- `single_choice` / `multiple_choice`；2–8 个选项，连续唯一 A–H。
- 题干去空白后非空，最多 5000 字符。
- 单选恰好一个答案，多选至少两个，答案必须为选项子集且不重复。
- 至少一个有效知识点编码；难度为整数 1–5。
- AI 结果强制草稿；只有 Java 审核/发布流程可以发布。
- 多选正确性按集合一致判断，顺序不影响，少选/多选均不算全对；领域判分器已预留显式 `partialScore` 参数，未配置少选分时应传 0，具体试卷评分策略由后续业务接入。
