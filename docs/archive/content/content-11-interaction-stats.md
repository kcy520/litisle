> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，内容迁自 [next_update.md](../../next/next_update.md)「批次十一：推荐互动数据与统计口径治理」（2026-11-07 归档）。条目原文、✅/⏳ 落地记录、依赖备注与验证证据**原样搬运，未压缩**。
> **归档范围**：11.1 / 11.2 / 11.3 / 11.4 / 11.5 / 11.6 / 11.7 / 11.8 / 11.9 / 11.10 / 11.12 / 11.13 / 11.14 / 11.15 / 11.16 / 11.17 共 16 项。
> **2026-11-07 二次整理（用户授权）**：批次内除 11.11 外的**全部内容**（背景含现状证据与相关文档、现状问题清单、方案 13 条、已确认决策、待确认项、任务项、执行注意项、环境验证清单、收尾记录）均已迁入本文件；[next_update.md](../../next/next_update.md) 批次十一只保留 11.11 一条与一行归档指针。**本文件为唯一完整副本**。
> **批次十一已全部归档（2026-11-09）**：11.11 反作弊与负反馈登记的三个部分均已迁入本文件——负反馈原文见「六」末尾 11.11 条；11.11-A/B 于 2026-11-09 补充归档至「六」末尾；11.11-C1~C3（延后）于 2026-11-09 归档至「六」末尾（含重启信号量化与 R2 处置降档评审结论）。主清单仅留指针。
> **互链**：活跃待办/验证清单见 [next_update.md](../../next/next_update.md)；挂起与预留项见 [Temporarily_not_implemented.md](../../next/temporarily/Temporarily_not_implemented.md)；同批次收口记录见 [content-12-batch12-closing.md](content-12-batch12-closing.md)；本模块其他归档见 [content-implemention-records.md](content-implemention-records.md)。

## 一、背景（迁自 next_update.md，原文搬运）

