> **归档说明**：新建板块文件（批次七，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次七：相似推荐与 Feign 提供方」节，正文原样搬运（含 ✅ 摘要与偏差说明）。
> **来源**：[next_update.md](../../next/next_update.md) 批次七；Embedding 配置项见该文档 9.4-2。

## 板块八：相似推荐与 Feign 提供方（批次七，2026-11-05 归档）

### search模块

#### 批次七：相似推荐与 Feign 提供方（依赖批次二/三；对齐批次D D5）

> **批次七选型决策（2026-11-04 已确认，照此实现，不再自行改口径）**：
> - **适配器**：OpenAI 兼容 `POST /v1/embeddings` 为主适配器（覆盖 SiliconFlow / 百炼 OpenAI 兼容入口 / 自建网关 / TEI）；Ollama `POST /api/embed` 作为备选适配器，不做主线。
> - **模型**：bge-m3，原生 1024 维，不使用 dimensions 压维，mapping 不改（`vec_dense` / `chunk_vector` 维持 dims=1024）。
> - **运行方式**：开发/小规模用 SiliconFlow bge-m3 API；数据需留内网时 Docker 起 TEI + bge-m3；两者都走 OpenAI 兼容配置切换（`embedding.provider=openai-compatible`，`base-url`/`model`/`api-key` 外置）。
> - **参数默认**：密钥环境变量 `LITISLE_EMBEDDING_API_KEY`；超时 10s；批量 16；重建批量 100（配 bulk）。
> - **触发方式**：管理端点（需 ADMIN/SUPER_ADMIN 鉴权）+ 保留服务方法手动调用；不做启动自动重建；重建完成后把两索引 `_meta.embedding_model` 更新为真实模型名。
> - **文本口径**：`vec_dense` = 标题 + 摘要 + 正文截断 2000 字；`chunk_vector` = `chunk_text` 原文。
> - **融合参数**：权重 0.7 / 0.2 / 0.1；余弦阈值默认 0.4 且可配置（不再默认 0）；topK 上限 50。

##### 7.1 VectorService + KnnRecommender + SimilarController

