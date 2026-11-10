# LitIsle Content 模块业务指南（学习向）

> 定位：**快速理解内容域业务 + 学习各板块实现逻辑**，不承载决策推演（防绕过论证/竞态推演看

> [Content-Architeche.md](../Architecture/Content-Architeche.md)）。

> 阅读方式：每个板块固定两段——**详细版**（真实类名+关键逻辑）→ **通俗版**（无代码名）。先读全景图，再按需跳板块。

## 〇、全景图：一篇文章从创作到可见

创作（草稿快照 + 正文图片占位）
→ 发布同步段（校验 → 免审直落 → CAS 0→1 发送审事件）
→ review 异步机审（切片/聚合在 review 消费端收口，content 不重复实现）
→ 结果回写（双链路聚合：文本 + 配图复审）
→ PASS 发布 / REJECT 首发驳回 1→3 或复审下架 2→4 → 申诉改判 3/4→2 / 封禁连带下架。\\

**四条下线路径**（软删/转私密/复审驳回/封禁连带）统一发 content.unpublished + 失效三键审核缓存（隐私红线）；发布成功发 content.published 供 search/ai/notification 消费。

## 架构设计

```
litisle-content
└─ com.litisle.litislecontent
   ├─ controller              # HTTP 入口（文章/发布编排/草稿/书籍/收藏/阅读历史/标签/推荐流端点）
   │  └─ feign                # 对外 Feign 提供方（review 回查原文等跨服务端点）
   ├─ service                 # 业务编排（发布/文章/草稿/书籍/进度/标签/推荐流/回写/内部）
   │  ├─ impl                 # 接口实现
   │  └─ support              # 领域支撑组件（热榜/打分/快照/画像/曝光/频控、浏览与阅读计数、客户端 IP 解析等；顶层 support 包已收敛至此）
   ├─ domain
   │  ├─ entity               # 表实体（article/article_draft/article_image/book/tag 等）
   │  ├─ dto                  # 入参对象（发布/编辑/存稿/挂章节等）
   │  ├─ vo                   # 出参对象（详情/卡片/回执等）
   │  ├─ statemachine         # ArticleStateMachine：status 流转白名单（禁 2→1 等）
   │  └─ router               # ZoneRouter：分区权限收口（AUTHOR/ADMIN 分区校验）
   ├─ mapper                  # 持久层 Mapper（发布 CAS/乐观锁/批量回查都在这层）
   ├─ enums                   # 领域枚举（文章状态/可见性/分区/对齐/书籍状态）
   ├─ mq
   │  ├─ ContentEventProducer # 事件发布统一收口（published/unpublished/submitted/image.submit）
   │  └─ consumer             # 结果/改判/封禁三消费者（板块七/八）
   ├─ cache                   # Redis 边界（推荐流 FollowRelationCache/下架缓存失效）
   ├─ dfa                     # 词库宿主侧：装载 SPI 实现/热更订阅/last-hash Redis 适配
   ├─ utils                   # 通用工具（HTML 白名单净化/日志脱敏）
   ├─ job                     # 定时任务（TagCountReconcileJob 计数对账 / ViewCountFlushJob 浏览计数缓冲落库 / FeedHotRankRefillJob 热度榜灌榜 / ArticleScheduledPublishJob 定时发布 / EventOutboxResendJob 事件发件箱重发）
   ├─ constant                # 域内常量（业务常量/限额常量）
   └─ config                  # 参数外置 ContentProperties/Rabbit 声明/SaToken/Web

litisle-api（契约层，宿主服务与 review 共用，参考）
└─ com.litisle.litisleapi
   ├─ moderation              # 发布侧门面（板块一调用的预检/免审/文本组装等）
   ├─ dfa                     # DFA 引擎门面（预检与发布闸门同源同快照）
   └─ event.moderation        # 送审/结果/改判等事件体契约
```

**各包作用说明（litisle-content）：**

> - controller：HTTP 端点层，只做参数接收/登录态/@RateLimiter 限流，业务委托 service；其下 feign 子包是 content 作为提供方暴露给 review 的回查端点（送审事件不含正文，review 凭定位键回查原文与配图）
> - service：业务编排层，事务边界与 CAS 收口都在实现类——发布编排（板块一）、文章域（板块二）、草稿/图片（板块三）、书籍/进度（板块四）、标签（板块五）、推荐流（板块六）、审核回写（板块七）、申诉/封禁处理（板块八）；impl 子包为接口实现
> - service.support：原顶层 support 包已整体迁入 service 下（`FeedItemAssembler` / `FeedRateLimitSupport` / `LikeRateLimitSupport` / `ClientIpSupport` 等），按“领域支撑组件”归位。此树此前未列该子包，补上以对齐代码现状，避免误以为支撑类仍在顶层。
> - domain：数据契约与领域规则——entity 对应表；dto/vo 防裸实体透传；statemachine 是状态机白名单载体（所有 status 流转的前置校验收口）；router 是分区权限收口（防各入口内联 if 口径漂移）
> - mapper：MyBatis 持久层，发布 CAS 0→1/0→2、回写 CAS 终态、草稿乐观锁等条件更新都定义在这层
> - enums：状态与类型枚举，状态机白名单的代码化载体
> - mq：事件边界——producer 统一补齐 base 字段后发布（禁各业务类直调 RabbitTemplate）；consumer 只做消费编排（traceId 恢复+ACK 纪律），业务委托回写服务
> - cache：Redis 读写边界——推荐流三防缓存与关注关系缓存、下架四路径共用的三键失效编排
> - dfa：板块一在 content 侧的宿主职责——词库装载 SPI 实现（Redis 优先+持久化兜底）、热更订阅 rebuild、契约层 last-hash 键的 Redis 适配
> - utils：UGC 安全工具——HTML 白名单净化（存储型 XSS 防线）、日志脱敏
> - job / constant / config：补偿与配置——标签计数对账、浏览计数缓冲落库、热度榜灌榜、定时发布、事件发件箱重发五个定时任务，域内常量收敛、参数外置与 MQ 声明

**各包作用说明（litisle-api，契约层参考）：**

> - moderation：发布同步段（板块一）调用的预检/免审判定/送审文本组装均在此，发布与 review 消费两侧共用同一实现防口径漂移
> - dfa：DFA 判定门面与快照结构，预检与发布闸门同源同快照
> - event.moderation：text/image.submit、moderation.result、appeal.result 等事件体契约，事件体只含定位键不含正文

**阶段一 · 创作与草稿（板块三）**

1. 保存草稿：
   `DraftServiceImpl` 快照 UPSERT——baseVersion 与 draft\_version 乐观锁比对，落后即拒（ARTICLE\_DRAFT\_CONFLICT 防两个标签页互相覆盖）；
   归属校验防横向越权；
   正文同上限 256KB 校验（`ContentLengthGuard`，草稿为单行覆盖 LONGTEXT，防绕过发布口灌大草稿行）；
   每个草稿仅一行、原地覆盖只存最新内容（draft\_version 递增仅作并发保护，历史版本不留存）
2. 插入正文图片：校验 fileId 归属本人且 bizType = ARTICLE\_IMG（防引用他人文件/违规图）→ 写 article\_image（position\_order/align）；正文只存 `{img:file_id}` 占位符，图片对齐权威单独存表

**阶段二 · 发布同步段（板块一，宿主本地执行，不依赖 review 可用性）**

1. 单篇创建并发布（`ArticleServiceImpl`）：`ZoneRouter` 分区权限（zone = 2 需 AUTHOR；阶段12 后仅此一条分区权限）→ `ArticleStateMachine` 状态流转合法性 → `PublishTextPrecheck` 空内容/标题 ≤100 → 正文长度硬上限 256KB（`ContentLengthGuard` 应用层兜底，超限 CONTENT\_TOO\_LONG=30018 拦截并提示拆分书籍章节；纯图文章正文残片同样受控）→ 正文 `ArticleHtmlSanitizer.sanitize` 白名单净化 → `SubmitTextAssembler.assemble` 拼接标题+正文 → `DfaFacade.check` DFA 同步闸门（正文与摘要分别判定：黑名单词拦截发布、观察词仅留痕；摘要不参与 content\_hash）→ 算 content\_hash 落库
2. 免审判定 `LastHashExemptChecker.decide`：content\_hash 未变 + 上次 PASS + 非驳回态 → CAS 0→2 直落发 content.published（**免文不免图**——配图仍发 image.submit 复审）；未命中 → CAS 0→1 回填批次 ID → afterCommit `ContentEventProducer` 发 content.submitted（事件体不含正文，review 经 ContentClient 回查）→ 即时返回「审核中」，发布 RT 不被审核绑架
3. 批量发布 `publishBatch`：单批 ≤20，`TransactionTemplate` 逐篇独立事务，失败不回滚已成功篇目，逐篇回执

**阶段三 · review 异步机审（review 模块，此处只列衔接点）**

1. review 消费 content.submitted → 凭定位键经 ContentClient 回查原文（正文 + article\_image 配图）→ 文本切片/双链路聚合均在 review 消费端收口，content 不重复实现
2. 结论经 moderation.result 事件回流 content 侧结果队列：文本路 scene = ARTICLE 与配图复审路 ARTICLE\_IMG\_RE\_REVIEW 同队列混收

**阶段四 · 结果回写与双链路聚合（板块七）**

1. `ModerationResultConsumer` 消费（`MqTraceSupport` 恢复 traceId、`MqAckHelper` ACK 纪律）→ `ArticleReviewWriteBackServiceImpl` 五防线：bizType 自筛 → SUSPICIOUS 非终局忽略 → scene 分流定位聚合槽 → 陈旧性防线（事件 contentHash 与落库指纹比对，编辑后旧结论迟到即丢弃）→ CAS 状态白名单
2. REJECT 到达即驳回落 CAS 不等另一路：首发 1→3 / 复审下架 2→4 → 发 content.unpublished + `UnpublishedCacheCleaner` 三键失效（last-hash + text-result + img-result）+ 显式审计日志
3. PASS 需文本 PASS 且（无图或图片 PASS，有无配图以 article\_image 表现状为准防竞态）→ CAS 1→2 → 终局删槽 → 发 content.published（search/ai/notification 消费）→ 写 last-hash（fail-safe，写失败宁多审不漏审）

