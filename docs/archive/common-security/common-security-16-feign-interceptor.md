# common-security 批次十六：Feign 拦截器无 SaToken 上下文安全降级（归档）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md)「服务间调用与 MQ 基础设施治理（批次十六）」的已完成条目 16.1，原文搬运（现象 / 根因 / 影响面实测 / 修法取舍 / 改动与代价 / 验收标准 / 实测结果），**未压缩**；编号不重排。
> **归属说明**：缺陷本体在 `litisle-common/common-security` 的 Feign 拦截器，修复只改这一个类；影响面跨 search/user/auth/review/content/file 六个服务的 MQ 与定时任务线程，但代码归属是 common-security，故新建 `docs/archive/common-security/` 按模块归档。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；模块速查见 [common-index.md](../../index/common-index.md)；本目录索引见 [common-security-implemention-records.md](common-security-implemention-records.md)。

> 编号说明（迁自 next_update.md）：本条为跨服务基础设施缺陷（common-security 的 Feign 拦截器 + MQ 消费线程），不属于内容结构调整，独立登记为批次十六，使用 `16.1`，不与 15.x 混排。

## 16.1 Feign 拦截器在无 SaToken 上下文线程抛异常，导致索引同步消费重投 / 进 DLQ

- [x] 【P0｜16.1】`FeignAuthInterceptor` 无 SaToken 上下文安全降级
  - **现象**：search 消费 `user.profile-updated` 报 `SaTokenContext 上下文尚未初始化`，消息延迟重投，`user_idx` 始终无文档。
  - **根因**：[FeignAuthInterceptor.java:78-85](../../../litisle-common/common-security/src/main/java/com/litisle/commonsecurity/feign/FeignAuthInterceptor.java#L78-L85) 直接调用 `StpUtil.isLogin()`；MQ 消费线程、定时任务线程天然没有 SaTokenContext，`StpUtil` 在此类线程必然抛 `SaTokenContextException`。服务间 Feign 调用本不应依赖「调用线程有登录态」。
  - **影响面实测（2026-11-08，只读）**：
    - 索引与源表：`article_idx` 0 / 源表 `article` 10（已发布 7）；`book_idx` 0 / 源表 `book` 5（未删 4）；`user_idx` 0 / 源表 `sys_user` 10；`article_chunks` 0。三条索引全空，影响面不止 user。
    - 走 Feign 的 MQ / 定时路径（代码链路）：search `IndexSyncConsumer` 六类事件 → `ContentClient` / `UserClient`；auth `PunishResultConsumer` → `ReviewClient`；review 文本/图片审核消费者 → `ContentClient` / `SocialClient` / `FileClient`；content `UserBannedConsumer`、`ModerationResultConsumer`、`ModerationAppealResultConsumer` → 回写服务内 `ReviewClient` / `FileClient`；review 定时任务 `QuarantineSweeper`（`FileClient`）、`PunishReconcileSweeper`（`AuthClient`）。file `ModerationResultListener` 走 `SeaweedFsClient`（HTTP，非 Feign），不受影响。
    - DLQ：Broker 重启后队列未持久化，历史积压无法回溯；修复前各队列与 DLQ 实测均为 0，日志仅见 user 队列 retry 1/5~5/5（11:38:26~12:20:46），未见 5/5 入 DLQ 行。修复后全队列 0、DLQ 0。
    - 日志：`SaTokenContext` 仅出现在 search 的 `user.profile.updated.index.queue`（其余消费者未被触发，见 16.1-V1）。
    - **环境级阻塞（非本条代码缺陷）**：本机 `localhost:9200` 被 Cpolar 占用，ES 容器无宿主端口映射，search 连 ES 报 `Unrecognized response's content-type text/plain`。验证时用临时 nginx stream 代理（`9201 → litisle-elasticsearch:9200`）+ 启动参数 `--litisle.es.hosts=localhost:9201`，验证后已删除代理容器并释放 9201。
  - **两条修法取舍**：
    1. **（已采用）拦截器内无上下文降级**：仅捕获 `SaTokenContextException`，命中即只透传 traceId 后返回。改动 1 个类；上下文存在时（HTTP 请求线程）行为完全不变；不伪造用户身份；下游 `/api/feign/**` 已排除登录校验，外部不可达。
    2. 服务间固定凭证（mTLS / HMAC / 内部服务 token）：鉴权更强，但需新增凭证分发、轮换与全链路配置，涉及所有 Feign 调用方与提供方，改动面与运维成本显著更大，本轮不选。
    3. 全局关闭 / 移除 Feign 拦截器：不可取，会丢失 HTTP 请求线程的用户身份透传并削弱下游鉴权。
  - **降级后受影响的调用范围**：无上下文线程里的 Feign 调用不再携带 userId / username / roles / permissions / token，下游按匿名内部调用处理。当前所有此类调用都是回查内部数据（`/api/feign/**`），不依赖用户身份；HTTP 请求线程内发起的调用不受影响（上下文存在时照旧注入）。
  - **改动 / 代价**：仅 [FeignAuthInterceptor.java:68-85](../../../litisle-common/common-security/src/main/java/com/litisle/commonsecurity/feign/FeignAuthInterceptor.java#L68-L85)（新增 `SaTokenContextException` 捕获 + 降级注释）；无 DDL、无配置、无调用点改动。
  - **验收标准**：MQ 消费线程发起 Feign 不再抛 `SaTokenContextException`；`user.profile-updated` / `content.published` / `content.book.changed` 三条链路索引文档正确写入且 `updateTime` / 笔名与源表一致；消息不重投、不进 DLQ；HTTP 请求线程的登录身份透传行为不变。
  - **实测结果（2026-11-08）**：
    - `user_idx`：改笔名（`林间有风-验FIX`）后 12:28:55 写入成功（含此前失败消息的恢复重投）；改回 `林间有风` 后 12:29:25 再次写入。ES 文档 `penName=林间有风`（HEX `E69E97E997B4E69C89E9A38E`）、`updateTime=2026-11-08T12:29:25`，与 `sys_user_profile.update_time` 一致；`sys_user.update_time` 恒为 `04:22:08`（仅临时改登录密码所致，笔名更新未触碰）。
    - `article_idx`：投递 `content.published`（bizType=1, bizId=5001）→ 12:32:28 `文章发布写入完成：articleId=5001`，`_count=1`；`article_chunks` 0（正文 length=54 < min=200，按设计不入库）。
    - `book_idx`：投递 `content.book.changed`（bookId=6101）→ 12:32:27 `书籍索引写入完成：bookId=6101`，`_count=1`。
    - 全队列 0、DLQ 0；search 日志无新增 `SaTokenContext` 异常。
  - ✅ 2026-11-08：修复 + search 三条链路运行态验证通过；`mvn -pl litisle-common/common-security -am compile` 与 `mvn -pl litisle-user,litisle-search,litisle-content,litisle-auth -am package -DskipTests` 均通过。

## 归档说明

- 本文件共归档 1 项（16.1）。
- **未归档**：16.1-V1（验证其余 MQ 消费者路径的 Feign 降级，`[~]`）仍有待验路径，正文保留在主清单。
- 编号空缺说明：主清单批次十六的 16.1 空缺指向本文件；编号不重排。
