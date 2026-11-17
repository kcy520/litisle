# ai 板块八：条件化相似推荐——条件提取管道（L0/L1 分层路由）

> 归档日期：2026-11-16（板块八 1.1/1.2/1.3 全部完成并归档；运行态验证走 [next_verify.md](../../next/next_verify.md) AI-V4）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」板块八节，条目原样搬运不压缩（含 P0 拍板口径）
> search 侧配套（维度注册表 / ai_labels 标签槽 / 条件版查询）见 [search-12-ai-labels.md](../search/search-12-ai-labels.md) 与 next_update.md search 模块板块三（活跃）

## 方案总纲（原样搬运）

> 方案：L0/L1 分层（无自由条件直通固定接口 0 次 LLM；带条件 1 次 LLM 结构化提取 → 条件版检索）+ 四类维度 match（filter 词表 / range 数值 / param 参数 / semantic 语义）+ ES ai_labels 动态标签槽（索引期 LLM 打标，查询期零 LLM）+ 维度注册表（配置驱动打标与提取两端，新维度零代码）
> 原则：成本红线——无自由输入绝不调 LLM；查询期基线 1 次 LLM，按「门控升级阶梯」按需递增（单请求 LLM 硬上限 3 次，钱只花在结果不好的时候）；提取失败降级 L0 不报错；打标失败不阻断索引主链路。**P0 三项已于 2026-11-16 拍板定稿（入口形态=复用 /similar 加 freeText；打标 purpose=新增 LABELING 独立桶；初版词表=style 六值/depth 三值/structure 四值/topic 语义），口径已补入条目，开工**

## P0 拍板结果（2026-11-16，随条目归档）

1. 入口形态：复用 POST /ai/xiaoyue/similar 请求体加 freeText 可空字段（空→L0 直通 SearchClient#similar 零 LLM；非空→L1 条件管道），不建独立新端点。
2. 打标 purpose：新增 LABELING 枚举值（索引期管线类，独立预算桶，不开放用户端点；扩 AiPurposeEnum + 四处桶映射，先例见板块五 5.1：AiBudgetService/AiBudgetProperties/AiQuotaProperties/AiUserQuotaService）。
3. 初版词表：style=轻松易懂/严谨专业/故事叙事/实战干货/观点评论/幽默趣味（六值受控）+ depth=入门/进阶/深度 + structure=叙事/论述/教程/清单 + topic（semantic 自由语义）；mood/stance/audience 留位不做；range 绑 word_count/publish_time；param 型 topK/sort/权重。

## 1.1 入口扩展与静态路由

- [x] POST /ai/xiaoyue/similar 请求体增 freeText 可空字段：空 → L0 直通转发 SearchClient#similar(articleId)（0 次 LLM，AI 不落计量明细）；非空 → L1 条件管道；提取失败/超时降级 L0 + 响应标注「未识别自定义条件」，不报错
  - 静态路由本身零成本（freeText 是否为空的 if 判断，不需要 LLM 判意图）
  - ✅ 拍板（2026-11-16）：入口形态=复用 /similar 加 freeText 可空字段，不建独立新端点
  - ✅ 2026-11-16：XiaoyueSimilarRequest 增 freeText；SimilarRecommendService.similar 改 L0/L1 静态路由（freeText 空 → L0 直通 RagSearchService.similarArticles 0 次 LLM；非空 → L1）；提取失败/超时/全字段空降级 L0 并经 XiaoyueSimilarResponse.notice 标注「未识别自定义条件」不报错；出参由 List\<SimilarArticleCard\> 改为 XiaoyueSimilarResponse 包装（cards + conditional + notice）；L1 降级检索计量 callSeq=4，与提取明细 callSeq 1~2 分离防幂等键冲突；跨板块遗留：queryText 口径与 search 板块一 1.4 buildQueryText 对齐改造（search 板块一）；编译验证已通过（2026-11-16）

## 1.2 结构化条件提取（固定管道，不用 Agent 循环）

