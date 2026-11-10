# litisle-search 搜索模块架构文档（简化版）

> 版本：v0.1（2026-10-19，设计先行版；2026-11-03 批次A\~C 定版 + 批次B 契约层落地 + 批次D D1 基础骨架先行落地；2026-11-03 批次一 1.1\~1.3 落地：四索引 mapping 初始化 + search\_history DDL（Sql-Document §五）+ Feign 启用，任务细目见 next\_update.md search模块）
>
> 关联文档：[Design-Document.md](../overall_documen/Design-Document.md) §2.3.9（搜索服务总设计）/ §2.6（RAG 知识库）· [Sql-Document.md]（search 无 MySQL 表，仅作确认）· [Api-Architecture.md](Api-Architecture.md)（Feign 契约）· [commom-des.md](commom-des.md)
>
> 阅读约定：本文只保留**结论与机制**；★=已落地，✅骨架=骨架已落地（业务逻辑批次D 余项），☆=待建

***

## 一、模块定位与边界

| 项目   | 说明                                                                                                                                                                                                                                                                    |
| ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 定位   | 独立微服务，站内检索统一收口方：**ES 索引同步 + 全文/多维度检索 + dense\_vector KNN 相似推荐**                                                                                                                                                                                                       |
| 技术选型 | Elasticsearch 8.18.8（全文 + dense\_vector KNN，零新增中间件）· RabbitMQ（Confirm/ACK/DLQ，索引异步同步）· Elasticsearch Java Client                                                                                                                                                      |
| 依赖方  | content / social / user 经 MQ 事件驱动索引更新（`content.published` / `content.unpublished` / `content.article-updated`🆕 / `content.view-count-flushed`🆕 / `social.liked` / `social.unliked`🆕（批次十一 11.14）/ `social.commented`）；ai 服务经 Feign 调用相似推荐向量；gateway 路由 `/search/**` |
| 硬边界  | search 无**业务数据** MySQL 表（ES 索引文档为投影，状态 SSOT 在业务方 DB）；唯一自有表 `search_history`（用户搜索词记录，2026-11-03 新增，见 §四）；search 不回写业务方数据（仅接收事件单向同步）；私有/草稿/拒绝文章永不入索引与向量库（与 review 可见性红线同源）                                                                                              |

<br />

```
# dense_vector KNN 是什么
它 不是独立中间件 ，而是 Elasticsearch 8.x 内置的向量检索能力 ——一个字段类型 + 一种搜索算法的组合。也就是说，你们项目没有为此引入任何新组件，用的是 ES 自带的。

### 1. dense_vector — 字段类型
ES 里除了常见的`text` 、`keyword` 、`long` 等类型，还有一种专门存 稠密向量 的类型：
- 文章经过 Embedding 模型（如 bge-m3）转成一串浮点数（这里是 1024 维），语义相近的文章向量在空间上距离也近
- `"index": true` 表示 ES 会为这个向量建 HNSW 图索引，支持 KNN 查询


### KNN — 检索算法
KNN = K-Nearest Neighbors（K 近邻） ：给一个查询向量，找出索引中距离最近的 K 篇文档。

- 精确算全部文档距离太慢（暴力扫描），ES 底层用 HNSW（分层可导航小世界图） 做近似最近邻检索（ANN），毫秒级返回
- 查询时把“问题文本”实时 Embedding 成向量，与库里的向量算 cosine 相似度，取 Top K


            ┌─────────── ES 集群 ───────────┐
用户请求 →  │ IK分词(切中文) → BM25(关键词)  │ ← 这是"搜索主链路"
            │ Embedding → KNN(向量相似)      │ ← 这是"语义/推荐链路"
            └───────────────┬───────────────┘
                            │ 旁路
                     Kibana（开发/运维看数据用）
```

### 1.1 三类能力

| 能力   | 载体                             | 说明                                                                                                                                             |
| ---- | ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| 索引同步 | IndexSyncConsumer（MQ 消费）       | 订阅发布/下架/互动事件，增量更新 `article_idx` / `book_idx` / `user_idx` 与热度向量；失败入 DLQ 重试                                                                     |
| 全文检索 | SearchService                  | bool + function\_score：标题(^4) → 标签(^3) → 摘要(^2) → 全文；用户笔名模糊匹配；分类/分区/时间范围筛选 + 排序；**空结果兜底：查询词实时 Embedding → KNN 返回语义相似文章（2026-11-03 批次A A1 定案）** |
| 搜索联想 | SearchService（suggest）         | 标题/作者 ES completion/prefix suggester；标签联想复用 content `GET /tags?keyword=` 聚合，不重复建标签索引                                                           |
| 搜索历史 | SearchHistoryService           | 用户搜索词记录（单表 UPSERT：同词去重仅前进 + 上限懒删）；搜索词不入任何 ES 索引                                                                                                |
| 相似推荐 | VectorService + KnnRecommender | KNN 检索 `vec_dense`，相似度 = 标签重合 + 向量 + 行为加权；供 ai 服务 RAG 与前端"相关文章"复用                                                                              |

### 1.2 代码结构（✅骨架=2026-11-03 D1 骨架先行，业务逻辑批次D 余项；☆=待建）

```text
litisle-search
├── controller/       ☆ SearchController（GET /search?q=&type=&sort= / GET /search/suggest?q=）
│                     / SimilarController（GET /search/similar/{articleId}）
│                     / SearchHistoryController（GET /search/history / DELETE /search/history）
├── service/          ☆ SearchService（bool+function_score 检索编排 + suggest 联想 + 空结果兜底）
│                     / SearchHistoryService（搜索词 UPSERT/列表/清空）
│                     / VectorService（Embedding + KNN 查询）/ KnnRecommender（相似推荐加权）
│                     ├── impl/（接口实现）
│                     └── support/（IndexVersionGuard：article/book/user 三索引写入版本守卫）
├── domain/
│   ├── entity/       ☆ （ES 索引文档类 + SearchHistory 实体，唯一自有表）
│   └── vo/           ☆ SuggestVO / SearchHistoryVO（仅 REST 端点用）
│                     （契约出入参 SearchRequestDTO / SearchResultVO / SimilarArticleVO 及其嵌套的
│                       ArticleDocVO / BookDocVO / UserDocVO 按批次A A6 定案入 litisle-api dto/vo 包，
│                       对齐 ContentDTO 先例；search/domain 仅留内部对象）
├── mapper/           ☆ SearchHistoryMapper（search_history 单表）
├── enums/            ✅骨架 SearchSortEnum / SearchTypeEnum（DictEnum 三值兼容绑定）
├── constant/         ✅骨架 SearchBizConstant（死信拓扑命名 / PUBLIC 闸门值 / ES 字段名收口 /
│                       互动热度 pending 键前缀与 hash 字段（批次A A7）；权重与阈值等可调参数
│                       外置 yml litisle.search.*，不入常量类）
├── es/               ✅ ElasticsearchConfig（客户端装配：hosts/凭证/超时 + JacksonJsonpMapper）；
│                     ✅ EsIndexInitializer（批次一 1.1 落地 2026-11-03：article_idx/book_idx/user_idx/article_chunks
│                       四索引启动时幂等创建，_meta 登记 Embedding 模型版本与维度）
├── mq/               ✅骨架 SearchMqConfig（十同步队列（批次十一 11.14 新增取消点赞队列）+ 独立 DLX/DLQ 拓扑声明，批次D D2 起）；
│                     ✅ IndexSyncConsumer（文本/互动事件消费 + DLQ 消费者）+ HeatEventConsumer（互动热度消费，onLiked/onUnliked/onCommented/onViewCountFlushed）
└── config/           ✅骨架 SearchProperties（litisle.search.* 参数外置）/ SearchMqConfig / ElasticsearchConfig
```

***

## 二、功能设计

### 2.1 功能清单

