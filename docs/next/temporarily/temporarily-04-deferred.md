> **拆分归档**：本文件拆分自同目录索引页 [Temporarily_not_implemented.md](Temporarily_not_implemented.md)，原单文件对应部分原样搬运（2026-10-28 拆分，内容零修改），各部分入口见索引页。

## 暂不实现（预留项，附触发条件）

### review 审核模块

#### 云 API 生产适配器

生产切换时落地（pom runtime scope + Nacos 配置 AK/SK 走环境变量）。

#### 图文组合审核 / OCR 多模态盲区（§2.9）

二期；此前由人审详情页「图文并排核对」兜底。

#### 撤销改判公示（§4.2.3）

二期，仅公示脱敏摘要，严禁回显 appeal.content/appellant_id/reporter_id。

#### 视频 / 音频审核

一期显式不支持，业务出现前须先补 SPI 方法与适配器。

### content 内容模块

#### 文章分类：Book 链路枚举改造待确认（迁自 docs/temp/temp.md，2026-10-23 归档）

Book 分类集合是否与文章一致（DB default '小说'）？一致 → 复用 ArticleCategoryEnum；不一致 → 新建 BookCategoryEnum 后再改 BookSaveDTO/Book/BookDetailVO/BookServiceImpl。阻塞 Book 链路，不阻塞文章链路（已枚举化落地，见归档板块九）。触发条件：Book 链路改造时。

#### 方案B 软删重发互动数据悬空兑底（迁自 temp.md 阶段7）

已发布文章改正文=软删旧文+重发新文（新 articleId），点赞/评论挂旧 articleId → 重发后旧互动悬空。默认口径「接受丢失」（正文已变，旧互动语义失效）；若产品要求元数据级编辑保留互动，再启用 social 侧迁移（旧→新 articleId 批量改绑）。触发条件：方案B 重发事件（如 content.replaced）落地 / social 立项时。

#### social 点赞/评论随文隐藏与悬空兑底（迁自 temp.md 阶段7，设计意图固化）

①social 订阅 content.unpublished（reason=1），文章维度点赞/评论树置「随文隐藏」（软删标记不物理删，法务取证对齐），列表按隐藏状态过滤；②点赞列表/评论入口反查 content 详情，ARTICLE_NOT_FOUND 时静默过滤（双保险：即使事件丢失/乱序也不感知悬空）；③ContentUnpublishedEvent.bizType=2 评论扩展位复用同一事件口径，评论软删时其下点赞连带隐藏。触发条件：social 模块立项随设计文档固化。

#### social 回写计数对已删文章吞 ARTICLE_NOT_FOUND（迁自 temp.md 阶段8④）

updateCounters 带 del_flag=0 条件 rows=0 抛错；约定 social 回写失败时对 ErrorCode=ARTICLE_NOT_FOUND 记 info 后视为成功（文章已软删计数无意义），其余错误走重试/DLQ。触发条件：social 回写落地时。

#### FeedItemVO.commentCnt 填充（迁自 temp.md 阶段8③）

字段已补（对齐 Card/Detail 三计数口径），填充值未接。触发条件：social 点赞/评论落地后 feed 聚合链路补充。

#### 推荐流热度榜 social 信号补齐与调参（阶段14 预留，2026-10-26）

热度榜已随阶段14 切流上线（content:feed:hot:zset，FeedHotRankRefillJob 5min 影子键 rename 全量重灌，/feed 优先读榜、榜空降级时间倒序）；like_cnt/comment_cnt 因 social 未开发恒 0，当前退化为「浏览量+时间衰减」排序（浏览量权重 0.2 远低于互动权重，刷 PV 扰动有限）。social 落地后回收：

① like/comment 计数信号补齐（阶段14 D10）：social 回写 updateCounters 成功后触发该文章热度分增量重算（ZADD 单成员，复用 FeedHotRankSupport#computeScore），消除 5min 全量灌榜滞后（ContentInternalServiceImpl#updateCounters 已留 TODO 接线点）；
② 权重调参（阶段14 D11）：真实互动数据进来后按效果调 likeWeight/commentWeight/viewWeight（yml litisle.content.feed.hot 外置可调，无需发版）。

