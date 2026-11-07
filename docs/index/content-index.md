# content-index · litisle-content 内容模块速查表

> 用途：写码/联调前查 content 模块坐标，**免去逐个 Read**。
> 标记：✅ 已落地 ｜ 🆕 待补（缺口）。
> 关联：[Content-Architeche.md](../Architecture/Content-Architeche.md)（架构全貌/决策推演，推荐流见 §3.3）· [Content-Business-Guide.md](../Study/Content-Business-Guide.md)（板块实现讲解，调用示例见 §六）· [Search-Architecture.md](../Architecture/Search-Architecture.md) §10.8（P0 与代码对照/P1 边界）· [api-index.md](api-index.md)（ContentClient 契约/moderation 门面）· [Sql-Document.md](../Sql-Document.md) §二（建表）。
> **进行中**：批次十（推荐流无限刷）10.1~10.6 代码已落地并编译通过，10.7/10.8 待环境验证；清单与验收证据见 [next_update.md](../next/next_update.md) 批次十，尚未归档，归档后再补归档索引条目。
> 纪律：状态流转一律走 `ArticleStateMachine` + CAS 条件更新；分区权限一律走 `ZoneRouter`；事件发送一律走 `ContentEventProducer`（禁各业务类直调 RabbitTemplate）；下线必发 content.unpublished（隐私红线）。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 服务名 / 端口 | `litisle-content` / `8085`（application.yml） |
| 定位 | 独立微服务，创作内容 SSOT：文章/草稿/书籍/合集核心管理——创作 → 审核 → 发布 → 组织完整创作流 |
| 库 | `litisle_content`（17 表：article / article_image / article_draft / book / book_chapter / reading_history / tag / article_tag / favorite / event_outbox / article_stat / book_stat / content_stat_window / user_like / feed_exposure_daily / feed_feedback / book_read_quality） |
| 关键依赖 | spring-boot-starter-amqp（手动 ACK）、common-redis（限流/锁）、common-mq（MqAckHelper/MqTraceSupport）、litisle-api（契约+DFA 门面）、Sa-Token、Knife4j、spring-boot-starter-actuator + micrometer-registry-prometheus（批次十收尾：/actuator/prometheus，端口与业务 8085 共用） |
| 只管 | 内容本体 + 组织结构 + 单层收藏（一期临时承载）；阅读历史/推荐流 |
| 不负责 | DFA 词库管理与切片判定（review）、图片存储与审核（file）、评论（social，计数经 Feign 回写冗余列；**点赞已收进 content**，11.8）、全文/向量检索（search/ai） |

## 二、包结构（`com.litisle.litislecontent`）

```text
cache/            FollowRelationCache（visibility=3 关注关系短缓存）/ UnpublishedCacheCleaner（下架四路径三键失效）
config/           ContentProperties（prefix litisle.content）/ ContentRabbitConfig / SaTokenWebConfig
constant/         ContentBizConstant（Redis 键前缀/占位符模式/下架原因字典）/ ContentLimitConstant（限流与上限）
controller/       ArticleController（CRUD 域）/ ArticlePublishController（发布编排域）/ BookController / DraftController
                  / FavoriteController / FeedController / LikeController / ReadingHistoryController / TagController
controller/feign/ ContentFeignProviderController（implements ContentClient，/api/feign/content）
domain/entity/    Article / ArticleDraft / ArticleImage / ArticleTag / Book / BookChapter / Favorite / ReadingHistory / Tag / EventOutbox
                  / UserLike / ArticleStat / BookStat / ContentStatWindow / BookReadQuality / FeedFeedback / FeedExposureDaily
domain/dto/       ArticlePublishDTO / ArticleUpdateDTO（含 scheduledPublishTime）/ ArticleSaveDTO / DraftSaveDTO
                  / BookSaveDTO / BookChapterReorderDTO / ChapterAddDTO / FavoriteDTO / 各 QueryDTO
                  / FeedQuery / FeedFeedbackDTO（批次十/十一）
domain/vo/        ArticleDetailVO / ArticleCardVO / DraftDetailVO / BookDetailVO / BookChapterDetailVO / FeedItemVO / ArticleBatchResultVO 等
domain/statemachine/ ArticleStateMachine（status 流转白名单，禁 2→1；4 终态，4→2 仅申诉）
domain/router/    ZoneRouter（分区权限收口：zone=2 需 AUTHOR）
enums/            ArticleStatusEnum / VisibilityEnum / ZoneEnum / BookStatusEnum / AlignEnum / TagStatusEnum / ArticleCategoryEnum / FavoriteTargetTypeEnum / FeedFeedbackTypeEnum
mapper/           ArticleMapper / ArticleDraftMapper / ArticleImageMapper / ArticleTagMapper / BookMapper / BookChapterMapper
                  / FavoriteMapper / ReadingHistoryMapper / TagMapper / EventOutboxMapper
                  / UserLikeMapper / ArticleStatMapper / BookStatMapper / ContentStatWindowMapper / BookReadQualityMapper
                  / FeedFeedbackMapper / FeedExposureDailyMapper / ContentStatReconcileMapper
mq/               ContentEventProducer（九发送方法统一收口 + outbox 必达）
mq/consumer/      ModerationResultConsumer / ModerationAppealResultConsumer / UserBannedConsumer
service/          ArticleService / ArticlePublishService / BookService / DraftService / FavoriteService / FeedService
                  / ReadingHistoryService / TagService / ArticleReviewWriteBackService / ContentInternalService
                  / FeedStreamService（批次十）/ FeedFeedbackService（11.11）/ LikeService（11.8）
service/impl/     对应实现（事务边界与 CAS 收口都在实现类）
service/support/  PublishDraftSnapshotSupport（发布保稿 REQUIRES_NEW）/ FeedHotRankSupport（灌榜读写）
                  / BookReadSupport / ViewCounterSupport / ReadingHistorySupport / FavoriteStateSupport
                  / ArticleImageRefSupport / ArticleImageRenderSupport / ClientIpSupport
                  / FeedSnapshotSupport（批次十：快照/游标/曝光/last-good）/ FeedProfileSupport（画像聚合+Redis 缓存）
                  / FeedScoreSupport（11.10 统一打分入口）/ FeedFeedbackSupport（11.11 负反馈名单）/ FeedExposureSupport（11.6 曝光落库）
support/          FeedItemAssembler（卡片装配）/ FeedRateLimitSupport / LikeRateLimitSupport（运行时限流，按登录态切 USER/IP）
job/              FeedHotRankRefillJob / ViewCountFlushJob / TagCountReconcileJob / ArticleScheduledPublishJob / EventOutboxResendJob
                  / ContentStatWindowJob（11.3）/ ContentStatReconcileJob（11.16）/ BookReadQualityJob（11.13）/ FeedExposureCleanupJob（11.6）
utils/            ArticleHtmlSanitizer（白名单净化）/ ContentLengthGuard（256KB 上限）/ LogSanitizeUtil（日志脱敏）
dfa/              ContentDfaConfig / ContentLastHashRedisAdapter / ContentRedisWordLibraryLoader / WordlibChangeSubscriber（词库宿主侧）
metrics/          ContentMetrics（feed 降级/快照/画像缓存/曝光与点击写失败 Counter + 对账差异 Gauge，统一打点收口）
```

