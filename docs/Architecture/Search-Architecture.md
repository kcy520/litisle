# litisle-search 搜索模块架构文档（简化版）

> 版本：v0.1（2026-10-19，设计先行版；2026-11-03 批次A~C 定版 + 批次B 契约层落地 + 批次D D1 基础骨架先行落地；2026-11-03 批次一 1.1~1.3 落地：四索引 mapping 初始化 + search_history DDL（Sql-Document §五）+ Feign 启用，任务细目见 next_update.md search模块）
>
> 关联文档：[Design-Document.md](../overall_documen/Design-Document.md) §2.3.9（搜索服务总设计）/ §2.6（RAG 知识库）· [Sql-Document.md]（search 无 MySQL 表，仅作确认）· [Api-Architecture.md](Api-Architecture.md)（Feign 契约）· [commom-des.md](commom-des.md)
>
> 阅读约定：本文只保留**结论与机制**；★=已落地，✅骨架=骨架已落地（业务逻辑批次D 余项），☆=待建

------

## 一、模块定位与边界

| 项目     | 说明                                                         |
| -------- | ------------------------------------------------------------ |
| 定位     | 独立微服务，站内检索统一收口方：**ES 索引同步 + 全文/多维度检索 + dense_vector KNN 相似推荐** |
| 技术选型 | Elasticsearch 8.18.8（全文 + dense_vector KNN，零新增中间件）· RabbitMQ（Confirm/ACK/DLQ，索引异步同步）· Elasticsearch Java Client |
| 依赖方   | content / social / user 经 MQ 事件驱动索引更新（`content.published` / `content.unpublished` / `content.article-updated`🆕 / `content.view-count-flushed`🆕 / `social.liked` / `social.commented`）；ai 服务经 Feign 调用相似推荐向量；gateway 路由 `/search/**` |
| 硬边界   | search 无**业务数据** MySQL 表（ES 索引文档为投影，状态 SSOT 在业务方 DB）；唯一自有表 `search_history`（用户搜索词记录，2026-11-03 新增，见 §四）；search 不回写业务方数据（仅接收事件单向同步）；私有/草稿/拒绝文章永不入索引与向量库（与 review 可见性红线同源） |

### 1.1 三类能力

| 能力 | 载体 | 说明 |
| --- | --- | --- |
| 索引同步 | IndexSyncConsumer（MQ 消费） | 订阅发布/下架/互动事件，增量更新 `article_idx` / `book_idx` / `user_idx` 与热度向量；失败入 DLQ 重试 |
| 全文检索 | SearchService | bool + function_score：标题(^4) → 标签(^3) → 摘要(^2) → 全文；用户笔名模糊匹配；分类/分区/时间范围筛选 + 排序；**空结果兜底：查询词实时 Embedding → KNN 返回语义相似文章（2026-11-03 批次A A1 定案）** |
| 搜索联想 | SearchService（suggest） | 标题/作者 ES completion/prefix suggester；标签联想复用 content `GET /tags?keyword=` 聚合，不重复建标签索引 |
| 搜索历史 | SearchHistoryService | 用户搜索词记录（单表 UPSERT：同词去重仅前进 + 上限懒删）；搜索词不入任何 ES 索引 |
| 相似推荐 | VectorService + KnnRecommender | KNN 检索 `vec_dense`，相似度 = 标签重合 + 向量 + 行为加权；供 ai 服务 RAG 与前端"相关文章"复用 |

### 1.2 代码结构（✅骨架=2026-11-03 D1 骨架先行，业务逻辑批次D 余项；☆=待建）

```text
litisle-search
├── controller/       ☆ SearchController（GET /search?q=&type=&sort= / GET /search/suggest?q=）
│                     / SimilarController（GET /search/similar/{articleId}）
│                     / SearchHistoryController（GET /search/history / DELETE /search/history）
├── service/          ☆ SearchService（bool+function_score 检索编排 + suggest 联想 + 空结果兜底）
│                     / SearchHistoryService（搜索词 UPSERT/列表/清空）
│                     / VectorService（Embedding + KNN 查询）/ KnnRecommender（相似推荐加权）
│                     └── impl/（接口实现）
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
├── mq/               ✅骨架 SearchMqConfig（六同步队列 + 独立 DLX/DLQ 拓扑声明，批次D D2 起）；
│                     ☆ IndexSyncConsumer（文本/互动事件消费 + DLQ 消费者）
└── config/           ✅骨架 SearchProperties（litisle.search.* 参数外置）/ SearchMqConfig / ElasticsearchConfig
```

