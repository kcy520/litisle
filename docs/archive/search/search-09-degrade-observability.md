> **归档说明**：新建板块文件（批次八，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次八：降级链路与观测」节，正文原样搬运（含 ✅ 摘要与告警规则）。
> **来源**：[next_update.md](../../next/next_update.md) 批次八；告警触发验证与前端提示见该文档 9.5 / V3。

## 板块九：降级链路与观测（批次八，2026-11-05 归档）

### search模块

#### 批次八：降级链路与观测（依赖批次四；对齐批次D D6）

##### 8.1 ES 降级链路（A3 定案）

- [x] ES 不可用 → `fallback.enabled` 开关 → 降级返回热门列表（content `/article/hot` 同源，`source=DEGRADED` 标记不缓存）+ 前端提示搜索不可用；降级关闭或热门获取失败 → SEARCH_ES_UNAVAILABLE；降级数据源语义随 content 批次E P5 切换为 heat 榜序（`CONTENT_FEED_HOT_ZSET` 同源 + 榜空回退 view_cnt 直查），落地时对齐（跨模块依赖：content 批次E P5）
  - ✅ 2026-11-04（批次外依赖，已先说明后实施）：`litisle-api` 新增 `HotArticleDTO`（articleId/title/summary/authorId/publishTime 轻量投影）+ `ContentClient#getHotArticles(limit)`；降级工厂对该方法 **fail-fast**（抛 `REMOTE_SERVICE_ERROR`，不静默返回空列表，避免用户误以为「搜索无结果」）；`litisle-content` 新增 `ContentInternalService#listHotArticles` + Feign 端点 `GET /api/feign/content/hot-articles`（网关不路由），复用 `/article/hot` 读链路（content 批次E P5：`CONTENT_FEED_HOT_ZSET` 热度榜主路径、榜空回退 view_cnt 直查），热榜口径不在 search 复制
  - ✅ 2026-11-04：`SearchServiceImpl#search` 统一捕获 `SEARCH_ES_UNAVAILABLE`——`fallback.enabled=true` 时走 `degradedResult`（source=`DEGRADED`，ARTICLE 取 content 热门、固定返回第一页且 total=当页条数；BOOK/USER 无对应热门源，返回空页 + `DEGRADED` 标记供前端提示）；开关关闭时原样抛出 `SEARCH_ES_UNAVAILABLE`；参数校验等非 ES 异常不参与降级
  - ✅ 2026-11-04：**降级缓存策略＝不写缓存、也不设短 TTL**，理由：① 热门列表与查询词无关，按查询键缓存会把同一份热门复制到每个关键词下白占内存；② 写入后 ES 恢复的 TTL 窗口内仍持续返回降级结果，掩盖恢复事实；③ 现有 `HotQueryCacheService#write` 只缓存 `source=ES`，降级分支直接返回不经过写缓存，与批次四兜底口径一致
  - ✅ 2026-11-04：降级失败语义——content 热门获取失败（网络异常/非成功码/data=null）统一收敛为 `SEARCH_ES_UNAVAILABLE`；降级仍属有效检索，照常异步记录搜索历史
  - 编译验证已通过（2026-11-04，`mvn -q -pl litisle-search,litisle-content -am compile -DskipTests` exit 0）

##### 8.2 指标埋点与告警

- [x] 检索/索引同步/热度刷写指标埋点 + DLQ 告警留痕（批次二 DLQ Consumer 联动）
  - ✅ 2026-11-04：新增 `SearchMetrics`（`ObjectProvider<MeterRegistry>` 注入，未接 Micrometer 时全方法静默空转）：`litisle_search_requests{type,outcome}`（outcome=cache/es/fallback/degraded/error）、`litisle_search_es_errors{type}`、`litisle_search_heat_flush_round`（轮次扫描键数）、`litisle_search_heat_flush_docs{result}`（条目成功/失败数）、`litisle_search_index_dlq{originQueue}`（死信数）；标签只用有限枚举值，杜绝用户输入造成基数膨胀
  - ✅ 2026-11-04：索引同步消费指标复用 common-mq `MqMetrics#recordConsume`——`IndexSyncConsumer` 用 `runWithMetrics` 包装 5 条同步动作，口径与 `MqAckHelper` ACK 三分支对齐（SUCCESS / BIZ_DROP / UNEXPECTED），异常原样抛出、不改变 ACK/重试分流
  - ✅ 2026-11-04：`IndexHeatFlushJob` 刷写轮次与条目结果埋点（整批异常回灌时记 failure）；`IndexDlqConsumer` 保留既有 error 日志（消息体截断 + originQueue），叠加死信计数形成「日志 + 指标」双告警留痕
  - ✅ 2026-11-04：指标经 `spring-boot-starter-actuator` + `micrometer-registry-prometheus`（search pom 已有）由 `/actuator/prometheus` 暴露，Grafana/告警规则可直接采集
  - 编译验证已通过（2026-11-04，`mvn -q -pl litisle-search,litisle-content -am compile -DskipTests` exit 0）
