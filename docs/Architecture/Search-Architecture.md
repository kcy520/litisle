# litisle-search 搜索模块架构文档（简化版）

> 版本：v0.1（2026-10-19，设计先行版，代码未落地，全组件 ☆）
>
> 关联文档：[Design-Document.md](../overall_documen/Design-Document.md) §2.3.9（搜索服务总设计）/ §2.6（RAG 知识库）· [Sql-Document.md]（search 无 MySQL 表，仅作确认）· [Api-Architecture.md](Api-Architecture.md)（Feign 契约）· [commom-des.md](commom-des.md)
>
> 阅读约定：本文只保留**结论与机制**；★=已落地，☆=待建（当前全部 ☆）

------

## 一、模块定位与边界

| 项目     | 说明                                                         |
| -------- | ------------------------------------------------------------ |
| 定位     | 独立微服务，站内检索统一收口方：**ES 索引同步 + 全文/多维度检索 + dense_vector KNN 相似推荐** |
| 技术选型 | Elasticsearch 8.18.8（全文 + dense_vector KNN，零新增中间件）· RabbitMQ（Confirm/ACK/DLQ，索引异步同步）· Elasticsearch Java Client |
| 依赖方   | content / social / user 经 MQ 事件驱动索引更新（`content.published` / `content.unpublished` / `social.liked` / `social.commented`）；ai 服务经 Feign 调用相似推荐向量；gateway 路由 `/search/**` |
| 硬边界   | search **无 MySQL 表**（纯 ES 索引文档，状态 SSOT 在业务方 DB）；search 不回写业务方数据（仅接收事件单向同步）；私有/草稿/拒绝文章永不入索引与向量库（与 review 可见性红线同源） |

### 1.1 三类能力

| 能力 | 载体 | 说明 |
| --- | --- | --- |
| 索引同步 | IndexSyncConsumer（MQ 消费） | 订阅发布/下架/互动事件，增量更新 `article_idx` / `book_idx` / `user_idx` 与热度向量；失败入 DLQ 重试 |
| 全文检索 | SearchService | bool + function_score：标题(^4) → 标签(^3) → 摘要(^2) → 全文；用户笔名模糊匹配；分类筛选 + 排序 |
| 相似推荐 | VectorService + KnnRecommender | KNN 检索 `vec_dense`，相似度 = 标签重合 + 向量 + 行为加权；供 ai 服务 RAG 与前端"相关文章"复用 |

### 1.2 代码结构（☆=全部待建）

```text
litisle-search
├── controller/       ☆ SearchController（GET /search?q=&type=&sort=）
│                     / SimilarController（GET /search/similar/{articleId}）
├── service/          ☆ SearchService（bool+function_score 检索编排）
│                     / VectorService（Embedding + KNN 查询）/ KnnRecommender（相似推荐加权）
│                     └── impl/（接口实现）
├── domain/
│   ├── entity/       ☆ （无 MySQL 实体，ES 索引文档类）
│   ├── dto/          ☆ SearchRequestDTO
│   └── vo/           ☆ SearchResultVO / ArticleDocVO / BookDocVO / UserDocVO
├── enums/            ☆ SearchSortEnum / SearchTypeEnum
├── constant/         ☆ SearchBizConstant（索引名称、权重配置、阈值常量）
├── es/               ☆ ElasticsearchClient 配置（连接/索引初始化/mapping）
├── mq/               ☆ IndexSyncConsumer（文本/互动事件消费 + DLQ 消费者）
└── config/           ☆ SearchConfig（参数外置 / Rabbit 声明 / 装配开关）
```

------

## 二、功能设计

### 2.1 功能清单

