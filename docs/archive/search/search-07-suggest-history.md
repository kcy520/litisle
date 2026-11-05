> **归档说明**：新建板块文件（批次六，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次六：搜索联想与搜索历史」节，正文原样搬运（含 ✅ 摘要）。
> **来源**：[next_update.md](../../next/next_update.md) 批次六；联想挂起项（标签入 ES completion）见 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md)。

## 板块七：搜索联想与搜索历史（批次六，2026-11-05 归档）

### search模块

#### 批次六：搜索联想与搜索历史（依赖批次一/四；对齐批次D D4）

##### 6.1 suggest 联想接口

- [x] `GET /search/suggest?q=`：article_idx `title.completion` / `authorName.completion` suggester + 聚合 content `GET /tags?keyword=` 标签联想 → 去重合并返回 `SuggestVO{type(KEYWORD/TAG/AUTHOR), text}`；**不建标签索引**（标签池 SSOT 在 content，防双写漂移）；开关/条数走 `litisle.search.suggest.*`
  - ✅ 2026-11-04：新增 `SuggestService/Impl` + `GET /api/search/suggest?q=&size=`（联想端点并入 `SearchController`，未单建 Controller）。实现要点：completion suggester 复用批次一已建的 `title.completion`/`authorName.completion`（**不改 mapping、不建标签索引**）；内容依次取书名→作者→标签，按文本去重、总数封顶；`suggest.enabled` 总开关关闭返回空，词空/超 64 返回空；ES 故障只丢书名/作者联想，content 故障只丢标签联想（降级互不影响）
  - ✅ 2026-11-04：契约层新增 `SuggestVO`、`TagSuggestDTO`；`ContentClient#getTagSuggestions`（降级返回空列表，fail-open）+ content 侧 `ContentInternalService#searchTagSuggestions`（复用 `TagService#searchByPrefix`）+ `/api/feign/content/tag-suggest` 内部端点
  - ✅ 2026-11-04（企业优化清单 1.1/1.2/1.3）：联想最小输入长度 2 字（`SUGGEST_MIN_KEYWORD_LENGTH`）；`/search/suggest` 补 IP 维度限流 20 次/60s（`RateLimiter.KeyType.IP`）；content `TagServiceImpl#searchByPrefix` 增加 Redis 短缓存（非空 TTL 480s ±10% 抖动、空结果 60s 防穿透），详见 temp.md 批次一

##### 6.2 SearchHistoryService

- [x] `GET /search/history` 分页 / `DELETE /search/history` 清空；搜索成功后异步记录 → search_history UPSERT（uk(user_id,keyword) 同词仅前进 updated_at）→ 超 `history.max-per-user`（默认 20）懒删最旧；**隐私纪律：keyword 不写入任何 ES 索引文档/向量库**
  - ✅ 2026-11-04：新增 `SearchHistory` 实体 + `SearchHistoryMapper` + `SearchHistoryService/Impl` + `SearchHistoryController`（`/api/search/history` GET/DELETE，均按 `StpUtil` 当前登录用户 ID 收口，无 userId 入参防越权）
  - ✅ 2026-11-04：记录链路——检索成功后（含缓存命中/兜底结果）在 `SearchServiceImpl#recordHistory` 仅对登录用户调用 `recordAsync`（`@Async` + 内部吞异常，失败不影响检索，启动类补 `@EnableAsync`）；同词 UPSERT 命中仅前进 `updated_at`，唯一键并发冲突退化为更新；超 `max-per-user` 用 `Page` 保留最新 N 条并删除其余（**未使用 `QueryWrapper#last()`**）；分页统一 `Page` 对象
  - ✅ 2026-11-04：隐私纪律已落实——关键词只落 MySQL `search_history`，不写任何 ES 索引文档/向量库
