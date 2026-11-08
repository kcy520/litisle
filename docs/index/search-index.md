# search-index · litisle-search 搜索模块速查表

> 用途：写码/联调前查 search 模块坐标，**免去逐个 Read**。
> 标记：✅ 已落地 ｜ ☆ 待建 ｜ 🆕 批次十一新增。
> 关联：[Search-Architecture.md](../Architecture/Search-Architecture.md)（架构全貌/事件契约 §五/推荐流边界 §十）· [Search-Business_Guide.md](../Study/Search-Business_Guide.md)（板块讲解）· [api-index.md](api-index.md)（SearchClient 契约）· [Sql-Document.md](../Sql-Document.md) §五（search_history DDL）。
> 纪律：状态 SSOT 在业务方 DB，search 只存 ES 投影（不回写业务数据）；可见性红线——仅 PUBLIC 且过审内容入索引与向量库，`content.unpublished` 四路径必删；搜索词 keyword 不入任何 ES 索引；事件消费一律手动 ACK + 幂等 + 指数退避重投。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 服务名 / 端口 | `litisle-search` / `8086`（application.yml） |
| 定位 | 独立微服务，站内检索统一收口：**ES 索引同步 + 全文/多维度检索 + dense_vector KNN 相似推荐**；「人找内容」，不负责推荐流召回（P1 才接入，见 Architecture §十） |
| 库 | `litisle_search`（1 表：search_history，唯一自有 MySQL 表） |
| ES 索引 | `article_idx`（含 vec_dense 1024 维 + suggest 字段）/ `book_idx` / `user_idx` / `article_chunks`（RAG 知识库，search 摄入 ai 纯查询）；启动时幂等创建，_meta 登记 Embedding 模型版本与维度 |
| 关键依赖 | Elasticsearch 8.18.8（Java Client）· spring-boot-starter-amqp（手动 ACK + DLX/DLQ + 延迟重投）· common-redis（热点缓存/热度待刷新键）· litisle-api（SearchClient 契约 + ContentClient 回查）· Sa-Token · actuator + micrometer（批次九） |
| 只管 | 全文检索 / 搜索联想 / 搜索历史 / 相似推荐 / 索引与向量同步 / 向量全量重建 |
| 不负责 | 业务数据存储（ES 投影非 SSOT）· 推荐流分页与快照（content）· RAG 摄入决策与 LLM 调用（ai）· 标签搜索（复用 content `/tags?keyword=` 聚合） |

## 二、包结构（`com.litisle.litislesearch`）

```text
controller/       SearchController（GET /search · GET /search/suggest）/ SimilarController（GET /search/similar/{id}）
                  / SearchHistoryController（GET·DELETE /search/history）/ VectorRebuildAdminController（向量全量重建管理端点）
controller/feign/ SearchFeignProviderController（implements SearchClient，/api/feign/search）
service/          SearchService（检索编排）/ SuggestService / SimilarService / SearchHistoryService
                  / VectorService（Embedding）/ VectorRebuildService / ArticleIndexService / BookIndexService
                  / UserIndexService / ArticleChunkService / HeatPendingService（热度待刷新键）/ HotQueryCacheService
                  / ArticleTombstoneService（下架墓碑）
service/impl/     对应实现（门控/幂等/写路径编排；版本比较统一委托 IndexVersionGuard）
service/support/  IndexVersionGuard（article/book/user 三索引写入版本守卫：现有 updateTime ≥ 新快照（含相等）跳过；读 ES 失败抛异常交 MQ 重试）
mq/               SearchMqConfig（**十同步队列** + 独立 DLX/DLQ + 延迟交换机拓扑声明）
mq/consumer 级：   IndexSyncConsumer（published/unpublished/article-updated/book-changed/user.registered/user.profile-updated）
                  / HeatEventConsumer（onLiked/onUnliked🆕/onCommented/onViewCountFlushed，只累加 Redis 热度键不直写 ES）
                  / IndexDlqConsumer（死信告警留痕）
es/               ElasticsearchConfig（客户端装配）/ EsIndexInitializer（四索引幂等创建 + book_idx 补 authorId）
domain/entity/    ArticleIndexDoc / ArticleHeatDoc / ArticleMetaDoc / BookIndexDoc / UserIndexDoc / ArticleChunkDoc
                  / SearchHistory（唯一 MySQL 实体）
domain/vo/        SearchHistoryVO / VectorRebuildResultVO（契约出入参 SearchRequestDTO / SearchResultVO 等在 litisle-api）
mapper/           SearchHistoryMapper
enums/            SearchTypeEnum（ARTICLE/BOOK/USER）/ SearchSortEnum（RELATED/NEWEST/HEAT）
constant/         SearchBizConstant（队列/交换机命名 · PUBLIC 闸门 · ES 字段名 · 热度 pending 键前缀）
metrics/          SearchMetrics（检索/降级/DLQ/热度刷写/积压 gauge 统一打点收口）
job/              IndexHeatFlushJob（聚合 _bulk 重算 heat，分片执行）/ IndexBacklogCollectJob（队列积压 gauge）
                  / HotQueryCacheEvictJob（脏标记合并失效）
config/           SearchProperties（prefix litisle.search.*）/ SearchMqConfig / ElasticsearchConfig
```

