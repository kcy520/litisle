# litisle-content 内容模块架构文档

> 版本：v1.1（2026-10-28 依据 Content_Function_Develop.md / Design-Document §2.3.3·§2.4·§2.6 / Sql-Document §二 收敛；
> v1.1 补后续另立项落地：P1 发布异常保稿（方案B）· P1 热度榜增量灌榜 · P2 书籍发现入口）
>
> 关联文档：[Design-Document.md](../overall_documen/Design-Document.md) §2.3.3 / §2.4 / §2.6 · [Function-Document.md](../overall_documen/Function-Document.md) §7 · [Sql-Document.md](../Sql-Document.md) §二 · [File-Architecture.md](File-Architecture.md)（fileId 契约）
>
> 定位：**独立微服务**（litisle-content），文章/草稿/书籍/合集的核心管理——创作 → 审核 → 发布 → 组织的完整创作流

------

## 一、模块定位与边界

| 项目     | 说明                                                         |
| -------- | ------------------------------------------------------------ |
| 定位     | 创作内容 SSOT：文章 CRUD、草稿自动保存、批量发布、书籍/章节组织、阅读历史、推荐流 |
| 职责边界 | 只管**内容本体 + 组织结构**；审核判定在 review、图片存储在 file、计数点赞在 social、全文/向量检索在 search/ai |
| 依赖方   | litisle-file（封面/正文图片 fileId）、litisle-review（送审）、litisle-user（作者身份 Feign）、litisle-api（契约）、MQ（事件生产）、Redis（推荐流缓存/免审直落 last-hash） |
| 不负责   | DFA 词库管理（review）、点赞评论（social）、收藏夹分组/佳句收藏（social；文章/书籍单层收藏一期由本模块临时承载，social 立项后迁移）、审核任务调度（review） |

### 1.1 模块协作拓扑

```
content ──Feign──► user（作者身份）、file（图片/封面）、review（送审）、ai（小审/标签）
content ──MQ──►   content.submitted / published / unpublished（review、search、notification、ai 消费；发送经本地 outbox 必达兜底，EventOutboxResendJob 重发）
review   ──MQ──►   review.decided（content 消费回写状态）、user.banned（封禁连带下架）
social   ──Feign──► content（回写 view/like/comment_cnt 冗余计数）
```

### 1.2 代码结构

```
litisle-content
├── controller/        # ArticleController / ArticlePublishController / BookController / DraftController
│   │                  # / FavoriteController / FeedController / ReadingHistoryController / TagController
│   └── feign/         # ContentFeignProviderController（review 回查正文/配图反查等跨服务端点）
├── service/           # ArticleService / ArticlePublishService / BookService / DraftService
│   │                  # / FavoriteService / FeedService / ReadingHistoryService / TagService
│   │                  # / ArticleReviewWriteBackService / ContentInternalService
│   ├── impl/          # 接口实现（事务边界与 CAS 收口都在实现类）
│   └── support/       # PublishDraftSnapshotSupport / FeedHotRankSupport / BookReadSupport / ViewCounterSupport
│                      # / ReadingHistorySupport / FavoriteStateSupport / ArticleImageRefSupport
│                      # / ArticleImageRenderSupport / ClientIpSupport
├── mapper/            # ArticleMapper / BookMapper / ArticleDraftMapper / ArticleImageMapper / ArticleTagMapper
│                      # / BookChapterMapper / ReadingHistoryMapper / FavoriteMapper / TagMapper / EventOutboxMapper
├── domain/
│   ├── entity/        # Article / Book / ArticleDraft / BookChapter / ArticleImage / ArticleTag / Tag
│   │                  # / Favorite / ReadingHistory / EventOutbox
│   ├── dto/           # ArticlePublishDTO / ArticleUpdateDTO / DraftSaveDTO / BookChapterReorderDTO 等
│   ├── vo/            # ArticleDetailVO / BookCardVO / BookChapterDetailVO / FeedItemVO 等
│   ├── statemachine/  # ArticleStateMachine（status 流转白名单，禁 2→1 等）
│   └── router/        # ZoneRouter（分区权限收口，防各入口内联 if 口径漂移）
├── constant/          # ContentBizConstant（业务常量）+ ContentLimitConstant（限流/上限常量；切片上限已归 review 侧）
├── enums/             # ZoneEnum / ArticleStatusEnum / VisibilityEnum / BookStatusEnum / AlignEnum
│                      # / TagStatusEnum / ArticleCategoryEnum / FavoriteTargetTypeEnum
├── mq/
│   ├── ContentEventProducer  # 事件发布统一收口（submitted/published/unpublished/image.submit 四发送方法，outbox 必达）
│   └── consumer/             # ModerationResultConsumer / ModerationAppealResultConsumer / UserBannedConsumer
├── cache/             # FollowRelationCache（关注关系缓存）/ UnpublishedCacheCleaner（下架四路径三键失效）
├── dfa/               # 词库宿主侧：ContentDfaConfig / ContentLastHashRedisAdapter / ContentRedisWordLibraryLoader
│                      # / WordlibChangeSubscriber（热更订阅）
├── utils/             # ArticleHtmlSanitizer（白名单净化）/ ContentLengthGuard（256KB 上限）/ LogSanitizeUtil（日志脱敏）
├── job/               # FeedHotRankRefillJob（灌榜）/ ViewCountFlushJob（浏览计数刷回）/ TagCountReconcileJob（标签对账）
│                      # / ArticleScheduledPublishJob（定时发布）/ EventOutboxResendJob（outbox 重发）
└── config/            # ContentProperties（参数外置）/ ContentRabbitConfig / SaTokenWebConfig
（slicer/ 已移除：切片职责收口 review 消费端，content 不重复实现）
```