触发条件：social 点赞/评论落地时。

#### Book.coverFileId 封面引用管理同病（迁自 temp.md 阶段8②）

文章封面已并入阶段6方案C 引用管理（COVER bizType，发布/编辑/软删/草稿清理全链路 bind/unbind），Book.coverFileId 未接入。触发条件：随「Book 分类确认」一并处理。

#### social 回写计数契约：viewCnt 禁用传 null（迁自 temp.md 阶段10）

content 自身 ViewCountFlushJob 对 view_cnt 做 SQL 原子自增，social 若回写 viewCnt 绝对值会并发互相覆盖；约定 viewCnt 永远传 null（PV 归属 content 内部闭环），仅回写 like/comment。ContentInternalServiceImpl#updateCounters 注释已留档。触发条件：social 立项时随设计文档固化。

#### zone 1/2 区域列表端点与 ai 个性化重排（迁自 temp.md 阶段12 后续另立项）

推荐排序已定案热度榜并随阶段14 切流上线；余两项另立项：①zone 1/2 区域列表端点（GET /api/content/articles?zone=1|2）——复用缓存三防 + recheckFilter 回查机制，与推荐流共用模板方法、缓存键分离；②ai 个性化重排——留三期演进。触发条件：产品需要区域列表 / 三期 ai 个性化立项时。

#### ARTICLE_IMG_REFUSED 错误码拆分暂缓（迁自 temp.md 阶段11 P3）

错误码现混装「文件不存在」与「引用他人文件」两种失败；暂缓拆分（fileId 唯一来源是上传接口，「不存在」仅在文件被物理清理/ID 错误的边缘场景出现，前端对保存失败统一提示即可；拆分需 file 侧契约联动，收益低）。触发条件：前端实际反馈需要区分提示时再立项。

#### 阅读历史四项待定扩展（迁自 temp.md 阶段15，2026-10-27 拍板暂缓）

① 章节级足迹明细：reading_history 一行=该书最后阅读（按书聚合防书架刷屏），如需「章节级历史足迹」另建明细表，不动主表；② 历史清空/删除：用户侧「清空阅读历史」「单条删除」功能未立项，现仅服务端容错展示；③ 多书归属上下文落历史：文章挂多书时自动路径不落（歧义防记错书），如搜索/推荐场景需落书籍历史，评估详情请求带 bookId/chapterId 上下文显式指定；④ 文章软删联动清理 book_chapter 挂载：本阶段只做历史列表 chapter_id 悬空容错展示（书名+最后阅读时间仍展示），挂载联动清理未做。触发条件：①产品需要章节足迹 / ②产品立项历史管理 / ③搜索或推荐场景要求落书籍历史 / ④方案B 软删链路改造时评估。

#### content 收藏迁移 social 与收藏夹扩展（迁自 temp.md 阶段16，2026-10-28）

一期文章/书籍单层收藏由 content favorite 表临时承载（无分组、无佳句、接口 /api/content/favorite）；social 立项后三件事：① favorite → favorite_item 迁移/合并（可建默认收藏夹），或确认 content 为收藏 SSOT、social 只做聚合展示；② 收藏夹分组、佳句收藏按 social 完整版设计落地（favorite_folder/favorite_item/quote_favorite）；③ 收藏计数如需立项时一并定（一期允许作者收藏自己，无刷计数问题）。触发条件：litisle-social 模块立项时。

#### 点赞与收藏迁入 social 模块（架构演进预留，2026-11-07）

