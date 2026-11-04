# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 任务内容与落地摘要直接记录在本文档条目内，不引用 status.md 细案
- **不拆分「代码部分/验证部分」**：模块直接挂在「待办部分」下，代码任务与验证任务（V{n}）统一按 模块 → 板块/任务 组织

### 文档模版

** next_update.md模版: **

```
# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 任务内容与落地摘要直接记录在本文档条目内，不引用 status.md 细案
- 不拆分代码/验证部分：验证项（V{n}）直接挂在对应模块下，与板块任务并列

### 文档模版

（本节：书写 next_update.md 的模版说明）

### 归档与暂不实现（板块完成后的文档迁移规则）

（沿用本文档既有迁移规则章节，新增文档原样保留）

--------------------------

## 待办部分

#### {模块名}模块

> 板块划分依据：{设计文档名，如 XX_Function_Develop.md / XX-Architecture.md}；推进顺序：板块一 → 二 → ...（有依赖的板块注明前置）

##### 板块{n}：{板块名称}

###### {n}.{m} {任务短标题}

- [ ] {任务内容一句话 + 关键口径/铁律 + 涉及的表/事件/常量}
  - ✅ {yyyy-MM-dd}：{落地摘要：核心类/方法 + 关键分支决策}；跨板块遗留：{遗留项（所属板块）}；编译验证已通过（{yyyy-MM-dd}）

------------

（每个板块之间用 ------------ 分隔线隔开）

##### V{n} {验证主题}

- [ ] {验证内容}（依赖{前置板块/模块}）

-------------------------

## 暂不实现汇总

#### {模块名}模块
```

### 归档与暂不实现（板块完成后的文档迁移规则）

**相关文档职责**：

| 文档 | 职责 |
| --- | --- |
| `docs/next/next_update.md`（本文档） | 仅收纳「待办 + 待验证」的活跃任务，按 模块 → 板块/任务（含验证项 V{n}）组织 |
| `docs/next/temporarily/` | 收纳暂不实现/暂不做/挂起项（索引 Temporarily_not_implemented.md + temporarily-01~04 正文文件，2026-10-28 拆分），每条必须附触发条件 |
| `docs/archive/` | 已完成板块的实现记录归档：按模块分子目录（如 `content/`、`review/`），每目录一个同名索引页 + 按板块拆分的板块文件（如 `content/content-09-temp-checklist.md`）；日期型归档（如 `20260929-implementation-records.md`）放 archive/ 根 |

**板块完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将该板块的实现步骤/完成记录（原散落在本文档条目内）整理移入 `docs/archive/` 对应归档文档（新模块新建归档子目录 + 同名索引页，遵循上方命名惯例；已有同模块归档则追加进对应板块文件，如临时清单批次追加进 content-09-temp-checklist.md）。
2. **本文档回填**：将已完成条目从本文档删除（或标注 ✅ 与日期后移除），保持本文档只留活跃待办。
3. **挂起项转移**：板块中因宿主模块未开发而无法落地的事项（如契约已备但入口未建），移入 `docs/next/temporarily/` 对应部分文件（代码类挂起进 temporarily-01-code.md，预留/暂不实现进 temporarily-04-deferred.md），并写明触发条件，供宿主模块开发时迁回本文档。
4. **预留项登记**：板块规划中明确「暂不实现/暂不做」的事项（含触发条件与决策原因），一并移入 temporarily-04-deferred.md，不在本文档保留。

> 纪律：迁移时条目内容原样搬运（含口径修正、注意事项），不压缩关键细节；归档文档与本文档/Temporarily 清单之间用相对路径互链，保证可回溯。

--------------------------

## 待办部分

#### content模块

> 板块划分依据：Content_Function_Develop.md / Content-Architecture.md；推进顺序：板块一 → 二 → 三 → 四/五/六（可并行）→ 七 → 八（依赖 review 板块三 3-5 消费端联调）

> **板块一~八已全部完成并归档**（2026-10-20）：实现记录见 [content-implemention-records.md](../archive/content/content-implemention-records.md)（next_update 条目摘要与实现细案，原样搬运；2026-10-28 拆分为九板块文件，本页为索引）

##### V1 发布链路联调

- [ ] 发布 → review 送审 → result 回写全链路联调（依赖板块三/板块八，前置 litisle-ai chat 端点）

##### V2 可见性与缓存验证

- [ ] visibility 判定链、推荐流展示前回查验证；密码防爆破限频演练

-------------------------

#### search模块

