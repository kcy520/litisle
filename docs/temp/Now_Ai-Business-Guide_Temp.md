# LitIsle AI 模块临时速览（当前实现态，2026-11-15）

> **文档定位**：临时速览版——快速理解 AI 模块当前的业务逻辑、架构与代码落位，快速上手用。
> **正式版**：`docs/Study/AI-Business-Guide.md`（当前为空；完整原版 1100+ 行在 git 提交 `0106d4a`/HEAD 中，恢复后以正式版为唯一业务口径来源）。
> **实现记录**：板块零~四全部完成，条目细案见 [docs/archive/ai/ai-implemention-records.md](../archive/ai/ai-implemention-records.md)；运行态验证见 [docs/next/next_verify.md](../next/next_verify.md) AI-V1/V2。
> **范围说明**：本速览聚焦**已实现部分**；图片判定端点、小审判定辅助、反馈采集、评测集等预留项只在清单标注，不展开（正式版有完整定档）。

---

## 〇、全景图：两条业务线

```
业务线A：机审供给侧（文本判定）                      业务线B：生成式小悦 Agent
review ──Feign──▶ AI /api/feign/ai/chat            用户/前端 ──网关──▶ AI /ai/xiaoyue/*
（LLM 文本判定，review 侧改造前仍走人工）            （摘要/标签/润色/结构/站内问答）

业务线B 的知识库支撑（板块四 RAG）：
content 发布/下架事件 ──MQ──▶ AI 摄入切片+向量 ──▶ ES(article_chunks_ai) ──▶ 问答引用
```

- **业务线A 现状**：AI 侧提供方端点已就绪并强制 purpose/requestId；review 侧传参改造未做（挂起项），改造前 review 调用被 80001 拒绝 → 转 SUSPICIOUS 人工，与「提供方缺失」现状等价，**无回归**。
- **业务线B 现状**：全链路已实现（Agent 底座 + 会话 + 润色/结构 + RAG 问答）；端到端联调待外部条件（DeepSeek api-key / Ollama / ES / RabbitMQ）。

## 一、模块定位与职责边界（当前实现态）

### 1.1 AI 负责什么

| 职责 | 落位 |
| --- | --- |
| 统一模型通道（provider 层唯一接触模型 SDK，配置外置，不硬编码端点） | `provider/text` + `provider/embedding` |
| Token 计量、预算分账、用户配额（幂等键 = requestId + callSeq 联合唯一） | `service` 三表（ai_token_usage / ai_usage_daily / ai_pricing） |
| 小悦 Agent：意图路由 + 工具编排 + 降级直连 + 结构化自校验 | `agent` + `service/XiaoyueChatService` |
| 会话治理与多轮记忆（DB 全量存档 + Redis 滑窗两层） | `service/AiSessionService` + `memory/RedisChatMemoryStore` |
| RAG 知识库：摄入（MQ→切片→向量→ES）+ 检索（knn→回查→引用） | `mq` + `rag` + `service/Rag*Service` |
| 指标与日志纪律（ai.* 指标族；日志不打印正文全文/用户身份） | `metrics/AiMetrics` |

### 1.2 AI 不负责什么（边界铁律）

- **不写业务库**：Agent 产出一律建议态（D4）——不落业务表，作者确认后由 content 落库；AI 只写自己的 ai_* 五张表（token_usage / usage_daily / pricing / session / message）。
- **不执行闸门**：DFA 敏感词闸门由调用方（content/review）执行，AI 侧工具③敏感词预检只是占位观察。
- **不做站内检索**：RAG 知识库不是站内搜索——AI 自建自管 `article_chunks_ai` 索引（P9 索引名隔离），与 search 的 article_idx 互不干涉。
- **不做路由 LLM**：purpose 由功能入口预传入（P6），AI 不做意图分类调用。

### 1.3 与各模块的功能切分

