# search 板块十四：条件版相似查询（SearchClient 契约扩展 + 本地 reranker 精排）

> 归档自 [next_update.md](../../next/next_update.md)「search 模块 · 板块三：条件版相似查询」（3.1 于 2026-11-16 落地、3.2 升级②于 2026-11-17 落地，2026-11-17 归档），条目原样搬运不压缩（含口径修正、注意事项与跨板块联动标注）。
> 归档编号说明：本文件对应 next_update.md search 模块「板块三」，编号沿用归档索引页板块序（search-13 对应板块一 向量池化）。
> 按需升级阶梯预留项去向：升级① 查询改写重检（二期）与升级③ LLM judge（默认关）已登记 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md)「search 模块」节（触发条件随条目）。

## 3.1 条件查询契约与四类 match 构造

- [x] SearchClient#similar 扩展条件版请求（queryText / authorId / excludeAuthorIds / category / tags / style 词表值 / wordCount 区间 / timeRange（publishTime range）/ topK / sort）；SimilarService 增条件版实现，按注册表 match 类型路由：filter 词表型 → term filter（含 ai_labels.style）/ range 数值型 → range filter（word_count、publish_time）/ param 参数型 → 直接映射请求参数（topK 上限截断、sort 与融合权重调整——similarity-first 时 vectorWeight=1 其余归零）/ semantic → queryText embed 后 knn（替代源文章向量作参照）；批次七三路融合评分沿用
  - 铁律：visibility=PUBLIC + 排除自身铁律不变；queryText 非空时查询文本口径与池化专项板块一 1.4 对齐（标题+摘要+正文截断）；无命中返回空列表不报错
  - 2026-11-15 实用性×成本筛选：一期纳入 timeRange（字段已有）与 similarity-first 权重语义（权重外置可调）；has_image / stance / audience / OR 组合 / 关注列表 / 已读去重 / 用户画像**均不做**（详见 temporarily-04-deferred.md ai 模块节暂不做清单）
  - ✅ 2026-11-16：SearchClient#similarByCondition（SimilarConditionRequestDTO：queryText/excludeArticleId/authorId/excludeAuthorIds/category/tags/aiLabels/wordCount 区间/publishTime 区间/topK/sortHint）+ SearchClient#searchUsers（UserSearchVO 作者名解析数据源，板块八 1.2 配套）两契约落地 + FallbackFactory fail-fast；SimilarService#similarByCondition 按注册表路由四类构造：filter 词表型 → term（aiLabels 各键缺省写 ai_labels.{维度名}，声明 esField 写既有字段，注册表外 key 忽略防污染）/ range 数值型 → range（word_count、publish_time→updateTime，解析失败该条件忽略）/ param → topK 截断 + sortHint（related 默认融合 / newest updateTime 降序 / heat 热度降序 / similarity-first 融合权重置纯向量 [1,0,0]）/ semantic → queryText embed 后 knn 作参照向量；queryText 空或 embedding 未就绪退化纯条件过滤检索按 sortHint ES 排序；visibility=PUBLIC + 排除自身强制（filter + mustNot 双保险）；相似度阈值过滤仅向量路径生效；SimilarArticleVO 增 authorId/heat/updateTime 透传（similar 旧链路同步补 authorId）；跨板块遗留：queryText 口径与池化专项板块一 1.4 buildQueryText 对齐改造（search 板块一，已随板块一落地回收）；编译验证已通过（2026-11-16）

## 3.2 按需升级阶梯（门控多次调用，2026-11-15 修订：1 次是基线不是上限）

- [x] 升级②本地 reranker 精排（0 次 LLM，推荐中间档）：bge-reranker / cross-encoder 对 (queryText, 候选摘要) 打分重排，跑在与 Ollama 同款的本地推理上，零 API 费；排序效果显著优于三路融合加权、接近 LLM judge——主流实现优先本地 reranker 而非 LLM 评断，LLM 只留给 reranker 判不了的复杂要求
  - ✅ 2026-11-17：新增 rerank 包 RerankProvider SPI（打分归提供方、排序/降级归服务，与 EmbeddingProvider 同款 SPI 范式）+ OpenAiCompatibleRerankProvider 主适配器（POST /v1/rerank 协议：{model,query,documents} → results[].index/relevance_score（缺失兜底 score 字段）；非 200/网络/解析一律返回 null 整批降级；@ConditionalOnProperty 按 rerank.provider 条件装配）+ SearchRerankConstant 常量收口（RERANK_DOC_MAX_CHARS=512 等）；配置 litisle.search.rerank.*（enabled 默认 false / provider=openai-compatible / model=bge-reranker-v2-m3 / base-url / api-key 环境变量注入 / timeout 10s / top-n 20）进 SearchProperties.Rerank + application.yml；RerankServiceImpl 编排层——触发判定（开关 + queryText 非空 + 提供方就绪）→ 候选「标题+摘要」组装按 512 截断（摘要缺失退化仅标题）→ 逐对打分 → 相关性分降序稳定重排（打分失败项沉底保持基线相对顺序）→ score 更新为相关性分 → 截断 topK；SimilarServiceImpl#similarByCondition 接线——rerankApplicable = queryText 非空 + enabled + sortHint ∉ {newest,heat}（用户显式排序意图不重排），fetchSize = min(max(topK, rerank.top-n), knn.max-top-k) 多取候选，重排后截回 topK；护栏铁律全链路 fail-open：未启用/queryText 空/提供方未就绪/整批打分失败/数量不符一律退回三路融合基线排序截断 topK，结果不劣化；0 次 LLM；边界决策（原待讨论项已拍板）：升级①查询改写重检留二期（触发条件：基线命中率实测不达标），升级③ LLM judge 维持默认关不实现（触发条件：出现 reranker 判不了的复杂要求场景）；编译验证已通过（2026-11-17）

### 升级阶梯预留项（拍板记录，正文已迁 temporarily-04-deferred.md「search 模块」节）

- [ ] 升级①查询改写重检（+1 次 LLM，触发式）：有效命中 < 3 条 或 最高分 < 阈值时，LLM 改写 queryText 再检索一轮（每请求至多 1 次改写；改写仍无命中 → 返回空列表不报错）——CRAG 式按检索质量补救，钱花在结果不好的时候
  - 拍板（2026-11-17）：留二期，触发条件：基线命中率实测不达标
- [ ] 升级③LLM judge 精排（+N 次 LLM，配置开关默认关）：对 TopN≤5 逐篇评断「符合用户要求吗」（跨维度主观权衡），每查询成本随候选线性涨，仅作最准档
  - 拍板（2026-11-17）：维持默认关不实现，触发条件：出现 reranker 判不了的复杂要求场景
  - 护栏铁律：每次升级必须有明确触发条件 + 配置开关 + 降级路径（升级失败退回上一级结果，结果不劣化），无任何「无条件多调」；注册表外长尾表达仍由 3.1 semantic 路径语义兜底
