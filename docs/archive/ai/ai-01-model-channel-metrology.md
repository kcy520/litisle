> **归档说明**：新建板块文件（板块一，2026-11-15 归档）——迁自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」节，条目原样搬运（含口径修正与跨板块遗留标注），不压缩关键细节。
> **板块定位**：模型通道与计量配额基建——后续板块（Agent/RAG/机审）的共同前置。

## 板块一：模型通道与计量配额基建（后续板块的共同前置）

###### 1.1 OpenAI 兼容模型接入通道（provider 层）

- [x] 新建 provider/text 包实现统一文本模型通道：base-url / api-key / model / 超时 / 重试参数全部外置到配置（yml/Nacos），代码内不硬编码模型端点；prompt 模板与 prompt_version 常量集中管理（文本场景 prompt_version 由 model_version 承载）；provider 是唯一接触模型 SDK/HTTP 的层，controller/service 不直连模型
  - 业务逻辑：单一 chat 入口收 system+user 双段 prompt → 出站前按最小必要原则过滤（禁止附带用户身份信息）→ 调用失败 fail-fast 上抛，不静默吞错、不默认放行；日志只留标识/指纹/必要片段，不打印正文全文
  - ✅ 2026-11-15：TextModelProvider 接口 + OpenAiCompatTextModelProvider（LangChain4j 1.23.0 官方 BOM + langchain4j-open-ai，DeepSeek OpenAI 兼容直连）+ AiProviderProperties（litisle.ai.provider.text.* 外置）+ AiModelConfig 装配；跨板块遗留：①未公开稿件脱敏过滤器随板块二 2.2 接入 provider 出站前 ②生成式 prompt/prompt_version 常量集中管理随板块二（AI 侧当前无私有 prompt）③P2「starter 自动装配」落地为显式 @Bean + enabled 开关（本地无 api-key 可启动，通道缺席端点抛明确错误码），其余 BOM/OpenAI 兼容/配置外置口径不变；编译验证已通过（2026-11-15）

###### 1.2 用途打标契约扩展（实施落 litisle-api）

- [x] litisle-api `dto/ai` 包新增 AiPurposeEnum（MODERATION_TEXT / MODERATION_IMAGE / SUMMARY / TAGGING / QA / OTHER 六值）；AiChatRequestDTO 增 purpose（必传，缺省端点侧拒绝并告警，防「忘传」被默认归机审）、requestId（业务侧生成的幂等键，重试复用同一键）、scene（可空）
  - 计费与预算只认前五类；OTHER 仅兜底落明细、不计入预算与报表，出现即按「未归类调用」告警；用途由调用方显式传入，不靠 URL/模型名/表名推断
  - ✅ 2026-11-15：AiPurposeEnum（@JsonValue/@JsonCreator，未知 code 反序列化为 null 由端点侧统一拒绝 + isMetered() 计费标记）；AiChatRequestDTO 增 purpose/requestId/scene 三字段；common-core ErrorCode 增 AI 段 80001~80005（purpose 缺失/requestId 缺失/额度用完/预算用尽/通道不可用）；跨板块遗留：review 侧 LlmTextModerationAdapter 传参改造（purpose=MODERATION_TEXT + requestId=taskId:chunkIndex）归属 review 模块（见 1.4 跨模块牵连），review 现有调用未传 purpose 将被端点侧拒绝 → 仍走 SUSPICIOUS 转人工，与「提供方缺失」现状等价无回归；编译验证已通过（2026-11-15，含 litisle-review 编译）

###### 1.3 Token 计量与预算分账基建

