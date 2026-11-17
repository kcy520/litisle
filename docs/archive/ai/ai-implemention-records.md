# ai 模块实现记录（归档索引）

> 本目录为 litisle-ai（AI）模块实现记录归档（2026-11-15 新建），按板块拆分维护，本页保留为索引，正文一律在板块文件中维护。
> **归档日期**：2026-11-15（板块零~四全部完成，随板块四收口一并归档）；2026-11-16 追加板块五/六/七归档（随板块七文档收口一并归档）；2026-11-16 追加板块八归档（条件化相似推荐，与 search 模块板块二/三 3.1 同批落地）；2026-11-16 追加板块九归档（Agent 交互扩展，书单动作与金句落库契约缺失部分挂起 temporarily-01-code.md）；2026-11-16 追加板块十归档（多轮调条件，板块八管道的会话增量扩展）；2026-11-16 追加板块十一归档（多文对比与收藏夹语义检索，search 板块四 4.1 白名单 filter 同批配套落地）；2026-11-16 追加板块十二归档（检索编排与创作辅助扩展，4.6 配套补 SearchClient#getArticleAiLabels 契约，4.7 并入板块十五承载）；2026-11-17 追加板块十五归档（写作辅助扩展——多级改写建议与文章草稿）
> **来源**：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」节，条目原样搬运不压缩（含口径修正、跨板块遗留标注）
> **口径说明**：`AI-Business-Guide.md` 曾被清空，已于 2026-11-15 从 git 提交 0106d4a（871 行完整原版）恢复，并新增「§4.6 生成式流程（小悦 Agent）」「§5.3 计量幂等键修订」「§十七 Agent 化定档（P1–P10）」；D 决策口径恢复以业务指南为唯一来源
> **范围说明**：本清单聚焦面向用户文章的小悦（Xiaoyue）生成式 Agent（简历导向优先）；图片判定端点、小审判定辅助、反馈采集、评测集不在清单范围
> **互链**：运行态验证清单见 [next_verify.md](../../next/next_verify.md)（AI-V1 RAG 端到端 / AI-V2 会话与计量）；挂起项见 [Temporarily_not_implemented.md](../../next/temporarily/Temporarily_not_implemented.md)；活跃待办见 [next_update.md](../../next/next_update.md)

## 板块索引

| 板块 | 主题 | 文件 |
| --- | --- | --- |
| 板块零 | 模块基础骨架接入（依赖/端口/yml+Nacos，只立骨架不写业务） | [ai-00-skeleton.md](ai-00-skeleton.md) |
| 板块一 | 模型通道与计量配额基建（provider 层 / purpose 契约 / 三表计量 / Feign 机审端点 / 指标） | [ai-01-model-channel-metrology.md](ai-01-model-channel-metrology.md) |
| 板块二 | 小悦 Agent 底座（AiServices 装配 + 工具注册 + 意图路由降级 + 结构化自校验 + 摘要标签） | [ai-02-agent-base.md](ai-02-agent-base.md) |
| 板块三 | 小悦有状态 Agent（会话实体/治理 + Redis ChatMemoryStore + 润色/结构分析） | [ai-03-session-polish-structure.md](ai-03-session-polish-structure.md) |
| 板块四 | RAG 知识库与站内问答（摄入链路 + 检索转正/引用回查/降级/匿名配额） | [ai-04-rag-qa.md](ai-04-rag-qa.md) |
| 板块五 | F8 相似文章推荐（similarArticles knn 检索 + 可见性双保险回查 + 可选推荐理由） | [ai-05-similar-recommend.md](ai-05-similar-recommend.md) |
| 板块六 | 用量可观测与模型自检（用量查询出账三端点 + CircuitBreakerChatModel 熔断自检 + ai_guard_state） | [ai-06-usage-observability.md](ai-06-usage-observability.md) |
| 板块七 | 文档收尾与登记（AI-Architecture 状态同步 + AI-Business-Guide §5.3 八类口径 + Api/Gateway-Architecture 网关前缀登记） | [ai-07-docs-registration.md](ai-07-docs-registration.md) |
| 板块八 | 条件化相似推荐——条件提取管道（freeText 入口 L0/L1 静态路由 + 1 次 LLM 结构化提取 + LABELING 打标端点） | [ai-08-conditional-similar.md](ai-08-conditional-similar.md) |
| 板块九 | Agent 交互扩展——会话结果集地基 + 结果动作 + 读后服务（序号映射注册 + 收藏动作工具 + 读后摘要/追问/大纲/术语/陪聊/金句） | [ai-09-agent-interaction.md](ai-09-agent-interaction.md) |
| 板块十 | 多轮调条件——会话记忆增量改条件（上一轮条件 JSON 暂存 + 增量提取 prompt 合并基线，复用板块八管道） | [ai-10-multi-turn-condition.md](ai-10-multi-turn-condition.md) |
| 板块十一 | 多文对比与收藏夹语义检索（工具⑨序号定位 ≤3 篇对比 + 工具⑩收藏白名单语义检索；含 search 板块四 4.1 include/excludeArticleIds 配套落地） | [ai-11-compare-favorites.md](ai-11-compare-favorites.md) |
| 板块十二 | 检索编排与创作辅助扩展（工具⑪找系列/工具⑫热榜上下文/工具⑬风格仿写素材 + /duplicate-check 查重端点 + 读者预设问题/选题灵感 scene 路由；含 SearchClient#getArticleAiLabels 配套落地；4.5 并入 3.1、4.7 并入板块十五） | [ai-12-creator-assist.md](ai-12-creator-assist.md) |
| 板块十五 | 写作辅助扩展——多级改写建议与文章草稿（/rewrite 四粒度 + /draft 建议态草稿，purpose=POLISH 复用写作辅助桶；板块十二 4.7 归并随 15.1 TITLE 粒度回收，2026-11-17 归档自 next_update.md ai 板块十五） | [ai-15-writing-assist.md](ai-15-writing-assist.md) |