- [x] `GET /search/similar/{articleId}?topK=`：取源文章 vec_dense + tags → KNN（num_candidates=100、TopK 默认 10、cosine）→ 融合评分（向量相似 + 标签重合度 + 行为热度加权）→ 阈值过滤（`knn.similarity-threshold` 默认 0.0）→ **强制 filter(visibility=PUBLIC)（C3）** + 排除自身与已下架文档
  - ✅ 2026-11-04：`SimilarServiceImpl` 落地——源文章回查（不存在/非 PUBLIC 报 `SEARCH_SOURCE_NOT_FOUND`，无向量返回空列表）→ KNN（`vec_dense`、k=topK+1、num_candidates 取配置且不小于 k）→ 融合评分（`knn.vector-weight` 0.7 / `tag-weight` 0.2 / `heat-weight` 0.1，按总和归一化；标签重合度用 Jaccard，热度用 log1p(x)/(1+x) 归一）→ 原始 cosine 分按 `similarity-threshold` 过滤 → 融合分降序截断 topK（上限 `knn.max-top-k` 50）；filter 强制 `visibility=PUBLIC` + `must_not id=自身`（C3；下架文档已在批次二删除，故无需额外状态过滤）；ES 异常统一 `SEARCH_ES_UNAVAILABLE`
  - ✅ 2026-11-04：新增 `SimilarController`（`GET /api/search/similar/{articleId}?topK=`，网关白名单既有 `/api/search/similar/**` 已放行，无额外网关改动）；`SimilarArticleVO` 复用既有契约
  - ✅ 2026-11-04：Embedding 接入骨架——新增 `embedding/EmbeddingProvider` SPI（providerName/modelName/available/embed/embedBatch，禁止硬编码、仅做本地配置判断）；`SearchProperties.embedding` 扩展 provider/model/dimension/base-url/api-key/timeout/batch-size/rebuild-batch-size（yml 同步，密钥用 `${LITISLE_EMBEDDING_API_KEY:}` 环境变量占位符）；`VectorServiceImpl` 改为按 `embedding.provider` 精确匹配提供方，未接入返回 null（开关关闭不告警、未接入仅告警一次）、调用失败/维度不一致返回 null 不阻断索引写入；`VectorService` 新增 `available()`
  - ✅ 2026-11-04：向量全量重建框架——新增 `VectorRebuildService/Impl`（`rebuildArticleVectors` / `rebuildChunkVectors`）：提供方未就绪整体跳过；`search_after` 按主键升序分页（规避 from+size 深分页窗口）→ 逐条向量化 → bulk partial update 只写 `vec_dense`/`chunk_vector`；文章输入与发布链路共用 `SearchTextUtils#buildEmbeddingInput`（标题+摘要+正文截断 2000 字，避免口径漂移），切片直接用 `chunk_text` 原文；**触发方式（管理端点/一次性任务/开关）待确认，本次仅落框架不含自动触发** ✅ 2026-11-05：触发方式已落地为管理端点 `POST /api/search/admin/rebuild-vectors`（详见下一行），本条待确认关闭。
  - ⚠️ 待确认：Embedding 提供方/模型/服务地址/密钥注入方式未定（清单未给选型），本次不写任何提供方适配器，`embedding.enabled=false`、`provider=none` 时行为与现网一致（不写向量字段）（已由下方选型确认 ✅ 覆盖，状态详见批次九 9.3）✅ 2026-11-05 已落地：选型确认为 openai-compatible 主适配器 + ollama 备选（模型 bge-m3/1024 维，密钥走 `LITISLE_EMBEDDING_API_KEY`），本条 ⚠️ 关闭。
  - ✅ 2026-11-04（选型确认后落地）：OpenAI 兼容主适配器 `OpenAiCompatibleEmbeddingProvider`——`POST {base-url}/v1/embeddings`，批量 16 / 超时 10s / Bearer 密钥（`LITISLE_EMBEDDING_API_KEY`，为空则不发鉴权头）/ 失败返回 null 不阻断索引写入 / 维度由 VectorService 统一校验；模型、地址、密钥、维度全部读 `litisle.search.embedding.*`
  - ✅ 2026-11-04（选型确认后落地）：Ollama 备选适配器 `OllamaEmbeddingProvider`——`POST {base-url}/api/embed`，按 `embedding.provider=ollama` 条件装配（不开主线）；与 OpenAI 适配器共用批量/超时/失败降级骨架
  - ✅ 2026-11-04（选型确认后落地）：新增管理端点 `POST /api/search/admin/rebuild-vectors`（`SaRoleHelper.checkAdmin()` 收口 ADMIN/SUPER_ADMIN，网关未放行需登录态），复用 `VectorRebuildService`；重建成功后把 article_idx + article_chunks 的 `_meta.embedding_model`/`_meta.embedding_dims` 更新为配置中的真实模型名与维度；不做启动自动重建
  - ✅ 2026-11-04（选型确认后落地）：`knn.similarity-threshold` 默认值由 0.0 调整为 0.4（`SearchProperties` 与 yml 同步），其余融合参数维持 0.7/0.2/0.1、topK 上限 50
  - 编译验证已通过（2026-11-04，`mvn -q -pl litisle-search -am compile -DskipTests` exit 0）

##### 7.2 FeignProviderController

- [x] 实现 `/api/feign/search/**`（网关不路由）：`GET /api/feign/search/similar`（对齐已落地 SearchClient，fail-fast）+ `POST /api/feign/search/search`（服务间检索，非降级链路）；供 ai RAG 与前端"相关文章"复用
  - ✅ 2026-11-04：`SearchFeignProviderController` 补 `GET /similar`（参数 `articleId` 必填、`topK` 可选，直接复用 `SimilarService`，与 REST 行为一致）；`POST /search` 已于批次四 4.1 落地，本次仅更新类注释契约清单
  - ✅ 2026-11-04：契约对齐 `litisle-api` 的 `SearchClient#similar/#search`（fail-fast 降级工厂已存在，无需改动 api 模块）
  - 编译验证已通过（2026-11-04，`mvn -q -pl litisle-search -am compile -DskipTests` exit 0）