- [x] 落地 ai_token_usage / ai_usage_daily / ai_pricing 三表实体与 Mapper（表结构已建：`docker/init/mysql/schema/ai-schema.sql`，2026-11-09）；实现 AiUsageRecordService 写明细、AiBudgetService 预算桶判定、AiUsageDailyAggregateTask 定时聚合
  - 明细口径：幂等键 = requestId + 调用序号（callSeq）联合唯一——一次业务请求可触发多次模型调用（Agent 多轮、机审切片），每轮/每片各落一条明细；重试复用同一 callSeq 键不重复计费（修正架构文档 §8.3④「request_id 唯一」的口径：该设计只覆盖了切片场景，Agent 多轮会撞键漏计；落地时须回写架构文档）；失败调用也落一条 status=FAIL（避免「失败即无成本」）；归属日期按调用完成时间，跨天重试只计一次成功成本；成本 = token 数 × ai_pricing 单价（两位小数），单价配置变更只影响未来日期
  - 用户级配额（D5/V8）：按 用户×用途×日 校验次数/Token 上限，普通低配、按等级放开、VIP 高额度非不限量；超额拒绝并提示「今日额度已用完」；数值待定档（Q39），未定档前仅计量不拦截
  - 预算口径（D15 分账）：机审桶 = 文本+图片合并；SUMMARY/TAGGING/QA 各自独立桶；判定读 ai_usage_daily 当日合计、本地缓存 60 秒，不在每次调用读写预算键；返回 ALLOWED / WARN(≥80%) / EXCEEDED(100%)；预算值来自 AiBudgetProperties，≤0 表示未设预算只计量不限额；100% 只停该用途新请求（机审桶到 100% 不停调用、转全量人工+告警），生成被停返回明确错误码，不静默返回空结果
  - 聚合口径：每小时 + 启动延迟一次，对当日与前一日幂等覆盖写（保证预算判定当日数据可用）；明细与汇总差异 >1% 记日志告警
  - ✅ 2026-11-15：三表实体（AiTokenUsage/AiUsageDaily/AiPricing）+ 三 Mapper（聚合 INSERT...SELECT...ODKU 覆盖写 / 窗口对账 @Select）；AiUsageRecordService（DuplicateKeyException 捕获跳过 = upsert 幂等；成本按归属日取最新 effective_from 单价核算，分页插件取 1 条不拼 LIMIT）；AiBudgetService（桶映射 + 60s 本地缓存，仅判定，执行语义收口在 AiChatService）；AiUserQuotaService（次数/Token 双维校验，≤0 未定档不拦截）；AiUsageDailyAggregateTask（@Scheduled 每小时 + 启动 30s 一次，@Repeatable 双注解）；架构文档 §8.3④ 口径已于 2026-11-15 修订完成（request_id+callSeq 联合唯一），ai-schema.sql 同步增 call_seq 列 + uk(request_id,call_seq)——**已有本地库需手动执行 ALTER**（ALTER 语句见 ai-schema.sql 注释）；跨板块遗留：用户配额等级/VIP 放开系数随板块二接线；编译验证已通过（2026-11-15）

###### 1.4 内部 Feign 端点（解锁 review 文本机审闭环）

- [x] 实现 POST /api/feign/ai/chat 提供方端点（契约 AiClient.chat 与 AiClientFallbackFactory fail-fast 已在 litisle-api 实现，AI 侧当前无 controller）；AI 只回结构化判定结论，防注入包裹与白名单三分态映射由 review 侧组织；接线后 review 文本机审从「提供方缺失→全量转人工」恢复为 LLM 判定
  - 跨模块牵连（归属 review 模块，需在 review 侧另行登记）：SPI ContentModerationService.moderateText 改收 TextModerationRequest；requestId = taskId:chunkIndex（分片确定性切分，延迟重试复用同一 requestId 满足「重试不重复计费」）；purpose 固定 MODERATION_TEXT；scene 取 review_task.scene
  - ✅ 2026-11-15：AiChatController（/api/feign/ai/chat，Result 包装）+ AiChatService 编排（purpose/requestId 缺省拒绝并告警 → OTHER「未归类调用」告警放行 → 预算桶判定 → provider fail-fast 调用 → 成功/失败均落计量明细 → 组装响应）；生成类 EXCEEDED 抛 80004、机审桶 EXCEEDED 告警不停调用；callSeq 本通道固定 1（requestId 已按调用单位唯一，Agent 多轮在 service 层直调 provider 按轮次传 seq，不经本端点）；模型通道未启用（enabled=false）时端点抛 80005；跨板块遗留：review 侧传参改造（同 1.4 牵连项，归属 review 模块另行登记）——改造前 review 调用会被 80001 拒绝转 SUSPICIOUS 人工，与现状等价无回归；编译验证已通过（2026-11-15）

###### 1.5 AI 侧指标与日志字段统一

- [x] 建立指标打点：ai_call_total{purpose,provider,result} / ai_call_latency{purpose}（Timer，P50/P95/P99）/ ai_tokens_total{purpose} / ai_quota_exceeded_total{purpose} / ai_generation_total{scene,result} / ai_guard_state{provider}；命名沿用 review 的 moderation_* 风格（代码侧点号、Prometheus 下划线）
  - 按用户用量不加指标标签（防高基数），统一由 ai_usage_daily 承担；日志字段统一 traceId / purpose / modelVersion / costMs / tokenIn / tokenOut / result，禁止打印正文全文、私信内容与用户身份信息
  - ✅ 2026-11-15：AiMetrics（MeterRegistry ObjectProvider optional 模式，对齐 review 惯例；代码侧点号 ai.call.total 等，Prometheus 渲染下划线）——ai.tokens_total 增 direction=in/out 标签；ai.guard_state{provider} Gauge 绑定 provider 健康标记（成功置 1/失败置 0）；pom 增 actuator + micrometer-registry-prometheus，yml 暴露 health,prometheus + application 标签；1.4 服务已接线 call/latency/tokens/quota 打点；ai_generation_total 打点方法就绪（生成端点随板块二接线）；日志字段纪律落在 AiChatService/OpenAiCompatTextModelProvider（标识+字符数+token+耗时，不打印正文）；编译验证已通过（2026-11-15）