| 功能   | 说明           | 核心机制                                                                                                                                                                                                                            |
| ---- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 索引同步 | 事件驱动增量更新     | 消费 `content.published`/`content.unpublished`/`content.article-updated`🆕/`content.view-count-flushed`🆕/`social.liked`/`social.unliked`（批次十一 11.14）/`social.commented`；按 articleId 幂等 upsert/删除；向量预计算随发布一次写入；失败指数退避延迟重投，超限入 DLQ |
| 全文检索 | 文章/书籍/用户多维检索 | multi\_match 权重 title^4 → tags^3 → summary^2 → content；function\_score 热度加权；tag/category/zone/时间范围筛选 + 排序；**空结果兜底：查询词实时 Embedding → KNN 语义相似集（标记来源，2026-11-03 批次A A1 定案）**                                                      |
| 搜索联想 | 输入即联想        | 标题/作者 ES completion/prefix suggester；标签联想复用 content 标签接口聚合返回                                                                                                                                                                    |
| 搜索历史 | 用户维度搜索词记录    | 单表 UPSERT：同词去重仅前进 + 条数上限懒删 + 清空接口；搜索词不入 ES 索引                                                                                                                                                                                   |
| 相似推荐 | KNN + 多因子加权  | 取源文章向量/标签 → KNN TopK（强制 filter visibility=PUBLIC）→ 标签重合度 + 向量相似 + 行为数据融合 → 阈值过滤；供 ai RAG 与前端复用                                                                                                                                  |
| 降级查询 | ES 不可用兜底     | 返回热门列表（content `/article/hot` 同源数据）+ 前端提示搜索不可用（`source=DEGRADED` 标记，结果不缓存；2026-11-03 批次A A3 定案）                                                                                                                                 |

### 2.2 枚举定义

```
SearchTypeEnum // ARTICLE(文章) / BOOK(书籍) / USER(用户) → 决定查哪个索引
SearchSortEnum // RELATED(默认，relevance+function_score) / NEWEST(updateTime 降序) / HEAT(heat 降序)
```

### 2.3 核心流程

**索引同步**：

```
事件到达 IndexSyncConsumer（手动 ACK）
1. 解析事件体（articleId + 元数据/状态）
2. unpublished → 删除 article_idx 文档（幂等，404 视为成功）
3. published → 校验 visibility=PUBLIC → IndexVersionGuard 版本比较（现有 updateTime ≥ 本次快照含相等则跳过；读 ES 失败抛异常交 MQ 重试）→ Embedding 预计算 vec_dense → upsert 文档
4. article-updated → 轻量回查元数据 → IndexVersionGuard 版本比较 → partial update 元数据字段（title/tags/summary 等索引字段，不动向量与 chunks；2026-11-03 批次A A2 定案）
4.5 user.registered / user.profile-updated → UserClient.getUserIndexDoc 回查 → 门控（无公开资料不建、已不可索引则删）→ IndexVersionGuard 版本比较 → upsert user_idx；版本源取 user.update_time 与 user_profile.update_time 的较大者（只改笔名时主表时间可能不前进，单取主表会被版本守卫永久判旧）；MQ 消费线程无 SaToken 上下文，FeignAuthInterceptor 只透传 traceId
5. liked/unliked/commented/view-count-flushed → **只累加 Redis 热度待刷新键，不直写 ES**（`HINCRBY` 累加 like/comment 增量 + `HSET` 覆盖 view_cnt 绝对值；点赞按 SocialLikedEvent.delta 累加（无 delta 兜底 +1、delta=0 跳过），取消点赞按 SocialUnlikedEvent.delta=-1 负数累加，IndexHeatFlushJob 写回 ES 前 `Math.max(0, …+likeDelta)` 下限保护防负值（批次十一 11.14）；2026-11-03 批次A A7 定案：article_idx 带 1024 维向量约 4KB，ES partial update 实为整文档重写，逐条互动消费写放大严重）；**ACK 后置**：Redis 累加成功才 ACK，累加失败 nack 重试（防丢数致 heat 漂移）→ IndexHeatFlushJob 定时聚合（`heat.flush.interval-ms` 默认 15min、可调 15~30min，fixedDelay 不堆积）→ `_bulk` 批量 partial update 重算 heat（每篇每窗口至多 1 次写，不动向量；heat 公式按 A5；**分片执行**：按 `batch-size`(1000)×`concurrency`(2) 分批防超大请求被 ES 拒绝，bulk 用 update+doc 只改 heat 字段禁 index 整篇替换；pending 键 TTL 2h 兜底防未刷先清）
6. 失败 → nack 重试耗尽入 DLQ → DLQ Consumer 记录告警（任务停留由人工/重投处理；版本守卫读 ES 失败也走此路径，先交延迟重试，不静默放行）
```

**全文检索**：

```
GET /search → 参数校验 + 查询词转义（防 ES 注入）
→ Redis 热点缓存命中则直返
→ 未命中：type 路由索引 → bool must(multi_match) + filter(visibility=PUBLIC)
  + filter（tag/category/zone/时间范围，可选）+ function_score(heat)
→ 命中：返回高亮片段 + 分页结果 → 写热点缓存
→ 无命中（兜底，2026-11-03 批次A A1 定案）：查询词实时 Embedding（带超时控制，配置 fallback.embedding-timeout）→ KNN 检索 vec_dense TopN 语义相似集返回，VO 标记结果来源=fallback
→ Embedding 超时/失败 → 二级兜底返回 HEAT 热门列表（复用降级链路热门数据，仍标 fallback）；热门数据也获取失败 → 返回空页（兜底失败不阻塞主链路）
→ 兜底结果不写热点缓存（防把兜底当真命中）
→ ES 异常 → 降级链路（见 §八-5）
```

**搜索联想**：

```
GET /search/suggest?q= → ES completion/prefix suggester（article_idx.title / author_name）
→ 聚合 content GET /tags?keyword= 标签联想 → 返回去重合并列表（不建标签索引）
```

**搜索历史**：

```
搜索成功后记录（异步）→ search_history UPSERT（uk(user_id,keyword)，仅前进更新时间）
→ 条数超上限懒删最旧 → GET /search/history 列表 / DELETE 清空
```

**相似推荐**：

```
GET /search/similar/{articleId} → 取源文章 vec_dense + tags
→ KNN 查询（num_candidates=100，TopK 可配）
→ 融合评分 = 向量相似 + 标签重合度 + 行为热度加权
→ 强制 filter(visibility=PUBLIC)（批次C C3 定版，与 §四查询侧双保险红线对齐）+ 阈值过滤 + 排除自身与已下架文档 → 返回列表
```

***

## 三、接口设计

### 3.1 REST 接口

| 方法     | 路径                            | 说明                      | 请求                                                                                                                                         | 响应                                    |
| ------ | ----------------------------- | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------- |
| GET    | `/search`                     | 全文检索                    | query：`q`（必填）、`type`（article/book/user）、`sort`、`pageNum`、`pageSize`、`tag`、`category`、`zone`、`publishBegin`/`publishEnd`（时间范围，2026-11-03 补） | `Result<SearchResultVO>`              |
| GET    | `/search/suggest`             | 搜索联想（2026-11-03 新增）     | query：`q`（必填）、`size`（可选，默认 10）                                                                                                             | `Result<List<SuggestVO>>`             |
| GET    | `/search/similar/{articleId}` | 相似文章推荐                  | path：articleId；query：`topK`（可选）                                                                                                            | `Result<List<SimilarArticleVO>>`      |
| GET    | `/search/history`             | 我的搜索历史（2026-11-03 新增）   | query：`pageNum`、`pageSize`                                                                                                                 | `Result<PageResult<SearchHistoryVO>>` |
| DELETE | `/search/history`             | 清空我的搜索历史（2026-11-03 新增） | —                                                                                                                                          | `Result<Void>`                        |