------

## 二、功能设计

### 2.1 功能清单

| 功能 | 说明 | 核心机制 |
| --- | --- | --- |
| 文章 CRUD | 富文本 HTML：标题≤100 + 摘要≤500 + 封面(fileId) + 分类 + 分区；正文硬上限 256KB（ContentLengthGuard，发布/建档/编辑/草稿四路收口，超限 CONTENT_TOO_LONG 提示拆分书籍章节） | 软删 del_flag；删除/转非公开必发 content.unpublished（隐私红线） |
| 图片插入与对齐 | 正文只存 `{img:file_id}` 占位符，对齐权威在 article_image（5 种对齐） | 插图校验 fileId 归属本人且 bizType=ARTICLE_IMG（防引用他人文件/违规图） |
| 草稿自动保存 | 快照 UPSERT（单行原地覆盖只存最新内容，无历史版本），draft_version 乐观锁 | 归属校验防横向越权；同版本幂等跳过 |
| 分区管理 | 1-普通 / 2-作家（跳 DFA 全量人工审）；**阶段12：原 3-推荐流（仅 ADMIN/运营写）分区已取消，推荐流为 zone 1/2 聚合视图** | 一篇文章仅一个分区，冲突拒绝 |
| 批量发布 | 单批 ≤20，逐篇独立事务 + 逐篇结果回执 `{articleId, success, errorCode}` | 已发布篇目 CAS 不满足自动跳过（幂等） |
| 定时发布（项2 2026-11-02） | 草稿态（status=0）文章可设定时发布时间：PUT /article/{id} 传 `scheduledPublishTime`（Service 侧校验未来时间）/传 `cancelScheduledPublish=true` 取消（两者互斥；**偏差说明：因 DTO 局部更新约定 null=不修改，取消不用"置 null 语义"而用独立布尔标志**） | `ArticleScheduledPublishJob` 每 1min（`litisle.content.publish.scheduled.interval-ms`）扫描到点草稿复用 publishBatch 送审链路（归属校验→状态机→CAS 0→1→content.submitted）；多实例分布式锁单点 + 单篇 CAS 幂等防重；发布成功/失败均清空 scheduled_publish_time（失败 warn 留痕不静默，防反复重扫，文章停留草稿态作者可见） |
| 发布可见性 | 1-公开 / 2-仅自己 / 3-仅关注者 | 转非公开必发事件；**阶段9：原 4-密码访问（BCrypt + 失败限频防爆破）已下线，存量 visibility=4 迁移为 1** |
| 发布异常保稿 | 发布入口（所有校验前）内容快照进 article_draft，后续任何一步失败内容必已在草稿 | 方案B：PublishDraftSnapshotSupport UPSERT + REQUIRES_NEW 独立事务（主链路失败回滚不连带快照回滚）+ fail-safe warn 不阻断；图片校验失败错误信息携带「第 N 张图片(file_id=xxx)」定位（P1 2026-10-28） |
| 书籍/合集 | book + book_chapter，章节序号即排序键 | `uk(book_id, chapter_no)`；章节挂载校验文章归属本人 |
| 阅读历史 | 书籍历史（含最后章节）/ 独立文章历史 / 阅读即落历史（详情命中自动落，fail-safe） | 单表 biz_type=1/2，`uk_user_biz` 每人每类型每对象一行；PUT 上报：章节归属校验 + 2/60s 限频 + 仅前进不回退；自动路径按「最后一次阅读」刷新可回退；**增长治理（2026-11-02）：单用户每类型上限 500 条，新插入行后懒删最旧行（idx_user_time 支撑，fail-safe 不影响落历史，零 job 零 yml）** |
| 收藏 | 文章/书籍收藏（一期 content 临时承载单层收藏） | `uk_user_target` 幂等；写入前目标可见性校验（不可见按 NOT_FOUND）；详情回填 favorited，列表态走 /check；social 立项后迁移 |
| 书籍阅读闭环 | 章节列表 / 章节详情 / 上一章下一章 / 上次阅读（阶段17） | 三读端点可选登录；章节正文复用文章可见性判定链；visibilities 按查看者计算（作者全可见/匿名仅公开/关注者回查，social 不可用降级）；章节详情触发浏览计数+书籍历史（fail-safe） |
| 书籍发现入口 | 公开书籍列表（匿名）/ 我的书籍（登录） | status!=下架 && 未软删 Service 硬条件；category 精确过滤；my 端 author_id 硬收口（含下架书作者自查）；排序走 PageQuery 默认 update_time 倒序；书籍搜索为 search 外部依赖一期不做（P2 2026-10-28） |
| 大篇幅切片 | 切片与聚合判定收口 review 消费端（SSOT）；content 仅发单条 content.submitted（事件体不含正文） | 禁止静默截断（review 侧兜底）；任一片段 REJECT→整体驳回 |
| 标签系统 | tag + article_tag 多对多，每篇 ≤5 | 新建走 DFA 闸门；一期不参与推荐流排序权重 |
| 发布前预检 | 仅返回 `{hit, risk}` 不回吐命中词 | @RateLimiter(USER) 限频；不替代发布闸门 |

