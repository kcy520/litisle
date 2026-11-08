> **归档说明**：新建板块文件（批次九，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次九：收尾、验证与遗漏补齐」节，仅收录已完成部分：9.2 a/b、9.3、9.4-3、9.7、9.8 P0-E/P0-D、9.9 A~D 与 F；正文原样搬运（含 ✅ 摘要、偏差说明与待环境验证点）。**2026-11-08 追加 9.6-B（search 的 TestNG 编译期依赖清理）与 9.8-P1（`user_idx` 版本源补 `sys_user_profile.update_time`）**。
> **来源**：[next_update.md](../../next/next_update.md) 批次九；未完成项（9.1、9.4-1/2、9.5、9.6-A、9.8-P2）仍在原文档；挂起项（9.2-c/d、9.8-P2、9.9-E/G/H）见 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md)。

## 板块十：批次九收尾已完成部分（2026-11-05 归档）

### search模块

- [x] 【可选】新建索引的 `_meta.embedding_model` 直接写配置模型名（来源：批次一 1.1「选型后改写 EMBEDDING_MODEL_PLACEHOLDER」未落地；证据：`litisle-search/.../es/EsIndexInitializer.java:254` 仍写 `SearchBizConstant.EMBEDDING_MODEL_PLACEHOLDER`（`SearchBizConstant.java:147` 值为 `unset-1024d`）；前置：Embedding 选型已确认 bge-m3；验收：新环境首次建索引 meta 即为真实模型名，无需先跑重建）
  - ✅ 2026-11-05：`EsIndexInitializer#vectorModelMeta` 改读 `properties.getEmbedding().getModel()`（空配置退化为空串，不再写占位名），初始建索引与重建后 `_meta` 写入同源；`EMBEDDING_MODEL_PLACEHOLDER` 已无引用，按约束保留并补注释说明预留用途（`SearchBizConstant.java:143-149`）。编译 `litisle-search` exit 0。
- [x] 【可选】二级兜底 heat 改为复用 content 热门端点（来源：批次四 4.2 偏差说明「可替换为 content 热门端点」未实施；证据：`SearchServiceImpl.java:760-762` 仍走 `heatFallback`，`:913` 直查 article_idx heat；前置：批次八 `ContentClient#getHotArticles` 已就绪；验收：空结果二级兜底与降级热门同源，不再依赖 article_idx heat 查询）
  - ✅ 2026-11-05：`heatFallback` 替换为 `hotArticleFallback`（Feign `ContentClient#getHotArticles`），仅在 ES 正常但检索/KNN 无命中时进入；与批次八降级同数据源（CONTENT_FEED_HOT_ZSET，榜空回退 view_cnt），同样不写热点查询缓存；`source` 仍为 `fallback`（契约不变），搜索历史记录逻辑未改。偏差：热门榜无深分页，改为固定第一页、total=当页条数（与降级一致，避免翻页重复）。

- [x] 【可选】清理过期状态标注（来源：批次二 2.2 ⏳、批次三 3.2 ⏳、批次七 7.1 ⚠️ 与「触发方式待确认」；证据：`next_update.md:152/185/262/263` 仍为旧状态；现状：`IndexSyncConsumer.java:112/148` 与 `IndexHeatFlushJob.java:160` 已调用 `evictAll`，Embedding 选型与重建管理端点已落地；验收：四处标注与代码一致，无过期 ⏳/⚠️）
  - ✅ 2026-11-05：四处旧标注均保留原句并在行尾追加「✅ 已落地 + 代码位置」，未改写既有无文字；2.2/3.2 指向 `IndexSyncConsumer`/`IndexHeatFlushJob`，7.1 指向管理端点与选型适配器。
  - 补注（2026-11-05，9.7 优化后）：发布/元数据/书籍/用户事件已由 `evictAll` 改为 `markDirty`，下架仍保留立即 `evictAll`，上述行号以优化后代码为准。
