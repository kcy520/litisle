# search 模块板块一：文章向量切片池化（1.1/1.2/1.3/1.4）

## Context

next_update.md search 板块一要求解决「vec_dense 只看文章开头（标题+摘要+正文前 2000 字）」的长文主题丢失问题：article_chunks 已存全量切片向量（bge-m3 单模型），池化聚合零新增 Embedding 调用、查询侧零变化。

- **1.1** 新增纯函数聚合器 ArticleVectorPooler + 池化策略配置外置
- **1.2** VectorRebuildService.rebuildArticleVectors 改池化口径（先补齐切片向量、再池化，一个任务两步）
- **1.3** 发布写入链路切换池化口径 + article-updated 改稿联动刷新
- **1.4** 查询侧零改动核对 + AI 侧 buildQueryText 口径对齐（标题+摘要+正文截断）

实施顺序铁律（1.2 先行收敛存量 → 1.3 后切）由运维按 next_verify.md V-P1 执行；本次代码两条链路同批实现。

## 关键现状（已扫描确认）

- 拼接单点：`litisle-search/.../utils/SearchTextUtils#buildEmbeddingInput`（title\n+summary\n+正文≤2000 截断），引用面仅 litisle-search 内部
- 切片链路：`ArticleChunkServiceImpl#rebuild(doc, plainText)` 返回 void，`buildChunkDoc` 内逐片 `vectorService.embed(chunkText)`；chunk 索引 mapping 的 article_id 为 **long** 类型
- 写入链路：`ArticleIndexServiceImpl#handlePublished` 先 upsert 再 `articleChunkService.rebuild`；`embedArticle` 直算旧口径
- 改稿链路：`handleArticleUpdated` 只 partial update 元数据，**不更新 vec_dense/切片**（ContentArticleUpdatedEvent = 元数据编辑，不改正文）
- 重建链路：`VectorRebuildServiceImpl#rebuildArticleVectors` 逐条 buildEmbeddingInput 直算
- AI 侧：`SimilarRecommendService#fetchArticle` 用 `contentClient.getContent`（ContentDTO.body 即 contentHtml，无摘要字段）；buildQueryText = 标题+正文前 500 字（QUERY_BODY_MAX_CHARS=500）；litisle-ai 无 HTML 转纯文本工具
- 向量服务：`VectorServiceImpl#embed` 失败一律返回 null；`available()` 判就绪

## 设计决策

1. **拼接规则跨服务单点下沉 litisle-api**：新增 `com.litisle.litisleapi.util.ArticleVectorTextUtils`（持有 toPlainText + buildEmbeddingInput + EMBEDDING_INPUT_MAX_CHARS），SearchTextUtils 两方法委托之——AI 侧与 search 侧调用同一份代码，「拼接规则保持一致」由单点代码保证而非两处注释约定（对齐项目单点纪律）。
2. **无切片判定按「切片向量是否存在」而非正文长度**：写入链路（切片服务产出）与重建链路（ES 回查）只有以切片存在性为判定才能保证两端口径一致（200~500 字短文有 1 个切片，若按文本长度判定会出现写重建口径分叉）；「纯图/无正文」在兜底分支内单独回退标题向量。
3. **权重口径照清单字面实现**：w_t=2 / w_s=2（摘要向量缺失则 w_s 并入 w_t）/ w_c=1；标题向量 embed 失败时标题权重归零、由切片向量独扛（防 null 向量），全部缺失返回 null。

## 变更清单

### A. litisle-api（共享工具下沉）

**A1. 新增 `litisle-api/src/main/java/com/litisle/litisleapi/util/ArticleVectorTextUtils.java`**
- `EMBEDDING_INPUT_MAX_CHARS = 2000`、`toPlainText(html)`（逻辑自 SearchTextUtils 原样搬迁：IMG 占位/块级标签/任意标签/实体/空白折叠）、`buildEmbeddingInput(title, summary, plainText)`（三段 \n 拼接 + 2000 截断）
- 类级注释按三层注释规范：【职责边界】说明 search/AI 两服务共用

### B. litisle-search

**B2. `constant/SearchEmbeddingConstant.java`**：新增 `POOL_STRATEGY_MEAN="mean"`、`POOL_STRATEGY_MAX_POOL="max-pool"`；EMBEDDING_INPUT_MAX_CHARS 改为引用 ArticleVectorTextUtils 常量的别名（原值与注释保留）

**B3. `config/SearchProperties.java`**：Embedding 嵌套类新增 `Pool pool = new Pool()`（`strategy` 默认 mean，取值引用常量），注释标注 yml key `litisle.search.embedding.pool.strategy`

**B4. 新增 `utils/ArticleVectorPooler.java`（纯函数聚合器，1.1 核心）**
- 常量 `WEIGHT_TITLE=2.0 / WEIGHT_SUMMARY=2.0 / WEIGHT_CHUNK=1.0`
- `chunkAggregate(List<List<Float>> chunkVectors, String strategy)`：mean=逐维平均；max-pool=逐维取最大；空列表返回 null
- `pool(titleVec, summaryVec, chunkAggVec)`：加权求和（摘要缺失权重并入标题；标题缺失权重归零）→ L2 归一化 → 返回；输入全空/模长 0 返回 null；以首个非空向量维度为基准，维度不符分量跳过（防御，正常路径同模型恒一致）
- 三层注释：类级【职责边界】【池化口径】【失败语义】

