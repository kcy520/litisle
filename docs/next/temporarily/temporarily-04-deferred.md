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