------

## 二、功能设计

### 2.1 功能清单

| 功能 | 说明 | 核心机制 |
| --- | --- | --- |
| 索引同步 | 事件驱动增量更新 | 消费 `content.published`/`content.unpublished`/`content.article-updated`🆕/`content.view-count-flushed`🆕/`social.liked`/`social.commented`；按 articleId 幂等 upsert/删除；向量预计算随发布一次写入；失败入 DLQ 重试 |
| 全文检索 | 文章/书籍/用户多维检索 | multi_match 权重 title^4 → tags^3 → summary^2 → content；function_score 热度加权；tag/category/zone/时间范围筛选 + 排序；**空结果兜底：查询词实时 Embedding → KNN 语义相似集（标记来源，2026-11-03 批次A A1 定案）** |
| 搜索联想 | 输入即联想 | 标题/作者 ES completion/prefix suggester；标签联想复用 content 标签接口聚合返回 |
| 搜索历史 | 用户维度搜索词记录 | 单表 UPSERT：同词去重仅前进 + 条数上限懒删 + 清空接口；搜索词不入 ES 索引 |
| 相似推荐 | KNN + 多因子加权 | 取源文章向量/标签 → KNN TopK（强制 filter visibility=PUBLIC）→ 标签重合度 + 向量相似 + 行为数据融合 → 阈值过滤；供 ai RAG 与前端复用 |
| 降级查询 | ES 不可用兜底 | 返回热门列表（content `/article/hot` 同源数据）+ 前端提示搜索不可用（`source=DEGRADED` 标记，结果不缓存；2026-11-03 批次A A3 定案） |

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
3. published → 校验 visibility=PUBLIC → Embedding 预计算 vec_dense → upsert 文档
4. article-updated → 轻量回查元数据 → partial update 元数据字段（title/tags/summary 等索引字段，不动向量与 chunks；2026-11-03 批次A A2 定案）
5. liked/commented/view-count-flushed → **只累加 Redis 热度待刷新键，不直写 ES**（`HINCRBY` 累加 like/comment 增量 + `HSET` 覆盖 view_cnt 绝对值；2026-11-03 批次A A7 定案：article_idx 带 1024 维向量约 4KB，ES partial update 实为整文档重写，逐条互动消费写放大严重）；**ACK 后置**：Redis 累加成功才 ACK，累加失败 nack 重试（防丢数致 heat 漂移）→ IndexHeatFlushJob 定时聚合（`heat.flush.interval-ms` 默认 15min、可调 15~30min，fixedDelay 不堆积）→ `_bulk` 批量 partial update 重算 heat（每篇每窗口至多 1 次写，不动向量；heat 公式按 A5；**分片执行**：按 `batch-size`(1000)×`concurrency`(2) 分批防超大请求被 ES 拒绝，bulk 用 update+doc 只改 heat 字段禁 index 整篇替换；pending 键 TTL 2h 兜底防未刷先清）
6. 失败 → nack 重试耗尽入 DLQ → DLQ Consumer 记录告警（任务停留由人工/重投处理）
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

------

## 三、接口设计

### 3.1 REST 接口

| 方法 | 路径 | 说明 | 请求 | 响应 |
| --- | --- | --- | --- | --- |
| GET | `/search` | 全文检索 | query：`q`（必填）、`type`（article/book/user）、`sort`、`pageNum`、`pageSize`、`tag`、`category`、`zone`、`publishBegin`/`publishEnd`（时间范围，2026-11-03 补） | `Result<SearchResultVO>` |
| GET | `/search/suggest` | 搜索联想（2026-11-03 新增） | query：`q`（必填）、`size`（可选，默认 10） | `Result<List<SuggestVO>>` |
| GET | `/search/similar/{articleId}` | 相似文章推荐 | path：articleId；query：`topK`（可选） | `Result<List<SimilarArticleVO>>` |
| GET | `/search/history` | 我的搜索历史（2026-11-03 新增） | query：`pageNum`、`pageSize` | `Result<PageResult<SearchHistoryVO>>` |
| DELETE | `/search/history` | 清空我的搜索历史（2026-11-03 新增） | — | `Result<Void>` |