> 板块划分依据：[Search-Architecture.md](../Architecture/Search-Architecture.md) §九 + [Search-Business_Guide.md](../Study/Search-Business_Guide.md) §四缺口清单（G1~G8）+ temp.md search模块 批次A~D + 2026-11-03 设计审查（发现 6 项重大缺陷 A1~A6）；推进顺序：批次一 → 二 → 三 → 四 → 五 → 六 → 七 → 八。
> **搜索归属核查结论（2026-11-03，精准扫描 content 控制器）**：
> - 不迁移（保留 content）：`GET /article/my`、`GET /book/my` 的 keyword 过滤（作者管理侧列表，author_id 硬收口，DB 直查合理）；`GET /tags?keyword=` 前缀搜索（tag 池为 content 域元数据，SearchTypeEnum 无 TAG，标签搜索≠全文检索）；`GET /article/list`、`/article/hot`（结构化筛选列表，无关键词）。
> - 归属 search（既定设计，`BookController` `/book/list` 注释已声明「一期不做 keyword 搜索（search 模块书籍索引为外部依赖）」）：公开文章/书籍/用户的全文与多维度检索 + 相似推荐。
> - ~~**发现的遗漏**：book_idx / user_idx 无索引同步事件来源（书籍创建/变更/完结/下架无事件、UserRegisteredEvent 为空类未发布）~~：**2026-11-04 已随批次五收口**（书籍事件 B4 + 用户事件 B5 + book_idx/user_idx 消费）。
> **优先级口径（2026-11-03 定）**：优先 content 文章与书籍相关链路（批次一~五），联想/相似/降级随后；user_idx 同步链路已于 2026-11-04 随批次五 5.3 落地（见批次五）。
> **批次A（缺陷修正定版 A1~A7）/ 批次B 契约 B1/B2/B3/B7（SearchClient、social 事件、SEARCH_* 错误码、article-updated/view-count-flushed 事件）/ 批次C 决策（C1~C4）已于 2026-11-03 完成**；批次B 余项 B6（回查字段核对）已于 2026-11-04 随批次二 2.1 收口，B4（书籍事件）/B5（用户事件）随批次五 收口。

##### 批次一：基础骨架收尾（对齐批次D D1 余项）

###### 1.1 ES 索引 mapping 初始化

- [x] article_idx mapping（title/tags/summary/content ik 分词 + visibility 闸门字段 + authorId/authorName（multi-field 含 .completion）+ like_cnt/comment_cnt/view_cnt + heat + vec_dense(dims=1024,cosine,indexed) + updateTime + suggest completion 子字段）；book_idx mapping（书名/作者/简介 text+ik + 分类 keyword）；article_chunks mapping（chunk_id/article_id/author_id/title/author_name/chunk_text/chunk_vector(dense_vector,cosine,indexed)/zone/visibility/publish_time/ingest_time，按 C2 一期仅公开文章正文）；索引名走 `litisle.search.index.*` 配置；Embedding 模型版本写入索引 meta（C1 单模型口径）；user_idx mapping 可一并创建（消费链路暂缓，见暂不实现汇总）
  - ✅ 2026-11-03：EsIndexInitializer（es 包，ApplicationRunner 启动时逐索引幂等创建，exists 跳过防覆盖；单索引失败仅告警不阻断启动，降级链路兜底）；四索引 mapping 全量落地：article_idx（ik_max_word/ik_smart + title/authorName completion 子字段 + vec_dense 1024 维 cosine indexed）+ book_idx（书名/作者/简介/分类）+ user_idx（笔名/简介，占位）+ article_chunks（chunk_id/article_id/chunk_text/chunk_vector 等 11 字段）；mappings._meta 写入 embedding_model（占位 unset-1024d，选型随 VectorService 定）+ embedding_dims（C1）；SearchBizConstant 补 book/user/chunk 字段名常量 + SEARCH_KEYWORD_MAX_LENGTH(64)/EMBEDDING_DIMS/ik 分词器名/meta 键；SearchProperties.Index + yml 补 `index.chunk=article_chunks`；跨板块遗留：Embedding 模型选型后改写 EMBEDDING_MODEL_PLACEHOLDER 并全量重建（批次七）

###### 1.2 search_history 表 DDL 登记

- [x] search_history DDL（id / user_id+keyword 组合 uk / keyword varchar 长度对齐 SEARCH_PARAM_INVALID 上限 / updated_at）登记 Sql-Document.md + local 库建表（闭环 G8）
  - ✅ 2026-11-03：docker/init/mysql/search-schema.sql 新建（search_history 单表：id / user_id / keyword varchar(64) / updated_at，uk(user_id,keyword) + idx_user_updated；keyword 长度 64 与新增常量 SEARCH_KEYWORD_MAX_LENGTH 同源，批次四参数校验按 SEARCH_PARAM_INVALID 拒绝超长）；00-init-databases.sql 补 litisle_search 建库 + GRANT 授权行；Sql-Document.md 新增「五、litisle-search 模块」章节（含存量环境手动执行说明 + ES 侧无 DDL 备注）；实体/Mapper 随批次六落地

###### 1.3 Feign 启用与骨架遗留收口

- [x] 主类/配置补 @EnableFeignClients（扫描 litisle-api）等 D1 骨架跨批次遗留项收口
  - ✅ 2026-11-03：LitisleSearchApplication 补 @EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")（ContentClient 等消费方 Bean 注册，批次二回查起消费；Provider 端点 /api/feign/search/** 由本服务实现无需额外注解），主类注释同步更新
  - ✅ 2026-11-03：**D1 骨架先行落地（编译验证已通过，本批次 1.1~1.3 余项保留不勾选）**：pom 依赖对齐 file 模块（web/actuator/validation/amqp/common 四件套/mybatis-plus+mysql/Feign+Sentinel+Nacos/ES Java Client 8.18.8/knife4j/litisle-api）；主类 LitisleSearchApplication（端口 8086，无 @EnableScheduling）；application.yml（`litisle.search.*` §六 全量配置外置 + Nacos/MySQL litisle_search 库/Redis/RabbitMQ 手动 ACK）+ application-local/dev.yaml（Sentinel 心跳 8722、ES localhost/litisle-elasticsearch:9200）；enums SearchTypeEnum/SearchSortEnum（DictEnum 三值绑定）；constant SearchBizConstant（EXCHANGE_SEARCH_DLX=litisle.search.dlx / QUEUE_SEARCH_INDEX_DLQ / PUBLIC 闸门值 / ES 字段名收口 / 热度 pending 键前缀与 hash 字段）；config 三件 SearchProperties（§六 参数绑定）+ SearchMqConfig（六同步队列 + 独立 DLX/DLQ 拓扑声明，消费者留批次二）+ ElasticsearchConfig（客户端装配，索引初始化留 1.1）；Architecture §1.2 已同步 ✅骨架标注、§六 rabbit 行默认值修正

