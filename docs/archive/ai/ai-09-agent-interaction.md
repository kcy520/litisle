# ai 板块九：Agent 交互扩展——会话结果集地基 + 结果动作 + 读后服务

> 归档日期：2026-11-16（板块九 1.1/1.3~1.7 全部落地 + 1.2 收藏动作落地；书单动作与金句落库依赖的契约缺失部分转 [temporarily-01-code.md](../../next/temporarily/temporarily-01-code.md)「litisle-ai 模块」节挂起；运行态验证走 [next_verify.md](../../next/next_verify.md) AI-V5）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」板块九节，条目原样搬运不压缩
> 前置核对结论（2026-11-16）：content 收藏接口已存在（FavoriteService.favorite，REST /api/content/favorite，幂等/可见性语义收口在 FavoriteService），缺 Feign 契约面——随本批补 ContentClient#favorite 契约 + ContentFeignProviderController 提供方端点落地；书单接口不存在（content 的 Book* 为书籍域非书单）；笔记/摘录契约不存在

## 方案总纲（原样搬运）

> 方案：一档四项——结果动作（0 次 LLM 工具路由）/ 读后摘要 / 读后追问（复用板块四 RAG 底座）/ 多轮调条件（会话记忆增量改条件）；二档两项——多文对比、收藏夹语义检索（订阅提醒不做）
> 2026-11-16 追加七项：板块九增 1.5 大纲导航 / 1.6 术语卡片 / 1.7 批判性阅读陪聊 / 1.8 金句摘录卡（契约依赖）；板块十二增 4.6 风格仿写辅助 / 4.7 标题/开头多版建议；板块十四增 6.5 继续阅读（契约依赖，与 6.4 同源）
> 原则：公共地基先行（会话结果集序号映射，所有"对结果下指令"类功能的前提）；动作类 0 生成成本、生成类走既有 purpose 计量与预算桶；契约核对前置（content 收藏/书单接口存在性，缺则登记 temporarily）；工具挂 XiaoyueAgent Function Calling 底座——固定管道（板块八）与工具编排两形态并存

## 计量与桶位定档（2026-11-16 随落地定档，原条目「桶位随 1.3/1.4 计量定档对齐」的结论）

- 1.2 收藏动作：**0 次生成成本**——意图路由后纯契约调用，不落 AI 计量明细（Agent 意图路由轮按入口 purpose 照常计量，由 MeteringAgentChatModel 收口）
- 1.3 读后摘要：purpose=SUMMARY（复用板块二摘要链路与 ≤500 字口径，入口预传 purpose 照常走 SUMMARY 桶配额/预算）
- 1.4 读后追问：purpose=QA（单文 RAG，引用回查铁律照搬）
- 1.5 大纲导航：purpose=STRUCTURE（大纲/结构同域，复用 {"analysis"} 结构化输出与校验）
- 1.6 术语卡片 / 1.7 批判性阅读陪聊 / 1.8 金句摘录卡：purpose=QA（读侧会话域统一 QA 桶；不新增 AiPurposeEnum 枚举值，对齐「生成类走既有 purpose 计量与预算桶」原则）

## 1.1 会话结果集映射（公共地基）

- [x] 会话上下文维护「最近一次推荐/检索结果集」的 articleId→序号 映射（复用板块三会话存储，TTL 外置过期失效）；跨会话不可引用，过期后引用序号 → 明确提示重新发起检索，不猜不编造
  - ✅ 2026-11-16：SessionResultRegistryService（Redis key=ai:result-set:{sessionId}，String 存 articleId 有序 JSON 数组，序号=下标+1；TTL 外置 litisle.ai.agent.result-set-ttl-minutes=60；空结果覆盖清空防引用陈旧集合；Redis 异常降级为不可解析并告警不中断会话）；AgentCallContext 扩 sessionId 字段（begin 重载，XiaoyueChatService 会话准备后传入，一次性请求为 null → 结果动作工具按不可用响应）；记录接线：SimilarRecommendService L0/L1 检索成功后 recordResultSet（仅会话请求记录，RAG 缺席/取稿失败不记不清空）；AiSessionService.deleteSession 联动 evict；跨会话不可引用由 sessionId 维度隔离 + 工具只读当前会话注册保证；编译验证已通过（2026-11-16）