### 2.2 状态与字段字典

```
status       // 0-草稿 / 1-待审核 / 2-已发布 / 3-驳回 / 4-下架（终态）
visibility   // 1-公开 / 2-仅自己 / 3-仅关注者（阶段9：原 4-密码访问已下线，存量 4 迁移为 1）
zone         // 1-普通 / 2-作家（阶段12：原 3-推荐流分区已取消，推荐流为 zone 1/2 聚合视图）
book.status  // 0-连载中 / 1-已完结 / 2-下架
```

### 2.3 文章状态机

```
0-草稿 ──发布──→ 1-待审核 ──PASS──→ 2-已发布
                   │                  │
                   └──REJECT──→ 3-驳回 │
                                      └──复审驳回/封禁连带──→ 4-下架
3-驳回 ──修改重提──→ 1-待审核
0/3-驳回 ──免审直落──→ 2-已发布（3 为竞态余量：review last-hash「非驳回态才免审」实际排除，白名单防御性保留）
3-驳回/4-下架 ──申诉改判──→ 2-已发布（仅 moderation.appeal.result 来源；改判前回查封禁态，成功后发 content.published）
```

- 发布 CAS：`status in (0,3) and del_flag=0`；**禁止 2→1**（改判只认申诉来源）
- 4-下架为**终态**：作者不可重提（终态 = 无业务重提入口；申诉改判 4→2 为唯一外部恢复路径，仅 moderation.appeal.result 来源，见上图），异议走 review 申诉链路
- **方案B**：status=2 禁改正文/标题/配图（Q1 已落地：摘要不在拒改列表，但改前须过 DFA 同步闸门），仅封面/分类/visibility 等元数据可改；改正文 → 软删旧文重新发布（防借编辑绕过复审）
- ✅ Q1 摘要同步闸门（2026-10-30 落地）：发布 createAndPublish / 建档 saveDraftRow / 编辑 update 三处对摘要（stripTags 后）独立过 DFA 同步闸门——黑名单词 70001 拦截、观察词仅留痕（复用 DfaPublishGate 裁决口径），走 DfaFacade 门面（引擎=自研 AC 自动机），与正文闸门同一调用方式；场景码外置（SUMMARY_DFA_SCENE，当前共用 ARTICLE 词库，升级 2b 独立送审单元仅改常量）；摘要不进 assemble 口径、content_hash 零影响（存量免审缓存不失配）
- 免审直落：last-hash + content_hash 命中且内容未变 → 0→2 直落（免文不免图：发布时对配图发 image.submit 复审，scene=ARTICLE_IMG_RE_REVIEW）
- 转公开不重审：content_hash 校验内容未变，原审核结论仍有效；发 content.published
- 转非公开/删除 → **必须同发 content.unpublished**（否则向量库/索引残留，隐私红线）

### 2.4 核心流程

**发布编排（同步段/异步段边界，口径B）**：

```
同步段（发布接口，只做 A1→A3，不同步等审）：
A0 发布保稿快照（P1 2026-10-28）：所有校验前先把请求内容 UPSERT 进 article_draft
   （PublishDraftSnapshotSupport：REQUIRES_NEW 独立事务——主链路失败回滚不连带快照回滚；
   fail-safe warn 不阻断发布主链路；不搬运图片行/file_ref，正文占位符已含 fileId 锚点）；
   快照返回实际 draftId，文章建档成功后主事务内回填 article_draft.article_id（P1 2026-10-29 补齐：
   草稿行保留带已发布标识、直发兜底行不游离；随主事务提交，发布失败连带回填回滚=回未关联态）；
   纯流转（publish-batch/submit 等不带正文的发布）article 建档行天然保稿，零动作
A1 校验：trim 非空 + 去标签可见文本非空；标题≤100；正文/配图不同时空；
   正文≤256KB（P1 2026-11-02 ContentLengthGuard 防滥用兜底，LONGTEXT 无 DB 约束，
   防打爆送审回查/search 索引/ai RAG 摄入；不进 hasText 分支——纯图文章 contentHtml 残片同样受控；
   错误信息含当前长度/上限并软引导拆分书籍章节，不做自动拆分；草稿保存路径同上限防绕过）
A2 免审直落判定 → 命中：CAS 0→2，发 content.published（免文不免图：配图仍发 image.submit 复审）
A3 未命中：CAS 0→1 → 落库 + afterCommit 发单条 content.submitted → 即时返回「审核中」

异步段（review 消费端，审核时长不绑架发布 RT）：
A4 review 收到 content.submitted（事件体不含正文，经 ContentClient 契约回查正文）
   → review 消费端切片/聚合（SSOT：任一 REJECT→整体驳回，含 SUSPICIOUS→转人审）
   → result 回写 status=2/3（content 板块八消费端收口）→ 发 content.published / 通知作者驳回理由
```