##### 批次二：文章索引同步消费端（依赖批次一；对齐批次D D2 文章部分）

###### 2.1 建索引回查字段核对（B6，缺口 G3）

- [x] 核对 ContentClient.getContent 出参是否够建索引字段（tags/summary/cover 等）+ 审核终态 PASS/DFA 结论获取方式；不足则扩 content 提供方端点（禁事件体塞大字段，保持事件轻量纪律）
  - ✅ 2026-11-04：核对结论——原 ContentDTO 缺 tags/summary/authorName/publishTime/zone/category，不足建索引。新建 `ArticleIndexDocDTO`（articleId/authorId/authorName/title/summary/contentHtml/tags/category/zone/status/visibility/publishTime/likeCnt/commentCnt/viewCnt/contentHash）；`ContentClient` 新增 `GET /api/feign/content/article-index-doc`（fallback 走 fail-fast 抛 BizException）；content 侧 `ContentInternalService#getArticleIndexDoc` 实现（按 del_flag=0 查 Article + 标签批量回查 + 作者昵称 best-effort 回查），Feign 提供方端点同步接入
  - ✅ 2026-11-04：审核终态/DFA 结论不新增 review 回查——content 侧 status=2 本身即「审核 PASS 回写或免审直落且 DFA 放行」的唯一出口，直接用 status=2 作等价门控（结论记入 ContentInternalServiceImpl 类注释）
  - ✅ 2026-11-04：偏差说明——cover 未入索引（EsIndexInitializer mapping 与 ArticleIndexDoc 均无 cover 字段，本期不做；后续如需展示封面再扩 mapping）

###### 2.2 IndexSyncConsumer 文章事件消费

- [x] 消费 `content.published`：事件体仅定位键 → ContentClient 回查元数据 → 乱序防御门控（status=2 且 visibility=PUBLIC 才写，防 unpublished 先到后 published 迟到写回）→ VectorService Embedding 预计算 vec_dense → 按 articleId 幂等 upsert article_idx；同时摄入 article_chunks（入库门控：PUBLIC + 审核终态 PASS + 正文 ≥200 字 + DFA 未命中高风险；切片 ~500 字/段重叠 50 字；按 articleId 幂等覆盖删旧写新）
  - ✅ 2026-11-04：`ArticleIndexServiceImpl#handlePublished`（回查 → status=2 且 visibility=1 门控 → 组装 ArticleIndexDoc → 按 articleId upsert → `ArticleChunkService#rebuild`）；正文经 `SearchTextUtils#toPlainText` 去 HTML 标签后入 content 字段；heat 初始 0（批次三重算）；vec_dense 由 `VectorService#embed`（标题+摘要+正文截断 2000 字），模型未接入返回 null 字段缺省
  - ✅ 2026-11-04：article_chunks 摄入（`ArticleChunkServiceImpl`）：正文门槛 `ingest.min-content-length`(200) + `chunk-size`(500)/`chunk-overlap`(50) 切片，chunkId=articleId_序号，先 deleteByQuery 旧块再 `_bulk` 写入（索引未建时跳过删除；bulk errors 逐条 error 留痕）；审核终态/DFA 以 status=2 等价门控（见 2.1）
  - ✅ 2026-11-04：新建实体 `ArticleIndexDoc`/`ArticleMetaDoc`/`ArticleChunkDoc`（字段名用 `@JsonProperty(SearchBizConstant.FIELD_*)`，`@JsonInclude(NON_NULL)` 保证局部更新与空向量不写脏值）
- [x] 消费 `content.unpublished`：删 article_idx 文档（404 视为成功幂等）+ 删 article_chunks 旧块 + 失效热点查询缓存（四条下架路径全量覆盖，隐私红线）
  - ✅ 2026-11-04：`ArticleIndexServiceImpl#handleUnpublished`——先 `exists` 判断再 delete（不存在按幂等成功跳过，避免 404 误判触发重试）→ `ArticleChunkService#deleteByArticleId`（索引未建时跳过）；四条下架路径统一按删除处理，不按 reason 分流
  - ⏳ 遗留批次四：热点查询缓存尚未落地（本服务当前无查询缓存），缓存失效随批次四缓存实现接入（实现位置已在代码注释标记）
- [x] 消费 `content.article-updated`（A2 定案）：轻量回查 → partial update 元数据字段（title/tags/summary/cover），不动向量与 chunks
  - ✅ 2026-11-04：`ArticleIndexServiceImpl#handleArticleUpdated`——回查后二次门控（文章已下架/转私密则转 `handleUnpublished` 清理）→ 组装 `ArticleMetaDoc`（title/tags/summary/zone/category/updateTime，null 字段不序列化）→ `update + doc` 局部更新；索引文档不存在时 warn 跳过（首发消息可能丢失，待重发/重建补齐）
  - ✅ 2026-11-04：偏差说明——cover 不在本批次索引字段（同 2.1）