**现状**：点赞表 `user_like`（`docker/init/mysql/schema/content-schema.sql` 282-292 行）与收藏表 `favorite`（同文件 175-186 行）都建在 `litisle_content` 库，均以 `uk_user_target` 保证幂等；对外接口为 `LikeController`（`/api/content/like`）与 `FavoriteController`（`/api/content/favorite`）；计数写入点 `ContentStatSupport` 在点赞/收藏的同一本地事务内更新 `article_stat`/`book_stat` 与 `article.like_cnt` 镜像列；事件契约已就绪——`SocialLikedEvent`/`SocialUnlikedEvent` 定义在 `litisle-api`，交换机 `EXCHANGE_SOCIAL`（`litisle.social.exchange`），content 经 `LikeEventSupport` afterCommit + 本地 outbox 发布，search 由 `HeatEventConsumer` 消费累加热度。

**目标**：点赞、收藏归独立的 `litisle-social` 服务（建议独立库 `litisle_social`，`00-init-databases.sql` 46 行已留建库注释位），social 成为互动关系与互动明细的唯一真源；content 只保留内容本体与聚合计数，详情/列表通过服务间契约取「是否已点赞/已收藏」与点赞数。

**触发条件**（满足任一即立项）：① social 域功能立项（收藏夹分组、佳句收藏、关注/私信、评论落地），social 必须有真实库与实体承载；② 互动表规模或写入压力达到需要独立库治理的程度——参考阈值：`user_like` 或 `favorite` 单表行数 > 5000 万，或点赞+收藏写 QPS 峰值持续 > 200，或 content 写事务中互动表行锁等待成为可观测瓶颈；③ 需要按用户维度统一查询「全站互动流水/互动风控」，不再限定文章/书籍两类目标时。

**迁移范围概要**（与上条收藏夹扩展、下文「阶段17 后续另立项余项」③ 为同一演进线）：

1. 表与库：`user_like`、`favorite` 迁至 `litisle_social` 库，同构搬迁 + 唯一键不变；`article_stat`/`book_stat` 留在 content 作计数真源，social 事务提交后经 outbox 重试调用既有契约 `ContentClient#updateCounters`（content 侧端点 `ContentFeignProviderController#updateCounters` 已存在），不跨库本地事务。
2. 契约：新增 `SocialClient` 的批量「用户是否点赞/收藏」与点赞数查询方法，供 content 详情、推荐流装配回填；事件生产者由 content 切到 social，search 的热度消费端不改（仍按 `delta` 累加）。
3. 接口：新路径统一为 `/api/social/like`、`/api/social/favorite`（网关 `litisle-social` 路由已配置），旧 `/api/content/*` 路径保留代理转发；下线条件见下方「已确认口径」③（按调用量判定，不按时间），不用改网关白名单（两类接口均要求登录）。
4. 分阶段（复核合计 26-34 人天，逐阶段变化见下方「已确认口径」估工复核表）：抽公共能力（限流/可见性/幂等模板下沉）3-4 → social 建库与骨架 5-6 → 双写 + 数据回填校验 5-6 → 双读对账与切读 6-8 → 切写 + 旧路径兼容转发 4-5 → 清理与文档收口 3-5。回滚：双写期任一阶段可回退到 content 单写；切读后保留 `content.like/favorite` 只读影子表与对账任务（影子表仅灰度期临时存在，终态删除），切写后保留旧接口代理直至满足下线条件。

不确定性（立项时需确认）：social 是否复用 content 的可见性校验与 follow 回查缓存；点赞数是否需要 social 自己维护一份聚合；旧路径兼容期已改由下方「已确认口径」③按调用量判定，不再依赖前端排期。

**已确认口径（2026-11-07 决策，立项时按此执行）**

1. **计数真源固定在 content**：`article_stat`、`book_stat`、`content_stat_window` 全部留在 `litisle_content`；social 只做互动真源（`user_like` / `favorite` 明细与是否已赞/已收藏状态）。content 侧详情、列表、推荐流、热度榜的计数读取全部保持本地读，不引入远程计数调用；social 事务提交后经 outbox 异步调 `ContentClient#updateCounters` 回写，该契约是绝对值语义，重试天然幂等。
2. **不采用影子表作为终态，计算跟着数据走**：
   - 30/90 天窗口聚合（收藏数、点赞数等）迁到 social 计算，结果以**绝对值**回写 content 的 `content_stat_window`；
   - 对账改为 content 拉 social 的批量计数接口，与本地 `article_stat` / `book_stat` 比对（不再用 SQL 直接 join `favorite`，`ContentStatWindowMapper` / `ContentStatReconcileMapper` 需同步改写）；
   - 影子表仅允许作为灰度阶段的临时手段，**终态必须删除**，清理阶段列为验收项，避免后人把它当成长期方案。