**阶段五 · 可见与消费（板块二/六）**

1. 读者浏览：`FeedServiceImpl` 缓存三防（互斥重建/空值短缓存/TTL±10% 抖动）+ 展示前批量回查权威态，已下线即时剔除
2. 读者访问详情：getDetail 可见性判定链（1-公开直放 / 2-仅自己按不存在处理 / 3-仅关注者回查 `FollowRelationCache`；阶段9：原 4-密码访问已下线）

**阶段六 · 异议与连带（板块八）**

1. 作者申诉 → review 改判裁决 → appeal.result 回流 → `ModerationAppealResultConsumer.handleAppeal`：删 last-hash 免审键 → 回查处罚权威态 `ReviewClient.getPunishStatus`（封禁中不放行）→ CAS 对称恢复 3→2 首发 / 4→2 下架（仅此两向、仅申诉来源）→ 连带逐张 `FileClient.unquarantine` 恢复隔离配图与封面 → 发 content.published 恢复索引对称性
2. 用户被封 → `UserBannedConsumer.handleUserBanned`：查作者全部已发布+已下架未删文章 → 批量 CAS 2→4 → 逐篇发 content.unpublished + 三键失效 + 审计留痕 operator = system(mq)
3. 全程兜底约束：下架四路径（软删/转私密/复审驳回/封禁连带）统一收口同一套动作；`TagCountReconcileJob` 定时校正标签计数漂移

## 一、发布编排（ArticlePublishServiceImpl，同步段口径 B）

**功能描述：**

> - 单篇创建并发布：按免审/送审分流，即时返回发布结果或「审核中」
> - 批量发布与批量改可见性：逐篇独立事务、逐篇回执，单篇失败不影响其他
> - 发布前风险预检：只回 hit/risk 不回吐命中词

**功能描述——详细版：**

> **单篇创建并发布**
>
> - **前置校验**：可见文本非空、标题 ≤100、正文/配图至少其一非空；正文硬上限 256KB（`ContentLengthGuard` 四路收口：发布/建档/编辑/草稿保存，超限错误信息含当前长度/上限并软引导拆分书籍章节发布）；正文 HTML 白名单净化后入库
> - **DFA 同步闸门**：正文（assemble 后）与摘要（stripTags 后）分别过 DFA 判定——黑名单词直接拦截发布，观察词仅留痕；摘要不进 assemble 口径、不参与 content\_hash（存量免审缓存不失配）
> - **免审分流**：内容指纹未变且上次结论为通过 → 直接发布；否则落库待审并异步送审，接口即时返回，不等审核结果
>
> **批量发布**
>
> - 单批上限 20 篇，逐篇独立事务，单篇失败不影响其他，逐篇返回成功/失败回执
>
> **发布前预检**
>
> - 与发布闸门同源判定，仅返回是否命中与风险级别，不回吐命中词，防词库试探
>
> **批量改可见性**
>
> - 转公开要求文章已过审；已发布转私密触发下架收口（事件+缓存失效）

**链路位置**：完整链路阶段二 · 发布同步段（宿主本地执行，不依赖 review 可用性；逐步编排细节见上方「阶段二 · 发布同步段」，不在此重复）

<br />

**详细版**：

- A1 校验：trim+去标签可见文本非空、标题 ≤100、正文/配图不同空
- A2 免审直落：last-hash 命中（hash 未变+PASS+非驳回态）→ CAS 0→2 直落发 content.published；\
  **免文不免图**——配图仍发 image.submit 复审（scene = ARTICLE\_IMG\_RE\_REVIEW）
- A3 未命中：\
  CAS 0→1 回填批次 ID → afterCommit 发 content.submitted（事件体不含正文，review 经 ContentClient 回查）→ 即时返回「审核中」，发布 RT 不被审核绑架
- 批量发布 `publishBatch`：\
  单批 ≤ batch-max(20) 防批量刷审；\
  `TransactionTemplate` 逐篇独立事务（规避同类自调用 @Transactional 失效），失败不回滚已成功篇目，逐篇回执 {articleId, success, errorCode}；\
  已发布/并发流转 CAS 不满足幂等跳过
- 预检 `previewCheck`：同源 DfaFacade + SubmitTextAssembler 判定，仅回 {hit, risk} **不回吐命中词**（防词库 oracle 试探）；\
  不替代发布闸门
- 改可见性：转公开限定 status = 2（未过审内容禁公开）且不重审；\
  已发布转私密必发 content.unpublished + 失效缓存（fail-safe 不阻断批量主流程）
- 定时发布 `publishDueScheduled`（项2 2026-11-02）：\
  草稿态文章经 PUT /article/{id} 设 `scheduledPublishTime`（仅 status=0、须未来时间；`cancelScheduledPublish=true` 取消，两者互斥）；\
  `ArticleScheduledPublishJob` 每 1min 扫描到点草稿（status=0 && scheduled\_publish\_time≤now && 未软删，单轮 ≤100 篇），逐篇复用 publishBatch 走既有送审链路——**作者意志的延迟执行，零新增状态机分支**；\
  多实例分布式锁单点扫描 + 单篇 CAS 0→1 幂等防重；\
  发布成功或失败均清空定时字段：失败不静默（warn 留痕，文章停留草稿态作者在"我的文章"可见，重试无意义故不重扫）

<br />

**通俗版**：\
发布接口只做三件事——校验、判断能否免审、落库待审并通知审核服务，**绝不原地等审核结果**。\
相同内容上次过审且未改动就直接发布，但配图仍要复审。\
批量发布一篇一个独立事务，失败不影响其他篇目，逐篇给出成功/失败回执。\
发布前预检只告诉你「有没有风险」，不告诉你命中了哪个词，防试探词库。

<br />

代码部分:

> - ArticlePublishService.java：
>   litisle\_content\service\ArticlePublishService.java：
>   作用：
>   发布编排接口，声明批量发布、批量改可见性、发布前预检、定时发布到点扫描等入口契约
> - ArticlePublishServiceImpl.java：
>   litisle\_content\service\impl\ArticlePublishServiceImpl.java：
>   作用：
>   发布编排实现——批量发布（单批上限/逐篇独立事务/逐篇回执）、批量改可见性（转公开限已发布、转私密发下架事件）、预检（DFA 只回 hit/risk）、定时发布到点扫描（复用 publishBatch 送审链路，发布/失败均清定时字段）
> - ArticleScheduledPublishJob.java：
>   litisle\_content\job\ArticleScheduledPublishJob.java：
>   作用：
>   定时发布任务（项2 2026-11-02）——@Scheduled 固定周期（默认 1min）+ enabled 开关 + 分布式锁单点 + fail-safe 吞异常下轮重试，范式对齐 FeedHotRankRefillJob；扫描逻辑收口在 ArticlePublishService#publishDueScheduled，job 只做开关/锁/调用/日志
> - ArticleServiceImpl.java：
>   litisle\_content\service\impl\ArticleServiceImpl.java：
>   作用：
>   单篇「创建并发布」所在类——A1 前置校验与 A2 免审直落判定在这里执行，批量入口不重复实现
> - ArticleStateMachine.java：
>   litisle\_content\domain\statemachine\ArticleStateMachine.java：
>   作用：
>   文章状态机，维护 status 流转白名单（禁 2→1；4 为终态，4→2 仅申诉链路可调），所有状态变更入口的前置合法性校验
> - ZoneRouter.java：
>   litisle\_content\domain\router\ZoneRouter.java：
>   作用：
>   分区权限校验收口——zone = 2 需 AUTHOR、zone = 1 放行（阶段12：原 zone = 3 仅 ADMIN/运营 口径随分区取消删除）；发布与编辑变更分区统一调用，防各入口内联 if 口径漂移
> - PublishTextPrecheck.java：
>   litisle\_api\moderation\PublishTextPrecheck.java：
>   作用：
>   契约层前置校验工具——空内容（trim+去标签可见文本）与标题 ≤100 校验，发布侧统一口径
> - LastHashExemptChecker.java：
>   litisle\_api\moderation\LastHashExemptChecker.java：
>   作用：
>   契约层免审判定器——按 content\_hash+上次结论+当前状态+配图复审结论判定「免审直落 / 送审」
> - SubmitTextAssembler.java：
>   litisle\_api\moderation\SubmitTextAssembler.java：
>   作用：
>   契约层送审文本组装器——统一拼接标题与正文并计算 content\_hash，发布/消费两侧共用防口径漂移
> - DfaFacade.java：
>   litisle\_api\dfa\DfaFacade.java：
>   作用：
>   DFA 同步闸门门面，基于本地词库快照判定命中级别（预检与发布闸门同源同快照）
> - ContentEventProducer.java：
>   litisle\_content\mq\ContentEventProducer.java：
>   作用：
>   content 域事件发布统一收口，补齐事件 base 字段后发 MQ
> - ContentProperties.java：
>   litisle\_content\config\ContentProperties.java：\
>   作用：\
>   运营可调参数载体（batch-max、last-hash TTL、重试参数等外置 yml）

<br />

<br />

## 二、文章域 CRUD 与 UGC 安全（ArticleServiceImpl + ArticleHtmlSanitizer）

**功能描述：**

> - 文章编辑（仅元数据，正文改动须软删重发）与软删除（必连带下架收口）
> - 详情可见性判定（公开/仅自己/仅关注者三档；阶段9：原密码访问已下线）与公开列表查询
> - UGC 安全防线：HTML 白名单净化、日志脱敏、审核缓存失效

**功能描述——详细版：**

