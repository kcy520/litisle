# search 板块十三：文章向量切片池化重建（相似推荐全文覆盖改造）

> 归档自 [next_update.md](../../next/next_update.md)「search 模块 · 板块一：文章向量切片池化重建」（2026-11-17 落地并归档），条目原样搬运不压缩（含口径修正、注意事项与跨板块联动标注）。
> 归档编号说明：本文件对应 next_update.md search 模块「板块一」，编号沿用归档索引页板块序（前序 search-12 对应 next_update 板块二 维度注册表）。
> 运行态验证：V-P1/S-P1 文章向量池化运行态验证在 [next_verify.md](../../next/next_verify.md)（迁移是移动，不在本文件保留）。

> 背景与动机：当前 vec_dense 输入 = 标题+摘要+正文前 2000 字截断（SearchTextUtils.buildEmbeddingInput），长文后半段主题丢失，相似推荐只看文章开头；主流解法为**切片向量池化**（mean pooling / 层级向量父向量），且 search 自管 article_chunks 切片索引（批次C 建，与 article_idx 同一 bge-m3 单模型，批次C C1）已存全量切片向量——池化零新增 Embedding 调用，查询侧零变化。

## 1.1 池化聚合器与配置外置

- [x] 新增纯函数聚合器 ArticleVectorPooler：vec_dense = (w_t·v标题 + w_s·v摘要 + w_c·mean(v全部切片向量)) / Σw，L2 归一化后写 article_idx.vec_dense；写入与重建共用同一聚合器，防口径漂移（对齐 buildEmbeddingInput 单点纪律）
  - 口径：w_t=2 / w_s=2（摘要为空则权重并入 w_t）/ w_c=1（mean 聚合）；池化策略配置开关 litisle.search.embedding.pool.strategy=mean|max-pool（默认 mean；max-pool 逐维取各切片最大值，对多主题长文更尖锐但可能放大噪声，备选不默认）
  - 无切片兜底：正文 < chunkSize 的短文退化为现口径直算（EMBEDDING_INPUT_MAX_CHARS=2000 降级为兜底路径上限，主链路不再依赖）；纯图/无正文回退标题向量（防 null 向量）；article_idx 的 _meta 模型登记不变（池化不换模型，批次C C1 单模型纪律天然满足）
  - ✅ 2026-11-17：新增纯函数聚合器 utils/ArticleVectorPooler（chunkAggregate 切片聚合 mean/max-pool 双策略 + pool 加权池化与 L2 归一化，纯函数零 Spring 依赖；摘要向量缺席 w_s 并入 w_t、标题向量缺席权重归零由切片独扛防 null 向量、全缺/零模长返回 null 跳过写入）+ 编排组件 service/support/ArticleVectorComposer（写入与重建共用单点：切片向量在位走池化、缺席走兜底——无正文回退 embed(title)，其余走 buildEmbeddingInput 直算，2000 上限降级为兜底路径）；池化策略外置 litisle.search.embedding.pool.strategy（SearchProperties.Embedding.Pool，默认 mean，非法值按 mean 兜底）；**无切片判定按「切片向量是否存在」而非正文长度**（写入链路取切片服务产出、重建链路回查 article_chunks，只有切片存在性判定才能保证两端口径一致，200~500 字短文有 1 个切片参与池化）；拼接规则下沉 litisle-api ArticleVectorTextUtils（toPlainText + buildEmbeddingInput + 2000 上限常量，SearchTextUtils 委托转发），ai 侧与 search 侧共用同一份代码防跨服务口径漂移；编译验证已通过（2026-11-17）

## 1.2 重建链路改造 + 全量重建（先行）