> 权限（2026-11-03 批次C C4 定版）：`/search`、`/search/suggest`、`/search/similar/**` 匿名可用（网关白名单登记，对齐 content `/article/*` 口径，随落地同步 Gateway 配置）；`/search/history`（GET/DELETE）需登录（网关 JWT + user_id 硬收口）。

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

------

## 四、ES 索引设计

**article_idx**（核心索引）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| id | keyword | articleId（文档主键，幂等 upsert 依据） |
| title | text（ik_max_word / ik_smart） | 权重 ^4 |
| tags | keyword[] | 权重 ^3；兼作标签重合度计算 |
| summary | text | 权重 ^2 |
| content | text（ik 分词） | 全文检索，返回高亮片段 |
| visibility | keyword | 过滤字段，仅 `PUBLIC` 入索引（写入侧闸门 + 查询侧 filter 双保险） |
| authorId | keyword | 作者 ID（作者维度聚合/筛选用；2026-11-03 批次A A4 补） |
| authorName | text（ik_max_word / ik_smart，multi-field 含 .completion 子字段） | 作者名检索 + suggest 联想源（2026-11-03 批次A A4 补） |
| like_cnt / comment_cnt / view_cnt | long | heat 计算基数：like/comment 随互动事件增量，view_cnt 随浏览量 flush 事件覆盖更新（2026-11-03 批次A A5 补） |
| heat | double | 互动热度 = `(w_like×like_cnt + w_comment×comment_cnt + w_view×view_cnt) × 时间衰减因子`（衰减按发布时间距今计算，防旧爆款长期霸榜；权重与半衰期外置 Nacos 可调，默认 2 / 3 / 0.1 / 168h，2026-11-03 批次A A5 定案）；function_score 加权因子 + HEAT 排序 |
| vec_dense | dense_vector（dims=1024，cosine，indexed） | KNN 相似推荐；维度与 Embedding 模型对齐 |
| updateTime | date | NEWEST 排序 |
| title.completion / authorName.completion | completion | 搜索联想 suggester 字段（authorName.completion 为 authorName 的 multi-field 子字段；2026-11-03 补，仅联想用不参与打分） |

**book_idx**：书名/作者/简介（text + ik）+ 分类 keyword；**user_idx**：笔名（text + ik，模糊匹配）+ 简介。⚠️ 两索引同步事件来源待落地（书籍/用户变更事件，temp 批次B B4/B5，契约待落地）。

**article_chunks**（RAG 知识库索引，Design-Document §2.6，2026-11-03 补）：search 摄入、ai 纯查询；**摄入范围一期仅公开文章正文，书籍章节不入库**（2026-11-03 批次C C2 定版，预留章节粒度摄入扩展待后续立项）。`chunk_id`(keyword) / `article_id`(long) / `author_id` / `title` / `author_name` / `chunk_text`(text) / `chunk_vector`(dense_vector, cosine, indexed) / `zone` / `visibility` / `publish_time` / `ingest_time`。入库门控：PUBLIC + 审核终态 PASS + 正文 ≥200 字 + DFA 未命中高风险；切片 ~500 字/段重叠 50 字；按 articleId 幂等覆盖（删旧块写新块）。✅ 与 article_idx 的 vec_dense **统一为同一 Embedding 单模型**（维度一致，具体选型落地时定，2026-11-03 批次C C1 定版闭环 G5）；模型版本写入两索引 meta，模型变更 → 全量重建（§八-8）。

**KNN 检索参数**：`num_candidates=100`（可配）、TopK 默认 10（可配）、相似度 `cosine`；向量在发布事件消费时一次预计算，查询期零 Embedding 开销。

**可见性红线**：仅 `visibility=PUBLIC` 且过审文章入索引与向量库；`content.unpublished` 事件必须同步移除对应文档与向量（覆盖删除/转私密/复审驳回下架/封禁连带四条下架路径），防止违规/封禁内容残留被越权检索。

