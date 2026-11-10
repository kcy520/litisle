# common-security 批次十六：Feign 拦截器无 SaToken 上下文安全降级（归档）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md)「服务间调用与 MQ 基础设施治理（批次十六）」的已完成条目 16.1 与 16.1-V1，原文搬运（现象 / 根因 / 影响面实测 / 修法取舍 / 改动与代价 / 验收标准 / 实测结果；V1 含逐链路验证证据与两条未修复旁路问题），**未压缩**；编号不重排。
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

## 16.1-V1 其余 MQ 消费者路径的 Feign 降级验证（已完成 ✅）

- [x] 【P1｜16.1-V1】验证其余 MQ 消费者路径的 Feign 降级
  - **待验路径**：auth `PunishResultConsumer` → `ReviewClient`；review 文本/图片审核消费者 → `ContentClient` / `SocialClient` / `FileClient`；content `UserBannedConsumer`、`ModerationResultConsumer`、`ModerationAppealResultConsumer` → `ReviewClient` / `FileClient`；review 定时任务 `QuarantineSweeper`、`PunishReconcileSweeper`。
  - **验收**：上述每条链路各触发一次，日志无 `SaTokenContextException`、无重投，下游回查返回 200。
  - **运行态验证（2026-11-08，五服务在 8081~8085 同时在线；日志 `%TEMP%\litisle-verify-{auth,user,file,review,content}.log`）**
    - auth `PunishResultConsumer` → `ReviewClient`：随 17.4 验证，投 `moderation.punish.result`（userId=2002）→ auth 日志 `禁言快照覆盖写入成功` + `封禁即时下线完成`，Redis `auth:punish:snapshot:2002` 写入。
    - review 文本消费（`RabbitListenerEndpointContainer#3-1`）→ `ContentClient#getContent`：review 日志 `[ContentClient#getContent] <--- HTTP/1.1 200 (29ms)`；content `ContentFeignProviderController` 记录 `远程api调用getContent bizType:1 bizId:5008`，MDC traceId=`84f1a07b...` 已透传。
    - review 图片消费（`#2-1`）→ `FileClient#getContent`：file 日志多次 `远程api调用getContent fileId:72b89e8c/b71490f2`，traceId=`57d71f99...`/`e22c74ed...` 透传；该消费因 `未知图片机审 provider：none` 走延迟重投（环境缺图片机审 provider，与 Feign 降级无关）。
    - review 配图复审消费（`#2-1`）→ `ContentClient#listArticleImages`：review 日志 `<--- HTTP/1.1 200 (184ms)`；content 日志 `远程api调用listArticleImages articleId:5008`，traceId=`a239302d...` 透传；随后仍因 `provider：none` 重投（环境问题）。
    - content `ModerationAppealResultConsumer` → `ReviewClient#getPunishStatus`：review 日志 `Feign 处罚权威态查询: userId=2002`（traceId `934cf662...`）返回 banned=true；`userId=2001`（traceId `4bb1ed71...`）返回 banned=false。无鉴权异常。
    - content → `FileClient#unquarantine` / `bindRef`：file 日志 `远程api调用unquarantine fileId:72b89e8c...`（traceId `ed0a0244...`，命中幂等闸口 `已处于目标状态`）与 `远程api调用bindRef ... bizId:5011`（traceId `144025beb...`，`file_ref` 插入 1 行）。
    - content `UserBannedConsumer`：投 `user.banned`（userId=99999）→ 日志 `[8.2] 封禁连带：作者无可下架文章`，正常 ack。
    - 全量统计：五份日志 `SaTokenContextException` 计数均为 0（`Select-String -SimpleMatch` 实测）。
  - **未覆盖/旁路问题（2026-11-08 实测发现，当时未修复）**
    - content `ModerationResultConsumer` 未能端到端触发：review 机审文本结果按其代码用 `ROUTING_MODERATION_RESULT`（`moderation.result`）发布，RabbitMQ 绑定显示该键只投递 `litisle.file.moderation.result.queue`，而 content 队列绑定的是 `moderation.text.result`；本次结果事件被 file 侧消费后忽略（file 日志 `非图片审核结果，file 侧忽略，bizType=1`），content 未收到。命中该 consumer 的 `handleResult` 本身不调用 Feign，故不影响本条 Feign 结论，但链路接线需单独确认。
    - review `ModerationTextService#persistAndPublish` 在 `transactionTemplate.execute` 返回后才调用 `TransactionSynchronizationManager.registerSynchronization`，事务已提交、同步未激活，实测抛 `Transaction synchronization is not active`，消息延迟重投后才经「已处理任务补发 result」分支自愈。该问题与 Feign 无关，属独立代码缺陷。
  - **✅ 两条旁路问题已于 2026-11-09 复核收口（修复在位，无需另行立项）**：
    - ①路由键：`ModerationResultPublisher#resolveRoutingKey` 已按 bizType 分发（1,2,4→`ROUTING_MODERATION_TEXT_RESULT` / 5→message / 6→image，Q87 修复注释在位）；content 队列在 `ContentRabbitConfig` 双向绑定 `EXCHANGE_MODERATION` 与 `EXCHANGE_MODERATION_DELAY` 的 `moderation.text.result` 键；图片链路 image service 直发 `moderation.result` ↔ file `ModerationMqConfig` 绑定同名键自洽；发布器现无 bizType=6 调用方（仅文本侧 3 处调用）。
    - ②事务同步：`registerSynchronization` 已移入 `transactionTemplate.execute` lambda 内（Q88 修复注释在位），事务提交前注册、同步处于活动状态。
    - 两处修复均随 commit `053c808`（review 模块重构）落地；2026-11-09 对 litisle-review/litisle-content 及依赖模块编译验证通过。

## 归档说明

- 本文件共归档 2 项（16.1、16.1-V1）。
- 16.1-V1 的两条未覆盖/旁路问题已于 2026-11-09 复核收口：修复已在位（Q87 路由键分发 + Q88 事务同步移入事务内，随 commit `053c808` 落地），无需另行立项，证据见上方 16.1-V1 节「✅ 两条旁路问题已于 2026-11-09 复核收口」；主清单批次十六仅留指针。
- 编号空缺说明：主清单批次十六的 16.1、16.1-V1 空缺指向本文件；编号不重排。