3. **旧接口按调用量下线，不按时间下线**：`/api/content/like`、`/api/content/favorite` 保留代理，同时做三件事——响应头带弃用提示、打调用量指标、记录调用方 UA；以「连续 30 天零调用」作为下线条件。依据：仓库内没有前端代码，无法从代码判断前端切换进度，只能以线上真实调用量为准。
4. **迁移范围只含点赞与收藏，骨架一次建到位**：本批只做「行为不变、位置改变」的迁移，不掺新功能（两类变更混在同一批，出问题无法归因）。第 2 阶段一次做齐库、模块、Feign 契约、监控接入（指标 + 告警）、双写/切读开关。后续功能按依赖顺序单独排期：关注 → 评论 → 私信（关注优先，因为 `SocialClient#isFollowing` 已被 content 可见性校验使用，是现成切入口）。
5. **CORS 已定：采用分支 A（同源部署，网关不配 CORS）**：
   - 采用：生产由 Nginx 或前端 devServer 同源转发，前端静态资源与 `/api` 走同一个 server 块（同协议、同域名/端口），网关不配 CORS，只需在部署文档写明转发关系与预检由谁处理。
   - 分支 B 不采用，仅在满足以下条件时再补：前端托管在无法自写 Nginx 的第三方平台（Vercel / GitHub Pages 等），或小程序、桌面端需直连网关。补法是在网关加 `globalcors` 统一处理（微服务主流做法，业务服务不各管一段），CORS 不随本次迁移下沉到 social。
   - 两套方案可共存：同源请求不带 `Origin`，CORS 响应头不生效，因此后续补 B 不等于推翻 A，也不是二选一后无法回头。
6. **social 的 API 前缀统一为 `/api/social/...`**：网关 `litisle-social` 路由（`application.yml` 68-71 行，`Path=/api/social/**`）已按此约定配置，文档跟代码对齐；代理期内旧 `/api/content/*` 仍指向 content。

**估工复核（按上述口径重算，仅本次迁移范围）**

| 阶段 | 原估（人天） | 复核（人天） | 变化依据 |
| ---- | ------------ | ------------ | -------- |
| 1. 抽公共能力（限流/可见性/幂等模板下沉） | 3-4 | 3-4 | 口径 1、4 不改变这部分范围 |
| 2. social 建库与骨架 | 4-5 | 5-6 | 口径 4 要求一次做齐库、模块、Feign 契约、监控接入与开关，比原「骨架」多约 1 人天 |
| 3. 双写 + 数据回填校验 | 5-6 | 5-6 | 双写与回填方案未变；绝对值回写契约已存在 |
| 4. 双读对账与切读 | 4-5 | 6-8 | 口径 2 把窗口聚合移到 social 计算并按绝对值回写，同时把对账从本地 SQL join `favorite` 改为拉 social 批量计数接口，两处均需重写与联调，增加约 2-3 人天 |
| 5. 切写 + 旧路径兼容转发 | 3-4 | 4-5 | 口径 3 增加弃用响应头、调用量指标、调用方 UA 记录与「连续 30 天零调用」判定，约多 1 人天；口径 6 只改路径命名，不额外计工 |
| 6. 清理与文档收口 | 3-5 | 3-5 | 影子表终态删除写进验收项，工作量与原清理相当（原估算未按时间预留代理期，故不额外增加） |
| **合计** | **22-29** | **26-34** | 净增 4-5 人天，集中在第 2、4、5 阶段 |

> 不计入上表：关注/评论/私信（按口径 4 另批排期）；口径 5 已定分支 A，CORS 不产生额外工期（仅部署文档说明）；若未来满足条件转分支 B，另加约 0.5 人天（网关配置 + 预检/凭据联调）。