## 三、枚举（`enums`，入库映射走 MyBatis-Plus MybatisEnumTypeHandler）

- `ArticleStatusEnum`：`0-草稿 / 1-待审核 / 2-已发布 / 3-驳回 / 4-下架（终态）`
- `VisibilityEnum`：`1-公开 / 2-仅自己 / 3-仅关注者`（阶段9 原 4-密码访问已下线，存量迁移为 1）
- `ZoneEnum`：`1-普通 / 2-作家`（阶段12 原 3-推荐流分区已取消，推荐流为 zone 1/2 聚合视图）
- `BookStatusEnum`：`0-连载中 / 1-已完结 / 2-下架`
- `AlignEnum`：`LEFT(1)/LEFT_CENTER(2)/CENTER(3)/RIGHT_CENTER(4)/RIGHT(5)`（对齐权威在 article_image.align）
- `TagStatusEnum`：`0-正常 / 1-封禁`（封禁标签列表侧不聚合展示）
- `ArticleCategoryEnum` / `FavoriteTargetTypeEnum`（`1-文章 / 2-书籍`）

## 四、REST 接口（前缀 `/api/content`，返回 `Result<T>`）

| 方法 | 路径 | 权限 | 限流(60s) | 说明 |
|---|---|---|---|---|
| POST | `/article` | 登录（zone=2 需 AUTHOR） | 10 | 创建/发布（同步段口径B） |
| POST | `/article/save` | 登录 | 10 | 建档不发布（status=0，不发事件，供批量送审） |
| PUT | `/article/{id}` | owner | — | 编辑：status=0/3 全字段；2 仅元数据（方案B）；定时发布（项2：`scheduledPublishTime` / `cancelScheduledPublish` 互斥） |
| POST | `/article/{id}/submit` | owner | 10 | 驳回重提（CAS 3→1） |
| DELETE | `/article/{id}` | owner/ADMIN | — | 软删 + 必发 content.unpublished（@AuditLog） |
| GET | `/article/{id}` | 按 visibility | — | 详情（判定链收口；触发浏览计数+自动落历史）；带 `from`/`snapshotId`/`position` 时记推荐流**点击**（11.6，写 `feed_exposure_daily.click_count`） |
| GET | `/article/list` · `/article/hot` | 匿名 | — | 公开列表（不带大字段）/ 热门（热度榜序 `CONTENT_FEED_HOT_ZSET`，榜空回退 view_cnt 直查+缓存三防；2026-11-03 批次E P5/D-3 切流，语义由 view_cnt 排序改为 heat 榜序，前端知会） |
| GET | `/article/my` | 登录 | — | 我的文章（author_id 硬收口；status 细粒度/published 二值过滤） |
| POST | `/article/publish-batch` | 登录 | 3 | 批量发布（≤20，逐篇独立事务+回执） |
| POST | `/article/batch-visibility` | owner | — | 批量改可见性（转公开限已发布；转私密发 unpublished） |
| POST | `/preview-check` | 登录 | 10 | 发布前预检（仅 {hit,risk} 不回吐命中词） |
| POST | `/draft/save` | 登录 | 10 | 草稿保存（baseVersion 乐观锁，冲突 ARTICLE_DRAFT_CONFLICT） |
| GET | `/draft/list` · `/draft/{id}` · DELETE | 登录 | — | 草稿箱/详情/删除（仅本人） |
| POST/PUT/DELETE | `/book` · `/book/{id}` · `/book/{id}/chapter(/{cid})` | owner | — | 书籍/章节管理（挂载校验文章归属本人） |
| PUT | `/book/{id}/chapters/reorder` | owner | 10 | 章节批量重排（全量新序 1..N，两阶段偏移落库，批次5） |
| GET | `/book/list` · `/book/my` | 匿名/登录 | — | 公开书籍列表（status!=下架）/ 我的书籍（含下架书） |
| GET | `/book/{id}` · `/book/{id}/chapters` · `/book/{id}/chapter/{cid}` | 可选登录 | — | 书籍详情（上次阅读/收藏态）/ 章节列表 / 章节详情（相邻章导航+计数+历史） |
| PUT | `/reading-history` | 登录 | 2 | 历史上报（仅前进不回退） |
| GET | `/reading-history` · `/reading-history/{bookId}` | 登录 | — | 历史列表 / 续读查询 |
| POST/DELETE/GET | `/favorite` · `/favorite/check` | 登录 | 写 20 | 收藏/取消（幂等）/ 我的收藏 / 状态查询 |
| GET | `/feed` | 登录 | 60 | 推荐流（热度榜排序，榜空降级时间倒序；缓存三防+展示前回查） |
| GET | `/feed/articles` · `/feed/books` | 登录或匿名 | 登录 60 / 匿名 20 | **批次十**：游标式无限刷（四路 DB 召回/画像/快照/曝光去重/三级降级）；匿名需 `X-Anon-Id`（缺失或非法由服务端回写响应头），两条流共用配额；响应含 `cursor/snapshotId/hasMore/reset/degraded`，调用口径见 Guide §六 |
| POST/DELETE | `/feed/feedback` | 登录 | 20 | **批次十一 11.11**：负反馈提交/撤销（body `{feedbackType, stream, targetId, targetKey}`）；三维度 1-不感兴趣/2-屏蔽作者/3-屏蔽分类，唯一键幂等；必须登录 |
| POST/DELETE | `/like` | 登录 | 20（可配） | **批次十一 11.8**：点赞/取消（`user_like` 幂等，stat 计数原子增减带下限，作者不可自赞）；取消发 −1 事件同步 search `likeCnt` |
| GET | `/tags?keyword=` · `/tags/hot` | 登录 | 30 | 标签前缀搜索 / 热门 TOP N |
| POST/DELETE | `/article/{id}/tags` | owner | — | 挂载/取消标签（≤5，新建过 DFA 闸门） |