> **文章编辑**
>
> - 已发布文章禁改标题/正文/配图（防绕过复审），仅允许改封面、摘要、分区、可见性、书籍挂载等元数据；改正文须软删重发重新过审
>
> **软删除**
>
> - 逻辑删除留痕；同步触发下架收口：发下架事件 + 失效免审键与审核结果缓存，防止「删了还能搜到」
>
> **详情可见性**
>
> - 三档判定：公开直放；仅自己对非本人按不存在处理；仅关注者回查关注关系（降级从严）
>
> **转公开不重审**
>
> - 正文未被改动（指纹未变），原审核结论继续有效，无需重新送审

**链路位置**：完整链路阶段五步骤 12 · 读者访问详情

> 1. 读者访问详情：getDetail 可见性判定链\
>    1-公开直放\
>    2-仅自己：非本人按不存在处理（不暴露存在性）\
>    3-仅关注者：回查 `FollowRelationCache` 关注关系缓存

<br />

**详细版**：

- sanitize 三层铁律：\
  标题/摘要剥标签纯文本落库；正文入库前白名单净化（`ArticleHtmlSanitizer.sanitize`，天然保留 `{img:file_id}` 占位符）；\
  **审核只判定不净化、净化禁仅做出库**（存储型 XSS 红线）
- 编辑方案 B：\
  status = 2 传入标题/正文/配图即拒（防借编辑绕过复审），改正文须软删重发；\
  仅封面/摘要/分类/visibility/书籍挂载等元数据可改；\
  非 null 字段 sanitize 后增量更新
- 软删红线：\
  CAS 逻辑删除（MP del\_flag）→ afterCommit 必发 content.unpublished + `UnpublishedCacheCleaner` 失效三键（last-hash + text-result + img-result）；\
  操作日志经 `LogSanitizeUtil` 剥控制字符防日志注入
- 可见性判定链 getDetail 收口：\
  1-公开直放；\
  2-仅自己非本人按不存在处理（不暴露存在性）；\
  3-仅关注者回查 social 关注关系（降级 = 按非关注者处理宁严勿松，短 TTL 缓存 ±10% 抖动）
- 转公开不重审：\
  方案 B 禁改正文 + content\_hash 未变 → 原审核结论仍有效，发 content.published（不重建审核缓存，原键仍有效）

<br />

**通俗版**：\
用户写的 HTML 入库前先「消毒」，只留白名单标签，防止恶意脚本存进数据库害人。\
已发布文章不许改正文（想改就删掉重发重新过审），只能改封面、摘要等外围信息。\
删除文章必须同时通知搜索和 AI 服务移除痕迹并清审核缓存，防止「删了还能搜到」。\
私密文章对陌生人表现得像不存在一样，不泄露「这篇存在」。\
转公开不需要重新审核——因为正文根本没被改过。

<br />

代码部分:

> - ArticleService.java / ArticleServiceImpl.java：\
>   litisle\_content\service\ArticleService.java、service\impl\ArticleServiceImpl.java：\
>   作用：\
>   文章域接口与实现——创建并发布（分区权限/校验/sanitize/免审判定）、编辑（方案 B 拒改正文）、软删（必发下架事件+缓存失效）、详情可见性判定链、公开列表查询
> - ArticleController.java：\
>   litisle\_content\controller\ArticleController.java：\
>   作用：\
>   文章 HTTP 入口，参数接收+登录态+\@RateLimiter 限流，业务委托 ArticleService
> - ArticleHtmlSanitizer.java：\
>   litisle\_content\utils\ArticleHtmlSanitizer.java：\
>   作用：\
>   正文 HTML 白名单净化器，入库前剔除危险标签/属性，保留 {img: file\_id} 占位符（存储型 XSS 防线）
> - LogSanitizeUtil.java：\
>   litisle\_content\utils\LogSanitizeUtil.java：\
>   作用：\
>   日志脱敏工具，操作日志落盘前剥离控制字符防日志注入
> - UnpublishedCacheCleaner.java：\
>   litisle\_content\cache\UnpublishedCacheCleaner.java：\
>   作用：\
>   下架缓存失效编排——last-hash 精确删 + text/img-result 缓存键 SCAN 通配删除，四条下线路径统一调用

## 三、草稿与正文图片（DraftServiceImpl + article\_image）

**功能描述——详细版：**

> **草稿自动保存**
>
> - **归属校验**：按 draft/article 的 author\_id 与当前登录用户比对，非本人拒绝——防横向越权改写他人草稿
> - **并发冲突**：请求携带 baseVersion 与 draft\_version 比对：相等 →UPSERT 递增；落后 → 返回冲突提示刷新（乐观锁拒绝后写覆盖先写，防丢更新）；同版本重复提交幂等跳过
> - **单行覆盖**：每个草稿仅一行，每次保存原地覆盖、只存最新内容（历史版本不留存，防 longtext 正文把表撑大）；draft\_version 递增仅作多端并发保护的乐观锁计数，不是版本历史
> - 草稿图片暂存 article\_image.draft\_id；发布时切换归属，废弃草稿的孤儿图片由 file 对账链路兜底

**功能描述：**

> - 草稿保存/草稿箱/详情/删除：版本冲突拒绝，单行覆盖只存最新版（无历史版本）
> - 正文插图：校验归属与业务类型，占位符+对齐信息分离存储
> - 渲染期占位符替换为带对齐样式的图片标签

**链路位置**：完整链路阶段一 · 创作与草稿

> 1. 保存草稿（`DraftServiceImpl` 快照 UPSERT）：\
>    baseVersion 与 draft\_version 乐观锁比对，落后即拒（ARTICLE\_DRAFT\_CONFLICT 防两个标签页互相覆盖）\
>    归属校验防横向越权；单行 UPSERT 原地覆盖只保留最新版本（无历史版本留存）
> 2. 插入正文图片：\
>    校验 fileId 归属本人且 bizType = ARTICLE\_IMG（防引用他人文件/违规图）\
>    写 article\_image（position\_order/align）；正文只存 `{img:file_id}` 占位符，图片对齐权威单独存表

<br />

**详细版**：

- 草稿快照 UPSERT：\
  baseVersion 与 draft\_version 乐观锁比对，落后即拒（ARTICLE\_DRAFT\_CONFLICT 防后写覆盖先写）；归属校验防横向越权；\
  单行 UPSERT 原地覆盖只存最新内容（不保留历史版本）；草稿独立表隔离高频写低频读
- 正文只存 `{img:file_id}` 占位符，对齐权威在 article\_image（align 5 种）；\
  插图校验 fileId 归属本人且 bizType = ARTICLE\_IMG（防引用他人文件/违规图，ARTICLE\_IMG\_REFUSED）；\
  发布时归属切换 draft\_id→article\_id
- 渲染期：\
  批量查 article\_image → fileId → URL + align 映射，按 position\_order 将占位符替换为带对齐样式的 `<img>` 标签；\
  废弃草稿孤儿图由 file 对账链路兜底

**通俗版**：\
草稿像「带版本号的自动存档」，两个标签页同时编辑时版本号对不上就拒绝保存，防止互相覆盖。\
正文里图片只记一个编号占位符，尺寸对齐方式单独存表——换编辑器零成本；插的图必须是自己上传过的，不能盗用别人的文件。

代码部分:

> - DraftService.java / DraftServiceImpl.java：\
>   litisle\_content\service\DraftService.java、service\impl\DraftServiceImpl.java：\
>   作用：\
>   草稿域接口与实现——快照 UPSERT、baseVersion 乐观锁冲突拒绝、归属校验、发布时回填 article\_id
> - DraftController.java：\
>   litisle\_content\controller\DraftController.java：\
>   作用：\
>   草稿 HTTP 入口（保存/草稿箱/详情/删除）
> - ArticleDraft.java：\
>   litisle\_content\domain\entity\ArticleDraft.java：\
>   作用：\
>   草稿快照实体，draft\_version 递增作多端并发编辑的乐观锁计数，新草稿 article\_id 为空、发布时回填
> - ArticleImage.java：\
>   litisle\_content\domain\entity\ArticleImage.java：\
>   作用：\
>   配图实体，记录 fileId/position\_order/align 与 draft\_id→article\_id 归属切换，是图片对齐信息的权威来源
> - ArticleDraftMapper.java / ArticleImageMapper.java：\
>   litisle\_content\mapper\ArticleDraftMapper.java、mapper\ArticleImageMapper.java：\
>   作用：\
>   两表持久层 Mapper，图片按文章反查供聚合判定与配图恢复使用

## 四、书籍合集与阅读历史（BookServiceImpl + ReadingHistoryServiceImpl）

**功能描述——详细版：**

> **建书与章节管理**
>
> - 章节号全书唯一且即排序键，支持按序读；挂载的章节必须是自己写的文章，防伪造跨书章节
> - 书籍状态支持连载中/已完结/下架三态

**功能描述：**

> - 建书、章节挂载/卸载（仅本人文章、序号唯一）、书籍状态流转（连载中/完结/下架）
> - 章节批量重排（PUT /book/{id}/chapters/reorder）：拖拽后全量提交新序 1..N，两阶段偏移解唯一键冲突（下架书拒重排）
> - 阅读历史上报：单表双类型按人按对象唯一、节流限频、PUT 仅前进不回退；阅读文章详情即自动落历史；单用户每类型上限 1000 条（写入时懒删最旧行）

**详细版**：

- book + book\_chapter：\
  `uk(book_id, chapter_no)` 章节序号不重复、序号即排序键；\
  章节挂载校验文章归属本人（BOOK\_CHAPTER\_MISMATCH 防伪造跨书章节）；\
  book.status 0-连载中/1-已完结/2-下架；\
  章节批量重排（批次5 2026-11-02）：全量提交新序（清单与本书章节一一对应 + 新序恰 1..N 连续无重复，违者 BOOK\_CHAPTER\_REORDER\_INVALID(30019) 整单拒绝）；\
  唯一键冲突用**两阶段偏移**落库——①整书序号 += 1千万进临时区 ②CASE WHEN 单语句落正值，禁先删后插（章节行含挂载关系）；单事务 + book 粒度分布式锁防并发互踩；下架书拒重排；排序键即 chapter\_no，重排后章节列表/上一章下一章导航自动生效