#### 书籍阅读配套缺口（迁自 temp.md 阶段17，2026-10-28）

阶段17 只交付三读端点（详情/章节列表/章节详情），以下配套未做：① 书籍发现入口——书籍列表/公开书籍列表/我的书籍页面缺失，现阶段书籍 ID 由前端已有入口或后续阶段提供（阶段16 收藏列表可作书籍 ID 来源之一）——✅ 已随 temp.md 批次5 落地（2026-10-28：GET /book/list 匿名 + GET /book/my 登录；③书籍搜索仍外部依赖），详见归档板块九「后续另立项」P2；② 章节级阅读足迹——reading_history 一行=该书最后阅读，章节级明细表待产品需要再立项；③ 书籍搜索——书籍标题/简介进搜索索引待 search 模块扩展。触发条件：①~~前端书籍发现页立项~~（已落地关闭）②产品要求章节足迹 / ③search 接入书籍索引。

#### 阶段17 后续另立项余项（迁自 temp.md 阶段17，2026-10-28）

temp.md「后续另立项（不在阶段17）」6 项中 3 项已被既有条目覆盖（书籍发现入口→书籍阅读配套缺口①、阅读足迹→阅读历史四项待定扩展①/书籍阅读配套缺口②、书籍收藏数与收藏夹分组→content 收藏迁移③②），余 3 项登记：① 章节评论/段评——评论归 social，落地后接入书籍章节评论位（content 唯一可能工作=章节详情 VO 加 comment_cnt 展示，现恒 0 无展示意义，待联调定）；② 推荐流热度榜全量灌榜性能优化——✅ 已随 temp.md 批次5 落地关闭（2026-10-28：增量水位 5min + 低频全量 1h 校正，纯水位口径——update_time 为 DB ON UPDATE CURRENT_TIMESTAMP 维护，覆盖全部变更源）；③ 文章/书籍点赞——点赞归 social 模块（content 侧仅剩 D10 计数回写后即时重算 ZADD 增强项，增量灌榜已自动覆盖 social 计数回写触发的 UPDATE，待 social 联调）。触发条件：①social 评论模块立项时 / ③social 点赞模块立项时。



#### visibility=3 取关失效联动（方向四 P1-B）

social 取关后 content 侧关注回查短 TTL 缓存（≤60s）内仍判关注者放行正文，属可接受弱一致性（status.md 4.1 已定口径）；follow.changed 失效事件联动留二期。触发条件：social 模块开发时评估事件联动收益与成本。

#### 生产端事件发送失败重投载体（方向六 P2-B）

afterCommit 发事件失败时通用纪律仅定"失败重投"口径，实现载体未定（本地消息表 vs 定时补偿扫描卡 status=1 超时文章重发 submitted）——不定则 MQ 抖动窗口内文章永久卡待审无人消费（review sweeper 只管 status=0 任务，不管 article 已落 1 但事件丢失的场景）。触发条件：板块三 3.5 事件收口实现时定夺。

#### search 建索引数据源契约（ES 检索联动缺口）

search 消费 content.published 建 article_idx/book_idx（Sql-Document §九：标题^4/标签^3/摘要^2/正文/dense_vector）需回查标题/摘要/正文/标签/作者聚合数据——published 事件体字段未定义、ContentClient 现有三端点（getContent/listArticleImages/updateCounters）分别服务 review/social，search 侧复用 getContent 还是新增带标签聚合的 detail 回查端点未定；content 文档按职责边界只保证"有契约可回查"，索引字段映射属 search 模块设计决策，不提前替其定死。触发条件：litisle-search 模块开发时定夺（与 search 索引 mapping 设计同步收口）。

#### search 索引终态对账 EventReconcileJob（迁自 temp.md 内容模块缺口立项清单 批次3 遗留，2026-11-03）