匿名白名单（网关层）：`/article/list` · `/article/{id}` · `/article/*` · `/article/hot` · `/book/*` · `/feed/articles` · `/feed/books`（写端点仍 @SaCheckLogin 收口）。

## 五、Feign 提供端（`ContentFeignProviderController implements ContentClient`，前缀 `/api/feign/content`）

网关不路由 `/api/feign/**`；送审事件不含正文，review 凭定位键回查。

- ✅ `GET /get` → `Result<ContentDTO>`（机审回查正文，fail-fast）
- ✅ `GET /article-images` → `Result<List<ArticleImageRefDTO>>`（配图复审反查，fail-fast）
- ✅ `GET /article-ids-by-file` → `Result<List<Long>>`（按 idx_file_id 反查引用文章，图片复审再聚合用）

## 六、Service 能力（按业务域）

- `ArticleService`（ArticleServiceImpl）：createAndPublish（A1 校验→A2 免审直落→A3 CAS 0→1）/ saveDraftRow / update（方案B）/ submit / delete（必发 unpublished）/ getDetail（可见性判定链）/ listPublic / listHot / listMy
- `ArticlePublishService`（ArticlePublishServiceImpl）：publishBatch（逐篇独立事务+回执）/ batchVisibility / previewCheck / publishDueScheduled（定时发布到点扫描）
- `BookService`（BookServiceImpl）：书籍/章节 CRUD / addChapter / reorderChapters（整单校验+两阶段偏移）/ getDetail / pageChapters / getChapterDetail（可见性 visibilities 按查看者计算）
- `DraftService`（DraftServiceImpl）：快照 UPSERT（单行覆盖只存最新）/ baseVersion 乐观锁 / 归属校验 / 发布回填 article_id
- `FavoriteService`（FavoriteServiceImpl）：幂等收藏/取消 / 目标可见性校验 / 我的收藏多态 join 分页 / isFavorited（FavoriteStateSupport 单点收口）；**11.9 起收藏计数排除作者自收藏**（文章取 `article.author_id`、书籍取 `book.author_id`，加/减同一判定来源）
- `LikeService`（LikeServiceImpl，批次十一 11.8）：点赞/取消（`user_like` 幂等）/ 目标可见性校验 / 自赞拒绝 / `article_stat.book_stat` 计数原子增减（下限保护）/ 提交后发 search 增量事件
- `FeedFeedbackService`（FeedFeedbackServiceImpl，批次十一 11.11）：负反馈提交/撤销 / 类型与 stream 白名单 / `insert ignore` 幂等 / 变更后按用户单键失效名单缓存
- `FeedFeedbackSupport`（service/support，批次十一 11.11）：负反馈名单读取（30 天窗口 + Redis 单键缓存 TTL 300s）/ 按用户失效（不做全量 evict）/ 建池与切片统一取用
- `FeedService`（FeedServiceImpl）：缓存三防（互斥重建/空值短缓存/TTL 抖动）/ recheckFilter 展示前回查 / loadFromDb 读榜降级
- `FeedStreamService`（FeedStreamServiceImpl，批次十）：文章/书籍游标式无限刷；四路 DB 召回+打分+打散 / 快照代次与游标 / 建池与切片双重曝光去重 / 三级降级（`logDegrade` 单入口留痕+指标打点）/ 画像缓存读取
- `ReadingHistoryService`（ReadingHistoryServiceImpl）：单表双类型 UPSERT / 章节归属校验 / 仅前进不回退 / 懒删最旧（`history.max-per-user=1000` 条上限，在读保留 10 条不受限；`duration_ms` 暂不写入）
- `TagService`（TagServiceImpl）：前缀搜索/热门榜 / 挂载取消（≤5）/ 新建过 DFA 闸门
- `ArticleReviewWriteBackService`（ArticleReviewWriteBackServiceImpl）：moderation.result 五防线回写（bizType 自筛→SUSPICIOUS 忽略→scene 分流→陈旧性防线→CAS 白名单）/ 双链路聚合槽 / handleAppeal / handleUserBanned
- `ContentInternalService`（ContentInternalServiceImpl）：social 计数回写冗余列（updateCounters，D10 TODO 增量重算接线点）

