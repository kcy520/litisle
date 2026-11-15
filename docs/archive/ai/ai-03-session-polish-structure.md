> **归档说明**：新建板块文件（板块三，2026-11-15 归档）——迁自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」节，条目原样搬运（含口径修正与跨板块遗留标注），不压缩关键细节。
> **板块定位**：小悦有状态 Agent——会话与消息实体/治理 + Redis ChatMemoryStore + 润色/结构分析端点（P4 分层定档：DB 全量存 + Memory 滑窗两层不冲突）。

## 板块三：小悦有状态 Agent——会话记忆与润色/结构分析

###### 3.1 会话与消息实体 + 会话治理

- [x] ai_session / ai_message 实体与 Mapper（DDL 以 Sql-Document §五为准）；GET /ai/sessions 会话列表
  - 治理铁律（D25）：留存 90 天；90 天内 200 会话上限、单会话 500 条上限，超限按最久未活跃（last_active_time）LRU 淘汰（is_lru 标记）
  - 删除铁律：支持用户删除——删除请求由 AI 侧受理（会话数据持有方），异步任务 + 完成回执 + 审计记录；懒删之外补定时兜底清扫，防用户停用后超期留存（R9 缺口）
  - ✅ 2026-11-15：建表落地 ai-schema.sql（ai_session + ai_message 两表 2026-11-15 随本板块新增建表，DDL 以 Sql-Document §五为准，已有本地库重跑脚本即可补建）；AiSession/AiMessage 实体 + AiSessionMapper/AiMessageMapper（BaseMapper）；AiSessionService 收口会话全部读写——创建（POST /ai/sessions，暂存区 is_lru=1 默认，达 session-stash-limit=200 上限时按 last_active_time LRU 淘汰最久未活跃：软删懒删）/列表（GET /ai/sessions，userId 过滤按活跃倒序）/归属校验（跨用户按不存在处理防会话存在性泄露）/消息落库（单会话 session-message-limit=500 条上限，超限拒绝并提示开新会话）/活跃刷新 touch/删除受理（DELETE /ai/sessions/{sessionId}——AI 侧受理软删懒删 del_flag=1，会话行保留作审计留痕，Redis 记忆暂存联动清理）；AiSessionCleanupTask 兜底清扫（每日 03:30 + 启动 60s 补跑）：留存 90 天（session-retention-days）的未删会话物理删除（行+级联消息，分批 200 防大事务）+ 软删超 7 天（session-purge-delay-days）懒删兜底物理清理，R9 缺口补齐；「完成回执」口径：删除端点同步受理返回即回执（物理清理为异步兜底任务，失败下轮重试自愈）；跨板块遗留：小审判 XIAOSHEN 会话复用本表（agent_type 字段已预留）；编译验证已通过（2026-11-15，mvn -pl litisle-ai,litisle-review -am compile）

###### 3.2 Redis 会话记忆（ChatMemoryStore 实现）

- [x] 实现 LangChain4j ChatMemoryStore 的 Redis 版（衔接 2.1 底座）：会话上下文暂存（Design-Document §2.3.6 口径：LRU 暂存 10/30 + 已保存 100/200）；Memory 不可用降级为无记忆单轮回答并明确标注，不静默丢上下文
  - ✅ 2026-11-15：RedisChatMemoryStore（memory 包，implements ChatMemoryStore：getMessages/updateMessages/deleteMessages，ChatMessageSerializer/Deserializer JSON 序列化，key=ai:memory:{memoryId} TTL=memory-ttl-minutes 30 分钟）；AiAgentConfig 装配补 chatMemoryProvider（MessageWindowChatMemory 窗口 memory-window-size=10 外置——LRU 暂存 10 条进模型口径；Redis 只存窗口消息过期即弃，与 DB 全量存档两层口径不冲突：Redis 服务模型上下文、ai_session/ai_message 服务列表与审计）；XiaoyueAgent 接口加 @MemoryId 参数——memoryId=sessionId（有会话多轮记忆）或 tmp:requestId 一次性键（无会话单轮，TTL 30 分钟自然过期不跨请求，消息不落库）；降级铁律：Redis 异常 getMessages 返回空列表（无记忆单轮回答）+ log.warn 明确标注告警，updateMessages 失败不中断回答，不静默丢上下文；「已保存 100/200」口径：is_lru=0 已保存区字段与治理参数预留（保存动作端点未在 3.1 清单要求，暂不实现，触发条件见 temporarily 汇总）；编译验证已通过（2026-11-15）

###### 3.3 润色与结构分析端点

- [x] POST /ai/xiaoyue/polish、POST /ai/xiaoyue/analyze-structure：润色建议与结构分析建议，带 session_id 走 3.2 Memory 支持多轮；同时作为 2.3 意图路由的分流目标之一；仅作者可见（建议态），同 2.5 的闸门/标识/计量/幂等口径
  - ✅ 2026-11-15：XiaoyueController 增 /polish、/analyze-structure（purpose 端点定死覆写 POLISH/STRUCTURE 防 client 误传，P6 入口预传入口径不变）；AiPurposeEnum 扩展 POLISH/STRUCTURE 两值（isMetered=true；D15 原文仅列前五类，扩桶口径=D15「生成类各自独立桶」精神延伸，文档口径修订随板块三回写），AiBudgetService/AiBudgetProperties/AiQuotaProperties/AiUserQuotaService 同步扩 polish/structure 独立桶 + yml 配置；Agent 路径走 XiaoyueChatService.chat（sessionId 非空 → 归属校验+USER 消息落库+touch → Agent memoryId=sessionId 多轮记忆 → 终答 ASSISTANT 落库带轮次 token 对账；sessionId 为空 → 一次性 tmp 键不落库）；直连固定管道：XiaoyueSuggestionService 增 polishDirect/structureDirect + polishFallback/structureFallback（润色无稿件可润色选中文本 articleId/message 至少一项；结构分析 articleId 必传基于全文）；SuggestionValidator 增 POLISH/STRUCTURE 校验（JSON 字段 polished/analysis 非空）；同 2.5 口径：建议态 aiGenerated 标识/闸门兜底由调用方执行/按轮计量 requestId+callSeq/P7 缓存幂等回查；跨板块遗留：网关 /api/ai/** 前缀与新增端点须 Api-Architecture 定稿登记；编译验证已通过（2026-11-15）