事件丢失兜底已随批次3 落路径A 本地 outbox（event_outbox 表 + EventOutboxResendJob 补发，兜「发送侧必达」）；本条为其上层演进——search 立项后新增 EventReconcileJob 做索引存在性终态对账（search 侧批量 exists(articleId, updateTime) 契约 + 差异补发 published/unpublished），outbox 兜发送侧、对账兜消费侧落库，两层互补；ai RAG 侧可复用同一 job 扩展比对。触发条件：litisle-search 立项后。

#### article 计数拆表与刷回周期可选缓解（迁自 temp.md content 批次E E.3 🦴 预留，2026-11-03）

暂不立项——可选缓解：刷回周期 5min → 10~15min（UPDATE 量减半以上，用户无感）/ 批量 UPDATE（JOIN VALUES，省 N 次 round trip）/ 读写分离。拆表评估口径（2026-11-03 复查定论）：article 21 列不拆——content_html 唯一大字段 longtext 行外存储且全链路列投影已排除，计数三列经批次E E.0 量化非瓶颈（167 条/s ≈ 单核 1~3%，无热点行锁竞争），拆计数表反引入 join/跨表事务复杂度。再评估触发条件：计数回写出现热点行锁等待、单行 UPDATE TPS 明显成为瓶颈、或热点文章计数行竞争可观测时，仅拆 `article_stats` 计数表（view/like/comment + stat_update_time 迁出），正文列不动。

### search 模块

#### heat 写路径升级——召回 + 精排分层（迁自 temp.md search 批次A A7 升级路径，2026-11-03）

当前=ES Redis 累加 + 定时 `_bulk`（A7 定版，录 [archive/search/search-01-heat-write-path.md](../../archive/search/search-01-heat-write-path.md)）；升级路径——ES 只做 BM25/向量召回 Top ~1000，热度排序移到应用层用 content `CONTENT_FEED_HOT_ZSET` 重排（heat 字段退化为可选，彻底移出 ES 写路径），不走侧车索引（跨索引一致性 + enrich 复杂度高，当前量级不划算）；「heat 相对变化超阈值才推」维持一期不做（只减少写次数、不改变整文档重写本质）。触发条件：单轮 bulk 耗时逼近刷写周期 50%，或 ES 段合并/refresh 告警频繁。

#### 标签入 ES completion（迁自 [next_update.md](../next_update.md) 9.2-c / [temp.md](../../temp/temp.md) 批次一 1.4，2026-11-05）

原条目（next_update.md 9.2-c，内容较 temp 1.4 完整，两处已合并）：- [ ] 【可选】标签入 ES completion（来源：temp.md 批次一 1.4 挂起项；触发条件：标签总数 > 5 万，或 suggest QPS > 200，或联想 P99 > 100ms；前置：标签双写一致性专项设计；验收：suggest 一次 ES 请求拿齐书名/作者/标签并消掉标签 Feign 调用）
  - 评估结论（2026-11-05）：本轮挂起。阻塞点：① article_idx mapping 需新增 tags.completion 子字段并全量重建/回填；② content 标签变更需新增同步事件或双写；③ suggest 去重合并口径需产品确认。均超出「不新增跨模块契约」约束，待专项排期。
  - 决策（2026-11-05）：**维持现状、不实施（P3）**。理由：项目属中小规模（标签池 2 千~2 万、suggest 峰值大概率 <200 QPS），现有「completion + 标签前缀查询 + 480s/60s 两级缓存」足够；改标签入 article_idx 会带来文章数×标签数的 FST 膨胀、标签更名需全量重建、丢失标签池 use_count 口径。若未来触发（suggest P99 持续 >100ms 或标签缓存未命中压垮 content DB），改用独立 `tag_suggest` 索引而非 article_idx。

触发条件：标签总数 > 5 万，或 suggest QPS > 200，或联想 P99 > 100ms（且标签缓存未命中压垮 content DB 时改用独立 tag_suggest 索引）。

#### 索引批量回查端点（迁自 [next_update.md](../next_update.md) 9.2-d / [temp.md](../../temp/temp.md) 批次二 2.2，2026-11-05）