- [x] 消费可靠性范式（对齐 review/content 规范）：手动 ACK（MqAckHelper）+ MqIdempotentHelper 幂等 + 业务失败 warn 后 ack 防毒丸 + 异常指数退避延迟重投，耗尽 nack 入 DLQ（SearchMqConfig 已声明拓扑）+ DLQ Consumer 告警留痕
  - ✅ 2026-11-04：新建 `mq/IndexSyncConsumer`（三个 `@RabbitListener`：QUEUE_CONTENT_PUBLISHED/UNPUBLISHED/ARTICLE_UPDATED）——`MqTraceSupport.restore` + `MqAckHelper.executeWithRetry`，bizType≠1 或 bizId 缺失时 warn 后正常返回（ack 丢弃，不浪费重试次数）；异常按指数退避重投到 `litisle.search.delay.exchange`（routingKey=队列名）
  - ✅ 2026-11-04：`SearchMqConfig` 补延迟重投拓扑——`searchDelayExchange`（CustomExchange，x-delayed-message + x-delayed-type=direct）+ 六条延迟绑定（绑定键=队列名）；同时修复既有死信拓扑 bug：六条 DLX→DLQ 绑定键由业务路由键改为队列名，与各队列 `deadLetterRoutingKey(queueName)` 对齐（Direct 精确匹配，原写法死信进不了 DLQ）
  - ✅ 2026-11-04：新建 `mq/IndexDlqConsumer`（监听 QUEUE_SEARCH_INDEX_DLQ）——从 `x-death` 头取原队列、截断消息体（500 字符）后 error 留痕，`executeWithAck` 确认出队；DLQ 不配 DLX 防死信再死信环路
  - ✅ 2026-11-04：参数外置——`SearchProperties.Retry`（max-count=5 / base-delay-ms=30000）+ `Ingest`（min-content-length/chunk-size/chunk-overlap）+ `Embedding.enabled`，yml 同步
  - ✅ 2026-11-04：偏差说明——**未使用 MqIdempotentHelper**：三类动作均以 articleId 为幂等键（upsert / delete / partial update 重复执行结果一致），SETNX 先占位会在「首次消费失败 → 延迟重试」时误判为重复消息而丢弃，故改为天然幂等重试（结论已写入 ArticleIndexService 接口注释）
  - ✅ 2026-11-04：偏差说明——延迟重投次数只走消息头（`MqSendUtil.HEADER_RETRY_COUNT`），事件体本无 retryCount 字段且属契约库不可私改；MqAckHelper 读消息头计数，重试上限逻辑不受影响

##### 批次三：互动热度链路（依赖批次二；对齐批次D D2 热度部分，A5/A7 定案）

###### 3.1 热度事件消费与待刷新键累加

- [x] 消费 `social.liked` / `social.commented`：仅 `HINCRBY` 累加 Redis 热度待刷新键 like/comment 增量（不直写 ES，A7：带 1024 维向量约 4KB 整文档重写写放大）；消费 `content.view-count-flushed`：`HSET` 覆盖写 view_cnt 绝对值（A5）；**ACK 后置纪律**：Redis 累加成功才 ACK，失败 nack 重试防热度漂移；pending 键常量 `SearchBizConstant.HEAT_PENDING_KEY_PREFIX` + hash 字段 view_cnt/like_delta/comment_delta，TTL 走 `heat.flush.pending-key-ttl`（2h 兜底）
  - ✅ 2026-11-04：新建 `HeatPendingService` / `HeatPendingServiceImpl`——key=`search:heat:pending:{articleId}`，`view_cnt` 用 HSET 覆盖写绝对值（可重放）、`like_delta`/`comment_delta` 用 HINCRBY 累加；每次写入刷新 `heat.flush.pending-key-ttl`（2h 兜底，正常由 3.2 GETDEL 取走）；Redis 异常包装 `IllegalStateException` 触发延迟重试，保证「累加成功才 ACK」
  - ✅ 2026-11-04：新建 `mq/HeatEventConsumer`（三个监听：QUEUE_SOCIAL_LIKED / QUEUE_SOCIAL_COMMENTED / QUEUE_CONTENT_VIEW_COUNT_FLUSHED）——复用批次二已验证的 `MqTraceSupport` + `MqAckHelper.executeWithRetry` + 延迟重投拓扑（routingKey=队列名），不新增拓扑
  - ✅ 2026-11-04：幂等口径——点赞/评论自增不可重入，消费前置 `MqIdempotentHelper.tryAcquire(queue, eventId)`（SETNX）拦重复消息；先占位后累加，优先保证 ACK 失败重投时「同一 eventId 不重复计数」（代价：占位成功但累加失败会少计一次，属宁漏勿重的可接受范围）；浏览量 HSET 绝对值天然幂等，不做 eventId 去重
  - ✅ 2026-11-04：非文章互动（bizType≠1，如评论点赞）debug 日志后 ack 丢弃，不占用重试次数；viewCount 缺失属发送侧契约违约，warn 后丢弃
  - ✅ 2026-11-04：3.2 已同日完成（见下方 3.2 条目），批次三整体收口

###### 3.2 IndexHeatFlushJob 定时刷写

