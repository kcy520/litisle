# litisle-content 内容模块功能设计文档

> 版本：v1.1（2026-10-28 依据 Function-Document §7 / Design-Document §2.3.3·§2.4·§2.5 / Sql-Document §二 收敛；
> v1.1 补后续另立项落地：P1 发布异常保稿 · P1 热度榜增量灌榜 · P2 书籍发现入口）
>
> 关联文档：[Function-Document.md](../overall_documen/Function-Document.md) §7 · [Design-Document.md](../overall_documen/Design-Document.md) §2.3.3 / §2.4 / §2.5 / §2.6 · [Sql-Document.md](../Sql-Document.md) §二 · [File-Architecture.md](../Architecture/File-Architecture.md)（fileId 契约）· [Content-Architeche.md](../Architecture/Content-Architeche.md)（架构口径权威）
>
> 状态：**落地阶段**（阶段1\~17 已落地，架构口径以 Content-Architeche.md 为权威；2026-10-28 后续另立项 P1/P2 已落地、P3 待 social 立项）；数据库 DDL 已定稿（Sql-Document §二，9 表）
>
> 定位：**独立微服务**（litisle-content），文章/草稿/书籍/合集的核心管理——创作 → 审核 → 发布 → 组织的完整创作流

***

## 一、模块定位与边界

| 项目   | 说明                                                                                                            |
| ---- | ------------------------------------------------------------------------------------------------------------- |
| 定位   | 创作内容 SSOT：文章 CRUD、草稿自动保存、批量发布、书籍/章节组织、阅读历史、推荐流                                                                |
| 职责边界 | 只管**内容本体 + 组织结构**；审核判定在 review、图片存储在 file、计数点赞在 social、全文/向量检索在 search/ai                                     |
| 外部依赖 | litisle-file（封面/正文图片 fileId）、litisle-review（送审 Feign/MQ）、litisle-api（契约）、MQ（事件生产）、Redis（推荐流缓存/免审直落 last-hash） |
| 不负责  | DFA 词库管理（review）、点赞评论（social）、收藏夹分组/佳句收藏（social；文章/书籍单层收藏一期由本模块临时承载，长期归 social，立项后迁移）、审核任务调度（review）          |

***

## 二、功能清单（对标 Function-Document §7，细化为设计级）

