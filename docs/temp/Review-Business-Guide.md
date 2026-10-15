/e:/NewJavaPro/LitIsle/docs/temp/Review-Business-Guide.md

# LitIsle 审核模块业务指南（学习向）

> 定位：**快速理解审核域业务 + 学习各板块具体实现逻辑**，不承载决策推演（防绕过论证/竞态推演看

> [Review-Architecture-Development-detailed.md](Review-Architecture-Development-detailed.md)）。

>
> 速查配套：[Review-Architecture-Development.md](../Review-Architecture-Development.md)（结论版架构）

> · [review-index.md](../index/review-index.md)（类/配置/常量速查）· [api-index.md](../index/api-index.md)（契约速查）

>
> 阅读方式：每个板块固定三段式——**职责一句话 → 核心实现（真实类名+关键逻辑）→ 板块间关联**。

> 先读全景图，再按需跳板块。

## 〇、全景图：一次文章发布的完整旅程

发布请求 → ①同步 DFA 闸门（content 服务本地执行，HIGH 拦 70001，MID 标记放行）→ ②前置校验（空内容/标题≤100，标题随正文送审，content_hash=sha256(标题+正文)）→ ③落库待审 + 发 MQ（text.submit）。①②③的真实载体是 litisle-api 的 moderation 门面组件（见板块七），在宿主服务本地执行、不依赖 review 可用性→ ④机审消费（幂等 upsert → 缓存查重 → SPI 分片送 LLM → 三分态）→ PASS 放行 / REJECT 记账处罚+通知 / SUSPICIOUS 进人审队列 → ⑤人审认领 CAS → 裁决终态 CAS；作者可申诉 → 改判联动（缓存失效/举报回写/处罚撤销）→ ⑥sweeper 兜底（回池/重投/隔离清理/归档/对账）。
全程 moderation_record append-only 留痕；result / appeal.result / punish 三事件驱动下游服务。

## 一、同步 DFA 闸门（dfa 包 + litisle-api 契约）

**职责**：发布/注册/私信链路内毫秒级关键词拦截，不依赖 review 服务可用性。

**核心实现**：
- 引擎下沉 litisle-api：`DfaFacade.check(scene, text)` 返回 `DfaHitResult`（hit/maxLevel/hitWords/wordlibVer）；HIGH 拦 70001，MID 只标记（layer1_hit=2）
- `DfaScenes` 场景归一：去 `_RE_REVIEW` 后缀、FEATURED/COMMUNITY→ARTICLE；图片场景→null 不走词库
- review 侧 `TableWordLibraryLoader`（SPI 实现）查 sensitive_word_library 表供数据；快照装载 Redis 优先 + 本地持久化兜底，空词库拒绝构建（绝不 default-allow）
- `WordlibVersionService` 变更同事务 +1 版本再广播；`WordlibChangeSubscriber` 订阅 review:wordlib:change，各宿主 rebuild（version 乐观比对防乱序）

**关联**：板块五是词库数据源与热更触发方；MID 标记词仍进板块二机审二次判定。

## 二、异步机审（moderation 包）

**职责**：MQ 消费驱动 LLM/ONNX，输出 PASS/REJECT/SUSPICIOUS 三分态。

**核心实现**：
- 入口三消费者：`ModerationTextConsumer` / `ModerationImageConsumer` / `ModerationDlqConsumer`（手动 ACK；DLQ 消费失败一律 ack，任务停留 status=0 交板块六兜底）
- 幂等建任务：定位键 bizType+bizId+scene（uk_biz_scene），`ReviewTaskUpsertService` 统一 upsert——hash 变化/已终态→重置（清 assignee、retry 归零）；在审→只回填不打断已认领任务
- 缓存查重：`ModerationResultCache` 版本化 key（文本含 wordlibVer+fewShotVer+scene；图片含 provider+modelVersion），仅复用终局结论；未命中降级查 moderation_record（双层兜底）
- SPI 送审：`ContentModerationService` → LlmTextModerationAdapter（防注入三件套，解析失败宁疑不放）/ OnnxImageModerationAdapter（nsfw-mobilenetv2，reject≥0.80/suspicious≥0.60）；超长文本分片 overlap 硬切，任一片 REJECT 整文 REJECT
- 落库发布：三分态写 review_task + moderation_record → `ModerationResultPublisher` afterCommit 发 moderation.result（统一路由，消费端按 bizType 自筛）

**关联**：REJECT 触发板块四处罚联动；SUSPICIOUS 进板块三队列；图片 REJECT 写黑名单键供 file 上传闸门探测。

## 三、人审工作流（service 六域）

**职责**：SUSPICIOUS 终审 + 举报处理 + 认领/裁决工作流。

**核心实现**：
- 认领 CAS：`where id=? and status=2 and assignee is null`，affected=0 → 70004；在办配额 max-active-per-reviewer=20
- 裁决终态 CAS：`where id=? and status=2`，同事务写 decided_by/opinion/decide_time，终态特性天然防重复裁决
- `ReportService`：举报受理/复审（多举报同内容只回填不重置）/本人撤回（status<3→4）；诬告 abuse_flag 累计 3 次反向落警告+降信誉
- 机审留痕 handler 固定 0-系统、不判 abuse_flag

