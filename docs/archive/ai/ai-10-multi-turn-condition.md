# ai 板块十：多轮调条件（原 temporarily-04 挂起项拉回）

> 归档日期：2026-11-16（板块十 2.1 全部落地，运行态验证走 [next_verify.md](../../next/next_verify.md) AI-V5 ⑤）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」板块十节，条目原样搬运不压缩
> 依赖：板块九 1.1 会话结果集地基（sessionId 入参 / 会话暂存 TTL 口径 / 会话删除联动清理模式）+ 板块八 1.2 条件提取管道（本板块为其增量扩展，不新建管道）

## 2.1 会话记忆增量改条件

- [x] "再轻松一点/时间改成最近半年"：会话内保存上一轮提取 JSON → 本轮 LLM 输出增量修改 → 合并后走注册表校验与条件检索（复用板块八管道）；每轮仍 1 次 LLM；首轮无上下文时按普通提取处理；合并冲突（本轮否定上轮条件）以上轮为准由本轮覆盖
  - ✅ 2026-11-16：SessionConditionStoreService（Redis key=ai:cond-extract:{sessionId}，String 存上一轮条件 JSON，TTL 外置复用 result-set-ttl-minutes=60 会话暂存口径；Redis 异常降级为无上下文按普通提取处理并告警不中断主链路；会话删除联动 evict——AiSessionService.deleteSession 与 SessionResultRegistry 并行清理）；ConditionExtractService 增 extract 四参重载（previousConditionJson 合并基线，原四参签名委托重载传 null 保持兼容）+ buildIncrementExtractPrompt（CONDITION_EXTRACT_INCREMENT_PROMPT_FRAME）+ buildDimensionLines 抽取共用（普通/增量提取共用同一注册表维度清单拼装）；SimilarRecommendService.similarWithCondition 接线：会话请求 load 基线 → extract(freeText, previousJson) → 成功后 saveConditionContext（objectMapper 序列化 ExtractedSearchCondition 暂存，失败降级不保存不阻断）；合并结果为空（hasAnyCondition=false）evict 条件上下文 = 下一轮按普通提取处理；计量每轮仍 1 次 LLM（purpose=SIMILAR 桶照常，回喂重试轮次按 callSeq 递增口径不变，增量轮仅 prompt 变长 token 微涨由桶管控）；编译验证已通过（2026-11-16）

## 设计口径（随落地定档）

- **合并语义收口在 prompt 不做机械合并**：上一轮条件 JSON 作为基线写入增量模板，模型按规则输出「合并后的完整条件 JSON」——本轮明确修改/否定的字段以本轮为准覆盖上轮（否定输出 null），未提及字段沿用上轮；service 侧不维护字段级 diff，合并产物与普通提取完全同口径过注册表词表/区间/topK 校验与回喂重试（校验兜底不依赖模型自觉的既有铁律不变）
- **跨会话不可引用**：条件上下文按 sessionId 维度隔离（工具/管道只读当前会话基线），一次性请求（sessionId=null）load 直接返回 null 按普通提取处理
- **上下文失效语义**：键不存在（首轮/TTL 过期/Redis 异常）统一按普通提取处理；TTL 外置（litisle.ai.agent.result-set-ttl-minutes=60，与会话结果集同源口径）
- **空合并结果**：本轮合并产物全字段空 → 降级 L0 标注（板块八既有行为）+ evict 条件上下文（下一轮按普通提取，不残留空基线）
