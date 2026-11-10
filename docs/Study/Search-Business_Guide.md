# LitIsle Search 模块业务指南（学习向）

> 定位：**快速理解搜索域业务设计 + 学习各板块设计逻辑**，不承载决策推演（存储选型/降级论证看
> [Search-Architecture.md](../Architecture/Search-Architecture.md)，RAG 知识库论证看
> [Design-Document.md](../overall_documen/Design-Document.md) §2.3.9 / §2.6）。
>
> 现状声明（2026-11-06 更新）：**litisle-search 已落地**——批次二~九已于 2026-11-04/05 实现并归档（见 [search 归档索引](../archive/search/search-implemention-records.md)），批次十一 11.14 补取消点赞链路；本文早期「全 ☆ 待建」标注为设计先行口径，阅读时以代码为准。
>
> 2026-11-08 修订：补齐 `IndexVersionGuard` 版本守卫、`user_idx` 版本源（`user` 与 `user_profile` 取较新）、MQ 消费线程无 SaToken 上下文时 `FeignAuthInterceptor` 只透传 traceId 的降级口径。
>
> 2026-11-03 增补定版：检索参数补齐（category/zone/时间范围）、搜索联想、空结果兜底（相似推荐）、搜索历史（search_history 唯一自有表）；任务登记见 [temp.md](../temp/temp.md) search模块 批次1/批次2。
>
> 阅读方式：每个板块固定两段——**设计版**（设计类名+机制）→ **通俗版**（无代码名）。文末附「契约缺口清单」，落地前须先补契约。

## 〇、全景图：一篇文章从发布到可被搜到、可被小月引用

```text
content.published（事件体仅 bizType/bizId/contentHash，不含标题正文）
  → search 消费：回查元数据 → 可见性/状态门控 → Embedding 预计算 → upsert article_idx（含 vec_dense）
  → 读者 GET /search：multi_match 权重检索 + 热度加权 + 缓存
  → 读者 GET /search/similar/{id}：KNN + 标签/行为融合评分
  → （另一条线）article_chunks 知识库索引：发布文章切片向量化入库 → 小月（ai 服务）RAG 检索引用

content.unpublished（四条下架路径必发）
  → search 消费：删 article_idx 文档 + 删 article_chunks 旧块
    （隐私红线：违规/封禁内容不得残留可检索/可引用）
```

## 一、链路位置

| 阶段 | search 职责 |
| --- | --- |
| 发布链路（阶段二~五）末端 | 消费 `content.published`，异步建索引，不阻塞发布 RT |
| 下架链路（四路径：软删/转私密/复审驳回/封禁连带）末端 | 消费 `content.unpublished`，同步删文档删向量 |
| 互动链路 | 消费 `social.liked` / `social.commented`（✅ 契约已落地，批次B B2 2026-11-03）、`social.unliked`（✅ 批次十一 11.14，取消点赞负修正）与 `content.view-count-flushed`（✅ 契约已落地，批次B B7 2026-11-03），增量维护 heat（含浏览量，口径对齐 content 热门榜）；**点赞/取消点赞事件现由 content 发布**（批次十一 11.8 点赞收进 content，LikeService AFTER_COMMIT） |
| 读者前台 | 全文检索 + 相似推荐两个 REST 入口 |
| ai 服务 RAG | 站内知识库 ES 侧载体（chunk 索引）+ 相似推荐 Feign 复用 |

**硬边界**：search 无业务数据表（唯一自有表 `search_history`，见板块六）；不回写业务方数据；私有/草稿/拒绝内容永不入索引与向量库。

**搜索接口归属判定（2026-11-03，对应 temp.md 批次1 1.1）**：

| content 现有接口 | 判定 | 理由 |
| --- | --- | --- |
| `GET /article/my`、`GET /book/my`（keyword 过滤） | 保留 content | 作者管理侧列表，author_id 硬收口，DB 直查合理 |
| `GET /tags?keyword=`、`/tags/hot` | 保留 content | tag 池为 content 域元数据（SearchTypeEnum 无 TAG），标签搜索≠全文检索；search 联想仅聚合复用 |
| `GET /article/list`、`/article/hot` | 保留 content | 结构化筛选列表，无关键词，非检索 |
| 公开文章/书籍/用户的全文与多维度检索 + 相似推荐 | 归属 search | 既定设计（BookController `/book/list` 注释已声明「一期不做 keyword 搜索，书籍索引为 search 外部依赖」） |

