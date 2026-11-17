# LitIsle AI 模块临时速览（当前实现态，2026-11-17）

> **文档定位**：临时速览版——快速理解 AI 模块当前的业务逻辑、架构与代码落位，快速上手用。每个功能按「功能描述 / 业务逻辑 / 重要子业务逻辑详解」三层展开。
> **正式版**：`docs/Study/AI-Business-Guide.md`（已完整，897 行，2026-11-17 已同步板块五~十五新能力）——业务口径冲突时**以正式版为唯一来源**，本速览只做上手导览。
> **实现记录**：板块零~十二、十三 5.3、十四部分、十五全部完成并归档，条目细案见 [docs/archive/ai/ai-implemention-records.md](../archive/ai/ai-implemention-records.md) 各板块文件；运行态验证见 [docs/next/next_verify.md](../next/next_verify.md) AI-V1~V8。活跃待办仅剩板块十三 5.1/5.2（能力发现端点）与板块十四（读前预览+组合编排）。
> **范围说明**：图片判定端点、小审判定辅助、反馈采集、评测集等预留项只在 §二·六 清单标注，不展开。

---

## 〇、全景图：两条业务线

```
业务线A：机审供给侧（文本判定）
review ──Feign──▶ AI /api/feign/ai/chat（LLM 文本判定；review 侧改造前仍走人工，无回归）

业务线B：生成式小悦 Agent
用户/前端 ──网关──▶ AI /ai/xiaoyue/*（摘要/标签/润色/结构分析/站内问答/相似推荐
                     /查重/改写建议/草稿）+ /ai/sessions（会话）+ /ai/usage/*（用量）

业务线B 的知识库支撑（RAG）：
content 发布/下架事件 ──MQ──▶ AI 摄入切片+向量 ──▶ ES(article_chunks_ai) ──▶ 问答引用回查

业务线B 的相似检索支撑（search 侧）：
条件提取（LLM）→ SearchClient#similarByCondition（四类 match + reranker 精排，0 次生成）
```

所有 AI 能力共用同一套底座：模型通道（provider 层）→ 计量配额预算（三表+判定）→ Agent 底座（AiServices+工具）→ 会话记忆 → RAG。下层不感知上层业务，上层可独立降级。

## 一、模块定位与职责边界

### 1.1 AI 负责什么 / 不负责什么（边界铁律）

| 职责 | 落位 | 边界铁律（不做什么） |
| --- | --- | --- |
| 统一模型通道（唯一接触模型 SDK 的层） | provider/text + provider/embedding | 不硬编码端点，配置外置；失败 fail-fast 上抛不静默 |
| Token 计量、预算分账、用户配额 | service + ai_* 三表 | 不预扣配额（判定式）；失败调用也计量 |
| 小悦 Agent（路由/工具/降级/自校验/边界话术） | agent + XiaoyueChatService | 不写业务库——产出一律建议态（D4），落库由 content 执行 |
| 会话治理与多轮记忆 | AiSessionService + RedisChatMemoryStore | 不跨用户泄露会话存在性（跨用户按不存在处理） |
| RAG 知识库（摄入+检索+引用） | mq + rag + Rag*Service | 不做站内检索——自建自管 article_chunks_ai，与 search 索引隔离（P9） |
| 条件化相似推荐（条件提取+条件版检索） | ConditionExtractService + SimilarRecommendService | 条件提取每轮 1 次 LLM（SIMILAR 桶）；注册表外维度拒绝，词表外长尾由 search 侧语义兜底 |
| 写作辅助（改写/草稿建议态） | WritingAssistService（/rewrite /draft） | AI 不落库——作者采用后走 content 正常创建流程（DFA+机审），AI 草稿不绕过送审 |
| 指标与日志纪律 | metrics/AiMetrics | 日志不打印正文全文/私信/用户身份 |

### 1.2 与各模块的功能切分