> 权限（2026-11-03 批次C C4 定版）：`/search`、`/search/suggest`、`/search/similar/**` 匿名可用（网关白名单登记，对齐 content `/article/*` 口径，随落地同步 Gateway 配置）；`/search/history`（GET/DELETE）需登录（网关 JWT + user\_id 硬收口）。

**VO 摘要**：

```
SearchRequestDTO  { q, type, sort, pageNum, pageSize, tag, category, zone, publishBegin, publishEnd }
SearchResultVO    { total, pageNum, pageSize, source(ES/fallback/DEGRADED), list<ArticleDocVO | BookDocVO | UserDocVO> }
ArticleDocVO      { articleId, title, summary, tags, authorId, authorName, heat, updateTime, highlight }
SuggestVO         { type(KEYWORD/TAG/AUTHOR), text }
SearchHistoryVO   { keyword, updatedAt }
SimilarArticleVO  { articleId, title, summary, authorName, score }
```

### 3.2 Feign 契约（litisle-api 侧 SearchClient ✅ 已落地 2026-11-03，temp 批次B B1；Provider 端待 search 实现 `/api/feign/search/**`）

> 跨服务契约集中在 litisle-api（对齐 Api-Architecture 约定）；search 侧由 FeignProviderController 实现（`/api/feign/search`，网关不路由）。✅ SearchClient + fallback 工厂 + 自动装配注册已于 2026-11-03 落地（temp 批次B B1，缺口 G1 闭环）：`similar(articleId, topK)` GET / `search(SearchRequestDTO)` POST；契约出入参已按批次A A6 定案入 litisle-api `dto/search`（SearchRequestDTO）与 `vo/search`（SearchResultVO/SimilarArticleVO/ArticleDocVO/BookDocVO/UserDocVO），search/domain 仅留内部对象（SuggestVO / SearchHistoryVO，随 search 本体落地）。落地口径：SearchResultVO 命中列表按 type 拆 articleList/bookList/userList 三强类型字段（Java 固定方法签名无法按 type 动态切换泛型，对文档 `list<ArticleDocVO|BookDocVO|UserDocVO>` 的落地化）。

```
// ai 服务 RAG / 小月相似推荐复用
List<SimilarArticleVO> similar(Long articleId, Integer topK);
// 业务方或 ai 服务间直接调检索（非降级链路；ES 降级见 §八-5，批次A A3 定案）
SearchResultVO search(SearchRequestDTO request);
```

***

## 四、ES 索引设计

**article\_idx**（核心索引）：

| 字段                                       | 类型                                                            | 说明                                                                                                                                                                                              |
| ---------------------------------------- | ------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| id                                       | keyword                                                       | articleId（文档主键，幂等 upsert 依据）                                                                                                                                                                    |
| title                                    | text（ik\_max\_word / ik\_smart）                               | 权重 ^4                                                                                                                                                                                           |
| tags                                     | keyword\[]                                                    | 权重 ^3；兼作标签重合度计算                                                                                                                                                                                 |
| summary                                  | text                                                          | 权重 ^2                                                                                                                                                                                           |
| content                                  | text（ik 分词）                                                   | 全文检索，返回高亮片段                                                                                                                                                                                     |
| visibility                               | keyword                                                       | 过滤字段，仅 `PUBLIC` 入索引（写入侧闸门 + 查询侧 filter 双保险）                                                                                                                                                     |
| authorId                                 | keyword                                                       | 作者 ID（作者维度聚合/筛选用；2026-11-03 批次A A4 补）                                                                                                                                                           |
| authorName                               | text（ik\_max\_word / ik\_smart，multi-field 含 .completion 子字段） | 作者名检索 + suggest 联想源（2026-11-03 批次A A4 补）                                                                                                                                                        |
| like\_cnt / comment\_cnt / view\_cnt     | long                                                          | heat 计算基数：like/comment 随互动事件增量，view\_cnt 随浏览量 flush 事件覆盖更新（2026-11-03 批次A A5 补）                                                                                                                 |
| heat                                     | double                                                        | 互动热度 = `(w_like×like_cnt + w_comment×comment_cnt + w_view×view_cnt) × 时间衰减因子`（衰减按发布时间距今计算，防旧爆款长期霸榜；权重与半衰期外置 Nacos 可调，默认 2 / 3 / 0.1 / 168h，2026-11-03 批次A A5 定案）；function\_score 加权因子 + HEAT 排序 |
| vec\_dense                               | dense\_vector（dims=1024，cosine，indexed）                       | KNN 相似推荐；维度与 Embedding 模型对齐                                                                                                                                                                     |
| updateTime                               | date                                                          | NEWEST 排序                                                                                                                                                                                       |
| title.completion / authorName.completion | completion                                                    | 搜索联想 suggester 字段（authorName.completion 为 authorName 的 multi-field 子字段；2026-11-03 补，仅联想用不参与打分）                                                                                                  |

**book\_idx**：书名/作者/简介（text + ik）+ 分类 keyword（无 likeCnt 字段——书籍点赞数取 content `book_stat.like_count` 真源，书籍流热度不经 search 索引，批次十一 11.8/11.15 口径）；**user\_idx**：笔名（text + ik，模糊匹配）+ 简介。✅ 两索引同步事件已随批次五 B4/B5 落地（`content.book-changed` / `user.registered` / `user.profile-updated`）；`user\_idx` 的版本源取 `user.update_time` 与 `user_profile.update_time` 的较大者——只改笔名/简介时主表时间可能不前进，单取主表会被版本守卫永久判旧。

**article\_chunks**（RAG 知识库索引，Design-Document §2.6，2026-11-03 补）：search 摄入、ai 纯查询；**摄入范围一期仅公开文章正文，书籍章节不入库**（2026-11-03 批次C C2 定版，预留章节粒度摄入扩展待后续立项）。`chunk_id`(keyword) / `article_id`(long) / `author_id` / `title` / `author_name` / `chunk_text`(text) / `chunk_vector`(dense\_vector, cosine, indexed) / `zone` / `visibility` / `publish_time` / `ingest_time`。入库门控：PUBLIC + 审核终态 PASS + 正文 ≥200 字 + DFA 未命中高风险；切片 \~500 字/段重叠 50 字；按 articleId 幂等覆盖（删旧块写新块）。✅ 与 article\_idx 的 vec\_dense **统一为同一 Embedding 单模型**（维度一致，具体选型落地时定，2026-11-03 批次C C1 定版闭环 G5）；模型版本写入两索引 meta，模型变更 → 全量重建（§八-8）。

**KNN 检索参数**：`num_candidates=100`（可配）、TopK 默认 10（可配）、相似度 `cosine`；向量在发布事件消费时一次预计算，查询期零 Embedding 开销。

**可见性红线**：仅 `visibility=PUBLIC` 且过审文章入索引与向量库；`content.unpublished` 事件必须同步移除对应文档与向量（覆盖删除/转私密/复审驳回下架/封禁连带四条下架路径），防止违规/封禁内容残留被越权检索。

**search\_history**（唯一自有 MySQL 表，2026-11-03 新增，DDL 登记 Sql-Document.md）：`id` / `user_id`（uk 组合 `uk(user_id, keyword)`）/ `keyword`（varchar，长度与 SEARCH\_PARAM\_INVALID 上限一致）/ `updated_at`。UPSERT 同词仅前进（更新 updated\_at）；超 `history.max-per-user`（默认 20）懒删最旧；**隐私纪律：keyword 不写入任何 ES 索引文档**（与 §八-9 对应）。书籍章节不入 article\_chunks（2026-11-03 批次C C2 定版，见上文 article\_chunks 摄入范围）。

***

## 五、事件契约（订阅侧）