- reading\_history（单表双类型）：\
  书籍历史 biz\_type=1（含最后章节）/ 独立文章历史 biz\_type=2，`uk_user_biz(user_id, biz_type, biz_id)` 每人每类型每对象一行；\
  PUT 上报：章节归属校验 + @RateLimiter 2/60s 配合前端 + **仅前进不回退**（防多端乱序上报把进度拖回去）；\
  阅读即落历史：文章详情命中自动落（归属判定：无挂载→文章历史 / 唯一挂载→书籍历史 / 多书歧义不自动落）+ 章节详情读章自动落书籍历史（recordBookRead）；\
  自动路径均按「最后一次阅读」刷新，回读旧章进度可回退——「仅前进」只约束 PUT 手动上报；\
  增长治理：单用户每类型上限 1000 条（HISTORY\_MAX\_PER\_USER，批次十一 11.4 调整），新插入行后懒删最旧行（按最后阅读时间，idx\_user\_time 支撑，fail-safe 删失败不影响落历史，零 job 零 yml 配置）；duration\_ms 列已建但暂不写入（预留）

**通俗版**：\
书籍是文章的「目录容器」，同本书章节号不能重复、序号就是排序，挂进书的章节必须是自己写的文章。\
想给章节调顺序？在目录页拖好顺序后一键保存即可，系统会整单核对后安全落库（不会出现序号断档或两章撞号）；书被下架期间不能调顺序。\
阅读历史分书籍历史（记住你看到哪一章）和文章历史（读过的文章详情就自动记下），同一本书/文章只记最新一次。\
历史不是无限攒的：每类最多记 1000 条，记满后最旧的那条会被自动清掉，腾位置给新记录。\
手动「上报进度」只能往后翻不能往回退（防多端乱序把进度拖回去，60 秒最多报 2 次）；但实际读章节/读文章是按「最后一次阅读」自动刷新的，回读旧章进度会跟着回退。

代码部分:

> - BookService.java / BookServiceImpl.java：\
>   litisle\_content\service\BookService.java、service\impl\BookServiceImpl.java：\
>   作用：\
>   书籍/章节管理——建书、章节挂载（校验文章归属本人+序号唯一）、书籍状态流转（连载中/完结/下架）
> - ReadingHistoryService.java / ReadingHistoryServiceImpl.java：\
>   litisle\_content\service\ReadingHistoryService.java、service\impl\ReadingHistoryServiceImpl.java：\
>   作用：\
>   阅读历史——单表双类型 UPSERT、章节归属校验、PUT 仅前进不回退（只约束`PUT /reading-history` 手动上报 ：新章节序号 > 当前记录序号才更新，否则忽略，防多端乱序回退进度）、阅读即落历史、历史列表分页装配
> - Book.java / BookChapter.java / ReadingHistory.java：\
>   litisle\_content\domain\entity\Book.java、entity\BookChapter.java、entity\ReadingHistory.java：\
>   作用：\
>   书籍容器、章节关联（book\_id+chapter\_no 唯一）、阅读历史（user\_id+biz\_type+biz\_id 唯一）三表实体
> - ReadingHistoryItemVO.java / ReadingHistorySupport.java：\
>   litisle\_content\domain\vo\ReadingHistoryItemVO.java、service\support\ReadingHistorySupport.java：\
>   作用：\
>   历史列表项出参（对象+章节信息装配）、文章详情命中自动落历史（fail-safe）
> - BookController.java / ReadingHistoryController.java：\
>   litisle\_content\controller\BookController.java、controller\ReadingHistoryController.java：\
>   作用：\
>   对应 HTTP 入口

### 收藏（一期 content 临时承载，阶段16）

**功能描述——详细版：**

- 收藏/取消收藏：文章与书籍两类目标，`uk_user_target(user_id, target_type, target_id)` 幂等——重复收藏/未收藏取消均按成功处理
- 写入前目标校验：文章对齐详情可见性口径（未删 + 非本人须发布态 + PUBLIC 放行/PRIVATE 拒/FOLLOWERS 回查关注缓存，social 不可用 fail-fast）；书籍未删 + 下架仅作者可收藏——不允许收藏自己看不到的内容
- 我的收藏：create\_time 倒序分页，targetType 三态（全部/文章/书籍），多态 join SQL 层装配标题/封面/摘要/作者
- 收藏状态：GET /check 登录查询；文章详情 VO 直接回填 favorited（列表不逐条回填防 N+1）
- 收藏计数口径（批次十一 11.9）：作者可以收藏自己的作品（收藏记录照常写入、按钮态正常），但 `article_stat.collect_count` / `book_stat.collect_count` **不计作者本人那一条**；文章作者取 `article.author_id`、书籍作者取 `book.author_id`，收藏与取消在**同一处判定**，不会出现「收藏没加、取消却扣 1」的不对称

**功能描述——通俗版：**

看到喜欢的文章或书点个「收藏」，再点一次取消；「我的收藏」里能翻到自己收藏过的全部内容（被删的不显示，转私密的还留着但点开要过详情那关）。

> 代码清单：
>
> - FavoriteService.java / FavoriteServiceImpl.java / FavoriteStateSupport.java：\
>   litisle\_content\service\FavoriteService.java、service\impl\FavoriteServiceImpl.java、service\support\FavoriteStateSupport.java：\
>   作用：\
>   收藏——幂等收藏/取消、目标可见性校验、我的收藏分页、状态查询单点收口
> - Favorite.java / FavoriteMapper.java / FavoriteTargetTypeEnum.java：\
>   litisle\_content\domain\entity\Favorite.java、mapper\FavoriteMapper.java、enums\FavoriteTargetTypeEnum.java：\
>   作用：\
>   收藏实体（uk\_user\_target）、多态 join 分页 SQL、目标类型白名单
> - FavoriteController.java：\
>   litisle\_content\controller\FavoriteController.java：\
>   作用：\
>   对应 HTTP 入口（收藏/取消/列表/状态，登录 + 20/60s 写限频）

与 social 收藏夹的关系：本节是单层收藏（无分组），收藏夹分组/佳句收藏归 social；social 立项后 favorite 迁移 favorite\_item 或另定合并方案。

### 书籍阅读闭环（阶段17）

**功能描述——详细版：**

- 书籍详情（GET /book/{id}）：元数据 + 可见章节数 + 首章/最新章定位 + 上次阅读信息（登录且历史章节当前可见才回填）+ 收藏状态；匿名可读公开书籍
- 章节列表（GET /book/{id}/chapters）：chapter\_no 升序分页（默认 50/页上限 100），SQL 层过滤「已发布+未删+查看者可见」；「上次读到」高亮由前端用详情 lastReadChapterId 对比
- 章节详情（GET /book/{id}/chapter/{chapterId}）：正文复用文章可见性判定链 + 相邻章导航（prev/next 随详情返回，SQL 按 visibilities 过滤自动跳过不可见章）；成功后触发浏览计数（匿名与普通读者都记，**作者本人自浏览不计**（11.11-A），按文章维度）+ 书籍阅读历史（登录非作者，uk\_user\_biz 幂等，fail-safe）
- 可见性范围：作者 \[1,2,3] 全可见 / 匿名 \[1] / 登录非作者按书内是否有关注者章节决定是否回查关注关系，social 不可用降级仅公开

**功能描述——通俗版：**

进书页能看到「继续阅读 第N章」（上次读到哪从第几章继续）；目录按章节号排好分页给；读某章直接看正文，页底「上一章/下一章」直接点（被删的或你没权限看的章节自动跳过）；你看过的每一章都默默记进阅读历史。

> 代码清单：
>
> - BookChapterDetailVO.java / BookReadSupport.java：\
>   litisle\_content\domain\vo\BookChapterDetailVO.java、service\support\BookReadSupport.java：\
>   作用：\
>   章节详情出参（书籍上下文+正文+相邻章导航）与阅读副作用收口（计数+历史，fail-safe）
> - BookChapterMapper 新方法（selectVisibleChapterPage 等 11 个，含批次5 重排 shiftChapterNoToTempArea/applyChapterNo）：\
>   litisle\_content\mapper\BookChapterMapper.java：\
>   作用：\
>   统一 join article 过滤「已发布+未删+visibility IN」的章节分页/首尾章/相邻章/关注章节存在性查询

## 五、标签域（TagServiceImpl + TagCountReconcileJob）

**功能描述——详细版：**

> **标签挂载**
>
> - 单篇 ≤5 个防关键词堆砌；重复挂载幂等跳过；使用计数异步累加、定时对账，列表查询零 join
>
> **新建标签**
>
> - 先过敏感词同步闸门再入全局池，防止标签位成为违禁词入口；被封禁标签不再对外展示

**功能描述：**

> - 标签前缀搜索与热门榜、文章挂载/取消（≤5 上限）
> - 新建标签：先过 DFA 敏感词闸门后入池
> - 使用计数异步累加 + 定时对账，封禁标签不再展示

**详细版**：

- tag 全局池（uk\_name 唯一）+ article\_tag 多对多（`uk(article_id, tag_id)` 冲突幂等跳过）；\
  单篇 ≤ tag.max-per-article(5) 防关键词堆砌；\
  **新建标签走 DFA 闸门** 后才可挂载
- use\_count 冗余计数异步刷新，列表侧零 join；\
  封禁标签（status = 1）列表侧不再聚合展示；\
  `TagCountReconcileJob` 定时对账计数漂移；一期标签不参与推荐流排序权重

**通俗版**：\
标签全站共用一个池子，文章最多挂 5 个防堆砌关键词；新造的词要先过敏感词检查。\
使用次数是异步累加的冗余数，定时任务定期校正误差；被封禁的标签不再对外聚合展示。

代码部分:

> - TagService.java / TagServiceImpl.java：\
>   litisle\_content\service\TagService.java、service\impl\TagServiceImpl.java：\
>   作用：\
>   标签域——前缀搜索/热门榜、文章挂载与取消（≤5 校验）、新建标签先过 DFA 闸门
> - TagController.java：\
>   litisle\_content\controller\TagController.java：\
>   作用：\
>   标签 HTTP 入口
> - TagCountReconcileJob.java：\
>   litisle\_content\job\TagCountReconcileJob.java：\
>   作用：\
>   use\_count 定时对账任务，校正异步计数漂移
> - Tag.java / ArticleTag.java：\
>   litisle\_content\domain\entity\Tag.java、entity\ArticleTag.java：\
>   作用：\
>   标签全局池（name 唯一/use\_count 冗余/status 封禁位）与文章-标签多对多关联实体

## 六、推荐流与缓存三防（FeedServiceImpl + FeedHotRankSupport + FollowRelationCache）

**功能描述：**

> - 登录用户推荐流分页查询：热度榜排序（榜空/开关关自动降级时间倒序），缓存三防保障稳定，展示前回查权威态剔除已下线内容
> - 仅关注者可见内容过滤，social 降级时宁严勿松
> - 热度榜灌榜：定时任务 5min 增量灌榜 + 1h 全量校正，榜缺失自动全量重建；打分统一走 FeedScoreSupport（11.10，七项权重+平滑），增量水位按 stat\_update\_time

**功能描述——详细版：**

> **推荐流分页**
>
> - 缓存优先；失效瞬间单飞重建防击穿、空页短缓存防穿透、TTL 随机抖动防雪崩
> - 展示前批量回查权威状态，缓存期内被删/转私密/下架的内容即时剔除

**链路位置**：完整链路阶段五步骤 11 · 读者浏览

> 1. 读者浏览：`FeedServiceImpl` 缓存三防\
>    互斥重建 / 空值短缓存 / TTL±10% 抖动\
>    展示前批量回查权威态，已下线即时剔除

<br />

**详细版**：

- 互斥重建防击穿：\
  单飞锁仅一个请求回源重建，其余等待回填后重读；\
  空结果短缓存防穿透（恶意翻页/冷页不再持续打库）；\
  正常 TTL ±10% 抖动防雪崩（last-hash TTL 同口径）
- 展示前回查：\
  缓存 TTL 内文章可能已下架/转私密/软删——渲染前批量回查权威态 status = 2 且 visibility = 1，已下线即时剔除；\
  feed.cache-ttl-seconds = 300 / feed.page-size = 20，@RateLimiter 60/60s
- 热度榜排序（阶段14 已落地）：\
  灌榜任务 FeedHotRankRefillJob 5min 周期 + 分布式锁单点，refillAuto 三分支编排——榜缺失全量重建 / 距上次全量 ≥1h 全量校正分数衰减 / 其余增量灌榜（读增量水位只捞 stat\_update\_time ≥ 水位的变更文章——该列仅由浏览/点赞/评论计数回写刷新，update\_time 保持「编辑才变」的业务语义，2026-11-03 批次E 修正，防浏览老文章被误顶到最新列表，重复 ZADD 幂等）；\
  全量轮影子键灌榜 + rename 原子换榜（灌榜期间读方无空榜窗口）；增量轮 ZADD 后裁剪保 top N 防无界增长；\
  榜 TTL 30min，任务连续异常死亡后榜自动过期，/feed 自动降级时间倒序（自愈闭环，无补偿逻辑）；\
  热度分：**11.10 起统一收口 FeedScoreSupport 打分**——文章七项权重（collect 0.12 / like 0.12 / comment 0.08 / view 0.03 / fresh 0.30（24h 半衰期）/ zone 0.10 / category 0.20）+ 贝叶斯平滑（strength 5 / prior 0.1），权重 yml 外置；旧公式 (like×3 + comment×2 + view×0.2)/(小时数+2)^1.5 及其 decay 参数**已作废不再读取**（仅 yml 保留兼容）；点赞已收进 content（11.8），like 信号来自点赞接口计数
- 回源读榜：\
  hot.enabled 且榜非空 → 按 ZSet 榜序组装（in 查询不保序，按榜返回顺序重排）；榜空/开关关 → 时间倒序直查兜底
- 仅关注者内容回查 `FollowRelationCache`，降级宁严勿松

**通俗版**：\
推荐流列表先走缓存：缓存失效瞬间只放一个请求去数据库重建，其余等结果（防击穿）；\
查不到的页也短暂记住「没有」（防穿透）；过期时间加随机抖动（防雪崩）。\
缓存里的文章可能在过期前被下架，所以展示前还要批量核实一遍「现在还能看吗」，不能看的立刻撤下。\
排序不是简单的时间倒序，而是定时任务维护的热度榜（互动加权+时间衰减算分）；榜单出问题会自动退回时间倒序，不会开天窗。

代码部分:

> - FeedService.java / FeedServiceImpl.java：\
>   litisle\_content\service\FeedService.java、service\impl\FeedServiceImpl.java：\
>   作用：\
>   推荐流实现——缓存快路径、互斥单飞重建、空结果短缓存、TTL±10% 抖动、展示前批量回查权威态
> - FollowRelationCache.java：\
>   litisle\_content\cache\FollowRelationCache.java：\
>   作用：\
>   仅关注者可见性的关注关系缓存，social 回查结果短 TTL 缓存 ±10% 抖动，降级按非关注者处理（宁严勿松）
> - FeedHotRankSupport.java：\
>   litisle\_content\service\support\FeedHotRankSupport.java：\
>   作用：\
>   热度榜读写——算分与影子键全量灌榜+rename 原子换榜、增量灌榜（水位读取+幂等 ZADD+裁剪）、榜序回源组装、榜空降级判定
> - FeedHotRankRefillJob.java：\
>   litisle\_content\job\FeedHotRankRefillJob.java：\
>   作用：\
>   灌榜定时任务（5min 周期+分布式锁单点），refillAuto 按榜/水位状态自动选择全量重建或增量灌榜
> - FeedController.java：\
>   litisle\_content\controller\FeedController.java：\
>   作用：\
>   推荐流 HTTP 入口（登录+限流）

### 推荐流是怎么工作的（原理详解：画像 → 召回 → 打分 → 快照 → 曝光 → 降级）

> 本节用通俗语言讲清文章流/书籍流接口内部每一步在做什么：内容从哪来、为什么每人看到的顺序不一样、翻页为什么不会乱、Redis 坏了会发生什么。接口怎么调用、参数怎么传，见下一节「推荐流无限刷」。

#### 第 1 步：先弄清楚你是谁、喜欢什么（用户画像）

- 每个访问者都有一个身份：登录用户用 userId，匿名用户用前端生成的 UUID（X-Anon-Id 请求头）。两类身份的数据分开存放，互不混淆。
- 系统查看你最近一段时间（默认 90 天）的阅读历史，从中归纳出一份「用户画像」，内容包括：
  - **常看的分区**：你读得最多的那 1 个分区（文章只有「普通」和「作家」两个分区）；
  - **常看的分类**：你读得最多的前 2 个分类（保留 2 个是为了内容不单一）；
  - **最近读过的文章**：不是偏好，是用来把你刚读过的文章往后排（少推但不彻底不推）；
  - **看书偏好**：最近读的 50 本书中完结书占一半以上，就认为你偏好完结书，否则认为你偏好连载；没有读书记录就默认按「偏好完结」处理。
- 画像算好后存进 Redis 缓存几分钟，翻页期间直接复用，不必每次重算。
- 没有任何阅读记录的用户（新注册用户，以及所有匿名用户）画像为空，称为**冷启动**：没有偏好可用，推荐流自动退化为纯「热门 + 最新」，不会报错，只会多一些随机探索内容。

#### 第 2 步：召回——先从全库挑出一小批候选

- 全库可能有几万篇文章，给每一篇都精细打分太慢。所以分两步走：先用简单快速的查询挑出几百篇「候选」，再对这一小批精打细算。挑候选这一步叫**召回**。
- 文章流一次做四路召回，每路各取固定条数，最后合并：
  - **热门路**：直接取热度榜前几名——保证公认的好内容一定出现；
  - **最新路**：按发布时间取最新的文章——保证新内容有露脸机会，不会被热度压制；
  - **常看分区路**：查你常看分区下的文章——保证你爱看的内容一定进候选；
  - **常看分类路**：查你常看分类下的文章——同上。
- 冷启动用户只用前两路（没有偏好可查）；后两路只在有画像时执行。
- 四路结果合并时同一篇只保留一份，然后过三道筛子：
  1. 请求里指定了分区筛选（普通/作家），就只留该分区的文章；
  2. 剔除你屏蔽过的内容、作者、分类（负反馈名单）；
  3. 剔除你今天已经看到过的文章（曝光记录）。
- 剩下的就是本代快照的候选池。书籍流类似，只是没有分区概念，个性化召回按分类过滤。

#### 第 3 步：打分——给池里每篇内容算一个总分

- 总分公式（各项权重都写在配置文件里，调权重不用改代码）：

```
文章总分 = 收藏分×权重 + 点赞分×权重 + 评论分×权重 + 浏览分×权重
         + 新鲜度×权重 + 分区命中×权重 + 分类命中×权重

书籍总分 = 收藏分×权重 + 阅读人数分×权重 + 新鲜度×权重 + 完结状态命中×权重
```

- 各项的含义：
  - **平滑**：互动数会被换算成 0~1 之间的分值。换算规则让「互动很少的文章」得分接近一个基准值而不是 0 分，「互动很多」才接近 1 分——避免一篇 2 赞的新文章和 0 赞的文章拉开悬殊差距。
  - **新鲜度**：按发布时间算，越新分越高，每过 24 小时分数减半。用发布时间而不是编辑时间，防止作者改几个字就把老文章顶到前排。
  - **分区/分类命中**：这篇文章的分区正好是你常看的分区就记 1 分，否则记 0 分。**偏好参与排序的全部方式就是这两次内存比对**——不查数据库，不触发额外查询，文章的分区分类信息在召回阶段就已经带出来了。
  - **已读降权**：你最近读过的文章从总分里扣一点分，排到后面但不出局——长刷场景下还是可能想再看。