- [x] VectorRebuildService.rebuildArticleVectors 改池化口径：存量文章先补齐 article_chunks 切片向量、再池化聚合写 vec_dense（一个任务两步）
  - 实施顺序铁律：先跑 1.2 全量重建收敛存量 → 再切 1.3 写入链路；中间态新发布文章走旧口径（前 2000 字）可接受，重建任务最终收敛
  - ✅ 2026-11-17：rebuildArticleVectors 改一个任务两步——步骤一调 rebuildChunkVectors() 补齐 article_chunks 切片向量（池化前置）；步骤二沿用 search_after 分页扫描公开文章，逐篇新增 fetchChunkVectors 回查该篇切片向量（term article_id 数值精确匹配 + source filter 只取 chunk_vector，size 上限常量 REBUILD_CHUNK_FETCH_SIZE=1000 防超长文溢出）→ ArticleVectorComposer 池化聚合（切片回查失败/缺席单篇退化为兜底直算不中止整轮，重跑收敛）→ bulk 局部更新 vec_dense；Embedding 未就绪整体跳过语义不变；_meta 登记不变（池化不换模型）。**运行态执行顺序铁律提醒：先跑本条全量重建收敛存量、再切 1.3 写入链路**（见 next_verify.md V-P1）；编译验证已通过（2026-11-17）

## 1.3 发布写入链路切换

- [x] ArticleIndexServiceImpl 发布事件建文档时向量改为池化口径：保证该篇切片向量在位（既有切片链路）→ ArticleVectorPooler 聚合 → 写 vec_dense（替换 embedArticle 标题+摘要+前 2000 字直算）
  - 改稿联动：article-updated → 切片更新 → vec_dense 同步刷新，全链路无人工介入；换模型仍走既有「_meta 防漂移 + 全量重建」纪律
  - ✅ 2026-11-17：handlePublished 重排为「buildBaseDoc（向量延后）→ articleChunkService.rebuild 先重建切片并取回本批切片向量（ArticleChunkService#rebuild 返回值 void→List&lt;List&lt;Float&gt;&gt;，切片向量与切片文档同批写入，单片 embed 失败剔除）→ ArticleVectorComposer 池化聚合写 vec_dense → upsert」；buildIndexDoc 拆分出 buildBaseDoc（word_count/ai_labels 在发布链路池化后回填）；embedArticle 旧直算方法删除；改稿联动——handleArticleUpdated 在 partial update 前调 refreshSlicesAndVector：重建切片（article-updated 为元数据编辑不改正文，切片冗余 title/authorName 刷新、chunk_text 不变）+ 按池化口径重算 vec_dense 回填 ArticleMetaDoc（新增 vecDense 字段，NON_NULL 序列化——embed 失败为 null 不覆盖索引原向量），元数据与向量一次 partial update 写入，全链路无人工介入；编译验证已通过（2026-11-17）

## 1.4 相似检索联动核对（查询侧零改动 + AI 查询文本口径对齐）

- [x] 相似推荐查询链路确认零改动并留核对记录：SimilarController / SimilarServiceImpl / SearchClient#similar 契约 / EsIndexInitializer mapping（1024 维 cosine 不变）均不动——池化只改 vec_dense 生产端，查询侧读同一字段
  - 联动改造一处（litisle-ai 侧）：板块五 SimilarRecommendService.buildQueryText 查询文本由「标题+正文前 500 字」对齐为「标题+摘要+正文截断」（经 getArticleIndexDoc 取 summary——ContentDTO 无摘要字段），保证查询向量与池化后文章向量输入口径可比（两边都是"标题+摘要+长正文"，knn 分才有可比性）；对齐后 AI 侧 buildQueryText 与 search 侧 buildEmbeddingInput 的拼接规则保持一致
  - 阈值回归（验证项，见 next_verify.md S-P1 ⑤）：池化改变文章向量分布，knn.similarityThreshold=0.4 与空结果兜底的实时 Embedding 查询需抽样回归，必要时校准配置
  - ✅ 2026-11-17：查询侧零改动核对完成——SimilarController / SimilarServiceImpl / SearchClient#similar 契约 / EsIndexInitializer mapping（vec_dense 1024 维 cosine）均未触碰，池化只改 vec_dense 生产端，查询侧读同一字段；AI 侧口径对齐落地——SimilarRecommendService.fetchArticle 改走 contentClient.getArticleIndexDoc（ArticleIndexDocDTO 带摘要），buildQueryText 改为 ArticleVectorTextUtils.buildEmbeddingInput(title, summary, toPlainText(contentHtml))——与 search 侧向量输入为**同一方法**（共享单点，拼接与 2000 截断规则天然一致），QUERY_BODY_MAX_CHARS=500 常量删除（截断职责由共享工具承担）；阈值回归留 next_verify.md S-P1 ⑤ 运行态执行；编译验证已通过（2026-11-17）