- [x] 定时任务（`heat.flush.interval-ms` 默认 15min，fixedDelay 不堆积）：pending 键原子取出聚合 → 按 `batch-size`(1000)×`concurrency`(2) 分片 `_bulk` partial update 重算 heat（update+doc 只改 heat 字段禁 index 整篇替换；heat = (w_like×like + w_comment×comment + w_view×view) × 时间衰减，权重/半衰期外置 `litisle.search.weight.heat.*`，默认 2/3/0.1/168h）；不动向量；ES 抖动仅跳过本轮；互动热度更新失效对应热点查询缓存
  - ✅ 2026-11-04：新建 `job/IndexHeatFlushJob`（`@Scheduled` fixedDelay=interval-ms、首次延迟 initial-delay-ms）——SCAN 扫 pending 键（禁用 KEYS）→ 逐键原子取走 → `mget` 回查现有文档 → 按 A5 公式重算 → 按 batch-size 分片、concurrency 固定线程池并行 `_bulk` 局部更新；线程池停机 `@PreDestroy` 释放
  - ✅ 2026-11-04：**GETDEL 语义落地方式**——pending 键为 Hash，Redis 原生 GETDEL 仅支持 String，故用 Lua `HGETALL + DEL` 原子脚本（`HeatPendingService#takePending`）；避免「读出后、删除前」被 3.1 消费端新写入的增量被覆盖丢失
  - ✅ 2026-11-04：写回字段为「计数基数 + heat」（like_cnt/comment_cnt/view_cnt/heat）——`update + action(doc)` 局部更新，绝不 index 整篇替换以保住 vec_dense/content；偏差说明：清单原文「只改 heat 字段」，但 heat 公式依赖计数绝对值，且 mapping 中三计数本是 heat 计算基数（A5），故一并写回，仍属热度相关字段、不触发向量重写
  - ✅ 2026-11-04：衰减基准取文档 `updateTime`（当前实现中兼具发布时间语义，批次二建索引时由 content publishTime 赋值）；缺失时按不衰减(1.0)，半衰期做 0/负值下限防御
  - ✅ 2026-11-04：容错闭环——空键/无有效增量跳过不写 ES；单键取走或解析异常只跳过该键；回查或 `_bulk` 整体异常 → 已取走增量 `mergeBack` 回灌 Redis（点赞/评论 HINCRBY 追加、浏览量 putIfAbsent 保留新值）等下一轮，不静默丢数；`_bulk` 逐条失败单独回灌 + error 留痕，成功条目照常生效
  - ✅ 2026-11-04：`LitisleSearchApplication` 补 `@EnableScheduling`；`litisle.search.weight.heat.*`（like=2/comment=3/view=0.1/time-decay-half-life=168h）与 `heat.flush.*` 沿用既有 SearchProperties + yml（注释同步为「批次三 3.2 已落地」）
  - ⏳ 遗留批次四：热点查询缓存失效仅留带注释接入标记（`IndexHeatFlushJob` 内），本批次不提前实现缓存

##### 批次四：全文检索（依赖批次一/二；对齐批次D D3）

###### 4.1 SearchController + SearchService 检索编排

- [x] `GET /search`：参数校验（q 必填、pageNum/pageSize 上限 `page.max-page-size`=50）→ 查询词转义防 ES 注入 → Redis 热点缓存（key 遵循 `search:模块:功能:标识`，TTL 10m）→ type 路由索引 → bool must(multi_match title^4/tags^3/summary^2/content) + filter(visibility=PUBLIC 双保险) + filter(tag/category/zone/时间范围，可选) + function_score(heat)；排序枚举白名单映射（RELATED/NEWEST/HEAT）；返回高亮片段 + 分页；命中列表按 type 拆 articleList/bookList/userList（A6 落地口径）；深分页上限（ES max_result_window，pageNum×pageSize 乘积上限约束）
  - ✅ 2026-11-04：新增 `SearchController`（GET /api/search）+ `SearchFeignProviderController`（POST /api/feign/search/search）+ `SearchService/Impl`；参数校验收口（q 必填/≤64/控制字符拦截、type/sort 枚举白名单、pageSize≤max-page-size、pageNum×pageSize≤新增 `page.max-result-window`=10000）；查询词按架构文档 §八 保留字符转义（结构化 DSL 无 query_string 注入面，转义作纵深防御）；multi_match 权重取 `weight.title/tags/summary`；article 索引 filter(visibility=PUBLIC + tag/category/zone/updateTime 范围)，book/user 按各自 mapping 字段检索；RELATED 用 function_score(log1p(heat), boost_mode=sum) 加权，NEWEST/HEAT 字段降序；三索引各返回高亮首片段；新增读侧投影 `BookIndexDoc`/`UserIndexDoc`（book_idx 未映射 authorId，BookDocVO.authorId 暂为 null）
  - ✅ 2026-11-04：新增 `HotQueryCacheService/Impl`：键 `search:hotquery:{type}:{sort}:{pageNum}:{pageSize}:{MD5(关键词+筛选)}`，TTL 取 `cache.ttl`；读/写异常 fail-open，仅 `source=ES` 结果写缓存；`evictAll()` SCAN+分批 DEL，异常抛 IllegalStateException 交 MQ 重试

###### 4.2 空结果兜底（A1 定案）