- 打分后的收尾处理：
  - 按总分从高到低排序（同分按 ID 排，保证顺序稳定）；
  - 抽出一小部分位置做**探索位**：随机放一些总分不高但类型多样的内容，防止你永远只看到同一类东西。冷启动用户没有个性化信号，探索位比例会加大；
  - 相邻同作者的内容隔开（打散），避免一屏全是同一个人。

#### 第 4 步：快照——把排好的顺序存起来，翻页才不乱

- **为什么需要快照**：如果每次翻页都重新算分，第 2 页可能和第 1 页重复或顺序错乱。所以第一次请求时，把排好序的完整候选列表（几百条）整体存进 Redis，称为**快照**，并给一个快照编号（snapshotId）返回给前端。
- **翻页的本质**：前端报出「快照编号 + 已看到的位置」，服务端从同一份列表里切出下一段返回。同一份快照无论翻到第几页，顺序都不会变。
- **翻到底怎么办**：位置超出列表长度时，服务端重新走一遍「召回 → 打分 → 存快照」，生成**下一代快照**（编号自动递增），接着给你看。这就是能一直刷下去的原因——「无限刷」不是一份无限大的列表，而是一代接一代的接力。
- **为什么下一代不重复**：重新建池时会剔除你今天已曝光过的内容，上一代翻过的文章不会再进新池。
- **快照过期**：每份快照只保留几分钟，且同一用户同时只留最近几代，旧的自动清理。续页时发现快照没了，服务端返回 reset=true，前端清空列表重新加载。
- 每页返回前还有一道保险：从数据库核实这批文章「现在还能不能看」（下架/转私密/已删除的即时剔除）。快照是几分钟前的结果，这段时间里内容状态可能已经变了。

#### 第 5 步：曝光记录——看过的不重复推，点击数据不造假

- 每页内容返回给前端后，本页的内容 ID 会写进一个「今天已曝光」名单（Redis）。下次重新建池时直接剔除这些内容，保证一天内同一篇不会在新池里重复出现。
- 你点进一篇文章时，系统先核验「这篇文章今天确实曝光给过你」才累计一次点击——防止有人伪造请求凭空造出点击量，保证点击率不会超过 100%。
- 点击本身也有当天去重：同一篇你今天点 10 次只算 1 次点击。
- 曝光和点击名单都按天存放，第二天自动作废，不需要人工清理。
- 已知的统计误差：并发刷页极端情况下同一条内容可能被记两次曝光；当前只用于展示统计，不参与排序，不影响推荐结果。

#### 第 6 步：降级——Redis 坏了怎么办

- Redis 在这套流程里管四件事：画像缓存、快照、曝光名单、热度榜。它坏了不影响数据库，所以系统设计成「**功能不停，体验打折**」，按优先级一级一级往下退：
  1. **正常**：读快照切片，完整体验；
  2. **返回上一次成功的结果（last-good）**：把最近一次成功生成的快照的第一页拿出来给用户（本服务重启后内存里还留有一份，也能拿出来用）；内容稍旧但能看，不可续刷；
  3. **现场算一页（实时计算）**：画像回数据库重新归纳，热门、最新两路召回照常，屏蔽过滤照常、下架内容照样拦——但每次刷新内容都会变、可能重复，只能看一页，翻页到此为止；
  4. 热度榜为空不算故障，只是「最新」一路多取一些补位，快照照常生成。
- 前三级的取舍：只要手里还有上一代成功内容，就先给旧的（体验连续），实在没有才现场算。
- 降级页上确定会丢的能力（都依赖 Redis，坏了就是没了）：翻页不重复、顺序稳定、当日去重、无限续刷。前端通过响应里的 degraded 字段知道当前是哪种降级，可以提示「网络波动」。
- 一个明确的取舍：**降级页不写曝光记录**（Redis 都坏了，写了也存不住）。所以降级期间看过的内容，等 Redis 恢复后可能再被推一次，这是接受的代价。

#### 第 7 步：并发压力——很多人同时用降级，扛得住吗

- **内存**：降级路径只算「页大小×2」条内容，算完返回就释放，不占常驻内存；唯一常驻的缓存（上一代快照的内存副本）最多存 200 份，超出自动淘汰最久没用的，内存上限固定，不会随用户数增长。
- **数据库**：降级时查询全部落到数据库，但每个请求只有约 5~7 次走索引的查询（画像、两路召回、屏蔽名单、内容核实、统计），都做了批量合并和数量限制，比正常建池（几百条候选池）反而更轻。
- **已知风险**：Redis「彻底连不上、立刻报错」的故障没有问题；但如果 Redis 是「连得上却一直不响应」，每个请求都要等超时才能进入降级分支，高并发下会拖慢整个服务。目前靠 Redis 客户端的超时时间配置（要设得短）兜底；common-redis 已引入 Resilience4j 熔断器保护限流链路的 Redis 调用，但推荐流快照链路（快照探测/读写）尚未接入熔断。预留方案：复用同一熔断器把快照探测点接入熔断保护，已登记为暂不实现项（见 `docs/next/temporarily/temporarily-04-deferred.md`「推荐流 Redis 黑洞型故障熔断」，触发条件：黑洞型故障真实发生且降级接口延迟可观测变差时立项）。

**本节涉及的代码**：编排入口 [FeedStreamServiceImpl.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java)；画像 [FeedProfileSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedProfileSupport.java)；打分公式 [FeedScoreSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedScoreSupport.java)；热度榜 [FeedHotRankSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java)；快照/曝光/降级探测 [FeedSnapshotSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedSnapshotSupport.java)；屏蔽名单 [FeedFeedbackSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedFeedbackSupport.java)。

### 推荐流无限刷（批次十 10.2~10.6：文章流 / 书籍流）

> 本节补新增两条游标式流端点的调用口径；旧 `GET /api/content/feed` 分页式推荐流保持原语义，未改。

**端点与鉴权**

| 方法 | 路径 | 鉴权 | 频控（窗口 60s） |
| --- | --- | --- | --- |
| GET | `/api/content/feed/articles` | 登录或匿名 | 登录按 userId 60 次；匿名按客户端 IP 20 次 |
| GET | `/api/content/feed/books` | 登录或匿名 | 同上，且两条流共用同一份配额 |

- 登录态来自 Sa-Token：请求头默认 `satoken: <token>`（`token-name` 未改则用该名，以 Nacos 实际配置为准）。
- 旧 `/api/content/feed` 仍要求登录；新两条流端点已在服务端拦截器与网关白名单放行匿名 GET。

**`X-Anon-Id` 规则**

- 匿名请求携带 `X-Anon-Id`，格式必须是 UUID v4（8-4-4-4-12，版本位 4、变体位 8/9/a/b）。
- 缺失或非法时，服务端生成新 UUID 并写入响应头 `X-Anon-Id`；前端必须存 localStorage 并在后续请求回传，否则每次都会得到新身份，曝光去重与快照隔离失效。
- 合法入参不回写响应头；登录请求忽略该头（身份固定用 `u{userId}`，匿名用 `a{anonId}`，两类身份的画像/快照/曝光键完全隔离）。
- 该标识不是身份凭证，只用于曝光去重与快照隔离；频控按 IP 而非该标识，防止换标识绕过限流。

**请求参数**

| 参数 | 必填 | 语义 |
| --- | --- | --- |
| `cursor` | 否 | 上一页返回的游标；首刷不传，服务端编码快照内偏移 |
| `snapshotId` | 否 | 上一页返回的快照代次；首刷不传，回传后服务端校验快照是否仍有效 |
| `pageSize` | 否 | 单页条数；缺省取 `litisle.content.feed.page-size`（20），上限 `MAX_PAGE_SIZE=50`，超限截断 |
| `zoneCode` | 否（仅文章流） | 1-普通 / 2-作家；空或非法值=不筛选。分区筛选参与快照作用域，不同 zone 各一份快照；书籍流不参与 zone |

**响应字段语义（`data` 内为 `FeedPageVO`）**

| 字段 | 语义 |
| --- | --- |
| `items` | 本页条目；已过展示前门控回查，下架/转私密/软删内容即时剔除 |
| `cursor` | 下一页游标；为空/null 表示已到底 |
| `snapshotId` | 本页所属快照代次，下次请求原样回传 |
| `hasMore` | 是否还有下一页；false 时前端停止加载，等下次会话重新建池 |
| `reset` | true=服务端已重建快照（快照过期/游标非法/翻到底续池），前端应清空列表并从头部重新渲染；false=在既有列表后追加 |
| `degraded` | null=正常链路；非空表示本次走降级分支，取值见下表 |

| `degraded` 取值 | 含义 | 前端处理 |
| --- | --- | --- |
| `redis-down` | Redis 不可用且无 last-good，退实时计算一页 | 终页：无 cursor/snapshotId、hasMore=false，停止续刷，可提示「推荐服务暂不可用」 |
| `last-good` | 命中上一代成功快照兜底（Redis 不可用或建池失败） | 终页：只返回上一代快照首页，无 cursor/snapshotId、hasMore=false，可提示「展示的是稍早推荐」 |
| `build-failed` | 快照建池失败（代次生成/写入异常）且无 last-good，退实时计算一页 | 同 `redis-down`，停止续刷 |
| `hot-empty` | 热度榜为空，用「最新」单独兜底（快照仍正常） | 正常续刷，无需特殊处理 |

**频控与超限表现**