## 二、架构设计（包结构；批次二~九已落地，此处保留目标结构供对照）

```text
litisle-search
├─ controller
├─ service                 # SearchService（检索编排）/ VectorService（Embedding+KNN）/ KnnRecommender（相似加权）
│  └─ support              # IndexVersionGuard（article/book/user 三索引写入版本守卫）
├─ mq                      # IndexSyncConsumer（published/unpublished/liked/commented + DLQ 消费者）
├─ es                      # ElasticsearchClient 配置（连接/索引初始化/mapping/生命周期）
├─ domain                  # ES 索引文档类放 domain/entity（无 MySQL 实体）；两套向量索引见板块四
├─ enums
├─ constant                # SearchBizConstant（索引名/权重/阈值/缓存键前缀）
└─ config                  # SearchConfig（参数外置 prefix litisle.search / Rabbit 声明 / 降级开关）
```

> - 包作用与 Content-Business-Guide 同约定：constant/enums 收口防魔法值；config 参数外置支持 Nacos 热调；mq 消费范式对齐 review/content（手动 ACK + MqAckHelper + MqTraceSupport + 幂等）。

## 三、板块讲解

### 板块一 ☆：索引同步消费端（IndexSyncConsumer）

**设计版**：

1. **订阅关系**：队列 `search.index.sync.queue` + 独立 DLX（配置外置），绑定以下路由键：
   - `content.published` / `content.unpublished`（MqConstant 现有路由键）
   - `content.article-updated` / `content.view-count-flushed`（元数据编辑 / 浏览量 flush 事件，2026-11-03 批次A A2/A5 定案，✅ 契约与 content 侧发布点均已落地，批次B B7）

2. **published upsert 编排**（事件体不含元数据，对齐 content-index §八：事件只带定位键）：
   - **回查元数据**：`ContentClient.getContent(bizType, bizId)` 回查标题/正文/标签等
   - **乱序防御门控**：回查后文章须 `status=2 且 visibility=1(PUBLIC)`，否则丢弃不写（防 unpublished 先到、published 迟到，把已删内容写回）
   - **版本守卫**：`IndexVersionGuard` 读 ES 现有 `updateTime`，现有版本 ≥ 本次快照（含相等）就跳过；读 ES 失败抛异常交 MQ 重试（防旧快照覆盖新文档、等版本重复写）
   - **向量预计算**：`VectorService` Embedding 预计算 `vec_dense`
   - **幂等写入**：按 id upsert `article_idx`

3. **unpublished 删除**：按 articleId 删 article_idx 文档（404 视为成功幂等）**并删 article_chunks 对应块**（板块四红线）；同步失效热点查询缓存。

4. **元数据编辑同步**（2026-11-03 批次A A2 定案）：
   - 触发：已发布文章编辑 title/tags/summary/cover 后，content 在事务提交后（afterCommit）发 `content.article-updated`
   - 处理：search 轻量回查 → **同一 `IndexVersionGuard` 版本比较**（旧快照/等版本跳过）→ partial update 索引元数据字段（不动向量与 chunks）
   - 目的：防止索引字段与正文永久过期

5. **用户索引同步**（批次五 B5）：
   - 链路：`user.registered` / `user.profile-updated` → `UserClient.getUserIndexDoc(userId)` 回查 → 门控（无公开资料不建/已不可索引则删）→ `IndexVersionGuard` 版本比较 → upsert `user_idx`
   - **版本源取 `user.update_time` 与 `user_profile.update_time` 的较大者**——只改笔名/简介时主表时间可能不前进，单取主表会被版本守卫永久判旧而丢更新
   - 认证降级：MQ 消费线程没有 SaToken 上下文，`FeignAuthInterceptor` 自动降级为只透传 traceId，不伪造用户身份、也不抛异常阻断消费