**search_history**（唯一自有 MySQL 表，2026-11-03 新增，DDL 登记 Sql-Document.md）：`id` / `user_id`（uk 组合 `uk(user_id, keyword)`）/ `keyword`（varchar，长度与 SEARCH_PARAM_INVALID 上限一致）/ `updated_at`。UPSERT 同词仅前进（更新 updated_at）；超 `history.max-per-user`（默认 20）懒删最旧；**隐私纪律：keyword 不写入任何 ES 索引文档**（与 §八-9 对应）。书籍章节不入 article_chunks（2026-11-03 批次C C2 定版，见上文 article_chunks 摄入范围）。

------

## 五、事件契约（订阅侧）

| 事件 | 来源 | 动作 |
| --- | --- | --- |
| `content.published` | content | 回查元数据（事件体仅定位键）→ 乱序门控（status=2 且 PUBLIC 才写）→ upsert `article_idx`（含向量预计算）+ 摄入 `article_chunks` |
| `content.unpublished` | content | 删除对应文档与向量（article_idx 文档 + article_chunks 旧块） |
| `content.article-updated` ✅（2026-11-03 批次A A2 定案；契约落地 temp 批次B B7） | content | 已发布（status=2）文章编辑元数据（title/tags/summary/cover）后 afterCommit 发布；search 轻量回查 → partial update 元数据字段（不动向量与 chunks）。事件体 ContentArticleUpdatedEvent + 路由键 ROUTING_CONTENT_ARTICLE_UPDATED 已入 litisle-api/MqConstant；content 侧发布点已接入（批次B B7，2026-11-03：ArticleService#update 元数据变更后 afterCommit 发送） |
| `content.view-count-flushed` ✅（2026-11-03 批次A A5 定案；契约落地 temp 批次B B7） | content | content 浏览量 flush 任务（ViewCountFlushJob）落库后补推；search **仅以覆盖 SET 写 view_cnt 绝对值到 Redis 热度待刷新键**（A7，不直写 ES），由 IndexHeatFlushJob 定时 `_bulk` 重算 heat，使 HEAT 排序与 content 热门榜口径对齐。事件体 ContentViewCountFlushedEvent（viewCount 为最新绝对值，覆盖 SET 非累加）+ 路由键 ROUTING_CONTENT_VIEW_COUNT_FLUSHED 已入 litisle-api/MqConstant；content 侧发布点已接入（批次B B7，2026-11-03：ViewCountFlushJob 每轮刷回成功后按篇补推） |
| `social.liked` / `social.commented` ✅（契约落地 temp 批次B B2，缺口 G2 闭环） | social | 消费后**仅 `HINCRBY` 累加 Redis 热度待刷新键的 like/comment 增量**（A7，不逐条直写 ES 防带向量整文档重写），由 IndexHeatFlushJob 定时 `_bulk` 重算 heat（function_score 加权因子）。事件体 SocialLikedEvent / SocialCommentedEvent + EXCHANGE_SOCIAL + 路由键 ROUTING_SOCIAL_LIKED/COMMENTED 已入 litisle-api/MqConstant；social 落地时 AFTER_COMMIT 发布 |

> 事件体纪律（对齐 content-index §八）：content 事件只带 bizType/bizId/contentHash 定位键，upsert 所需标题/标签等元数据经 ContentClient 回查（字段不足扩提供方端点，禁事件体塞大字段，缺口 G3）。⚠️ 回查字段充足性 + 审核终态/DFA 结论获取方式待核（temp 批次B B6，契约待落地）。
>
> ⚠️ book_idx / user_idx 尚无索引同步事件来源（书籍创建/变更/完结/下架、用户注册/资料修改），契约待落地（temp 批次B B4/B5）。

消费铁律（对齐 review 规范）：手动 ACK；消费幂等（按事件体 articleId 幂等更新）；索引失败入 DLQ 重试；ES 不可用降级返回热门列表（§八-5，批次A A3 定案）。

------

## 六、配置说明

