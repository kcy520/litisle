> **归档说明**：新建板块文件（批次四，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次四：全文检索」节，正文原样搬运（含 ✅ 摘要与偏差说明）。
> **来源**：[next_update.md](../../next/next_update.md) 批次四；验证项见该文档 search V2。

## 板块五：全文检索（批次四，2026-11-05 归档）

### search模块

#### 批次四：全文检索（依赖批次一/二；对齐批次D D3）

##### 4.1 SearchController + SearchService 检索编排

- [x] `GET /search`：参数校验（q 必填、pageNum/pageSize 上限 `page.max-page-size`=50）→ 查询词转义防 ES 注入 → Redis 热点缓存（key 遵循 `search:模块:功能:标识`，TTL 10m）→ type 路由索引 → bool must(multi_match title^4/tags^3/summary^2/content) + filter(visibility=PUBLIC 双保险) + filter(tag/category/zone/时间范围，可选) + function_score(heat)；排序枚举白名单映射（RELATED/NEWEST/HEAT）；返回高亮片段 + 分页；命中列表按 type 拆 articleList/bookList/userList（A6 落地口径）；深分页上限（ES max_result_window，pageNum×pageSize 乘积上限约束）
  - ✅ 2026-11-04：新增 `SearchController`（GET /api/search）+ `SearchFeignProviderController`（POST /api/feign/search/search）+ `SearchService/Impl`；参数校验收口（q 必填/≤64/控制字符拦截、type/sort 枚举白名单、pageSize≤max-page-size、pageNum×pageSize≤新增 `page.max-result-window`=10000）；查询词按架构文档 §八 保留字符转义（结构化 DSL 无 query_string 注入面，转义作纵深防御）；multi_match 权重取 `weight.title/tags/summary`；article 索引 filter(visibility=PUBLIC + tag/category/zone/updateTime 范围)，book/user 按各自 mapping 字段检索；RELATED 用 function_score(log1p(heat), boost_mode=sum) 加权，NEWEST/HEAT 字段降序；三索引各返回高亮首片段；新增读侧投影 `BookIndexDoc`/`UserIndexDoc`（book_idx 未映射 authorId，BookDocVO.authorId 暂为 null）
  - ✅ 2026-11-04：新增 `HotQueryCacheService/Impl`：键 `search:hotquery:{type}:{sort}:{pageNum}:{pageSize}:{MD5(关键词+筛选)}`，TTL 取 `cache.ttl`；读/写异常 fail-open，仅 `source=ES` 结果写缓存；`evictAll()` SCAN+分批 DEL，异常抛 IllegalStateException 交 MQ 重试

##### 4.2 空结果兜底（A1 定案）

- [x] 无命中：查询词实时 Embedding（超时 `fallback.embedding-timeout`=1s）→ KNN 检索 vec_dense 语义相似集，`source=fallback` 标记；Embedding 超时/失败 → 二级兜底 HEAT 热门列表（复用降级链路数据，仍标 fallback）；热门也失败 → 空页（兜底失败不阻塞主链路）；**兜底结果不写热点缓存**
  - ✅ 2026-11-04：`SearchServiceImpl#fallback` 落地三级链路：一级 `embedWithTimeout`（独立守护线程池 + Future 超时，返回维度不符视为失败）→ `knnFallback`（article_idx `vec_dense` KNN + visibility 过滤，固定第一页）；二级 `heatFallback`（article_idx 按 heat 降序，同批次八 A3 热门口径）；三级空页。所有兜底 `source=fallback` 且不写缓存
  - ✅ 2026-11-04：偏差/依赖说明——`VectorService` 当前未接入模型恒返回 null，故线上现阶段实际走二级热门口径；KNN 分支代码已就绪，待批次七向量模型接入即生效；book/user 索引无 vec_dense/heat，空结果直接返回空页；二级热门口径暂查 article_idx，批次八统一降级服务接入后可替换为 content 热门端点

##### 4.3 权限登记

- [x] `/search`、`/search/suggest`、`/search/similar/**` 网关匿名白名单登记 + `/search/history` 需登录（user_id 硬收口，C4 定版）；SEARCH_* 错误码使用（100001~100004 已入 common-core）
  - ✅ 2026-11-04：`litisle-gateway` 路由表已有 `litisle-search`（`Path=/api/search/**`）；白名单由整体 `/api/search/**` 收窄为 `/api/search`、`/api/search/suggest`、`/api/search/similar/**`，`/api/search/history` 故意不登记（需登录，user_id 由登录态取）
  - ✅ 2026-11-04：检索链路使用 `SEARCH_PARAM_INVALID`（参数非法）、`SEARCH_PAGE_EXCEEDED`（单页/深分页超限）、`SEARCH_ES_UNAVAILABLE`（ES 异常）；`SEARCH_SOURCE_NOT_FOUND` 沿用批次二索引回查