- [x] 无命中：查询词实时 Embedding（超时 `fallback.embedding-timeout`=1s）→ KNN 检索 vec_dense 语义相似集，`source=fallback` 标记；Embedding 超时/失败 → 二级兜底 HEAT 热门列表（复用降级链路数据，仍标 fallback）；热门也失败 → 空页（兜底失败不阻塞主链路）；**兜底结果不写热点缓存**
  - ✅ 2026-11-04：`SearchServiceImpl#fallback` 落地三级链路：一级 `embedWithTimeout`（独立守护线程池 + Future 超时，返回维度不符视为失败）→ `knnFallback`（article_idx `vec_dense` KNN + visibility 过滤，固定第一页）；二级 `heatFallback`（article_idx 按 heat 降序，同批次八 A3 热门口径）；三级空页。所有兜底 `source=fallback` 且不写缓存
  - ✅ 2026-11-04：偏差/依赖说明——`VectorService` 当前未接入模型恒返回 null，故线上现阶段实际走二级热门口径；KNN 分支代码已就绪，待批次七向量模型接入即生效；book/user 索引无 vec_dense/heat，空结果直接返回空页；二级热门口径暂查 article_idx，批次八统一降级服务接入后可替换为 content 热门端点

###### 4.3 权限登记

- [x] `/search`、`/search/suggest`、`/search/similar/**` 网关匿名白名单登记 + `/search/history` 需登录（user_id 硬收口，C4 定版）；SEARCH_* 错误码使用（100001~100004 已入 common-core）
  - ✅ 2026-11-04：`litisle-gateway` 路由表已有 `litisle-search`（`Path=/api/search/**`）；白名单由整体 `/api/search/**` 收窄为 `/api/search`、`/api/search/suggest`、`/api/search/similar/**`，`/api/search/history` 故意不登记（需登录，user_id 由登录态取）
  - ✅ 2026-11-04：检索链路使用 `SEARCH_PARAM_INVALID`（参数非法）、`SEARCH_PAGE_EXCEEDED`（单页/深分页超限）、`SEARCH_ES_UNAVAILABLE`（ES 异常）；`SEARCH_SOURCE_NOT_FOUND` 沿用批次二索引回查

##### 批次五：书籍索引链路（依赖批次一；优先级仅次于文章，B4 契约随本批次收口）

###### 5.1 书籍索引变更事件契约（B4）

- [x] litisle-api 定义书籍创建/变更/完结/下架事件 + MqConstant 路由键/队列（对齐 ContentPublishedEvent 定位键纪律：事件体仅 bizType/bizId/contentHash）；content 侧书籍变更点 afterCommit 发布接入
  - ✅ 2026-11-04：新增 `ContentBookChangedEvent`（基座四字段 + `bookId` 定位键）与 `MqConstant.ROUTING_CONTENT_BOOK_CHANGED/QUEUE_CONTENT_BOOK_CHANGED`；创建/编辑/完结/下架/删除统一走一条 `content.book-changed`，消费端回查权威态后收敛（完结当前无独立端点，端点落地时复用同事件）。content 侧新增 `ContentEventProducer#sendBookChanged`（复用 outbox + sendCritical），`BookServiceImpl.create/update/delete` 接入发布：create/update 无事务即时发送，delete 的 `@Transactional` 注册 afterCommit 后发送
  - ✅ 2026-11-04：新增 `BookIndexDocDTO` + `ContentClient#getBookIndexDoc`（fail-fast 降级）+ `ContentInternalService/Impl#getBookIndexDoc`（显式 del_flag=0，不存在/已软删抛 `BOOK_NOT_FOUND`，作者昵称 best-effort）+ `ContentFeignProviderController` 端点 `/api/feign/content/book-index-doc`

###### 5.2 book_idx 同步消费

- [x] IndexSyncConsumer 扩展书籍事件消费：回查 → 门控 → 幂等 upsert/删除 book_idx（书名/作者/简介/分类；书籍不入 article_chunks，C2 定版）；V1 可见性红线验证覆盖 book_idx
  - ✅ 2026-11-04：新增 `BookIndexService/Impl`：`getBookIndexDoc` 回查 → 门控（`delFlag=0` 且 `status≠TAKEN_DOWN`）→ 按 bookId upsert / delete（删除前判存在，幂等）；`BOOK_NOT_FOUND` 收敛为删索引，ES/远程异常抛 `IllegalStateException` 交延迟重试
  - ✅ 2026-11-04：`SearchMqConfig` 补 `content.book-changed` 队列/绑定/延迟重投/DLX-DLQ；`IndexSyncConsumer#onBookChanged` 接入成功后 `evictAll` 失效热点缓存；书籍不入 `article_chunks`
  - ✅ 2026-11-04（批次四遗留）：`book_idx` mapping 补 `authorId`（keyword），`BookIndexDoc` 增字段，`SearchServiceImpl#toBookResult` 解析回填 `BookDocVO.authorId`；`EsIndexInitializer` 启动时对已存在索引幂等 `putMapping` 补字段。**存量影响**：新增字段可热更新 mapping、无需重建索引；但存量文档不回填，需事件重放/全量重建后 `authorId` 才有值，此前检索结果为 null（不影响命中与排序）

###### 5.3 用户索引同步（原批次B B5，本次随批次五一并落地）