6. **热度更新**（2026-11-03 批次A A5/A7 定案 + 增强采纳；批次十一 11.14 补取消点赞）：
   - **事件累加**：liked/unliked/commented → `HINCRBY` 累加 Redis 热度待刷新键的 like/comment 增量
     - 点赞：按 SocialLikedEvent.delta 累加（无 delta 兜底 +1、delta=0 跳过）
     - 取消点赞：按 SocialUnlikedEvent.delta=-1 负数累加
     - 浏览量：content 浏览量 flush 任务落库后补推 `content.view-count-flushed` → `HSET` 覆盖写 view_cnt 绝对值
   - **下限保护**：IndexHeatFlushJob 写回 ES 前 `Math.max(0, …+likeDelta)`，防 likeCnt 负值
   - **互动事件均不直写 ES**（A7 定案）：article_idx 带 1024 维向量约 4KB，ES partial update 实为整文档重写，爆款逐条互动消费写放大严重
   - **ACK 时机**：累加成功才 ACK（累加失败 nack 重试，防丢数致热度漂移）
   - **批量刷写**：`IndexHeatFlushJob` 定时（默认 15min、可调 15~30min，fixedDelay 不堆积）聚合 `_bulk` partial update
     - 按 `batch-size`(1000) × `concurrency`(2) 分片执行（防单次超大请求被 ES 拒绝；bulk 用 update+doc 只改热度字段，禁 index 整篇替换）
     - 重算公式：`heat = (w_like×like_cnt + w_comment×comment_cnt + w_view×view_cnt) × 时间衰减因子`（权重与半衰期外置，默认 2/3/0.1/168h——含浏览量对齐 content 热门榜口径，衰减防旧爆款长期霸榜）
     - 不动向量：向量随发布一次写入，查询期零 Embedding 开销
   - **容忍度与兜底**：heat 仅作排序信号，容忍 15~30min 滞后；pending 键 TTL 2h 兜底（≥2 个刷写周期，正常路径原子取走 TTL 仅保险）

7. **可靠性范式**（对齐全站消费规范）：
   - 手动 ACK；MqIdempotentHelper 按 eventId 幂等
   - 业务失败 warn 后 ack 防毒丸；异常指数退避延迟重投，耗尽 nack 入 DLQ
   - DLQ Consumer 告警留痕，人工重投

**通俗版**：文章一发布，搜索服务后台悄悄把它"编入目录"：先确认这文章当前确实是公开且过审的（不盲信通知），再把标题正文算成"指纹向量"存进 ES。文章下架（不管哪条路径）就立刻从目录和知识库里撕掉这一页；已发布的文章改了标题/标签/简介，搜索服务也会收到通知同步更新目录，保证搜出来的是最新信息。有人点赞评论、文章被浏览（浏览量由文章管理那边攒一批后通知过来），都只更新"热度数字"并按公式重算，不重算指纹——而且热度不是来一条改一次目录，先在小本子上累加，攒一会儿（默认 15 分钟）再批量写回目录，避免爆款文章被反复"整页重抄"。

### 板块二 ☆：全文检索（SearchService + SearchController）

**设计版**：

- **查询编排**：参数校验（q 必填、分页上限 50）→ **查询词转义防 ES 注入**（`+ - = && || > < ! ( ) { } [ ] ^ " ~ * ? : \ /`）→ Redis 热点缓存（key 遵循 `search:模块:功能:标识`，TTL 10m，互动热度更新失效对应缓存）→ 未命中则 type 路由索引。
- **DSL**：`bool must(multi_match: title^4 → tags^3 → summary^2 → content)` + `filter(visibility=PUBLIC)`（**查询侧强制过滤，与写入侧门控双保险**）+ `filter(tag/category/zone/publishTime 区间，可选，2026-11-03 补齐)` + `function_score(heat)`；用户检索追加笔名 text+ik 模糊匹配。
- **排序枚举收口**：RELATED（默认，relevance+热度）/ NEWEST（updateTime 降序）/ HEAT（heat 降序）——排序字段仅白名单枚举映射，不透传用户输入构造 DSL。
- **空结果兜底**（2026-11-03 批次A A1 定案 + 增强采纳）：
  - 无命中不返回空页 → 查询词实时 Embedding（带超时控制，`fallback.embedding-timeout`）→ KNN 检索 vec_dense 返回语义相似集，`SearchResultVO.source=fallback` 标记来源
  - Embedding 超时/失败 → 二级兜底返回 HEAT 热门列表（复用降级链路热门数据，仍标 fallback）
  - 热门数据也获取失败 → 返回空页（兜底失败不阻塞主链路）
  - 兜底结果不写热点缓存（防把兜底当真命中）