| 事件                                                                    | 来源      | 动作                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| --------------------------------------------------------------------- | ------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `content.published`                                                   | content | 回查元数据（事件体仅定位键）→ 乱序门控（status=2 且 PUBLIC 才写）→ IndexVersionGuard 版本比较（旧/同版本跳过）→ upsert `article_idx`（含向量预计算）+ 摄入 `article_chunks`                                                                                                                                                                                                                                                                                                                  |
| `content.unpublished`                                                 | content | 删除对应文档与向量（article\_idx 文档 + article\_chunks 旧块）                                                                                                                                                                                                                                                                                                                                                                                                 |
| `content.article-updated` ✅（2026-11-03 批次A A2 定案；契约落地 temp 批次B B7）    | content | 已发布（status=2）文章编辑元数据（title/tags/summary/cover）后 afterCommit 发布；search 轻量回查 → IndexVersionGuard 版本比较 → partial update 元数据字段（不动向量与 chunks）。事件体 ContentArticleUpdatedEvent + 路由键 ROUTING\_CONTENT\_ARTICLE\_UPDATED 已入 litisle-api/MqConstant；content 侧发布点已接入（批次B B7，2026-11-03：ArticleService#update 元数据变更后 afterCommit 发送）                                                                                                                       |
| `content.view-count-flushed` ✅（2026-11-03 批次A A5 定案；契约落地 temp 批次B B7） | content | content 浏览量 flush 任务（ViewCountFlushJob）落库后补推；search **仅以覆盖 SET 写 view\_cnt 绝对值到 Redis 热度待刷新键**（A7，不直写 ES），由 IndexHeatFlushJob 定时 `_bulk` 重算 heat，使 HEAT 排序与 content 热门榜口径对齐。事件体 ContentViewCountFlushedEvent（viewCount 为最新绝对值，覆盖 SET 非累加）+ 路由键 ROUTING\_CONTENT\_VIEW\_COUNT\_FLUSHED 已入 litisle-api/MqConstant；content 侧发布点已接入（批次B B7，2026-11-03：ViewCountFlushJob 每轮刷回成功后按篇补推）                                                                |
| `social.liked` / `social.commented` ✅（契约落地 temp 批次B B2，缺口 G2 闭环）      | social  | 消费后**仅** **`HINCRBY`** **累加 Redis 热度待刷新键的 like/comment 增量**（A7，不逐条直写 ES 防带向量整文档重写），由 IndexHeatFlushJob 定时 `_bulk` 重算 heat（function\_score 加权因子）。事件体 SocialLikedEvent / SocialCommentedEvent + EXCHANGE\_SOCIAL + 路由键 ROUTING\_SOCIAL\_LIKED/COMMENTED 已入 litisle-api/MqConstant；social 落地时 AFTER\_COMMIT 发布。**批次十一 11.8 起该事件由 content 点赞接口（LikeService AFTER\_COMMIT）发布，SocialLikedEvent 携带 `delta` 字段**，消费按 delta 累加（无 delta 兜底 +1、delta=0 跳过） |
| `social.unliked` ✅（批次十一 11.14 落地）                                     | content | 取消点赞负修正：事件体 SocialUnlikedEvent（delta=-1），content 侧 LikeService 取消成功后 AFTER\_COMMIT 发布；search 独立队列（QUEUE\_SOCIAL\_UNLIKED）消费，按负数 `HINCRBY` 累加 like 增量，IndexHeatFlushJob 写回 ES 前 `Math.max(0, …)` 下限保护；同一条消息重投不重复生效（幂等），失败与延迟重投共用既有 DLX/延迟交换机不另建拓扑。使 search likeCnt 热度信号与 content 真源（article\_stat.like\_count）双向同步                                                                                                                               |

> 事件体纪律（对齐 content-index §八）：content 事件只带 bizType/bizId/contentHash 定位键，upsert 所需标题/标签等元数据经 ContentClient 回查（字段不足扩提供方端点，禁事件体塞大字段，缺口 G3）。⚠️ 回查字段充足性 + 审核终态/DFA 结论获取方式待核（temp 批次B B6，契约待落地）。
>
> ⚠️ book\_idx / user\_idx 尚无索引同步事件来源（书籍创建/变更/完结/下架、用户注册/资料修改），契约待落地（temp 批次B B4/B5）。
>
> 补记（2026-11-05）：上述 ⚠️ 已随批次五 B4/B5 收口——书籍事件见 [IndexSyncConsumer.java:204](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L204)，用户注册/资料更新事件见 [IndexSyncConsumer.java:245](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L245) / [:258](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L258)；原警示句保留以留存历史口径。

消费铁律（对齐 review 规范）：手动 ACK；消费幂等（按事件体 articleId 幂等更新）；索引失败入 DLQ 重试；ES 不可用降级返回热门列表（§八-5，批次A A3 定案）。

***

## 六、配置说明

> 配置体系与 user/file 一致：`application.yml` 主配置 + 环境特定值（local/dev.yaml）+ Nacos `litisle-search.yaml` / `litisle-shared.yaml`。

| 配置项                                                                                                     | 默认值                                  | 说明                                                                                                                                                                                                                                                                                                   |
| ------------------------------------------------------------------------------------------------------- | ------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `litisle.search.es.hosts`                                                                               | `localhost:9200`                     | ES 集群地址                                                                                                                                                                                                                                                                                              |
| `litisle.search.es.username / password`                                                                 | —                                    | ES 凭证，生产走环境变量                                                                                                                                                                                                                                                                                        |
| `litisle.search.es.connect-timeout / socket-timeout`                                                    | 3s / 10s                             | 查询超时（ai 侧 RAG 依赖此超时可容忍）                                                                                                                                                                                                                                                                              |
| `litisle.search.index.article / book / user`                                                            | article\_idx / book\_idx / user\_idx | 索引名（外置，便于分环境）                                                                                                                                                                                                                                                                                        |
| `litisle.search.weight.title / tags / summary`                                                          | 4 / 3 / 2                            | multi\_match 字段权重                                                                                                                                                                                                                                                                                    |
| `litisle.search.weight.heat.like / comment / view / time-decay-half-life`                               | 2 / 3 / 0.1 / 168h                   | heat 公式权重 + 时间衰减半衰期（衰减后热度按半衰期折半，评论 > 点赞 > 浏览，Nacos 可调；2026-11-03 批次A A5 定案 + 增强采纳）                                                                                                                                                                                                                   |
| `litisle.search.heat.flush.interval-ms / initial-delay-ms / batch-size / concurrency / pending-key-ttl` | 900000（15min）/ 60000 / 1000 / 2 / 2h | 互动热度刷写：liked/commented/view-count-flushed 消费仅累加 Redis 热度待刷新键，本任务按周期聚合 `_bulk` 更新 heat（fixedDelay 不堆积，每篇每窗口至多 1 次写，容忍 15\~30min 滞后）；`_bulk` 按 batch-size（主流区间 500\~2000）× concurrency（2\~4）分片执行，bulk 用 update+doc 只改 heat 字段禁 index 整篇替换；pending 键 TTL ≥2 个刷写周期防未刷先清（2026-11-03 批次A A7 定案新增 + 工程细节补充） |
| `litisle.search.knn.top-k / num-candidates`                                                             | 10 / 100                             | KNN 参数                                                                                                                                                                                                                                                                                               |
| `litisle.search.knn.similarity-threshold`                                                               | 0.0（默认不过滤）                           | 相似推荐阈值（ai RAG 的 0.65 阈值属 ai 侧，不在此收敛）                                                                                                                                                                                                                                                                 |
| `litisle.search.cache.hot-query-enabled / ttl`                                                          | true / 10m                           | 热点查询缓存                                                                                                                                                                                                                                                                                               |
| `litisle.search.suggest.enabled / size`                                                                 | true / 10                            | 搜索联想开关与默认条数（2026-11-03 新增）                                                                                                                                                                                                                                                                           |
| `litisle.search.history.max-per-user`                                                                   | 20                                   | 单用户搜索历史上限，超限懒删最旧（2026-11-03 新增）                                                                                                                                                                                                                                                                      |
| `litisle.search.page.max-page-size`                                                                     | 50                                   | 分页上限                                                                                                                                                                                                                                                                                                 |
| `litisle.search.rabbit.sync-queue / dlx`                                                                | （预留）/ `litisle.search.dlx`           | dlx=索引同步死信交换机（Direct，**十同步队列**死信按队列名（`deadLetterRoutingKey(queueName)`）汇入 `litisle.search.index.dlq.queue`，SearchMqConfig 声明；批次十一 11.14 新增取消点赞队列后共 10 条绑定）；sync-queue 为预留 key（实际同步队列以 MqConstant 队列为准，拓扑声明不读取此值；2026-11-03 D1 骨架落地修正默认值）                                                             |
| `litisle.search.retry.max-count / base-delay-ms`                                                        | 5 / 30000                            | 索引同步消费异常按指数退避延迟重投（baseDelayMs × 2^已重试次数，经 search.delay 延迟交换机），超过 maxCount 仍失败 nack 入死信队列（批次九落地；SearchProperties.retry）                                                                                                                                                                               |
| `litisle.search.metrics.backlog-collect-interval-ms / backlog-collect-initial-delay-ms`                 | 15000 / 15000                        | 索引同步队列积压采集任务周期（IndexBacklogCollectJob，写入 gauge `litisle_search_index_backlog{queue}`，支撑「积压持续 >5 分钟」告警；批次九 9.9-F）                                                                                                                                                                                     |
| `litisle.search.fallback.embedding-timeout`                                                             | 1s                                   | 空结果兜底查询词实时 Embedding 超时上限（超时/失败二级兜底 HEAT 热门列表，2026-11-03 批次A A1 定案新增）                                                                                                                                                                                                                                |
| `litisle.search.fallback.enabled`                                                                       | true                                 | ES 不可用降级开关（降级返回热门列表 + 前端提示，2026-11-03 批次A A3 定案，原 db-query-enabled 废弃）                                                                                                                                                                                                                               |