- 切片失败重试不静默截断（review 侧兜底）；作家分区（zone=2）跳 DFA 全量人工
- 事件消费按 articleId 幂等覆盖

**正文图片渲染（与 file 协作）**：

```
草稿期：图片上传 file（bizType=ARTICLE_IMG），article_image.draft_id 暂存归属
发布期：article_image 归属切换 draft_id → article_id
渲染期：批量查 article_image → fileId → URL + align 映射
→ 按 position_order 将 {img:file_id} 占位符替换为带对齐样式的 <img> 标签
```

- 对齐信息与正文解耦：换编辑器零成本；废弃草稿孤儿图片由 file 对账链路兜底

**推荐流热度榜（阶段14 排序演进 + P1 增量灌榜 2026-10-28）**：

```
灌榜（FeedHotRankRefillJob，@Scheduled 5min + 分布式锁单点；refillAuto 三分支编排）：
分支① 榜缺失（TTL 过期/Redis 重启）→ 全量重建（防增量 ZADD 造残榜绕过榜空降级兜底）
分支② 距上次全量 ≥ full-refill-interval-ms（默认 1h）→ 全量校正分数衰减
      （增量只升不降：存量成员分数随时间应衰减，靠低频全量轮覆盖收敛）
分支③ 其余轮次 → 增量灌榜：读增量水位 → 只捞 update_time ≥ 水位（含 5s 安全回退兜时钟偏差，重复 ZADD 幂等）
      的变更文章（update_time 为 DB ON UPDATE CURRENT_TIMESTAMP——浏览量自增/计数回写/状态流转/编辑
      均刷新，增量水位完整覆盖全部热度信号变更源，DB 压力随变更量而非存量走）
      → 算分 ZADD 现有榜 → ZREMRANGEBYRANK 裁剪保 top N → 水位前推 + 榜 TTL 续期
全量轮（阶段14 R1~R4 保留）：批量查 zone 1/2 且 status=2 且 visibility=1 且未软删（含三冗余计数）
→ 逐篇 computeScore（HN 型时间衰减：score=(like×3+comment×2+view×0.2)/(小时数+2)^1.5，权重 yml 外置）
→ 内存裁剪 top N（默认 200）→ 影子键 ZADD → rename 原子换榜（灌榜期间读方无空榜窗口）
→ 榜 TTL 30min：任务连续异常死亡后榜自动过期，/feed 自动降级时间倒序（自愈闭环）
fail-safe：ZADD 失败水位不推（下轮重捞）；水位键缺失/值非法走全量兜底重建

读榜（FeedServiceImpl#loadFromDb）：
热路径：feed 缓存命中 → recheckFilter 展示前回查（不变）
回源：hot.enabled 且榜非空 → 按榜序组装（in 查询不保序，按 ZSet 返回顺序重排）
兜底：榜空/开关关 → 时间倒序直查（原阶段13 链路保留）
```

- social 未开发期间 like/comment 恒 0，公式退化为「浏览量+时间衰减」排序，链路先行跑通；
  social 落地后计数回写→下轮灌榜自动生效，增量重算接线点已留 updateCounters TODO（D10）
- 下架残留：两次灌榜间最长 5min 由 recheckFilter 展示前回查剔除（隐私红线，零开发）；
  即时出榜 ZREM 留待 4-4 ContentUnpublishedInvalidator 单点接入（B7 评估暂缓，见 temp.md）

**书籍阅读闭环（阶段17，进入书籍→选章→读章→下一章）**：

```
书籍详情页（并行调两个端点）：
D1 GET /book/{id}     → 元数据 + chapterCount + 首章/最新章 + lastRead*（继续阅读）+ favorited
D2 GET /book/{id}/chapters → 章节列表分页（chapter_no 升序，50/页）
可见性范围（visibilities）按查看者计算：
作者 [1,2,3] 全可见 / 匿名 [1] 仅公开 / 登录非作者：existsFollowersChapter 前置判定，
书内无关注者章节直接 [1] 省一次 social 回查；有则回查关注关系得 [1,3]，social 不可用降级 [1]+warn
读章（GET /book/{id}/chapter/{chapterId}）：
C1 书可读校验 → 章节归属校验（BOOK_CHAPTER_MISMATCH）
C2 正文复用文章可见性判定链（不可见 ARTICLE_NOT_FOUND）→ prev/next 随详情返回（visibilities 过滤，跳过不可见章）
C3 成功后 BookReadSupport：浏览计数（所有访问者，按文章维度）+ 书籍历史 recordBookRead（登录非作者，fail-safe）
```

------

## 四、数据模型（Sql-Document §二，10 表摘要）