- 返回高亮片段 + 分页；ES 异常 → 降级链路（板块五）。
- **权限口径**（2026-11-03 批次C C4 定版）：`/search`、`/search/suggest`、`/search/similar/**` 匿名可用（网关白名单登记，对齐 content `/article/*` 口径）；`/search/history` 需登录（user_id 硬收口）。

**通俗版**：读者搜"修仙"，系统按"标题最像 > 标签 > 简介 > 正文"的优先级打分，再叠加文章热度加权，可以再按分类/分区/发布时间缩小范围，且**永远只从公开文章里找**（就算有人往库里塞了私密的，查询时也会被强制拦住）。一个结果都搜不到时不给空白页，而是把你的搜索词现场算成"指纹"，去库里找指纹最像的几篇推给你，并告诉用户这是兜底推荐（这一步有严格耗时控制，算不出来就退而推热门文章，绝不硬凑）。查询词里的特殊符号先消毒，防止伪造查询语句。

### 板块三 ☆：相似推荐（VectorService + KnnRecommender + SimilarController）

**设计版**：

- 入口 `GET /search/similar/{articleId}?topK=`；取源文章 vec_dense + tags。
- KNN：`num_candidates=100`（可配）、TopK 默认 10（可配）、cosine。
- 融合评分 = 向量相似 + 标签重合度 + 行为热度加权 → 阈值过滤（`knn.similarity-threshold` 默认 0.0 不过滤；ai RAG 侧的 0.65 阈值属 ai 侧，不在此收敛）→ **强制 filter(visibility=PUBLIC)**（2026-11-03 批次C C3 定版，与查询侧双保险红线对齐）→ 排除自身与已下架文档。
- 复用：前端"相关文章" + ai 服务经 SearchClient（✅ 契约已落地，批次B B1 2026-11-03，见 api-index §三）调 `similar`。

**通俗版**：看完一篇文，推荐"长得最像"的其他文：不光比内容指纹，还比标签重不重合、文章热不热，三项加权后择优，自己排除自己。

### 板块四 ☆：RAG 知识库侧职责（与 ai 服务的分工）

**设计版**（对齐 Design-Document §2.6）：

- **存储选型结论**：复用 ES `dense_vector`，不新增 pgvector/Milvus。
- **search 负责摄入**（监听同一 `content.published`）：
  - 入库门控 = visibility=PUBLIC + 审核终态 PASS + 正文 ≥200 字 + DFA 未命中高风险（**审核终态/DFA 结论经回查获取的方式待确认——缺口 G3 扩展，契约待落地 → 批次B B6**）
  - 处理链：正文清洗 → 切片（~500 字/段，重叠 50 字）→ Embedding → 写 `article_chunks`
  - chunk 字段：chunk_id / article_id / author_id / title / author_name / chunk_text / **chunk_vector(dense_vector, cosine, indexed)** / zone / visibility / publish_time / ingest_time
- **摄入范围**（2026-11-03 批次C C2 定版）：一期仅公开文章正文入库；**书籍章节不入 article_chunks**（小月暂不引用小说内容），预留章节粒度摄入扩展待后续立项。
- **ai 负责查询**：
  - LangChain4j `ContentRetriever` 封装 KNN TopK(5) + 强制 filter PUBLIC + 相似度 ≥0.65
  - 引用卡片展示前回查文章当前态；站内内容按**不可信数据**包裹拼 prompt（不进 system 段，防间接注入）
  - ES/Embedding 故障 → ai 侧静默降级纯 LLM
- **两套向量体系统一**（2026-11-03 批次C C1 定版，闭环 G5）：vec_dense 与 chunk_vector 统一为同一 Embedding 单模型（维度一致，具体选型落地时定）；各自模型版本写入索引 meta 防漂移错位，模型变更 → 全量重建索引。
- **成本控制**（ai 侧检索路由，search 无感）：寒暄/纯润色/短消息（<10 字）不查 RAG。

**通俗版**：发布的公开文章会被自动"切页装订"成小月的知识库（每页 500 字、页与页有重叠防断章取义），只有够长、够干净、公开过审的文章才配入库。小月回答时优先翻这本库并附引用卡片；文章一下架立刻从库里抽走对应页，防止"文没了引用还在"。

### 板块五 ☆：降级与可靠性

**设计版**：

