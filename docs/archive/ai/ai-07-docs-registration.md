# 板块七：文档收尾与登记（实现记录归档）

> 归档日期：2026-11-16（板块七为契约与文档类任务，落地即收口）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」板块七，条目原样搬运不压缩
> 涉及文档：[AI-Architecture.md](../../Architecture/AI-Architecture.md) · [AI-Business-Guide.md](../../Study/AI-Business-Guide.md) · [Api-Architecture.md](../../Architecture/Api-Architecture.md) · [Gateway-Architecture.md](../../Architecture/Gateway-Architecture.md) · [temporarily-01-code.md](../../next/temporarily/temporarily-01-code.md)

##### 板块七：文档收尾与登记（板块五/六落地后的配套，契约与文档类）

###### 7.1 新端点登记与网关前缀定稿

- [x] Api-Architecture 定稿 /ai/xiaoyue/similar、/ai/usage/me 及运营侧用量端点的路径与出入参；网关 /api/ai/\*\* 前缀登记（原 temporarily-01「网关登记/架构文档登记」挂起项随板块五/六完成触发，落地后从挂起清单移除）
  - ✅ 2026-11-16：AI-Architecture §7.1 端点清单定稿——全端点路径 + 出入参 DTO 级登记（统一入口 /ai/xiaoyue/chat、直连 summary/tag/polish/analyze-structure、相似推荐 /ai/xiaoyue/similar（XiaoyueSimilarRequest→List\<SimilarArticleCard>）、/ai/sessions 三端点、用量三端点 /ai/usage/me（AiUsageMeResponse）/me/details（days 1~7/pageNo/pageSize 上限 100→List\<AiUsageDetailItem>）/summary（start/end 区间上限 31 天→AiUsageSummaryResponse，StpUtil.checkRole(ADMIN)）、内部 /api/feign/ai/chat），状态符号 🟡→✅（小审判 /ai/xiaoshen/judge 保留 🟡、场景开关动态调整端点保留 ⬜）；网关 /api/ai/\*\* 前缀已在 Gateway-Architecture §2.1 业务路由表登记（litisle-ai → /api/ai，lb:// 服务发现），并在 AI-Architecture §7.1 与 Api-Architecture §4.5 补充互链口径（用户侧端点经 /api/ai/\*\* 转发、机审 Feign 不经网关）；Api-Architecture §4.5 标题与 §2 包结构树同步 AiClient 提供方端点已实现状态（2026-11-15）；纯文档改造无编译面

###### 7.2 purpose 枚举与预算桶扩展（D15 修订收口）

- [x] AiPurposeEnum 扩 SIMILAR（六值 → 七值）+ AiBudgetProperties 扩桶 + AI-Business-Guide §5.3「该五类为计量打标的唯一定义」同步更新为六类；D15 修订挂起项随本任务关闭
  - ✅ 2026-11-16：代码侧 AiPurposeEnum 扩 SIMILAR + 四处预算/配额桶映射已随板块五 5.1 完成（2026-11-15）；文档侧——AI-Business-Guide.md 确认已完整（897 行，与 git 提交 0106d4a 恢复版一致，无需恢复），§5.3「成本可见」行由「五类为计量打标的唯一定义」修订为**八类**（机审文本/机审图片/摘要/标签/问答/润色/结构分析/相似推荐，枚举载体 litisle-api AiPurposeEnum，OTHER 仅兜底落明细不计费不计预算），并补预算分账口径（机审合并桶 + 生成类含相似推荐各自独立桶）；D15 决策行「用途枚举以 §5.3 五类为准」与 §16.3.4「口径见 §5.3 五类用途」两处引用同步为八类；D15 修订挂起项已在 temporarily-01-code.md 标注 ✅ 已回收；纯文档改造无编译面

###### 7.3 架构文档状态同步

- [x] AI-Architecture §2.3 / §3.2 / §4.1 / §5.5 / §7.1 登记板块五/六新增能力与类（RagSearchService.similarArticles、用量查询服务、provider 层熔断自检、ai\_guard\_state 指标、/ai/xiaoyue/similar 与用量端点），状态符号随实现推进由 🟡 改 ✅
  - ✅ 2026-11-16：§2.3 组件清单全面核对同步——新增「相似文章推荐（F8）」「用量查询与出账（F9）」「provider 层熔断自检」三行 ✅，板块零~四既有各行 🟡→✅（AiLimiter 标 ⬜ 未立项：限流语义由判定式配额与 review QPS 护栏承担），节标题改「组件清单（板块零~六已落地）」；§3.1 改写为实际包结构树（按 2026-11-16 代码核对，agent/client/config/constant/controller/domain/dto/enums/mapper/memory/metrics/mq/provider/rag/service/task 全量 ✅）、§3.2 由「规划结构」改为「与规划的差异及剩余待建项」（guard 包未建——熔断收口 provider 层装饰器；provider/image 未建；XiaoshenController 未建；用途枚举实际落位 litisle-api）；§4.1 组件总表 🟡→✅ 并增相似推荐/用量查询两行，站内问答依赖描述同步 P9 自管 article_chunks_ai 口径；§5.5 链路状态 ✅ 化并补熔断自检 bullet（CircuitBreakerChatModel 80005/降级链/半开切回），新增 §5.6 相似文章推荐链路（板块五全链路流程图）；§7.1 定稿（见 7.1）；§7.3 补登记 AI 消费 content.published/content.unpublished 两队列（RagIngestConsumer，AiRagMqConfig 条件装配）并修正「AI 不生产不消费 MQ」为「只消费不生产」；§7.4 幂等/生成式重试语义 ✅ 化（判定式配额不预扣无退还语义、structured-retry-budget=1）；§9 可靠性熔断/隔离行改写为 provider 层熔断自检实现口径（Q52 原 Redis 计数口径由状态机替代），RAG 不可用/生成失败/告警定位/限流配额/幂等各行 ✅ 化；§11.1 六个 ai\_\* 指标 🟡→✅（ai_guard_state 语义改熔断状态，由 CircuitBreakerChatModel 持有）；配套：temporarily-01-code.md「litisle-ai 模块」节三项挂起（网关登记 / §7.3 队列登记+§7.1 端点核对 / D15 桶口径修订）标注 ✅ 已回收（2026-11-16）；纯文档改造无编译面