- [x] 用户事件契约 + user 侧发布点 + user_idx 同步消费
  - ✅ 2026-11-04：`UserRegisteredEvent` 补业务字段（userId/username/uid/registerSource）；新增 `UserProfileUpdatedEvent`（userId 定位键）与 `MqConstant.ROUTING_USER_PROFILE_UPDATED/QUEUE_USER_REGISTERED_INDEX/QUEUE_USER_PROFILE_UPDATED_INDEX`；新增 `UserIndexDocDTO` + `UserClient#getUserIndexDoc`（fail-fast 降级）
  - ✅ 2026-11-04：`litisle-user` 引入 `common-mq`、补 `spring.rabbitmq` 配置、新增 `UserMqConfig`（声明 `EXCHANGE_USER`）与 `UserEventProducer`；`UserFeignProviderController#createUser` 成功后发 `user.registered`，`UserProfileServiceImpl#updateCurrentUserProfile` 在笔名/简介变更后发 `user.profile-updated`（均为写库后 best-effort）；新增 `/api/feign/user/index-doc` 回查端点（仅公开字段）
  - ✅ 2026-11-04：新增 `UserIndexService/Impl`：回查 → 门控（`status=NORMAL`、`delFlag=0`、笔名/简介至少一项非空）→ 按 userId upsert/delete；`USER_NOT_FOUND` 收敛为删索引；`SearchMqConfig` 补 user 两队列/绑定/延迟重投/DLX-DLQ，`IndexSyncConsumer#onUserRegistered/onUserProfileUpdated` 接入。说明：新注册用户通常无公开资料，注册事件按门控「删索引」幂等空操作，待资料更新事件再正式入索引

##### 批次六：搜索联想与搜索历史（依赖批次一/四；对齐批次D D4）

###### 6.1 suggest 联想接口

- [x] `GET /search/suggest?q=`：article_idx `title.completion` / `authorName.completion` suggester + 聚合 content `GET /tags?keyword=` 标签联想 → 去重合并返回 `SuggestVO{type(KEYWORD/TAG/AUTHOR), text}`；**不建标签索引**（标签池 SSOT 在 content，防双写漂移）；开关/条数走 `litisle.search.suggest.*`
  - ✅ 2026-11-04：新增 `SuggestService/Impl` + `GET /api/search/suggest?q=&size=`（联想端点并入 `SearchController`，未单建 Controller）。实现要点：completion suggester 复用批次一已建的 `title.completion`/`authorName.completion`（**不改 mapping、不建标签索引**）；内容依次取书名→作者→标签，按文本去重、总数封顶；`suggest.enabled` 总开关关闭返回空，词空/超 64 返回空；ES 故障只丢书名/作者联想，content 故障只丢标签联想（降级互不影响）
  - ✅ 2026-11-04：契约层新增 `SuggestVO`、`TagSuggestDTO`；`ContentClient#getTagSuggestions`（降级返回空列表，fail-open）+ content 侧 `ContentInternalService#searchTagSuggestions`（复用 `TagService#searchByPrefix`）+ `/api/feign/content/tag-suggest` 内部端点

###### 6.2 SearchHistoryService

- [x] `GET /search/history` 分页 / `DELETE /search/history` 清空；搜索成功后异步记录 → search_history UPSERT（uk(user_id,keyword) 同词仅前进 updated_at）→ 超 `history.max-per-user`（默认 20）懒删最旧；**隐私纪律：keyword 不写入任何 ES 索引文档/向量库**
  - ✅ 2026-11-04：新增 `SearchHistory` 实体 + `SearchHistoryMapper` + `SearchHistoryService/Impl` + `SearchHistoryController`（`/api/search/history` GET/DELETE，均按 `StpUtil` 当前登录用户 ID 收口，无 userId 入参防越权）
  - ✅ 2026-11-04：记录链路——检索成功后（含缓存命中/兜底结果）在 `SearchServiceImpl#recordHistory` 仅对登录用户调用 `recordAsync`（`@Async` + 内部吞异常，失败不影响检索，启动类补 `@EnableAsync`）；同词 UPSERT 命中仅前进 `updated_at`，唯一键并发冲突退化为更新；超 `max-per-user` 用 `Page` 保留最新 N 条并删除其余（**未使用 `QueryWrapper#last()`**）；分页统一 `Page` 对象
  - ✅ 2026-11-04：隐私纪律已落实——关键词只落 MySQL `search_history`，不写任何 ES 索引文档/向量库

##### 批次七：相似推荐与 Feign 提供方（依赖批次二/三；对齐批次D D5）

###### 7.1 VectorService + KnnRecommender + SimilarController

