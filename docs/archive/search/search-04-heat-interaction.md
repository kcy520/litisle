> **归档说明**：新建板块文件（批次三，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次三：互动热度链路」节，正文原样搬运（含 ✅ 摘要与偏差说明）。
> **来源**：[next_update.md](../../next/next_update.md) 批次三；升级路径见 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md)。

## 板块四：互动热度链路（批次三，2026-11-05 归档）

### search模块

#### 批次三：互动热度链路（依赖批次二；对齐批次D D2 热度部分，A5/A7 定案）

##### 3.1 热度事件消费与待刷新键累加

- [x] 消费 `social.liked` / `social.commented`：仅 `HINCRBY` 累加 Redis 热度待刷新键 like/comment 增量（不直写 ES，A7：带 1024 维向量约 4KB 整文档重写写放大）；消费 `content.view-count-flushed`：`HSET` 覆盖写 view_cnt 绝对值（A5）；**ACK 后置纪律**：Redis 累加成功才 ACK，失败 nack 重试防热度漂移；pending 键常量 `SearchBizConstant.HEAT_PENDING_KEY_PREFIX` + hash 字段 view_cnt/like_delta/comment_delta，TTL 走 `heat.flush.pending-key-ttl`（2h 兜底）
  - ✅ 2026-11-04：新建 `HeatPendingService` / `HeatPendingServiceImpl`——key=`search:heat:pending:{articleId}`，`view_cnt` 用 HSET 覆盖写绝对值（可重放）、`like_delta`/`comment_delta` 用 HINCRBY 累加；每次写入刷新 `heat.flush.pending-key-ttl`（2h 兜底，正常由 3.2 GETDEL 取走）；Redis 异常包装 `IllegalStateException` 触发延迟重试，保证「累加成功才 ACK」
  - ✅ 2026-11-04：新建 `mq/HeatEventConsumer`（三个监听：QUEUE_SOCIAL_LIKED / QUEUE_SOCIAL_COMMENTED / QUEUE_CONTENT_VIEW_COUNT_FLUSHED）——复用批次二已验证的 `MqTraceSupport` + `MqAckHelper.executeWithRetry` + 延迟重投拓扑（routingKey=队列名），不新增拓扑
  - ✅ 2026-11-04：幂等口径——点赞/评论自增不可重入，消费前置 `MqIdempotentHelper.tryAcquire(queue, eventId)`（SETNX）拦重复消息；先占位后累加，优先保证 ACK 失败重投时「同一 eventId 不重复计数」（代价：占位成功但累加失败会少计一次，属宁漏勿重的可接受范围）；浏览量 HSET 绝对值天然幂等，不做 eventId 去重
  - ✅ 2026-11-04：非文章互动（bizType≠1，如评论点赞）debug 日志后 ack 丢弃，不占用重试次数；viewCount 缺失属发送侧契约违约，warn 后丢弃
  - ✅ 2026-11-04：3.2 已同日完成（见下方 3.2 条目），批次三整体收口

##### 3.2 IndexHeatFlushJob 定时刷写

- [x] 定时任务（`heat.flush.interval-ms` 默认 15min，fixedDelay 不堆积）：pending 键原子取出聚合 → 按 `batch-size`(1000)×`concurrency`(2) 分片 `_bulk` partial update 重算 heat（update+doc 只改 heat 字段禁 index 整篇替换；heat = (w_like×like + w_comment×comment + w_view×view) × 时间衰减，权重/半衰期外置 `litisle.search.weight.heat.*`，默认 2/3/0.1/168h）；不动向量；ES 抖动仅跳过本轮；互动热度更新失效对应热点查询缓存
  - ✅ 2026-11-04：新建 `job/IndexHeatFlushJob`（`@Scheduled` fixedDelay=interval-ms、首次延迟 initial-delay-ms）——SCAN 扫 pending 键（禁用 KEYS）→ 逐键原子取走 → `mget` 回查现有文档 → 按 A5 公式重算 → 按 batch-size 分片、concurrency 固定线程池并行 `_bulk` 局部更新；线程池停机 `@PreDestroy` 释放
  - ✅ 2026-11-04：**GETDEL 语义落地方式**——pending 键为 Hash，Redis 原生 GETDEL 仅支持 String，故用 Lua `HGETALL + DEL` 原子脚本（`HeatPendingService#takePending`）；避免「读出后、删除前」被 3.1 消费端新写入的增量被覆盖丢失
  - ✅ 2026-11-04：写回字段为「计数基数 + heat」（like_cnt/comment_cnt/view_cnt/heat）——`update + action(doc)` 局部更新，绝不 index 整篇替换以保住 vec_dense/content；偏差说明：清单原文「只改 heat 字段」，但 heat 公式依赖计数绝对值，且 mapping 中三计数本是 heat 计算基数（A5），故一并写回，仍属热度相关字段、不触发向量重写
  - ✅ 2026-11-04：衰减基准取文档 `updateTime`（当前实现中兼具发布时间语义，批次二建索引时由 content publishTime 赋值）；缺失时按不衰减(1.0)，半衰期做 0/负值下限防御
  - ✅ 2026-11-04：容错闭环——空键/无有效增量跳过不写 ES；单键取走或解析异常只跳过该键；回查或 `_bulk` 整体异常 → 已取走增量 `mergeBack` 回灌 Redis（点赞/评论 HINCRBY 追加、浏览量 putIfAbsent 保留新值）等下一轮，不静默丢数；`_bulk` 逐条失败单独回灌 + error 留痕，成功条目照常生效
  - ✅ 2026-11-04：`LitisleSearchApplication` 补 `@EnableScheduling`；`litisle.search.weight.heat.*`（like=2/comment=3/view=0.1/time-decay-half-life=168h）与 `heat.flush.*` 沿用既有 SearchProperties + yml（注释同步为「批次三 3.2 已落地」）
  - ⏳ 遗留批次四：热点查询缓存失效仅留带注释接入标记（`IndexHeatFlushJob` 内），本批次不提前实现缓存（状态详见批次九 9.3：已接入 evictAll，标注待清理）✅ 2026-11-05 已落地：`IndexHeatFlushJob.java:160` 在刷写成功后调用 `HotQueryCacheService#evictAll`，本条 ⏳ 关闭。