***

## 七、异常与错误码

> ✅ SEARCH\_\* 四码已入 common-core ErrorCode（缺口 G4 闭环，temp 批次B B3 落地 2026-11-03）：search 新模块分段 100000-109999（SEARCH\_PARAM\_INVALID 100001 / SEARCH\_PAGE\_EXCEEDED 100002 / SEARCH\_SOURCE\_NOT\_FOUND 100003 / SEARCH\_ES\_UNAVAILABLE 100004），Api-Architecture.md §10 分段表已同步。

| 场景                    | 错误码                        | 说明                                       |
| --------------------- | -------------------------- | ---------------------------------------- |
| 查询词为空/超长、sort/type 非法 | SEARCH\_PARAM\_INVALID     | 参数校验失败                                   |
| pageNum/pageSize 超限   | SEARCH\_PAGE\_EXCEEDED     | pageSize > max-page-size                 |
| 相似推荐源文章不存在/已下架        | SEARCH\_SOURCE\_NOT\_FOUND | 取不到源向量时抛                                 |
| ES 不可用且降级关闭/降级也失败     | SEARCH\_ES\_UNAVAILABLE    | 降级关闭或热门列表获取失败时抛（批次A A3 定案）               |
| 请求过频                  | TOO\_MANY\_REQUESTS(10005) | @RateLimiter / Sentinel 触发（对齐 file 限流体系） |

***

## 八、安全与可靠性

1. **ES 注入防护**：查询词对特殊字符（`+ - = && || > < ! ( ) { } [ ] ^ " ~ * ? : \ /`）转义；排序/筛选字段仅白名单枚举映射，不透传用户输入构造 DSL
2. **可见性双保险**：写入侧仅 PUBLIC 入索引（§四红线）+ 查询侧强制 `filter(visibility=PUBLIC)`，防脏数据越权召回
3. **下架全路径移除**：`content.unpublished` 四条下架路径（删除/转私密/复审驳回/封禁连带）必须到达，否则违规内容残留向量库（隐私红线，同 Design-Document §2.6）
4. **消费可靠性**：手动 ACK + 按 articleId 幂等 upsert/删除 + 消费异常**指数退避延迟重投**（baseDelayMs × 2^重试次数，超 `retry.max-count` 入 DLQ）+ 取消点赞独立队列（批次十一 11.14）；无本地状态，可水平扩展。**可观测（批次九）**：DLQ 死信按 originQueue 计数（`litisle_search_index_dlq_total{originQueue}`）配 error 日志告警；队列积压 gauge `litisle_search_index_backlog{queue}` 定时采集（IndexBacklogCollectJob）；规则组 `litisle-search`。search 自身指标族：`litisle_search_requests_total{type,outcome}` / `litisle_search_es_errors_total{type}` / `litisle_search_heat_flush_round_total` / `litisle_search_heat_flush_docs_total{result}` / `litisle_search_cache_dirty_mark_total{result}` / `litisle_search_cache_evict_merge_total{result}`（收口 SearchMetrics）。**版本守卫**：文本索引写入前统一经 `IndexVersionGuard` 比较 ES 现有 `updateTime`（相等也跳过；读 ES 失败抛可恢复异常交延迟重试）；MQ 消费线程调用 Feign 回查时无 SaToken 上下文，`FeignAuthInterceptor` 降级为仅透传 traceId（不伪造用户身份、不抛异常阻断消费）
5. **降级链路**（2026-11-03 批次A A3 定案）：ES 不可用 → 降级返回热门列表（content `/article/hot` 同源数据，`source=DEGRADED` 标记不缓存）+ 前端提示搜索不可用（不做 Feign 查业务方 DB 降级——content 无公开 keyword 检索端点，公开搜索已全划归 search）；降级关闭或热门列表获取失败 → SEARCH\_ES\_UNAVAILABLE；ai RAG 向量库不可用由 ai 侧静默降级纯 LLM，search 仅保证超时可容忍。**数据源语义备注（2026-11-03）：`/article/hot`** **将随 content 临时清单批次E P5 切换为 heat 榜序**（`CONTENT_FEED_HOT_ZSET` 同源 + 榜空回退 view\_cnt 直查），降级结果口径落地时对齐
6. **缓存规范**：热点查询 Redis 缓存，key 遵循企业规范 `search:模块:功能:唯一标识`；互动热度更新失效对应缓存
7. **限流**：@RateLimiter(USER) 单用户维度，全站 QPS/Sentinel 规则外置 Nacos（对齐 file 限流三层体系，按需裁剪）
8. **向量一致性**：Embedding 模型版本变更 → 全量重建索引（vec\_dense 维度/语义漂移），模型版本写入索引 meta 防错位
9. **搜索词隐私**：搜索历史 keyword 仅存 search\_history 自有表，**不写入任何 ES 索引文档/向量库**（2026-11-03 新增，防用户搜索意图被检索侧聚合暴露）

***

## 九、next 清单生成依据（☆ 待建分解，对齐 temp.md search模块 批次A\~D）

按落地顺序建议（批次A 缺陷修正 + 批次B 契约 + 批次C 决策已定版 2026-11-03，批次D search 本体待建，细案见 temp.md）：

> 补记（2026-11-05）：上句「批次D search 本体待建」为 2026-10-19 设计先行口径。实际进度：批次二~九已于 2026-11-04/05 落地并归档（实现记录见 [search 归档索引](../archive/search/search-implemention-records.md)），下方七条仅作历史分解依据；当前待办以 [next\_update.md](../next/next_update.md) search 模块为准。