| 表 | 关键字段 | 设计原因 |
| --- | --- | --- |
| `article` | zone / title / content_html / cover_file_id / status / visibility / publish_batch_id / scheduled_publish_time（项2 2026-11-02：定时发布，仅草稿态可设，置 NULL 取消）/ view_cnt·like_cnt·comment_cnt（冗余计数）/ content_hash / version | 计数冗余避免列表页 join 聚合，配合 Redis 异步刷新；`idx_zone_status` 支撑分区+状态过滤；**阶段9：access_password 列已删除** |
| `article_image` | article_id / draft_id（二选一，发布后清空 draft_id）/ file_id / position_order / align / width / height | 对齐信息与正文解耦；`idx_file_id` 支撑图片复审再聚合 |
| `article_draft` | article_id（新草稿 null，发布回填）/ draft_version 递增 | 草稿高频写低频读，独立表避免污染主表缓存 |
| `book` | status：0-连载中 / 1-已完结 / 2-下架 | 合集容器 |
| `book_chapter` | book_id + article_id + chapter_no，`uk(book_id, chapter_no)` | 章节序号不重复，序号即排序 |
| `reading_history` | biz_type（1-书籍 / 2-独立文章）+ biz_id，`uk_user_biz(user_id, biz_type, biz_id)`；`idx_user_time`（类型 tab）/ `idx_user_time_all`（混合时间线）双索引 | 书籍历史含最后章节（chapter_id 悬空列表容错）；每人每类型每对象一行=上次阅读记录，update_time 即最后阅读时间；阅读即落历史 fail-safe |
| `tag` | name（uk_name 唯一）/ use_count（冗余计数异步刷新）/ status（0-正常 1-封禁）/ del_flag | 标签全局池：审计与统计锚点；封禁标签列表侧不再聚合展示 |
| `article_tag` | article_id + tag_id，`uk(article_id, tag_id)` | 多对多关联；冲突幂等跳过；idx_tag_id 支撑按标签聚合文章 |
| `favorite` | target_type（1-文章 2-书籍）+ target_id，`uk_user_target(user_id, target_type, target_id)`；`idx_target` / `idx_user_time` | 一期单层收藏防重复；目标软删列表过滤，转私密/下架保留展示、详情可见性兜底；social 立项后迁移 favorite_item 或另定合并方案 |
| `event_outbox` | event_id（与事件体 eventId 同值）/ payload_type + payload（事件快照 JSON）/ exchange + routing_key / status（0-待发送 1-已发送 2-失败 3-超限放弃）/ retry_count / send_time；`idx_status_create` 重发扫描 / `idx_sent_update` 过期清理 | 本地 outbox 事件必达兜底（批次3 2026-11-02）：生产者发送前落表，失败由 EventOutboxResendJob 补发；消费端 eventId 幂等去重，重发无副作用 |

**设计要点**：

1. **计数冗余**：view/like/comment_cnt 落 article，social 异步回写，列表页零 join
2. **草稿独立表**：高频写低频读隔离；draft_version 递增仅作乐观锁计数（单行覆盖，无历史版本），发布回填 article_id
3. **大字段延迟加载**：列表 VO 不带 content_html，详情页单独取
4. **图片归属跨模块约定**：`file_meta.align`（file 库）为文件维度冗余；`article_image`（content 库）为对齐权威来源；正文 HTML 只存占位符

------

## 五、配置说明（litisle.content.*，运营可调参数统一收口）

| 配置项 | 默认值 | 说明 |
| --- | --- | --- |
| `litisle.content.slice.max-chars` | （已废弃） | 切片职责收口 review 消费端（llm.max-text-chars 分片阈值），content 侧不再配置 |
| `litisle.content.feed.cache-ttl-seconds / feed.page-size` | 300 / 20 | 推荐流缓存 TTL 与分页大小 |
| `litisle.content.feed.hot.enabled` | true | 热度榜总开关（false 则 /feed 退回时间倒序直查） |
| `litisle.content.feed.hot.top-n` | 200 | 榜容量上限（灌榜内存裁剪保留 top N，防 ZSet 无界增长） |
| `litisle.content.feed.hot.like-weight / comment-weight / view-weight` | 3 / 2 / 0.2 | 热度分三类互动信号权重（social 落地后 D11 按效果调参） |
| `litisle.content.feed.hot.decay-exponent / hour-offset` | 1.5 / 2 | 时间衰减指数与小时偏移（防新文章分母趋 0 分数爆炸） |
| `litisle.content.feed.hot.refill-enabled / refill-interval-ms / refill-initial-delay-ms` | true / 300000 / 60000 | 灌榜任务开关（多实例靠分布式锁单点；2026-11-03 批次E P7 补漏②起兼作异步重建触发闸门——FeedHotRankSupport.triggerRebuildAsync 同判此开关，统一「谁有资格灌榜」口径）/ 周期 5min / 启动延迟 1min |
| `litisle.content.feed.hot.full-refill-interval-ms` | 3600000 | 全量校正间隔（默认 1h）：增量轮只升不降，分数衰减靠低频全量轮覆盖收敛；榜缺失时无视本值直接全量重建（P1 2026-10-28） |
| `litisle.content.draft.keep-versions` | （未实现） | 历史设计残留：草稿实际为单行 UPSERT 原地覆盖只存最新内容（无多版本保留），此配置项代码中不存在 |
| `litisle.content.publish.batch-max` | 20 | 批量发布单批上限，防批量刷审 |
| `litisle.content.publish.scheduled.enabled / interval-ms / initial-delay-ms` | true / 60000 / 60000 | 定时发布任务（项2 2026-11-02，job 占位符直读非 ContentProperties 绑定）：开关（多实例靠分布式锁单点）/ 扫描周期 1min / 启动延迟 1min |
| `litisle.content.tag.max-per-article` | 5 | 单篇文章标签数上限，防关键词堆砌（**当前为代码常量 ContentLimitConstant.TAG_MAX_PER_ARTICLE=5，未接 yml 外置**） |
| `litisle.content.outbox.resend-enabled / resend-interval-ms / initial-delay-ms` | true / 60000 / 60000 | 事件发件箱重发任务（批次3 2026-11-02）：扫描 event_outbox 待发送/失败行补发，保证事件必达；多实例靠分布式锁单点 |
| `litisle.content.outbox.max-retry-count` | 5 | 单行重发次数上限，超限置放弃态（status=3）留痕人工介入 |
| `litisle.content.outbox.resend-batch-size` | 200 | 单轮重发批量上限（fixedDelay 模式超限部分下一轮自然消化） |
| `litisle.content.outbox.retain-days` | 7 | 已发送行保留天数，过期物理清理防表无界增长 |