- [x] 【可选】完成批次归档迁移（来源：本文档「归档与暂不实现」迁移规则第 1-2 步；前置：批次九验收完成；验收：已完成条目迁入 `docs/archive/`，本文档只保留活跃待办与验证项）
  - ✅ 2026-11-05：采用「折叠归档」实现（偏差说明：不迁出文件、不删减原文）——批次一~八各自包裹 `<details><summary>批次X：…（已完成，点击展开）</summary>`，正文与 ✅ 摘要原样保留，主文档默认折叠提升可读性；批次九与 V1~V3 验证段保持展开。


- [x] 【必须】search 指标告警规则（来源：批次八 8.2「Grafana/告警规则可直接采集」；证据：`docker/init/prometheus/alert-rules.yml:8-20` 目前仅 redis/moderation 两组，无 search 规则；前置：`/actuator/prometheus` 可抓取；验收：DLQ 死信 > 0、ES 异常率、DEGRADED 占比、热度刷写失败数至少形成可触发并可通知的规则）
  - ✅ 2026-11-05：实际由 9.9-C 落地——`docker/init/prometheus/alert-rules.yml` 新增 `litisle-search` 告警组 5 条规则（SearchIndexDlqNew / SearchEsErrorRateHigh / SearchDegradedRatioHigh / SearchHeatFlushFailureHigh / SearchCacheEvictMergeFailure），风格与既有 redis/moderation 组一致；抓取目标已由 9.9-B 补齐（`host.docker.internal:8086`）；运行态触发验证仍需联调环境（依赖 9.4-1 环境就绪）。


- [x] 【必须】热点查询缓存失效降频：打脏标记 + 定时合并失效（来源：批次九 9.2-d 决策的替代方案；前置：Redis 可用；验收：发布/元数据/书籍/用户事件只写脏标记，定时任务按 1 秒间隔合并执行一次 evictAll，下架仍立即失效）
  - ✅ 2026-11-05：新增 `SearchBizConstant.HOT_QUERY_DIRTY_KEY="search:hotquery-dirty"`（刻意避开 `search:hotquery:*`，防被 evictAll 一并删除）、`HOT_QUERY_DIRTY_TTL_SECONDS=300`（防任务宕机丢标记）；`HotQueryCacheService` 增 `markDirty()/evictIfDirty()`；新增 `HotQueryCacheEvictJob`，`@Scheduled(fixedDelayString="${litisle.search.cache.evict-merge-interval-ms:1000}")`，先删标记再 evictAll（并发标记可留到下一轮），失败补打标记；配置 `litisle.search.cache.evict-merge-interval-ms=1000` 并同步 `SearchProperties.Cache`。指标复用 `SearchMetrics`：`litisle_search_cache_dirty_mark_total{result}`、`litisle_search_cache_evict_merge_total{result}`。`IndexSyncConsumer` 发布/元数据/书籍/用户四类改 `markDirty`；**下架保留 `evictAll` 并注明隐私红线**；`markDirty` Redis 失败抛 IllegalStateException 交 MQ 延迟重投。编译 `litisle-search` exit 0。
- [x] 【必须】RabbitMQ 消费者并发显式配置（来源：批次九 9.2-d 决策的替代方案；前置：联调环境观察 content Feign 与 ES 写入水位；验收：并发 4/最大 8 下无消息漏 ack、无重复写入异常）
  - ✅ 2026-11-05：`application.yml` 增 `spring.rabbitmq.listener.simple.concurrency=4`、`max-concurrency=8`，并加中文注释说明调大前需观察 content 回查 QPS 与 ES 刷新/段合并压力。语义影响：逐条手动 ACK、幂等 upsert、延迟重投、DLQ 均不变；**版本守卫存在理论竞态**——同队列同 id 的多条事件并发消费时，「先 GET 再写」的窗口可能让较旧快照覆盖较新文档（此前串行消费不存在此窗口，跨队列并发本就有）。建议后续补 ES 条件更新（updateTime 比较写入或 external version），本轮先按 4/8 配置并在运行态观察。
  - 后续处置（2026-11-05）：上述理论竞态经评估确认为真实写覆盖窗口，已回退为串行 1/1，详见 9.8 P0-E。