## 七、数据模型（17 表摘要，DDL 见 Sql-Document §二 + `docker/init/mysql/schema/content-schema.sql`；原 `zz-*.sql` 补丁已于 2026-11-06 合并进 schema 并删除）

| 表 | 关键点 |
|---|---|
| `article` | zone / status / visibility / content_html（LONGTEXT，256KB 应用层兜底）/ cover_file_id / publish_batch_id / scheduled_publish_time（仅草稿态可设）/ view_cnt·like_cnt·comment_cnt（冗余计数，**镜像不参与排序**）/ content_hash / version；`idx_zone_status`；del_flag 逻辑删除 |
| `article_image` | article_id / draft_id（发布切换归属）/ file_id / position_order / align（对齐权威）；`idx_file_id` 支撑图片复审反查 |
| `article_draft` | article_id（发布回填）/ draft_version 乐观锁；单行 UPSERT 无历史版本 |
| `book` / `book_chapter` | book.status 三态；`uk(book_id, chapter_no)` 序号即排序键 |
| `reading_history` | biz_type（1-书籍 2-文章）+ biz_id，`uk_user_biz`；`idx_user_time` / `idx_user_time_all`；`duration_ms` 为 11.11 预留列（本次不写入、不统计） |
| `tag` / `article_tag` | uk_name 全局池 + use_count 冗余；`uk(article_id, tag_id)` 多对多 |
| `favorite` | target_type（1-文章 2-书籍），`uk_user_target`；`idx_target` / `idx_user_time`；**11.9：作者自收藏行照常写入，但统计不计** |
| `event_outbox` | event_id / payload / status（0-待发送 1-已发送 2-失败 3-放弃）/ retry_count；`idx_status_create` / `idx_sent_update` |
| `article_stat` | article_id 主键；collect_count / like_count / comment_count / view_count / stat_update_time；**11.1 统计真源**，`stat_update_time` 只随计数变更刷新（热度榜增量水位） |
| `book_stat` | book_id 主键；collect_count / reader_count / chapter_count / like_count / view_count / stat_update_time；`reader_count` 只增不减，`chapter_count` 只算公开可见章节 |
| `content_stat_window` | `uk_target_window(target_type, target_id, window_days)`；collect_count / read_count；11.3 窗口预计算（30/90 天，聚合排除作者自收藏） |
| `user_like` | `uk_user_target(user_id, target_type, target_id)`；`idx_target`；11.8 点赞幂等，取消物理删除 |
| `feed_exposure_daily` | `uk_feed_exposure_daily(stat_date, stream, target_id, identity_type)`；exposure_count / click_count；11.6/11.12 CTR 分身份 |
| `feed_feedback` | `uk_user_type_stream_target(user_id, feedback_type, stream, target_id, target_key)`；`idx_user_time`；11.11 负反馈 |
| `book_read_quality` | book_id 主键；finish_reader_count / eligible_reader_count / stat_update_time；11.13 只读完读统计，不参与排序 |

设计要点：统计表是**真源**、实体冗余列是**镜像**（排序/计数读 stat）；大字段延迟加载（列表 VO 不带 content_html）；MP 逻辑删除按字段名 delFlag 匹配（article/book/tag 三表），业务 CAS 仍须显式补 del_flag=0。

## 八、MQ 拓扑（content 侧）