| 功能      | 设计要点                                                                                                                                                                                                                                 |
| ------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| 文章 CRUD | 富文本（HTML）：标题≤100 字符 + 正文 + 摘要≤500 + 封面(fileId) + 分类 + 分区；软删（del\_flag）仅 owner/ADMIN，删除必发 content.unpublished（见 §4.1 红线）                                                                                                              |
| 图片插入与对齐 | 正文任意位置插入，正文只存 `{img:file_id}` 占位符；对齐信息独立落 `article_image`（5 种对齐），渲染时替换（见 §5.3）；**插图须校验 fileId 归属本人且 bizType=ARTICLE\_IMG**（防引用他人文件/违规图）                                                                                              |
| 草稿自动保存  | `article_draft` 每次编辑存快照，`draft_version` 递增（乐观锁 + 仅保留最近 N 版，见 §6.2）；草稿图片可暂存（`draft_id` 关联，发布后清空）；保存幂等                                                                                                                                 |
| 分区管理    | `zone`：1-普通（所有用户可发）/ 2-作家（仅 AUTHOR 角色，跳 DFA 全量人工审）；一篇文章仅一个分区，冲突拒绝；**阶段12：原 3-推荐流（仅 ADMIN/运营可写）分区已取消，推荐流为 zone 1/2 聚合视图**                                                                                                             |
| 批量发布    | 同批次文章/章节共享 `publish_batch_id`；**单批 ≤20 篇**，逐篇独立事务 + 逐篇结果回执（见 §6.3）；支持多选批量改可见性（逐篇 CAS + 逐篇发事件）                                                                                                                                        |
| 发布可见性   | 1-公开 / 2-仅自己 / 3-仅关注者（**依赖 social 关注关系回查，跨服务契约**）；发布后仅 owner 可改；转非公开必发 content.unpublished；                                                                                                                                          |
| 发布异常保稿  | 发布入口（所有校验前）请求内容 UPSERT 快照进 article\_draft——后续任何一步失败（图文/图片归属/敏感词/CAS）内容必已在草稿；**方案B：PublishDraftSnapshotSupport + REQUIRES\_NEW 独立事务（主链路失败回滚不连带快照回滚）+ fail-safe warn 不阻断**；发布成功后主事务内回填草稿行 article\_id（已发布标识，兜底行不游离，P1 2026-10-29 补齐）；图片校验失败错误信息携带「第 N 张图片(file\_id=xxx)」定位（见 §6.1，P1 2026-10-28） |
| 书籍/合集   | `book` + `book_chapter`（`uk_book_id_chapter_no` 唯一索引，序号即排序键）；仅 owner 可管理；章节挂载需校验文章归属本人                                                                                                                                               |
| 书籍发现入口  | `GET /book/list`（匿名：status!=下架 && 未软删，category 精确过滤）/ `GET /book/my`（登录：author\_id 硬收口，含下架书作者自查、不含软删书，category/status/keyword 过滤）；排序走 PageQuery 默认 update\_time 倒序；书籍搜索为 search 外部依赖一期不做（见 §6.10，P2 2026-10-28）                      |
| 阅读历史    | `reading_history` 单表双类型（`uk_user_biz`），书籍历史含最后章节 / 独立文章历史 / 阅读即落历史；**PUT 上报 chapter\_id 须归属 book\_id + 2/60s 限频 + 仅前进不回退**（见 §6.6）；续读 + 历史列表                                                                                         |
| 大篇幅切片   | 切片与聚合判定均在 review 消费端（已落地 SSOT：llm.max-text-chars 阈值分片硬切，禁静默截断）；content 发布仅发单条 content.submitted 事件（事件体不含正文），任一片段 REJECT→整体 REJECT、含 SUSPICIOUS→转人审                                                                                   |
| 发布前预检   | `POST /api/content/preview-check`：仅返回 `{hit, risk}` 不回吐命中词；@RateLimiter(USER) 限频；不替代发布闸门                                                                                                                                             |
| 标签系统    | `tag` + `article_tag` 多对多；每篇 ≤5 个（`tag.max-per-article`）；标签文本过 DFA 闸门（受控新建）；**不参与推荐流排序权重**（详见 §6.7）                                                                                                                                  |

***

## 三、数据模型（Sql-Document §二，8 表摘要）

| 表                 | 关键字段                                                                                                                                                        | 设计原因                                                                                      |
| ----------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| `article`         | zone / title / content\_html / cover\_file\_id / status / visibility / publish\_batch\_id / view\_cnt·like\_cnt·comment\_cnt（冗余计数）/ content\_hash / version | 计数冗余避免列表页 join 聚合，配合 Redis 异步刷新；`idx_zone_status` 支撑分区+状态过滤；**阶段9：access\_password 列已删除** |
| `article_image`   | article\_id / draft\_id（二选一，发布后清空 draft\_id）/ file\_id / position\_order / align / width / height                                                           | 对齐信息与正文解耦；`idx_file_id` 支撑图片复审再聚合（按 file\_id 反查引用文章，重算 ARTICLE\_IMG\_RE\_REVIEW 聚合结论）     |
| `article_draft`   | article\_id（新草稿 null，发布回填）/ draft\_version 递增                                                                                                               | 草稿高频写低频读，独立表避免污染主表缓存                                                                      |
| `book`            | status：0-连载中 / 1-已完结 / 2-下架                                                                                                                                 | 合集容器                                                                                      |
| `book_chapter`    | book\_id + article\_id + chapter\_no，`uk(book_id, chapter_no)`                                                                                              | 章节序号不重复，序号即排序                                                                             |
| `reading_history` | biz\_type（1-书籍 / 2-独立文章）+ biz\_id，`uk_user_biz(user_id, biz_type, biz_id)`；`idx_user_time` + `idx_user_time_all` 双索引                                        | 书籍历史含最后章节；每人每类型每对象一行=上次阅读记录；阅读即落历史 fail-safe                                              |
| `tag`             | name（uk\_name 唯一）/ use\_count（冗余计数异步刷新）/ status（0-正常 1-封禁）/ del\_flag                                                                                       | 标签全局池：审计与统计锚点；封禁标签列表侧不再聚合展示                                                               |
| `article_tag`     | article\_id + tag\_id，`uk(article_id, tag_id)`                                                                                                              | 多对多关联；冲突幂等跳过；idx\_tag\_id 支撑按标签聚合文章                                                       |
| `favorite`        | target\_type（1-文章 2-书籍）+ target\_id，`uk_user_target(user_id, target_type, target_id)`；`idx_target` / `idx_user_time`                                        | 一期单层收藏（content 临时承载）：防重复收藏幂等；目标软删列表过滤；social 立项后迁移 favorite\_item 或另定合并方案                 |