原条目（next_update.md 9.2-d，内容较 temp 2.2 完整，两处已合并）：- [ ] 【可选】索引批量回查端点（来源：temp.md 批次一 2.2 挂起项；触发条件：索引同步日均消费 > 1 万条；前置：content 提供批量回查端点；验收：消费端 N 次逐条 RPC 压成 1 次，消息量级上升后 RPC 调用量显著下降）
  - 评估结论（2026-11-05）：本轮挂起。阻塞点：需在 `litisle-api` 新增批量回查 DTO/Feign 方法并在 content 提供内部端点（跨模块契约），且消费端需按 id 聚合改批处理；属独立专项，本轮不擅改其它模块契约。
  - 决策（2026-11-05）：**维持现状、不实施（P3）**。理由：当前均值 <1 条/秒，单消费者逐条回查远未饱和；批量回查需跨模块契约 + 聚合窗口，且要重做逐条 ACK/重试/DLQ 映射，收益与复杂度不匹配。替代方案已落地：缓存失效降频 + 消费并发（见 [search-10-batch09-closing.md](../../archive/search/search-10-batch09-closing.md) 9.7）。
  - 触发条件修正（2026-11-05）：原文「日均消费 > 1 万条」偏低，改为「**消费积压持续 >5 分钟，或峰值持续 >50 条/秒**」再评估批量回查。

触发条件：消费积压持续 >5 分钟，或峰值持续 >50 条/秒。

#### ES external version + update 脚本条件判断（P2 条件项，迁自 [next_update.md](../next_update.md) 9.8，2026-11-05）

原条目：- [ ] 【P2 条件项】ES external version + update 脚本条件判断（来源：同上；**触发条件：积压持续 >5 分钟或峰值持续 >50 条/秒，且确认串行成为瓶颈**；未实施原因：A 与 B 必须配合——ES Update API 不支持 external version，只上 A 会漏掉元数据 partial update 路径）
  - 说明（2026-11-05）：A（整篇 upsert 带 external version）+ B（元数据 update 脚本内嵌 updateTime 条件）需成对落地，并统一处理 409 `version_conflict_engine_exception`（旧事件跳过并 ack）。本轮仅记录方案与触发条件，不改代码。
  - 不采纳项：按 articleId 路由同一消费者（标准 RabbitMQ 不保证 key 顺序，需一致性哈希插件或一 id 一队列，跨模块代价过高）。

触发条件：积压持续 >5 分钟，或峰值持续 >50 条/秒，且确认串行消费成为瓶颈。

#### updateTime 秒级保序（E，迁自 [next_update.md](../next_update.md) 9.9-E，2026-11-05）

原条目：- [ ] E updateTime 秒级保序（A/B 方案，状态：待触发）
  - 目标：彻底消除同秒两次编辑被判定为旧的问题（仅在产品明确要求秒内保序时启动）。
  - 依据：DB 三表均为秒级 `datetime`（`content-schema.sql:43`、`:108`、`user-schema.sql:55`）；实体为 `LocalDateTime` 且文章/书籍无 fill（`Article.java:177-178`、`Book.java:103-104`）；Feign 传值为 `publishTime`（`ArticleIndexDocDTO.java:62-63` ← `ArticleQuerySupport.java:352`）；守卫比较方式见 `IndexVersionGuard.java:77-78`。
  - 决策：本轮维持现状（D 方案），不为此进 DDL 窗口。
  - 若触发：优先复用 `article.version` 做单调版本（所有写路径 `version = version + 1`，并同步 DTO/事件/ES 字段），**不优先**改三表 `datetime(3)`（需同时改 DTO 格式、全局 Jackson、ES 序列化，改动面大）。注意现有 `version` 列（`content-schema.sql:41`）未接 `@Version`（`Article.java:165-166`），CAS 更新路径不会自增，须逐一改造。
  - 证据采集 SQL（`event_outbox` 同秒同 bizId 多事件统计，`event_outbox` 无 biz_id 列，需从 payload JSON 提取）：
    ```sql
    SELECT DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS sec,
           JSON_UNQUOTE(JSON_EXTRACT(payload, '$.bizId')) AS biz_id,
           COUNT(*) AS event_cnt,
           GROUP_CONCAT(event_type) AS types
    FROM litisle_content.event_outbox
    WHERE create_time >= NOW() - INTERVAL 30 DAY
    GROUP BY sec, biz_id
    HAVING COUNT(*) > 1
    ORDER BY event_cnt DESC
    LIMIT 20;
    ```
    判定口径：同秒多事件多数是重复投递/幂等重放，需看 `types` 是否包含不同事件类型；只有不同内容版本的同类事件才算真风险。
  - 验收：触发条件成立后再立实施单，以「同一文章同秒两次不同内容编辑能被顺序落索引」为验收标准。