1. ☆ 契约与决策（已定版）：SearchClient/social 事件/SEARCH\_\* 错误码契约待落地（批次B B1\~B7）；向量维度统一单模型（C1）、章节 RAG 边界一期不入（C2）、KNN 强制 PUBLIC 过滤（C3）、匿名策略（C4）均已定案
2. ☆ 基础骨架：es 配置 + SearchConfig + constant/enums + 索引 mapping 初始化（article\_idx 含 suggest 字段 + article\_chunks）+ search\_history 表
3. ☆ 索引同步：IndexSyncConsumer + Rabbit 声明 + DLQ + 幂等 upsert/删除 + 乱序防御门控
4. ☆ 全文检索：SearchService + Controller（bool+function\_score + 防注入 + tag/category/zone/时间范围筛选 + 空结果兜底 + 分页）
5. ☆ 搜索联想与历史：suggest 接口 + SearchHistoryService（UPSERT/懒删/清空）
6. ☆ 相似推荐：VectorService + KnnRecommender + SimilarController
7. ☆ 兜底：ES 降级查 DB + 热点查询缓存 + 指标埋点

***

## 十、推荐流召回的定位与 P1 规划（2026-11-05 新增）

> 背景：content 模块批次十「用户个性化推荐流」立项，细案见 [next\_update.md](../next/next_update.md) 批次十。本章只登记 search 在推荐流链路里的边界、P1 规划与前置改造，不修改前九章既有设计。

### 10.1 定位边界（主结论）

**search 负责「人找内容」——站内搜索、搜索联想、相似推荐；不负责「内容找人」——推荐流 P0 的召回与分页一律不走 ES。**

