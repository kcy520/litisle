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
content ──MQ──►   content.submitted / published / unpublished（review、search、notification、ai 消费）
review   ──MQ──►   review.decided（content 消费回写状态）、user.banned（封禁连带下架）
social   ──Feign──► content（回写 view/like/comment_cnt 冗余计数）
```

### 1.2 代码结构

```
litisle-content
├── controller/        # ArticleController / BookController / DraftController / FeedController
├── service/           # ArticleService / BookService / DraftService / FeedService
├── mapper/            # ArticleMapper / BookMapper 等
├── domain/
│   ├── entity/        # Article / Book / ArticleDraft / BookChapter / ArticleImage / Tag
│   ├── dto/           # ArticleCreateDTO / DraftSaveDTO
│   └── vo/            # ArticleDetailVO / BookCardVO
├── constant/          # ContentBizConstant（推荐流长度等；切片上限已归 review 侧）
├── enums/             # 本服务无私有枚举，使用 api 共享
├── mq/                # ContentEventProducer
└── config/            # ContentConfig
（slicer/ 已移除：切片职责收口 review 消费端，content 不重复实现）
```

------

## 二、功能设计

### 2.1 功能清单

| 功能 | 说明 | 核心机制 |
| --- | --- | --- |
| 文章 CRUD | 富文本 HTML：标题≤100 + 摘要≤500 + 封面(fileId) + 分类 + 分区 | 软删 del_flag；删除/转非公开必发 content.unpublished（隐私红线） |
| 图片插入与对齐 | 正文只存 `{img:file_id}` 占位符，对齐权威在 article_image（5 种对齐） | 插图校验 fileId 归属本人且 bizType=ARTICLE_IMG（防引用他人文件/违规图） |
| 草稿自动保存 | 快照 UPSERT，draft_version 乐观锁，仅保留最近 N 版 | 归属校验防横向越权；同版本幂等跳过 |
| 分区管理 | 1-普通 / 2-作家（跳 DFA 全量人工审）；**阶段12：原 3-推荐流（仅 ADMIN/运营写）分区已取消，推荐流为 zone 1/2 聚合视图** | 一篇文章仅一个分区，冲突拒绝 |
| 批量发布 | 单批 ≤20，逐篇独立事务 + 逐篇结果回执 `{articleId, success, errorCode}` | 已发布篇目 CAS 不满足自动跳过（幂等） |
| 发布可见性 | 1-公开 / 2-仅自己 / 3-仅关注者 | 转非公开必发事件；**阶段9：原 4-密码访问（BCrypt + 失败限频防爆破）已下线，存量 visibility=4 迁移为 1** |
| 发布异常保稿 | 发布入口（所有校验前）内容快照进 article_draft，后续任何一步失败内容必已在草稿 | 方案B：PublishDraftSnapshotSupport UPSERT + REQUIRES_NEW 独立事务（主链路失败回滚不连带快照回滚）+ fail-safe warn 不阻断；图片校验失败错误信息携带「第 N 张图片(file_id=xxx)」定位（P1 2026-10-28） |
| 书籍/合集 | book + book_chapter，章节序号即排序键 | `uk(book_id, chapter_no)`；章节挂载校验文章归属本人 |
| 阅读历史 | 书籍历史（含最后章节）/ 独立文章历史 / 阅读即落历史（详情命中自动落，fail-safe） | 单表 biz_type=1/2，`uk_user_biz` 每人每类型每对象一行；PUT 上报：章节归属校验 + 2/60s 限频 + 仅前进不回退；自动路径按「最后一次阅读」刷新可回退 |
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
- **方案B**：status=2 禁改正文/标题，仅封面/摘要/分类/visibility/书籍挂载等元数据可改；改正文 → 软删旧文重新发布（防借编辑绕过复审）
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
A1 校验：trim 非空 + 去标签可见文本非空；标题≤100；正文/配图不同时空
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

- 对齐信息与正文解耦：换编辑器零成本；废弃草稿孤儿图片由 file 对账链路兕底

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
兕底：榜空/开关关 → 时间倒序直查（原阶段13 链路保留）
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

## 四、数据模型（Sql-Document §二，9 表摘要）

| 表 | 关键字段 | 设计原因 |
| --- | --- | --- |
| `article` | zone / title / content_html / cover_file_id / status / visibility / publish_batch_id / view_cnt·like_cnt·comment_cnt（冗余计数）/ content_hash / version | 计数冗余避免列表页 join 聚合，配合 Redis 异步刷新；`idx_zone_status` 支撑分区+状态过滤；**阶段9：access_password 列已删除** |
| `article_image` | article_id / draft_id（二选一，发布后清空 draft_id）/ file_id / position_order / align / width / height | 对齐信息与正文解耦；`idx_file_id` 支撑图片复审再聚合 |
| `article_draft` | article_id（新草稿 null，发布回填）/ draft_version 递增 | 草稿高频写低频读，独立表避免污染主表缓存 |
| `book` | status：0-连载中 / 1-已完结 / 2-下架 | 合集容器 |
| `book_chapter` | book_id + article_id + chapter_no，`uk(book_id, chapter_no)` | 章节序号不重复，序号即排序 |
| `reading_history` | biz_type（1-书籍 / 2-独立文章）+ biz_id，`uk_user_biz(user_id, biz_type, biz_id)`；`idx_user_time`（类型 tab）/ `idx_user_time_all`（混合时间线）双索引 | 书籍历史含最后章节（chapter_id 悬空列表容错）；每人每类型每对象一行=上次阅读记录，update_time 即最后阅读时间；阅读即落历史 fail-safe |
| `tag` | name（uk_name 唯一）/ use_count（冗余计数异步刷新）/ status（0-正常 1-封禁）/ del_flag | 标签全局池：审计与统计锚点；封禁标签列表侧不再聚合展示 |
| `article_tag` | article_id + tag_id，`uk(article_id, tag_id)` | 多对多关联；冲突幂等跳过；idx_tag_id 支撑按标签聚合文章 |
| `favorite` | target_type（1-文章 2-书籍）+ target_id，`uk_user_target(user_id, target_type, target_id)`；`idx_target` / `idx_user_time` | 一期单层收藏防重复；目标软删列表过滤，转私密/下架保留展示、详情可见性兜底；social 立项后迁移 favorite_item 或另定合并方案 |

**设计要点**：

1. **计数冗余**：view/like/comment_cnt 落 article，social 异步回写，列表页零 join
2. **草稿独立表**：高频写低频读隔离；draft_version 递增留痕，发布回填 article_id
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
| `litisle.content.feed.hot.refill-enabled / refill-interval-ms / refill-initial-delay-ms` | true / 300000 / 60000 | 灌榜任务开关（多实例靠分布式锁单点）/ 周期 5min / 启动延迟 1min |
| `litisle.content.feed.hot.full-refill-interval-ms` | 3600000 | 全量校正间隔（默认 1h）：增量轮只升不降，分数衰减靠低频全量轮覆盖收敛；榜缺失时无视本值直接全量重建（P1 2026-10-28） |
| `litisle.content.draft.keep-versions` | 10 | 草稿快照保留版数，超限滚动清理最旧快照 |
| `litisle.content.publish.batch-max` | 20 | 批量发布单批上限，防批量刷审 |
| `litisle.content.tag.max-per-article` | 5 | 单篇文章标签数上限，防关键词堆砌 |

------

## 三、接口设计（`/api/content`）

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| POST | `/article` | 登录（zone=2 需 AUTHOR） | 创建/发布 |
| PUT | `/article/{id}` | owner | status=0/3 全字段可编；status=2 仅元数据（方案B） |
| POST | `/article/{id}/submit` | owner | 驳回后修改重提（CAS 3→1） |
| DELETE | `/article/{id}` | owner | 软删 + 必发 content.unpublished |
| POST | `/draft/save` | 登录（仅本人草稿） | 草稿保存（幂等，baseVersion 乐观锁） |
| POST | `/article/publish-batch` | 登录 | 批量发布（单批 ≤20，逐篇结果回执） |
| POST | `/preview-check` | 登录·限频 | 仅返回 `{hit,risk}` 不回吐命中词 |
| GET | `/article/{id}` | 按 visibility | 详情/正文按可见性判定链收口（三档） |
| GET | `/feed` | 登录 | 推荐流（热度榜排序，榜空降级时间倒序；缓存 + 展示前回查） |
| POST/PUT/DELETE | `/book`·`/book/{id}`·`/book/{id}/chapter` | owner | 书籍/章节管理 |
| PUT/GET | `/reading-history` | 登录 | 历史上报（限频）/ 历史列表 / 续读；GET `/article/{id}` 详情命中自动落历史 |
| POST/DELETE/GET | `/favorite` · `/favorite/check` | 登录 | 收藏（幂等）/ 取消（幂等）/ 我的收藏 / 状态查询；写操作 20/60s 限频 |
| GET | `/book/{id}` · `/book/{id}/chapters` · `/book/{id}/chapter/{cid}` | 可选登录 | 书籍详情（上次阅读/收藏态）/ 章节列表分页 / 章节详情（正文+相邻章导航；触发计数与历史）；公开白名单三条（写端点仍 @SaCheckLogin） |
| GET | `/book/list` · `/book/my` | 匿名 / 登录 | 公开书籍列表（status!=下架 && 未软删，category 过滤）/ 我的书籍（author_id 硬收口，含下架书）；书籍发现入口（P2 2026-10-28） |
| GET | `/tags?keyword=` · `/tags/hot` | 登录 | 前缀搜索已有标签池 / 热门 TOP N |
| POST/DELETE | `/article/{id}/tags` | owner | 挂载/取消（≤5，新建过 DFA） |

### 3.1 限流矩阵（@RateLimiter(USER)，阈值外置 yml 可调）

| 端点 | 限流(60s) | 说明 |
| --- | --- | --- |
| POST `/article` · `/article/{id}/submit` | 10 | 发布/重提 |
| POST `/draft/save` | 10 | 自动保存高频写，防刷库 |
| POST/DELETE `/favorite` | 20 | 收藏写库防刷（取消收藏同限频） |
| POST `/article/publish-batch` | 3 | 批量重操作 |
| POST `/preview-check` | 10 | 预检 |
| PUT `/reading-history` | 2 | 配合前端 30s 节流 |
| GET `/feed` | 60 | 读多 |

### 3.2 事件矩阵（ContentEventProducer）

| 事件 | 消费方 | 说明 |
| --- | --- | --- |
| `content.submitted` | review | 进入待审（单条，事件体不含正文，review 回查 ContentClient 契约）；逻辑事件名，物理路由 = moderation.exchange / moderation.text.submit（review §3.2 契约，禁新建交换机） |
| `image.submit`（发布复审，scene=ARTICLE_IMG_RE_REVIEW） | review | 发布时对配图发复审（免文不免图）；与 file 上传预审共用路由键，scene 是唯一区分键；content_hash=全部配图 sha256 排序拼接后再哈希的聚合指纹 |
| `content.published` | search / notification / ai(RAG) | 发布成功；RAG 门控回查 status=2 且 visibility=公开 |
| `content.unpublished` | search / ai(RAG 移除) | **所有下线路径必发**（四路径统一收口）；同路径调 ContentUnpublishedInvalidator 失效 last-hash + text/img-result 缓存键（review §2.5 红线） |
| `moderation.result` | content（自筛 bizType=1，按 scene 分流：发布场景=文本链路 / ARTICLE_IMG_RE_REVIEW=图片复审链路）· notification（自筛做结果通知） | review 判定回写：PASS→1→2；REJECT 按现态分流 1→3 首发 / 2→4 复审下架兑底（宁严勿松，双链路聚合在消费端收口）；消费端 CAS 前比对事件体 contentHash 防陈旧事件覆盖（防 MQ 乱序误杀新提交）；2→4 复审下架分支同发 content.unpublished + 失效审核缓存键 + @AuditLog 留痕 |
| `moderation.appeal.result` | content（自筛 bizType=1） | 申诉改判 allow-list 对称恢复：3→2 / 4→2 仅此两向 |

- 消费侧：`moderation.result` 回写状态并通知作者（review v1.2 统一单路由，消费端自筛 bizType）；`user.banned` 封禁连带下架（发 content.unpublished）
- 路由映射：content.submitted 物理路由 = moderation.exchange / moderation.text.submit；content.published / content.unpublished / moderation.appeal.result 的物理路由键随 review MqConstant 统一收口定义，禁私自新建交换机
- 图片复审再聚合：review 按 article_image 的 `idx_file_id` 反查引用文章，重算 ARTICLE_IMG_RE_REVIEW 聚合结论

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
| 标签数超限 | ARTICLE_TAG_EXCEEDED | 单篇 > tag.max-per-article（5） |
| 非本人操作 | NO_PERMISSION | 非 owner 且非 ADMIN（草稿/文章/书籍/标签挂载） |
| 请求过频 | TOO_MANY_REQUESTS(10005) | @RateLimiter 触发 |

------

## 七、安全与可靠性

1. **UGC 回显安全**：入库前 HTML 白名单 sanitize（标题/摘要 strip tags 纯文本落库；正文净化白名单须保留 `{img:file_id}` 占位符）+ 出库输出编码双保险；审核只判定不净化，净化与判定职责分离（review 后台内容预览直吐库中 HTML，净化禁仅做出库——存储型 XSS 红线）
2. **发布幂等**：发布 CAS 防重；草稿幂等；事件消费按 articleId 幂等覆盖
3. **事务顺序铁律**：本地事务提交 → afterCommit 事件发送（失败重投/DLQ）
4. **越权防护**：草稿/文章/书籍按 author_id 归属校验防横向越权；非本人按不存在处理不暴露存在性（visibility=2）
5. **下架收口**：四路径（删除/转私密/复审驳回/封禁连带）统一收口发 content.unpublished，防向量库/索引残留（隐私红线）
6. **缓存三防**：互斥重建防击穿 + 空结果短缓存防穿透 + TTL ±10% 抖动防雪崩；展示前回查 status=2 且 visibility=1，已下架/转私密即时剔除；写入→失效对称铁律：content 侧新增任何 review 域缓存键写入（如 last-hash）必须同步核对下架四路径失效收口（ContentUnpublishedInvalidator），禁只写不失效。
   **热度榜灌榜口径（阶段14 + P1 增量 2026-10-28）**：全量轮只经影子键灌榜 + rename 原子换榜；增量轮允许对在线榜直接 ZADD，但前置约束=正式榜必须存在（refillAuto 分支①：榜缺失一律全量重建，防增量 ZADD 造出非空残榜绕过榜空降级兕底）+ 增量水位可用；增量 ZADD 后 ZREMRANGEBYRANK 裁剪保 top N 防无界增长；榜 TTL 30min ≥ 2×灌榜周期，增量轮成功即续期，任务死亡后自动过期降级，自愈闭环无补偿逻辑
7. **送审收口**：切片/聚合收口 review 消费端（SSOT，content 不重复实现）；切片失败重试不静默截断由 review 侧兜底；content 仅发单条 submitted 事件
8. **计数异步**：view/like/comment_cnt、tag.use_count 异步刷新，不阻塞主链路
9. **审计留痕**：ADMIN 下架、封禁连带下架等治理动作落 @AuditLog（操作人/时间/articleId/原因）；作者侧软删记操作日志
10. **密码安全（阶段9 已下线）**：原 visibility=4 密码访问功能（BCrypt 加密存储、校验失败限频防爆破）已整体下线；错误码 ARTICLE_PASSWORD_ERROR 为 common-core 公共契约保留不删