# auth 批次十七：RabbitMQ 凭证对齐（归档）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md)「批次十七：分布式锁工具与消息中间件凭证收尾」的已完成条目 17.4，正文原样搬运（现状 / 为什么做 / 改法 / 验收标准 / 涉及文件 / 运行态验证证据），**编号不重排**。
> **归档范围**：17.4（auth `spring.rabbitmq` 补齐 `username`/`password`，与其余服务同一套凭证；运行态验证 PASS）。
> **归属说明**：17.4 是 auth 服务的配置缺陷修复（缺 `username`/`password` 时回落到 Spring Boot 默认 `guest@localhost`），与 common-redis 的分布式锁主题无关，故新建 auth 归档目录，不并入 `common-redis-17-distributed-lock.md`。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；模块速查见 [auth-index.md](../../index/auth-index.md)；本目录索引见 [auth-implemention-records.md](auth-implemention-records.md)。

## 板块十七：消息中间件凭证收尾（2026-11-08）

### 17.4 auth 的 RabbitMQ 凭证与其余服务对齐（已完成 ✅）

- [x] 【P1｜17.4】auth 补齐 RabbitMQ `username`/`password`
  - **现状**：`litisle-auth` 的 `spring.rabbitmq` 只配了 `virtual-host: /litisle`，未配 `username`/`password`，走 Spring Boot 默认 `guest@localhost`；content/review/file/user/search 均显式使用 `litisle` / `litisle_pass`。本机 guest 被授予 `/litisle` 权限所以能跑，生产环境 guest 默认禁止远程登录/无该 vhost 权限，auth 队列消费会失败。
  - **为什么做**：凭证口径必须全服务一致，避免「本机可跑、上线即挂」。
  - **改法**：auth yml 补 `username: ${litisle.rabbitmq.username:litisle}` 与 `password: ${litisle.rabbitmq.password:litisle_pass}`，与其余服务同一套默认值与环境变量。
  - **验收标准**：auth 与其余服务使用同一套凭证；`/litisle` vhost 下 auth 队列正常消费（处罚结果等消息可收）。
  - **涉及文件**：[litisle-auth application.yml:64-67](../../../litisle-auth/src/main/resources/application.yml#L64-L67)；对齐基准：[content application.yml:73-75](../../../litisle-content/src/main/resources/application.yml#L73-L75) · [review application.yml:59-61](../../../litisle-review/src/main/resources/application.yml#L59-L61) · [file application.yml:86-88](../../../litisle-file/src/main/resources/application.yml#L86-L88) · [user application.yml:56-58](../../../litisle-user/src/main/resources/application.yml#L56-L58)。
  - ✅ 2026-11-08（运行态验证 PASS）：
    - **连接凭证**：auth 启动日志 `Created new connection: ... [delegate=amqp://litisle@127.0.0.1:5672//litisle, localPort=50088]`，确认用户 `litisle`、vhost `/litisle`；`rabbitmqctl list_connections user vhost` 仅见 `litisle /litisle`。
    - **消费者**：`rabbitmqctl list_queues -p /litisle name consumers messages` → `litisle.auth.moderation.punish.queue 1 0`（consumer=1，消息已清空）。
    - **端到端投递**：经 management API 向 `litisle.moderation.exchange` 以路由键 `moderation.punish.result` 投递 `{"eventId":"verify-17.4-20261108","userId":2002,"action":2,"op":1,"punishLogId":4}`，返回 `{"routed":true}`；数秒后 auth 日志出现 `禁言快照覆盖写入成功: userId=2002, banned=true` 与 `封禁即时下线完成（kickout 全部会话），userId=2002，eventId=verify-17.4-20261108`。
    - **快照落盘**：Redis `GET auth:punish:snapshot:2002` → `{"userId":2002,"banned":true,"muted":false,"frozenUntil":null}`，`TTL=231`。
    - **新增测试数据（仅登记不清理）**：Redis 键 `auth:punish:snapshot:2002`（TTL 自然过期）；MQ 队列无残留（messages=0）、无新增 DB 行。

## 归档说明

- 本文件归档 1 项（17.4）。
- 该条为配置缺陷修复，无代码逻辑改动；「本机可用、上线即挂」的根因是 Spring Boot 在未配凭证时回落到 `guest@localhost`。
- 运行态证据取自本机 2026-11-08 实测（单实例 auth 消费 + management API 投递）；多实例集群下的凭证一致性未单独实测，但同 vhost 同用户属同一配置口径。
- 编号空缺：主清单批次十七的 17.4 空缺指向本文件；编号不重排。