- 限流键：登录 `rate_limit:feed:user:{userId}:stream`，匿名 `rate_limit:feed:ip:{客户端IP}:stream`（前缀常量 `CacheConstant.RATE_LIMIT` + `FeedConstant.LIMIT_KEY_*`）。
- 客户端 IP 统一由 `ClientIpSupport` 解析（15.2-A）：`X-Forwarded-For` 首段 → `X-Real-IP` → `RemoteAddr` → `unknown`；匿名频控与曝光去重共用同一结果，禁止各入口再写私有解析逻辑。
- 超限抛 `BizException(ErrorCode.TOO_MANY_REQUESTS)`：统一 `Result` 返回 `code=10005`、提示「推荐流请求过于频繁，请稍后再试」；content 当前由 `GlobalExceptionHandler` 包裹为 HTTP 200 + 业务码，前端应以 `code` 判定而非仅看 HTTP 状态（若网关后续按码映射 429，以网关实际为准）。

**调用示例（Windows 11 PowerShell）**

```powershell
# 1) 登录用户首刷文章流（token 头名以 Nacos 实际配置为准，默认 satoken）
curl.exe -H "satoken: <token>" "http://localhost:8085/api/content/feed/articles?pageSize=20"

# 2) 匿名首刷书籍流：先不带 X-Anon-Id，观察响应头 X-Anon-Id 并保存
curl.exe -i "http://localhost:8085/api/content/feed/books?pageSize=20"

# 3) 匿名续刷：回传上次的 X-Anon-Id + cursor + snapshotId
curl.exe -H "X-Anon-Id: <anonId>" "http://localhost:8085/api/content/feed/books?cursor=<cursor>&snapshotId=<snapshotId>&pageSize=20"

# 4) 走网关同路径（网关 8080 转发到 content 8085）
curl.exe "http://localhost:8080/api/content/feed/articles?pageSize=20"
```

**实现落点**：入口 [FeedController.java](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/FeedController.java)；游标/快照/降级编排 [FeedStreamServiceImpl.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java)；快照与曝光 [FeedSnapshotSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedSnapshotSupport.java)；画像缓存 [FeedProfileSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedProfileSupport.java)；指标 [ContentMetrics.java](../../litisle-content/src/main/java/com/litisle/litislecontent/metrics/ContentMetrics.java)；架构口径见 [Content-Architeche.md](../Architecture/Content-Architeche.md)，进行中清单见 [next_update.md](../next/next_update.md)。

### 互动统计与推荐信号（批次十一 11.1~11.14）

> 本节是「统计口径」的唯一文字说明，代码侧口径以 `FeedScoreSupport` 与 `ContentStatSupport` 为准；数据表与写入点见 [Content-Architeche.md](../Architecture/Content-Architeche.md)。

**统计真源（11.2 / 11.10）**

- `article_stat` / `book_stat` 是**唯一真源**：收藏数、点赞数、评论数、浏览量、读者数、章节数都只认这两张表。
- `article.collect_count`、`article.like_cnt` 等实体冗余列只作展示镜像与老接口兼容读，**不参与排序**；两处不一致时以 stat 表为准，差异由 11.16 对账任务发现。
- `content_stat_window` 存 30/90 天窗口值（收藏数 / 阅读人数），推荐排序优先读窗口值、窗口缺失时回退累计值；窗口聚合同样排除作者自收藏，避免与累计值口径打架。

**点赞（11.8 / 11.14）**

- 接口：`POST /api/content/like`（body `{targetType, targetId}`）点赞、`DELETE /api/content/like?targetType=&targetId=` 取消；必须登录，频控 `LIKE_MAX_COUNT`（20/60s，按 userId）。
- `user_like` 唯一键 `(user_id, target_type, target_id)` 保证幂等：重复点赞不报错、不重复计数；取消未点过的也不报错。
- 计数：`article_stat.like_count` / `book_stat.like_count` 原子 +1/−1，递减用 `GREATEST(x-1, 0)` 兜底，不出现负数。
- 跨模块同步（11.14）：取消点赞发 −1 事件，search 侧按增量正负修正 `likeCnt`，同一条消息重投不重复生效（幂等 + 失败进 DLQ）。

**曝光 / 点击与 CTR（11.6 / 11.12）**

- `feed_exposure_daily` 唯一键 `(stat_date, stream, target_id, identity_type)`——按「日期 + 流 + 内容 ID + 身份」唯一，切片阶段批量 upsert 累加曝光；点击上报在同一行累加 `click_count`。
- CTR = click_count / exposure_count，**分身份**统计：登录按 userId、匿名按 `X-Anon-Id` 单独成列，不混算（匿名身份易变，混算会把「换标识」当成新用户行为）。
- 自互动排除（11.11）：`ViewCounterSupport` 在写浏览计数前比对内容作者与查看者，作者本人浏览直接返回不计 PV（11.11-A）；`FeedExposureSupport.recordClick` 同理排除作者自点击，只影响 `click_count`/CTR 分子，曝光在翻页时已记不受影响（11.11-B）。
- 已知偏差：曝光去重是 Redis Set + 日聚合入库，切页与切片并发时**可能双计**；本轮只做统计展示，不进排序。

**负反馈（11.11）**

- 接口：`POST /api/content/feed/feedback` 提交、`DELETE` 撤销，同一 DTO；**必须登录**（匿名只有 `X-Anon-Id`，清缓存即换身份，做不了长期屏蔽）；频控 `FEEDBACK_MAX_COUNT`（20/60s，按 userId）。
- 表 `feed_feedback`：三类维度 1-不感兴趣（单条内容）/2-屏蔽作者/3-屏蔽分类，`stream` 隔开文章流与书籍流；唯一键 `(user_id, feedback_type, stream, target_id, target_key)` 防重复，重复提交幂等成功；撤销按同一键删除，同样幂等。
- 生效位置：作者/分类屏蔽在**建池**时过滤；单条「不感兴趣」在建池 + **切片**双重过滤，当前快照当页起立即消失。名单按用户单键缓存（TTL 300s、查询窗口 30 天），变更只失效该用户该流键，不做全量 evict。
- 已知偏差（收窄）：旧快照已建好的剩余页里，作者/分类屏蔽内容可能仍出现，用户下一次刷（续池或 TTL 过期重建）才完全消失；`last-good` 降级兜底页**已补负反馈过滤**（批次十二修复：lastGoodArticlePage/lastGoodBookPage 经 filterArticleIdsByFeedback/filterBookIdsByFeedback 三维度过滤后再切片）。存量库 category 列存在 latin1 双重编码（mojibake），分类屏蔽对存量脏数据行天然无效，新写入行正常。

**粗完读统计（11.13）**

- 口径：用 `reading_history.chapter_id` join `book_chapter` 取章节号，最后阅读章节号 ≥ 该书**最大公开可见章节号**（`visibility=1` + 已发布 + 未删）即近似「追上进度」；**零前端埋点**。
- 落点：`book_read_quality` 存分子 `finish_reader_count`、分母 `eligible_reader_count`（「最后一次阅读章节能解析到公开章节的读者数」，与分子同源；不用 `book_stat.reader_count` 当分母）。
- 更新：`BookReadQualityJob` **按日全量重算**（新章发布会把完读用户改判未完成、历史裁剪会缩小分母，增量感知不到这类反向变化）。
- 已知误差：①打开即算阅读（点开最后一章就离开 → 假阳性）；②书更新后原本完读用户回退未完成（假阴性）；③只读最后一章也判完读；④追平后回看中间章会重判未完成；⑤`visibility=3`（仅关注者）章节需社交关系，纯 SQL 不做个性化判定。
- 本轮**只统计不进排序**：避免同时改权重与新指标导致分数漂移无法归因。

**待确认（未拍板，勿按已定口径使用）**

- **90 天阅读历史是否对外展示**：`reading_history` 有 90 天上限裁剪，前端「更早的阅读记录」是否展示、展示到什么粒度尚未拍板；影响：完读分母只覆盖最近 90 天，长期读者可能被漏算。
- **分类编码问题**：`article.category` 以中文名入库，历史种子数据存在双重编码（`hex=C3A5C2B0…`）；是否改为存枚举编码、以及存量数据如何修，尚未拍板；影响：负反馈「屏蔽分类」按名称匹配，名称与库内值不一致时该规则不生效。
- **`chapter_count` 与详情页作者视角不一致**：`book_stat.chapter_count` 只统计公开可见章节，作者在详情页看到的是含私有章节的数量；两处数字不同属已知识偏差，是否统一待确认。

## 七、审核结果回写与双链路聚合（ModerationResultConsumer + ArticleReviewWriteBackServiceImpl）

**功能描述：**

> - 消费机审/人审结论事件，双链路（文本+配图复审）聚合判定文章终态
> - 驱动文章发布（1→2）、首发驳回（1→3）、复审下架（2→4）并联动事件与缓存
> - 写 last-hash 免审键，支撑下次发布免审

**链路位置**：完整链路阶段三（review 衔接点）+ 阶段四（结果回写）

> 1. review 消费 content.submitted，凭定位键经 ContentClient 回查原文（正文 + article\_image 配图）\
>    文本切片/双链路聚合均在 review 消费端收口，content 不重复实现
> 2. 结论经 moderation.result 事件回流 content 侧结果队列\
>    文本路 scene = ARTICLE 与配图复审路 ARTICLE\_IMG\_RE\_REVIEW 同队列混收
> 3. `ModerationResultConsumer` 消费（`MqTraceSupport` 恢复 traceId、`MqAckHelper` ACK 纪律），业务进入 `ArticleReviewWriteBackServiceImpl` 五防线：\
>    bizType 自筛 → SUSPICIOUS 非终局忽略 → scene 分流定位聚合槽\
>    陈旧性防线：事件 contentHash 与落库指纹比对，编辑后旧结论迟到即丢弃\
>    CAS 状态白名单放行终态
> 4. REJECT 到达即驳回落 CAS 不等另一路\
>    首发 1→3 / 复审下架 2→4 → 发 content.unpublished + `UnpublishedCacheCleaner` 三键失效 + 显式审计日志
> 5. PASS 需文本 PASS 且（无图或图片 PASS，有无配图以 article\_image 表现状为准防竞态）→ CAS 1→2\
>    终局删槽 → 发 content.published（search/ai/notification 消费）→ 写 last-hash（fail-safe，写失败宁多审不漏审）