## 1.2 收藏/书单动作工具（0 次 LLM 生成成本）

- [x] XiaoyueAgent 新增动作工具：解析"收藏第 2、3 篇/第 1 篇加入书单"→ 经 1.1 序号映射定位 articleId → 调 content 收藏/书单契约；动作失败明确响应不静默吞掉
  - **0 次生成成本**（意图路由后纯契约调用，不落 AI 计量明细）
  - 前置核对：content 收藏接口 / 书单接口是否已存在（核对结果决定本任务是否转 temporarily 挂起，触发条件写明缺哪个契约）
  - ✅ 拍板（2026-11-16 前置核对结论）：content 收藏接口已存在（FavoriteService，幂等/可见性校验收口），缺 Feign 契约面——随本批补契约落地；**书单接口不存在**（content Book* 为书籍域），书单动作转 temporarily 挂起（缺 content 书单契约，见 temporarily-01-code.md ai 节）
  - ✅ 2026-11-16：litisle-api 契约 ContentClient#favorite(userId, targetType, targetId)（POST /api/feign/content/favorite，userId 显式传参不复用 StpUtil，降级 fail-fast）+ ContentClientFallbackFactory 收藏条目；content 侧 ContentInternalService#favorite 委托 FavoriteService + ContentFeignProviderController 实现端点；AI 侧 XiaoyueTools 新增工具⑥ favoriteArticles（序号清单解析兼容「2、3」「2,3」「2 3」，批量上限 litisle.ai.agent.result-action-max-batch=10 超出明确说明未处理，逐条解析序号失败不影响其余条目，逐条明确成功/失败响应不静默吞掉，targetType=1 文章常量 AiContentConstant.FAVORITE_TARGET_ARTICLE）；system prompt 补「收藏动作只支持收藏文章（书单能力暂未开放）」；编译验证已通过（2026-11-16）

## 1.3 读后摘要工具

- [x] "第 1 篇讲了什么/帮我提炼要点"：按序号取正文 → 复用板块二摘要链路（purpose=SUMMARY 计量与预算照常）；长文按既有截断口径；文章不可见/已下架 → 明确响应
  - ✅ 2026-11-16：XiaoyueChatRequest 增 articleSeq 可空字段（序号引用与 articleId 二选一，入口校验 ③ 放宽）；buildIntentMessage SUMMARY 路由：articleId 缺省且 articleSeq 非空 → INTENT_POST_READ_SUMMARY（Agent 先经工具⑤序号解析定位（含可见性回查，已下架/不可见明确响应）→ 取稿工具获取正文 → 摘要产出 {"summary":...} 走 SuggestionValidator SUMMARY 校验与 ≤500 字口径）；计量 purpose=SUMMARY 照常（SUMMARY 桶配额/预算，Agent 多轮按轮落明细）；编译验证已通过（2026-11-16）

## 1.4 读后追问工具（单文 RAG）

- [x] "文中说的 X 是什么意思/这篇的论据有哪些"：复用 article_chunks_ai 按 articleId 过滤检索该篇切片 → 生成回答带引用（verifyChunk 引用回查铁律照搬：非已发布/非公开/contentHash 不一致即剔除）；问题超出单文范围 → 引导走全文问答入口，不混用全库检索
  - ✅ 2026-11-16：ChunkIndexClient#knnSearchByArticle（knn + articleId term filter 收窄召回范围）+ RagSearchService#retrieveArticlePassages（单文 knn + minScore 阈值 + verifyChunk 回查剔除/标注 + RagContext.record 引用卡片回传 + 片段使用规则注明单文边界与超范围引导）；XiaoyueTools 新增工具⑦ searchArticlePassages（RAG 未启用降级占位 Q54）；buildIntentMessage QA 路由：关联单文（articleId 或 articleSeq）→ INTENT_POST_READ_QA（单文边界/术语不编造/金句只摘原文/批判平衡呈现四项约束写入意图指令）；purpose=QA 计量照常；编译验证已通过（2026-11-16）