> 配置体系与 user/file 一致：`application.yml` 主配置 + 环境特定值（local/dev.yaml）+ Nacos `litisle-search.yaml` / `litisle-shared.yaml`。

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `litisle.search.es.hosts` | `localhost:9200` | ES 集群地址 |
| `litisle.search.es.username / password` | — | ES 凭证，生产走环境变量 |
| `litisle.search.es.connect-timeout / socket-timeout` | 3s / 10s | 查询超时（ai 侧 RAG 依赖此超时可容忍） |
| `litisle.search.index.article / book / user` | article_idx / book_idx / user_idx | 索引名（外置，便于分环境） |
| `litisle.search.weight.title / tags / summary` | 4 / 3 / 2 | multi_match 字段权重 |
| `litisle.search.weight.heat.like / comment / view / time-decay-half-life` | 2 / 3 / 0.1 / 168h | heat 公式权重 + 时间衰减半衰期（衰减后热度按半衰期折半，评论 > 点赞 > 浏览，Nacos 可调；2026-11-03 批次A A5 定案 + 增强采纳） |
| `litisle.search.heat.flush.interval-ms / initial-delay-ms / batch-size / concurrency / pending-key-ttl` | 900000（15min）/ 60000 / 1000 / 2 / 2h | 互动热度刷写：liked/commented/view-count-flushed 消费仅累加 Redis 热度待刷新键，本任务按周期聚合 `_bulk` 更新 heat（fixedDelay 不堆积，每篇每窗口至多 1 次写，容忍 15~30min 滞后）；`_bulk` 按 batch-size（主流区间 500~2000）× concurrency（2~4）分片执行，bulk 用 update+doc 只改 heat 字段禁 index 整篇替换；pending 键 TTL ≥2 个刷写周期防未刷先清（2026-11-03 批次A A7 定案新增 + 工程细节补充） |
| `litisle.search.knn.top-k / num-candidates` | 10 / 100 | KNN 参数 |
| `litisle.search.knn.similarity-threshold` | 0.0（默认不过滤） | 相似推荐阈值（ai RAG 的 0.65 阈值属 ai 侧，不在此收敛） |
| `litisle.search.cache.hot-query-enabled / ttl` | true / 10m | 热点查询缓存 |
| `litisle.search.suggest.enabled / size` | true / 10 | 搜索联想开关与默认条数（2026-11-03 新增） |
| `litisle.search.history.max-per-user` | 20 | 单用户搜索历史上限，超限懒删最旧（2026-11-03 新增） |
| `litisle.search.page.max-page-size` | 50 | 分页上限 |
| `litisle.search.rabbit.sync-queue / dlx` | （预留）/ `litisle.search.dlx` | dlx=索引同步死信交换机（Direct，六同步队列死信按原路由键汇入 `litisle.search.index.dlq.queue`，SearchMqConfig 声明）；sync-queue 为预留 key（实际同步队列以 MqConstant 六队列为准，拓扑声明不读取此值；2026-11-03 D1 骨架落地修正默认值） |
| `litisle.search.fallback.embedding-timeout` | 1s | 空结果兜底查询词实时 Embedding 超时上限（超时/失败二级兜底 HEAT 热门列表，2026-11-03 批次A A1 定案新增） |
| `litisle.search.fallback.enabled` | true | ES 不可用降级开关（降级返回热门列表 + 前端提示，2026-11-03 批次A A3 定案，原 db-query-enabled 废弃） |

------

## 七、异常与错误码

> ✅ SEARCH_* 四码已入 common-core ErrorCode（缺口 G4 闭环，temp 批次B B3 落地 2026-11-03）：search 新模块分段 100000-109999（SEARCH_PARAM_INVALID 100001 / SEARCH_PAGE_EXCEEDED 100002 / SEARCH_SOURCE_NOT_FOUND 100003 / SEARCH_ES_UNAVAILABLE 100004），Api-Architecture.md §10 分段表已同步。