------

## 三、接口设计（`/api/content`）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/article` | 登录（zone=2 需 AUTHOR） | 创建/发布 |
| PUT | `/article/{id}` | owner | status=0/3 全字段可编；status=2 仅元数据（方案B）；定时发布（项2）：status=0 可传 `scheduledPublishTime`（未来时间）设/覆盖定时或 `cancelScheduledPublish=true` 取消（互斥），非草稿态传参拒绝 |
| POST | `/article/{id}/submit` | owner | 驳回后修改重提（CAS 3→1） |
| DELETE | `/article/{id}` | owner | 软删 + 必发 content.unpublished |
| POST | `/article/save` | 登录 | 建档不发布（落库 status=0 草稿态行，不发事件；供「先建档攒批 → publish-batch 批量送审」链路） |
| POST | `/article/batch-visibility` | 登录 | 批量改可见性（逐篇独立事务+回执；转公开限已发布，转私密发 content.unpublished） |
| POST | `/draft/save` | 登录（仅本人草稿） | 草稿保存（幂等，baseVersion 乐观锁） |
| POST | `/article/publish-batch` | 登录 | 批量发布（单批 ≤20，逐篇结果回执） |
| POST | `/preview-check` | 登录·限频 | 仅返回 `{hit,risk}` 不回吐命中词 |
| GET | `/article/{id}` | 按 visibility | 详情/正文按可见性判定链收口（三档） |
| GET | `/article/list` · `/article/hot` | 匿名 | 公开列表（仅 status=2 且 visibility=1，不带正文大字段）/ 热门文章（热度榜序读 `CONTENT_FEED_HOT_ZSET` + 榜空回退 view_cnt 直查 + 缓存三防；2026-11-03 批次E P5/D-3 切流，消除 filesort，语义由全站 view_cnt 排序改为 heat 榜序含 zone 过滤+时间衰减，前端展示口径已同步知会） |
| GET | `/article/my` | 登录（author_id 硬收口） | 我的文章分页（status 细粒度/published 二值/zone/category/keyword 过滤；status=0 可筛建档行供批量送审） |
| GET | `/feed` | 登录 | 推荐流（热度榜排序，榜空降级时间倒序；缓存 + 展示前回查） |
| POST/PUT/DELETE | `/book`·`/book/{id}`·`/book/{id}/chapter` | owner | 书籍/章节管理 |
| PUT | `/book/{id}/chapters/reorder` | owner | 章节批量重排（批次5 2026-11-02）：前端拖拽后全量提交新序 `[{chapterId, newChapterNo}]`；整单校验（清单与本书章节一一对应 + 新序恰 1..N 连续无重复，违者 BOOK_CHAPTER_REORDER_INVALID）；uk(book_id, chapter_no) 冲突用**两阶段偏移**落库（①整书序号 += 1千万进临时区 ②CASE WHEN 单语句落正值），禁先删后插（章节行含挂载关系）；单事务 + book 粒度分布式锁防并发互踩；下架书拒重排；排序键即 chapter_no，重排后列表/相邻章导航自动生效 |
| PUT/GET | `/reading-history` · `/reading-history/{bookId}` | 登录 | 历史上报（限频）/ 历史列表 / 续读查询（GET /{bookId}，无历史或章节已删返回 null）；GET `/article/{id}` 详情命中自动落历史 |
| POST/DELETE/GET | `/favorite` · `/favorite/check` | 登录 | 收藏（幂等）/ 取消（幂等）/ 我的收藏 / 状态查询；写操作 20/60s 限频 |
| GET | `/book/{id}` · `/book/{id}/chapters` · `/book/{id}/chapter/{cid}` | 可选登录 | 书籍详情（上次阅读/收藏态）/ 章节列表分页 / 章节详情（正文+相邻章导航；触发计数与历史）；公开白名单三条（写端点仍 @SaCheckLogin） |
| GET | `/book/list` · `/book/my` | 匿名 / 登录 | 公开书籍列表（status!=下架 && 未软删，category 过滤）/ 我的书籍（author_id 硬收口，含下架书）；书籍发现入口（P2 2026-10-28） |
| GET | `/tags?keyword=` · `/tags/hot` | 登录 | 前缀搜索已有标签池 / 热门 TOP N |
| POST/DELETE | `/article/{id}/tags` | owner | 挂载/取消（≤5，新建过 DFA） |