- **生产**（`ContentEventProducer` 九个发送方法，统一「先落 event_outbox（status=0）→ 必达发送（静默重试 1 次）→ 成功置 1/失败置 2」）：
  - `sendSubmitted` → `content.submitted`（逻辑名，物理路由 = `moderation.exchange` / `moderation.text.submit`）
  - `sendImageSubmit` → `image.submit`（scene=ARTICLE_IMG_RE_REVIEW 免文不免图复审；与 file 上传预审同路由键，scene 唯一区分）
  - `sendPublished` → `content.published`（search/ai/notification 消费）
  - `sendUnpublished` → `content.unpublished`（**四下线路径必发**：软删/转私密/复审驳回/封禁连带，同路径调 UnpublishedCacheCleaner 三键失效）
  - `sendSocialLiked` → `social.liked`（**批次十一 11.8/11.14**：点赞成功 → search 侧 `likeCnt` +1）
  - `sendSocialUnliked` → `social.unliked`（**批次十一 11.8/11.14**：取消点赞 → search 侧 `likeCnt` −1，下限压 0；消费端幂等，重投不重复生效）
  - `sendArticleUpdated` / `sendViewCountFlushed` / `sendBookChanged`：文章编辑、浏览数落库、书籍变更同步（前批次已存在，非本批新增）
- **消费**（手动 ACK + `MqAckHelper`：成功 ack / 业务失败 warn 后 ack 防毒丸 / 异常指数退避延迟重投，耗尽 nack 入 DLQ；`MqTraceSupport` 恢复 traceId；prefetch=10）：
  - `moderation.result` → `ModerationResultConsumer`（bizType=1 自筛；PASS→1→2 / REJECT→1→3 首发 / 2→4 复审下架兜底；CAS 前比对 contentHash 防陈旧事件）
  - `moderation.appeal.result` → `ModerationAppealResultConsumer`（删 last-hash → 回查 ReviewClient.getPunishStatus → CAS 3→2 / 4→2 仅申诉来源 → 连带 FileClient.unquarantine → 发 published）
  - `user.banned` → `UserBannedConsumer`（批量 CAS 2→4 + 逐篇 unpublished + 三键失效 + 审计 operator=system(mq)）
- 路由键统一引用 `litisle-api` MqConstant，禁私自新建交换机（ContentRabbitConfig 声明）

## 九、配置（`ContentProperties` prefix `litisle.content`；job 类占位符直读项注明）

- `publish.batch-max=20`（Bean 绑定）
- `publish.scheduled.{enabled=true, interval-ms=60000, initial-delay-ms=60000}`（ArticleScheduledPublishJob 占位符直读）
- `feed.{cache-ttl-seconds=300, page-size=20}`（Bean 绑定）
- `feed.exposure.{retain-days=30, cleanup-enabled=true, cleanup-batch-size=1000, cleanup-interval-ms=86400000, cleanup-initial-delay-ms=300000}`（**批次十一 11.6**；清理任务占位符直读）
- `feed.feedback.{window-days=30, cache-ttl-seconds=300}`（**批次十一 11.11**：屏蔽生效窗口与名单缓存 TTL，Bean 绑定）
- `like.{rate-limit-window-seconds=60, rate-limit-max-count=20}`（**批次十一 11.8**：点赞运行时限流，`LikeRateLimitSupport` 读取；注解阈值无法配置化故改运行时）
- `stat.window.{enabled=true, short-days=30, long-days=90}` + `stat.window.interval-ms / initial-delay-ms`（11.3 窗口任务，后者占位符直读）
- `stat.reconcile.{enabled=true, diff-rows-threshold=50}` + `stat.reconcile.interval-ms / initial-delay-ms`（11.16 对账任务，后者占位符直读）
- `stat.read-quality.{enabled=true, interval-ms=86400000, initial-delay-ms=600000}`（**11.13** 完读全量重算，占位符直读）
- `history.{trim-enabled=true, retain-days=90, max-per-user=1000, keep-in-progress=10, trim-batch-size=100}`（阅读历史裁剪，影响完读分母覆盖范围）
- `feed.recall=db`（Bean 绑定；`es` 为 P1 占位，配 es 仅 WARN 并按 db 执行）
- `feed.stream.{pool-size=300, recall-limit-per-path=100, snapshot-ttl-seconds=1800, max-active-snapshots=3, exploration-ratio=0.15, cold-start-exploration-ratio=0.30, read-penalty=0.5, read-window-days=30, fresh-half-life-hours=24, max-consecutive-same-author=1, max-consecutive-same-category=2, exposure-ttl-seconds=172800, new-book-days=7, new-book-quota=6, profile-sample-size=100, profile-cache-ttl-seconds=900}`（Bean 绑定，批次十）
- `feed.stream.article-weights.{collect=0.12, like=0.12, comment=0.08, view=0.03, fresh=0.30, zone=0.10, category=0.20}`（11.10 七项，`heat` 键保留兼容不再读取）/ `feed.stream.book-weights.{collect=0.4, read=0.3, fresh=0.2, finish=0.1}`（Bean 绑定，Nacos 可热调）
- `feed.stream.smoothing.{strength=5, prior=0.1}`（11.10 统一平滑，`read-strength`/`read-prior`/`heat-midpoint` 保留兼容不再读取）
- `management.endpoints.web.exposure.include=health,info,metrics,prometheus` + `management.metrics.tags.application=${spring.application.name}`（批次十收尾；未设 `management.server.port`，管理端点与业务 8085 共用）
- `feed.hot.{enabled=true, top-n=200, like-weight=3, comment-weight=2, view-weight=0.2, decay-exponent=1.5, hour-offset=2, full-refill-interval-ms=3600000}`（Bean 绑定；**11.10 起 like/comment/view/decay/hour-offset 五项保留兼容不再读取**，打分改 FeedScoreSupport 统一口径）+ `feed.hot.refill-{enabled=true, interval-ms=300000, initial-delay-ms=60000}`（FeedHotRankRefillJob 占位符直读）
- `moderation.{last-hash-ttl-hours=72, ttl-jitter-ratio=0.1}` + `moderation.retry.{max-count=5, base-delay-ms=30000}`（Bean 绑定）
- `outbox.{resend-enabled=true, resend-interval-ms=60000, initial-delay-ms=60000, max-retry-count=5, resend-batch-size=200, retain-days=7}`（EventOutboxResendJob 占位符直读）

