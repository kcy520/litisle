> **归档说明**：新建板块文件（板块二，2026-11-15 归档）——迁自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」节，条目原样搬运（含口径修正与跨板块遗留标注），不压缩关键细节。
> **板块定位**：小悦 Agent 底座——AiServices 装配 + 工具注册 + 意图路由 + 结构化自校验 + 摘要/标签建议（Agent 化核心）；会话记忆随板块三 3.2 补齐。

## 板块二：小悦 Agent 底座——AiServices 装配 + 工具注册 + 意图路由（Agent 化核心）

###### 2.1 LangChain4j Agent 底座装配

- [x] 引入 LangChain4j，用 AiServices 装配小悦 ChatAgent：system prompt（角色定位 + 能力边界 + 建议态约束）+ ChatMemory 接口（实现见 3.2）+ Tools 注册；模型调用复用 1.1 provider 层；Agent 不直接写业务库，产出一律为建议态，落库决策在 service
  - 业务逻辑：用户消息 → Agent 推理（自主决定调哪个工具、调几次）→ 工具结果回喂 → 终答；Agent 循环设上限（初值 5 轮）防死循环与成本放大；工具失败作为观察结果回喂模型自行调整，不直接中断会话
  - ✅ 2026-11-15：pom 增 dev.langchain4j:langchain4j 主模块（BOM 1.23.0 管理）；AiAgentConfig 用 AiServices.builder 装配 XiaoyueAgent 接口（chatModel 计量装饰链 + systemMessageProvider 注入 AiPromptConstants.XIAOYUE_SYSTEM_PROMPT + tools 注册 + maxSequentialToolsInvocations 框架层护栏），enabled=false 不装配（端点抛 80005 与机审通道同口径）；双重循环护栏：框架 maxSequentialToolsInvocations + MeteringAgentChatModel（agent 包）按轮计数强制上限（超限抛 80007 零成本 fail-fast，由 service 降级），轮次上限外置 litisle.ai.agent.max-loop-rounds=5；每轮计量 requestId+callSeq 落明细（AgentCallContext ThreadLocal 承载，成功/失败均落，token 指标由 AiUsageRecordService 统一打点不重复计数）；ChatMemory 未装配（板块二无状态单次对话含工具循环，Redis ChatMemoryStore 随板块三 3.2 补 chatMemoryProvider）；跨板块遗留：会话记忆接线（板块三 3.2）；编译验证已通过（2026-11-15，mvn -pl litisle-ai,litisle-review -am compile）

###### 2.2 能力工具注册（Function Calling）

- [x] 将小悦的能力拆成 tools 注册给 Agent：①取当前稿件标题+正文（经 ContentClient 回查）②查标签池候选（经 ContentClient）③敏感词预检（复用既有 DFA 闸门能力；若无内部端点则先占位，闸门兜底仍由调用方执行）④知识库检索（板块四落地前注册占位实现，未就绪返回「工具暂不可用」，模型改走无检索回答）
  - 铁律：工具只读、不写业务表；工具出入参日志不带正文全文；purpose 按意图打标（SUMMARY/TAGGING/QA），计入 1.3 计量预算
  - 出站红线（Q63 已定档，2026-11-15）：选「边界修订」路线——未公开稿件经**脱敏过滤器**（手机号/邮箱/身份证/外部链接）后，外发给签约合规的国内模型供应商（API 协议含数据处理条款），并在用户协议中告知同意；脱敏在 AI 供给层（provider 出站前）统一执行；不搞私有化部署；稿件外发保留配置开关，可一键关闭
  - ✅ 2026-11-15：XiaoyueTools（agent/tools 包，@Tool 注解四工具全注册）——①getArticleDraft 经 ContentClient.getContent(bizType=1) 回查 ②getTagPool 经 getTagSuggestions 前缀查池 ③sensitiveWordPrecheck 占位（DFA 闸门无内部端点）④searchKnowledgeBase 占位（板块四 4.2 转正）；工具失败统一返回观察文本回喂（不抛异常中断会话），日志只记长度与标识不带正文；启动类 @EnableFeignClients(clients=ContentClient.class) 显式登记（不用 basePackages 扫描——避免自注册 AiClient）；P1 脱敏落地：OutboundSanitizer（provider/text，手机号/邮箱/身份证/外部链接四类正则替换为占位符）+ DesensitizingChatModel ChatModel 装饰器（system/user/ai/工具结果四类消息出站统一脱敏，覆盖机审+Agent 全通道），AiModelConfig 装配链=DesensitizingChatModel→OpenAiChatModel（容器唯一 ChatModel Bean），外发开关 litisle.ai.agent.desensitize-enabled 默认 true 可一键关闭；编译验证已通过（2026-11-15）

###### 2.3 意图路由与降级直连

