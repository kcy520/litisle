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
   ├─ controller              # HTTP 入口（文章/草稿/书籍/标签/推荐流/进度端点）
   │  └─ feign                # 对外 Feign 提供方（review 回查原文等跨服务端点）
   ├─ service                 # 业务编排（发布/文章/草稿/书籍/进度/标签/推荐流/回写/内部）
   │  └─ impl                 # 接口实现
   ├─ domain
   │  ├─ entity               # 表实体（article/article_draft/article_image/book/tag 等）
   │  ├─ dto                  # 入参对象（发布/编辑/存稿/挂章节等）
   │  ├─ vo                   # 出参对象（详情/卡片/回执等）
   │  ├─ statemachine         # ArticleStateMachine：status 流转白名单（禁 2→1 等）
   │  └─ router               # ZoneRouter：分区权限收口（AUTHOR/ADMIN 分区校验）
   ├─ mapper                  # 持久层 Mapper（发布 CAS/乐观锁/批量回查都在这层）
   ├─ enums                   # 领域枚举（文章状态/可见性/分区/对齐/书籍状态）
   ├─ mq
   │  ├─ ContentEventProducer # 事件发布统一收口（published/unpublished/submitted）
   │  └─ consumer             # 结果/改判/封禁三消费者（板块七/八）
   ├─ cache                   # Redis 边界（推荐流 FollowRelationCache/下架缓存失效）
   ├─ dfa                     # 词库宿主侧：装载 SPI 实现/热更订阅/last-hash Redis 适配
   ├─ utils                   # 通用工具（HTML 白名单净化/日志脱敏）
   ├─ job                     # 定时对账（TagCountReconcileJob 计数漂移校正）
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
> - domain：数据契约与领域规则——entity 对应表；dto/vo 防裸实体透传；statemachine 是状态机白名单载体（所有 status 流转的前置校验收口）；router 是分区权限收口（防各入口内联 if 口径漂移）
> - mapper：MyBatis 持久层，发布 CAS 0→1/0→2、回写 CAS 终态、草稿乐观锁等条件更新都定义在这层
> - enums：状态与类型枚举，状态机白名单的代码化载体
> - mq：事件边界——producer 统一补齐 base 字段后发布（禁各业务类直调 RabbitTemplate）；consumer 只做消费编排（traceId 恢复+ACK 纪律），业务委托回写服务
> - cache：Redis 读写边界——推荐流三防缓存与关注关系缓存、下架四路径共用的三键失效编排
> - dfa：板块一在 content 侧的宿主职责——词库装载 SPI 实现（Redis 优先+持久化兑底）、热更订阅 rebuild、契约层 last-hash 键的 Redis 适配
> - utils：UGC 安全工具——HTML 白名单净化（存储型 XSS 防线）、日志脱敏
> - job / constant / config：补偿与配置——标签计数对账定时任务、域内常量收敛、参数外置与 MQ 声明

**各包作用说明（litisle-api，契约层参考）：**

> - moderation：发布同步段（板块一）调用的预检/免审判定/送审文本组装均在此，发布与 review 消费两侧共用同一实现防口径漂移
> - dfa：DFA 判定门面与快照结构，预检与发布闸门同源同快照
> - event.moderation：text/image.submit、moderation.result、appeal.result 等事件体契约，事件体只含定位键不含正文

**阶段一 · 创作与草稿（板块三）**

1. 保存草稿：
   `DraftServiceImpl` 快照 UPSERT——baseVersion 与 draft\_version 乐观锁比对，落后即拒（ARTICLE\_DRAFT\_CONFLICT 防两个标签页互相覆盖）；
   归属校验防横向越权；
   超过 draft.keep-versions = 10 滚动清理最旧快照
2. 插入正文图片：校验 fileId 归属本人且 bizType = ARTICLE\_IMG（防引用他人文件/违规图）→ 写 article\_image（position\_order/align）；正文只存 `{img:file_id}` 占位符，图片对齐权威单独存表

**阶段二 · 发布同步段（板块一，宿主本地执行，不依赖 review 可用性）**