热点公式（**11.10 起作废**）：旧 `score = (like×3 + comment×2 + view×0.2) / (发布小时数 + 2)^1.5` 已由 `FeedScoreSupport` 统一口径取代（article-weights 七项 + smoothing + fresh 半衰期，见 §九配置）。

## 十、限流与上限（`ContentLimitConstant`，窗口统一 60s，@RateLimiter(USER)）

| 常量 | 值 | 场景 |
|---|---|---|
| `PUBLISH_MAX_COUNT` | 10 | 发布/重提/建档 |
| `DRAFT_SAVE_MAX_COUNT` | 10 | 草稿保存 |
| `BATCH_PUBLISH_MAX_COUNT` | 3 | 批量发布 |
| `REORDER_MAX_COUNT` | 10 | 章节重排 |
| `PREVIEW_CHECK_MAX_COUNT` | 10 | 发布前预检 |
| `HISTORY_MAX_COUNT` | 2 | 历史上报（配合前端 30s 节流） |
| `FAVORITE_MAX_COUNT` | 20 | 收藏/取消 |
| `FEED_MAX_COUNT` | 60 | 推荐流（登录按 userId） |
| `FEED_ANON_MAX_COUNT` | 20 | 推荐流匿名（按客户端 IP；与登录配额分开，批次十） |
| `TAG_SEARCH_MAX_COUNT` | 30 | 标签搜索防爬 |
| `LIKE_MAX_COUNT` | 20 | 点赞/取消（11.8；运行时限流，阈值 `litisle.content.like.rate-limit-max-count` 可配） |
| `FEEDBACK_MAX_COUNT` | 20 | 负反馈提交/撤销（11.11） |
| `TAG_MAX_PER_ARTICLE` | 5 | 单篇标签上限（代码常量，未接 yml） |
| `ARTICLE_CONTENT_MAX_BYTES` | 256KB | 正文硬上限（发布/建档/编辑/草稿四路收口） |
| `HISTORY_MAX_PER_USER` | 1000 | 单用户每类型历史条数（写入懒删最旧，在读保留 10 条不受限） |
| `BOOK_CHAPTER_MAX` | 1000 | 单书章节上限 |
| `BOOK_CHAPTER_REORDER_OFFSET` | 10,000,000 | 重排两阶段偏移临时区偏移量 |
| `TAG_NAME_MAX_LENGTH` / `TAG_HOT_TOP_N` / `TAG_SEARCH_RESULT_LIMIT` / `TAG_HOT_CACHE_TTL_SECONDS` | 64 / 10 / 10 / 300 | 标签域辅助上限 |

## 十一、错误码（common-core `ErrorCode`，300xx = content 域）

| 码 | 常量 | 触发场景 |
|---|---|---|
| 30001 | `ARTICLE_NOT_FOUND` | 文章不存在/软删/不可见（仅自己按不存在处理不暴露存在性） |
| 30002 | `ARTICLE_OFFLINE` | 文章已下架 |
| 30004 | `BOOK_NOT_FOUND` | 书籍不存在/软删 |
| 30005 | `CONTENT_EMPTY` | 标题或内容为空（图文同空） |
| 30007 | `ARTICLE_PARAM_INVALID` | 参数校验失败（标题>100、状态机拒绝、定时时间非未来、互斥同传等） |
| 30008 | `ARTICLE_ZONE_DENIED` | zone=2 非 AUTHOR；一篇仅一分区冲突 |
| 30009 | `ARTICLE_IMG_REFUSED` | 插图/封面非本人上传或 bizType≠ARTICLE_IMG；错误信息携带「第 N 张图片(file_id=xxx)」定位 |
| 30010 | `ARTICLE_DRAFT_CONFLICT` | baseVersion 落后 draft_version |
| 30011 | `ARTICLE_BATCH_EXCEEDED` | 单批 > batch-max(20) |
| 30012 | `BOOK_CHAPTER_MISMATCH` | 章节与书籍不匹配（防伪造跨书章节） |
| 30013 | `ARTICLE_PASSWORD_ERROR` | 密码访问已下线（阶段9），公共契约保留不删 |
| 30014 | `ARTICLE_TAG_EXCEEDED` | 单篇标签 > 5 |
| 30015 | `BOOK_CHAPTER_NO_CONFLICT` | 章节序号已被占用 |
| 30016 | `BOOK_STATUS_INVALID` | 书籍状态不允许该操作（如下架书拒重排） |
| 30017 | `BOOK_COVER_REFUSED` | 书籍封面 fileId 归属校验失败 |
| 30018 | `CONTENT_TOO_LONG` | 正文 > 256KB（错误信息含长度/上限，引导拆分书籍章节） |
| 30019 | `BOOK_CHAPTER_REORDER_INVALID` | 重排清单与本书章节不一致或新序非 1..N |
| 40002 | `ALREADY_LIKED` | 重复点赞（11.8，user_like 唯一键幂等前置校验；前端据此把按钮态归位） |
| 40004 | `LIKE_TARGET_NOT_VISIBLE` | 点赞目标非已发布公开态（含软删/下架），按不可见拒绝不暴露存在性 |
| 40005 | `LIKE_SELF_FORBIDDEN` | 作者不可赞自己的文章/书籍（与收藏口径对齐） |
| 70001 | `SENSITIVE_WORD_DETECTED` | DFA 黑名单词拦截（发布/摘要/新建标签闸门） |
| — | `NO_PERMISSION` / `TOO_MANY_REQUESTS` / `PARAM_ERROR` | 10014 / 10005 / 10001（通用） |