## 横跨板块的 Agent 化定档决策（P1–P10，2026-11-15 全部定档；原样搬运自 next_update.md「待决策与外部前置」）

| 编号 | 事项 | 定档口径（2026-11-15） | 影响范围 |
| --- | --- | --- | --- |
| P1 | 出站红线（Q63） | 选「边界修订」：未公开稿件经脱敏（手机号/邮箱/身份证/外部链接）后外发合规国内供应商（API 协议含数据处理条款）+ 用户协议告知；脱敏在 provider 出站前统一执行；不私有化；外发保留配置开关 | 板块二/三 |
| P2 | 模型选型 | DeepSeek API（OpenAI 兼容、国内直连）+ LangChain4j 官方 BOM + spring-boot-starter 自动装配；换供应商只改 yml（1.1 配置外置保证） | 0.1、1.1、板块二 |
| P3 | Embedding | 本地 Ollama + bge-m3（1024 维，零外发零成本），云 API（智谱 embedding-3）兜底；DeepSeek 无 embedding，必须独立选型 | 4.1 |
| P4 | 会话上限口径 | 分层定档：D25（90 天/200 会话/单会话 500 条）为**持久化上限**；Design-Document 的 10/30 为**进 prompt 的 ChatMemory 滑窗**（windowSize 取最近 N 条）——DB 全量存 + Memory 滑窗，两者不同层不冲突 | 3.1/3.2 |
| P5 | 匿名问答配额 | Redis 日计数键 `ai:quota:anon:{ip哈希}`；计量明细 user_id 空 + scene 记 IP 哈希（脱敏）；数值先写常量、后外置配置 | 4.2、1.3 |
| P6 | 路由打标口径 | 不做独立路由 LLM 调用：purpose 由功能入口（调用方）预传入，Agent 循环继承该值；路由靠入口信息而非模型分类；OTHER 兜底 | 2.3、1.3 |
| P7 | 建议态存活期 | Redis 缓存建议结果，key=requestId，TTL 30 分钟；刷新/多端可回查，过期即「未确认丢弃」 | 2.5/3.3 |
| P8 | content.published 事件核实 | ✅ 已闭环（2026-11-15 代码核实）：事件已存在——MqConstant 定义 `content.published`/`content.unpublished` 路由键、ContentPublishedEvent 已在 litisle-api、content 发布且 search 已有消费先例；AI 直接消费，正文经 ContentClient 回查，无需新增契约 | 4.1 |
| P9 | ES 实例与索引归属 | 共用 ES 实例，AI 自建自管 `article_chunks` 索引（索引名隔离）；与 D9 不冲突——RAG 知识库不是站内检索，与 search 的 article_idx 互不干涉 | 4.1 |
| P10 | 一次性生成与会话额度 | 直连端点（/summary、/tag 等）**不建会话**：不写 ai_session/ai_message，Memory 仅服务 /chat 多轮入口；生成留痕由计量明细承担 | 2.5/3.1 |

> 剩余外部条件：DeepSeek / Ollama 服务可用与 api-key 配置（联调前置，不阻塞编码）；运行态验证项见 next_verify.md AI-V1/AI-V2。

## 跨板块遗留与挂起项去向（归档时登记，2026-11-15）

| 遗留/挂起项 | 去向 |
| --- | --- |
| 网关 /api/ai/** 前缀与新增端点登记（Api-Architecture 定稿） | temporarily-01-code.md「litisle-ai 模块」节 |
| AI-Architecture §7.3 登记 AI 消费 content.published/content.unpublished 两队列 + §7.1 端点登记 | temporarily-01-code.md「litisle-ai 模块」节 |
| D15 桶口径文档修订（POLISH/STRUCTURE 扩桶） | temporarily-01-code.md「litisle-ai 模块」节 |
| 小审判 XIAOSHEN 会话复用 ai_session/ai_message（agent_type 字段已预留） | temporarily-01-code.md「litisle-ai 模块」节 |
| 会话「已保存区」（is_lru=0）字段与治理参数预留，保存动作端点未在清单要求 | temporarily-01-code.md「litisle-ai 模块」节 |
| review 侧 LlmTextModerationAdapter 传参改造（purpose=MODERATION_TEXT + requestId=taskId:chunkIndex） | 归属 review 模块（1.2/1.4 牵连，登记于 temporarily-01-code.md ai 节备注） |
| ai_token_usage / ai_usage_daily 计量明细超期清理（18.1 遗留部分） | temporarily-01-code.md 18.1 条目（ai_session/ai_message 部分已随板块三 3.1 AiSessionCleanupTask 落地回收） |
