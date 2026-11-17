# search 模块实现记录（归档索引）

> 本目录为 litisle-search 模块实现记录归档（2026-11-03 新建，批次9 迁自 docs/temp/temp.md），按板块拆分维护，本页保留为索引，正文一律在板块文件中维护。
> **归档日期**：2026-11-03（板块一：批次A 重大设计缺陷修正 · A7 互动热度 ES 写入频率优化，文档层定版）；2026-11-17 追加板块十三/十四归档（文章向量切片池化重建 / 条件版相似查询含本地 reranker 精排，迁自 next_update.md search 板块一/三）
> **来源**：迁移自 docs/temp/temp.md（临时任务清单，2026-11-03 search 设计审查立项），条目原样搬运不压缩
> **互链**：活跃待办/验证清单见 [next_update.md](../../next/next_update.md)；挂起项见 [Temporarily_not_implemented.md](../../next/temporarily/Temporarily_not_implemented.md)；search 本体落地批次（批次D）仍活跃于 temp.md 待办部分

## 板块索引

| 板块 | 主题 | 文件 |
| --- | --- | --- |
| 板块一 | 批次A · A7 互动热度 ES 写入频率优化（Redis 累加 + 定时 _bulk，写放大治理定版；代码随批次D D2 落地） | [search-01-heat-write-path.md](search-01-heat-write-path.md) |
| 板块二 | 批次一 · 基础骨架收尾（ES mapping / search_history DDL / Feign 启用，2026-11-05 归档） | [search-02-foundation.md](search-02-foundation.md) |
| 板块三 | 批次二 · 文章索引同步消费端（回查字段核对 + published/unpublished/article-updated 消费 + 可靠性范式） | [search-03-article-index-sync.md](search-03-article-index-sync.md) |
| 板块四 | 批次三 · 互动热度链路（social 事件累加待刷新键 + IndexHeatFlushJob 定时刷写） | [search-04-heat-interaction.md](search-04-heat-interaction.md) |
| 板块五 | 批次四 · 全文检索（SearchController/SearchService + 空结果兜底 + 权限登记） | [search-05-fulltext-search.md](search-05-fulltext-search.md) |
| 板块六 | 批次五 · 书籍与用户索引链路（B4 书籍事件 + book_idx + user_idx 同步） | [search-06-book-user-index.md](search-06-book-user-index.md) |
| 板块七 | 批次六 · 搜索联想与搜索历史（suggest completion + SearchHistoryService） | [search-07-suggest-history.md](search-07-suggest-history.md) |
| 板块八 | 批次七 · 相似推荐与 Feign 提供方（VectorService + KnnRecommender + FeignProviderController） | [search-08-similar-feign.md](search-08-similar-feign.md) |
| 板块九 | 批次八 · 降级链路与观测（ES 降级 + 指标埋点 + DLQ 告警） | [search-09-degrade-observability.md](search-09-degrade-observability.md) |
| 板块十 | 批次九收尾已完成部分（9.2 a/b、9.3、9.4-3、9.7、9.8 P0、9.9 A~D 与 F，2026-11-05 归档） | [search-10-batch09-closing.md](search-10-batch09-closing.md) |
| 板块十一 | 临时清单批次一~三（1.1/1.2/1.3/2.1/3.1，2026-11-05 归档自 temp.md） | [search-11-temp-checklist.md](search-11-temp-checklist.md) |
| 板块十二 | 维度注册表与 ai_labels 标签槽（2026-11-16 归档自 next_update.md search 板块二；含板块三 3.1 条件版查询配套摘要） | [search-12-ai-labels.md](search-12-ai-labels.md) |
| 板块十三 | 文章向量切片池化重建（相似推荐全文覆盖改造，2026-11-17 归档自 next_update.md search 板块一；写入/重建/改稿全链路池化 + AI 查询文本口径对齐） | [search-13-vector-pooling.md](search-13-vector-pooling.md) |
| 板块十四 | 条件版相似查询（SearchClient#similarByCondition 四类 match 路由 + 3.2 升级②本地 reranker 精排，2026-11-17 归档自 next_update.md search 板块三；升级①/③ 预留项迁 temporarily-04-deferred.md） | [search-14-conditional-query.md](search-14-conditional-query.md) |