1. 单篇创建并发布（`ArticleServiceImpl`）：`ZoneRouter` 分区权限（zone = 2 需 AUTHOR；阶段12 后仅此一条分区权限）→ `ArticleStateMachine` 状态流转合法性 → `SubmitTextAssembler.assemble` 拼接标题+正文算 content\_hash → `PublishTextPrecheck` 空内容/标题 ≤100 → 正文 `ArticleHtmlSanitizer.sanitize` 白名单净化后落库
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

1. 作者申诉 → review 改判裁决 → appeal.result 回流 → `ModerationAppealResultConsumer.handleAppeal`：删 last-hash 免审键 → 回查处罚权威态 `ReviewClient.getPunishStatus`（封禁中不放行）→ CAS 对称恢复 3→2 首发 / 4→2 下架（仅此两向、仅申诉来源）→ 连带逐张 `FileClient.unquarantine` 恢复隔离配图 → 发 content.published 恢复索引对称性
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
> - **前置校验**：可见文本非空、标题 ≤100、正文/配图至少其一非空；正文 HTML 白名单净化后入库
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

**链路位置**：完整链路阶段二 · 发布同步段（宿主本地执行，不依赖 review 可用性）

> 1. 单篇创建并发布（`ArticleServiceImpl`）：`ZoneRouter` 分区权限（zone = 2 需 AUTHOR；阶段12 后仅此一条分区权限）→ `ArticleStateMachine` 状态流转合法性 → `SubmitTextAssembler.assemble` 拼接标题+正文算 content\_hash → `PublishTextPrecheck` 空内容/标题 ≤100 → 正文 `ArticleHtmlSanitizer.sanitize` 白名单净化后落库
> 2. 免审判定 `LastHashExemptChecker.decide`：content\_hash 未变 + 上次 PASS + 非驳回态 → CAS 0→2 直落发 content.published（**免文不免图**——配图仍发 image.submit 复审）；未命中 → CAS 0→1 回填批次 ID → afterCommit `ContentEventProducer` 发 content.submitted（事件体不含正文，review 经 ContentClient 回查）→ 即时返回「审核中」，发布 RT 不被审核绑架
> 3. 批量发布 `publishBatch`：单批 ≤20，`TransactionTemplate` 逐篇独立事务，失败不回滚已成功篇目，逐篇回执

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
>   发布编排接口，声明批量发布、批量改可见性、发布前预检等入口契约
> - ArticlePublishServiceImpl.java：
>   litisle\_content\service\impl\ArticlePublishServiceImpl.java：
>   作用：
>   发布编排实现——批量发布（单批上限/逐篇独立事务/逐篇回执）、批量改可见性（转公开限已发布、转私密发下架事件）、预检（DFA 只回 hit/risk）
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
> - **快照保留**：同一草稿仅保留最近 N 版（litisle.content.draft.keep-versions，默认 10），超限滚动清理最旧快照，防 longtext 快照无限膨胀
> - 草稿图片暂存 article\_image.draft\_id；发布时切换归属，废弃草稿的孤儿图片由 file 对账链路兑底

**功能描述：**

> - 草稿保存/草稿箱/详情/删除：版本冲突拒绝，历史快照滚动保留
> - 正文插图：校验归属与业务类型，占位符+对齐信息分离存储
> - 渲染期占位符替换为带对齐样式的图片标签

**链路位置**：完整链路阶段一 · 创作与草稿

> 1. 保存草稿（`DraftServiceImpl` 快照 UPSERT）：\
>    baseVersion 与 draft\_version 乐观锁比对，落后即拒（ARTICLE\_DRAFT\_CONFLICT 防两个标签页互相覆盖）\
>    归属校验防横向越权；超过 draft.keep-versions = 10 滚动清理最旧快照
> 2. 插入正文图片：\
>    校验 fileId 归属本人且 bizType = ARTICLE\_IMG（防引用他人文件/违规图）\
>    写 article\_image（position\_order/align）；正文只存 `{img:file_id}` 占位符，图片对齐权威单独存表

<br />

**详细版**：