<br />

**详细版**：

- 消费编排：\
  监听 content 侧结果队列（同队列混收 scene = ARTICLE 文本路 + ARTICLE\_IMG\_RE\_REVIEW 配图复审路）；\
  `MqTraceSupport` 恢复 traceId；\
  `MqAckHelper` 统一 ACK 纪律（成功 ack / 业务性失败 warn 后 ack 防毒丸 / 非预期异常指数退避经延迟交换机重投，耗尽 nack 入 DLQ **绝不自动放行**）
- 回写五防线：\
  bizType 自筛（1-文章，2-评论挂起留痕）→ SUSPICIOUS 非终局忽略 → scene 分流定位聚合槽 field；\
  **陈旧性防线**（文本路事件 contentHash 与落库指纹比对，编辑后旧结果迟到即丢弃）；\
  CAS 状态白名单（1→2 / 1→3 / 2→4；机审人审禁 3→2，恢复发布只走申诉来源）
- 双链路聚合：\
  Redis Hash 槽暂存两路 verdict（HSET+TTL，重写幂等）；REJECT 到达即驳回落 CAS 不等另一路；\
  PASS 需文本 PASS 且（无图或图片 PASS），**有无配图以 article\_image 表现状为准**防竞态；终局删槽
- 终局配套：\
  PASS → 发 content.published；首发驳回 1→3；复审下架 2→4 → 发 content.unpublished + 三键失效 + 显式结构化审计日志（@AuditLog 切面不覆盖 MQ 消费线程）；\
  affected = 0 且现态已 TAKEN\_DOWN 属重试自愈补发事件
- last-hash 写入跟随事件到达（fail-safe 不阻断，写失败 **宁多审不漏审**）；\
  文本主键取落库指纹、图片连带键取配图聚合指纹；TTL ±10% 抖动

**通俗版**：\
审核服务对「文字」和「配图」分别给结论，content 这边拿个小本子（Redis 槽）分别记录：任何一路说不合格，整篇立刻驳回；\
两路都合格（或压根没配图）才正式发布。\
如果审核结论到得晚、而这期间作者又改了文章（指纹对不上），旧结论直接作废。\
改状态全部用条件更新，状态对不上就静默放弃，天然防重复消费和并发打架。

代码部分:

> - ModerationResultConsumer.java：\
>   litisle\_content\mq\consumer\ModerationResultConsumer.java：\
>   作用：\
>   审核结果消费者，只做消费编排（traceId 恢复+重试感知 ACK 模板），业务委托回写服务
> - ArticleReviewWriteBackService.java / ArticleReviewWriteBackServiceImpl.java：\
>   litisle\_content\service\ArticleReviewWriteBackService.java、service\impl\ArticleReviewWriteBackServiceImpl.java：\
>   作用：\
>   回写业务载体——陈旧性防线、Redis 聚合槽读写、双链路聚合判定、CAS 终态回写（1→2/1→3/2→4）、last-hash 写入、封禁连带下架
> - ContentLastHashRedisAdapter.java：\
>   litisle\_content\dfa\ContentLastHashRedisAdapter.java：\
>   作用：\
>   LastHashStore 的宿主 Redis 适配实现，让契约层免审键读写不直接依赖 spring-data-redis
> - LastHashStore.java：\
>   litisle\_api\moderation\LastHashStore.java：\
>   作用：\
>   契约层免审键读写门面（write/read/remove，key 含版本+场景），发布侧与回写侧共用同一拼键口径
> - MqAckHelper.java：\
>   litisle\_common\commonmq\consume\MqAckHelper.java：\
>   作用：\
>   通用手动 ACK 模板——成功 ack / 业务性失败丢弃防毒丸 / 异常指数退避延迟重投、耗尽入 DLQ
> - MqTraceSupport.java：\
>   litisle\_common\commonmq\consume\MqTraceSupport.java：\
>   作用：\
>   MQ 链路 traceId 在消息 Header 与 MDC 间搬运，消费日志可续链
> - MqSendUtil.java：\
>   litisle\_common\commonmq\send\MqSendUtil.java：\
>   作用：\
>   MQ 发送工具，支持经延迟交换机（x-delayed）的延迟重投
> - ContentRabbitConfig.java：\
>   litisle\_content\config\ContentRabbitConfig.java：\
>   作用：\
>   content 侧队列/交换机绑定声明，路由键统一引用 litisle\_api\constant\MqConstant.java，禁私自新建交换机

## 八、申诉改判与封禁连带（handleAppeal + UserBannedConsumer）

<br />

**功能描述：**

> - 申诉改判恢复：平反文章（3→2/4→2 仅申诉来源）并连带恢复隔离配图与封面
> - 封禁连带下架：用户被封时名下文章批量下架并清缓存
> - 下架四路径统一收口：软删/转私密/复审驳回/封禁连带同一套动作，禁只写不失效

**功能描述——详细版：**

> **申诉改判恢复**
>
> - 仅申诉来源可 3→2 / 4→2 恢复；恢复前回查作者处罚状态，封禁中不放行（防晚到申诉翻转封禁结果）
> - 平反时连带恢复被隔离配图与封面；恢复发布后重建搜索/AI 索引对称
>
> **封禁连带下架**
>
> - 用户被封时名下全部已发布+已下架未删文章批量下架，逐篇发事件+清缓存，审计留痕

<br />

**链路位置**：完整链路阶段六 · 异议与连带

> 1. 作者申诉 → review 改判裁决 → appeal.result 回流 → `ModerationAppealResultConsumer` 消费委托 `ArticleReviewWriteBackServiceImpl#handleAppeal`\
>    删 last-hash 免审键 → 回查处罚权威态 `ReviewClient.getPunishStatus`（封禁中不放行；回查非成功同样不放行，抛异常交延迟重试）\
>    CAS 对称恢复 3→2 首发 / 4→2 下架（仅此两向、仅申诉来源）\
>    连带逐张 `FileClient.unquarantine` 恢复隔离配图与封面 → 发 content.published 恢复索引对称性
> 2. 用户被封 → `UserBannedConsumer` 消费委托 `handleUserBanned`\
>    查作者全部已发布+已下架未删文章 → 批量 CAS 2→4\
>    逐篇发 content.unpublished + 三键失效 + 审计留痕 operator = system(mq)

<br />

**详细版**：

- 申诉改判 `handleAppeal`：\
  bizType 分流 → appealPass = false 维持原判 ack；\
  **删 last-hash 免审键**（键里可能存 REJECT 结论，不清则发布侧误提示；fail-safe 不阻断）；\
  改判前回查处罚权威态 `reviewClient.getPunishStatus`（封禁中拒绝放行，防晚到申诉翻转封禁连带结果；**回查非成功一律不放行**——调用异常、业务码非 0、data 为空都视为“无法证明作者未被封禁”，抛非 BizException 交 `MqAckHelper` 指数退避重试，禁 return 被当业务成功 ack 丢弃）；\
  CAS allow-list 对称恢复 **3→2 首发 / 4→2 下架（仅此两向、仅申诉来源）**；\
  4→2 连带逐张 `fileClient.unquarantine` 恢复隔离配图与封面（幂等，单张失败留痕不阻断；封面恢复成功单独按 COVER 四元组补登记引用）；\
  发 content.published 恢复 search/ai(RAG) 索引对称性
- 封禁连带 `handleUserBanned`：\
  查作者全部已发布+已下架未删文章（含已下架 = 重试补发自愈窗口）→ 批量 CAS 2→4；\
  逐篇发 content.unpublished + `UnpublishedCacheCleaner` 三键失效；\
  显式审计日志 operator = system(mq)
- **下架四路径统一收口**：\
  软删/转私密/复审驳回/封禁连带，全部发 content.unpublished + 失效 last-hash 与 text/img-result 缓存键；\
  写入 → 失效对称铁律——content 侧新增任何 review 域缓存键写入必须同步核对失效收口，禁只写不失效

**通俗版**：\
作者申诉成功后，平台「以最新结论为准」恢复文章——但恢复前先确认作者本人没被封禁（防止晚到的申诉把被封用户的违规内容翻回来）；\
如果当初连配图或封面一起被隔离了，平反时也一并恢复。\
配图恢复窗口：隔离图（正文配图与封面）到期软删后仍有 7 天缓冲期，期内改判可完整恢复（含引用计数补登记）；缓冲期满被物理清理后才改判的，配图/封面永久丢失，文章照常恢复、图片呈死链（申诉发起窗口 ≤ 留存窗口，策略上不会出现）。\
用户被封号时，名下所有已发布文章连带下架，并清掉一切可能让旧内容继续可见的缓存。\
所有下线路径（自己删、转私密、复审驳回、封禁）走同一套收口动作，防止搜索结果或向量库留残留。

代码部分:

> - ModerationAppealResultConsumer.java：\
>   litisle\_content\mq\consumer\ModerationAppealResultConsumer.java：\
>   作用：\
>   申诉改判消费者，消费编排同板块七，业务委托 handleAppeal
> - UserBannedConsumer.java：\
>   litisle\_content\mq\consumer\UserBannedConsumer.java：\
>   作用：\
>   用户封禁连带消费者，业务委托 handleUserBanned（批量下架+逐篇事件+缓存失效+审计留痕）
> - ReviewClient.java：\
>   litisle\_api\feign\ReviewClient.java：\
>   作用：\
>   review 服务 Feign 客户端——申诉改判前回查作者处罚权威态（封禁中不放行，回查失败不静默）
> - FileClient.java：\
>   litisle\_api\feign\FileClient.java：\
>   作用：\
>   file 服务 Feign 客户端——4→2 平反后逐张恢复被隔离配图与封面（unquarantine 幂等）
> - UnpublishedCacheCleaner.java：\
>   litisle\_content\cache\UnpublishedCacheCleaner.java：\
>   作用：\
>   见板块二；封禁连带/复审下架路径复用同一失效编排