| 功能 | 说明 | 核心机制 |
| --- | --- | --- |
| 索引同步 | 事件驱动增量更新 | 消费 `content.published`/`content.unpublished`/`social.liked`/`social.commented`；按 articleId 幂等 upsert/删除；向量预计算随发布一次写入；失败入 DLQ 重试 |
| 全文检索 | 文章/书籍/用户多维检索 | multi_match 权重 title^4 → tags^3 → summary^2 → content；function_score 热度加权；分类筛选 + 排序 |
| 相似推荐 | KNN + 多因子加权 | 取源文章向量/标签 → KNN TopK → 标签重合度 + 向量相似 + 行为数据融合 → 阈值过滤；供 ai RAG 与前端复用 |
| 降级查询 | ES 不可用兜底 | 经 litisle-api Feign 回查业务方 DB（返回标记降级来源，结果不缓存） |

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
4. liked/commented → 仅增量更新 heat 字段（partial update，不动向量）
5. 失败 → nack 重试耗尽入 DLQ → DLQ Consumer 记录告警（任务停留由人工/重投处理）
```

**全文检索**：

```
GET /search → 参数校验 + 查询词转义（防 ES 注入）
→ Redis 热点缓存命中则直返
→ 未命中：type 路由索引 → bool must(multi_match) + filter(visibility=PUBLIC) + function_score(heat)
→ 返回高亮片段 + 分页结果 → 热点查询写入缓存
→ ES 异常 → Feign 降级查业务方 DB（标记降级，不缓存）
```

**相似推荐**：

```
GET /search/similar/{articleId} → 取源文章 vec_dense + tags
→ KNN 查询（num_candidates=100，TopK 可配）
→ 融合评分 = 向量相似 + 标签重合度 + 行为热度加权
→ 阈值过滤 + 排除自身与已下架文档 → 返回列表
```

------

## 三、接口设计

### 3.1 REST 接口

| 方法 | 路径 | 说明 | 请求 | 响应 |
| --- | --- | --- | --- | --- |
| GET | `/search` | 全文检索 | query：`q`（必填）、`type`（article/book/user）、`sort`、`pageNum`、`pageSize`、`tag` | `Result<SearchResultVO>` |
| GET | `/search/similar/{articleId}` | 相似文章推荐 | path：articleId；query：`topK`（可选） | `Result<List<SimilarArticleVO>>` |

> 权限：需登录（网关 JWT 校验）；匿名访问策略以 Gateway 白名单为准。

**VO 摘要**：

```
SearchRequestDTO  { q, type, sort, pageNum, pageSize, tag }
SearchResultVO    { total, pageNum, pageSize, list<ArticleDocVO | BookDocVO | UserDocVO> }
ArticleDocVO      { articleId, title, summary, tags, authorId, authorName, heat, updateTime, highlight }
SimilarArticleVO  { articleId, title, summary, authorName, score }
```

### 3.2 Feign 契约（litisle-api 侧 SearchClient，Provider 端实现）

> 跨服务契约集中在 litisle-api（对齐 Api-Architecture 约定）；search 侧由 FeignProviderController 实现（`/api/feign/search`，网关不路由）。

```
// ai 服务 RAG / 小月相似推荐复用
List<SimilarArticleVO> similar(Long articleId, Integer topK);
// 降级链路：业务方或 ai 直接调检索（仅服务间）
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
| heat | double | 互动热度（function_score 加权因子 + HEAT 排序） |
| vec_dense | dense_vector（dims=1024，cosine，indexed） | KNN 相似推荐；维度与 Embedding 模型对齐 |
| updateTime | date | NEWEST 排序 |

**book_idx**：书名/作者/简介（text + ik）+ 分类 keyword；**user_idx**：笔名（text + ik，模糊匹配）+ 简介。

**KNN 检索参数**：`num_candidates=100`（可配）、TopK 默认 10（可配）、相似度 `cosine`；向量在发布事件消费时一次预计算，查询期零 Embedding 开销。

**可见性红线**：仅 `visibility=PUBLIC` 且过审文章入索引与向量库；`content.unpublished` 事件必须同步移除对应文档与向量（覆盖删除/转私密/复审驳回下架/封禁连带下架四条下架路径），防止违规/封禁内容残留被越权检索。

------

## 五、事件契约（订阅侧）