- **ES 不可用降级**：`fallback.enabled` 开关 → 降级返回热门列表（content `/article/hot` 同源数据，`source=DEGRADED` 标记、不缓存）+ 前端提示搜索不可用（2026-11-03 批次A A3 定案：content 无公开 keyword 检索端点，公开搜索已全划归 search，不做 Feign 查 DB 降级）；降级关闭或热门列表获取失败 → SEARCH_ES_UNAVAILABLE。
- **Embedding 失败**（摄入侧）：延迟重试，耗尽进 DLQ + 告警（防文章永久漏入库无感知）。
- **热度刷写的自我保护**（2026-11-03 批次A A7 定案 + 工程细节补充）：
  - 消费端互动高峰只累加 Redis 热度待刷新键（不碰 ES，可用性不依赖 ES；累加成功才 ACK，失败重试，热度数字不丢）
  - `IndexHeatFlushJob` 按周期（`heat.flush.interval-ms` 默认 15min）聚合 `_bulk` 提交——批量写按 `batch-size`(1000) × `concurrency`(2) 分片，一次搬上千篇也不会撑爆 ES 的单次请求上限
  - 只提交热度小字段不重抄整页（保住指纹向量）
  - pending 键每篇原子取出（失败则保留键下轮重试，不丢热度也不放大重试）；键自带 2 小时保活兜底（正常路径用完即取走，保活只防意外）
  - 单篇每窗口至多 1 次 ES 带文档重写；ES 抖动时仅跳过本轮，下一轮继续
  - **收益看两个数**：批量请求次数约降 800 倍、单篇整页重写约降 50 倍，大头是 ES 内部"碎页合并"压力骤减
  - **升级预案**（触发条件：单轮批量写耗时逼近刷写周期一半，或 ES 频繁报段合并告警）：改为「ES 只负责找出候选文章，热度排名挪到应用层借 content 热门榜重排」，把热度彻底移出 ES 写入路径，不必另建小索引
- 无本地状态可水平扩展；限流 @RateLimiter(USER)（对齐 file 体系按需裁剪）。

**通俗版**：搜索挂了不白屏——改推当前热门文章列表，并明确告诉用户"搜索暂时不可用"；知识库录入失败会反复重试直到有人收到告警。

### 板块六 ☆：搜索联想与搜索历史（2026-11-03 新增）

**设计版**：

- **联想（suggest）**：
  - `GET /search/suggest?q=` → article_idx 的 `title.completion` / `authorName.completion` suggester 字段（仅联想用，不参与打分）返回标题/作者联想
  - 聚合 content `GET /tags?keyword=` 的标签联想 → 去重合并返回（`SuggestVO{type(KEYWORD/TAG/AUTHOR), text}`）
  - **不建标签索引**（标签池 SSOT 在 content，防双写漂移）
- **搜索历史（SearchHistoryService）**：
  - 搜索成功后异步记录 → `search_history` 单表 UPSERT（`uk(user_id, keyword)`，同词仅前进更新 updated_at，仿 reading_history 模式）
  - 超 `history.max-per-user`（默认 20）懒删最旧 → `GET /search/history` 列表 / `DELETE /search/history` 清空
- **隐私纪律**：keyword 仅存自有表，**不写入任何 ES 索引文档/向量库**（Search-Architecture §八-9），搜索历史接口 user_id 硬收口（仅本人可见）。
- **边界修订**：search 由「无 MySQL 表」修订为「无业务数据表，search_history 为唯一自有表」（Architecture §一硬边界已同步）。

**通俗版**：打字时输入框下面实时蹦出联想词（标题、作者、标签各来几个，标签直接借用文章管理那边的现成接口，不重复建一套）；搜过的词记在小本本上方便下次点一下重搜，小本本最多记 20 条、记满了自动撕掉最旧的；搜索内容是隐私，只进你自己的小本本，绝不进检索库。

## 四、契约缺口清单（🆕 落地前须先补，均未在 litisle-api/common-core 落地）

> 落地任务登记（temp.md search模块 批次B）：G1→B1、G2→B2、G3→B6、G4→B3；书籍/用户索引事件（原遗漏 1.5/1.6）→B4/B5；批次A 新增事件（article-updated/view-count-flushed）→B7。**2026-11-03 已完成文档层「契约待落地」标注，代码落地待批次B 执行。**