- 草稿快照 UPSERT：\
  baseVersion 与 draft\_version 乐观锁比对，落后即拒（ARTICLE\_DRAFT\_CONFLICT 防后写覆盖先写）；归属校验防横向越权；\
  draft.keep-versions = 10 滚动清理最旧快照；草稿独立表隔离高频写低频读
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
>   草稿域接口与实现——快照 UPSERT、baseVersion 乐观锁冲突拒绝、归属校验、超限滚动清理最旧版本、发布时回填 article\_id
> - DraftController.java：\
>   litisle\_content\controller\DraftController.java：\
>   作用：\
>   草稿 HTTP 入口（保存/草稿箱/详情/删除）
> - ArticleDraft.java：\
>   litisle\_content\domain\entity\ArticleDraft.java：\
>   作用：\
>   草稿快照实体，draft\_version 递增留痕，新草稿 article\_id 为空、发布时回填
> - ArticleImage.java：\
>   litisle\_content\domain\entity\ArticleImage.java：\
>   作用：\
>   配图实体，记录 fileId/position\_order/align 与 draft\_id→article\_id 归属切换，是图片对齐信息的权威来源
> - ArticleDraftMapper.java / ArticleImageMapper.java：\
>   litisle\_content\mapper\ArticleDraftMapper.java、mapper\ArticleImageMapper.java：\
>   作用：\
>   两表持久层 Mapper，图片按文章反查供聚合判定与配图恢复使用

## 四、书籍合集与阅读进度（BookServiceImpl + ReadingProgressServiceImpl）

**功能描述——详细版：**

> **建书与章节管理**
>
> - 章节号全书唯一且即排序键，支持按序读；挂载的章节必须是自己写的文章，防伪造跨书章节
> - 书籍状态支持连载中/已完结/下架三态

**功能描述：**

> - 建书、章节挂载/卸载（仅本人文章、序号唯一）、书籍状态流转（连载中/完结/下架）
> - 阅读进度上报：按书按人唯一、节流限频、仅前进不回退

**详细版**：

- book + book\_chapter：\
  `uk(book_id, chapter_no)` 章节序号不重复、序号即排序键；\
  章节挂载校验文章归属本人（BOOK\_CHAPTER\_MISMATCH 防伪造跨书章节）；\
  book.status 0-连载中/1-已完结/2-下架
- reading\_progress：\
  `uk(user_id, book_id)` UPSERT 冲突转 update；\
  章节归属校验 + ≥30s 节流（@RateLimiter 2/60s 配合前端）；\
  **仅前进不回退**

**通俗版**：\
书籍是文章的「目录容器」，同本书章节号不能重复、序号就是排序，挂进书的章节必须是自己写的文章。\
阅读进度只记「看到哪一章」，30 秒最多上报一次，且只能往后翻不能往回退。

代码部分:

> - BookService.java / BookServiceImpl.java：\
>   litisle\_content\service\BookService.java、service\impl\BookServiceImpl.java：\
>   作用：\
>   书籍/章节管理——建书、章节挂载（校验文章归属本人+序号唯一）、书籍状态流转（连载中/完结/下架）
> - ReadingProgressService.java / ReadingProgressServiceImpl.java：\
>   litisle\_content\service\ReadingProgressService.java、service\impl\ReadingProgressServiceImpl.java：\
>   作用：\
>   阅读进度——UPSERT 冲突转更新、章节归属校验、仅前进不回退
> - Book.java / BookChapter.java / ReadingProgress.java：\
>   litisle\_content\domain\entity\Book.java、entity\BookChapter.java、entity\ReadingProgress.java：\
>   作用：\
>   书籍容器、章节关联（book\_id+chapter\_no 唯一）、阅读进度（user\_id+book\_id 唯一）三表实体
> - BookController.java / ReadingProgressController.java：\
>   litisle\_content\controller\BookController.java、controller\ReadingProgressController.java：\
>   作用：\
>   对应 HTTP 入口

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

## 六、推荐流与缓存三防（FeedServiceImpl + FollowRelationCache）

**功能描述：**

