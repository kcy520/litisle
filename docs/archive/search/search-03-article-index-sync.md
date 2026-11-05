> **归档说明**：新建板块文件（批次二，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次二：文章索引同步消费端」节，正文原样搬运（含 ✅ 摘要、偏差说明与待环境点）。
> **来源**：[next_update.md](../../next/next_update.md) 批次二；挂起项（批量回查端点）见 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md)。

## 板块三：文章索引同步消费端（批次二，2026-11-05 归档）

### search模块

#### 批次二：文章索引同步消费端（依赖批次一；对齐批次D D2 文章部分）

##### 2.1 建索引回查字段核对（B6，缺口 G3）

- [x] 核对 ContentClient.getContent 出参是否够建索引字段（tags/summary/cover 等）+ 审核终态 PASS/DFA 结论获取方式；不足则扩 content 提供方端点（禁事件体塞大字段，保持事件轻量纪律）
  - ✅ 2026-11-04：核对结论——原 ContentDTO 缺 tags/summary/authorName/publishTime/zone/category，不足建索引。新建 `ArticleIndexDocDTO`（articleId/authorId/authorName/title/summary/contentHtml/tags/category/zone/status/visibility/publishTime/likeCnt/commentCnt/viewCnt/contentHash）；`ContentClient` 新增 `GET /api/feign/content/article-index-doc`（fallback 走 fail-fast 抛 BizException）；content 侧 `ContentInternalService#getArticleIndexDoc` 实现（按 del_flag=0 查 Article + 标签批量回查 + 作者昵称 best-effort 回查），Feign 提供方端点同步接入
  - ✅ 2026-11-04：审核终态/DFA 结论不新增 review 回查——content 侧 status=2 本身即「审核 PASS 回写或免审直落且 DFA 放行」的唯一出口，直接用 status=2 作等价门控（结论记入 ContentInternalServiceImpl 类注释）
  - ✅ 2026-11-04：偏差说明——cover 未入索引（EsIndexInitializer mapping 与 ArticleIndexDoc 均无 cover 字段，本期不做；后续如需展示封面再扩 mapping）

##### 2.2 IndexSyncConsumer 文章事件消费

- [x] 消费 `content.published`：事件体仅定位键 → ContentClient 回查元数据 → 乱序防御门控（status=2 且 visibility=PUBLIC 才写，防 unpublished 先到后 published 迟到写回）→ VectorService Embedding 预计算 vec_dense → 按 articleId 幂等 upsert article_idx；同时摄入 article_chunks（入库门控：PUBLIC + 审核终态 PASS + 正文 ≥200 字 + DFA 未命中高风险；切片 ~500 字/段重叠 50 字；按 articleId 幂等覆盖删旧写新）
  - ✅ 2026-11-04：`ArticleIndexServiceImpl#handlePublished`（回查 → status=2 且 visibility=1 门控 → 组装 ArticleIndexDoc → 按 articleId upsert → `ArticleChunkService#rebuild`）；正文经 `SearchTextUtils#toPlainText` 去 HTML 标签后入 content 字段；heat 初始 0（批次三重算）；vec_dense 由 `VectorService#embed`（标题+摘要+正文截断 2000 字），模型未接入返回 null 字段缺省
  - ✅ 2026-11-04：article_chunks 摄入（`ArticleChunkServiceImpl`）：正文门槛 `ingest.min-content-length`(200) + `chunk-size`(500)/`chunk-overlap`(50) 切片，chunkId=articleId_序号，先 deleteByQuery 旧块再 `_bulk` 写入（索引未建时跳过删除；bulk errors 逐条 error 留痕）；审核终态/DFA 以 status=2 等价门控（见 2.1）
  - ✅ 2026-11-04：新建实体 `ArticleIndexDoc`/`ArticleMetaDoc`/`ArticleChunkDoc`（字段名用 `@JsonProperty(SearchBizConstant.FIELD_*)`，`@JsonInclude(NON_NULL)` 保证局部更新与空向量不写脏值）