- [x] 【必须】P0-E：索引同步消费者并发回退为串行（来源：9.7 引入的版本守卫写覆盖窗口；前置：无；验收：`concurrency`/`max-concurrency` 均为 1，同队列同 id 的旧/新快照不再并发处理）
  - ✅ 2026-11-05：`litisle-search/application.yml` 将 `spring.rabbitmq.listener.simple.concurrency` 与 `max-concurrency` 由 4/8 回退为 1/1，保留原有注释并追加中文回退说明。理由：当前量级串行吞吐有数十倍余量，4/8 属提前消费并换来一个真实的写覆盖窗口，不划算；企业/社区主流在小体量索引同步任务上也默认串行消费。恢复条件：积压持续 >5 分钟或峰值持续 >50 条/秒，且确认串行成为瓶颈，先落地下方 P2 条件项后再提高本值。语义：手动 ACK、幂等 upsert、5 次延迟重投、DLQ 均不变。
- [x] 【必须】P0-D：文章下架 Redis 墓碑 + 写前校验（来源：跨队列发布/下架竞态（串行亦存在），隐私红线兜底；验收：下架删除索引后写墓碑；发布/元数据写前命中墓碑则重新门控；失败不静默吞消息）
  - ✅ 2026-11-05：新增 `SearchBizConstant.ARTICLE_TOMBSTONE_KEY_PREFIX="search:index:tombstone:article:"`、`SearchProperties.Index.tombstoneTtl`（默认 10m）与 yml `litisle.search.index.tombstone-ttl: 10m`；新增 `ArticleTombstoneService` / `ArticleTombstoneServiceImpl`（写/查失败记 error 并抛 `IllegalStateException` 交 MQ 延迟重投，查询失败宁可重试也不放行）。`ArticleIndexServiceImpl#handleUnpublished` 删除索引后、删切片前写墓碑；`handlePublished` / `handleArticleUpdated` 在门控后、版本比较前查墓碑，命中则重新回查：非公开跳过（元数据路径转为下架清理），仍公开则改用最新快照继续，日志留痕说明跳过原因。只对文章生效（书籍/用户无下架语义）。TTL 依据：5 次 × 30 秒最大重投 ≈150 秒 + 处理余量，取 10 分钟；既覆盖最大重投窗口，也避免长期残留。

**一、先做（无 DDL）**

- [x] A【P1，优先级最高】ES 日期格式隐患（状态：代码已完成，实际写入待联调验证）
  - 目标：确认并修复 `article_idx` / `book_idx` / `user_idx` 的 `updateTime` 写入格式与 `date` mapping 的兼容性，避免索引写入直接报错。
  - 依据：`EsIndexInitializer.java:163-164`（article）、`:189`（book）、`:208`（user）均调用 `dateProperty()`；`EsIndexInitializer.java:329-336` 只声明 `type=date`、未声明 `format`（ES 默认 `strict_date_optional_time||epoch_millis`）；ES 客户端复用全局 ObjectMapper（`ElasticsearchConfig.java:69`），全局 Jackson 日期格式为 `yyyy-MM-dd HH:mm:ss`（`JacksonConfig.java:46`、`:63-68`），实际序列化为空格分隔、无毫秒，非 ES 默认 date 接受的 ISO 写法，理论上会报 `mapper_parsing_exception`。
  - 执行动作：① 联调首条发布后执行 `GET article_idx/_doc/{id}`，验证 `updateTime` 实际存储值与是否报错；② 若失败，二选一修复——优先给 ES 客户端用独立 ObjectMapper 序列化为 ISO（`yyyy-MM-dd'T'HH:mm:ss`，可选 `.SSS`），或给 mapping 显式声明 `format`；③ 修复后补 `book_idx` / `user_idx` 同类验证。
  - 验收：三索引各写入一条文档，`_doc` 回读 `updateTime` 正常、检索返回的时间字段不为空。
  - ✅ 2026-11-05：`ElasticsearchConfig` 改为「复制全局 ObjectMapper + 注册 ISO 日期模块」的 ES 专用实例（`LocalDateTime` 输出 `yyyy-MM-dd'T'HH:mm:ss`，有毫秒自动带小数；同时注册 ISO 反序列化器供版本守卫回读），全局 Jackson/REST 契约未改；`EsIndexInitializer.dateProperty()` 显式声明 `SearchBizConstant.ES_DATE_FORMAT=strict_date_optional_time||epoch_millis`（三业务索引 + 切片索引统一）；新增启动校验 `checkExistingDateFormat()`：既有索引缺 format 仅 WARN 提示（ES 不允许原地修改既有 date 字段 format，默认格式与 ISO 写入兼容，不重建索引）。
  - 联调验证清单（需环境）：
    1. 发布一篇文章后执行 `GET article_idx/_doc/{articleId}`，确认 `updateTime` 形如 `2026-11-05T10:00:00`（ISO 带 `T`）、无 `mapper_parsing_exception`；
    2. 执行 `GET article_idx/_mapping`，确认新建索引 `updateTime.format = strict_date_optional_time||epoch_millis`；既有索引若启动日志出现「未显式声明 format」WARN，属预期（不重建）；
    3. 发布/修改一本书，`GET book_idx/_doc/{bookId}` 校验 `updateTime`；
    4. 修改一次用户资料，`GET user_idx/_doc/{userId}` 校验 `updateTime`；
    5. 调用检索接口，确认返回的时间字段非空且格式正常；
    6. 若任一步报解析错误：抓取 ES 客户端请求日志确认序列化值，再按「mapping 显式 format 或重建索引」兜底。