> - 登录用户推荐流分页查询：缓存三防保障稳定，展示前回查权威态剔除已下线内容
> - 仅关注者可见内容过滤，social 降级时宁严勿松

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
- 仅关注者内容回查 `FollowRelationCache`，降级宁严勿松

**通俗版**：\
推荐流列表先走缓存：缓存失效瞬间只放一个请求去数据库重建，其余等结果（防击穿）；\
查不到的页也短暂记住「没有」（防穿透）；过期时间加随机抖动（防雪崩）。\
缓存里的文章可能在过期前被下架，所以展示前还要批量核实一遍「现在还能看吗」，不能看的立刻撤下。

代码部分:

> - FeedService.java / FeedServiceImpl.java：\
>   litisle\_content\service\FeedService.java、service\impl\FeedServiceImpl.java：\
>   作用：\
>   推荐流实现——缓存快路径、互斥单飞重建、空结果短缓存、TTL±10% 抖动、展示前批量回查权威态
> - FollowRelationCache.java：\
>   litisle\_content\cache\FollowRelationCache.java：\
>   作用：\
>   仅关注者可见性的关注关系缓存，social 回查结果短 TTL 缓存 ±10% 抖动，降级按非关注者处理（宁严勿松）
> - FeedController.java：\
>   litisle\_content\controller\FeedController.java：\
>   作用：\
>   推荐流 HTTP 入口（登录+限流）

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

> - 申诉改判恢复：平反文章（3→2/4→2 仅申诉来源）并连带恢复隔离配图
> - 封禁连带下架：用户被封时名下文章批量下架并清缓存
> - 下架四路径统一收口：软删/转私密/复审驳回/封禁连带同一套动作，禁只写不失效

**功能描述——详细版：**

> **申诉改判恢复**
>
> - 仅申诉来源可 3→2 / 4→2 恢复；恢复前回查作者处罚状态，封禁中不放行（防晚到申诉翻转封禁结果）
> - 平反时连带恢复被隔离配图；恢复发布后重建搜索/AI 索引对称
>
> **封禁连带下架**
>
> - 用户被封时名下全部已发布+已下架未删文章批量下架，逐篇发事件+清缓存，审计留痕

<br />

**链路位置**：完整链路阶段六 · 异议与连带

> 1. 作者申诉 → review 改判裁决 → appeal.result 回流 → `ModerationAppealResultConsumer.handleAppeal`\
>    删 last-hash 免审键 → 回查处罚权威态 `ReviewClient.getPunishStatus`（封禁中不放行）\
>    CAS 对称恢复 3→2 首发 / 4→2 下架（仅此两向、仅申诉来源）\
>    连带逐张 `FileClient.unquarantine` 恢复隔离配图 → 发 content.published 恢复索引对称性
> 2. 用户被封 → `UserBannedConsumer.handleUserBanned`\
>    查作者全部已发布+已下架未删文章 → 批量 CAS 2→4\
>    逐篇发 content.unpublished + 三键失效 + 审计留痕 operator = system(mq)

<br />

**详细版**：

- 申诉改判 `handleAppeal`：\
  bizType 分流 → appealPass = false 维持原判 ack；\
  **删 last-hash 免审键**（键里可能存 REJECT 结论，不清则发布侧误提示；fail-safe 不阻断）；\
  改判前回查处罚权威态 `reviewClient.getPunishStatus`（封禁中拒绝放行，防晚到申诉翻转封禁连带结果；回查异常穿透交延迟重试，禁静默吞错）；\
  CAS allow-list 对称恢复 **3→2 首发 / 4→2 下架（仅此两向、仅申诉来源）**；\
  4→2 连带逐张 `fileClient.unquarantine` 恢复隔离配图（幂等，单张失败留痕不阻断）；\
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
如果当初连配图一起被隔离了，平反时配图也一并恢复。\
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
>   file 服务 Feign 客户端——4→2 平反后逐张恢复被隔离配图（unquarantine 幂等）
> - UnpublishedCacheCleaner.java：\
>   litisle\_content\cache\UnpublishedCacheCleaner.java：\
>   作用：\
>   见板块二；封禁连带/复审下架路径复用同一失效编排

