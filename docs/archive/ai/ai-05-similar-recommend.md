# 板块五：F8 相似文章推荐（实现记录归档）

> 归档日期：2026-11-16（板块五随板块六/七收口一并归档）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」板块五，条目原样搬运不压缩
> 互链：运行态验证见 [next_verify.md](../../next/next_verify.md) AI-V3（相似推荐与用量出账）；架构登记见 [AI-Architecture.md](../../Architecture/AI-Architecture.md) §2.3/§4.1/§5.6/§7.1

##### 板块五：F8 相似文章推荐（复用板块四 RAG 底座，纯检索形态零生成成本）

> **板块五 5.1/5.2 已于 2026-11-15 全部落地**（实现记录见本板块条目 ✅ 摘要；运行态验证走 [next_verify.md](../../next/next_verify.md) AI-V3；板块归档迁移与架构文档登记随板块六/七批次一并执行，2026-11-16 完成）
> 原「AI-Business-Guide.md 为空文件」事项已消除：该文档已确认完整（897 行，与 git 提交 0106d4a 恢复版一致），§5.3 计量口径已随板块七 7.2 修订为八类（含 SIMILAR）。

###### 5.1 相似文章推荐端点

- [x] POST /ai/xiaoyue/similar（设计锚点，落地前在 Api-Architecture 定稿）：按文章向量在自管索引 article\_chunks\_ai 做 knn 近邻检索，返回相似公开文章卡片列表；**默认不调 LLM，零生成成本**
  - 业务逻辑：ContentClient 取稿 → 标题+摘要（或正文首块）向量化（本地 Ollama bge-m3）→ knnSearch TopK + min-score 阈值过滤 → 排除自身文章（articleId）→ 复用 verifyChunk 同口径逐片回查 → 返回卡片（articleId/标题/作者/相似度）
  - 可见性铁律（与 §四引用回查同双保险口径）：只返回当前已发布且公开的文章——检索命中不代表可推荐，回查失败/非已发布/非公开即移除；contentHash 与摄入时不一致标注「内容已更新」；下架即失效
  - 边界铁律（F8）：只做相似检索，不输出对作者的负面评价性结论；无命中 / rag.enabled=false → 返回空列表明确响应，不报错不降级抛错
  - 计量：purpose 新增 SIMILAR 值（扩 AiPurposeEnum，同步扩预算桶与 D15 修订挂起项核对）；纯检索形态 token=0 仍落一条明细保证可观测；embedding 为本地模型不产生外部费用
  - ✅ 2026-11-15：AiPurposeEnum 扩 SIMILAR（枚举注释同步分账桶口径）+ AiBudgetService/AiBudgetProperties/AiQuotaProperties/AiUserQuotaService 四处桶映射同步；AiRagProperties 增 Similar 配置组（topK/minScore）；RagSearchService.similarArticles（knn 3×topK 切片召回防同篇挤占 → minScore 过滤 + LinkedHashMap 按篇去重取最高分 + 排除自身 → verifyForSimilar 同口径回查改经 getArticleIndexDoc 一次取齐状态/可见性/contentHash/作者昵称，回查失败/非公开移除、hash 不一致标 outdated）；SimilarRecommendService 编排（ObjectProvider 容忍 rag.enabled=false Bean 缺席 → 空列表；取稿失败/检索异常降级空列表并落 FAIL 明细；查询文本=标题+正文首块 500 字截断）；XiaoyueController POST /ai/xiaoyue/similar 返回 Result\<List\<SimilarArticleCard>>；计量：纯检索 token=0 落一条明细（callSeq=1，modelVersion=ollamaModel）；跨板块遗留：网关 /api/ai/\*\* 前缀与端点登记（板块七 7.1，已随 2026-11-16 板块七回收）；AI-Business-Guide.md 曾为空文件，§计量口径「五类」扩充核对受阻（板块七 7.2，已于 2026-11-16 收口）；编译验证已通过（2026-11-15）

###### 5.2 推荐理由生成（可选增强，默认关闭）

- [x] 配置开关 similar.reason-enabled=false：开启时对 TopN 相似文章按需生成一句话推荐理由（结构化输出 + SuggestionValidator 自校验，purpose=SIMILAR 走计量与预算）；默认关闭，避免每次推荐都产生生成成本
  - ✅ 2026-11-15：AiRagProperties.Similar 增 reason-enabled=false/reason-top-n=3/reason-max-chars=60；AiPromptConstants.SIMILAR\_REASON\_PROMPT（主题相关性一句话，禁评价优劣/负面评价——F8 边界铁律入 prompt）；SuggestionValidator.validateReasons（index 范围 1..TopN + 字数上限校验，缺条目不判失败）+ ReasonItem 载体；SimilarRecommendService.fillReasons（预算 EXCEEDED 只跳过理由不报错、记 ai\_quota\_exceeded\_total；结构化回喂重试复用 structuredRetryBudget，耗尽静默返回无理由卡片；成功按 callSeq=2 落 SIMILAR 计量明细 + ai\_call\_total/ai\_call\_latency/ai\_generation\_total 打点）；yml 增 litisle.ai.rag.similar.\* 与 budget/quota similar 桶；编译验证已通过（2026-11-15）