## 十二、Redis 键（`ContentBizConstant` 收口，禁业务类私拼键）

- `content:follow:relation:{ownerId:viewerId}`：关注关系缓存，TTL 60s ±10% 抖动（值 1-关注 0-非关注，负缓存防穿透）
- `content:article:review-agg:{articleId}`：双链路聚合槽（Hash field text/img → verdict），TTL 7200s
- `content:article:view:pending:{articleId}`：浏览计数增量（INCR 累计，ViewCountFlushJob GETDEL 刷回）
- `content:article:view:dedup:{articleId:visitorHash}`：浏览去重窗口，TTL 86400s（PV+去重口径，非纯 UV）
- 批次十推荐流（`FeedConstant` 收口）：
  - `content:feed:profile:u{userId}` / `content:feed:profile:a{anonId}`：画像缓存，TTL 900s（阅读行为不主动失效）
  - `content:feed:exposed:{stream}:{identity}:{yyyyMMdd}`：当日曝光 Set，TTL 172800s；建池与切片各过滤一次
  - `content:feed:snapshot:{scope}` / `content:feed:snapshot:last:{scope}` / `content:feed:snapshot:gen:{scope}`：快照本体 / last-good 指针 / 代次计数器（INCR）
  - `rate_limit:feed:user:{userId}:stream` / `rate_limit:feed:ip:{ip}:stream`：频控键（文章流与书籍流共用）
- 批次十一新增：
  - `content:feed:feedback:{stream}:u{userId}`：负反馈名单缓存（11.11，TTL 300s、窗口 30 天；变更时**只删该用户该流键**，不全量 evict）
  - `rate_limit:like:user:{userId}`：点赞频控键（11.8，运行时限流复用同一固定窗口脚本）
  - `content:stat:book-read-quality`（锁键，`CacheConstant.LOCK_PREFIX` 前缀）：完读全量重算分布式锁（11.13）
  - 曝光/点击去重沿用 `content:feed:exposed:*` Set；点击另记 Set（同 stream/identity/日期维度），只做日聚合入库，不常驻业务缓存
- 热度榜 ZSet 键与 last-hash/text-result/img-result 键：属 review 域契约（见 api-index §ModerationConstant），content 侧经 `FeedHotRankSupport` / `ContentLastHashRedisAdapter` / `UnpublishedCacheCleaner` 读写，不私拼

## 十三、定时任务（9 个，范式统一：@Scheduled + enabled 开关 + 分布式锁单点 + fail-safe 吞异常下轮重试）

| 任务 | 周期（默认） | 职责 |
|---|---|---|
| `FeedHotRankRefillJob` | 5min（启动延迟 1min） | refillAuto 三分支：榜缺失全量重建 / 距上次全量 ≥1h 全量校正（影子键+rename 原子换榜）/ 其余增量灌榜（水位 ZADD+裁剪）；榜 TTL 30min 死亡自愈降级 |
| `ViewCountFlushJob` | 周期任务 | GETDEL 取浏览增量刷回 `article_stat.view_count`（并回写 article.view_cnt 镜像 + 发 view-count-flushed 事件） |
| `TagCountReconcileJob` | 定时对账 | 校正 tag.use_count 异步计数漂移 |
| `ArticleScheduledPublishJob` | 1min | 扫描到点草稿复用 publishBatch 送审链路（单轮 ≤100 篇）；发布/失败均清定时字段 |
| `EventOutboxResendJob` | 1min | 扫 event_outbox 待发送/失败行补发（retry 达上限置放弃留痕）；已发送行 7 天后清理 |
| `ContentStatWindowJob` | 按日（**11.3**） | 重算 `content_stat_window` 30/90 天窗口值（收藏数/阅读人数，收藏排除作者自收藏）；推荐排序读窗口值、缺失回退累计值 |
| `ContentStatReconcileJob` | 按日（**11.16**） | 比对 stat 表与源表（收藏/浏览/点赞/评论等），差异超阈值 ERROR 告警；**只观察不自动修正** |
| `FeedExposureCleanupJob` | 按日（**11.6**） | 清理超过 `retain-days` 的 `feed_exposure_daily` 行（分批删除） |
| `BookReadQualityJob` | 按日（**11.13**） | 全量重算 `book_read_quality`（分子 `finish_reader_count` / 分母 `eligible_reader_count`）；分布式锁 + 单条 upsert，失败保留上一轮值 |

## 十四、开发导航（按需求找落点）