- [x] 单一入口 POST /ai/xiaoyue/chat：Agent 首轮判断意图（摘要/标签/润色/结构分析/站内问答/闲聊）自主路由；原设计锚点端点（/ai/xiaoyue/summary、/tag 等）保留为固定管道直连通道，Agent 循环异常时降级走直连
  - 降级铁律：Agent 循环超限/模型不可用 → 降级为固定管道或「暂时无法生成」提示（可手动填写，不落库）；purpose 打标与预算判定在路由后即执行，降级链路同样计量
  - ✅ 2026-11-15：XiaoyueController（/ai/xiaoyue/chat|summary|tag，路径对齐 AI-Architecture §7.1；网关 /api/ai/** 前缀落地前须 Api-Architecture 定稿登记）+ XiaoyueChatService 统一入口编排——purpose 入口预传入（P6 定档，不做路由 LLM 调用），当前支持 SUMMARY/TAGGING/QA（润色/结构随板块三扩充，非法 purpose 拒 80001）；前置链=用户配额 tryAcquire（80003，1.3 用户配额遗留项随本板块接线）→ 预算判定（EXCEEDED 拒 80004，WARN 提醒）→ 建议缓存回查（命中直接返回不重复计量）→ AgentCallContext.begin；降级铁律落地：Agent 抛 80007（循环超限）/80005（通道异常）→ leaveAgentPhase 解除循环约束（callSeq 连续累计）→ 转直连固定管道（降级明细 degraded=1）；降级也失败 → degraded 响应「暂时无法生成，可手动填写」不落库不抛异常；跨板块遗留：QA 引用卡片/匿名问答配额（板块四 4.2）；编译验证已通过（2026-11-15）

###### 2.4 结构化输出与自校验循环

- [x] 标签候选、摘要等结构化产出要求模型输出 JSON：解析失败或校验不过（标签越池、摘要超 500 字）时，把具体错误信息回喂模型重试 1 次（最小反思闭环）；仍失败降级为失败提示，不落库
  - 重试预算固定 1 次，防止反思循环成本放大；校验规则（池内白名单、长度上限）在 service 侧执行，不依赖模型自觉
  - ✅ 2026-11-15：SuggestionValidator（service/support，校验规则 service 侧统一收口，Agent 输出与直连管道共用）——SUMMARY 校验 summary 非空 ≤summary-max-chars；TAGGING 校验 tags 数量 ≤max-tag-count 且逐一经 ContentClient 精确比对池内白名单（标签服务不可用按越池拒绝，防新造标签漏放）；QA 非空自由文本；解析容忍 ```json 围栏与前后夹带说明文字；回喂闭环：校验错误（含越池标签名单/超出量）经 STRUCTURED_RETRY_FEEDBACK 模板回喂重试 1 次（structured-retry-budget=1 外置），仍失败降级 degraded 响应不落库；Agent 路径回喂轮由计量装饰模型正常计量（callSeq 递增）；编译验证已通过（2026-11-15）

###### 2.5 摘要与标签建议（Agent 编排实现）

- [x] 在 Agent 底座上实现摘要（≤500 字）与标签建议：由模型经 2.2 工具自主取稿、查池、预检后产出建议
  - 建议态铁律（D4）：不自动写入，作者编辑/确认后由 content 落库；未确认则丢弃不落库
  - 展示铁律（D3/D13）：响应带「AI 生成/AI 建议」标识；展示前须过 DFA 闸门（闸门由调用方 content/review 执行，机制已有）；摘要不进正文指纹、不触发正文重审
  - 失败语义：失败重试 1 次 → 终态 → 退还预扣配额，同一 requestId 只扣一次
  - 标签铁律：AI 不新造标签，prompt + 越池校验双重约束；新标签只能作者走正常创建流程
  - ✅ 2026-11-15：直连端点 /ai/xiaoyue/summary、/tag（XiaoyueSuggestionService 固定管道：取稿→模板 prompt→单次 provider 调用→自校验回喂重试 1 次→终态；P10 定档一次性动作不建会话不写 ai_session/ai_message）；建议态 P7 缓存落地：SuggestionCacheService（Redis key=ai:suggestion:{requestId} TTL 30 分钟外置，只缓存成功建议，命中直接回查不重复计量；Redis 异常 best-effort 静默降级不影响主链路；D4 未确认建议到期即弃——缓存不是落库）；计量接线：SUMMARY/TAGGING 各走独立预算桶，Agent 每轮与直连每轮均按 requestId+callSeq 落明细（降级明细 degraded=1），ai.generation.total{scene,result} 打点随本板块消费；展示标识：响应恒 aiGenerated=true + message 提示文案（degraded 时 false+失败提示）；「退还预扣配额」不适用——配额为判定式（读日汇总非预扣），无预扣可退还；同一 requestId 只扣一次由建议缓存幂等回查保证；编译验证已通过（2026-11-15）