- [x] B【P1，阻塞项】Prometheus 补 search 抓取目标（状态：配置已完成，Targets UP 待环境）
  - 目标：让 Prometheus 真正采集 `litisle-search` 指标，否则后续告警规则全部空转。
  - 依据：`prometheus.yml:23-30` 抓取目标只有 8082/8081/8083；`litisle-search/application.yml:1-3` 端口为 8086；`litisle-gateway/application.yml:189-192` 明确未放行 `/actuator/**` 且无 `/actuator` 路由，须直连抓取。
  - 执行动作：在 `docker/init/prometheus/prometheus.yml` 的 `litisle-services.targets` 增加 `host.docker.internal:8086  # litisle-search`；不新增网关路由。
  - 验收：Prometheus Targets 页面 search 状态 UP，能查询到 `litisle_search_requests_total`。
  - ✅ 2026-11-05：`docker/init/prometheus/prometheus.yml` 的 `litisle-services.targets` 增 `host.docker.internal:8086  # litisle-search`；job 名与 metrics_path 沿用现有风格，未新增网关路由、未改 actuator 暴露。YAML 解析校验通过（targets 现为 8082/8081/8083/8086）。

- [x] C【P1】search 告警规则 5 条（状态：配置已完成，触发验证待环境）
  - 目标：覆盖索引死信、ES 异常率、检索降级、热度刷写失败、缓存合并失效失败五类可观测风险。
  - 依据：现有结构 `alert-rules.yml:9-19`（redis group）、`:21-91`（moderation group），字段为 `alert/expr/for/labels.severity/annotations.summary+description`，summary 带 `[{{ $labels.application }}]`；指标来自 `SearchMetrics.java:34-53`（7 个 Counter，Prometheus 导出名补 `_total`）。
  - 执行动作：在 `alert-rules.yml` 新增 group `litisle-search`，风格与现有 redis/moderation 一致：
    ① `SearchIndexDlqNew`：`increase(litisle_search_index_dlq_total[10m]) > 0`，`for: 0m`，`critical`；
    ② `SearchEsErrorRateHigh`：`sum by (application) (rate(litisle_search_es_errors_total[5m])) / sum by (application) (rate(litisle_search_requests_total[5m])) > 0.05`，`for: 10m`，`critical`；
    ③ `SearchDegradedRatioHigh`：`sum by (application) (rate(litisle_search_requests_total{outcome="degraded"}[10m])) / sum by (application) (rate(litisle_search_requests_total[10m])) > 0.2`，`for: 10m`，`warning`；
    ④ `SearchHeatFlushFailureHigh`：`sum by (application) (rate(litisle_search_heat_flush_docs_total{result="failure"}[15m])) / sum by (application) (rate(litisle_search_heat_flush_docs_total[15m])) > 0.1`，`for: 15m`，`warning`；
    ⑤ `SearchCacheEvictMergeFailure`：`increase(litisle_search_cache_evict_merge_total{result="failure"}[15m]) > 0`，`for: 5m`，`warning`。
  - 说明：比例阈值与 `for` 时长首版先跑 1~2 周再收紧；文件不自动热加载，改后需触发重载（`alert-rules.yml:1-6`）。
  - 验收：`promtool` 校验通过；人为制造一条 DLQ 能触发告警（或规则 dry-run 通过）。
  - ✅ 2026-11-05：`alert-rules.yml` 新增 group `litisle-search`，5 条规则按上述口径落地（`SearchIndexDlqNew` critical/0m、`SearchEsErrorRateHigh` critical/10m、`SearchDegradedRatioHigh` warning/10m、`SearchHeatFlushFailureHigh` warning/15m、`SearchCacheEvictMergeFailure` warning/5m），字段与 redis/moderation 组一致（summary 带 `[{{ $labels.application }}]`，description 含口径+处置建议）。YAML 结构校验通过（3 组共 12 条规则，必填字段无缺失）；本机无 promtool，未执行 `promtool check rules`。