**G1 — `SearchClient` 未定义**

- 说明：✅ 2026-11-03 批次B B1 落地：SearchClient + SearchClientFallbackFactory + 自动装配注册入 litisle-api，DTO/VO 按 A6 归属 `dto/search`/`vo/search`（api-index §三已标 ✅）；Provider 端点待 search 本体实现
- 收口建议：Provider 由 search 实现（`/api/feign/search/**`，网关不路由）

**G2 — `social.liked` / `social.commented` 事件未定义**

- 说明：✅ 2026-11-03 批次B B2 落地：SocialLikedEvent / SocialCommentedEvent（event.social 包）+ MqConstant `EXCHANGE_SOCIAL` + `social.liked`/`social.commented` 路由键/队列；**批次十一补 SocialUnlikedEvent（delta=-1）+ `social.unliked` 路由键/队列（11.14），且 liked/unliked 事件改由 content 点赞接口发布（11.8 点赞收进 content）**
- 收口建议：social 落地时 AFTER_COMMIT 发布 + search 绑定

**G3 — published upsert 的元数据来源**

- 说明：ContentPublishedEvent 仅 bizType/bizId/contentHash；ContentClient.getContent 出参是否够建索引字段（tags/summary/cover 等）待核
- 收口建议：落地时核对 ContentDTO 字段，不足则扩 content 提供方端点（禁事件体塞大字段，保持事件轻量纪律）

**G4 — SEARCH_* 错误码未入 common-core ErrorCode**

- 说明：✅ 2026-11-03 批次B B3 落地：ErrorCode 新增 search 分段 100000-109999，SEARCH_PARAM_INVALID / PAGE_EXCEEDED / SOURCE_NOT_FOUND / ES_UNAVAILABLE 四码入枚举（Api-Architecture.md §10 分段表已同步）
- 收口建议：落地时按分段使用

**G5 — 两套向量维度（1024 vs 1536）**

- 说明：✅ 2026-11-03 批次C C1 定版：统一为同一 Embedding 单模型（维度一致，具体选型落地时定），写入索引 meta + 本清单闭环（板块四已同步）
- 收口建议：落地时按定版结论执行

**G6 — article_chunks 索引归 search 之名**

- 说明：Architecture 文档 §四未列该索引（本次已补）
- 收口建议：保持 search=ES 索引唯一维护方、ai=纯查询方的分工

**G7 — 书籍章节 RAG 边界待决（2026-11-03 新增）**

- 说明：✅ 2026-11-03 批次C C2 定版：一期书籍章节不入 article_chunks（小月暂不引用小说内容），预留章节粒度摄入扩展待后续立项（板块四已同步）
- 收口建议：落地时按定版结论执行；书籍 RAG 后续另立项

**G8 — search_history 表未登记（2026-11-03 新增）**

- 说明：搜索历史引入 search 唯一自有 MySQL 表，Sql-Document.md 尚无 DDL；「无 MySQL 表」硬边界已修订
- 收口建议：批次2 骨架阶段补 DDL 进 Sql-Document.md（id / user_id+keyword uk / updated_at，上限懒删）

## 五、落地顺序（对齐 Architecture §九 与 temp.md search模块 批次A~D）

1. ☆ 契约先行（批次B）：G1/G2/G4 入 litisle-api 与 common-core + B7 新增事件契约（✅ 2026-11-03 代码已落地，见缺口清单各条）；决策项批次A/B/C 已定版（G5/G7 闭环见缺口清单）。
2. ☆ 基础骨架：es 配置 + SearchConfig + constant/enums + 两索引 mapping 初始化（article_idx 含 suggest 字段 + article_chunks）+ search_history 表（G8 闭环）。
3. ☆ 索引同步：IndexSyncConsumer + Rabbit 声明 + DLQ + 幂等 upsert/删除 + 乱序防御门控。
4. ☆ 全文检索：SearchService + Controller（权重 DSL + 防注入 + tag/category/zone/时间范围筛选 + 空结果兜底 + 缓存 + 分页）。
5. ☆ 搜索联想与历史：suggest 接口 + SearchHistoryService（UPSERT/懒删/清空）。
6. ☆ 相似推荐：VectorService + KnnRecommender + SimilarController。
7. ☆ 兜底与观测：降级查 DB + 指标埋点 + DLQ 告警。