***

## 四、文章状态机与可见性

### 4.1 status 流转

```
0-草稿 ──发布──→ 1-待审核 ──PASS──→ 2-已发布
                   │                  │
                   └──REJECT──→ 3-驳回 │
                                      └──复审驳回/封禁连带──→ 4-下架
3-驳回 ──修改重提──→ 1-待审核
```

- 发布 CAS：`status in (0,3) and del_flag=0`；**禁止 2→1**（改判只认申诉来源）
- 4-下架为**终态**：作者不可重提（复审驳回/封禁连带属治理动作）；对判定有异议走 review 申诉链路，不提供直接重提入口
- **已发布（status=2）禁止编辑正文/标题（方案B）**：仅封面/摘要/分类/visibility/书籍挂载等元数据可改；要改正文 → 软删旧文重新创作发布（防借编辑绕过复审）
- **转公开不重审**：visibility 2/3 → 1 直接恢复（阶段9：原 4-密码访问已下线）——content\_hash 校验内容未变 + 方案B 禁改正文，原审核结论仍有效；发 content.published
- 免审直落：last-hash + content\_hash 命中且内容未变且 del\_flag=0 → 0→2 直落
- 转非公开/删除 → **必须同发** **`content.unpublished`**（否则向量库/索引残留，隐私红线）

***

## 五、架构设计（分层与组件）

### 5.1 分层架构

```
Controller 层  ArticleController / BookController / DraftController / FeedController
    ↓ 参数校验（标题≤100/正文非空/分区权限）
Service 层     ArticleService（CRUD+发布编排）/ DraftService（快照幂等）
               BookService（合集+章节）/ FeedService（推荐流缓存）
    ↓
支撑组件       ZoneRouter（分区权限）/ ContentEventProducer（事件生产）
    ↓
外部协作       file(图片/封面 fileId) · review(送审) · social(计数回写) · search/ai(事件消费)
```

### 5.2 包结构（对标 Design-Document §2.3.3）

```
controller/  service/  mapper/  mq/(ContentEventProducer)
domain/      entity(Article/Book/ArticleDraft/BookChapter/ArticleImage) + dto + vo
slicer/      （已移除：切片职责收口 review 消费端，content 不重复实现）
constant/    ContentBizConstant（推荐流长度等）
enums/       无私有枚举，使用 api 共享
```

### 5.3 关键设计决策

| 决策      | 思路                                                                                        |
| ------- | ----------------------------------------------------------------------------------------- |
| 占位符渲染   | 正文只存 `{img:file_id}`；对齐权威在 `article_image`，渲染时批量查表替换为带对齐 class 的 `<img>`（对齐与正文解耦，换编辑器零成本） |
| 计数冗余    | view/like/comment\_cnt 落 article，social 异步回写，列表页零 join                                    |
| 草稿独立表   | 高频写低频读隔离；draft\_version 递增留痕，发布回填 article\_id                                             |
| 大字段延迟加载 | 列表 VO 不带 content\_html，详情页单独取                                                             |