| 事件 | 来源 | 动作 |
| --- | --- | --- |
| `content.published` | content | upsert `article_idx`（含向量预计算） |
| `content.unpublished` | content | 删除对应文档与向量 |
| `social.liked` / `social.commented` | social | 增量更新热度字段（function_score 加权因子） |

消费铁律（对齐 review 规范）：手动 ACK；消费幂等（按事件体 articleId 幂等更新）；索引失败入 DLQ 重试；ES 不可用降级查业务方 DB（经 litisle-api Feign）。

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
| `litisle.search.knn.top-k / num-candidates` | 10 / 100 | KNN 参数 |
| `litisle.search.knn.similarity-threshold` | 0.0（默认不过滤） | 相似推荐阈值（ai RAG 的 0.65 阈值属 ai 侧，不在此收敛） |
| `litisle.search.cache.hot-query-enabled / ttl` | true / 10m | 热点查询缓存 |
| `litisle.search.page.max-page-size` | 50 | 分页上限 |
| `litisle.search.rabbit.sync-queue / dlx` | search.index.sync.queue / search.index.dlx | 索引同步队列 + 死信声明 |
| `litisle.search.fallback.db-query-enabled` | true | ES 不可用降级查 DB 开关 |

------

## 七、异常与错误码

| 场景 | 错误码 | 说明 |
| --- | --- | --- |
| 查询词为空/超长、sort/type 非法 | SEARCH_PARAM_INVALID | 参数校验失败 |
| pageNum/pageSize 超限 | SEARCH_PAGE_EXCEEDED | pageSize > max-page-size |
| 相似推荐源文章不存在/已下架 | SEARCH_SOURCE_NOT_FOUND | 取不到源向量时抛 |
| ES 不可用且降级关闭/降级也失败 | SEARCH_ES_UNAVAILABLE | 降级 Feign 查 DB 也异常时抛 |
| 请求过频 | TOO_MANY_REQUESTS(10005) | @RateLimiter / Sentinel 触发（对齐 file 限流体系） |

------

## 八、安全与可靠性

1. **ES 注入防护**：查询词对特殊字符（`+ - = && || > < ! ( ) { } [ ] ^ " ~ * ? : \ /`）转义；排序/筛选字段仅白名单枚举映射，不透传用户输入构造 DSL
2. **可见性双保险**：写入侧仅 PUBLIC 入索引（§四红线）+ 查询侧强制 `filter(visibility=PUBLIC)`，防脏数据越权召回
3. **下架全路径移除**：`content.unpublished` 四条下架路径（删除/转私密/复审驳回/封禁连带）必须到达，否则违规内容残留向量库（隐私红线，同 Design-Document §2.6）
4. **消费可靠性**：手动 ACK + 按 articleId 幂等 upsert/删除 + 失败 DLQ 重试；无本地状态，可水平扩展
5. **降级链路**：ES 不可用 → Feign 降级查业务方 DB（结果标记降级不缓存）；ai RAG 向量库不可用由 ai 侧静默降级纯 LLM，search 仅保证超时可容忍
6. **缓存规范**：热点查询 Redis 缓存，key 遵循企业规范 `search:模块:功能:唯一标识`；互动热度更新失效对应缓存
7. **限流**：@RateLimiter(USER) 单用户维度，全站 QPS/Sentinel 规则外置 Nacos（对齐 file 限流三层体系，按需裁剪）
8. **向量一致性**：Embedding 模型版本变更 → 全量重建索引（vec_dense 维度/语义漂移），模型版本写入索引 meta 防错位 |

------

## 九、next 清单生成依据（☆ 待建分解）

按落地顺序建议（每一项对应一个可验收板块）：

1. ☆ 基础骨架：es 配置 + SearchConfig + constant/enums + 索引 mapping 初始化
2. ☆ 索引同步：IndexSyncConsumer + Rabbit 声明 + DLQ + 幂等 upsert/删除
3. ☆ 全文检索：SearchService + Controller（bool+function_score + 防注入 + 分页）
4. ☆ 相似推荐：VectorService + KnnRecommender + SimilarController
5. ☆ 兜底：ES 降级查 DB + 热点查询缓存 + 指标埋点