| 场景 | 错误码 | 说明 |
| --- | --- | --- |
| 查询词为空/超长、sort/type 非法 | SEARCH_PARAM_INVALID | 参数校验失败 |
| pageNum/pageSize 超限 | SEARCH_PAGE_EXCEEDED | pageSize > max-page-size |
| 相似推荐源文章不存在/已下架 | SEARCH_SOURCE_NOT_FOUND | 取不到源向量时抛 |
| ES 不可用且降级关闭/降级也失败 | SEARCH_ES_UNAVAILABLE | 降级关闭或热门列表获取失败时抛（批次A A3 定案） |
| 请求过频 | TOO_MANY_REQUESTS(10005) | @RateLimiter / Sentinel 触发（对齐 file 限流体系） |

------

## 八、安全与可靠性

1. **ES 注入防护**：查询词对特殊字符（`+ - = && || > < ! ( ) { } [ ] ^ " ~ * ? : \ /`）转义；排序/筛选字段仅白名单枚举映射，不透传用户输入构造 DSL
2. **可见性双保险**：写入侧仅 PUBLIC 入索引（§四红线）+ 查询侧强制 `filter(visibility=PUBLIC)`，防脏数据越权召回
3. **下架全路径移除**：`content.unpublished` 四条下架路径（删除/转私密/复审驳回/封禁连带）必须到达，否则违规内容残留向量库（隐私红线，同 Design-Document §2.6）
4. **消费可靠性**：手动 ACK + 按 articleId 幂等 upsert/删除 + 失败 DLQ 重试；无本地状态，可水平扩展
5. **降级链路**（2026-11-03 批次A A3 定案）：ES 不可用 → 降级返回热门列表（content `/article/hot` 同源数据，`source=DEGRADED` 标记不缓存）+ 前端提示搜索不可用（不做 Feign 查业务方 DB 降级——content 无公开 keyword 检索端点，公开搜索已全划归 search）；降级关闭或热门列表获取失败 → SEARCH_ES_UNAVAILABLE；ai RAG 向量库不可用由 ai 侧静默降级纯 LLM，search 仅保证超时可容忍。**数据源语义备注（2026-11-03）：`/article/hot` 将随 content 临时清单批次E P5 切换为 heat 榜序**（`CONTENT_FEED_HOT_ZSET` 同源 + 榜空回退 view_cnt 直查），降级结果口径落地时对齐
6. **缓存规范**：热点查询 Redis 缓存，key 遵循企业规范 `search:模块:功能:唯一标识`；互动热度更新失效对应缓存
7. **限流**：@RateLimiter(USER) 单用户维度，全站 QPS/Sentinel 规则外置 Nacos（对齐 file 限流三层体系，按需裁剪）
8. **向量一致性**：Embedding 模型版本变更 → 全量重建索引（vec_dense 维度/语义漂移），模型版本写入索引 meta 防错位
9. **搜索词隐私**：搜索历史 keyword 仅存 search_history 自有表，**不写入任何 ES 索引文档/向量库**（2026-11-03 新增，防用户搜索意图被检索侧聚合暴露）

------

## 九、next 清单生成依据（☆ 待建分解，对齐 temp.md search模块 批次A~D）

按落地顺序建议（批次A 缺陷修正 + 批次B 契约 + 批次C 决策已定版 2026-11-03，批次D search 本体待建，细案见 temp.md）：

1. ☆ 契约与决策（已定版）：SearchClient/social 事件/SEARCH_* 错误码契约待落地（批次B B1~B7）；向量维度统一单模型（C1）、章节 RAG 边界一期不入（C2）、KNN 强制 PUBLIC 过滤（C3）、匿名策略（C4）均已定案
2. ☆ 基础骨架：es 配置 + SearchConfig + constant/enums + 索引 mapping 初始化（article_idx 含 suggest 字段 + article_chunks）+ search_history 表
3. ☆ 索引同步：IndexSyncConsumer + Rabbit 声明 + DLQ + 幂等 upsert/删除 + 乱序防御门控
4. ☆ 全文检索：SearchService + Controller（bool+function_score + 防注入 + tag/category/zone/时间范围筛选 + 空结果兜底 + 分页）
5. ☆ 搜索联想与历史：suggest 接口 + SearchHistoryService（UPSERT/懒删/清空）
6. ☆ 相似推荐：VectorService + KnnRecommender + SimilarController
7. ☆ 兜底：ES 降级查 DB + 热点查询缓存 + 指标埋点