- [x] `GET /search/similar/{articleId}?topK=`：取源文章 vec_dense + tags → KNN（num_candidates=100、TopK 默认 10、cosine）→ 融合评分（向量相似 + 标签重合度 + 行为热度加权）→ 阈值过滤（`knn.similarity-threshold` 默认 0.0）→ **强制 filter(visibility=PUBLIC)（C3）** + 排除自身与已下架文档
  - ✅ 2026-11-04：`SimilarServiceImpl` 落地——源文章回查（不存在/非 PUBLIC 报 `SEARCH_SOURCE_NOT_FOUND`，无向量返回空列表）→ KNN（`vec_dense`、k=topK+1、num_candidates 取配置且不小于 k）→ 融合评分（`knn.vector-weight` 0.7 / `tag-weight` 0.2 / `heat-weight` 0.1，按总和归一化；标签重合度用 Jaccard，热度用 log1p(x)/(1+x) 归一）→ 原始 cosine 分按 `similarity-threshold` 过滤 → 融合分降序截断 topK（上限 `knn.max-top-k` 50）；filter 强制 `visibility=PUBLIC` + `must_not id=自身`（C3；下架文档已在批次二删除，故无需额外状态过滤）；ES 异常统一 `SEARCH_ES_UNAVAILABLE`
  - ✅ 2026-11-04：新增 `SimilarController`（`GET /api/search/similar/{articleId}?topK=`，网关白名单既有 `/api/search/similar/**` 已放行，无额外网关改动）；`SimilarArticleVO` 复用既有契约
  - ✅ 2026-11-04：Embedding 接入骨架——新增 `embedding/EmbeddingProvider` SPI（providerName/modelName/available/embed/embedBatch，禁止硬编码、仅做本地配置判断）；`SearchProperties.embedding` 扩展 provider/model/dimension/base-url/api-key/timeout/batch-size/rebuild-batch-size（yml 同步，密钥用 `${LITISLE_EMBEDDING_API_KEY:}` 环境变量占位符）；`VectorServiceImpl` 改为按 `embedding.provider` 精确匹配提供方，未接入返回 null（开关关闭不告警、未接入仅告警一次）、调用失败/维度不一致返回 null 不阻断索引写入；`VectorService` 新增 `available()`
  - ✅ 2026-11-04：向量全量重建框架——新增 `VectorRebuildService/Impl`（`rebuildArticleVectors` / `rebuildChunkVectors`）：提供方未就绪整体跳过；`search_after` 按主键升序分页（规避 from+size 深分页窗口）→ 逐条向量化 → bulk partial update 只写 `vec_dense`/`chunk_vector`；文章输入与发布链路共用 `SearchTextUtils#buildEmbeddingInput`（标题+摘要+正文截断 2000 字，避免口径漂移），切片直接用 `chunk_text` 原文；**触发方式（管理端点/一次性任务/开关）待确认，本次仅落框架不含自动触发**
  - ⚠️ 待确认：Embedding 提供方/模型/服务地址/密钥注入方式未定（清单未给选型），本次不写任何提供方适配器，`embedding.enabled=false`、`provider=none` 时行为与现网一致（不写向量字段）
  - 编译验证已通过（2026-11-04，`mvn -q -pl litisle-search -am compile -DskipTests` exit 0）

###### 7.2 FeignProviderController

- [x] 实现 `/api/feign/search/**`（网关不路由）：`GET /api/feign/search/similar`（对齐已落地 SearchClient，fail-fast）+ `POST /api/feign/search/search`（服务间检索，非降级链路）；供 ai RAG 与前端"相关文章"复用
  - ✅ 2026-11-04：`SearchFeignProviderController` 补 `GET /similar`（参数 `articleId` 必填、`topK` 可选，直接复用 `SimilarService`，与 REST 行为一致）；`POST /search` 已于批次四 4.1 落地，本次仅更新类注释契约清单
  - ✅ 2026-11-04：契约对齐 `litisle-api` 的 `SearchClient#similar/#search`（fail-fast 降级工厂已存在，无需改动 api 模块）
  - 编译验证已通过（2026-11-04，`mvn -q -pl litisle-search -am compile -DskipTests` exit 0）

##### 批次八：降级链路与观测（依赖批次四；对齐批次D D6）

###### 8.1 ES 降级链路（A3 定案）

- [ ] ES 不可用 → `fallback.enabled` 开关 → 降级返回热门列表（content `/article/hot` 同源，`source=DEGRADED` 标记不缓存）+ 前端提示搜索不可用；降级关闭或热门获取失败 → SEARCH_ES_UNAVAILABLE；降级数据源语义随 content 批次E P5 切换为 heat 榜序（`CONTENT_FEED_HOT_ZSET` 同源 + 榜空回退 view_cnt 直查），落地时对齐（跨模块依赖：content 批次E P5）

###### 8.2 指标埋点与告警

- [ ] 检索/索引同步/热度刷写指标埋点 + DLQ 告警留痕（批次二 DLQ Consumer 联动）

##### V1 索引同步与可见性红线验证（依赖批次二/三/五）

- [ ] 四条下架路径发出 `content.unpublished` 后 article_idx 文档与 article_chunks 旧块均被移除；乱序门控（published 迟到不写回）；未过审/私密文章永不入索引；元数据编辑后索引同步生效；heat 累加-定时 bulk 链路生效（pending 键 GETDEL 清理 + 每篇每窗口至多 1 次 ES 写 + 重复消费不重复累加）

##### V2 检索功能验证（依赖批次四/五/六/七）

- [ ] 检索参数组合（tag/category/zone/时间范围）生效；空结果兜底返回且 source=fallback 标记、兜底不缓存；联想去重合并；搜索历史记录/去重/上限懒删且 keyword 未出现在任何 ES 索引文档；KNN 结果无私密/下架内容（C3 口径）

##### V3 降级验证（依赖批次八）

- [ ] 停 ES 后按 A3 方案降级生效且结果标记来源；降级关闭/降级失败返回 SEARCH_ES_UNAVAILABLE

-------------------------

## 暂不实现汇总

#### content模块

#### search模块

- ~~user_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user_idx），此项从「暂不实现汇总」移出，原触发条件已满足