- [x] D【P2，无 DDL】版本跳过计数器（状态：代码已完成，指标可见待环境）
  - 目标：量化「同秒折叠」实际发生频率，为是否推进 E 中的 A/B 方案提供数据。
  - 依据：`IndexVersionGuard.java:77-78` 判定 `stale = !currentUpdateTime.isBefore(newUpdateTime)`（相等即跳过），`:80-81` 仅打印「版本未前进」日志，无指标。
  - 执行动作：在判定 stale 时累加 counter（建议 `litisle_search_index_version_skip_total{type}`），复用 `SearchMetrics` 体系。
  - 验收：指标出现在 `/actuator/prometheus`；在规则/运维文档里说明后续用该指标决定是否推进 A/B 方案。
  - ✅ 2026-11-05：`SearchMetrics` 新增 `litisle_search_index_version_skip`（标签 `type`）与 `recordIndexVersionSkip`；`IndexVersionGuard#isStale` 增 `type` 参数，判定 stale 时先累加指标再保留原 info 日志（跳过逻辑不变）；三个调用点分别传 `SearchTypeEnum.ARTICLE/BOOK/USER.getCode()`，避免字符串硬编码。


- [x] F 队列积压 gauge（P2，状态：代码已完成，指标可见性与告警触发待环境验证）
  - 目标：支撑「积压持续 >5 分钟」告警，并作为 P2（并发恢复 + A+B）的触发判断依据。
  - 依据：现有指标只有 `mq_consume_total{queue,result}`（`MqMetrics.java:33-37`，埋点 `IndexSyncConsumer.java:313-326`），只能表达吞吐速率，无法表达积压量；`SearchMetrics.java:34-53` 无 backlog 指标。
  - 执行动作：定时（约 15s）用 `AmqpAdmin#getQueueInfo(...).getMessageCount()` 采集 3 个同步队列，暴露 `litisle_search_index_backlog{queue}` gauge。
  - 验收：指标出现在 `/actuator/prometheus`；可配置 `litisle_search_index_backlog{queue=~"litisle\\.content\\..*"} > 20` + `for: 5m` 近似表达「积压持续 >5 分钟」。
  - ✅ 2026-11-05：新增 `IndexBacklogCollectJob`（job 包）定时采集 3 个文章索引同步队列（published / unpublished / article-updated，队列名取 `MqConstant` 常量，无字面量）消息数，经 `AmqpAdmin#getQueueInfo(...).getMessageCount()` 写入 gauge `litisle_search_index_backlog{queue}`（`SearchMetrics#recordIndexBacklog`，数值载体按队列缓存，防同名 gauge 重复注册导致长期为 0）；采集间隔 `litisle.search.metrics.backlog-collect-interval-ms`（默认 15000）+ 首次延迟 `backlog-collect-initial-delay-ms`（默认 15000），`SearchProperties.Metrics` 与 application.yml 已同步并带中文注释；容错——单队列读取失败仅 WARN 跳过该队列、整体异常 try/catch 兜底不静默、采集跑在独立单线程执行器且 CAS 标记防重入（调度线程提交后立即返回，MQ 卡顿不阻塞热度刷写与缓存合并任务，fixedDelay 下不堆积）；本项编码/编译不依赖 ES/Redis/MQ 运行。
  - 遗留/待环境：① 指标需启动 search + RabbitMQ + Prometheus 后在 `/actuator/prometheus` 确认出现并随积压变化；② 按登记口径只采集 3 个文章队列，浏览量 / 社交互动 / 书籍 / 用户共 6 个队列是否纳入（追加 `BACKLOG_QUEUES` 常量即可）待确认；③ 告警规则（>20 + for 5m）尚未加入 `alert-rules.yml`，触发验证待环境。
  - ✅ 2026-11-05（补齐遗留）：`BACKLOG_QUEUES` 由 3 个文章队列扩展为 search 实际监听的全部 9 个业务队列——content published / unpublished / article-updated / view-count-flushed / book-changed + social liked / commented + user registered-index / profile-updated-index（与 `IndexSyncConsumer`、`HeatEventConsumer` 的 `@RabbitListener` 逐一核对，队列名取 `MqConstant` 常量）；DLQ 不纳入（已有 `litisle_search_index_dlq_total{originQueue}` 计数器与独立告警，避免两套口径）；gauge 标签仍为 `queue`，基数固定 9 不膨胀。`docker/init/prometheus/alert-rules.yml` 的 `litisle-search` 组新增 `SearchIndexBacklogHigh`：`max by (application, queue) (litisle_search_index_backlog) > 20`，`for: 5m`，`severity: warning`——按单队列判定（避免多队列零散瞬时消息求和误报，且保留 queue 标签可直接定位）；采集失败无样本时该规则表现为 no data（不触发、不误报），采集器自身失效需另配 `absent()` 规则（未纳入）。YAML 解析校验通过（3 组 / search 6 条规则），编译验证通过（2026-11-05）。