- [x] 1 次 LLM 调用输出统一 JSON：queryText（主旨+风格合并语义文本，含否定语义如“不要标题党”）/ authorName / excludeAuthorName / category / tags / style / topK / wordCount(min,max) / timeRange（发布时间范围，映射既有 publishTime 字段）/ sortHint（related|newest|heat|similarity-first——similarity-first 表示融合权重置纯向量）；service 侧按维度注册表校验（体裁越枚举、wordCount min>max、timeRange 起止倒置、topK 超上限 → 具体错误回喂重试 1 次，复用板块二 2.4 自校验模式）；全字段空 = 无有效条件 → 降级 L0
  - 铁律：**不用 Agent 循环**——条件提取是单轮任务，5 轮循环是纯成本浪费；提取 prompt 由维度注册表动态拼装（新维度零代码）；输出 schema 与注册表 match 类型一一对应
  - 作者名→ID 解析走 SearchClient 查 user_idx（普通 Feign 查询，非 LLM）
  - 阅读时长换算（“5 分钟能读完的”）：提取层按 ~400 字/分钟换算为 wordCount range（约 2000 字），**不加独立字段**——2026-11-15 实用性×成本筛选结论
  - 多轮调条件（会话式“再轻松一点”）：**2026-11-15 已拉回**，随板块十落地（会话记忆 + 增量改条件），原 temporarily-04 挂起项随之关闭
  - ✅ 2026-11-16：ConditionExtractService 固定管道 1 次 LLM（不用 Agent 循环）；提取 prompt 由 AiLabelDimensionsProperties 注册表动态拼装（维度清单+受控词表+当前日期换算基准；阅读时长 ~400 字/分钟换算 wordCount 区间写入 prompt 不加独立字段）；service 侧校验（越词表/wordCount min>max/timeStart>timeEnd/topK 超 rag.similar.conditionalTopKMax/sortHint 越枚举 → IllegalArgumentException 具体错误回喂重试 1 次，复用 structuredRetryBudget）；semantic 维度（topic）并入 queryText；作者名→ID 经 SearchClient#searchUsers 查 user_idx（非 LLM，优先精确笔名匹配，解析失败丢弃该条件不阻断）；全字段空降级 L0；purpose=SIMILAR 查询期计量（重试轮次 callSeq 1~2），预算超限/重试耗尽上抛由调用方降级 L0；编译验证已通过（2026-11-16）

## 1.3 打标端点（供 search 索引链路调用）

- [x] POST /api/feign/ai/label（内部 Feign，网关不路由）：收文章标题+正文 → 1 次 LLM 按注册表词表输出全部维度标签 JSON（低置信输出 null 不硬造）→ 返回 ai_labels；失败 fail-fast 上抛，重试语义由 search 消费侧承担
  - 计量：走 AI 计量明细落痕
  - ✅ 拍板（2026-11-16）：purpose=新增 LABELING（索引期管线类用途，独立预算桶，不开放给用户端点；扩 AiPurposeEnum + 四处桶映射，随板块五 5.1 先例）
  - 依赖：provider.text.enabled=true（LLM 通道就绪）
  - ✅ 2026-11-16：AiPurposeEnum 新增 LABELING（独立预算桶四处映射：AiBudgetService bucketPurposes/resolveBucket/resolveBudget + AiBudgetProperties.labeling + AiQuotaProperties.labeling + AiUserQuotaService case LABELING）；AiClient#label 契约 + AiClientFallbackFactory fail-fast（降级返回空标签会让索引链路误判「无标签可写」）；AiLabelService（注册表动态拼装打标 prompt，仅 filter 词表型 + semantic 型维度；词表越表校验回喂重试；低置信 null 不硬造；预算超限/重试耗尽 fail-fast 上抛；purpose=LABELING 计量无发起用户，scene=index-labeling）；AiLabelController POST /api/feign/ai/label（/api/feign/** 网关不路由）；编译验证已通过（2026-11-16）

## 依赖与登记说明

- provider.text.enabled=true（LLM 通道就绪）为打标端点运行前置（见 next_verify.md AI-V4）
- 检索条件版契约（SearchClient#similarByCondition / #searchUsers）随本批落地于 litisle-api，实现方为 search 模块（见 [search-12-ai-labels.md](../search/search-12-ai-labels.md) 与 next_update.md search 模块板块三 3.1 条目）
