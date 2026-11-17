## 现状基线（2026-11-17 按代码核对，板块零~十二、十三 5.3、十五落地后形态；活跃余板块十三 5.1/5.2 与板块十四）
litisle-ai 为独立微服务模块：web/feign/mybatis/redis/mq/es/langchain4j/micrometer 全量接入；application.yml 承载模型/RAG/预算/熔断/会话治理/会话结果集/写作辅助配置（外置可调）
包结构：agent（XiaoyueAgent · MeteringAgentChatModel · AgentCallContext · tools 十三工具）/ config / constant / controller / domain / dto / enums / mapper / memory / metrics / mq / provider（text+embedding）/ rag / service / task

## 已实现能力（板块零~十五活跃余项外全部完成，实现记录见 archive/ai/ai-implemention-records.md 索引页）
板块零：启动骨架 + 依赖/端口/yml 接入（空业务）
板块一：模型通道与计量配额基建（OpenAI 兼容 provider · 脱敏/熔断装饰链 · AiPurposeEnum 契约 · 三表计量 + 聚合任务 · /api/feign/ai/chat 机审端点 · ai_call_* 指标）
板块二：小悦 Agent 底座（AiServices 装配 + Function Calling + 意图路由三级降级 + 结构化自校验回喂重试 + 摘要/标签建议）
板块三：会话与润色/结构分析（ai_session/ai_message 治理 D25 · RedisChatMemoryStore · /polish · /analyze-structure）
板块四：RAG 知识库与站内问答（article_chunks_ai 自管索引 · RagIngestConsumer 消费发布/下架 · 引用回查 Q75 · 匿名配额）
板块五：F8 相似文章推荐（similarArticles knn + 可见性双保险回查 + 可选推荐理由）
板块六：用量可观测与模型自检（/ai/usage/me·me/details·summary 三端点 · CircuitBreakerChatModel 熔断 + ai_guard_state）
板块七：文档收尾与登记（AI-Architecture 状态同步 · 网关 /api/ai/** 登记 · §5.3 八类计量口径）
板块八：条件化相似推荐（/similar freeText L0/L1 路由 · ConditionExtractService 1 次 LLM 提取 · LABELING 打标端点 · SearchClient 条件版查询配套）
板块九：Agent 交互扩展（SessionResultRegistryService 会话结果集序号映射 · XiaoyueTools 八工具 · 收藏动作 ContentClient#favorite 契约落地 · 读后摘要/追问/大纲/术语/陪聊/金句，purpose 复用 SUMMARY/QA/STRUCTURE 桶）
板块十：多轮调条件（SessionConditionStoreService 上一轮条件 JSON 暂存 · ConditionExtractService 增量提取重载——合并基线入 prompt 输出完整条件 JSON，每轮仍 1 次 LLM，复用板块八管道）
板块十一：多文对比与收藏夹语义检索（XiaoyueTools 扩至十工具：工具⑨序号定位 ≤3 篇对比素材组装 + 工具⑩收藏白名单语义检索 · ContentClient#listFavoriteArticleIds 契约落地 · search 板块四 4.1 include/excludeArticleIds 白名单 filter 同批配套）
板块十二：检索编排与创作辅助扩展（XiaoyueTools 扩至十三工具：工具⑪找系列/工具⑫热榜上下文/工具⑬风格仿写素材 · POST /ai/xiaoyue/duplicate-check 查重端点（外置阈值 0.85 + explain 1 次 LLM）· 读者预设问题/选题灵感经 chat scene 路由（purpose=QA）· SearchClient#getArticleAiLabels 契约配套 · 4.5 并入 3.1、4.7 并入板块十五承载）
板块十三：能力发现——5.3 边界响应模板已落地（XIAOYUE_BOUNDARY_RESPONSE_RULES 规则段 + renderXiaoyueSystemPrompt 仅意图路由轮注入 + boundary-refusal-template yml 外置「这个我还做不到，但我可以：{capabilities}」，零 LLM 成本）；5.1 冷启动快捷指令 / 5.2 能力清单端点 ⏳ 恢复待开发
板块十四：读前预览+组合编排 ⏳ 未开发（6.3~6.5 依赖契约核对前置）
板块十五：写作辅助扩展（POST /ai/xiaoyue/rewrite 四粒度改写建议 + POST /ai/xiaoyue/draft 草稿，WritingAssistService 编排：Agent 优先 → SuggestionValidator 按粒度校验回喂 → 降级直连三级链；articleId 归属校验非本人拒绝；**AI 不落库**——采用走 content 正常创建流程含 DFA+机审；purpose=POLISH 复用写作辅助桶；板块十二 4.7 由 15.1 TITLE 粒度统一承载）
search 侧配套（2026-11-17）：条件版查询本地 reranker 精排（RerankProvider SPI + RerankServiceImpl，litisle.search.rerank.* 默认关，0 次 LLM fail-open）+ 文章向量切片池化（ArticleVectorPooler/Composer，写入/重建/改稿全链路，vec_dense 生产口径升级）——AI 调用方无感

## 已实现契约面（代码在 litisle-api，不在 litisle-ai 模块内）
AiClient（feign）：POST /api/feign/ai/chat（提供方端点已实现，板块一）
AiClient#label：POST /api/feign/ai/label（板块八打标契约，提供方已实现）
ContentClient：get / article-images / article-ids-by-file / counters/update / article-index-doc / book-index-doc / tag-suggest / hot-articles / favorite（板块九收藏契约）/ favorite/article-ids（板块十一收藏列表契约，均含 content 提供方端点）
SearchClient：similar / search / similarByCondition（板块八条件版；板块十一扩展 includeArticleIds 白名单 + excludeArticleIds 黑名单 filter；queryText 相关性类排序叠加本地 reranker 精排）/ users（作者名解析）/ article-labels（板块十二 AI 标签回查契约）
网关：/api/ai/** 业务前缀已登记（Gateway-Architecture §2.1）；/api/feign/** 网关不路由仅服务间可用

## 设计态锚点（仅设计口径，未在代码中出现前不得引用为既有实现）
XiaoshenController / 图片判定 provider/image/：未建（agent_type 字段已预留，图片判定随立项补建）
数据表：ai_session / ai_message / ai_usage_daily / ai_token_usage / ai_pricing（DDL 以 Sql-Document §五 为准）
指标：ai_call_total / ai_call_latency / ai_tokens_total / ai_quota_exceeded_total / ai_generation_total / ai_guard_state（AI-Architecture §11.1，均 ✅）
固定评测集与反馈采集：设计态（AI-Architecture §十四；反馈采集 ⬜ 未开始）

## 文档指针
业务口径：[AI-Business-Guide.md](../Study/AI-Business-Guide.md)（F1–F22、流程、D1–D25 决策台账 §十六、V1–V12 参数定档 §16.3）
架构口径：[AI-Architecture.md](../Architecture/AI-Architecture.md)（现状 + 设计态，板块零~十五状态已同步）
实现记录归档：[ai-implemention-records.md](../archive/ai/ai-implemention-records.md)（板块零~十五拆分文件索引）
活跃待办：[next_update.md](../next/next_update.md)（板块十三 5.1/5.2、板块十四）；运行态验证：[next_verify.md](../next/next_verify.md)（AI-V1~V8）；挂起项：[temporarily-01-code.md](../next/temporarily/temporarily-01-code.md)「litisle-ai 模块」节
快速上手：[Now_Ai-Business-Guide_Temp.md](../temp/Now_Ai-Business-Guide_Temp.md)（2026-11-17 实现态速览，与正式版口径一致）