### 3.1 限流矩阵（@RateLimiter(USER)，阈值收口代码常量 ContentLimitConstant，窗口统一 60s）

| 端点 | 限流(60s) | 说明 |
| --- | --- | --- |
| POST `/article` · `/article/{id}/submit` · `/article/save` | 10 | 发布/重提/建档不发布（同一常量 PUBLISH_MAX_COUNT） |
| POST `/draft/save` | 10 | 自动保存高频写，防刷库 |
| POST/DELETE `/favorite` | 20 | 收藏写库防刷（取消收藏同限频） |
| POST `/article/publish-batch` | 3 | 批量重操作 |
| PUT `/book/{id}/chapters/reorder` | 10 | 章节重排批量写（REORDER_MAX_COUNT，批次5 2026-11-02） |
| POST `/preview-check` | 10 | 预检 |
| PUT `/reading-history` | 2 | 配合前端 30s 节流 |
| GET `/feed` | 60 | 读多 |
| GET `/tags?keyword=` | 30 | 标签搜索防爬（矩阵外补充，TAG_SEARCH_MAX_COUNT） |

### 3.2 事件矩阵（ContentEventProducer）

| 事件 | 消费方 | 说明 |
| --- | --- | --- |
| `content.submitted` | review | 进入待审（单条，事件体不含正文，review 回查 ContentClient 契约）；逻辑事件名，物理路由 = moderation.exchange / moderation.text.submit（review §3.2 契约，禁新建交换机） |
| `image.submit`（发布复审，scene=ARTICLE_IMG_RE_REVIEW） | review | 发布时对配图发复审（免文不免图）；与 file 上传预审共用路由键，scene 是唯一区分键；content_hash=全部配图 sha256 排序拼接后再哈希的聚合指纹 |
| `content.published` | search / notification / ai(RAG) | 发布成功；RAG 门控回查 status=2 且 visibility=公开 |
| `content.unpublished` | search / ai(RAG 移除) | **所有下线路径必发**（四路径统一收口）；同路径调 ContentUnpublishedInvalidator 失效 last-hash + text/img-result 缓存键（review §2.5 红线） |
| `moderation.result` | content（自筛 bizType=1，按 scene 分流：发布场景=文本链路 / ARTICLE_IMG_RE_REVIEW=图片复审链路）· notification（自筛做结果通知） | review 判定回写：PASS→1→2；REJECT 按现态分流 1→3 首发 / 2→4 复审下架兜底（宁严勿松，双链路聚合在消费端收口）；消费端 CAS 前比对事件体 contentHash 防陈旧事件覆盖（防 MQ 乱序误杀新提交）；2→4 复审下架分支同发 content.unpublished + 失效审核缓存键 + @AuditLog 留痕 |
| `moderation.appeal.result` | content（自筛 bizType=1） | 申诉改判 allow-list 对称恢复：3→2 / 4→2 仅此两向 |

- 消费侧：`moderation.result` 回写状态并通知作者（review v1.2 统一单路由，消费端自筛 bizType）；`user.banned` 封禁连带下架（发 content.unpublished）
- 路由映射：content.submitted 物理路由 = moderation.exchange / moderation.text.submit；content.published / content.unpublished / moderation.appeal.result 的物理路由键随 review MqConstant 统一收口定义，禁私自新建交换机
- 图片复审再聚合：review 按 article_image 的 `idx_file_id` 反查引用文章，重算 ARTICLE_IMG_RE_REVIEW 聚合结论
- **事件必达兜底（本地 outbox，批次3 2026-11-02）**：ContentEventProducer 四个发送方法统一走「发送前落 event_outbox（status=0）→ 必达语义发送（sendCritical 内静默重试 1 次）→ 成功置 1 / 失败置 2」编排；EventOutboxResendJob（默认 1min + 分布式锁单点）扫待发送/失败行补发（retry_count 达上限置 3 放弃留痕），已发送行默认 7 天后清理；兜住「发送前进程崩溃」「发送失败」两类事件丢失（search 索引/ai RAG 依赖必达），消费端 eventId 幂等去重保证重发无副作用；发件箱自身写失败退化为尽力而为不阻断主流程

------

## 六、异常与错误码