## 三、枚举

- `SearchTypeEnum`：`ARTICLE / BOOK / USER` → 决定查哪个索引
- `SearchSortEnum`：`RELATED（默认，relevance+function_score）/ NEWEST（updateTime 降序）/ HEAT（heat 降序）`

## 四、REST 接口（前缀 `/api/search`，返回 `Result<T>`）

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/search` | 匿名（网关白名单） | 全文检索：multi_match（title^4/tags^3/summary^2/content）+ filter（visibility=PUBLIC/tag/category/zone/时间范围）+ function_score(heat)；空结果兜底 = 实时 Embedding → KNN 语义相似（标 fallback）；查询词转义防 ES 注入 |
| GET | `/search/suggest?q=` | 匿名 | 联想：title/authorName completion+prefix + 标签复用 content 接口聚合 |
| GET | `/search/similar/{articleId}` | 匿名 | KNN vec_dense + 标签重合 + 行为加权，强制 filter PUBLIC，排除自身/已下架 |
| GET / DELETE | `/search/history` | 登录 | 搜索历史列表 / 清空（UPSERT 同词仅前进，超 max-per-user=20 懒删最旧；keyword 不入索引） |
| POST | 向量重建管理端点 | 管理 | Embedding 模型变更 → 全量重建（VectorRebuildAdminController） |

错误码分段 `100000-109999`：SEARCH_PARAM_INVALID 100001 / SEARCH_PAGE_EXCEEDED 100002 / SEARCH_SOURCE_NOT_FOUND 100003 / SEARCH_ES_UNAVAILABLE 100004。

## 五、Feign 提供端（`/api/feign/search`，网关不路由）

- `GET /api/feign/search/similar` → `List<SimilarArticleVO>`（ai RAG / 前端相关文章复用）
- `POST /api/feign/search/search` → `SearchResultVO`（业务方直接检索，非降级链路）

## 六、索引同步（10 条 MQ 队列，SearchMqConfig 声明）

| 事件 | 消费方法 | 动作 |
|---|---|---|
| `content.published` | IndexSyncConsumer.onPublished | 回查元数据 → 乱序门控（status=2 且 PUBLIC 才写）→ **IndexVersionGuard 版本比较**（旧/同版本跳过）→ Embedding 预计算 → upsert article_idx + 摄入 article_chunks |
| `content.unpublished` | onUnpublished | 删 article_idx 文档 + article_chunks 旧块（幂等 404 视为成功）+ 落下架墓碑 |
| `content.article-updated` | onArticleUpdated | **IndexVersionGuard 版本比较** → partial update 元数据字段（不动向量与 chunks） |
| `content.book-changed` | onBookChanged | upsert book_idx（批次五 B4） |
| `user.registered` / `user.profile-updated` | onUserRegistered / onUserProfileUpdated | upsert user_idx（批次五 B5）；版本源取 `user.update_time` 与 `user_profile.update_time` 的较大者，写入前过 IndexVersionGuard |
| `social.liked` | HeatEventConsumer.onLiked | `HINCRBY` 累加 like 增量（按 SocialLikedEvent.delta，无 delta 兜底 +1、delta=0 跳过） |
| `social.unliked` 🆕 | onUnliked（11.14） | 负数累加（delta=-1）；写回 ES 前 `Math.max(0,…)` 下限保护防 likeCnt 负值 |
| `social.commented` | onCommented | `HINCRBY` 累加 comment 增量 |
| `content.view-count-flushed` | onViewCountFlushed | `HSET` 覆盖写 view_cnt 绝对值 |

- 互动事件**只累加 Redis 热度待刷新键，不直写 ES**（A7：带向量整文档重写写放大严重）；`IndexHeatFlushJob` 定时（默认 15min）聚合 `_bulk` 重算 heat，按 batch-size(1000)×concurrency(2) 分片。
- 可靠性：手动 ACK + ACK 后置（Redis 累加成功才 ACK）+ eventId 幂等 + **指数退避延迟重投**（baseDelay 30s × 2^n，超 5 次入 DLQ）；死信按队列名路由（`deadLetterRoutingKey(queueName)`）。
- 版本守卫：文本索引写入前统一经 `IndexVersionGuard` 比较 ES 现有 `updateTime`（相等也跳过；读 ES 失败抛可恢复异常交延迟重试）；MQ 消费线程调用 Feign 回查无 SaToken 上下文时，`FeignAuthInterceptor` 降级为仅透传 traceId（不伪造身份、不抛异常）。

## 七、配置速查（`litisle.search.*`）

| 配置 | 默认值 | 说明 |
|---|---|---|
| `es.hosts / username / password / connect-timeout / socket-timeout` | localhost:9200 / 3s / 10s | ES 连接与查询超时 |
| `index.article / book / user` | article_idx / book_idx / user_idx | 索引名外置 |
| `weight.title / tags / summary` | 4 / 3 / 2 | multi_match 字段权重 |
| `weight.heat.like / comment / view / time-decay-half-life` | 2 / 3 / 0.1 / 168h | heat 公式权重 + 半衰期 |
| `heat.flush.interval-ms / initial-delay-ms / batch-size / concurrency / pending-key-ttl` | 900000 / 60000 / 1000 / 2 / 2h | 热度刷写任务 |
| `knn.top-k / num-candidates / max-top-k / similarity-threshold` | 10 / 100 / 50 / 0.0 | KNN 参数 |
| `cache.hot-query-enabled / ttl / evict-merge-interval-ms` | true / 10m / 1000 | 热点查询缓存与合并失效 |
| `suggest.enabled / size` | true / 10 | 搜索联想 |
| `history.max-per-user` | 20 | 搜索历史上限懒删 |
| `page.max-page-size / max-result-window` | 50 / 10000 | 分页上限 |
| `rabbit.sync-queue / dlx` | （预留）/ litisle.search.dlx | 十同步队列死信按队列名汇入 DLQ |
| `retry.max-count / base-delay-ms` | 5 / 30000 | 指数退避重投 |
| `metrics.backlog-collect-interval-ms / backlog-collect-initial-delay-ms` | 15000 / 15000 | 队列积压采集 |
| `fallback.embedding-timeout / enabled` | 1s / true | 空结果兜底 / ES 降级开关 |
| `ingest.min-content-length / chunk-size / chunk-overlap` | 200 / 500 / 50 | article_chunks 摄入切片 |
| `embedding.dimension / batch-size / rebuild-batch-size` | 1024 / 16 / 100 | Embedding 模型参数（模型版本入索引 meta，变更走全量重建） |

## 八、可观测（/actuator/prometheus，端口 8086 共用）

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `litisle_search_requests_total` | Counter | type, outcome | 检索请求数（outcome ∈ cache/es/fallback/degraded/error） |
| `litisle_search_es_errors_total` | Counter | type | ES 查询异常次数 |
| `litisle_search_heat_flush_round_total` | Counter | — | 热度刷写轮次扫描到的待刷新键数 |
| `litisle_search_heat_flush_docs_total` | Counter | result | 热度刷写成功/失败条数 |
| `litisle_search_index_dlq_total` | Counter | originQueue | 索引同步死信数（配 error 日志告警） |
| `litisle_search_cache_dirty_mark_total` | Counter | result | 热点缓存脏标记写入 |
| `litisle_search_cache_evict_merge_total` | Counter | result | 合并失效执行结果 |
| `litisle_search_index_backlog` | Gauge | queue | 同步队列积压消息数（15s 采集，支撑积压告警） |

## 九、边界与已知限制

- **推荐流不用 ES**（P0）：召回与分页全在 content（Architecture §10.8）；P1 search 仅作第 5 路召回器（≤300 条 id+score），切换阈值见 §10.6。
- `article_idx` mapping 无 `delFlag`/`heatScore`/`tagCount`（P1 前置未完成，见 Architecture §10.5）；book_idx 无 likeCnt（书籍点赞读 content `book_stat.like_count` 真源）。
- date format 等 mapping 不可在线变更，只能重建索引。
- ES 不可用降级返回热门列表（content `/article/hot` 同源，`source=DEGRADED` 不缓存）；降级关闭或热门获取失败抛 SEARCH_ES_UNAVAILABLE。