| 模块 | 交互方式 | 说明 |
| --- | --- | --- |
| review | Feign（AiClient.chat） | AI 只回结构化判定结论；防注入包裹与白名单三分态映射由 review 侧组织 |
| content | Feign（ContentClient） | 工具回查稿件/标签池 + RAG 摄入门控回查 + 引用 verifyChunk 回查 |
| search | 无直接交互 | 仅共用 ES 实例（索引名隔离）；article_chunks 文本切片归 search、向量切片归 AI |
| gateway | HTTP 转发 | /api/ai/** 前缀登记待 Api-Architecture 定稿（挂起项） |

## 二、功能清单与实现状态（板块零~四映射）

| 能力 | 状态 | 关键类 |
| --- | --- | --- |
| 模型通道（DeepSeek OpenAI 兼容） | ✅ 已实现 | OpenAiCompatTextModelProvider（enabled=false 可启动，调用抛 80005） |
| 出站脱敏（P1：手机号/邮箱/身份证/外链） | ✅ 已实现 | OutboundSanitizer + DesensitizingChatModel 装饰器（全通道覆盖，开关可一键关闭） |
| purpose 打标契约 | ✅ 已实现 | AiPurposeEnum（MODERATION_TEXT/IMAGE、SUMMARY/TAGGING/QA、POLISH/STRUCTURE、OTHER） |
| 计量/预算/配额/聚合 | ✅ 已实现 | AiUsageRecordService / AiBudgetService / AiUserQuotaService / AiUsageDailyAggregateTask |
| 机审 Feign 端点 | ✅ 已实现 | AiChatController /api/feign/ai/chat |
| Agent 底座（AiServices） | ✅ 已实现 | AiAgentConfig + XiaoyueAgent + MeteringAgentChatModel（循环上限 5 轮→80007） |
| 四工具（取稿/查池/敏感词占位/RAG 检索） | ✅ 已实现 | XiaoyueTools（工具③敏感词为占位观察，闸门兜底在调用方） |
| 单一入口 /chat + 直连 /summary /tag | ✅ 已实现 | XiaoyueController + XiaoyueChatService |
| 结构化输出自校验（回喂重试 1 次） | ✅ 已实现 | SuggestionValidator + STRUCTURED_RETRY_FEEDBACK 模板 |
| 建议态缓存（P7：key=requestId TTL 30min） | ✅ 已实现 | SuggestionCacheService |
| 会话治理（D25：200 会话 LRU / 500 条 / 90 天） | ✅ 已实现 | AiSessionService + AiSessionCleanupTask |
| Redis ChatMemory（窗口 10 / TTL 30min） | ✅ 已实现 | RedisChatMemoryStore |
| 润色 / 结构分析 | ✅ 已实现 | /polish /analyze-structure（POLISH/STRUCTURE 独立预算/配额桶） |
| RAG 摄入链路 | ✅ 已实现 | RagIngestConsumer + RagIngestService + ContentChunker + ChunkIndexClient |
| RAG 检索 + 引用回查 + 引用卡片 | ✅ 已实现 | RagSearchService + RagContext + XiaoyueCitationDTO |
| 匿名问答配额（P5） | ✅ 已实现 | AiUserQuotaService.tryAcquireAnonymous（IP 哈希日限 10 次） |
| 图片判定端点 / 小审判 / 反馈采集 / 评测集 | ⏳ 预留 | 不在本期范围（正式版定档）；小审判会话已预留 agent_type 字段 |

## 三、业务流程（当前实现态）

### 3.1 小悦生成式流程（核心链路）

```
POST /ai/xiaoyue/chat（purpose 入口预传入）
 ① purpose 校验（非法 80001）→ ② requestId 校验（80002）+ 匿名放行判定（仅 QA + anonIpHash）
 ③ articleId 校验（QA/POLISH 可不带）→ ④ 用户配额（80003）/ 匿名 IP 配额
 ⑤ 预算判定（EXCEEDED 80004 / WARN 只提醒）→ ⑥ 建议缓存回查（命中直接返回，不重复计量）
 ⑦ 会话准备（sessionId 非空：归属校验 + USER 消息落库 + touch；空：tmp:requestId 一次性键）
 ⑧ AgentCallContext.begin → ⑨ RagContext.begin
 → XiaoyueAgent 推理循环（每轮经 MeteringAgentChatModel 计量，超 5 轮抛 80007）
    工具自主调用：取稿（脱敏出站）→ 查池 → RAG 检索（QA）→ 结果回喂
 → 终答：SuggestionValidator 校验（不过回喂重试 1 次，仍失败降级）
 → 成功：ASSISTANT 落库（带轮次 token 对账）+ P7 缓存 + citations 回传（QA）
 降级链：80007/80005 → 解除循环约束 → 直连固定管道（degraded=1 计量）→ 也失败 → 「暂时无法生成」提示（不落库）
```

### 3.2 RAG 摄入与引用链路

```
摄入（异步，rag.enabled=true 才装配）：
 content.published MQ → RagIngestConsumer → ContentClient 回查
 → 门控（status=2 已发布 && visibility=1 公开；不过即删除切片——隐私红线）
 → ContentChunker 清洗（剥 script/style/HTML）→ 滑窗切片（500 字/重叠 50/单篇上限 100 片）
 → Ollama bge-m3 向量化（1024 维，维度校验兜底）→ 先删后 bulkUpsert 覆盖写（chunkId=articleId_chunkIndex 幂等）
 content.unpublished MQ → 按篇删除（不区分 reason，幂等）
 失败语义：ES/向量异常 → MQ 延迟重投（3 次×5s 起步）→ 耗尽落 DLQ

检索（问答时工具④触发）：
 embed 查询 → knnSearch TopK=5 → min-score=0.6 过滤
 → verifyChunk 回查：回查失败/非已发布/非公开 → removed=true；contentHash 与摄入时不一致 → 「内容已更新」标注（Q75）
 → passages 组装（【引用N】溯源编号 + 使用规则）回喂模型 → 终答带 citations 卡片
 降级（Q54）：RAG 不可用/无命中 → 提示模型无检索回答并标注「未引用站内检索结果」，不冒充站内回答
```

### 3.3 会话与记忆两层口径（P4 定档，不冲突）

- **DB 层（ai_session/ai_message）**：全量存档服务列表与审计；治理 = 200 会话 LRU / 单会话 500 条 / 90 天物理清理 / 软删 7 天兜底清扫（D25）。
- **Redis 层（ai:memory:{memoryId}）**：只存窗口最近 10 条进模型上下文，TTL 30 分钟过期即弃；Redis 异常降级为无记忆单轮回答（log.warn，不静默丢上下文）。

## 四、关键规则速查

| 规则 | 口径 |
| --- | --- |
| 计量幂等 | requestId + callSeq 联合唯一；失败也落 status=FAIL；同一 requestId 只扣一次（判定式配额 + P7 缓存回查保证） |
| 预算分账（D15） | 机审桶=文本+图片合并；SUMMARY/TAGGING/QA/POLISH/STRUCTURE 各自独立桶；≤0 只计量不限额；≥80% WARN；100% 停生成不停机审 |
| 用户配额（D5/V8） | 用户×用途×日，次数+Token 双维；数值未定档（≤0 仅计量）；匿名 QA 独立 IP 哈希日限（P5，默认 10） |
| 出站红线（P1/Q63） | 未公开稿件脱敏后外发（四类正则替换）；开关 desensitize-enabled 一键关闭 |
| 建议态（D4/D3/D13） | 不自动写入；响应恒带 aiGenerated 标识；未确认 TTL 30 分钟到期即弃 |
| 引用铁律（Q75/Q54） | 引用必须可溯源可回查；版本比对 contentHash；下架即失效；RAG 降级必须标注 |
| 建议错误码 | 80001 purpose 缺失 / 80002 requestId 缺失 / 80003 配额超限 / 80004 预算超限 / 80005 通道不可用 / 80007 Agent 循环超限 |

## 五、代码结构速览（litisle-ai）

```
com.litisle.litisleai
├── controller      AiChatController（/api/feign/ai/chat 机审）· XiaoyueController（/ai/xiaoyue/*）· SessionController（/ai/sessions）
├── agent           XiaoyueAgent 接口（@MemoryId）· MeteringAgentChatModel（按轮计量+循环上限）· AgentCallContext（ThreadLocal）
│   └── tools       XiaoyueTools（@Tool 四工具：取稿/查池/敏感词占位/RAG 检索）
├── provider
│   ├── text        TextModelProvider · OpenAiCompatTextModelProvider · OutboundSanitizer · DesensitizingChatModel
│   └── embedding   EmbeddingProvider · OllamaEmbeddingProvider
├── memory          RedisChatMemoryStore（LangChain4j ChatMemoryStore）
├── rag             ContentChunker · ArticleChunkDoc · ChunkIndexClient（唯一 ES 出口）· RagContext（ThreadLocal 引用）
├── mq              AiRagMqConfig（拓扑声明，rag.enabled 条件装配）· RagIngestConsumer（双队列）
├── service         AiChatService · XiaoyueChatService（入口编排）· XiaoyueSuggestionService（直连管道）
│   │               AiUsageRecordService · AiBudgetService · AiUserQuotaService · AiSessionService · RagIngestService · RagSearchService
│   └── support     SuggestionValidator · SuggestionCacheService
├── config          AiModelConfig（装饰链装配）· AiAgentConfig（AiServices）· AiRagConfig（向量+ES）· 各 *Properties
├── domain/mapper   AiTokenUsage · AiUsageDaily · AiPricing · AiSession · AiMessage
├── metrics         AiMetrics
└── task            AiUsageDailyAggregateTask（每小时）· AiSessionCleanupTask（每日 03:30）
```

## 六、配置速览（litisle.ai.*，yml/Nacos 外置）

| 段 | 关键项 | 说明 |
| --- | --- | --- |
| provider.text | enabled / base-url / api-key / model / timeout-ms | **enabled=false 本地可启动**，调用抛 80005；api-key 环境变量注入 |
| budget | moderation/summary/tagging/qa/polish/structure + warn-ratio | 各桶日预算（元），≤0 只计量 |
| quota.* | daily-calls / daily-tokens | ≤0 未定档仅计量不拦截 |
| agent | max-loop-rounds=5 / summary-max-chars=500 / max-tag-count=5 / structured-retry-budget=1 / desensitize-enabled=true / suggestion-cache-ttl-minutes=30 / session-*（D25 治理）/ memory-*（滑窗 10/30min） | |
| rag | **enabled=false** / ollama-base-url / ollama-model=bge-m3 / vector-dimension=1024 / es-* / es-index=article_chunks_ai / chunk-size=500 / chunk-overlap=50 / top-k=5 / min-score=0.6 / anon-qa-daily-limit=10 | **rag=false 时 MQ 拓扑不声明、消费者不装配**（与既有配置并存不冲突） |

## 七、快速上手路径

1. **看流程**：先读本文 §三 → 再看 [ai-implemention-records.md](../archive/ai/ai-implemention-records.md) 各板块 ✅ 摘要（实现细节全在这）。
2. **读代码**：入口 [XiaoyueChatService](../../litisle-ai/src/main/java/com/litisle/litisleai/service/XiaoyueChatService.java)（业务编排主脉络）→ [XiaoyueTools](../../litisle-ai/src/main/java/com/litisle/litisleai/agent/tools/XiaoyueTools.java)（工具）→ [AiAgentConfig](../../litisle-ai/src/main/java/com/litisle/litisleai/config/AiAgentConfig.java)（装配）。
3. **跑起来**：DeepSeek api-key 注入（provider.text.enabled=true）+ MySQL/Redis/Nacos；RAG 联调另需 Ollama（bge-m3）+ ES + RabbitMQ 并开 rag.enabled=true。
4. **验证**：按 next_verify.md AI-V1（RAG 端到端 7 项验收）/ AI-V2（会话与计量）逐条执行。
5. **挂起项**：网关登记 / 架构文档登记 / D15 修订 / review 传参改造等见 [temporarily-01-code.md](../next/temporarily/temporarily-01-code.md)「litisle-ai 模块」节（各附触发条件）。