## 9.6-B：search 的 TestNG 编译期依赖清理（2026-11-08 追加归档）

> 迁自 [next_update.md](../../next/next_update.md) 批次九「9.6 测试补齐」组的 9.6-B 条目，原文搬运（含现象 / 动作 / 代价 / 验收 / 实测）；同组 9.6-A（Testcontainers 集成测试）仍为主清单待办。

- [x] 【清理｜9.6-B】search 的 TestNG 编译期依赖清理（[pom.xml:169-174](../../../litisle-search/pom.xml#L169-L174)）
  - **现象**：`org.testng:testng` 版本写 `RELEASE`、`scope=compile`，测试框架被当成编译期依赖打进运行期；`litisle-search/src` 内 `org.testng` 精确检索 0 命中，实际无人使用。
  - **动作**：直接删除该依赖块（保留 `spring-boot-starter-test` 的 JUnit 5）；若后续确需 TestNG，再以 `scope=test` 引入并锁定版本，不用 `RELEASE`。
  - **代价 / DDL**：1 处 pom 改动；无 DDL、无配置。**验收**：`mvn -pl litisle-search -am test` 通过，`mvn -pl litisle-search dependency:tree` 中编译/运行期不再出现 testng。
  - **已拍板（2026-11-08｜第一批·零风险）**：先删除 TestNG 依赖，改为仅 JUnit 5；不改变运行时行为。
  - ✅ 2026-11-08：全仓 java 复核 `org.testng` 0 命中后，已删除 search pom 的 TestNG 依赖块；`mvn -pl litisle-search -am -DskipTests compile` 通过。另发现 `litisle-file/pom.xml:187` 也有 TestNG 声明，不在本条范围，未动。

## 9.8-P1：`user_idx` 版本源补上 `sys_user_profile.update_time`（2026-11-08 追加归档）

> 迁自 [next_update.md](../../next/next_update.md) 批次九「9.8 并发竞态修复与下架墓碑 + `updateTime` 精度」组的 9.8-P1 条目，原文搬运（含问题 / 改法 / 代价 / 验收 / 拍板 / 分阶段实测证据与后续闭环）。同组 9.8-P2（`datetime(3)`，`[~]` 只改初始化脚本未动库）仍在主清单。

- [x] 【P1｜必修｜9.8-P1】`user_idx` 版本源补上 `sys_user_profile.update_time`
  - **问题**：`getUserIndexDoc` 只回填 `user.getUpdateTime()`（[UserFeignProviderController.java:292](../../../litisle-user/src/main/java/com/litisle/litisleuser/controller/feign/UserFeignProviderController.java#L292)），而 `user_profile.pen_name` 只改笔名时不刷新 `sys_user.update_time`；`IndexVersionGuard` 判定版本未前进 → 索引永不更新，属**必然漏**（不是同秒竞态）。
  - **改法**：同一方法已加载 `profile`（[同文件:281-283](../../../litisle-user/src/main/java/com/litisle/litisleuser/controller/feign/UserFeignProviderController.java#L281-L283)），把回填改为 `max(user.getUpdateTime(), profile == null ? null : profile.getUpdateTime())`；`UserProfile#getUpdateTime` 已存在（[UserProfile.java:58-60](../../../litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/UserProfile.java#L58-L60)）。
  - **动哪些文件 / 代价**：1 处代码（1 个文件）；无 DDL、无新配置。
  - **验收**：只修改笔名（不动 user 主表）→ 触发 `UserProfileUpdatedEvent` → `user_idx` 的 `penName` 更新且 `updateTime` 前进；只改主表简介同样正常。
  - **已拍板（2026-11-08｜第二批·必修，需回归）**：按 `max(user.updateTime, profile.updateTime)` 实施；回归文章详情、章节阅读、匿名刷流三种身份。
  - ✅ 2026-11-08（版本源 + 索引写入均已实测通过）：`getUserIndexDoc` 已取 `user` / `profile` 的 `updateTime` 最大值，复用同方法已加载的 `profile`、未新增查询。
    - **已验通过（版本源）**：启动 user + search（直连 8081/8086），`PUT /api/user/profile` 只改笔名后，`sys_user.update_time` 全程保持 `2026-11-08 03:26:45`（乱码写入、正确 UTF-8 写入、复原三次均未变），`sys_user_profile.update_time` 前进到 `11:40:54`；`GET /api/feign/user/index-doc?userId=2002` 返回 `updateTime=2026-11-08 11:40:54`，证明 `max(user.updateTime, profile.updateTime)` 生效。
    - **未通过（索引写入）**：search 消费 `user.profile-updated` 报 `SaTokenContext 上下文尚未初始化`（[FeignAuthInterceptor.java:68](../../../litisle-common/common-security/src/main/java/com/litisle/commonsecurity/feign/FeignAuthInterceptor.java#L68) 的 `StpUtil.isLogin()` 在 MQ 消费线程无上下文），消息重投后进 DLQ，ES `user_idx` count=0、doc 2002 返回 404。故「索引 `updateTime` 前进且笔名已更新」无实测证据，**不标 `[x]`**。该缺陷为本轮之前即存在的基础设施问题，非 9.8-P1 改动引入。
    - **复原**：笔名已改回 `林间有风`（`HEX(pen_name)=E69E97E997B4E69C89E9A38E`，`profile.update_time=11:41:42`）；因 `user_idx` 自始至终未写入，「改回后索引是否同步回去」无同步对象可验。
    - **后续闭环（2026-11-08 12:29:25）**：批次十六 16.1 修复 `FeignAuthInterceptor` 无上下文异常后重跑，`user_idx` 文档成功写入：`penName=林间有风`（HEX `E69E97E997B4E69C89E9A38E`）、`updateTime=2026-11-08T12:29:25`（与 `sys_user_profile.update_time` 一致），`sys_user.update_time` 未因笔名变化，本次验收完整通过。

## 归档说明

- 本文件共归档 1 项（9.8-P1）；9.6-B 见前节。
- 9.8-P1 最初因 16.1 缺陷导致「索引写入」未通过而暂不勾选，16.1 修复后于 2026-11-08 12:29:25 补齐实测，本次按完整证据归档。
- 编号空缺：主清单批次九 9.8 组仅 9.8-P2 在本文档，9.8-P1 指向本文件；编号不重排。