| 场景 | 错误码 | 说明 |
| --- | --- | --- |
| 标题/正文校验失败 | ARTICLE_PARAM_INVALID | 标题>100、去标签可见文本为空、正文/配图同空 |
| 分区冲突/无权限 | ARTICLE_ZONE_DENIED | zone=2 非 AUTHOR；一篇仅一分区冲突拒绝（阶段12：原 zone=3 非 ADMIN/运营 口径随分区取消删除） |
| fileId 归属校验失败 | ARTICLE_IMG_REFUSED | 插图非本人上传或 bizType≠ARTICLE_IMG；错误信息携带定位——正文配图「第 N 张图片(file_id=xxx)」/ 封面「封面图片(file_id=xxx)」/ 指纹缺图列表（P1 2026-10-28，前端定位编辑器图片位置） |
| 草稿版本冲突 | ARTICLE_DRAFT_CONFLICT | baseVersion 落后 draft_version，拒绝后写覆盖先写 |
| 批量超限 | ARTICLE_BATCH_EXCEEDED | 单批 > publish.batch-max（20） |
| 非法章节归属 | BOOK_CHAPTER_MISMATCH | chapter_id 不归属上报的 book_id，拒绝伪造跨书章节 |
| 章节重排清单非法 | BOOK_CHAPTER_REORDER_INVALID(30019) | 重排清单与本书章节不一致（缺/多/跨书/重复）或新序号非 1..N 连续无重复（批次5 2026-11-02） |
| 标签数超限 | ARTICLE_TAG_EXCEEDED | 单篇 > tag.max-per-article（5） |
| 正文超长 | CONTENT_TOO_LONG(30018) | 正文 > 256KB（应用层兜底，LONGTEXT 无 DB 约束）；发布/建档/编辑/草稿保存四路收口，错误信息含当前长度/上限并提示拆分书籍章节 |
| 非本人操作 | NO_PERMISSION | 非 owner 且非 ADMIN（草稿/文章/书籍/标签挂载） |
| 请求过频 | TOO_MANY_REQUESTS(10005) | @RateLimiter 触发 |

------

## 七、安全与可靠性

1. **UGC 回显安全**：入库前 HTML 白名单 sanitize（标题/摘要 strip tags 纯文本落库；正文净化白名单须保留 `{img:file_id}` 占位符）+ 出库输出编码双保险；审核只判定不净化，净化与判定职责分离（review 后台内容预览直吐库中 HTML，净化禁仅做出库——存储型 XSS 红线）
2. **发布幂等**：发布 CAS 防重；草稿幂等；事件消费按 articleId 幂等覆盖
3. **事务顺序铁律**：本地事务提交 → afterCommit 事件发送（失败重投/DLQ）；发送链路带本地 outbox 必达兜底（发送前落 event_outbox，失败由 EventOutboxResendJob 补发，见 §3.2 事件矩阵备注），防「发送前崩溃/发送失败」事件丢失
4. **越权防护**：草稿/文章/书籍按 author_id 归属校验防横向越权；非本人按不存在处理不暴露存在性（visibility=2）
5. **下架收口**：四路径（删除/转私密/复审驳回/封禁连带）统一收口发 content.unpublished，防向量库/索引残留（隐私红线）
6. **缓存三防**：互斥重建防击穿 + 空结果短缓存防穿透 + TTL ±10% 抖动防雪崩；展示前回查 status=2 且 visibility=1，已下架/转私密即时剔除；写入→失效对称铁律：content 侧新增任何 review 域缓存键写入（如 last-hash）必须同步核对下架四路径失效收口（ContentUnpublishedInvalidator），禁只写不失效。
   **热度榜灌榜口径（阶段14 + P1 增量 2026-10-28；2026-11-03 批次E D-1 水位列切换）**：全量轮只经影子键灌榜 + rename 原子换榜；增量轮允许对在线榜直接 ZADD，但前置约束=正式榜必须存在（refillAuto 分支①：榜缺失一律全量重建，防增量 ZADD 造出非空残榜绕过榜空降级兜底）+ 增量水位可用；**增量水位列 = `stat_update_time`**（仅热度信号刷新：view/like/comment 计数回写；`update_time` 回归业务编辑语义，浏览量刷回显式保留原值——修复「浏览老文章顶到最新列表」语义污染 P2）；增量 ZADD 后 ZREMRANGEBYRANK 裁剪保 top N 防无界增长；榜 TTL 30min ≥ 2×灌榜周期，增量轮成功即续期，任务死亡后自动过期降级，自愈闭环无补偿逻辑；**下架显式剔除**：四条下架路径统一收口 `ContentEventProducer#sendUnpublished`，发布时对文章 `ZREM` 榜成员（不依赖时间列，实时剔除防残留榜上最长 1h）
7. **送审收口**：切片/聚合收口 review 消费端（SSOT，content 不重复实现）；切片失败重试不静默截断由 review 侧兜底；content 仅发单条 submitted 事件
8. **计数异步**：view/like/comment_cnt、tag.use_count 异步刷新，不阻塞主链路
9. **审计留痕**：ADMIN 下架、封禁连带下架等治理动作落 @AuditLog（操作人/时间/articleId/原因）；作者侧软删记操作日志
10. **密码安全（阶段9 已下线）**：原 visibility=4 密码访问功能（BCrypt 加密存储、校验失败限频防爆破）已整体下线；错误码 ARTICLE_PASSWORD_ERROR 为 common-core 公共契约保留不删