| 模块 | 交互 | AI 负责 | 对方负责 |
| --- | --- | --- | --- |
| review | Feign（AiClient.chat） | 回结构化判定结论；purpose/requestId 强制 | 防注入包裹、白名单三分态映射、SPI 传参改造（挂起项） |
| content | Feign（ContentClient） | 门控回查/取稿/查池/引用回查/热榜上下文/收藏列表 | 发布与下架事件生产、闸门（DFA）、落库建议、收藏查询 |
| search | Feign（SearchClient） | similar/similarByCondition/searchUsers/getArticleAiLabels 调用 | 索引与切片、条件版查询四类 match 路由、本地 reranker 精排（litisle.search.rerank.*，0 次 LLM） |
| gateway | HTTP 转发 | 端点实现 | /api/ai/** 前缀已登记（Gateway-Architecture §2.1 + Api-Architecture §4.5，板块七回收） |

## 二、功能清单与实现状态总表

| 能力 | 状态 | 关键类 |
| --- | --- | --- |
| 模型通道（DeepSeek OpenAI 兼容） | ✅ | OpenAiCompatTextModelProvider |
| 出站脱敏（P1） | ✅ | OutboundSanitizer + DesensitizingChatModel |
| purpose 打标契约 | ✅ | AiPurposeEnum |
| 计量/预算/配额/聚合 | ✅ | AiUsageRecordService / AiBudgetService / AiUserQuotaService / AiUsageDailyAggregateTask |
| 机审 Feign 端点 | ✅ | AiChatController /api/feign/ai/chat |
| Agent 底座 | ✅ | AiAgentConfig + XiaoyueAgent + MeteringAgentChatModel |
| 四工具注册 | ✅ | XiaoyueTools（工具③敏感词为占位观察） |
| 单一入口 /chat + 直连 /summary /tag | ✅ | XiaoyueController + XiaoyueChatService |
| 结构化输出自校验 | ✅ | SuggestionValidator |
| 建议态缓存（P7） | ✅ | SuggestionCacheService |
| 会话治理（D25） | ✅ | AiSessionService + AiSessionCleanupTask |
| Redis ChatMemory | ✅ | RedisChatMemoryStore |
| 润色 / 结构分析 | ✅ | /polish /analyze-structure |
| RAG 摄入链路 | ✅ | RagIngestConsumer + RagIngestService + ContentChunker + ChunkIndexClient |
| RAG 检索 + 引用回查 + 引用卡片 | ✅ | RagSearchService + RagContext + XiaoyueCitationDTO |
| 匿名问答配额（P5） | ✅ | AiUserQuotaService.tryAcquireAnonymous |
| F8 相似文章推荐（板块五） | ✅ | SimilarRecommendService（POST /ai/xiaoyue/similar，纯检索 token=0 落明细） |
| 用量查询与出账（F9，板块六） | ✅ | /ai/usage/me·/me/details·/summary 三端点 + 模型自检 |
| 条件化相似推荐——条件提取管道（板块八） | ✅ | ConditionExtractService（提取→词表/区间/topK 校验回喂重试）+ 维度注册表 |
| 条件化相似推荐——条件版检索（板块五扩展） | ✅ | SimilarRecommendService.similarWithCondition（SearchClient#similarByCondition） |
| 多轮调条件（板块十） | ✅ | SessionConditionStoreService（Redis 会话条件上下文 + 增量合并） |
| 会话结果集与结果动作（板块九） | ✅ | ResultStoreService + XiaoyueTools 动作（收藏落库；书单/金句挂起 temporarily-01） |
| 多文对比/收藏夹检索（板块十一） | ✅ | XiaoyueTools 工具⑨ compareResultArticles / 工具⑩ searchMyFavorites |
| 创作辅助扩展（板块十二） | ✅ | 工具⑪找系列/⑫热榜上下文/⑬风格素材 + /duplicate-check + 读者预设问题/选题灵感 scene 路由 |
| 能力发现——边界响应话术（板块十三 5.3） | ✅ | XIAOYUE_BOUNDARY_RESPONSE_RULES + boundary-refusal-template（零 LLM）；5.1/5.2 ⏳ 恢复待开发 |
| 写作辅助扩展（板块十五） | ✅ | WritingAssistService（/rewrite 四粒度 + /draft 草稿，AI 不落库） |
| 本地 reranker 精排（search 侧配套，2026-11-17） | ✅ | search RerankServiceImpl（条件版查询重排，0 次 LLM，fail-open） |
| 图片判定端点 / 小审判 / 反馈采集 / 评测集 | ⏳ 预留 | 不在本期范围；小审判已预留 agent_type 字段 |

---

## 三、功能详述：机审供给侧（业务线A）

### 3.1 功能描述

对内提供 LLM 文本机审判定端点 `POST /api/feign/ai/chat`（契约 AiClient 已在 litisle-api）。review 侧把疑似违规文本分片送来，AI 返回结构化判定结论；AI 只负责「调模型出结论」，不负责防注入与结论消费。

### 3.2 业务逻辑（端点编排，AiChatService）

1. **入参校验**：purpose（必须 MODERATION_TEXT 类）与 requestId 缺省 → 抛 80001/80002 并告警——防「忘传」被默认归机审。
2. **OTHER 兜底**：非前五类 purpose 按「未归类调用」告警放行，落明细但不计预算报表。
3. **预算判定**：走机审桶（文本+图片合并桶）。
4. **调模型**：经 provider 层（出站脱敏后）调 DeepSeek；失败 fail-fast 上抛不重试（重试由 review 侧 MQ 重试机制负责，requestId 复用保证不重复计费）。
5. **计量**：成功/失败均落 ai_token_usage 明细（callSeq 固定 1）。
6. **响应组装**：返回结构化结论文本；模型通道未启用（enabled=false）抛 80005。

### 3.3 重要子业务逻辑详解

- **机审桶 100% 语义**：预算到 100% **不停机审调用**（审核是合规底线，不能停），转为全量人工复核 + 告警；生成类桶到 100% 则停新请求抛 80004。两种桶执行语义不同，判定与执行分离：AiBudgetService 只判定，执行语义收口在 AiChatService/XiaoyueChatService。
- **callSeq 为什么固定 1**：本通道 requestId = taskId:chunkIndex（review 侧分片确定性切分），一次调用即一个计量单位，天然唯一；Agent 多轮场景才需要 callSeq 递增（见 §四）。
- **review 侧传参已改造打通（2026-11-15）**：SPI `ContentModerationService.moderateText` 已改收 `TextModerationRequest`（content/taskId/chunkIndex/scene），适配器传 purpose=MODERATION_TEXT + requestId=taskId:chunkIndex + scene 快照；LLM 文本机审闭环打通——联调前置：`litisle.moderation.text-provider=llm` + AI 侧 provider.text.enabled=true + api-key 注入。
- **出站脱敏覆盖**：机审文本同样经 DesensitizingChatModel 脱敏出站（用户私信/稿件含手机号等敏感信息不外发明文）。

---

## 四、功能详述：小悦 Agent 底座（业务线B核心）

### 4.1 功能描述

基于 LangChain4j AiServices 装配的小悦 ChatAgent：模型自主决定调哪个工具、调几次，产出摘要/标签/润色/结构分析/问答建议。区别于固定管道式 LLM 调用——同一底座承载多用途，入口只传 purpose。

### 4.2 业务逻辑（装配与执行）

- **装配**（AiAgentConfig）：ChatModel（计量装饰链）+ systemMessageProvider（角色定位+能力边界+建议态约束+**边界响应规则段**（板块十三 5.3：拒绝话术模板 + {capabilities} 从真实注册工具如实填写，仅意图路由轮注入，固定管道不混入防污染 JSON））+ tools 注册（工具③~⑬，板块九~十二扩展：结果动作/对比/收藏夹检索/找系列/热榜上下文/风格素材）+ maxSequentialToolsInvocations（框架层护栏）。enabled=false 不装配，端点抛 80005。
- **执行**：用户消息 → Agent 推理 → 工具调用与结果回喂（可多轮）→ 终答。工具失败**不抛异常**，统一作为观察文本回喂模型自行调整（如标签池查不到 → 模型改为提示用户）。
- **上下文传递**：AgentCallContext（ThreadLocal）承载 requestId/purpose/userId/scene 与 callSeq 递增，每轮计量都归属同一业务请求。

### 4.3 重要子业务逻辑详解

- **双重循环护栏（防死循环与成本放大）**：
  - 第一层：框架 maxSequentialToolsInvocations；
  - 第二层：MeteringAgentChatModel（ChatModel 装饰器）按轮计数，超过 max-loop-rounds=5 抛 80007 零成本 fail-fast——「零成本」指超限轮不再发起模型调用，直接中断。
  - 为什么要两层：框架护栏管「连续工具调用次数」，业务护栏管「轮次预算」，且计量必须收口在模型调用层才能精确到每轮 token。
- **每轮计量**：每轮（含工具回喂轮、自校验回喂轮）经装饰模型调用时按 requestId+callSeq 落一条明细，成功/失败均落；降级轮 callSeq 连续累计不断号——同一请求全链路成本可对账。
- **出站脱敏装饰链（P1/Q63）**：DesensitizingChatModel 是 ChatModel 装饰器，对 system/user/ai/工具结果四类消息出站前统一经 OutboundSanitizer（手机号/邮箱/身份证/外部链接四类正则替换占位符）。装配链 = DesensitizingChatModel → OpenAiChatModel，容器唯一 ChatModel Bean——机审与 Agent 全通道自动覆盖，无需各业务点自行调用。开关 desensitize-enabled 一键关闭（正式供应商协议变化时）。
- **建议态缓存（P7）**：Redis key=`ai:suggestion:{requestId}` TTL 30 分钟，只缓存成功建议。幂等重试/多端刷新命中缓存直接返回，不重复计量不重复调模型；过期即「未确认丢弃」（D4：建议态不落库，确认后由 content 落库）。Redis 异常 best-effort 静默降级不影响主链路。

## 五、功能详述：意图路由与降级直连

### 5.1 功能描述

单一入口 `POST /ai/xiaoyue/chat` 承载全部生成类用途（SUMMARY/TAGGING/QA/POLISH/STRUCTURE）；/summary、/tag、/polish、/analyze-structure 为固定管道直连端点，既是一键式轻量入口，也是 Agent 异常时的降级通道。板块九~十五扩展入口按 scene 路由（读后追问/书单动作/对比/收藏夹检索/读者预设问题/选题灵感/REWRITE/DRAFT），purpose 随入口复用既有桶不扩枚举。

### 5.2 业务逻辑（XiaoyueChatService.chat 编排九步）

```
① purpose 校验（非法 80001 告警拒绝）
② requestId 校验（80002）+ 匿名放行判定：userId 为空 → 仅 purpose=QA 且必传 anonIpHash
③ articleId 校验（QA/POLISH 可不带——润色对象可为选中文本）
④ 用户配额 tryAcquire（80003）/ 匿名走 IP 哈希日配额
⑤ 预算判定（EXCEEDED 80004 拒绝 / WARN ≥80% 只提醒）
⑥ 建议缓存回查（命中直接返回，幂等不重复计量）
⑦ 会话准备：sessionId 非空 → 归属校验 + USER 消息落库 + 活跃刷新；空 → tmp:requestId 一次性键
⑧ AgentCallContext.begin + ⑨ RagContext.begin → Agent 执行（finally 兜底 clear/drain 防线程池串号）
→ 终答：结构化校验（不过回喂重试 1 次）→ ASSISTANT 落库（轮次 token 对账）+ 缓存 + citations 回传
```

### 5.3 重要子业务逻辑详解

- **路由不做 LLM 调用（P6）**：purpose 由功能入口预传入，Agent 循环继承——路由靠入口信息而非模型分类，省一次路由成本且不引入误路由；非法 purpose 在入口直接拒绝并告警（80001）。
- **三级降级链**：
  1. Agent 循环超限（80007）/ 模型不可用（80005）→ `leaveAgentPhase` 解除循环约束 → 转直连固定管道（XiaoyueSuggestionService：取稿→模板 prompt→单次调用→自校验），降级明细 degraded=1 可识别；
  2. 直连也失败 → degraded 响应「AI 暂时无法生成，可手动填写」，不落库不抛异常——用户端永远拿到可处理的响应；
  3. RAG 不可用 → 见 §七 降级铁律（业务级降级，与通道降级正交）。
  - 配额与预算在路由后即判定，Agent/降级链路均被覆盖——降级不会绕过成本管控。
- **直连端点不建会话（P10）**：/summary、/tag 等一次性动作不写 ai_session/ai_message，Memory 仅服务 /chat 多轮入口；生成留痕由计量明细承担。
- **结构化输出自校验回喂闭环**：要求模型输出 JSON（摘要/标签/润色/结构）；解析容忍 ```json 围栏与夹带文字；校验失败把**具体错误**（越池标签名单、超出字数）回喂重试 1 次（最小反思闭环，重试预算外置 structured-retry-budget=1）；仍失败降级 degraded 响应不落库。校验规则（池内白名单逐一经 ContentClient 精确比对、长度上限）在 service 侧执行，不依赖模型自觉。

---

## 六、功能详述：会话与多轮记忆

### 6.1 功能描述

`POST /ai/sessions` 创建会话、`GET /ai/sessions` 列表、`DELETE /ai/sessions/{sessionId}` 删除受理；会话内 /chat 带 sessionId 即获得多轮记忆。会话是 AI 持有的暂存数据（D25），不进 content 业务库。

### 6.2 业务逻辑（两层口径，P4 定档不冲突）

- **DB 层（ai_session / ai_message）**：全量存档，服务列表展示与审计对账；ai_message 记 USER/ASSISTANT 全量消息与轮次 token。
- **Redis 层（ai:memory:{memoryId}）**：LangChain4j ChatMemoryStore 实现，只存窗口最近 10 条进模型上下文，TTL 30 分钟过期即弃。

### 6.3 重要子业务逻辑详解

- **memoryId 绑定策略**：memoryId = sessionId（有会话多轮）或 `tmp:{requestId}`（一次性请求）——tmp 键随 Redis TTL 自然过期，消息不落库，实现「无会话请求零留痕」。
- **LRU 淘汰**：暂存区（is_lru=1）达 session-stash-limit=200 上限时，创建新会话按 last_active_time 淘汰最久未活跃会话（软删懒删，行保留作审计）。
- **消息条数上限**：单会话达 500 条拒绝追加并提示开新会话——上限是持久化口径，与进 prompt 的滑窗 10 条不同层。
- **删除与清扫**：删除 = AI 侧受理软删（del_flag=1，同步返回即回执）+ Redis 记忆联动清理；AiSessionCleanupTask 兜底清扫（每日 03:30 + 启动 60s 补跑）：留存 90 天的未删会话物理删除（行+级联消息，分批 200 防大事务）+ 软删超 7 天懒删兜底物理清理（R9 缺口：防用户停用后数据超期残留）。
- **记忆降级**：Redis 异常 getMessages 返回空列表（无记忆单轮回答）+ log.warn 告警，updateMessages 失败不中断回答——降级明确标注，不静默丢上下文。
- **归属校验**：跨用户访问按「不存在」处理（防会话存在性泄露），不存在/已删同样拒绝。

---

## 七、功能详述：计量、配额与预算基建

### 7.1 功能描述

三条表 + 三个服务的成本管控底座：ai_token_usage（明细）/ ai_usage_daily（日汇总）/ ai_pricing（单价）；AiUsageRecordService 写明细、AiBudgetService 预算判定、AiUserQuotaService 用户配额、AiUsageDailyAggregateTask 聚合。

### 7.2 业务逻辑

- **明细写入**：每次模型调用（机审/Agent 每轮/直连每轮/自校验回喂轮）各落一条明细；成本 = token × 归属日最新单价（两位小数）；单价变更只影响未来日期。
- **聚合**：每小时 + 启动延迟一次，对当日与前一日幂等覆盖写（INSERT...SELECT...ODKU）；明细与汇总差异 >1% 记日志告警——保证预算判定读到的当日数据可用且可信。
- **判定时机**：配额与预算在入口路由后即判定；预算读 ai_usage_daily 当日合计，本地缓存 60 秒，不在每次调用读写预算键。

### 7.3 重要子业务逻辑详解

- **幂等键 = requestId + callSeq 联合唯一**（修正架构文档 §8.3④ 原「request_id 唯一」口径——该口径只覆盖机审切片，Agent 多轮会撞键漏计）：
  - requestId 是业务请求幂等键（重试复用同一键），callSeq 区分同一请求内的多次模型调用（轮次/切片序号）；
  - 重复写入捕获 DuplicateKeyException 跳过 = upsert 幂等——MQ 重试/用户刷新不会重复计费；
  - 已有本地库需手动执行 ai-schema.sql 注释中的 ALTER（call_seq 列 + 联合唯一键）。
- **失败也计量**：status=FAIL 明细落库——避免「失败即无成本」假象（模型调用失败也可能已消耗上游 token）。
- **判定式配额（不预扣）**：按 用户×用途×日 校验次数/Token 双维，读日汇总判定而非预扣-退还模型——无「失败退还预扣」复杂度；数值未定档（≤0 仅计量不拦截，D5/V8，Q39）。
- **预算桶分账（D15 修订为八类，板块七 7.2）**：机审桶=文本+图片合并；SUMMARY/TAGGING/QA/POLISH/STRUCTURE/SIMILAR 各自独立桶——一个桶爆了不影响其他用途；机审桶 100% 转人工不停调用（合规底线），生成桶 100% 停新请求（80004）。≥80% WARN 只提醒。
- **用量可观测与模型自检（F9，板块六）**：`/ai/usage/me`（当日用量/剩余配额）· `/me/details`（明细分页）· `/summary`（近 7 日花费）三端点 + 模型自检（通道健康/单价登记核验）；读数全部来自既有三表，不新增写入面。
- **匿名问答配额（P5）**：Redis key=`ai:quota:anon:{ipHash}:{yyyyMMdd}`，INCR + 首次 expire 24h，超限拒绝 80003；计量明细 user_id 空、scene 记 IP 哈希（脱敏）；Redis 异常降级放行不阻断问答（可用性优先，匿名场景损失可控）。

---

## 八、功能详述：RAG 知识库与站内问答

### 8.1 功能描述

两条链路：**摄入**（异步 MQ 驱动：发布/下架事件 → 公开文章切片向量化入 ES）；**检索**（问答工具④：knn 向量检索 → 引用回查 → 组 prompt → 带引用回答）。目标：站内问答有据可循，答案带可溯源引用卡片。

### 8.2 业务逻辑

**摄入链路**（rag.enabled=true 才装配 MQ 拓扑与消费者）：

```
content.published → RagIngestConsumer（手动 ack）
 → ContentClient 回查正文 → 门控：status=2 已发布 && visibility=1 公开
   （不过门控 → deleteByArticle 同步移除切片——未公开内容绝不入库，隐私红线）
 → ContentChunker：剥 script/style 整段 + HTML 标签 + 空白归一 → 滑窗切片（500 字/重叠 50/段落边界断句/单篇上限 100 片）
 → ensureIndex（dense_vector 1024 维 cosine）→ 逐片 Ollama bge-m3 向量化 → 先删后 bulkUpsert 覆盖写
content.unpublished → deleteByArticle 按篇删除（不区分 reason，幂等）
失败语义：ES/向量异常 → MQ 延迟重投（3 次，基础延迟 5s）→ 耗尽落 DLQ；非文章消息 warn 丢弃即 ack
```

**检索链路**（/chat purpose=QA 时 Agent 自主触发工具④）：

```
searchKnowledgeBase → RagSearchService.searchForPrompt
 → embed 查询 → knnSearch TopK=5 → min-score=0.6 相似度过滤
 → verifyChunk 逐片回查 content → passages 组装（【引用N】溯源编号 + 使用规则）回喂模型
 → 终答 → RagContext 引用上下文 → citations 卡片回传前端
```

### 8.3 重要子业务逻辑详解

- **门控与覆盖写（隐私+幂等双保险）**：未公开稿件**永不入索引**（出站红线 D2/V3 同口径）；下架/删文即时移除；改稿重新发布 → chunkId=articleId_chunkIndex 先删后写覆盖更新。异步解耦不阻塞发布主链路（摄入失败只影响知识库新鲜度，不影响文章可用性）。
- **索引隔离（P9）**：AI 自建自管 `article_chunks_ai`，与 search 的 article_idx 及其批次二 article_chunks 文本切片互不干涉——RAG 知识库不是站内检索，检索口径/评分/生命周期独立，避免互相覆盖污染。
- **引用回查 verifyChunk（引用铁律 Q75 + 下架即失效）**：检索命中不代表可引用——每片引用回查 content：
  - 回查失败 / 非已发布 / 非公开 → removed=true 引用移除；
  - contentHash 与摄入时不一致 → 标注「内容已更新」（改稿后旧切片未重建时防引用过期内容）；
  - 门控删除（事前）+ 回查（事中）双保险，引用内容下架即失效。
- **引用上下文传递（RagContext）**：ThreadLocal 承载本轮命中的引用；chat ⑨ begin / finally drain 兜底清理防线程池串号；终答组装时 drain 转 citations 卡片（articleId/title/contentHash/removed）——模型生成的引用编号与真实文章 ID 的映射由框架保证，不依赖模型自觉。
- **RAG 降级（Q54）**：rag.enabled=false 工具 Bean 缺席 / 检索异常 / 无命中 → RAG_DEGRADED_HINT 提示模型「无检索请基于稿件直接回答并标注『未引用站内检索结果』」；事实性结论只答「暂无相关资料」——**不得混入模型自身知识冒充站内回答**（ hallucination 红线）。降级不阻断 Agent 循环。
- **维度校验兜底**：OllamaEmbeddingProvider 返回向量维度不符配置 → 抛 80005 拒绝——防脏向量写入污染 knn 检索。
- **匿名问答放行**：userId 为空仅 purpose=QA 且必传 anonIpHash → IP 哈希日配额（默认 10 次）；匿名链路同样走计量预算（明细 user_id 空 + scene 记 IP 哈希）。

---

## 八·补、功能详述：相似推荐与条件化查询（板块五/八/十/十一）

### 8A.1 功能描述

三段链路共用 search 侧条件版查询：**F8 相似文章推荐**（按文章 knn 近邻，纯检索零生成成本）、**条件化相似推荐**（LLM 把用户口语需求提取为结构化条件 → search 四类 match 检索）、**会话多轮调条件**（会话内增量修改上轮条件）。另有会话结果集（推荐结果缓存 + 序号引用）与结果动作（收藏落库）。

### 8A.2 业务逻辑

```
F8：POST /ai/xiaoyue/similar（requestId+articleId+scene）
 → 向量化 → knn TopK + minScore → 排除自身/同篇去重 → verifyForSimilar 可见性双保险回查
 → 卡片返回（outdated 标注、可选推荐理由 reason-enabled 默认关）；纯检索 token=0 落明细（SIMILAR 桶）

条件化：/chat 口语需求 → ConditionExtractService 提取 JSON（1 次 LLM）
 → 维度注册表校验（词表/区间/topK，越限回喂重试 1 次）→ SearchClient#similarByCondition
 → 卡片 + 条件回显；无命中返回空列表不报错；注册表外维度拒绝，词表外长尾由 search 侧语义兜底

多轮：会话内「再轻松一点/时间改最近半年」→ Redis 取上轮条件 JSON → 增量提取合并（本轮否定覆盖上轮）
 → 校验检索同上；SessionConditionStoreService 异常降级为无上下文普通提取，告警不中断

结果集：推荐结果缓存（result-set-ttl-minutes=60）→ 序号引用（"第 2 篇"）→ 动作（收藏/对比/书单）
```

### 8A.3 重要子业务逻辑详解

- **计量口径**：条件提取每轮 1 次 LLM（SIMILAR 桶，重试轮次 callSeq 递增）；检索本身 0 次 LLM；对比/收藏检索素材组装 0 次额外生成成本，结论在意图路由轮内一次生成。
- **search 侧精排**：条件版查询带 queryText 且按相关性排序时可叠加本地 reranker 重排（0 次 LLM，fail-open 退融合基线）——AI 调用方无感。
- **收藏夹检索**：拉用户收藏 ID 白名单（100 条上限）→ includeArticleIds + queryText 语义 knn → 只出已收藏且当前公开；收藏为空/无命中明确响应。
- **挂起项**：书单动作/金句落库契约缺失（content 侧缺端点），见 temporarily-01-code.md「litisle-ai」节。

---

## 九、关键规则速查

| 规则 | 口径 |
| --- | --- |
| 计量幂等 | requestId + callSeq 联合唯一；失败落 FAIL；同一 requestId 只扣一次（判定式配额 + P7 缓存保证） |
| 预算分账（D15+扩桶） | 机审合并桶 / 生成类各自独立桶；≤0 只计量；≥80% WARN；机审 100% 转人工不停调用，生成 100% 停新请求 |
| 用户配额（D5/V8） | 用户×用途×日双维；≤0 未定档仅计量；匿名 QA 独立 IP 哈希日限（P5，默认 10） |
| 出站红线（P1/Q63） | 未公开稿件脱敏后外发；装饰链全通道覆盖；开关一键关闭 |
| 建议态（D4/D3/D13） | 不自动写入；恒带 aiGenerated 标识；未确认 TTL 30 分钟到期即弃 |
| 会话治理（D25） | 200 会话 LRU / 单会话 500 条 / 90 天物理清理 / 软删 7 天兜底；删除受理即回执 |
| 引用铁律（Q75/Q54） | 引用可溯源可回查；contentHash 版本比对；下架即失效；RAG 降级必须标注 |
| 相似推荐（F8/板块五/八/十/十一） | 纯检索 token=0 落明细；条件提取每轮 1 次 LLM（SIMILAR 桶）；无命中空列表不报错；可见性回查双保险 |
| 写作辅助（板块十五） | 建议态不落库（D4）；articleId 归属校验非本人拒绝；采用走 content 正常创建流程（DFA+机审） |
| 边界话术（板块十三 5.3） | 拒绝必带 {capabilities}（真实工具如实填写，禁编造）；模板 yml 外置；零 LLM 成本 |
| 错误码 | 80001 purpose 缺失 / 80002 requestId 缺失 / 80003 配额超限 / 80004 预算超限 / 80005 通道不可用 / 80007 Agent 循环超限 |

## 十、代码结构速览（litisle-ai）

```
com.litisle.litisleai
├── controller      AiChatController（机审）· XiaoyueController（/ai/xiaoyue/*：摘要/标签/润色/结构/查重/改写/草稿）· SessionController（/ai/sessions）· 用量端点（/ai/usage/*，板块六）· AiLabelController（打标）
├── agent           XiaoyueAgent（@MemoryId）· MeteringAgentChatModel（按轮计量+循环上限）· AgentCallContext
│   └── tools       XiaoyueTools（工具③~⑬：RAG/标签池/敏感词占位/相似/结果动作/对比/收藏夹检索/找系列/热榜/风格素材）
├── provider
│   ├── text        TextModelProvider · OpenAiCompatTextModelProvider · OutboundSanitizer · DesensitizingChatModel
│   └── embedding   EmbeddingProvider · OllamaEmbeddingProvider
├── memory          RedisChatMemoryStore（ChatMemoryStore）
├── rag             ContentChunker · ArticleChunkDoc · ChunkIndexClient（唯一 ES 出口）· RagContext
├── mq              AiRagMqConfig（rag.enabled 条件装配）· RagIngestConsumer
├── service         AiChatService · XiaoyueChatService · XiaoyueSuggestionService · AiUsageRecordService
│   │               AiBudgetService · AiUserQuotaService · AiSessionService · RagIngestService · RagSearchService
│   │               SimilarRecommendService（F8+条件版）· ConditionExtractService（条件提取）· SessionConditionStoreService（多轮条件）
│   │               ResultStoreService（会话结果集）· WritingAssistService（改写/草稿）· DuplicateCheckService（查重）
│   └── support     SuggestionValidator（摘要/标签/改写/草稿校验）· SuggestionCacheService
├── config          AiModelConfig · AiAgentConfig · AiRagConfig · 各 *Properties
├── domain/mapper   AiTokenUsage · AiUsageDaily · AiPricing · AiSession · AiMessage
├── metrics         AiMetrics
└── task            AiUsageDailyAggregateTask · AiSessionCleanupTask
```

## 十一、配置速览（litisle.ai.*，yml/Nacos 外置）

| 段 | 关键项 | 说明 |
| --- | --- | --- |
| provider.text | enabled / base-url / api-key / model / timeout-ms | **enabled=false 本地可启动**，调用抛 80005；api-key 环境变量注入 |
| budget | 八桶日预算 + warn-ratio（D15 修订为八类：机审合并 + SUMMARY/TAGGING/QA/POLISH/STRUCTURE/SIMILAR） | ≤0 只计量 |
| quota.* | daily-calls / daily-tokens | ≤0 未定档仅计量 |
| agent | max-loop-rounds=5 / summary-max-chars=500 / max-tag-count=5 / structured-retry-budget=1 / desensitize-enabled=true / suggestion-cache-* / session-* / memory-* | 会话与记忆治理参数全外置 |
| agent（板块九~十五扩展） | result-set-ttl-minutes=60 / result-action-max-batch=10 / reader-question-count=5 / topic-inspiration-hot-limit=10 / boundary-refusal-template / rewrite-candidate-limit=3 / rewrite-title-max-chars=100 / rewrite-reason-max-chars=50 / draft-max-chars=2000 | 会话结果集 TTL、动作批量上限、创作辅助 N 值、边界话术模板、写作辅助校验上限 |
| rag | **enabled=false** / ollama-* / vector-dimension=1024 / es-* / es-index=article_chunks_ai / chunk-* / top-k=5 / min-score=0.6 / anon-qa-daily-limit=10 | **rag=false 时 MQ 拓扑不声明、消费者不装配** |
| rag.similar（板块五/八/十二） | top-k=5 / conditional-top-k-max=50 / min-score=0.6 / reason-enabled=false / reason-top-n=3 / reason-max-chars=60 / duplicate-score-threshold=0.85 | 相似推荐 TopK 与推荐理由开关（默认关）、查重风险阈值外置 |

## 十二、快速上手路径

1. **看流程**：先读本文 §三~§八·补（每功能三层：描述/逻辑/子逻辑详解）→ 细节再看 [ai-implemention-records.md](../archive/ai/ai-implemention-records.md) 各板块 ✅ 摘要。
2. **读代码**：入口 [XiaoyueChatService](../../litisle-ai/src/main/java/com/litisle/litisleai/service/XiaoyueChatService.java)（九步编排）→ [XiaoyueTools](../../litisle-ai/src/main/java/com/litisle/litisleai/agent/tools/XiaoyueTools.java)（工具）→ [AiAgentConfig](../../litisle-ai/src/main/java/com/litisle/litisleai/config/AiAgentConfig.java)（装配）→ [RagIngestService](../../litisle-ai/src/main/java/com/litisle/litisleai/service/RagIngestService.java)（摄入）→ [SimilarRecommendService](../../litisle-ai/src/main/java/com/litisle/litisleai/service/SimilarRecommendService.java)（相似/条件化推荐）→ [WritingAssistService](../../litisle-ai/src/main/java/com/litisle/litisleai/service/WritingAssistService.java)（写作辅助）。
3. **跑起来**：DeepSeek api-key 注入（provider.text.enabled=true）+ MySQL/Redis/Nacos；RAG 联调另需 Ollama（bge-m3）+ ES + RabbitMQ 并开 rag.enabled=true；条件化推荐依赖 search 侧（SearchClient 契约 + litisle.search.embedding/rerank 配置）。
4. **验证**：按 next_verify.md AI-V1~V8 逐条执行（V1 RAG 端到端 / V2 会话与计量 / V3 相似推荐与用量 / V4 条件提取 / V5 会话交互 / V6 创作辅助 / V7/V8 见文件内条目）。
5. **挂起项**：书单动作/金句落库（content 契约缺失）、板块十三 5.1/5.2（能力发现端点恢复）、板块十四（读前预览+组合编排）——活跃待办见 [next_update.md](../next/next_update.md) 板块十三/十四；其余见 [temporarily-01-code.md](../next/temporarily/temporarily-01-code.md)「litisle-ai 模块」节（各附触发条件）。