- [x] 消费 `content.unpublished`：删 article_idx 文档（404 视为成功幂等）+ 删 article_chunks 旧块 + 失效热点查询缓存（四条下架路径全量覆盖，隐私红线）
  - ✅ 2026-11-04：`ArticleIndexServiceImpl#handleUnpublished`——先 `exists` 判断再 delete（不存在按幂等成功跳过，避免 404 误判触发重试）→ `ArticleChunkService#deleteByArticleId`（索引未建时跳过）；四条下架路径统一按删除处理，不按 reason 分流
  - ⏳ 遗留批次四：热点查询缓存尚未落地（本服务当前无查询缓存），缓存失效随批次四缓存实现接入（实现位置已在代码注释标记）（状态详见批次九 9.3：代码已接线，标注待清理）✅ 2026-11-05 已落地：`IndexSyncConsumer` 发布/下架/元数据更新/书籍/用户五类处理器均调用 `HotQueryCacheService#evictAll`（`IndexSyncConsumer.java:112/148` 等），热榜刷写后同样失效（`IndexHeatFlushJob.java:160`），本条 ⏳ 关闭。
- [x] 消费 `content.article-updated`（A2 定案）：轻量回查 → partial update 元数据字段（title/tags/summary/cover），不动向量与 chunks
  - ✅ 2026-11-04：`ArticleIndexServiceImpl#handleArticleUpdated`——回查后二次门控（文章已下架/转私密则转 `handleUnpublished` 清理）→ 组装 `ArticleMetaDoc`（title/tags/summary/zone/category/updateTime，null 字段不序列化）→ `update + doc` 局部更新；索引文档不存在时 warn 跳过（首发消息可能丢失，待重发/重建补齐）
  - ✅ 2026-11-04：偏差说明——cover 不在本批次索引字段（同 2.1）
  - ✅ 2026-11-04（企业优化清单 2.1）：`ArticleIndexServiceImpl` 发布路径与元数据更新路径写入前统一走 `IndexVersionGuard` 版本比较（现有 `updateTime` >= 新快照则跳过；发布路径跳过时同步跳过切片重建；读 ES 失败抛可恢复异常交 MQ 重试），book_idx/user_idx 同口径跟进
- [x] 消费可靠性范式（对齐 review/content 规范）：手动 ACK（MqAckHelper）+ MqIdempotentHelper 幂等 + 业务失败 warn 后 ack 防毒丸 + 异常指数退避延迟重投，耗尽 nack 入 DLQ（SearchMqConfig 已声明拓扑）+ DLQ Consumer 告警留痕
  - ✅ 2026-11-04：新建 `mq/IndexSyncConsumer`（三个 `@RabbitListener`：QUEUE_CONTENT_PUBLISHED/UNPUBLISHED/ARTICLE_UPDATED）——`MqTraceSupport.restore` + `MqAckHelper.executeWithRetry`，bizType≠1 或 bizId 缺失时 warn 后正常返回（ack 丢弃，不浪费重试次数）；异常按指数退避重投到 `litisle.search.delay.exchange`（routingKey=队列名）
  - ✅ 2026-11-04：`SearchMqConfig` 补延迟重投拓扑——`searchDelayExchange`（CustomExchange，x-delayed-message + x-delayed-type=direct）+ 六条延迟绑定（绑定键=队列名）；同时修复既有死信拓扑 bug：六条 DLX→DLQ 绑定键由业务路由键改为队列名，与各队列 `deadLetterRoutingKey(queueName)` 对齐（Direct 精确匹配，原写法死信进不了 DLQ）
  - ✅ 2026-11-04：新建 `mq/IndexDlqConsumer`（监听 QUEUE_SEARCH_INDEX_DLQ）——从 `x-death` 头取原队列、截断消息体（500 字符）后 error 留痕，`executeWithAck` 确认出队；DLQ 不配 DLX 防死信再死信环路
  - ✅ 2026-11-04：参数外置——`SearchProperties.Retry`（max-count=5 / base-delay-ms=30000）+ `Ingest`（min-content-length/chunk-size/chunk-overlap）+ `Embedding.enabled`，yml 同步
  - ✅ 2026-11-04：偏差说明——**未使用 MqIdempotentHelper**：三类动作均以 articleId 为幂等键（upsert / delete / partial update 重复执行结果一致），SETNX 先占位会在「首次消费失败 → 延迟重试」时误判为重复消息而丢弃，故改为天然幂等重试（结论已写入 ArticleIndexService 接口注释）
  - ✅ 2026-11-04：偏差说明——延迟重投次数只走消息头（`MqSendUtil.HEADER_RETRY_COUNT`），事件体本无 retryCount 字段且属契约库不可私改；MqAckHelper 读消息头计数，重试上限逻辑不受影响