- P0（当前）：content 自建 4 路 DB 召回（最热 / 最新 / 常看分区 / 常看分类）+ Redis 快照分页（单快照 ≤300 条、`cursor=offset`、`from` 恒 0、翻页不重算分）。P0 代码落点、打分权重、降级三级与指标口径见 §10.8。
- P1（按触发条件切换）：search 只作为**其中一路召回器**，一次性返回 `id + score`；快照、翻页、打散、曝光去重、降级全部留在 content。
- 决策与阈值原文见 [next\_update.md](../next/next_update.md#L240) 批次十「二、已确认决策」第 13 条；用户隔离与算分口径见同文「四、用户隔离与算分口径（已确认）」，P1 改造边界见「五、P1 改造边界（换发动机，不新开链路）」。

### 10.2 当前三件实活（均已落地）

> 接口参数与 VO 口径已在 §3.1 列明，本表只补**代码落点**与实现要点，不重复契约定义。

| 能力   | 入口                                                      | 代码位置                                                                                                                                                                                                                                                                       | 实现要点                                                                                                                                                      |
| ---- | ------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 全文检索 | `GET /api/search`（类映射 L38 / 方法 L55）                     | [SearchController.java](../../litisle-search/src/main/java/com/litisle/litislesearch/controller/SearchController.java#L55)；DSL 在 [SearchServiceImpl.java](../../litisle-search/src/main/java/com/litisle/litislesearch/service/impl/SearchServiceImpl.java#L508) L508-L536 | `bool.must(multi_match)`（title^4 / tags^3 / summary^2 / content）+ `filter(visibility / tag / category / zone / 时间范围)` + RELATED 附加 `function_score(heat)` |
| 搜索联想 | `GET /api/search/suggest`（L73）                          | [SearchController.java](../../litisle-search/src/main/java/com/litisle/litislesearch/controller/SearchController.java#L73)                                                                                                                                                 | title / authorName 的 completion 与 prefix 联想；标签联想复用 content 标签接口聚合                                                                                         |
| 相似推荐 | `GET /api/search/similar/{articleId}`（L31 类映射 / L46 方法） | [SimilarController.java](../../litisle-search/src/main/java/com/litisle/litislesearch/controller/SimilarController.java#L46)                                                                                                                                               | KNN `vec_dense` + 标签重合 + 行为加权，强制 `filter(visibility=PUBLIC)`                                                                                              |

配套入口：Feign 侧 `/api/feign/search/similar`、`/api/feign/search/search`（[SearchFeignProviderController.java](../../litisle-search/src/main/java/com/litisle/litislesearch/controller/feign/SearchFeignProviderController.java#L48) L31 / L48 / L61）；搜索历史为登录侧辅助能力（[SearchHistoryController.java](../../litisle-search/src/main/java/com/litisle/litislesearch/controller/SearchHistoryController.java#L47) L32 / L47），不参与推荐链路。

### 10.3 索引同步链路（MQ → article\_idx / book\_idx / user\_idx）

> 各事件的语义与事件体纪律已在 §五 列明，本表只补**消费端代码落点**，不重复事件契约。

| 事件                                                                                    | 消费方法                                                   | 代码位置                                                                                                                                                                                                                                                                                                                                                                                                                                                           | 动作                                                                                          |
| ------------------------------------------------------------------------------------- | ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `content.published`                                                                   | onPublished                                            | [IndexSyncConsumer.java:90](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L90)                                                                                                                                                                                                                                                                                                                                        | 回查元数据 → 门控 → 版本守卫（IndexVersionGuard）→ upsert article\_idx（含向量预计算）+ 摄入 article\_chunks       |
| `content.unpublished`                                                                 | onUnpublished                                          | [IndexSyncConsumer.java:130](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L130)                                                                                                                                                                                                                                                                                                                                      | 删 article\_idx 文档与旧 chunks（幂等，404 视为成功）                                                     |
| `content.article-updated`                                                             | onArticleUpdated                                       | [IndexSyncConsumer.java:166](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L166)                                                                                                                                                                                                                                                                                                                                      | 版本守卫（IndexVersionGuard）→ partial update 元数据字段（不动向量与 chunks）                                 |
| `content.book-changed`                                                                | onBookChanged                                          | [IndexSyncConsumer.java:204](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L204)                                                                                                                                                                                                                                                                                                                                      | upsert book\_idx（批次五 B4）                                                                    |
| `user.registered` / `user.profile-updated`                                            | onUserRegistered / onUserProfileUpdated                | [IndexSyncConsumer.java:245](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L245) / [:258](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/IndexSyncConsumer.java#L258)                                                                                                                                                                                                                                | upsert user\_idx（批次五 B5）；版本源取 user 与 user\_profile 的 updateTime 较大者，写入前过 IndexVersionGuard  |
| `social.liked` / `social.unliked` / `social.commented` / `content.view-count-flushed` | onLiked / onUnliked / onCommented / onViewCountFlushed | [HeatEventConsumer.java:69](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/HeatEventConsumer.java#L69) / [:117](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/HeatEventConsumer.java#L117)（onUnliked，批次十一 11.14）/ [:159](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/HeatEventConsumer.java#L159) / [:191](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/HeatEventConsumer.java#L191) | 只累加 Redis 热度待刷新键（HINCRBY，取消点赞按负数累加，写回前 `Math.max(0,…)` 下限保护），由定时 `_bulk` 重算 heat            |
| 索引创建与字段补齐                                                                             | run → createIndexIfAbsent                              | [EsIndexInitializer.java](../../litisle-search/src/main/java/com/litisle/litislesearch/es/EsIndexInitializer.java#L50) L50 / L107；article mapping L133-L168、book L176-L193、user L201-L212                                                                                                                                                                                                                                                                      | 启动时幂等创建 article\_idx / book\_idx / user\_idx / article\_chunks，并对已存在 book\_idx 幂等补 authorId |

### 10.4 P1 规划：作为推荐流的一路召回器

- 一次召回返回 **≤300 条 `id + score`**，`track_total_hits=false`；**永不参与逐页查询与深分页**（分页仍由 content 快照 `cursor` 切片）。
- 查询形态：`bool filter`（`delFlag=0`、`status=PUBLISHED`、`visibility=PUBLIC`、`zone/category`）+ `function_score`（`heat` 加权 + `gauss(updateTime)` 时间衰减 + 用户 Top5 标签的**有界 `should.term`** 加权）。
- 结果只作为 content 的第 5 路召回输入，与 DB 四路合并、去重、打散、落快照；content 不对 ES 结果做二次逐页查询。
- ES 不可用或超时时，由 content 侧自动回落数据库四路；`litisle.content.feed.recall=db|es` 开关必须有真实回落分支，不能只做摆设。

### 10.5 前置改造清单（切换前必须完成）

| 项                   | 现状                                                                                                                                                       | 需做                                                                                                                                                                               |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `article_idx` 删除态字段 | mapping 无 `delFlag`（见 [EsIndexInitializer.java](../../litisle-search/src/main/java/com/litisle/litislesearch/es/EsIndexInitializer.java#L133) L133-L168） | 补 `delFlag` 字段 + 写入侧同步，否则软删文章仍可能被召回                                                                                                                                              |
| `book_idx` 推荐字段     | 仅 id/title/authorId/authorName/summary/category/updateTime（L176-L193），缺 `status` / `collectCount` / `readerCount`，无 zone、无向量                             | 补计数与状态字段，写入侧随书籍事件同步；zone 是否补按书籍流决策（当前书籍流不参与 zone）                                                                                                                                |
| 写入侧预计算              | 索引无 `heatScore` / `tagCount` 落库                                                                                                                          | 消费事件时预计算写入，避免查询期脚本算分                                                                                                                                                             |
| mapping 变更          | 既有索引的 date format 不可在线变更                                                                                                                                 | 只能新建索引或走重建流程（重建框架见 [VectorRebuildAdminController.java](../../litisle-search/src/main/java/com/litisle/litislesearch/controller/VectorRebuildAdminController.java#L29) L29 / L42） |

### 10.6 切换阈值（满足任一即把 feed 召回切到 ES）

> 与批次十「二、已确认决策」第 13 条同源，此处作架构侧登记，不另立标准。

1. 文章行数 > 30 万；
2. 补索引后 DB 召回 P99 > 120ms 且持续 3 天；
3. 需要关键词兴趣召回或向量召回；
4. 筛选维度 ≥ 4 且组合种类 ≥ 3 种。

### 10.7 `tags` 字段能力说明

`article_idx.tags` 为 `keyword` 数组（mapping 见 [EsIndexInitializer.java](../../litisle-search/src/main/java/com/litisle/litislesearch/es/EsIndexInitializer.java#L144) L144，注释在 L143）：

- **支持**：`terms` 精确过滤；对用户 Top5 标签做**有界 `should.term`** 加权（命中个数即标签重合度，成本与标签数线性，无需 nested）；
- **不支持也不应做**：用高基数 `terms` 聚合算标签匹配度（聚合需在 doc\_values 上全量扫描，且 `cardinality` 存在约 40% 误差，代价远高于打分本身）；
- 标签偏好的画像聚合留在 content（P0 不读 `article_tag`；P1 启用前先按批次十已确认决策改 [content-schema.sql](../../docker/init/mysql/schema/content-schema.sql)（`article_tag` 表注释）的注释口径）。

### 10.8 P0 真实落地形态与指标口径（2026-11-06 补记）

> 10.1\~10.4 为立项期规划，本节按 content 实际代码核对落地形态；两者冲突时以代码为准。
> 批次十清单 10.1~10.6 已实现并编译通过，10.7/10.8 待环境验证（见 [next\_update.md](../next/next_update.md)）。

**文章流召回（四路 DB，去重后统一打分）**

| 召回路  | 查询方式                                             | 单路条数                          |
| ---- | ------------------------------------------------ | ----------------------------- |
| 最热   | `FeedHotRankSupport.topArticleIds` 取榜序 → 按 ID 回查 | `recall-limit-per-path=100`   |
| 最新   | `articleGate()` 按 `create_time` 倒序               | 100；热度榜为空时放大到 `pool-size=300` |
| 常看分区 | 画像 `preferredZones` 过滤                           | 100；冷启动跳过                     |
| 常看分类 | 画像 `preferredCategories` 过滤                      | 100；冷启动跳过                     |

- 门控统一走 `articleGate()`：`zone ∈ {1 普通, 2 作家}`、`status=PUBLISHED`、`visibility=PUBLIC`、`del_flag=0`，且不 select 正文大字段。
- 曝光硬去重：当日曝光 Set 在**建池时**与**切片时**各过滤一次，续池/跨快照代次继续生效。
- 已读只降权不剔除：`read-penalty=0.5`，判定窗口 `read-window-days=30`。
- 打分权重（`article-weights`）：热度 0.35 + 新鲜度 0.30 + 分区匹配 0.10 + 分类匹配 0.20；新鲜度半衰期 `fresh-half-life-hours=24`。
- 探索与打散：常规探索位 `exploration-ratio=0.15`，冷启动上调到 `cold-start-exploration-ratio=0.30`；同作者连续上限 1、同分类连续上限 2。

**书籍流召回与打分**

- 按分类偏好召回（冷启动不限定分类，取全局候选），门控：`status ∈ {连载中, 已完结}` 且 `del_flag=0`（下架永不进流），书籍不参与 zone。
- 四权重（`book-weights`）：收藏 0.4 + 阅读人数 0.3 + 新鲜度 0.2 + 完结偏好 0.1；前两类计数先 `log1p` 归一化。
- 新书保底：`new-book-days=7`、`new-book-quota=6`（每个快照前排保证出现的新书条数）。

**快照与游标**

- 快照结构：条目顺序 + 分数 + seed + degraded 标记；单身份活动快照 ≤ `max-active-snapshots=3`，TTL `snapshot-ttl-seconds=1800`。
- `snapshotId` 由 Redis INCR 代次生成（`content:feed:snapshot:gen:{scope}`），保证单调递增；`cursor` 编码快照内偏移，切片 `from` 恒 0，翻页不重算分。
- 翻到底自动建下一代快照（`reset=false`，前端追加）；快照过期/非法游标 `reset=true`（前端清空重拉）；文章按 zone 隔离作用域，书籍不加 zone 后缀。

**降级三级（全部经 `FeedStreamServiceImpl#logDegrade` 单入口留痕）**

1. Redis 不可用或建池失败 → 优先 last-good：内存副本 + Redis 指针 `content:feed:snapshot:last:{scope}`，返回上一代成功快照首页；
2. 无 last-good → 实时计算一页（`degraded=redis-down` / `build-failed`），无 cursor/snapshotId、`hasMore=false`；
3. 热度榜为空 → 「最新」单独兜底（`degraded=hot-empty`），最新召回量放大到 `pool-size`，快照仍可正常续刷。

> last-good 与实时兜底均为终页（不可续刷）；last-good 不写曝光记录。

**画像与缓存**

- 维度只有「常看分区 + 常看分类」；标签偏好仅 `FeedProfile#preferredTags` 结构占位（P1）。
- 缓存键按身份隔离：`content:feed:profile:u{userId}` / `content:feed:profile:a{anonId}`，TTL `profile-cache-ttl-seconds=900`；阅读行为不主动失效，接受 TTL 内滞后。
- 冷启动判定：`read-window-days` 天内无文章阅读记录即 `coldStart=true`，两路个性化跳过并上调探索位；匿名恒为冷启动且不跨设备。

**指标口径（content 已接 actuator + micrometer-registry-prometheus）**

| Prometheus 指标                                   | 类型      | 标签                          | 含义                       |               |           |                |                         |
| ----------------------------------------------- | ------- | --------------------------- | ------------------------ | :------------ | :-------- | :------------- | :---------------------- |
| `litisle_content_feed_degraded_total`           | Counter | \`stream=article            | book`、`reason=redis-down | last-good     | hot-empty | build-failed\` | 降级次数，只在 `logDegrade` 打点 |
| `litisle_content_feed_snapshot_created_total`   | Counter | `stream`、\`cold\_start=true | false\`                  | 快照创建次数（含空池快照） |           |                |                         |
| `litisle_content_feed_profile_cache_hit_total`  | Counter | 无                           | 画像缓存命中                   |               |           |                |                         |
| `litisle_content_feed_profile_cache_miss_total` | Counter | 无                           | 画像缓存未命中（回源聚合并覆盖写）        |               |           |                |                         |

- 管理端点与业务端口共用 8085（未单独设置 `management.server.port`），抓取路径 `/actuator/prometheus`；Prometheus 目标 `host.docker.internal:8085`。
- 告警：`ContentFeedDegradedRateHigh`（warning，降级速率 > 0.5 次/秒持续 10 分钟）与 `ContentFeedRedisDown`（critical，`reason=redis-down` 任一新增）；规则不补 `or 0`，采集缺失按 no data 不触发。

**P1 边界与实际代码对照**

| 规划项（§10.4 / §10.5）                                   | 代码现状                                                             | 结论             |
| ---------------------------------------------------- | ---------------------------------------------------------------- | -------------- |
| `litisle.content.feed.recall=es` 真实回落                | `warnIfEsRecallConfigured` 检测到 `es` 仅打 WARN 后继续按 db 执行；无 ES 查询实现 | 开关存在，P1 召回器未开工 |
| search 一次返回 ≤300 条 `id + score`                      | 无 content → search 召回调用                                          | P1             |
| `article_idx` 补 `delFlag` / `heatScore` / `tagCount` | search 侧 mapping 仍未补（§10.5）                                      | P1 前置未完成       |
| 标签偏好参与打分                                             | 仅结构占位，不读 `article_tag`                                           | P1             |
| 快照/分页/打散/曝光去重/降级留在 content                           | 已按本节落地                                                           | ✅ P0           |

***

## 十一、前端配合事项清单（2026-11-06 新增）

> 定位：本节只列前端要落地的实事，不展开后端架构。接口以 [FeedController.java:78-135](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/FeedController.java#L78-L135) 与 [FeedPageVO.java:23-45](../../litisle-content/src/main/java/com/litisle/litislecontent/domain/vo/FeedPageVO.java#L23-L45) 为准；频控/未授权码以 [ErrorCode.java:50-65](../../litisle-common/common-core/src/main/java/com/litisle/commoncore/exception/ErrorCode.java#L50-L65) 为准。
> 批次归属：批次十 = 无限刷流本体；批次十一 = 统计口径与归因闭环。标注「P1，未启动」的条目未启动前不得先行开发。

| # | 前端要做的事                                                                                                                                                   | 涉及接口/字段                                                                                                       | 验收怎么判断                                                                                                                     | 批次                         | 优先级       |
| - | -------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | -------------------------- | --------- |
| 1 | 匿名标识：生成 UUID v4，存 `localStorage`；每个流请求带 `X-Anon-Id`；响应头若回下发 `X-Anon-Id`，写回本地并后续沿用                                                                        | `GET /api/content/feed/articles`、`/books`；请求头 `X-Anon-Id`；服务端回写响应头                                            | 首次无 header 请求能拿到回填值并持久化；刷新/重启浏览器后值不变；登录后不带也不报错                                                                             | 批次十（后端 `AnonIdFilter` 已实现） | P0        |
| 2 | 两条流端点对接：首次请求不传 `cursor`/`snapshotId`；后续原样回传；`reset=true` 清空列表并按新快照重拉；`hasMore=false` 停止加载                                                                | 入参 `cursor`、`snapshotId`、`pageSize`、`zoneCode`（仅文章流）；出参 `items`、`cursor`、`snapshotId`、`hasMore`、`reset`       | 连续翻页不重复/不跳条；快照失效时列表清空重来；`hasMore=false` 后不再发请求                                                                             | 批次十                        | P0        |
| 3 | 降级提示：按 `degraded` 四值分别处理 `redis-down` / `build-failed`（服务繁忙、内容有限）、`hot-empty`（热门暂缺）、`last-good`（终页，弱提示「内容有限」）                                            | 出参 `degraded`                                                                                                 | 四值都有明确文案；`last-good` 不显示无限加载、不请求下一页                                                                                        | 批次十                        | P0        |
| 4 | 频控与未授权处理：`code=10005`（当前 HTTP 200 + 业务码；网关若返回 HTTP 429 同样按频控处理）统一提示并退避；`code=10002`（未登录）只在点赞/收藏等需登录接口出现，统一引导登录，不当作普通错误（流接口本身允许匿名，不应因未登录报错）               | 响应 `code/message`；错误码 `10005`/`10002`                                                                         | 超频有提示且不自动死循环重试；未登录刷流正常；点赞/收藏时弹登录引导；登录态恢复后正常续刷                                                                              | 批次十 + 批次十一                 | P0        |
| 5 | 点击归因：从流进入文章/书籍详情时带 `from=feed&snapshotId=...`，可选 `position`；非流入口不带                                                                                       | 详情接口查询参数 `from`、`snapshotId`、`position`                                                                       | 流内跳转详情请求参数齐全（`from=feed` + 当前 `snapshotId`，`position` 可选）；直接搜索/收藏入口进入时不带；后端按日聚合 `click_count/exposure_count`，未带参数时只计曝光不计点击 | 批次十一（11.6）                 | P0        |
| 6 | 点赞/取消点赞交互：未登录时拦截并引导登录；作者本人点赞时展示后端明确业务码对应的提示（不要当成成功）                                                                                                      | 批次十一新增 `POST/DELETE /like`；`targetType`、`targetId`；返回业务码                                                      | 三种拒绝（未登录/目标不可见/作者自赞）都有明确提示；点赞状态与计数刷新正确                                                                                     | 批次十一（11.8）                 | P0        |
| 7 | 负反馈入口（后端已就绪）：卡片/详情「不感兴趣」、作者「屏蔽该作者」、分类「屏蔽该分类」；提交成功后当前列表立即移除对应条目；撤销需前端自己保存 `(feedbackType, stream, targetId, targetKey)`（**后端本期不提供名单查询接口**，待确认是否需要补一个列表接口） | `POST /api/content/feed/feedback`（body `{feedbackType, stream, targetId, targetKey}`）、`DELETE` 同参；必须登录，20/60s | 三维度都能提交成功；重复提交不报错（幂等）；`feedbackType=1` 当页起消失；`feedbackType=2/3` 下一代快照消失；未登录返回 10002                                        | 批次十一（11.11）                | 后端已就绪，待前端 |
| 8 | 阅读时长心跳：本轮**仅预留**上报字段，后端不写入、不统计；前端先不上报，等心跳口径（间隔/有效判定/聚合方式）定稿后再接入                                                                                          | `PUT /api/content/reading-history` 可选参数 `durationMs`（当前被显式忽略）                                                 | 当前无验收：传与不传都不影响历史上报结果                                                                                                       | 批次十一（11.11 预留）             | 仅预留接口     |

**处理约定**

- `last-good` 是终页：响应无 `cursor`、`hasMore=false`，前端不得再发续页请求。
- `reset=true` 时必须丢弃旧列表与旧 `snapshotId`，用本次响应重新渲染，避免快照混用。
- 频控当前是 HTTP 200 + 业务码（不是 HTTP 429）；若网关或代理返回 429，统一按频控分支处理。
- 第 5 条（点击归因）已按 2026-11-06 决策转为 P0 可开发；第 6 条（点赞）后端已完成；第 7 条（负反馈）后端已完成、待前端接入；第 8 条（时长心跳）仅预留参数，前端暂不开发。
- 负反馈已知表现：`feedbackType=1`（单条）当页立即生效（切片二次过滤）；`feedbackType=2/3`（作者/分类）**只在下一次快照生效**，旧快照剩余页仍可能出现，属预期，不要当 bug 报。
- 负反馈无法匿名提交（匿名身份不稳定，做不了长期屏蔽）；匿名用户点「不感兴趣」应先引导登录。
- 完读统计（11.13）是服务端离线统计，前端**无需埋点**，也不提供展示接口（本轮只进 `book_read_quality` 表）。
- 条目与批次十一任务的对应关系：第 5 条对应 11.6，第 6 条对应 11.8，第 7 条对应 11.11，第 8 条对应 11.11 预留项（时长心跳）；批次十条目沿用 [next\_update.md](../next/next_update.md) 批次十已确认决策。