***

## 六、功能实现思路

### 6.1 发布编排（核心链路）

```
A0 发布保稿快照（P1 2026-10-28，所有校验前）：请求内容（标题/摘要/正文/封面/分类/可见性）
   UPSERT 进 article_draft（PublishDraftSnapshotSupport）——后续任何一步失败内容必已在草稿；
   与前端自动保存互为兜底（不依赖前端行为）、与草稿单行 UPSERT 设计同构，零新表；
   事务口径：REQUIRES_NEW 独立事务（主链路失败回滚不连带快照回滚）+ 调用方 fail-safe（仅 warn 不阻断）；
   定位口径：draftId 非空覆盖本人草稿行（非本人/已删新建兜底行防越权）、为空新建兜底行；
   不搬运图片行/file_ref（正文占位符 {img:file_id} 已含 fileId 锚点，失败时归属切换未执行、
   原草稿图行未被触碰，快照重挂反引入兜底行图行/引用残留清理联动）；
   发布回填（P1 2026-10-29 补齐）：快照返回实际 draftId，文章建档成功后主事务内回填
   article_draft.article_id——草稿行保留带已发布标识（前端可展示「已发布」或过滤）、
   直发场景快照新建的兜底行不再游离；回填随主事务提交，后续任一步失败（免审提示/
   配图登记/CAS 并发）事务回滚连带回填回滚，草稿行回未关联态（仅发布成功才带标识），
   与 REQUIRES_NEW 已提交的快照内容互不影响；
   纯流转（publish-batch/submit 等不带正文的发布）article 建档行天然保稿，零动作
A1 校验：trim 非空 + 去标签可见文本非空；标题≤100；正文/配图不同时空
A2 免审直落判定 → 命中：CAS 0→2，发 content.published（免文不免图：配图仍走 file 侧送审）
A3 未命中：CAS 0→1 → 落库 + afterCommit 发 content.submitted → 接口即时返回「审核中」
A4 异步段（发布编排本地收尾）：组装 submit 事件体（{modType, bizType=1, bizId,
   scene, contentHash, retryCount, traceId}，不含正文——review 消费端按任务定位
   回查 ContentClient 契约取正文）→ 发单条 content.submitted（afterCommit）；
   切片与聚合判定均在 review 消费端（已落地 SSOT）；⚠️ 聚合判定与状态回写
   唯一收口在 result 消费端（review 板块三 3-5 口径），本段不做回写（防双写竞态）
```

**同步段/异步段边界（口径B）**：发布接口只做 A1→A3（校验+落库+事件），**不同步等审**——
审核时长不绑架发布 RT，对齐评论/私信先发后审与 file 上传后 safe\_flag 事件回写的既有模式。
作者侧体验：提交即返回「审核中」，终审结论由事件驱动（PASS→可搜索/可推流；REJECT→站内通知+驳回理由）。

作家分区（zone=2）跳 DFA 全量人工，走 review 人审；切片失败重试不静默截断（review 侧兜底）。

**配图发布复审**：发布时对配图发 image.submit（scene=ARTICLE\_IMG\_RE\_REVIEW，content\_hash=全部配图 sha256 排序拼接后再哈希的聚合指纹，对齐 review §2.3）；免文不免图的连带校验由 review last-hash 判定器（LastHashExemptChecker 契约已备）处理，content 不重复实现。

### 6.2 草稿自动保存

- **归属校验**：按 draft/article 的 author\_id 与当前登录用户比对，非本人拒绝——防横向越权改写他人草稿
- **并发冲突**：请求携带 baseVersion 与 draft\_version 比对：相等→UPSERT 递增；落后→返回冲突提示刷新（乐观锁拒绝后写覆盖先写，防丢更新）；同版本重复提交幂等跳过
- **快照保留**：同一草稿仅保留最近 N 版（`litisle.content.draft.keep-versions`，默认 10），超限滚动清理最旧快照，防 longtext 快照无限膨胀
- 草稿图片暂存 `article_image.draft_id`；发布时切换归属，废弃草稿的孤儿图片由 file 对账链路兜底