## 1.5 文章大纲导航（2026-11-16 追加）

- [x] "这篇的结构是什么/给我列个提纲"：复用 article_chunks_ai 按 articleId 取切片 → 拼装文章大纲（0~1 次 LLM，切片标题信息完整时零生成）；文章不可见/已下架 → 明确响应
  - ✅ 2026-11-16：ChunkIndexClient#listByArticle（articleId term query 取全量切片，内存按 chunkIndex 升序=原文段落顺序）+ RagSearchService#listArticleChunkTexts（逐片 verifyChunk 回查，不可见剔除）；XiaoyueTools 新增工具⑧ getArticleSequentialChunks（按序编号切片素材，无可用切片明确响应）；buildIntentMessage STRUCTURE 路由：articleSeq 引用 → INTENT_OUTLINE（序号定位 → 按序切片 → 拼装大纲 {"analysis":...}）；purpose=STRUCTURE 计量（大纲/结构同域桶）；编译验证已通过（2026-11-16）

## 1.6 术语卡片（2026-11-16 追加）

- [x] "这篇里的 X 是什么意思/这篇有哪些术语"：1.4 单文 RAG 轻量变体——抽提该文术语清单，逐个按切片上下文解释（1 次 LLM）；术语不在该文范围 → 明确说明不编造
  - ✅ 2026-11-16：复用 1.4 单文切片检索底座（工具⑦ + INTENT_POST_READ_QA 内「术语解释只限该篇出现的术语，术语不在该篇范围内时明确说明，禁止编造」约束）；purpose=QA 计量照常；编译验证已通过（2026-11-16）

## 1.7 批判性阅读陪聊（2026-11-16 追加）

- [x] "我觉得这篇观点有问题，帮我辩一辩"：1.4 读后追问的对话化延伸——检索该篇切片 + 平衡呈现双方论点（不预设立场、不迎合用户）；每轮 1 次 LLM，purpose 计量照常（桶位随 1.3/1.4 计量定档对齐）
  - ✅ 2026-11-16：复用 1.4 单文切片检索底座；INTENT_POST_READ_QA 内「批判性讨论要平衡呈现双方论点，不预设立场、不迎合用户」约束 + system prompt 读后服务规则同口径；桶位定档 purpose=QA（随 1.4 对齐）；每轮 1 次 LLM 由 MeteringAgentChatModel 按轮计量照常；编译验证已通过（2026-11-16）

## 1.8 金句摘录卡（2026-11-16 追加，契约依赖）

- [x] "把这篇的精华句子都摘出来"：单文切片检索 → 0~1 次 LLM 抽取原文金句（只摘原文不改写，不编造）；保存/落库依赖 content 笔记/摘录契约
  - 前置核对：content 笔记/摘录契约是否可经 Feign 复用（缺则转 temporarily 挂起，触发条件写明缺哪个契约；纯会话内展示不落库可先行）
  - ✅ 拍板（2026-11-16 前置核对结论）：content 笔记/摘录契约**不存在**——落库部分转 temporarily 挂起（缺 content 笔记/摘录契约，见 temporarily-01-code.md ai 节）；纯会话内展示先行
  - ✅ 2026-11-16（会话内展示部分）：复用 1.4 单文切片检索底座（工具⑦按需语义检索 / 工具⑧按序全切片素材均可支撑）；INTENT_POST_READ_QA 内「摘录金句只摘原文，不改写、不编造；保存摘录依赖的笔记契约暂未开放，如实说明」约束；purpose=QA 计量照常；编译验证已通过（2026-11-16）

## 依赖与登记说明

- 序号引用链路依赖：/ai/xiaoyue/similar 请求体增 sessionId 可空字段（会话内推荐/检索后记录结果集）——前端在会话内发起推荐时携带
- 运行态验证走 next_verify.md AI-V5（①~⑪ 验收口径）；content 收藏契约提供方端点随本批落地（ContentFeignProviderController#favorite）
- 书单动作与金句落库挂起项见 temporarily-01-code.md「litisle-ai 模块」节（触发条件：content 书单契约 / 笔记摘录契约立项后迁回）