> 需求来源：用户 2026-11-06 基于「书籍推流互动数据现状分析」提出（原话要点）：①补齐三个不易察觉的失真与性能隐患；②参考主流方案给出修改方案；③优化历史记录方案；④收藏、点赞必须登录且校验权限；⑤文章推流同步向主流方案靠拢。
> 板块归属：**content 为主**（统计表、收藏/阅读/点赞写入、推荐打分、曝光归因）；**social 为最终归属**（点赞/评论计数最终由 social 承载，social 未落地前由 content 统计表提供真实计数，避免 `like_cnt/comment_cnt` 继续恒 0）；**api 承载契约**；**前端负责**曝光点击归因参数与点赞交互。
> 不重复建设：复用批次十两条流端点、快照、游标、曝光去重与三级降级；本批次只改「输入信号质量、统计口径、权限边界、归因闭环」，不新开推荐链路，也不回改 10.x 已勾条目。
> 与批次十边界：**11.1~11.5、11.7、11.10 是对 10.3/10.4/10.6 的补强**（10.4 的 `log1p(池内 max)`、10.6 的曝光 Set、10.5 的画像缓存均保留原实现，本批次替换其数据来源/算法）；**11.6、11.8、11.9、11.11~11.13 为新增能力**（11.9 属收藏域新增，不在 10.x 范围）。**10.7/10.8 仍按批次十旧口径验收**；本批次落地后另立 V6/V7 验证，避免同一验收对象漂移。
> 现状证据（2026-11-06 已逐文件核对）：[Book.java](../../../litisle-content/src/main/java/com/litisle/litislecontent/domain/entity/Book.java#L28-L104)、[content-schema.sql](../../../docker/init/mysql/schema/content-schema.sql#L35-L181)、[FeedStreamServiceImpl.java](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L580-L754)、[FeedProfileSupport.java](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedProfileSupport.java#L125-L133)、[FavoriteServiceImpl.java](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L30-L99)、[ReadingHistoryServiceImpl.java](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L383-L432)。
> 相关文档：[Content-Architeche.md](../../Architecture/Content-Architeche.md) §3.3、[Search-Architecture.md](../../Architecture/Search-Architecture.md) §10.8、[Content-Business-Guide.md](../../Study/Content-Business-Guide.md) §六。

## 二、现状问题清单（12 条，迁自 next_update.md 原文）

| # | 问题 | 证据（文件与行号） | 影响 | 优先级 |
| --- | --- | --- | --- | --- |
| 1 | 书籍无点赞、无浏览量字段；文章 `like_cnt/comment_cnt` 因 social 未落地恒 0 | [Book.java:28-104](../../../litisle-content/src/main/java/com/litisle/litislecontent/domain/entity/Book.java#L28-L104)；[content-schema.sql:96-111](../../../docker/init/mysql/schema/content-schema.sql#L96-L111)；[content-schema.sql:35-37](../../../docker/init/mysql/schema/content-schema.sql#L35-L37)；[FeedHotRankSupport.java:38-45](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java#L38-L45) | 无法做「高赞召回」和点赞率权重；文章热度榜退化为纯浏览量排序 | P1 |
| 2 | **失真①**：readerCount 取 `reading_history` 去重人数，而每用户每类型只保留最近 500 条、超出懒删最旧 | [ReadingHistoryMapper.java:113-123](../../../litisle-content/src/main/java/com/litisle/litislecontent/mapper/ReadingHistoryMapper.java#L113-L123)；[ReadingHistoryServiceImpl.java:383-384](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L383-L384)、[420-432](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L420-L432) | 用户继续阅读会把老书从历史中裁掉，老书 readerCount **反向下降**；推荐长期偏向新书 | P0 |
| 3 | 收藏是物理删除、无取消历史、无时间窗；且明确允许作者收藏自己作品，批次十 10.4 起 `collectCount` 把这部分计入排序 | [content-schema.sql:170-181](../../../docker/init/mysql/schema/content-schema.sql#L170-L181)；[FavoriteServiceImpl.java:37-39](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L37-L39)、[90-99](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L90-L99) | 取消即丢历史；作者自收藏污染权重；无法做 30/90 天收藏率 | P0（自收藏）；取消历史已拍板不做（2026-11-06） |
| 4 | **失真②**：归一化分母取「当前池内最大收藏/最大阅读」，是池内相对归一化 | [FeedStreamServiceImpl.java:641-684](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L641-L684) | 同一本书跨池分数最多可差约 6 倍（1 收藏在 max=100 池约 0.150，在 max=1 池为 1.0）；冷启动小池会放大弱信号 | P0 |
| 5 | **失真③**：新书判定按 `create_time`，新鲜度按 `update_time`；编辑简介/挂章会刷新 `update_time` | [FeedStreamServiceImpl.java:1101-1103](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L1101-L1103)、[1112-1118](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L1112-L1118) | 老书改简介就「变新」；新书边界与新鲜度口径不一致 | P0 |
| 6 | 曝光只写当日去重 Redis Set，无曝光日志、无点击归因 | [FeedConstant.java:103](../../../litisle-content/src/main/java/com/litisle/litislecontent/constant/FeedConstant.java#L103)；[FeedStreamServiceImpl.java:738-740](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L738-L740) | 算不出 CTR/CVR，推荐效果无法评估，也无法用反馈迭代 | P0 |
| 7 | `reading_history` 缺 `(biz_type,biz_id,...)` 前导索引；每翻一页重算章节/收藏/阅读三次聚合 | [content-schema.sql:131-141](../../../docker/init/mysql/schema/content-schema.sql#L131-L141)；[FeedStreamServiceImpl.java:721-727](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L721-L727) | 翻页 DB 抖动；书量与历史行数上来后 P99 明显恶化 | P0 |
| 8 | 书籍详情页不记浏览；章节 PV 记在文章维度，书籍「被打开次数」不存在 | [BookController.java:167-170](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/BookController.java#L167-L170)、[199-205](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/BookController.java#L199-L205)；[BookReadSupport.java:46-54](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/BookReadSupport.java#L46-L54) | 曝光→阅读无法归因；书籍缺少独立浏览量口径 | P1 |
| 9 | 章节数统计含草稿/私密/下架/仅关注者可见章节 | [BookChapterMapper.java:294-311](../../../litisle-content/src/main/java/com/litisle/litislecontent/mapper/BookChapterMapper.java#L294-L311)；[content-schema.sql:113-122](../../../docker/init/mysql/schema/content-schema.sql#L113-L122) | 卡片章节数与详情可见章节数不一致 | P2 |
| 10 | 无完读率、阅读时长、章节进度、追读率 | [content-schema.sql:131-141](../../../docker/init/mysql/schema/content-schema.sql#L131-L141) | 「打开即算阅读」，无法区分点开就走与追到最新章，内容质量不可度量 | P1 |
| 11 | 无负反馈、无刷量识别、无设备/IP 行为风控 | [FavoriteServiceImpl.java:37-39](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L37-L39) | 自互动/异常流量可直接污染计数与排序 | P1 |
| 12 | 匿名有曝光但无行为历史，登录/匿名 CTR 口径不能直接混用 | [FeedProfileSupport.java:125-133](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedProfileSupport.java#L125-L133) | CTR 分母含匿名、点击只来自登录时，转化率被系统性低估 | P1 |

> 问题→任务映射（保证每条问题都有可执行落点）：1→11.8/11.10；2→11.1/11.2/11.4；3→11.1（自收藏不计入统计）+11.9（权限复核）；4→11.3；5→11.7；6→11.6；7→11.5；8→11.1（`book_stat.view_count`）；9→11.1（`chapter_count` 口径）；10→11.13；11→11.11；12→11.12。

## 三、方案（13 条：P0 8 条 / P1 5 条，迁自 next_update.md 原文）

> 每条按「改什么 / 动哪张表、哪个类 / 要不要前端 / 风险」写实；所有新增计数字段与阈值走配置或常量，禁止散落硬编码。

**方案 1（P0，对应 11.1）计数与历史解耦**
- 改什么：新增 `article_stat` / `book_stat` 两张互动统计表，字段至少 `target_id(PK)、collect_count、reader_count、like_count、comment_count、view_count、chapter_count、stat_time、update_time`；收藏、首次阅读、点赞、浏览落库时**原子增量更新**（`count = count + 1`），取消收藏/取消点赞原子递减但 `reader_count` **只增不减**。`view_count` 口径：书籍详情/章节详情浏览记 `book_stat.view_count + 1`（沿用现有浏览去重，章节 PV 归书籍维度，不再只记文章维度）；`chapter_count` 只统计「章节对应文章未删 + 已发布 + 公开可见」的章节，由挂章/删章/文章状态与可见性变更时增量维护，回填按同一口径重算（卡片总数与详情页逐用户可见章节数存在差异，详情页保持现状逐用户判定）。写入模型（2026-11-06 已确认决策）：收藏/首次阅读/点赞/浏览在写入点**同步增量更新**，另配**每日对账任务**（源表聚合 vs 统计表，差异告警并按源表纠正）；**不引入 MQ/outbox**，避免消费延迟与重复消费补偿。
- 动哪张表/哪个类：新表 + `FavoriteServiceImpl`、`ReadingHistoryServiceImpl`（仅「首次插入」时 +1）、新 `LikeService`、浏览量刷回任务；推荐侧只读 `article_stat/book_stat`，不再从 `reading_history` 现算。
- 要不要前端：否（接口不变）。
- 风险：双写不一致 → 以源表定时对账/全量重建兜底；首次阅读判定依赖 `uk_user_biz`，并发重复插入会被唯一键挡住，增量需放在插入成功之后；回填脚本只能手工窗口执行。

**方案 2（P0，对应 11.2）推荐打分只读统计表**
- 改什么：`rankBooks` / `rankArticles` 的收藏、阅读、点赞输入全部改为读统计表；`sliceBookPage` / `sliceArticlePage` 的卡片计数也直接取统计表字段，不再每页 group by。
- 动哪张表/哪个类：`FeedStreamServiceImpl`（ranking + slice）、`FeedItemAssembler`；`FavoriteMapper#countBookCollects`、`ReadingHistoryMapper#countBookReaders` 退出推荐链路（保留给对账/管理端）。
- 要不要前端：否。
- 风险：统计表缺行时返回 0，需要「缺行按 0 处理 + 首次写入补行」；回填完成前不能切读。

**方案 3（P0，对应 11.3）计数平滑与时间窗**
- 改什么：把 `log1p(x)/log1p(池内 max)` 替换为稳定口径：率类（收藏率/阅读率/CTR）用 Wilson 下界；纯计数用贝叶斯收缩 `adj=(count+m*prior)/(1+m)`（`prior`=全站同计数中位数或均值，`m` 默认 20，配置化），再除以固定全局分位（如 P95，按日更新），**分母不再取池内最大值**。收藏/阅读另算 30/90 天窗口作为时效信号，全量作兜底；窗口查询走源表新索引。
- 动哪张表/哪个类：`FeedStreamServiceImpl#rankBooks/rankArticles`、`ContentProperties`（新增 `smoothing.m/prior-percentile/window-days`）；`FavoriteMapper`、`ReadingHistoryMapper` 增加时间窗聚合（仅构建统计/对账时用）。
- 要不要前端：否。
- 风险：先验与 `m` 取值影响排序，需要配置调参；窗口聚合会多一次查询，建议定时刷新进统计表而非每请求现算。

**方案 4（P0，对应 11.4）历史记录裁剪优化**
- 改什么：把「每用户每类型最多 500 条、超出懒删最旧」改为「**90 天时间窗保留 + 单用户上限 1000 条**」；「在读」代理口径=最近 7 天有更新且带 `chapter_id` 的书籍历史最多 10 条不裁（项目无完读标记，不能承诺真『未完成不裁』，此处用最后阅读时间代理并写注释）。
- 动哪张表/哪个类：`ReadingHistoryServiceImpl#trimOverflowHistory` 与常量；`reading_history` 不新增字段，无需迁移，旧行在下一次写入时按新规则收敛。
- 要不要前端：否。
- 风险：存储上限从 500 升到 1000（按用户×类型），需评估容量；**readerCount 不再依赖历史保留**后，裁剪只影响「我的历史」展示，不影响统计。

**方案 5（P0，对应 11.5）索引与翻页性能**
- 改什么：补 `reading_history idx_biz(biz_type,biz_id,update_time)`；统计表主键 `target_id` + 常用排序/时间列索引；翻页阶段禁止任何 group by，卡片计数一次批量 `IN` 查询统计表。
- 动哪张表/哪个类：`content-schema.sql`（手工 DDL 窗口）、`BookChapterMapper`/`FavoriteMapper`/`ReadingHistoryMapper` 的批量计数方法、`FeedStreamServiceImpl` 切片。
- 要不要前端：否。
- 风险：DDL 期间锁表/耗时（MySQL 8 online DDL 仍需窗口）；索引增加写放大，需观察 favorite/reading_history 写入 RT。

**方案 6（P0，对应 11.6）曝光计数与点击归因最小闭环**
- 改什么：在现有切片 `markExposed` 处同步 `INCR` 每日曝光计数（`content:feed:expo:{stream}:{yyyyMMdd}:{targetId}`），定时任务每 5 分钟落 `feed_exposure_daily(stat_date,stream,content_id,exposure_count,click_count)`（保留 30 天）；文章/书籍详情接口新增可选 `from=feed`、`snapshotId`，命中时 `INCR` 点击计数；CTR=`click_count/exposure_count`，按日/内容/流维度可查。
- 动哪张表/哪个类：`FeedStreamServiceImpl`（曝光计数）、`FeedExposureFlushJob`（新增）、`ArticleController`/`BookController`（点击归因）、新表 `feed_exposure_daily`。
- 要不要前端：**是**（跳详情带 `from=feed` 与 `snapshotId`）。
- 风险：曝光写 Redis 增加一次 pipeline 写，需批量/采样；点击依赖前端参数，漏传时只统计曝光不统计点击（CTR 偏低，需在报表标注）。

**方案 7（P0，对应 11.7）新鲜度口径统一**
- 改什么：P0 先零 DDL 统一为「内容首次可被推荐的时间」：书籍用 `create_time`，文章用 `create_time`（不再用 `update_time`）；新书判定与新鲜度共用同一基准字段，编辑不刷新。P1 再新增 `publish_time` 列并在首次发布/首次过审时写入，存量已发布行按 `create_time` 回填，彻底区分「草稿创建」与「发布」。
- 动哪张表/哪个类：`FeedStreamServiceImpl#freshScore/isNewBook`；P1 涉及 `article`/`book` 表与发布/过审写入点。
- 要不要前端：否。
- 风险：P0 用 `create_time` 时，长期存草稿后发布的老文章会被判「不新鲜」；P1 加列需要 DDL 与回填，需独立窗口。

**方案 8（P0，对应 11.8）点赞体系与权限**
- 改什么：新增 `user_like(user_id,target_type,target_id,create_time)`，唯一键 `uk_user_target(user_id,target_type,target_id)`；新增 `POST/DELETE /like`，**强制登录**（未登录返回统一未授权码，不混用 403），写入前走与收藏相同的目标可见性校验（私密/下架/不存在统一 NOT_FOUND）；点赞幂等，`article_stat/book_stat.like_count` 原子增减；作者自赞**硬禁止**（2026-11-06 已确认决策）：点赞人 = 内容作者时直接拒绝并返回**明确业务码**，不复用 NOT_FOUND（避免与「不可见」混淆）；原「允许自赞但仅不计入统计」的口径已被取代。
- 动哪张表/哪个类：新表 + 新 `LikeService`/`LikeController` + 统计表；`litisle-api` 加契约；social 后续接评论时复用同一统计表。
- 要不要前端：**是**（点赞按钮与状态）。
- 风险：点赞是高频写，需按 target 聚合后再落统计表防热点行；与 social 未来点赞表存在归属冲突，需在 api 层先定契约避免二次迁移。

**方案 9（P1，对应 11.9）收藏权限复核与作者自收藏排除**
- 改什么：收藏现有「登录 + 目标可见性」已满足（控制器 `@SaCheckLogin` 见 [FavoriteController.java:50-71](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/FavoriteController.java#L50-L71)，Service 目标校验见 [FavoriteServiceImpl.java:63-72](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L63-L72)），本项只做两件事：①**复核**收藏/取消收藏接口「必须登录 + 目标可见性」不回退（现状已满足，补测即可）；②统计写入**排除作者自收藏**（比较目标作者 `author_id`，不等于拒绝收藏）。取消收藏保持**物理删除**、**不记取消历史**、**不建 `favorite_event` 审计表**（2026-11-06 已确认决策；原「逻辑删除 / 审计表」选项已被取代）。
- 动哪张表/哪个类：`FavoriteServiceImpl`、统计表；不新增 `favorite_event`。
- 要不要前端：否。
- 风险：统计排除需要拿到目标作者 ID——现有 `checkTargetReadable` 只做校验不返回作者，落地时改为返回 `authorId` 或复用已查出的 `Article`/`Book`，否则统计写入无法判断自收藏；**原「逻辑删除与 `uk_user_target` 冲突 / 审计表写量」风险已被 2026-11-06 决策取代，不再成立**。

**方案 10（P1，对应 11.10）文章流对齐主流**
- 改什么：文章流复用同一套统计表、平滑、时间窗、曝光点击闭环；`FeedHotRankSupport` 的 `like_cnt/comment_cnt` 改读统计表（social 落地前由 content 统计表占位，不再恒 0）；文章 `stat_update_time` 继续作为热度增量灌榜时间列，不与 `update_time` 混用。
- 动哪张表/哪个类：`FeedHotRankSupport`、`FeedStreamServiceImpl#rankArticles`、`article_stat`、`feed_exposure_daily`；`article` 现有 `like_cnt/comment_cnt` 列保留但改为统计表回写（或只读统计表）。
- 要不要前端：**是**（仅点击归因与点赞按钮，曝光无感）。
- 风险：两套计数（列 vs 统计表）并存期需明确唯一真源，建议统计表为真源、列仅兼容/回写。

**方案 11（P1，对应 11.11）反作弊与负反馈**
- 改什么：先做规则层：作者自互动过滤、同设备/IP 短时间异常点赞/收藏阈值、曝光不计数异常流量；再做「不感兴趣」：新增 `dislike(user_id,target_type,target_id,reason)` 或 Redis 负反馈键，推荐侧降权/剔除。P1 登记，达到触发条件再实施。
- 动哪张表/哪个类：新 `user_dislike` 表或 Redis 键、`FeedStreamServiceImpl` 过滤/降权、风控规则类。
- 要不要前端：**是**（不感兴趣入口）。
- 风险：误杀正常用户；规则阈值需要数据基线，先影子统计再启用。

**方案 12（P1，对应 11.12）匿名统计口径**
- 改什么：明确匿名曝光计入曝光分母、匿名点击若不带身份则按设备/IP 计入单独标签；CTR 报表区分 `identity=login/anon`，不把匿名曝光与登录点击混算。匿名仍不落 `reading_history`（沿用现口径）。
- 动哪张表/哪个类：`FeedExposureFlushJob` 的维度、`feed_exposure_daily` 增 `identity_type` 列；推荐打分 P1 不直接使用匿名 CTR。
- 要不要前端：**是**（匿名点击归因沿用 `X-Anon-Id`）。
- 风险：匿名去重能力弱，CTR 仅作参考；不能据此对匿名用户做强个性化。

**方案 13（P1，对应 11.13）阅读质量信号**
- 改什么：先用零前端口径补「粗完读率」：以 `reading_history.chapter_id` 反查 `book_chapter.chapter_no`，配合 `book_stat.chapter_count` 得出「读到第几章 / 总章数」；追读率=当日读过该书、次日仍读同一书的登录用户占比。阅读时长需前端心跳上报，作为 P1 可选子项，不在 P0 引入。所有信号先只落统计与报表，**不进入 P0 排序权重**，观察分布后再定。
- 动哪张表/哪个类：`reading_history`（只读）、`book_chapter`（章节序号）、`book_stat` 扩展 `finish_user_count/avg_progress` 或新 `book_read_quality` 表、阅读上报接口（时长子项需新增字段）。
- 要不要前端：**是**（仅时长心跳需要；粗完读率与追读率可先零前端推算）。
- 风险：无时长/无完读标记时，「读到最新章」只是代理口径，不能等同真实读完；心跳上报会增加写量，需采样与批量落库。

## 四、已确认决策（5 条，2026-11-06 拍板，迁自 next_update.md 原文）

1. **统计表写入：同步增量 + 每日对账**（2026-11-06 拍板）。收藏、首次阅读、点赞、浏览在各自写入点同步增量更新 `article_stat/book_stat`；另配每日对账任务（源表聚合 vs 统计表，差异告警并按源表纠正）；**不引入 MQ/outbox**，避免消费延迟与重复消费补偿。落地见 11.1。
2. **取消收藏：保持物理删除**（2026-11-06 拍板）。不记取消历史、不建 `favorite_event` 审计表；11.9 收敛为「复核登录 + 目标可见性（现状已满足，补测即可）」与「统计排除作者自收藏」两件事，保持 **P1，不虚升 P0**。
3. **作者自赞：硬禁止**（2026-11-06 拍板）。11.8 验收要求三件事同时满足：①必须登录；②目标必须可见；③点赞人 ≠ 作者本人（返回明确业务码），不是「自赞允许但不计入统计」。
4. **CTR 采用中小项目方案**（2026-11-06 拍板）。原问题「是否接受前端改造做点击归因」现决定：接受，但按中小项目口径——**不做埋点 SDK、不做 Kafka、不做离线数仓**；采用「跳转 URL 带来源参数 `from=feed&snapshotId`（可选 `position`）+ 服务端按日聚合 `feed_exposure_daily`」的最小闭环，按天看趋势。落地见 11.6。
5. **匿名曝光/点击进入 CTR 分母，但单独打 `identity_type=anon`**（2026-11-06 拍板）。登录 CTR 只看登录口径，不混算。**前置约束**：点击侧必须稳定携带同一个 `X-Anon-Id`，否则匿名点击漏记会让比率系统性偏低；该约束作为 11.6/11.12 的验收前置。落地见 11.12。

**待确认项（0 条，暂无未决项；如有新问题在此登记并标注日期）**

> 原 2 条（点击归因、匿名 CTR 分母）已于 2026-11-06 转为已确认决策 4/5，原文要点见上，本节不再保留未决条目。

## 五、任务项（17 项：P0 11 项 / P1 6 项，迁自 next_update.md 原文）

> 其中 16 项（11.1~11.10、11.12~11.17）已完成并收录于下方「六、已完成任务条目」；11.11 已于 2026-11-09 全部归档（负反馈原文 + A/B 已完成补录「六」末尾；C1~C3 延后项随评审结论归档「六」末尾）。编号不重排。

## 六、已完成任务条目（迁自 next_update.md，原文搬运）

### 11.1 新建互动统计表并接入增量写（P0，必须）

- [x] 【P0｜前端配合：否】11.1 新建互动统计表并接入增量写（来源：问题 1/2/3；前置：无；涉及 content；验收：`article_stat`/`book_stat` 建成，收藏/首次阅读/点赞各写入点增量正确，取消收藏递减、读者数只增不减，回填后与源表 SQL 聚合一致；写入模型为同步增量 + 每日对账，不引入 MQ/outbox；DDL 与回填均在手工窗口执行；回填脚本/基线/分批窗口见 11.15，对账口径与指标见 11.16，本项不得省略这两步；2026-11-06 已在 litisle-mysql/litisle_content 建表，收藏同事务增减、首次阅读 +1、点赞入口、浏览量同步均已接入）

### 11.2 推荐打分与卡片计数改读统计表（P0，必须）

- [x] 【P0｜前端配合：否】11.2 推荐打分与卡片计数改读统计表（来源：问题 2/7；前置：11.1；涉及 content；验收：`rankBooks/rankArticles` 与 `sliceBookPage/sliceArticlePage` 不再调用 `countBookCollects/countBookReaders`，缺行按 0，翻页无 group by；`countBookCollects/countBookReaders/countBookChapters` 三个方法**保留并标注 `@Deprecated`**（禁止删除，留给对账/管理端复用））
  - ✅ 2026-11-06：`FeedStreamServiceImpl` 的 `rankArticles/rankBooks` 与 `sliceArticlePage/sliceBookPage/realtime*/lastGood*` 计数全部改读 `article_stat/book_stat`；新增 `loadArticleStats/loadBookStats`（按 ID 集合 `selectBatchIds` 一次批量查询，缺行按 0）；翻页路径已无任何 `countBook*` 调用与 group by；三个旧方法已加 `@Deprecated` + 保留说明；挂章/移除章节同步 `book_stat.chapter_count`；编译通过（应用未启动，单页聚合=0 以静态调用链核验）

### 11.3 计数平滑与时间窗（P0，必须）

- [x] 【P0｜前端配合：否】11.3 计数平滑与时间窗（来源：问题 4；前置：11.1/11.2；涉及 content；验收：同一本书在不同候选池的分数波动 < 5%，新书不再因 max=1 池被放大到满分，收藏/阅读 30/90 天窗口可配置，权重与 `m/prior` 全配置化）
  - ✅ 2026-11-06：新增 `content_stat_window`（DDL 已并入 `schema/content-schema.sql` 尾部「批次十一补充表」段；原 `zz-content-stat-window.sql` 已于 2026-11-06 合并后删除），按「目标类型+目标ID+窗口天数」唯一存 30/90 天收藏数与阅读人数；`ContentStatWindowJob` 每日全量重算（`fixedDelay` 24h、开关/窗口天数/周期全在 `litisle.content.stat.window`），翻页只按 ID 集合一次批量读，查询期零聚合。
  - ✅ 2026-11-06：书籍排序的 `log1p(x)/log1p(池内最大值)` 改为贝叶斯平滑 `(x+m*prior)/(x+m)`（`m` 默认 5、`prior` 默认 0.1，配置项 `litisle.content.feed.stream.smoothing.*`）；文章热度极值归一化改为池无关饱和 `heat/(heat+midpoint)`（中点默认 1.0）。实测同一本书 6102 在两池中得分均为 0.275（波动 0%），旧口径 0.5417→0.8000（波动 47.7%）；小池 max=1 时旧口径收藏/阅读均被放大到 1.0，新口径为 0.25。
  - ✅ 2026-11-06：窗口值回退链为「短窗口(30d) → 长窗口(90d) → 统计表累计值」；窗口任务关闭时直接用累计值。窗口阅读人数受阅读历史 90 天裁剪约束，超过 retain-days 的窗口会偏小（yml 注释已写明约束）。

### 11.4 历史裁剪策略优化（P0，必须）

- [x] 【P0｜前端配合：否】11.4 历史裁剪策略优化（来源：问题 2；前置：11.1；涉及 content；验收：裁剪改为 90 天 + 1000 条上限 + 在读代理保留 10 条，超限懒删；readerCount 与裁剪完全解耦，模拟删历史后统计不变；裁剪 SQL 由 offset 式改为「90 天条件 + 1000 上限 + 在读 10 条豁免」，`HISTORY_MAX_PER_USER` 改 1000 并同步常量注释与三处行内注释（保留常量，不删除）；90 天/1000/10 三个阈值全部配置化；超 90 天记录不再出现在 `pageMyHistory` 结果，落地前需确认产品预期）
  - ✅ 2026-11-06：`ReadingHistoryServiceImpl.trimOverflowHistory` 改为读 `litisle.content.history.*`（`trim-enabled`/`retain-days=90`/`max-per-user=1000`/`keep-in-progress=10`/`trim-batch-size=100`）；`ReadingHistoryMapper.deleteOverflowOldest` 改为「90 天时间窗 ∨ 超出 1000 条」为删除候选、最近 10 条在读书籍历史豁免，单批 100 行、最旧优先；`HISTORY_MAX_PER_USER` 改 1000 并保留常量作为配置默认值。
  - ✅ 2026-11-06：在读判定仅用现有字段：取书籍最大可见章节号（`book_chapter` join `article`，未删+已发布+公开），历史最后章节号小于该值即未读完；`chapter_id` 空/章节已删/无可见章节时保守保留。局限：读者读完最后一章后书又新增章节会重新变为在读；不加埋点。
  - ✅ 2026-11-06：事务内实测裁剪 SQL（用户 2003，时间窗置为未来强制过期）：书籍删 1 行、在读 630102 被保留；文章删 2 行；回滚后历史仍 5 行。`pageMyHistory` 的排序（update_time desc, id desc）与去重（`uk_user_biz`）不变，条数会因 90 天窗口/1000 上限减少——产品侧需确认「超 90 天历史不再展示」的预期。模拟删除 `biz_type=1` 全部历史后 `book_stat.reader_count` 保持 `6101=2,6102=1` 不变（已解耦）。

### 11.5 索引补齐与翻页去聚合（P0，必须）

- [x] 【P0｜前端配合：否】11.5 索引补齐与翻页去聚合（来源：问题 7；前置：11.1；涉及 content/MySQL；验收：`reading_history idx_biz(biz_type,biz_id,update_time)` 生效（EXPLAIN 命中），翻页只做统计表批量查询，单页聚合次数为 0；DDL 手工窗口执行；与批次十遗留 4 个索引（`article idx_feed_zone`/`idx_feed_category`、`book idx_feed`、`reading_history idx_biz`）合并同一窗口，`idx_biz` 直接建三列版、不重复建两列版；执行顺序见「七、执行注意项」第 2 条）
  - ✅ 2026-11-06：四个索引（`article idx_feed_zone`/`idx_feed_category`、`book idx_feed`、`reading_history idx_biz` 三列版）已直接写入 `schema/content-schema.sql` 的建表语句；原 `zz-content-feed-index.sql`（存储过程 + `information_schema` 幂等外壳）已于 2026-11-06 合并后删除。整理前已在 `litisle-mysql`/`litisle_content` 执行，二次执行四个索引全部 skip；EXPLAIN 结果见当次回报（`idx_biz`/`idx_feed` 命中，文章索引数据量太小被优化器选为 `idx_visibility`，`force index` 可命中且分类索引走反向扫描）

### 11.6 曝光计数与点击归因最小闭环（P0，必须）

- [x] 【P0｜前端配合：是】11.6 曝光计数与点击归因最小闭环（来源：问题 6；前置：11.1；涉及 content/api/前端；验收：按中小项目口径（跳转 URL 参数 + 日聚合表 `feed_exposure_daily`，不做埋点 SDK/Kafka/离线数仓，不做 trace/session 归因）——曝光按日/内容/流计数（保留 30 天）；详情带 `from=feed&snapshotId`（可选 `position`）记点击；未带归因参数时只计曝光、不计点击；可按天算出 `click_count/exposure_count`；`feed_exposure_daily` 需唯一键 `(stat_date,stream,content_id,identity_type)` 与 30 天过期清理任务；匿名曝光可被无限刷，CTR 只作趋势参考，不得直接进 P0 排序）
  - ⏳ 2026-11-06（代码与 SQL/Redis 已验证，HTTP 端到端待环境，暂不勾选）：新建 `feed_exposure_daily`（DDL 已并入 `schema/content-schema.sql` 尾部「批次十一补充表」段；原 `zz-feed-exposure.sql` 已于 2026-11-06 合并后删除），唯一键 `uk_feed_exposure_daily(stat_date,stream,target_id,identity_type)`；曝光在切片 `markExposed` 之后按页一条多行 upsert（`on duplicate key update exposure_count=exposure_count+1`），同身份同日同条只计 1 次仍由既有 Redis 曝光 Set 保证，未另建去重键。
  - ⏳ 2026-11-06：点击归因接入 `GET /api/content/article/{id}` 与 `GET /api/content/book/{id}`：`from=feed` + `snapshotId` 必须同时携带才计点击，`position` 可选（服务端计数不需要）；登录按 `userId`，匿名按 `X-Anon-Id`（匿名详情不经过 `AnonIdFilter`，Support 内直接校验 UUID v4，缺失即不计，避免临时生成新身份灌大 CTR）；同日同条用新增 Redis Set `content:feed:clicked:{stream}:{identity}:{yyyyMMdd}` 去重，且点击前校验当日曝光 Set 成员，保证 `click_count<=exposure_count`。
  - ⏳ 2026-11-06：`snapshotId` 不落库——计数维度是「日期+流+内容+身份口径」，不含快照代次，且同身份同日同条点击已去重，落库只会每行多存一段无用长字符串；仅作归因「必带」校验。
  - ⏳ 2026-11-06：清理选择「定时分批物理删除」而非表分区（日表数据量小、分区需持续维护 DDL 且唯一键必须含分区列，收益不成比例）；每日按 `litisle.content.feed.exposure.retain-days=30` 执行 `delete ... where stat_date < cutoff limit 1000` 循环。曝光/点击写失败只记 WARN + Counter（`litisle_content_feed_exposure_write_fail` / `litisle_content_feed_click_write_fail`），不阻断翻页与详情返回。
  - ⏳ 2026-11-06：实测（Redis 6380 AUTH 123）：曝光 SADD 首次 `:1`、重复 `:0`；点击 SADD 首次 `:1`、重复 `:0`（测试键已 DEL）。SQL 实测（种子文章 5001）：曝光 1→2（两个登录身份）、点击 1、重复点击被 Set 拦截后仍 1、匿名行 1/1；登录 CTR=1/2=0.5000、全站 CTR=2/3=0.6667、分母 0 返回 NULL；40 天前测试行被清理 SQL 删除，测试行已清理为 0。应用未启动，HTTP 端到端与指标暴露待环境。
  - ✅ 2026-11-07 状态回填（依据：「八、环境验证清单」第 7 项（11.6/11.12 曝光点击，✅ 实测）；应用启动见第 5 项）：曝光按日/流/内容增长、登录与匿名分行、带 `from=feed`+`snapshotId` 才 +1 点击、重复点击不增、CTR 两口径均实测通过。条目内「应用未启动，端到端待环境」为回填前记录，保留不动。

### 11.7 新鲜度与新书口径统一（P0，必须）

- [x] 【P0｜前端配合：否】11.7 新鲜度与新书口径统一（来源：问题 5；前置：无；涉及 content；验收：推荐新鲜度与新书判定共用同一时间字段，编辑简介/挂章不再改变排序；P0 使用 `create_time`，P1 再评估 `publish_time` 列）
  - ✅ 2026-11-06：`rankBooks` 新鲜度改用 `create_time`（移除 `update_time` 优先逻辑），与 `isNewBook` 共用同一字段；`freshScore` 入参更名 `createTime` 并注释说明不用 `update_time` 的原因（编辑简介/挂章会刷新，不代表内容新鲜度）；回滚造数验证：`create_time` 为两天前时分值 0.25，改 `update_time` 后仍 0.25（按旧口径会跳到 1），验证后已回滚

### 11.8 点赞体系与权限校验（P0，必须）

- [x] 【P0｜前端配合：是】11.8 点赞体系与权限校验（来源：问题 1/11 + 用户要求「点赞必须登录且有权限」；前置：11.1；涉及 content/api/前端；验收：三件事同时满足——①必须登录（未登录返回统一未授权码）；②目标必须可见（私密/下架/不存在返回 NOT_FOUND）；③点赞人 ≠ 作者本人（作者自赞返回明确业务码）；点赞/取消幂等且统计表计数正确；点赞接口沿用 `@RateLimiter` 60s 频控（新增 `LIKE_MAX_COUNT`，对齐收藏 20/60s）；计数用原子自增、递减用 `GREATEST(cnt-1,0)` 或 `where cnt>0`，并发唯一键冲突按幂等成功处理；点赞记录与计数同一事务；作者自赞新增独立业务码（不复用 NOT_FOUND）；点赞/取消点赞均需同步 search 增量（见 11.14））
  - ✅ 2026-11-06：新建 `user_like`（DDL 已并入 `schema/content-schema.sql`；原 `zz-user-like.sql` 已于 2026-11-06 合并后删除），唯一键 `uk_user_target(user_id,target_type,target_id)`；取消点赞物理删除，`delete` 影响行数 > 0 才递减。
  - ✅ 2026-11-06：接口 `POST/DELETE /api/content/like`，`@SaCheckLogin` 统一未授权码；新增业务码 `LIKE_TARGET_NOT_VISIBLE(40004)`（文章/书籍不存在、未发布、私密、下架统一返回，不混用 403）与 `LIKE_SELF_FORBIDDEN(40005)`（作者自赞硬禁止）；频控为 `LikeRateLimitSupport`（单用户维度、yml `litisle.content.like.rate-limit-*` 可调，复用 Redis 固定窗口脚本；注解阈值无法配置化故未用注解）。
  - ✅ 2026-11-06：幂等实测——首次点赞 `user_like` 1 行、`article_stat.like_count` 15→16；重复插入命中 `uk_user_target` 报 1062（应用捕获视为成功且不重复加）；取消后删 1 行、计数回 15；重复取消删 0 行不递减；`GREATEST` 下限实测 0-1 仍为 0；书籍点赞 6101 实测 `book_stat.like_count` 0→1→0。
  - ✅ 2026-11-06：文章点赞同事务同步 `article.like_cnt` 镜像（详情页 VO、`getArticleIndexDoc` 建索引、11.16 对账仍读该列），三处计数同步推进；编译通过（应用未启动，HTTP 接口端到端待环境）。

### 11.9 收藏权限复核与作者自收藏排除（P1）

- [x] 【P1｜前端配合：否】11.9 收藏权限复核与作者自收藏排除（来源：问题 3 + 用户要求「收藏必须登录且有权限」；前置：11.1；涉及 content；验收：**验证项（现状已满足，补测即可）**——①收藏接口 `@SaCheckLogin` + 目标可见性校验不回退；取消收藏接口 `@SaCheckLogin` + 仅能删除本人收藏（按 `user_id` 条件删除）；②统计写入排除作者自收藏；取消收藏保持物理删除、不记取消历史（2026-11-06 决策）；保持 P1，不虚升 P0）
  - ✅ 2026-11-06 权限复核（逐点看过，无退化）：`POST /favorite` 与 `DELETE /favorite` 都保留 `@SaCheckLogin` + `@RateLimiter(count=FAVORITE_MAX_COUNT=20, 60s, USER)`；`favorite()` 先 `checkTargetReadable` 再 insert（文章判定链=未删 → 本人放行 → 非本人须已发布 → PUBLIC 放行/FOLLOWERS 回查关注/PRIVATE 拒；书籍判定链=未删 → 下架仅作者可收藏）；`unfavorite()` 删除条件为 `eq(userId, 当前登录用户)`，不存在越权删除他人的分支。取消历史仍为物理删除（沿用 2026-11-06 决策）。
  - ✅ 2026-11-06 排除作者自收藏（实质改动）：`checkTargetReadable` 改为返回目标作者 ID——**文章取 `article.author_id`、书籍取 `book.author_id`**（两处来源不同，均在 `FavoriteServiceImpl` 内取，不跨模块拼装）；收藏成功后仅当 `authorId != userId` 才 `increaseCollect`；取消前用 `resolveTargetAuthor` 按同一来源取作者，`deleted > 0` 且非作者才 `decreaseCollect`，**保证「收藏没加、取消也不减」对称**；目标行被物理删除时 `resolveTargetAuthor` 返回 null，按「非本人」照常递减（保留旧行为，偏差交给对账暴露，不静默吞掉一次递减）。
  - ✅ 2026-11-06 回填与窗口口径（原 `zz-content-stat.sql` 已于 2026-11-06 合并后删除；存量回填不再单列脚本，新库改由 `seed/content-seed.sql` 写入自洽统计基线）：原回填脚本步骤 1/3 的收藏聚合加 `f.user_id <> coalesce(a.author_id, 0)` / `<> coalesce(b.author_id, 0)`（left join 兼容目标行已不存在的历史收藏，仍按非本人计数）；`ContentStatWindowMapper` 的 30/90 天窗口聚合同样排除作者自收藏——否则窗口值会计入、累计值不计入，推荐排序（优先读窗口）与卡片展示（读累计）会打架。
  - ✅ 2026-11-06 对账同步（11.16）：`ContentStatReconcileMapper` 4 条收藏查询（文章/书籍 × 源表有 + 统计残留）的源值全部改为同口径过滤，**改后差异应为 0**；「只有作者自收藏」的目标源值视为无记录、stat 应为 0，若写入侧漏判会由 statOnly 查询（`collect_count <> 0`）报出来。对账任务仅在源表聚合侧过滤，不修改历史数据。
  - ⏳ 2026-11-06 实测（**未执行**：接续时 Docker Desktop 未运行，启动后引擎随即退出、3307 短暂可连又断开，`docker.exe ps` 报 npipe 不存在；未改动任何容器与数据）：待环境恢复后按「八、环境验证清单」第 4 条跑——作者自收藏 → `article_stat.collect_count` 不动；普通用户收藏 → +1；两次取消 → 0 / 回到原值；书籍同验。SQL 口径已静态核对（`left join` 作者比较、`GREATEST` 下限），未取得数字前不勾选。
  - ✅ 2026-11-07 状态回填（依据：「八、环境验证清单」第 4 项（11.9 收藏，✅ 实测））：作者自收藏 `favorite`+1 但 stat 不动、普通用户收藏 0→1、作者取消不扣、普通取消 1→0；书籍 6101 作者自收藏后 `book_stat.collect_count` 仍为 2；30/90 天窗口复算与对账差异 0。条目内「未执行」为回填前记录，保留不动。

### 11.10 文章流对齐统计与闭环（P1）

- [x] 【P1｜前端配合：是】11.10 文章流对齐统计与闭环（来源：用户要求「文章推流也参考主流」；前置：11.1~11.8；涉及 content/api/前端；验收：文章流与书籍流共用统计表/平滑/曝光点击模型，`FeedHotRankSupport` 的点赞/评论取真实统计值，不再恒 0；`stat_update_time` 口径不变）
  - 2026-11-06 落地（静态+SQL+jshell 实测，应用未启动故端到端待环境 ⏳）：排序统计量统一改读 `article_stat`（收藏取 30/90 天窗口、缺失回退累计），`article.like_cnt` 明确为详情展示镜像列、仅保留索引用途；文章/书籍共用 `FeedScoreSupport` 的 `(x+m*prior)/(x+m)`（m=5/prior=0.1）与同一套 yml 参数；文章权重拆为 collect/like/comment/view/fresh/zone/category（默认 0.12/0.12/0.08/0.03/0.30/0.10/0.20，拆分理由见 `ContentProperties.ArticleWeights` 注释）；`FeedHotRankSupport` 与 `FeedStreamServiceImpl` 均调用 `FeedScoreSupport#computeArticleScore`，仅候选范围/画像命中不同；时间衰减、已读降权 -0.5、探索位、打散规则未改。实测：5004 点赞 30→0 分数 0.245196→0.152624、位次 2→4；5001 点赞 15→0 分数 0.302586→0.221586、位次 1→2；单篇池与全场池 5003 均为 0.089000（diff=0）；热门榜与推荐流冷启动对 5001~5005 五篇分数 diff 全为 0。

### 11.12 匿名统计口径（P1）

- [x] 【P1｜前端配合：是】11.12 匿名统计口径（来源：问题 12；前置：11.6；涉及 content/前端；验收：执行 2026-11-06 决策第 5 条——`feed_exposure_daily` 增 `identity_type(login|anon)`，匿名与登录分开统计，登录 CTR 不含匿名；前置约束：点击侧必须稳定带同一个 `X-Anon-Id`，否则匿名点击漏记会导致比率偏低；匿名仍不落阅读历史）
  - ⏳ 2026-11-06（代码与 SQL 已验证，HTTP/报表端到端待环境，暂不勾选）：`identity_type` 落库为 `login|anon`；登录 CTR = 仅 `identity_type='login'` 的 `click/exposure`，全站 CTR 去掉该条件（含匿名）。两条 SQL 收口在 `FeedExposureDailyMapper.selectCtrByTarget/selectDailyCtr`，由 `identityType` 传 `login` 或 `null` 区分口径，报表按日期区间直查。
  - ⏳ 2026-11-06：不做在线实时聚合、也不建第二张汇总结果表——`feed_exposure_daily` 本身就是日聚合结果，报表直查即可；再加汇总表只会多一份重复数据与一个同步点。分母为 0 时 CTR 返回 `NULL`（表示没有分母），不是 `0`（表示有曝光但无人点击）。
  - ⏳ 2026-11-06：指标只新增「曝光写失败」「点击写失败」两个 Counter（标签 `stream`），不为 CTR 本身建 Gauge。
  - ✅ 2026-11-07 状态回填（依据：「八、环境验证清单」第 7 项）：`identity_type=login|anon` 分行落库，登录 CTR 不含匿名（anon 0.1667 / login 0.2000），分母 0 返回 NULL，均已实测。

### 11.13 阅读质量信号（P1）

- [x] 【P1｜前端配合：是】11.13 阅读质量信号（来源：问题 10；前置：11.1/11.4；涉及 content/前端；验收：用 `reading_history.chapter_id` + `book_chapter.chapter_no` 可算出粗完读率与追读率并落统计，时长心跳作为 P1 可选子项；信号先只做统计展示，不改 P0 排序权重，缺上报时按「读到最新章」代理并标注口径）
  - ⏳ 2026-11-06（代码与 SQL 已验证，应用未启动，定时任务实跑待环境，暂不勾选）：新建只读统计表 `book_read_quality`（DDL 已并入 [content-schema.sql](../../../docker/init/mysql/schema/content-schema.sql)；原 `zz-book-read-quality.sql` 已于 2026-11-06 合并后删除），`book_id` 为主键，**同表存分子 `finish_reader_count` 与分母 `eligible_reader_count`**。分母不用 `book_stat.reader_count`（累计只增不减，与 `reading_history` 的 90 天裁剪不同源，两者相除会让比率长期偏低），而用「最后一次阅读章节能解析到该书公开可见章节的读者数」，与分子同源、比率可直接计算。
  - ⏳ 2026-11-06：判定口径——`reading_history` 实际只有 `chapter_id`（**没有 `chapter_no` 列**），章节号经 `chapter_id join book_chapter` 取得；某用户对某书最后阅读章节的 `chapter_no` ≥ 该书**最大可见章节号**即近似判为「追上进度」。可见章节只算 `article.visibility=1（公开）` + `status=2（已发布）` + `del_flag=0`。
  - ⏳ 2026-11-06：三个已知误差写在 [BookReadQuality](../../../litisle-content/src/main/java/com/litisle/litislecontent/domain/entity/BookReadQuality.java) 类注释，不藏在心里：①**打开即算阅读**——点开最后一章就离开会被判完读（假阳性）；②**书更新后回退**——新章节出现时，原本完读的用户变回未完成（假阴性）；③**只读最后一章**——只翻最后一章的用户历史上会被判完读。另补两条边界：追平后回看中间章会重判未完成（历史只存最后章节）、`visibility=3（仅关注者）`章节需社交关系，纯 SQL 不做个性化判定，统一按公开章节算。
  - ⏳ 2026-11-06：更新方式为**按日全量重算**（[BookReadQualityJob](../../../litisle-content/src/main/java/com/litisle/litislecontent/job/BookReadQualityJob.java)，`litisle.content.stat.read-quality.interval-ms=86400000`、`initial-delay-ms=600000`、`enabled` 可关；分布式锁 + 单条 `insert … on duplicate key update` 一次落表），**不选增量**：新章发布会把已完读用户改判未完成、历史裁剪会缩小分母，增量感知不到这类反向变化；`reading_history` 有 90 天上限、单次扫描量可控。
  - ⏳ 2026-11-06：**建议暂不进排序，先做只读统计**（与「避免一次改两个变量」的判断一致）：本轮若同时把新指标塞进书籍四项权重，分数漂移将无法区分是权重改动还是指标引入导致，归因不了。启用时机：积累一段时间后先看 `finish_reader_count/eligible_reader_count` 的分布与稳定性（确认代理口径与实际完读大体相符），再单独提权重校准变更，届时给出改动前后分数对照。
  - ⏳ 2026-11-06：实测（`upsert` SQL 已执行）：6101 `finish_reader_count=1`、`eligible_reader_count=1`、比率 `1.0000`（用户 2001 最后读第 2 章＝该书最大公开章；第 3 章 `visibility=2` 私有，不计入分母，用户 2003 恰好停在私有第 3 章故不进 6101 分母）；6102 `finish_reader_count=0`、`eligible_reader_count=1`、比率 `0.0000`（用户 2003 读第 2 章，该书 3 章全公开）；6103~6105 无有效历史，`0/0`、比率为 `NULL`（无分母，不是 0）。
  - ✅ 2026-11-07 状态回填（依据：「八、环境验证清单」第 9 项）：`BookReadQualityJob` 实跑 rows=10，6101=1/1、6102=0/1、6103~6105=0/0(NULL)，重复执行不放大。

### 11.14 互动计数跨模块同步（P0，必须）

- [x] 【P0｜前端配合：否】11.14 互动计数跨模块同步（来源：审查缺口 1：点赞上线后搜索结果页与详情页数字不一致；前置：11.1/11.8；涉及 content/search/api；验收：点赞/取消点赞后 `article_idx.like_cnt` 与详情页、推荐流一致；search 现只有 [QUEUE_SOCIAL_LIKED](../../../litisle-search/src/main/java/com/litisle/litislesearch/mq/HeatEventConsumer.java#L67-L90) 的 +1 消费、无取消点赞队列，必须新增负增量或绝对值事件；[getArticleIndexDoc](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ContentInternalServiceImpl.java#L320-L322) 与 [FeedHotRankSupport](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java#L308) 的计数来源统一：以 `article_stat` 为真源，或保留 `article.like_cnt/comment_cnt` 作镜像并同事务刷新 `stat_update_time`；`book_idx` 无计数字段，不同步（见注意项第 9 条））
  - ⏳ 2026-11-06（代码与静态链路完成，端到端待环境，暂不勾选）：content 侧 `LikeEventSupport` 在事务提交后（`TransactionSynchronization.afterCommit`）经 outbox 发布 `social.liked`（delta=+1）/ `social.unliked`（delta=-1）；契约层新增 `ROUTING_SOCIAL_UNLIKED=social.unliked`、`QUEUE_SOCIAL_UNLIKED=litisle.social.unliked.queue` 与 `SocialUnlikedEvent`；`SocialLikedEvent` 增加 `delta` 字段（旧消息缺失按 +1 兜底）。
  - ⏳ 2026-11-06：search 侧选择「新增取消点赞监听」而非在同一队列按 delta 区分——正负事件分队列便于分别观测/重投，且复用既有 `MqIdempotentHelper`（SETNX+eventId）、`MqAckHelper`（延迟重投）、`IndexDlqConsumer`（死信）链路，未另建基础设施；`HeatPendingService.applyLikeDelta(articleId, delta)` 统一正负累加；`IndexHeatFlushJob` 写回 `article_idx.likeCnt` 时 `Math.max(0, 现有值 + delta)` 做下限保护。
  - ⏳ 2026-11-06：书籍点赞不改索引——`BookIndexDoc` 确无 `likeCnt` 字段，书籍点赞只写 `book_stat.like_count` 并在类注释写明理由；文章点赞同时同事务刷新 `article.like_cnt` 镜像，保证详情页/首次建索引/对账与 `article_stat` 一致。三模块编译通过；端到端未验证（应用未启动，ES 中尚无 `article_idx` 索引），`user_like` 与 `article_stat` 已用种子数据实测一致。
  - ✅ 2026-11-07 状态回填（依据：「八、环境验证清单」第 6 项）：u2003 点赞后 stat 15→16、`article.like_cnt` 同步、search 消费 `social.liked` 后 ES `article_idx.like_cnt`=16；取消后 16→15、ES=15 不为负；重复取消无副作用。

### 11.15 统计表存量回填（P0，必须）

- [x] 【P0｜前端配合：否】11.15 统计表存量回填（来源：审查缺口 2：建表后存量从哪来；前置：11.1；涉及 content/MySQL；验收：回填脚本按 `favorite`→`collect_count`、`reading_history`→`reader_count`、`article.view_cnt`→`view_count`、`book_chapter`→`chapter_count`、`like/comment` 回填 0（social 未落地无真值）；基线=回填开始时刻，回填期间写入走同步增量、完成后才切读；分批提交（单批 ≤1000 行）避免长事务；进度日志可查；注明 `reading_history` 已按 500 条裁剪，回填的 `reader_count` 是「现存历史基线」，此后只增不减，老书数值会低于真实历史累计，报表需标注；原回填脚本 `docker/init/mysql/zz-content-stat.sql`（幂等 `ON DUPLICATE KEY UPDATE + GREATEST`，1000 条/批，2026-11-06 已执行且重复执行行数不放大）已于 2026-11-06 合并进 schema 后删除；新库的统计基线由 `seed/content-seed.sql` 提供）

### 11.16 统计对账任务与指标（P0，必须）

- [x] 【P0｜前端配合：否】11.16 统计对账任务与指标（来源：审查缺口 2/8：对账口径未细化；前置：11.1/11.15；涉及 content；验收：每日对账按源表聚合 vs 统计表；差异阈值写清（建议绝对差 ≥10 或相对差 ≥5% 告警），`reader_count` 只增不减故「源表小于统计表」不纠正；差异告警/自动纠正/差异日志落点明确；对账与回填进度复用 [ContentMetrics](../../../litisle-content/src/main/java/com/litisle/litislecontent/metrics/ContentMetrics.java#L78-L116) 新增指标；对账失败不得阻断业务写入；2026-11-06 已完成代码与配置（`ContentStatReconcileJob` + 6 类差异查询 + `ContentMetrics` 指标 + `diff-rows-threshold` 配置），对账 SQL 已手工实测差异 0，定时任务实跑待应用启动确认）
  - ✅ 2026-11-07 状态回填（依据：12.2 收口实测）：首轮 total=0 且 6 类 gauge 均 0.0；扰动 `article.like_cnt` 后 total=1 触发 ERROR，恢复后回 0，0 值可观测与阈值告警均有效。

### 11.17 文档与验收联动（P1）

- [x] 【P1｜前端配合：否】11.17 文档与验收联动（来源：审查缺口 9；前置：11.14~11.16；涉及 docs；验收：[Content-Business-Guide.md](../../Study/Content-Business-Guide.md#L552-L582) 补点赞接口/登录与可见性权限/错误码（现 §六 仍写 「social 未落地 like 恒 0」）；[content-index.md](../../index/content-index.md#L15) 表清单由 10 表增补 `article_stat`/`book_stat`/`user_like`/`feed_exposure_daily`，并补 REST 接口、MQ 拓扑、错误码；把 V6/V7 验收草案补入本文档（草案见「七、执行注意项」第 14 条））
  - ✅ 2026-11-06 [Content-Business-Guide](../../Study/Content-Business-Guide.md)：§四 收藏补「作者可收藏自己作品但统计不计」口径（文章/书籍作者来源不同）；§六 末尾新增「互动统计与推荐信号」整节——统计真源（stat 表真源、实体列镜像）、点赞接口与幂等、曝光点击 CTR 分身份、负反馈三维度与生效位置、粗完读口径与三条已知误差、三条「待确认」（90 天以上历史展示、分类双重编码、chapter_count 作者视角差异）。已知偏差（last-good 不带屏蔽过滤、曝光 Set 并发双计）按原样写入，未抹平。
  - ✅ 2026-11-06 [Content-Architeche](../../Architecture/Content-Architeche.md)：§四 数据模型由 10 表扩到 17 表，补 `article_stat`/`book_stat`/`content_stat_window`/`user_like`/`feed_exposure_daily`/`feed_feedback`/`book_read_quality` 的键与设计原因，并新增「统计真源口径 + 写入点一览」（收藏排除作者自收藏、窗口任务、完读任务、曝光点击、对账同口径）；§三 接口设计补 `/like`、`/feed/feedback` 与 `/article/{id}` 的点击归因副作用；§3.1 限流矩阵补 `LIKE_MAX_COUNT`、`FEEDBACK_MAX_COUNT`。
  - ✅ 2026-11-06 [content-index](../../index/content-index.md)：§四 REST 补 `/like`、`/feed/feedback` 与详情点击归因；§六 Service 补 `LikeService`/`FeedFeedbackService`/`FeedFeedbackSupport`，`FavoriteService` 标注 11.9 改动；§七 数据模型 10→17 表；§八 MQ 补 `social.liked`/`social.unliked` 与其余 5 个发送方法；§九 配置补 like/feed.feedback/stat.window/stat.reconcile/stat.read-quality/history；§十 限流补两条；§十二 Redis 键补负反馈名单键、点赞频控键、完读锁键；§十三 定时任务 5→9；§十四 导航补统计真源/负反馈/点赞/完读四条；§十五 指标补对账差异、曝光/点击写失败。
  - ✅ 2026-11-06 [Search-Architecture](../../Architecture/Search-Architecture.md) §十一：前端清单第 7 条由「P1 未启动」改为「后端已就绪，待前端」（三维度入口/撤销自存五元组/后端不提供名单查询接口标注为待确认），新增第 8 条「阅读时长心跳：仅预留接口」；处理约定补「单条当页生效、作者/分类下代生效」「负反馈不可匿名」「完读无需前端埋点」。
  - ✅ 2026-11-06 本文档（next_update.md）：11.9/11.17 标注落地项与 ⏳ 项；批次十一末尾新增「六、环境验证清单」，按依赖顺序 11 步写「跑什么 → 预期看到什么」。
  - ⏳ 2026-11-06：文档为静态更新，无需运行验证；其中引用的接口数字与错误码待应用启动后按环境验证清单逐条复核，若与实现不符再回改。

  - ✅ 2026-11-07 状态回填（依据：本条四个文档子项均已 ✅；末项明确「文档为静态更新，无需运行验证」）：Content-Business-Guide、Content-Architeche、content-index、Search-Architecture 与本文档均已更新，无剩余文档动作。

### 11.11 反作弊与负反馈登记（`[~]` 部分完成；**已完成部分原文留档**，任务本身仍挂 next_update.md）

> 说明：11.11 未归档（还有未做部分），但主清单已按要求精简为此前 ⏳ 记录的原文留档在此，保证负反馈落地细节与实测数字不丢失。

- [~] 【P1｜前端配合：是】11.11 反作弊与负反馈登记（部分完成）（来源：问题 11；前置：11.6 有曝光点击数据；涉及 content/api/前端；验收：登记作者自互动过滤、异常频次规则、「不感兴趣」入口与降权口径；达到触发条件后另拆实施项）
  - ⏳ 2026-11-06（负反馈代码与 SQL 已验证，HTTP 端到端待环境，暂不勾选）：新建 `feed_feedback`（DDL 已并入 [content-schema.sql](../../../docker/init/mysql/schema/content-schema.sql)；原 `zz-feed-feedback.sql` 已于 2026-11-06 合并后删除）。三类维度：1-不感兴趣（单条内容，`target_id`=article.id）/2-屏蔽作者（`target_id`=authorId）/3-屏蔽分类（`target_key`=分类名、`target_id`=0）；`stream` 列把文章流与书籍流的名单隔开（默认 `article`），避免同名 ID 串用。唯一键 `uk_user_type_stream_target(user_id, feedback_type, stream, target_id, target_key)` 防重复提交，提交走 `insert ignore`、重复提交按幂等成功返回。
  - ⏳ 2026-11-06：接口 `POST /api/content/feed/feedback`（提交）与 `DELETE /api/content/feed/feedback`（撤销），同一 DTO；两者 `@SaCheckLogin`，**必须登录**——匿名只有 `X-Anon-Id`，浏览器清缓存即换身份，做不了长期屏蔽，故匿名提交直接拒绝（统一未授权码）；频控沿用既有风格 `@RateLimiter(count=FEEDBACK_MAX_COUNT=20, timeUnit=SECONDS=60, keyType=USER)`，与收藏/点赞同档。**提供撤销**：误点概率高、按五元组 DELETE 成本极低，且撤销幂等（首次删 1 行、重复删 0 行），不额外记录撤销历史。
  - ⏳ 2026-11-06：召回过滤落点——屏蔽作者/分类在**建池阶段**（`buildArticleSnapshot`/`buildBookSnapshot`）生效；单条「不感兴趣」在建池 + **切片阶段**（`sliceArticlePage`/`sliceBookPage`）双重过滤，当前快照内立即剔除。名单缓存为按用户单键 `content:feed:feedback:{stream}:u{userId}`（TTL `cache-ttl-seconds=300`、查询窗口 `window-days=30`），变更时只失效该用户该流键，**不做全量 evict**。
  - ⏳ 2026-11-06：缓存一致性实际表现——①单条内容屏蔽：切片时二次过滤，用户**当页起**就不再看到该条；②作者/分类屏蔽：只在建池生效，旧快照已经建好的剩余页里可能仍出现被屏蔽内容，用户下一次刷（翻到底生成下一代快照，或快照 TTL 过期后重建）即完全消失；③不做主动全量失效，避免一次屏蔽清空所有用户快照的缓存雪崩。④Redis 不可用时的 `last-good` 降级路径未追加屏蔽过滤（降级只保证可用性，不在 Redis 故障时再打一次 DB）。
  - ⏳ 2026-11-06：阅读时长**只做预留不实现**——`reading_history.duration_ms` 已并入 [content-schema.sql](../../../docker/init/mysql/schema/content-schema.sql) 的 `reading_history` 建表语句（原 `zz-reading-duration-reserve.sql` 的 `information_schema` + `prepare` 幂等外壳仅用于存量库，已于 2026-11-06 合并后删除），上报接口 `PUT /api/content/reading-history` 新增可选 `durationMs` 入参并显式忽略；有效心跳口径（间隔/有效判定/聚合方式）留到前端方案确定后再定，不凭空定义。
  - ⏳ 2026-11-06：本轮只做负反馈登记；「作者自互动过滤」「异常频次规则」等反作弊项**未做**，仍按原验收要求另拆实施项。
  - ⏳ 2026-11-06：实测（测试用户 9999）：4 次 insert（含 1 次重复的「内容 5003」）落 3 行，唯一键生效；内容 5003 屏蔽后候选剩 5001/5002/5004/5005；作者 2002 屏蔽后候选为空（种子文章作者均为 2002）；分类「小说」屏蔽后剩 5002/5003/5004；DELETE 首次 1 行、重复 0 行。测试行已清理（`feed_feedback` 计数 0）。附注：种子 5001~5005 的 `article.category` 在库内为双重编码（`hex=C3A5C2B0…`，按 latin1→utf8mb4 解码后才是「小说/散文/现代诗/随笔」），分类过滤实测用解码后的值比较；该问题属存量种子数据，未修改数据。
  - 🔶 2026-11-07 状态回填为**部分完成**（依据：12.1 收口实测；范围见本条最后一个 ⏳ 子项「本轮只做负反馈登记」）：已完成负反馈三维度提交/撤销/幂等、当页剔除与下一代快照剔除（含 last-good 兜底过滤）；未做「作者自互动过滤」「异常频次规则」——属原设计明确不展开、另拆实施项，不是遗漏。

> **2026-11-09 追加归档（11.11-A/B）**：作者自浏览排除与推荐流作者自点击排除已实施并于 2026-11-08 运行态实测通过，按归档规则自主清单迁入本条（原文搬运，仅调整相对链接层级）；主清单仅保留 11.11-C1~C3（延后）与指针。

  - **11.11-A（必须）作者自浏览排除**：给 `ViewCounterSupport#recordView` 增加带 `authorId` 的重载，内部判断「浏览者 == 文章作者」直接 `return`；两个调用点（[ArticleController.java:170](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/ArticleController.java#L170)、[BookReadSupport.java:50](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/BookReadSupport.java#L50)）改走重载。依据：文章详情无条件调 `recordView`，章节阅读注释明确写「含匿名/作者本人」；`article_stat.view_count` 是文章流排序四个统计量之一（权重 0.03），是当前唯一仍会污染推荐权重的自互动。成本约 30 行、3 个文件，无 DDL、无新配置；历史数据不回滚。验收：作者本人浏览不再增加 `view_count`，他人与匿名计数不变。
  - **已拍板（2026-11-08｜第二批·必修，需回归）**：作者自浏览排除进入实施；回归文章详情、章节阅读、匿名刷流三种身份。
  - ✅ 2026-11-08（运行态实测通过｜文章侧 + 章节侧双路径）：`ViewCounterSupport` 带 `authorId` 的重载内部短路；`ArticleController` 的 `recordView` 在 `detail != null` 分支传 `detail.getAuthorId()`；`BookReadSupport` 改传 `bookAuthorId`。启动 auth/user/content 直连实测（服务需带 `--sa-token.token-name=Authorization` 才能识别 auth 签发的 `Authorization: Bearer <token>`，否则 viewerId=null 被当匿名）：
    - 文章侧（articleId=5001，authorId=2002）：清空 `content:article:view:pending:5001` 后，作者 2002 访问详情 → pending 仍为空（0）；换读者 2001（非作者）访问 → pending=1。
    - 章节侧（book 6101 / 章节 610101 → article 5001）：作者 2002 阅读章节 → pending 仍为空；新读者 2003 阅读 → pending=1。
    - DB `article_stat.view_count`：122→127（此前匿名冒烟 5 次）→128（读者 2001 文章侧 1 次）→129（读者 2003 章节侧 1 次，11:40:20 刷回）。作者两次访问均未贡献计数。
  - **11.11-B（可选）推荐流作者自点击排除**：推荐流 `recordClick` 前判断作者自点击并跳过（调用点在 [ArticleController.java:177](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/ArticleController.java#L177)、[BookController.java:192](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/BookController.java#L192)）。只影响 CTR 报表口径，不影响排序；与 11.11-A 独立，可后做。
  - **已拍板（2026-11-08｜第二批·随 11.11-A 顺手做）**：不单独立项，随 11.11-A 一并实施；只影响 CTR 报表口径。
  - ✅ 2026-11-08（运行态实测通过｜查库确认）：`FeedExposureSupport#recordClick` 增加 `authorId` 入参与「作者 == 浏览者」短路；`ArticleController`、`BookController` 调用点已同步。实测 `feed_exposure_daily`（target 5004，`from=feed&snapshotId=1`）：
    - 作者 2002 拉自己的推荐流并点击 5004 → 该行 `exposure_count` 保持 1、`click_count` 保持 0（未 +1）。
    - 读者 2003 拉流后点击同一条 → 该行 `exposure_count=2`、`click_count=1`（非作者正常 +1）。

> **2026-11-09 追加归档（11.11-C1~C3 延后项）**：异常频次规则三阶段（C1 影子统计 / C2 处置 / C3 复盘）经复核确认当前无可执行前置（需真实互动量基线），用户拍板写入本文档并自主清单归档删除。以下为条目原文搬运（仅调整相对链接层级）与 2026-11-09 评审结论（**唯一副本**）。

  - **11.11-C1（Phase 1｜影子统计，不拦截）异常频次三维度**：实现 R2 单用户持续高频（600s / 80 次）、R3 单内容突增（10min / 25 赞、15 藏，且为 24h 基线的 3 倍）、R4 匿名指纹泛滥（10min / 50 篇）的计数与命中标记；约 150~200 行，无 DDL。Phase 1 只记录「本应命中」与原因，不拒绝、不停计数，先验证阈值误伤。
  - **已拍板（2026-11-08｜延后）**：等有真实互动量、能看出稳定 24h 基线后再做，避免在无样本时拍阈值。**重启条件**：11.6 的曝光/点击数据已能支撑三维度统计，且单用户/单内容/匿名的真实频次足以形成稳定基线（具体阈值由实施前数据复核确认）；C2、C3 随 C1 顺延。
  - **11.11-C2（Phase 2｜阈值验证后执行处置）**：按 Phase 1 数据复核阈值后启用处置——R2 超限拒绝、R3 仅打标不拦截、R4 只停计数不断访问；复用 Phase 1 统计口径，不新增表。
  - **11.11-C3（Phase 3｜复盘与可选加固）**：阈值调优、指标/告警接入与误伤复盘。本阶段明确**不做**多账号/IP 关联、设备指纹、独立风控服务（不在本批次范围）。
  - **追溯**：环境验证清单原表已移入本文件「八、附录」，实测依据以该处为准；四条未验证风险已登记 [constraints R4~R7](../../constraints/constraints-index.md)，不进待办区。
  - **评审结论（2026-11-09，对照主流方案，用户拍板采纳）**：三阶段框架与业界内容平台风控演进路径（shadow mode 先行 → 阶梯处置 → 规则运营闭环）同构，**框架不改**；两处优化供 C1/C2 重启时采纳：
    - **① 重启信号量化（并入 C1 重启条件）**：11.6 曝光/点击数据连续 7 天满足「单内容日互动量 ≥ 100 或单用户日互动行为 ≥ 20」任一，即启动 C1 影子统计；避免「延后」变成无触发依据的无限等待。
    - **② C2 的 R2 处置降一档**：R2（单用户高频）最易误伤正常重度用户，首期改为「只停计数 + 限流软拒绝」，待 C3 复盘确认零误伤后再升级硬拒绝；R4（匿名刷量）无误伤成本，可放心先上处置；R3 维持「仅打标」。
    - **边界维持**：多账号/IP 关联、设备指纹、独立风控服务仍不做；如未来攻击量大且被针对性薅，优先评估云厂商风控 API（按量付费）而非自建重型风控。
  - **设计文档登记（2026-11-09）**：本节三阶段设计（含重启信号与 C2 处置矩阵、前端配合职责）已同步写入模块设计文档 [Content-Architeche.md](../../Architecture/Content-Architeche.md) §五「异常频次反作弊（11.11-C1\~C3，延后未实施）」小节，实施时以该处 + 本文件为依据。
  - **暂不实现登记（2026-11-09）**：C1\~C3 已按挂起项纪律登记 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md) content 节（附量化触发条件）；触发条件满足时从该处迁回 next_update.md 实施。

## 七、执行注意项（15 条，审查补充 2026-11-06；迁自 next_update.md，原文搬运）

> 本节只写「跨任务、易踩、且不写进某一条验收就会漏」的约束；新增任务见 11.14~11.17。11.11 的剩余实施仍需遵守本节约束。

1. **DDL 风格对齐既有表**：`create table if not exists`、`datetime`（不用 `datetime(3)`）、`bigint unsigned` / `tinyint`、`engine=InnoDB`、`default charset = utf8mb4`、索引 `idx_*`、唯一键 `uk_*`（依据 [content-schema.sql](../../../docker/init/mysql/schema/content-schema.sql) 的 article/book 建表段与 reading_history 段）。
2. **执行顺序（一个窗口内）**：①建 `article_stat`/`book_stat`/`user_like`/`feed_exposure_daily` → ②分批回填（11.15）→ ③切换读统计表（11.2）→ ④统一补索引（批次十遗留 4 个 + 本批 `idx_biz(biz_type,biz_id,update_time)`）。索引最后建，避免回填期写放大；`idx_biz` 只建三列版，不重复建批次十的两列版；回填 SQL 分批提交，禁长事务锁表。
3. **计数原子性与下限**：一律 `update ... set cnt = cnt + 1`（禁止读改写）；递减用 `set cnt = greatest(cnt-1,0)` 或带 `where cnt > 0`；以影响行数判断成功，取消收藏/取消点赞重复调用按幂等成功（删 0 行不递减）。
4. **首次阅读判定**：只在 insert 成功后 `reader_count + 1`；`DuplicateKeyException` 转更新分支时不得加计数（依据 [ReadingHistoryServiceImpl.java:374-392](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L374-L392)）。
5. **唯一键冲突**：收藏 `uk_user_target`、点赞 `uk_user_target` 冲突一律按幂等成功处理，不抛 500。
6. **事务边界**：收藏/点赞记录行与统计计数同一事务，任一步失败一起回滚；search 同步事件在事务提交后发送，避免回滚后事件已发出。
7. **真源口径**：`article_stat`/`book_stat` 为真源；`article.like_cnt/comment_cnt` 仅作既有读接口的镜像（[FeedItemAssembler.java:38](../../../litisle-content/src/main/java/com/litisle/litislecontent/support/FeedItemAssembler.java#L38)、[ArticleQuerySupport.java:214](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/ArticleQuerySupport.java#L214)、[ContentFeignProviderController.java:74](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/feign/ContentFeignProviderController.java#L74) 均读列），镜像与 `stat_update_time` 必须同事务刷新，否则热度榜增量轮捞不到变更（[FeedHotRankSupport.java:46-50](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java#L46-L50)）。
8. **搜索同步**：[IndexHeatFlushJob](../../../litisle-search/src/main/java/com/litisle/litislesearch/job/IndexHeatFlushJob.java#L248-L318) 只认 Redis pending 增量；pending 由 `HeatEventConsumer` 消费 `QUEUE_SOCIAL_LIKED` 写入。content 当前没有点赞事件生产者，也没有取消点赞队列 → 点赞与取消点赞都必须发事件，否则 `article_idx` 只增不减、heat 虚高。
9. **书籍索引不同步**：[BookIndexDoc](../../../litisle-search/src/main/java/com/litisle/litislesearch/domain/entity/BookIndexDoc.java#L25-L49) 只有 7 个基础字段、无计数，`book_idx` 不需要计数同步；若后续搜索页要展示收藏/阅读数，另立任务。
10. **历史裁剪的产品影响**：90 天裁剪会让超 90 天记录从 [pageMyHistory](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L181-L205) 结果消失；现有懒删 SQL 只有 offset/limit（[ReadingHistoryMapper.java:92-102](../../../litisle-content/src/main/java/com/litisle/litislecontent/mapper/ReadingHistoryMapper.java#L92-L102)），需换新 SQL；`HISTORY_MAX_PER_USER=500`（[ContentLimitConstant.java:70-72](../../../litisle-content/src/main/java/com/litisle/litislecontent/constant/ContentLimitConstant.java#L70-L72)）改 1000 并同步 383/420-421/430-432 三处注释（保留常量，不删除）。
11. **频控与防刷**：收藏已有 20/60s（[FavoriteController.java:48-49](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/FavoriteController.java#L48-L49)、[62-63](../../../litisle-content/src/main/java/com/litisle/litislecontent/controller/FavoriteController.java#L62-L63)），点赞必须新增 `LIKE_MAX_COUNT` 同款注解；匿名曝光可无限刷（当日仅按 `X-Anon-Id` 去重），CTR 只作趋势、不进 P0 排序；`snapshotId` 不可信，不得因伪造点击参数抬高计数。
12. **配置化清单**：平滑 `m`/先验分位、30/90 天窗口、90 天/1000 条/在读 10 条、曝光保留 30 天，全部挂 `ContentProperties`（[ContentProperties.java:64-124](../../../litisle-content/src/main/java/com/litisle/litislecontent/config/ContentProperties.java#L64-L124) 的 `Feed`/`Stream` 或新增嵌套）并提供 yml 默认值；禁止散落业务常量。
13. **可观测**：`ContentMetrics` 现仅 4 个入口（[ContentMetrics.java:78/92/105/116](../../../litisle-content/src/main/java/com/litisle/litislecontent/metrics/ContentMetrics.java#L78-L116)），需新增对账差异、回填进度、计数异常指标；取不到 `MeterRegistry` 时按现有 `ObjectProvider` 静默空转，不阻断主流程。
14. **V6/V7 验收草案**：V6=统计一致性与权限（对账差异在阈值内、连点取消计数不为负、未登录点赞/收藏返回 10002、自赞返回独立业务码、不可见目标返回 NOT_FOUND）；V7=曝光点击闭环（曝光/点击/取消点赞均能按日聚合、未带 `from=feed&snapshotId` 只计曝光、登录与匿名分开统计）；实施完成后正式补入 next_update.md。
15. **判定无需处理（避免重复提问）**：①`book_idx` 计数——无字段（[BookIndexDoc.java:25-49](../../../litisle-search/src/main/java/com/litisle/litislesearch/domain/entity/BookIndexDoc.java#L25-L49)）；②content 既有单测——仅 `LitisleContentApplicationTests` 冒烟，改 500 条逻辑不会打破旧断言，但需新增裁剪单测；③浏览量→search 链路——[ViewCountFlushJob.java:124](../../../litisle-content/src/main/java/com/litisle/litislecontent/job/ViewCountFlushJob.java#L124) 已发 `ContentViewCountFlushedEvent`，无需重建；④收藏频控——[ContentLimitConstant.java:33-34](../../../litisle-content/src/main/java/com/litisle/litislecontent/constant/ContentLimitConstant.java#L33-L34) 已存在，11.9 无需新增；⑤匿名曝光去重键——已有当日 Set，本批只改 CTR 口径，不改去重键。

## 八、附录：环境验证清单（批次十一收口用，2026-11-06；**本表为唯一副本，原位于 next_update.md「六、环境验证清单」**）

> 背景：本轮全部代码与 DDL 均为「静态可验证」范围内完成，应用未启动；2026-11-06 接续时 Docker Desktop 未运行（启动后引擎随即退出），3307 不可用，故 11.9 的造数实测未做。下列 11 步按依赖顺序排列，**前一步不通过不要跳到下一步**；每一步都写清「跑什么 → 预期看到什么」。
> 标记：✅ = 已手动验证；⏳ = 待环境。

| # | 要跑什么 | 预期看到什么 | 状态 |
| --- | --- | --- | --- |
| 1 | `docker ps`：确认 mysql(3307)/redis(6379)/rabbitmq(5672)/elasticsearch(9200) 四个容器都在运行；`mysql -h127.0.0.1 -P3307 -uroot -pverysecret -e "select 1"` 通 | 四容器均 healthy，`select 1` 返回 1 | ✅ 2026-11-06 19:4x 实测：12 个容器 Up（mysql/elasticsearch healthy），`select 1` 返回 1 |
| 2 | 按序手动执行幂等脚本：`zz-user-like.sql` → `zz-content-stat.sql`（含回填）→ `zz-content-stat-window.sql` → `zz-feed-exposure.sql` → `zz-feed-feedback.sql` → `zz-book-read-quality.sql` → `zz-reading-duration-reserve.sql`；执行完再整套重跑一次 | 首次全部 `Query OK`；第二次仍 `Query OK` 且表行数不变（幂等）；`reading_history` 有 `duration_ms` 列 | ✅ 两轮共 18 次执行全部 exit=0；行数稳定（article_stat 9、book_stat 5、content_stat_window 26→52 即 30/90 两窗口各 26、book_read_quality 5）；**附注（2026-11-06 整理）**：上述 7 个 `zz-` 脚本的表结构内容已合并进 `schema/content-schema.sql` 并删除，本行为整理前实测记录，新库初始化不再需要手动执行补丁 |
| 3 | 回填复核（11.15）：跑完后抽查 `article_stat.collect_count` / `book_stat.collect_count` 与「排除作者自收藏」的聚合值 | 两者一致；若先前已有作者自收藏，回填值**低于** `favorite` 总行数（差值等于作者自收藏条数） | ✅ 实测：文章 5003 仅作者 2002 自收藏时 `article_stat.collect_count=0`；书籍 6101 有作者自收藏（作者 2002）时 `book_stat.collect_count=2`（favorite 聚合 3 行，差值=1 条作者行） |
| 4 | **11.9 实测**：用作者账号收藏自己的文章/书籍，再用普通账号收藏同一目标；各取消一次 | 作者自收藏：`favorite` 表 +1 行、`article_stat.collect_count` / `book_stat.collect_count` **不动**；普通收藏：计数 **+1**；作者取消：计数**不动**；普通取消：计数**回退 1**（不会出现「收藏没加、取消扣 1」） | ✅ 实测（article 5003，作者 2002）：作者自收藏 → favorite+1(id17)、stat 仍 **0**；普通用户 2003 收藏 → stat **0→1**；作者取消 → stat 仍 **1**；普通取消 → stat **1→0**。书籍 6101：作者自收藏 → `book_stat.collect_count` 仍 **2**。`content_stat_window` 30/90 天按任务同 SQL 复算：6101 collect=2（排除作者行）、5003=0（仅作者自收藏），对账差异 0 |
| 5 | 启动 content 应用，看启动日志 | 无 Mapper/Bean 装配异常；`FeedHotRankRefillJob`/`ContentStatWindowJob`/`ContentStatReconcileJob`/`FeedExposureCleanupJob`/`BookReadQualityJob` 等 9 个任务注册成功；`/actuator/health` UP | ✅ 19:35 重启后 `/actuator/health`=UP；`job/` 下 9 个 `@Scheduled` 类全部装载，`ContentStatReconcileJob`/`ContentStatWindowJob`/`BookReadQualityJob`/`FeedExposureCleanupJob` 有实跑日志，其余 4 个（热度灌榜、浏览刷回、发件箱重发、定时发布）空载时不打日志、无法单独取证 |
| 6 | 11.8/11.14 点赞闭环：点赞 → 取消 → 再点一次；看 MQ 与 ES | `article_stat.like_count` +1/−1/+1；`article.like_cnt` 镜像同步；`social.liked`/`social.unliked` 各一条消息；ES `article_idx.likeCnt` 与详情页一致（负数不会出现） | ✅ 实测（article 5001，作者 2002，基线 like_count=15 / like_cnt=15）：u2003 点赞 → user_like 1 行、stat **15→16**、`article.like_cnt` **15→16**、Redis `search:heat:pending:5001` 出现 `like_delta=1`（证明 MQ 已发出并被 search 消费）→ 19:32 刷新后 ES `article_idx/_doc/5001` 的 `like_cnt`=**16**、pending 键被 GETDEL；取消 → user_like 0 行、stat **16→15**、镜像 **15**、pending `like_delta=-1`→ 19:47 刷新后 ES **15**（version 3，未变负）；重复取消无副作用 |
| 7 | 11.6/11.12 曝光点击：连续刷两次文章流；从流内打开一篇文章（带 `from=feed&snapshotId&position`），再从搜索入口打开同一篇 | `feed_exposure_daily` 出现当日行且 `exposure_count` 增长；带参数打开 `click_count` +1，不带参数只计曝光；登录与匿名各占一行（`identity_type` 分开） | ✅ 实测：匿名（合法 v4 UUID）刷流 → `feed_exposure_daily` 匿名行（stream=article、identity_type=anon）；登录 u2003 → 独立 login 行；点击必须带 `from=feed` + `snapshotId` + 当日已曝光才 +1，`5001` 曝光 2/点击 1、重复点击不再增；不带归因参数的 `5004` 点击 0；CTR 两口径 anon 6 曝光/1 点击=**0.1667**、login 5 行/1 点击=**0.2000**（登录口径不含匿名） |
| 8 | 11.11 负反馈：分别提交「不感兴趣(5003)」「屏蔽作者」「屏蔽分类」，重复提交一次；刷流观察；再 DELETE 撤销 | 重复提交不报错且行数不增；单条内容**当页**消失；作者/分类屏蔽**下一代快照**消失（旧快照剩余页仍可能看到，属已知表现）；撤销后重新建池可见；未登录提交返回 10002 | ❌ 未通过：未登录 `10002` ✅、单条内容当页消失 ✅、旧快照仍可刷到 ✅、撤销恢复 ✅、频控第 21 次 `10005` ✅；但**作者屏蔽的「下代生效」失败**——建池后候选池为空触发 last-good 兜底，而 last-good 路径不应用负反馈名单，新代仍返回被屏蔽作者内容（`FeedStreamServiceImpl` 空池兜底分支）；分类维度因种子 `category` 双重编码、feed VO `category` 为 null，**无法验证**。**2026-11-06 晚修复复测（验收转入批次十二 12.1）**：兜底路径已补负反馈过滤——article 作者屏蔽 gen1 含 5099→屏蔽 2003→gen2 剔除；article 分类屏蔽（合法枚举「散文」）gen4 含 5099→屏蔽→gen5 剔除；book 作者屏蔽 gen1 含 6199→屏蔽 2003→gen2 剔除；空池 last-good 兜底页同样不放出被屏蔽内容。原「分类无法验证」的根因是造数用了非枚举分类值（测试问题非代码缺陷） |
| 9 | 11.13 完读：等 `BookReadQualityJob` 首次执行（默认启动 10 分钟后）或临时把 `initial-delay-ms` 调小；查 `book_read_quality` | 种子书 6101 = 1/1（比率 1.0000）、6102 = 0/1（0.0000）；6103~6105 = 0/0（比率 NULL）；日志有耗时；重复执行不放大 | ✅ 19:45:04 实跑：日志 `[粗完读率] 本轮完成：rows=10`（5 本书 upsert，MySQL 计 2/行）；`book_read_quality` 落表 6101=**1/1**（1.0000）、6102=**0/1**（0.0000）、6103~6105=**0/0**（NULL），`stat_update_time` 统一刷新为 11:45:04(UTC)；upsert 语义保证重复执行不新增行 |
| 10 | 11.16 对账：等对账任务跑一轮，看日志与指标 | 收藏/浏览/点赞/评论差异均为 0（收藏源值已排除作者自收藏）；超 `diff-rows-threshold` 才 ERROR；`litisle_content_stat_reconcile_diff_total` 按类型可见 | ⚠️ 无法验证（指标可见性）：对账任务实跑 19:21、19:40 两轮均 `total=0, threshold=50`，6 类差异 articleCollect/bookCollect/articleView/articleLike/articleComment/bookReaderUnder 全为 0，阈值未触发故日志级别正确（仅 INFO）；`litisle_content_stat_reconcile_diff_total` 因 `diffRows<=0` 按设计不打点，本轮**看不到**该指标，未伪造差异。**2026-11-06 晚修复复测（验收转入批次十二 12.2）**：已改 Gauge 预注册（选型理由与实现见 12.2），重启后 6 条 `litisle_content_stat_reconcile_diff` gauge 全部可见 `0.0`，0 值不再缺席 |
| 11 | 前端契约复核：按 [Search-Architecture](../../Architecture/Search-Architecture.md) §十一 第 5~8 条逐条过 | 第 5 条点击归因、第 6 条点赞提示、第 7 条负反馈三维度与撤销、第 8 条时长心跳仅预留（传 `durationMs` 不影响历史写入） | ✅ 静态复核通过：第 5 条详情接口已接受 `from`/`snapshotId`/`position` 且实测只在该组合下计点击；第 6 条 `POST/DELETE /like` 的三种拒绝码与计数刷新实测一致；第 8 条 `PUT /reading-history` 的 `durationMs` 仍被忽略。第 7 条契约与实现基本一致，唯「下代快照消失」受 11.11 的 last-good 兜底问题影响（见第 8 项） |

> **批次十一收尾（2026-11-06）**：上表 1~7、9、11 项实测通过按现状勾收；**11.11 作者屏蔽下代生效、11.16 对账 0 值可观测两项转入批次十二**（代码修复已完成并实测，正式验收随批次十二收口）。本批连带落地：① `docker/init/mysql/01-schema.sql`/`03-seed.sql` 入口补 `SET NAMES utf8mb4`（修复容器客户端 latin1 导致的中文双重编码，对全新初始化生效）；② `tools/db-repair/content-stat-backfill.sql`（zz 脚本删除后的存量统计回填替代，不参与自动初始化）；③ user schema 补 phone_hash/email_hash 并实测全新初始化（见 12.3）；④ `10-nacos-schema.sql`（0 字节）实测在 entrypoint 中执行无错、`nacos_config` 库 0 表由 Nacos 3.x 启动迁移器自建（20-nacos-user.sql 已授予该账号 DDL），占位文件无害，是否删除待用户定。

> 与「暂不实现汇总」衔接：本批次无「暂不实现」项；P1 项按触发条件推进，不满足条件时保留登记不实施。
> 验证口径：10.7/10.8 仍按批次十旧口径通过；本批次落地后新增 V6（统计一致性与权限）与 V7（曝光点击闭环）验证，草案见「七、执行注意项」第 14 条，实施完成后正式补入 next_update.md。

## 九、归档说明

- 本批次共归档 16 项（11.1~11.10、11.12~11.17）。
- **2026-11-09 批次十一全部归档**：11.11 剩余部分（C1~C3 延后项）经复核无当前可执行前置（需真实互动量基线），用户拍板归档删除；条目原文与评审结论（重启信号量化、R2 处置降档）已迁入「六」末尾追加块。至此批次十一在本文件内**唯一副本完整**，主清单仅留指针。
- **2026-11-07 二次整理**：批次十一在 next_update.md 的其余内容（现状问题清单、方案 13 条、决策、待确认项、执行注意项 15 条、环境验证清单、收尾记录、衔接与验证口径）已全部迁入本文件；「八、附录：环境验证清单」为**唯一副本**，原位于 next_update.md「六、环境验证清单」，主清单已不再保留该表。
- 编号空缺说明：主清单批次十一仅保留 11.11，11.1~11.10、11.12~11.17 的空缺指向本文件；编号不重排。
- 主清单 11.11 的追溯（负反馈落地、12.1 收口、环境实测）统一指向本文件，不再依赖主清单内的清单表。