### 6.3 批量发布

- **批量上限**：单批 ≤20 篇（`litisle.content.publish.batch-max`），超限拒绝——防批量刷审
- **结果回执**：返回逐篇结果列表 `{articleId, success, errorCode}`，而非整体成败——部分失败可定位重提
- **事务边界**：逐篇独立事务，失败不回滚已成功篇目；重复提交幂等（已发布篇目不满足 CAS 自动跳过）
- 批量改可见性同口径：逐篇 CAS + 每篇独立发 content.unpublished/published 事件

### 6.4 可见性控制

- **判定链**：详情/正文接口按 visibility 收口——1 公开放行；2 仅自己（owner 校验，非本人按不存在处理，不暴露存在性）；3 仅关注者（**回查 social 关注关系契约** SocialClient.isFollowing，跨服务依赖；降级=按非关注者处理——宁严勿松，回查失败不静默放行，回查结果加短 TTL 缓存 ±10% 抖动）；**阶段9：原 4-密码访问（元信息可见、正文需密码）已下线**
- **变更权限**：仅 owner 可改 visibility；每次转非公开变更即发 content.unpublished（见 4.1 红线）
- **转公开**（visibility 2/3 → 1，status 不变——数字与 status 字典同值易误读，CAS 限定 `status=2 and del_flag=0`）：不重审——content\_hash 校验内容未变 + 方案B 禁改已发布正文，原审核结论仍有效；发 content.published 恢复曝光

### 6.5 推荐流

- **视图口径（阶段12）**：推荐流不是分区，是 zone 1/2 已发布公开文章的聚合视图；原 zone=3 仅 ADMIN/运营收录口径已随分区取消删除
- **增量灌榜（P1 2026-10-28）**：refillAuto 三分支编排——①榜缺失全量重建（防增量 ZADD 造残榜绕过榜空降级兜底）②距上次全量 ≥ full-refill-interval-ms（默认 1h）全量校正分数衰减 ③其余轮增量：只捞 update\_time ≥ 水位的变更文章重算 ZADD（update\_time 为 DB ON UPDATE CURRENT\_TIMESTAMP，浏览量自增/计数回写/状态流转/编辑均刷新，增量覆盖全部热度信号变更源）→ ZREMRANGEBYRANK 裁剪保 top N → 水位前推 + 榜 TTL 续期；ZADD 失败水位不推下轮重捞，水位缺失/非法走全量兜底——DB 压力随变更量而非存量走
- **缓存防护**：互斥重建（单飞锁）防击穿 + 空结果短缓存防穿透；TTL ±10% 抖动防雪崩
- **展示前回查**：推荐位文章渲染前回查 status=2 且 visibility=1，已下架/转私密即时剔除——防残留曝光

### 6.6 阅读历史

- **单表双类型**：`reading_history` 承载书籍历史（biz\_type=1，biz\_id=book.id，chapter\_id 有值）与独立文章历史（biz\_type=2，biz\_id=article.id）；`uk_user_biz(user_id, biz_type, biz_id)` 每人每类型每对象一行=上次阅读记录，update\_time 即最后阅读时间
- **阅读即落历史**：`GET /article/{id}` 详情命中（登录、非本人、可见性通过）后自动落历史，fail-safe 不阻断详情；归属按 `book_chapter.article_id` 判定——无挂载→文章历史、唯一挂载→书籍历史、多书归属（歧义）不自动落，交由书籍阅读页 PUT 显式上报
- **参数合法性（PUT 上报）**：chapter\_id 必须归属上报的 book\_id（book\_chapter 反查校验），拒绝伪造跨书章节
- **节流**：前端 ≥30s 节流上报 + 服务端 @RateLimiter(USER) 2/60s 兜底——高频写表的自我保护
- **一致性**：PUT 上报仅前进不回退（新 chapter\_no 小于当前值忽略，防多端乱序回退）；自动路径按「最后一次阅读」刷新（回读旧章可回退）；两路径共用 UPSERT，`uk_user_biz` 冲突转 update
- **容错**：章节行已删→列表章节信息留空（书名+最后阅读时间仍展示）；文章/书籍软删→列表静默跳过；均不物理删历史行