**关联**：裁决 REJECT 走板块四处罚；申诉由板块四接手。

## 四、申诉与处罚联动

**职责**：作者申诉改判 + 机审拒绝自动处罚 + 处罚状态跨服务同步。

**核心实现**：
- 改判七步编排（`AppealServiceImpl`）：入参闸门 → ADMIN+原裁决人回避 → 高风险 confirm 双签（mod_type=6 留痕）→ 改判 CAS → 同事务联动（处罚撤销 CAS 1→2 + violation_score 仅重放 action∈{1,2}）→ 缓存失效（`ImageAppealCacheInvalidator` 按留痕行重建键）→ 举报批量回写（biz→target 错位映射 5↔4、6↔5）→ afterCommit 发 appeal.result
- 机审处罚：`MachineRejectPunishGate` 统一发布收口（禁机审/人审双链路各写）→ `RejectPunishAdapterHost` 记账（参数化绑定，DuplicateKeyException 转累加）→ `PunishResultPublisher` sendCritical
- 消费端按 userId 串行消费 punish 事件并拉权威态，不依赖到达顺序；`PunishReconcileSweeper` 双向对账兜底

**关联**：改判 PASS 后缓存失效让板块二重新送审；处罚事件由 auth/user 消费强制下线。

## 五、词库运营（wordlib 域）

**职责**：敏感词 CRUD/导入导出/版本推进/误报上报。

**核心实现**：
- 版本 SSOT = sensitive_word_version（scene 级），变更同事务 +1 再广播（驱动板块一热更）
- 导入防 N+1（场景版本查询移出循环）、CSV 引号转义还原；导出防公式注入（=/+/-/@ 前置 '）
- 出参 `SensitiveWordVO`、更新 `WordUpdateDTO`（禁裸实体暴露内部字段）；删除后同事务 +1 并广播
- 误报上报：仅黑名单词可报；灰度转正数据源 = wordlib_misreport_total{scene}

**关联**：板块一的数据源与热更触发方。

## 六、sweeper 兜底与观测

**职责**：四大定时清扫 + 指标埋点，「绝不默认放行」的最后一道网。

**核心实现**：
- `ReviewTaskSweeper`：认领超时回池/申诉超时自动维持/三态 Gauge；每 30 轮分频 stuck 重投（status=0 超时任务按场景分流重发，dedup 短锁防双投，retryCount 不归零，与 DLQ 互斥）
- `QuarantineSweeper`：按保留期扫隔离图、申诉中豁免、限速删除
- `ModerationRecordArchiveSweeper`：04:00 错峰、id 区间推进、引用保护跳过，archive-enabled 默认关
- `PunishReconcileSweeper`：正向补踢复用 forceLogout、反向回查权威态覆盖本地快照
- 埋点：`ModerationMetrics`（total 仅落库入口计一次、cost 仅真实送审后记、标签仅 verdict·action·layer）+ `AlertThresholdMetrics`（告警阈值外置 yml）

**关联**：板块二 DLQ 与卡死任务的兜底出口；观测数据支撑板块五误报转正。

## 七、发布侧门面组件（litisle-api · moderation 包）

**职责**：发布/注册/私信链路决策口径的 SSOT，落在契约层供 content/auth/social 宿主服务接入，防止同一决策两侧各写漂移。

**核心实现**（10 组件，宿主经适配器调用）：
- 链路组装：`SubmitTextAssembler.assemble(title, body)` 统一送审文本与 contentHash（分隔符常量 TEXT_SUBMIT_SEPARATOR，发布/消费两侧共用——这是 MQ 事件体不含正文、消费端可回查复算的前提）；`PublishTextPrecheck` 空内容（trim+去标签可见文本）与标题≤100 校验
- 闸门决策：`DfaPublishGate` 封装 HIGH 拦 70001 / MID 只标记的 decide 结论（DfaGateDecision），替代宿主手写 if 分支；`UsernameRejectGate` 编排注册拒绝取证（取证失败仅留痕、不影响拦截结论）
- 免审判定：`LastHashStore` 读写删 last-hash（value 为 JSON 含 hash+verdict，SUSPICIOUS 禁写）+ `LastHashExemptChecker` 三种判定（decide 常规判定 / 状态排除 / 图片复审连带免审）——hash 未变 + PASS + 非驳回态才免审
- 私信域：`StrangerQuotaGate` 陌生人配额（Redis INCR 主计数，故障降级 DB 原子条件更新，上限 3）；`MessageRecallGate` 仅本人、仅正常态 CAS 撤回
- 联动收口：`MachineRejectPunishGate` 机审拒绝处罚统一发布口（板块四已详述）；`ContentUnpublishedInvalidator` 下架/删除/转私密时批量失效 text-result/img-result 与 last-hash 键

**关联**：全景图步骤①②③的真实载体；板块一 DFA 引擎的调用方；板块二缓存键的失效动作由 `ContentUnpublishedInvalidator` 承接（双向呼应：发布侧写免审键、下架侧删免审键）。