**B5. 新增 `service/support/ArticleVectorComposer.java`（编排组件，写入与重建共用单点）**
- 依赖 VectorService + SearchProperties
- `compose(title, summary, plainText, chunkVectors)`：
  1. chunkVectors 空 → 兜底：plainText 空白 → `embed(title)`（纯图/无正文回退标题向量）；否则 `embed(buildEmbeddingInput(...))`（2000 上限降级为兜底路径）
  2. 否则 titleVec/summaryVec 分别 embed → `ArticleVectorPooler.chunkAggregate(池化策略取配置)` → `pool(...)` 返回
- 三层注释：【职责边界】本类做编排与兜底决策，聚合数学在 ArticleVectorPooler

**B6. `service/ArticleChunkService.java` + `impl/ArticleChunkServiceImpl.java`**
- `rebuild` 返回值 void → `List<List<Float>>`（切片向量列表，按切片顺序过滤 null；未入库/Embedding 未产出返回空列表）；接口 javadoc 同步

**B7. `service/impl/ArticleIndexServiceImpl.java`（1.3 写入链路切换）**
- `buildIndexDoc` 拆出 `buildBaseDoc(dto)`（不含 vecDense），buildIndexDoc = buildBaseDoc + wordCount + aiLabels
- `handlePublished` 重排：buildBaseDoc → `articleChunkService.rebuild`（先保切片向量在位，取回切片向量）→ `articleVectorComposer.compose(...)` 写 vecDense → upsert
- `handleArticleUpdated` 改稿联动：partial update 后调 `articleChunkService.rebuild`（切片冗余 title/authorName 刷新）→ compose 新 vec_dense → 随 metaDoc partial update 刷新 vec_dense
- 注入 ArticleVectorComposer；embedArticle 旧方法删除（其调用点全部切换）

**B8. `domain/entity/ArticleMetaDoc.java`**：新增 `vecDense` 字段（@JsonProperty(FIELD_VEC_DENSE)，NON_NULL 已有——embed 失败 null 不序列化即保持原值）

**B9. `service/impl/VectorRebuildServiceImpl.java`（1.2 重建两步）**
- `rebuildArticleVectors`：步骤1 调 `rebuildChunkVectors()` 补齐切片向量（一个任务两步，日志记录两步条数）；步骤2 沿用既有 search_after 扫描循环，逐篇改为「fetchChunkVectors 回查切片向量 → articleVectorComposer.compose → updateVectorOperation 写 vec_dense」
- 新增私有 `fetchChunkVectors(articleId)`：term article_id（long 数值）查 chunk 索引，source filter 只取 chunk_vector，size 上限私有常量 `REBUILD_CHUNK_FETCH_SIZE = 1000`（覆盖超长文切片数）
- 类头注释【口径】更新为池化口径说明（保留原注释结构，只改过期描述）
- 注入 ArticleVectorComposer

**B10. `utils/SearchTextUtils.java`**：toPlainText / buildEmbeddingInput 委托 ArticleVectorTextUtils（公共 API 不变，javadoc 注明单点已下沉）；splitIntoChunks 不动

**B11. `application.yml`**：embedding 段新增 `pool.strategy: mean`（注释说明 mean 默认 / max-pool 备选语义）

### C. litisle-ai（1.4 查询文本口径对齐）

**C12. `service/SimilarRecommendService.java`**
- `fetchArticle` 改 `contentClient.getArticleIndexDoc(articleId)` 返回 `ArticleIndexDocDTO`（title/summary/contentHtml 齐备）
- `buildQueryText` 改调 `ArticleVectorTextUtils.buildEmbeddingInput(title, summary, toPlainText(contentHtml))`——与 search 侧向量输入拼接规则同一份代码
- `QUERY_BODY_MAX_CHARS=500` 常量删除（截断职责由共享工具 2000 上限承担，常量对应逻辑已删）
- `similarL0` 中 sourceTitle 取 `article.getTitle()`；清理不再使用的 import（ContentDTO/ModerationConstant 以实际引用为准）

### D. 文档回填

**D13. `docs/next/next_update.md`**：1.1/1.2/1.3/1.4 勾选并按既有格式写 ✅ 落地摘要（含：无切片判定按切片存在性的理由、权重并入口径、article-updated 联动实现、查询侧零改动核对记录——SimilarController/SimilarServiceImpl/SearchClient#similar/EsIndexInitializer mapping 1024 维均未动）

## 明确不做（范围外）

- SimilarController / SimilarServiceImpl / SearchClient#similar 契约 / ES mapping（1.4 核对项，零改动）
- 运行态全量重建执行与阈值回归（next_verify.md V-P1 / S-P1 ⑤）
- 3.2 升级阶梯、板块二/四（已完成归档）

## 验证

1. 编译：`cd E:\NewJavaPro\LitIsle` 后 `.\mvnw.cmd -q -pl litisle-api,litisle-search,litisle-ai -am compile`（api 为共享模块，三模块同批编译）
2. 自查要点：
   - 写入/重建两条链路均只经 ArticleVectorComposer.compose 单点
   - chunkVectors 为空时兜底路径输出与旧口径一致（buildEmbeddingInput）
   - ArticleMetaDoc NON_NULL 保证 embed 失败不覆盖既有 vec_dense
   - AI 侧 buildQueryText 与 search 侧 buildEmbeddingInput 为同一方法