### 6.7 标签系统

- **与 category 分工**：category 是内容分类（单选、编辑体系枚举，服务类目浏览）；标签是内容关键词（多选、开放词汇，服务发现与聚合）——查询语义不同不重复
- **写路径**：输入触发前缀搜索已有标签池（防碎片化），确无命中才允许新建——新建走 DFA 闸门（同步，命中拒挂），存量标签挂载零审核成本；挂载/取消随文章编辑，`uk_article_tag` 冲突幂等跳过
- **数量上限**：每篇 ≤5 个（`litisle.content.tag.max-per-article`）——防关键词堆砌式刷标签
- **use\_count**：冗余计数异步刷新（与 view\_cnt 同机制），不阻塞主链路；支撑热门标签 TOP N
- **方案B 关系**：status=2 允许增删标签（标签不参与审核聚合、不影响 content\_hash）——仅 owner，新增仍过 DFA
- **不参与推荐流排序权重**（一期定死）：推荐流为 zone 1/2 聚合视图（预计算排序，阶段12 取消 zone=3 分区），标签权重留二期；一期仅做标签聚合列表页 + 热门标签 TOP N
- **联动**：search 索引标签字段；下架/转私密文章即时剔除出标签聚合列表（复用 §6.5 展示前回查口径）；RAG 入库 metadata 可带标签提升召回

### 6.8 收藏（content 一期临时承载）

- **归属**：架构上收藏归 social，一期 litisle-social 空壳、目标校验需跨服务回查 content，故单层收藏（无分组）由 content 临时承载；social 立项后迁移 favorite\_item 或另定合并方案；收藏夹分组/佳句收藏仍归 social
- **幂等**：`uk_user_target(user_id, target_type, target_id)` 唯一键——重复收藏捕获 DuplicateKeyException 按成功、未收藏取消影响行数 0 也成功
- **写入前目标校验（同域零跨服务）**：文章对齐详情可见性口径（未删 + 非本人须发布态 + PUBLIC 放行/PRIVATE 拒/FOLLOWERS 回查 FollowRelationCache，social 不可用 fail-fast）；书籍未删 + 下架仅作者可收藏；不存在/不可见统一 NOT\_FOUND 不暴露存在性
- **列表**：create\_time 倒序，targetType 三态过滤；目标软删不出列表（SQL del\_flag 过滤），转私密/下架保留展示、详情可见性兜底
- **状态回填**：文章详情 VO favorited（匿名 false）；列表不逐条回填防 N+1，列表态前端单独 GET /check
- **限频**：POST/DELETE /favorite 20/60s（USER）

### 6.9 书籍阅读闭环

- **三读端点（可选登录）**：GET /book/{id}（详情：章节数/首尾章/上次阅读/收藏态）、GET /book/{id}/chapters（chapter\_no 升序分页，默认 50 上限 100）、GET /book/{id}/chapter/{chapterId}（正文+prev/next 导航）；网关公开白名单三条（写端点仍 @SaCheckLogin）
- **可见性范围（visibilities）**：作者 \[1,2,3] / 匿名 \[1] / 登录非作者先 existsFollowersChapter 省回查，有关注者章节才回查关注关系得 \[1,3]；social 不可用 catch 降级 \[1]+warn，章节阅读主链路不因 social 抖动失败
- **正文复用**：章节详情正文走 articleService.getDetail 判定链（未发布/已删/可见性拦截 → ARTICLE\_NOT\_FOUND）；章节行校验防跨书（BOOK\_CHAPTER\_MISMATCH）；相邻章 SQL 层按 visibilities 过滤（跳过不可见章防 404）
- **阅读副作用**：BookReadSupport 收口——浏览计数所有访问者都记（含匿名/作者，按文章维度 PV+去重窗口）；书籍历史仅登录非作者（recordBookRead，uk\_user\_biz 幂等）；两步各自 fail-safe
- **上次阅读**：书籍详情 getLastRead 后按当前可见性复核（章节已删/超出可见范围 → 置 null，前端回退第一章），不物理删历史行
- **空书**：chapterCount=0、first/latest/lastRead 全 null、列表空页，前端「暂无章节」