| 我要…… | 落点 |
|---|---|
| 加/改文章状态流转 | `ArticleStateMachine`（白名单）+ `ArticleMapper`（CAS 条件更新）+ `ArticleStatusEnum` |
| 改分区权限 | `ZoneRouter` + `ZoneEnum` |
| 调批量上限/缓存参数 | `ContentProperties`（yml `litisle.content`，Nacos 可覆盖热调） |
| 改限流阈值 | `ContentLimitConstant` |
| 加新事件 | `ContentEventProducer` 加发送方法（自动获得 outbox 必达）+ 路由键收口 litisle-api MqConstant |
| 改审核回写逻辑 | `ArticleReviewWriteBackServiceImpl`（五防线/聚合槽/last-hash） |
| 改推荐流排序 | `FeedScoreSupport`（**唯一打分入口**，文章/书籍/热度榜共用；权重读 `ContentProperties.feed.stream.article-weights/book-weights`） |
| 改无限刷召回/打分/降级 | `FeedStreamServiceImpl` + `ContentProperties.feed.stream` + `FeedSnapshotSupport` / `FeedProfileSupport`（调用口径见 Guide §六，架构见 Content-Architeche §3.3） |
| **查统计数字对不对** | stat 表（`article_stat` / `book_stat`）是真源；实体冗余列是镜像；差异看 `ContentStatReconcileJob` 日志与 `content_stat_window` 窗口值 |
| 改负反馈规则（类型/窗口/缓存） | `FeedFeedbackServiceImpl`（校验与幂等）+ `FeedFeedbackSupport`（30 天窗口/单键缓存）+ `ContentProperties.feed.feedback`；过滤落点在 `FeedStreamServiceImpl#buildArticleSnapshot/buildBookSnapshot/sliceArticlePage/sliceBookPage` |
| 改点赞口径 | `LikeServiceImpl`（幂等/自赞拒绝/计数）+ `ContentProperties.like`；取消点赞增量同步 search 走 `social.unliked` |
| 改完读判定/频率 | `BookReadQualityMapper#upsertFull`（口径 SQL）+ `BookReadQualityJob`（周期/开关）+ `ContentProperties.stat.readQuality`；**只读统计，未进排序** |
| 改可见性判定 | `ArticleServiceImpl.getDetail`（文章三档）+ `BookServiceImpl`（章节 visibilities）+ `FollowRelationCache` |
| 改下线收口 | 四路径统一发 `content.unpublished` + `UnpublishedCacheCleaner`（写入→失效对称铁律，禁只写不失效） |
| 改定时发布 | `ArticleScheduledPublishJob`（开关/锁）+ `ArticlePublishServiceImpl#publishDueScheduled`（扫描编排） |
| 跨服务调用契约 | `litisle-api` 的 `ContentClient`（Provider: `ContentFeignProviderController`）· `ReviewClient` / `FileClient` / `UserClient`（消费方） |
| 改发布保稿/图片定位文案 | `PublishDraftSnapshotSupport` + `ContentBizConstant`（IMG_CHECK_FAIL_MSG 等模板） |

## 十五、可观测指标（批次十收尾：`ContentMetrics` + Actuator，管理端点与业务端口共用 8085）

| Prometheus 指标 | 类型 | 标签 | 含义 / 打点位置 |
|---|---|---|---|
| `litisle_content_feed_degraded_total` | Counter | `stream=article|book`、`reason=redis-down|last-good|hot-empty|build-failed` | 推荐流降级次数；只在 `FeedStreamServiceImpl#logDegrade` 打点（降级单入口） |
| `litisle_content_feed_snapshot_created_total` | Counter | `stream`、`cold_start=true|false` | 快照创建次数（含空池快照）；文章/书籍两个 build 方法成功后打点 |
| `litisle_content_feed_profile_cache_hit_total` / `litisle_content_feed_profile_cache_miss_total` | Counter | 无 | 画像缓存命中 / 未命中回源；`FeedProfileSupport#load` 打点 |
| `litisle_content_stat_reconcile_diff` | **Gauge** | `type=article_collect|article_comment|article_like|article_view|book_collect|book_reader_under` | 最近一轮对账差异行数（**11.16**；启动预注册、每轮 `set()` 覆盖含 0——0 值恒可见，批次十二 12.2 收口；收藏源值已排除作者自收藏） |
| `litisle_content_feed_exposure_write_fail_total` | Counter | `stream=article|book` | 曝光写库失败次数（**11.6**，只记失败不记 CTR；失败不阻断翻页） |
| `litisle_content_feed_click_write_fail_total` | Counter | `stream` | 点击写库失败次数（**11.6**） |

- 暴露端点：`/actuator/health` · `/actuator/info` · `/actuator/metrics` · `/actuator/prometheus`（`management.endpoints.web.exposure.include`）。
- Prometheus 抓取：`docker/init/prometheus/prometheus.yml` → `litisle-services.targets` 的 `host.docker.internal:8085`。
- 告警：`docker/init/prometheus/alert-rules.yml` 的 `litisle-content` 组（`ContentFeedDegradedRateHigh` warning / `ContentFeedRedisDown` critical），规则不补 `or 0`。
- 口径与 P1 边界：见 [Search-Architecture.md](../Architecture/Search-Architecture.md) §10.8。