触发条件：D 的计数器证明同秒折叠有实际发生（且排除重复提交），并且产品要求秒内保序。

#### 三表 datetime(3) 同秒折叠（G，已决定不做，迁自 [next_update.md](../next_update.md) 9.9-G，2026-11-05）

原条目：- [x] G 同秒折叠进 DDL 窗口（三表 `modify update_time datetime(3)`）——**不采纳**（状态：已决定不做，本轮仅登记）。理由：单改列无效（还须改 Feign 全局格式与 ES 序列化）；同秒折叠只导致短暂陈旧，下一条事件即自愈；隐私由门控 + 下架墓碑按权威态回查兜底，不依赖 `updateTime`。

触发条件：不重新评估，除非上层契约（Feign 全局日期格式 / ES 序列化）整体改为毫秒或单调版本（届时并入 9.9-E 单调版本方案统一处理）。

#### 消息级 RabbitMQ exporter / 最老消息年龄（H，已决定不做，迁自 [next_update.md](../next_update.md) 9.9-H，2026-11-05）

原条目：- [x] H 消息级 RabbitMQ exporter / 最老消息年龄——**暂不引入**（状态：已决定不做，本轮仅登记）。理由：需额外 exporter/插件与采集链路，当前用 backlog gauge + `for` 时长已可近似表达；真正的「最老消息停留时长」待积压成为真问题再评估。

触发条件：积压成为真问题（backlog gauge 告警持续触发且无法用现有采集定位），或需要按消息年龄做 SLA 统计时再评估引入 exporter。

### file 文件模块

#### CDN 接入

public-url-base 已分离，届时仅改配置；随审核图片外链需求一并评估。

#### disk 冷热分层（`-disk=ssd/hdd` + `010` 规范）

生产 Volume 多机时一并设计，不单独排期。

#### 存储 TTL

quarantine 保留期（需按 appeal 待裁决态顺延）与对账清理（需 file_ref 活跃引用前置校验）均为业务条件删除，不适用存储 TTL；二期大文件分片的半途清理走 S3 桶生命周期规则（已定双保险方案）。

#### 隔离图申诉 14 天发起窗口校验（迁自 temp.md file 模块删除语义统一立项清单 挂起项④，2026-11-03）

`AppealServiceImpl.submitAppeal` 实现时加 14 天发起窗口校验（基准 review_task.decide_time；14 < 17 天软删+对账总窗口，留 3 天余量吸收对账延迟/重试——「申诉成功但图已物理删」策略上不可能发生）；⚠️ 实现时区分两个「7 天」：`appeal-timeout-days=7` 是申诉裁决超时（既有），14 天是申诉发起窗口——新增独立 window 配置键（预留错误码 70002 申诉窗口外），勿复用既有键。触发条件：随 submitAppeal 本体实现批次落地（详录见 [archive/file/file-01-delete-semantics.md](../../archive/file/file-01-delete-semantics.md) 方案A 改动点④）。

### 公共库模块

#### common-redis 不引 Sentinel（保持轻量，仍用自研 LocalRateLimiter）

业务模块已引 Sentinel（Nacos 数据源规则已落地）；触发条件与落位见 `docs/archive/20260929-implementation-records.md` §一·五（库模块当前不动）。