### 6.10 书籍发现入口（P2 2026-10-28）

- **公开书籍列表（GET /book/list，匿名）**：硬条件 status != 下架（连载中/已完结对外可见，与 requireReadableBook 口径一致）+ del\_flag=0；category 可选精确过滤（book.category 为自由字符串，MP 参数化无注入风险）；排序走 PageQuery（默认 update\_time 倒序，匿名端 sortBy 仅通用时间列白名单）；网关 `/api/content/book/*` 一层通配已覆盖，零白名单改动
- **我的书籍（GET /book/my，登录）**：author\_id=当前登录人硬收口（不信任前端）+ 未软删（软删书不展示，回收站概念未立项）；含下架书（作者自查）；category/status/keyword（书名模糊，idx\_author\_id 前缀过滤后量小）可选过滤；复用文章 my 列表编排模式
- **VO**：BookCardVO 卡片字段（无章节明细，走详情/章节分页接口）；book 无热度计数冗余列，热度排序待计数落地扩展（P3 social 依赖）
- **书籍搜索（一期不做）**：search 模块扩展书籍索引为外部依赖；无索引 SQL LIKE 全表扫不立项（书籍量小可接受；量大再加 title 前缀索引）

***

## 七、事件矩阵与外部联动

| 事件                       | 消费方                                                                          | 说明                                                                                                        |
| ------------------------ | ---------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| content.submitted        | review                                                                       | 进入待审（事件体不含正文，review 回查 ContentClient 契约）                                                                  |
| content.published        | search / notification / ai(RAG)                                              | 发布成功；RAG 门控回查 status=2 且 visibility=公开                                                                    |
| content.unpublished      | search / ai(RAG 移除)                                                          | 所有下线路径必发；同路径经 ContentUnpublishedInvalidator 失效 last-hash + text/img-result 缓存键（review §2.5 红线，挂起项 4-4 回收） |
| moderation.result        | content（自筛 bizType=1，按 scene 分流：发布场景=文本链路 / ARTICLE\_IMG\_RE\_REVIEW=图片复审链路） | review 判定回写；REJECT 按现态分流 1→3 首发 / 2→4 复审下架兑底（宁严勿松）                                                        |
| moderation.appeal.result | content（仅自筛 bizType=1；bizType=2 为评论，评论改判恢复属评论模块职责，勿在 content 消费）             | 申诉改判 allow-list 对称恢复：article 3→2 / 4→2（仅此两向，挂起项 5-4.4 回收）                                                 |

- 下线键失效：下线四路径（删除/转私密/复审驳回 2→4/封禁连带）发 unpublished 同时经 ContentUnpublishedInvalidator 契约失效 last-hash + text/img-result 缓存键（review §2.5 红线，挂起项 4-4 回收；与 §九.5 四路径口径统一）
- appeal.result 事件体：`{ bizType, bizId, scene, appealId, verdict, contentHash, traceId }`（字段名与 result 体对齐，随 review MqConstant 统一收口；contentHash 供消费端陈旧性比对，防迟到旧申诉改判覆盖新内容状态）
- 图片复审再聚合：review 按 article\_image.idx\_file\_id 反查引用文章，重算 ARTICLE\_IMG\_RE\_REVIEW
- RAG 入库条件：公开 + PASS + ≥200 字 + 非水帖 + DFA 未命中高风险，AUTHOR 优先

***

## 八、接口概要（/api/content）

| 方法              | 路径                                  | 权限                  | 说明                                                                              |
| --------------- | ----------------------------------- | ------------------- | ------------------------------------------------------------------------------- |
| POST            | /article                            | 登录（zone=2 需 AUTHOR） | 创建/发布                                                                           |
| PUT             | /article/{id}                       | owner               | status=0/3 全字段可编；status=2 仅元数据（方案B，正文/标题禁改→重发路径）                                |
| POST            | /article/{id}/submit                | owner               | 驳回后修改重提（CAS 3→1）                                                                |
| DELETE          | /article/{id}                       | owner/ADMIN         | 软删 + 必发 content.unpublished                                                     |
| POST            | /article/batch-visibility           | owner               | 批量改可见性（逐篇 CAS + 逐篇发事件，§6.3）                                                     |
| POST            | /draft/save                         | 登录（仅本人草稿）           | 草稿保存（幂等）                                                                        |
| POST            | /article/publish-batch              | 登录                  | 批量发布                                                                            |
| POST            | /preview-check                      | 登录·限频               | 仅返回 {hit,risk} 不回吐命中词                                                           |
| GET             | /article/{id}                       | 按 visibility        | 按可见性判定链放行（三档）                                                                   |
| GET             | /feed                               | 登录                  | 推荐流                                                                             |
| POST/PUT/DELETE | /book·/book/{id}·/book/{id}/chapter | owner               | 书籍/章节                                                                           |
| GET             | /book/list · /book/my               | 匿名 / 登录             | 公开书籍列表（status!=下架 && 未软删，category 过滤）/ 我的书籍（author\_id 硬收口，含下架书）（P2 2026-10-28） |
| PUT/GET         | /reading-progress                   | 登录                  | 进度/续读                                                                           |
| GET             | /tags?keyword= · /tags/hot          | 登录                  | 前缀搜索已有标签池 / 热门 TOP N                                                            |
| POST/DELETE     | /article/{id}/tags                  | owner               | 挂载/取消（≤5，新建过 DFA）                                                               |

### 限流矩阵（@RateLimiter(USER)，阈值外置 yml 可调）

| 端点                                   | 限流(60s) | 说明          |
| ------------------------------------ | ------- | ----------- |
| POST /article · /article/{id}/submit | 10      | 发布/重提       |
| POST /draft/save                     | 10      | 自动保存高频写，防刷库 |
| POST /article/publish-batch          | 3       | 批量重操作       |
| POST /preview-check                  | 10      | 预检          |
| PUT /reading-progress                | 2       | 配合前端 30s 节流 |
| GET /feed                            | 60      | 读多          |

***

## 九、安全与可靠性

1. UGC 回显 HTML sanitize + 输出编码；sanitize 层次：入库前 HTML 白名单净化（标题/摘要 strip tags 纯文本落库；白名单保留 `{img:file_id}` 占位符）+ 出库编码双保险，禁仅出库净化（review 后台内容预览直吐库中 HTML，属存储型 XSS 攻击面）；审核只判定不净化
2. 发布 CAS 防重；草稿幂等；事件消费按 articleId 幂等覆盖
3. 事务顺序铁律：本地事务提交 → 事件发送（失败重投/DLQ）
4. 切片并行线程池隔离；缓存抖动防雪崩；计数异步刷新
5. 下架四路径（删除/转私密/复审驳回/封禁连带）统一收口发 content.unpublished
6. **审计日志**：ADMIN 下架、封禁连带下架等治理动作落 @AuditLog 留痕（操作人/时间/articleId/原因）；作者侧软删记操作日志

***

## 十、配置外置清单（litisle.content.\*，运营可调参数统一收口）

| 配置项                                     | 默认值      | 说明              |
| --------------------------------------- | -------- | --------------- |
| slice.max-chars                         | 2000     | 切片字数上限          |
| feed.cache-ttl-seconds / feed.page-size | 300 / 20 | 推荐流缓存 TTL 与分页大小 |
| draft.keep-versions                     | 10       | 草稿快照保留版数（§6.2）  |
| publish.batch-max                       | 20       | 批量发布单批上限（§6.3）  |
| tag.max-per-article                     | 5        | 单篇文章标签数上限（§6.7） |

