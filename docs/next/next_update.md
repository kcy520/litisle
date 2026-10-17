# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分
- 验证部分

### 文档说明:

- 代办任务的具体实现步骤 参考status.md文档

### 归档与暂不实现（板块完成后的文档迁移规则）

**相关文档职责**：

| 文档 | 职责 |
| --- | --- |
| `docs/next/next_update.md`（本文档） | 仅收纳「待办 + 待验证」的活跃任务，按 类别（代码/验证）→ 模块 → 板块/任务 组织 |
| `docs/next/status.md` | 各待办条目的具体实现步骤（细案），本文档不重复 |
| `docs/next/Temporarily_not_implemented.md` | 收纳暂不实现/暂不做/挂起项，每条必须附触发条件 |
| `docs/archive/` | 已完成板块的实现记录归档，命名惯例：按日期（如 `20260929-implementation-records.md`）或按主题/模块（如 `review-implemention-records.md`） |

**板块完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将该板块的实现步骤/完成记录（原散落在 status.md 或本文档条目内）整理移入 `docs/archive/` 对应归档文档（新板块新建归档文件，遵循上方命名惯例；已有同模块归档则追加章节）。
2. **本文档回填**：将已完成条目从本文档删除（或标注 ✅ 与日期后移除），保持本文档只留活跃待办。
3. **挂起项转移**：板块中因宿主模块未开发而无法落地的事项（如契约已备但入口未建），移入 `Temporarily_not_implemented.md` 对应模块节，并写明触发条件，供宿主模块开发时迁回本文档。
4. **预留项登记**：板块规划中明确「暂不实现/暂不做」的事项（含触发条件与决策原因），一并移入 `Temporarily_not_implemented.md`，不在本文档保留。

> 纪律：迁移时条目内容原样搬运（含口径修正、注意事项），不压缩关键细节；归档文档与本文档/Temporarily 清单之间用相对路径互链，保证可回溯。








--------------------------

## 待办部分

### 代码部分

#### content模块

> 板块划分依据：Content_Function_Develop.md / Content-Architecture.md；推进顺序：板块一 → 二 → 三 → 四/五/六（可并行）→ 七 → 八（依赖 review 板块三 3-5 消费端联调）

##### 板块一：模块基建与文章 CRUD

###### 1.1 实体/Mapper/状态机基建

- [ ] Sql-Document §二 8 表实体与 Mapper；article 状态机（0-草稿/1-待审/2-已发布/3-驳回/4-下架终态）流转与 CAS 铁律（禁 2→1）；方案B（status=2 禁改正文/标题）
  - ✅ 2026-10-17：实体/Mapper、del_flag 逻辑删除配置、ArticleStateMachine（canTransit 白名单，禁 2→1/4 终态）已落地，细目见 status.md 板块一 [1.1]；CAS 铁律与方案B 随 1.2/3.1 落地，本条保持待办

###### 1.2 文章 CRUD 接口

- [x] 创建/编辑/软删；插图 fileId 归属校验（本人 + bizType=ARTICLE_IMG）；article_image 对齐权威 + `{img:file_id}` 占位符渲染链路
  - ✅ 2026-10-17：ArticleServiceImpl 7 方法全量实现 + ArticleHtmlSanitizer（utils/）+ ContentEventProducer + 插图归属校验/渲染收口，细目见 status.md 板块一 [1.2]；跨板块遗留：sendSubmitted（已随三3.4 封装收口）/sendPublished（四4.2）、FOLLOWERS 回查（六）、last-hash 失效（已随三3.4 方案A 收口）、标签挂载（三）；编译验证已通过（2026-10-17）

###### 1.3 常量与错误码

- [x] ContentBizConstant、ARTICLE_* 错误码（✅ 2026-10-17：30007~30014 共 8 码扩展入 common-core ErrorCode，细目见 status.md 板块一 [1.3]）；治理动作 @AuditLog 审计留痕随 [1.2/1.3] Controller 项落地

------------

##### 板块二：草稿系统

###### 2.1 草稿快照保存

- [x] baseVersion 乐观锁 + 同版本幂等；归属校验防横向越权
  - ✅ 2026-10-17：DraftServiceImpl#save 四分支严格按序判定（落后冲突/超前非法/同版本幂等/同版本递增）+ locateDraft 归属校验，细目见 status.md 板块二 [2.1]；编译验证已通过

###### 2.2 快照保留与图片暂存

- [x] keep-versions 滚动清理；draft_id 图片暂存 → 发布归属切换；孤儿图片 file 对账兑底
  - ✅ 2026-10-17：afterCommit 滚动清理（keep-versions=10 配置化）+ 草稿图片 draft_id 暂存整体重挂（ARTICLE_IMG 归属校验同口径），细目见 status.md 板块二 [2.2]；跨板块遗留：发布归属切换 draft_id→article_id（板块三 3.1）、孤儿图片 file 对账兑底（file 侧）对账兜底

------------

##### 板块三：发布与送审链路

###### 3.1 发布编排

- [x] 同步段 A1→A3（校验/免审直落/落库发事件）；异步段 A4；afterCommit 事件铁律——已落地：createAndPublish 编排 A0 分区权限（ZoneRouter）→ A1 校验（PublishTextPrecheck 契约门面+图文同空拒+插图归属）→ sanitize 三层铁律 → DFA 同步闸门（DfaPublishGate：HIGH 拦 70001 禁回吐命中词/MID 留痕，zone=2 跳 DFA）→ A2 免审三分支（LastHashExemptChecker.decide：EXEMPT_PASS 直落 CAS 1→2 不发事件、REJECT_HINT 前置拒、SUBMIT 照常送审；免文不免图 imgReReviewPassed 门控）→ A3 落库 status=1 + afterCommit 发单条 submitted；驳回重提 3→1 走 /submit 专口（状态机禁 2→1）。注：CAS 口径等价实现为「insert 1 → CAS 1→2」（免审键需 articleId）；content.published 随板块四 [4.2] 契约缺口接通；异步段 A4 为 review 消费端职责（板块八收口）。

###### 3.2 送审事件与配图复审

- [x] 单条 content.submitted（事件体不含正文）；切片/聚合均在 review 消费端（已落地 SSOT，content 不重复切片）；配图发布复审发 image.submit（ARTICLE_IMG_RE_REVIEW 聚合指纹）——已落地：content.submitted afterCommit 单条发送（ContentEventProducer.sendSubmitted）；配图复审 image.submit（ContentEventProducer.sendImageSubmit，scene=ARTICLE_IMG_RE_REVIEW、contentHash=全部配图 sha256 字典序排序拼接后二次 SHA-256 聚合指纹，复用 SubmitTextAssembler.contentHash 口径）；免审图片连带校验经 LastHashExemptChecker.imgReReviewPassed 门控（免文不免图，EXEMPT_PASS 仅在配图复审已 PASS 时可达，不重复发事件）；新增 FileClient.batchGetSha256 契约端点（file 侧实现，审核链路 fail-fast）

###### 3.3 批量发布与分区

- [x] 单批 ≤20 逐篇事务+结果回执；zone 1/2/3 权限路由——已落地：publishBatch（TransactionTemplate 逐篇独立事务+逐篇回执+publish_batch_id 回填，CAS 0→1 未命中幂等跳过）/batchVisibility（逐篇 CAS+转私密 unpublished 事件，转公开 status=2 不重审）/previewCheck（DfaFacade 同快照，仅回 {hit,risk}）/verifyPassword（BCrypt 不回显）四方法收口 ArticlePublishServiceImpl；ZoneRouter 分区权限单点收口（发布 A0+编辑分区变更复用）；ContentProperties.publish.batchMax 配置绑定（application.yml 默认 20，Nacos 可覆盖）。注：批量发布走送审路径不做免审直落；content.published 事件随板块四 [4.2] 契约缺口接通。

###### 3.4 事件生产

- [x] ContentEventProducer：submitted/published/unpublished；下架四路径统一收口——已落地（2026-10-17）：三事件封装收口（基座四字段统一 fillBase）；下架三路径接入 content 侧（软删/单篇转私密/批量转私密：afterCommit 发 unpublished + 事务内同步调 UnpublishedCacheCleaner 失效 last-hash/text/img-result 缓存键，方案 A SCAN 通配版本段，键拼 SSOT 新增 ModerationConstant 两 SCAN 常量，fail-safe 残留键 TTL 兑底）；复审驳回/封禁连带随板块八 [8.1]/[8.2] 复用接入；published 事件随板块四 [4.2] 契约接通；Invalidator 签名澄清项已闭环（Temporarily_not_implemented.md）

> ⚠️ 前置阻塞已解除：content.unpublished 发送仅依赖本地 RabbitMQ，可独立联调；review 送审契约联调随板块八消费端接入时验证（见 Temporarily_not_implemented.md）

------------

##### 板块四：可见性控制与安全

###### 4.1 visibility 判定链

- [x] 1/2/3/4 四级；仅关注者回查 social 契约；转公开不重审（content_hash 校验）
  - ✅ 2026-10-17：FOLLOWERS 分支接通 FollowRelationCache（短 TTL 60s±10% 抖动 + 负缓存 + Redis 故障降级直查；social 回查 fail-fast 不静默放行，非关注者按不存在处理）；SocialClient 补 isFollowing 契约 + MessageClientFallbackFactory 对称降级（提供方端点待 social 实现，联调随 social 落地验证）；转公开不重审落地：update 单篇 visibility 2/3/4→1 且 status=2 走独立 CAS（where status=2）+ afterCommit 发 content.published（ContentPublishedEvent 契约补齐 + ContentEventProducer.sendPublished）；细目见 status.md 板块四 [4.1]

###### 4.2 密码访问与下架收口

- [x] BCrypt + 失败限频防爆破；四路径必发 content.unpublished（隐私红线）
  - ✅ 2026-10-17：新增端点 POST /article/{id}/verify-password（@RateLimiter 5/60s + LoginRetryLimiter 连 5 次失败锁 60s 双层防爆破，identifier=article-pwd:{userId} 隔离命名空间，超限抛 10005）；验密通过写短时效凭证标记 content:pwd-pass:{operatorId}:{articleId}（TTL 10min），getDetail PASSWORD 分支命中放行正文；access_password 不落草稿快照验证性确认（DraftSaveDTO/article_draft 均无密码字段，天然满足）；批量路径 batchVisibility 转公开分支补发 content.published（单篇转公开 [4.1] 已落地），转私密/删除/封禁连带/复审驳回四路径 unpublished 收口既有；细目见 status.md 板块四 [4.2]

###### 4.3 限流矩阵

- [x] @RateLimiter(USER) 全端点落地（对齐挂起项 3-7.2）
  - ✅ 2026-10-17：§3.1 矩阵八端点全量核对——POST /article 与 /article/{id}/submit（10）、/draft/save（10）、/article/publish-batch（3）、/preview-check（10）、/article/{id}/verify-password（5，[4.2] 刚挂）、PUT /reading-progress（2）、GET /feed（60）均已挂 @RateLimiter(USER) 且 count 与 ContentLimitConstant 一致；本次补唯一缺口 GET /tags?keyword=（TAG_SEARCH_MAX_COUNT=30，矩阵外补充防爬）；阈值统一收口 ContentLimitConstant（@RateLimiter 注解参数需编译期常量，yml 外置需切面运行时配置支持，属 common-redis 增强项不在本清单）；3-7.2 挂起项可回收（content 侧限流范式已就绪，review 侧成本护栏随板块八接入）

------------

##### 板块五：书籍合集与阅读进度

###### 5.1 书籍/章节管理

- [x] book / book_chapter（uk_book_id_chapter_no）；挂载校验文章归属本人
  - ✅ 2026-10-17：BookServiceImpl 五方法全量落地（Controller/实体/DTO/VO 既有，服务层原为 TODO 壳）：① create 默认 status=0 连载中 + category 空落默认「小说」；② update owner 校验 + 非 null 字段更新；③ delete 软删 CAS（where del_flag=0，重复删幂等）+ 物理清挂载行（挂载关系无独立业务价值，文章本体不动）@Transactional；④ addChapter 四重校验：书籍归属 requireOwnedBook 复用 → 文章归属本人（挂他人文章 NO_PERMISSION，非已发布 ARTICLE_PARAM_INVALID，软删按不存在）→ uk 冲突预检 → 落挂载行（并发双挂 DB uk 兑底转 30015）；⑤ removeChapter 书籍归属 + 章节归属（chapter_id 不属 book_id 拒 BOOK_CHAPTER_MISMATCH，防伪造跨书章节）仅删挂载行不删文章；新增 ErrorCode BOOK_CHAPTER_NO_CONFLICT(30015)；细目见 status.md 板块五 [5.1]

###### 5.2 阅读进度

- [x] 章节归属校验 + ≥30s 节流 + 仅前进不回退 + 续读
  - ✅ 2026-10-17：ReadingProgressServiceImpl 两方法全量落地（Controller/实体/Mapper/VO 既有，服务层原为 TODO 壳）：① report：chapter_id 归属 book_id 反查（章节行缺失或 bookId 不匹配拒 BOOK_CHAPTER_MISMATCH，伪造跨书章节被拒；书籍软删时挂载行随删，无需额外 book 查询）→ UPSERT：selectOne uk_user_book 无则 insert（并发首报 catch DuplicateKeyException 重读转 update，uk 兑底）→ 仅前进不回退：progress 无 chapter_no 列，经 book_chapter 反查双方序号比对，新序号 ≤ 当前序号静默忽略（多端乱序不回退），当前章节行被退章移除时容错放行；② getLastChapter：无进度返回 null（前端落第一章），进度指向章节已被退章移除时按 null 返回前端按最新章容错；③ 节流：Controller @RateLimiter(USER)=2/60s 既有（[4.3] 已核对），配合前端 30s 节流；细目见 status.md 板块五 [5.2]

------------

##### 板块六：标签系统

###### 6.1 标签挂载

- [x] tag/article_tag 多对多；≤5 上限；uk 冲突幂等跳过
  - ✅ 2026-10-17：TagServiceImpl#attachTags/detachTags 全量落地（Controller/实体/Mapper 既有，服务层原为 TODO 壳）：① attachTags：owner 校验（requireOwnedArticle 复用，软删按 ARTICLE_NOT_FOUND 不暴露存在性）→ 同名单请求内去重收敛 + 已有挂载数与新增合计 >5 抛 ARTICLE_TAG_EXCEEDED（新常量 TAG_MAX_PER_ARTICLE=5 收口）→ 仅池内命中可挂（in name + status=NORMAL + del_flag=0；封禁标签拒挂不给违规词引流）→ 已挂载过滤后逐条 insert（并发双挂 catch DuplicateKeyException 幂等跳过不报错不计数，清单口径与 addChapter 的冲突拒绝不同）→ 挂载成功才 use_count 原子 +1 @Transactional；② detachTags：owner 校验 → 先查实际存在的挂载行（幂等重复摘除不多扣）→ 删挂载行 → use_count 原子 -1（GREATEST 兑底不为负）；③ 方案B：requireOwnedArticle 不校验文章 status（status=2 允许增删标签，不影响 content_hash 不参与审核聚合）；池中无命中标签名的新建 DFA 闸门随 [6.2] 接入；细目见 status.md 板块六 [6.1]

###### 6.2 标签池与闸门

- [x] searchByPrefix 前缀搜索（防碎片化）+ DFA 新建闸门（同步，命中拒挂）
  - ✅ 2026-10-17：① searchByPrefix：空参空列表 → likeRight 前缀（%/_/\\ 转义防通配符全池拖出）+ status=NORMAL + del_flag=0 + use_count 倒序 LIMIT 10（TAG_SEARCH_RESULT_LIMIT）；② attachTags 接 DFA 闸门：池内全量按名查询（不过滤状态）→ 正常态可挂/封禁已删静默跳过/池外名字同步 dfaFacade.check(DfaScenes.BIO, name)（BIO 短文本词库，标签/简介/代表作共用）→ 任一命中即抛 SENSITIVE_WORD_DETECTED(70001)（标签是聚合锚点宁严勿松，不分 HIGH/MID）→ 未命中落池（useCount=0，uk_name 并发冲突重查幂等）；③ 名称规范化：trim + 空串跳过 + >64 字拒（TAG_NAME_MAX_LENGTH 与词库词条同口径）；④ 契约缺口修复：DfaScenes.normalize 未映射 BIO（BIO 在 textScenes 全集但 check(BIO) 被 default 判非文本抛异常，直审场景不可达）→ 补 case BIO -> BIO 直映；⑤ 顺带修复 6.1 遗留编译问题：tagIdsOf 占位方法及三处调用全部内联替换
- [x] use_count 冗余计数异步刷新（与 view_cnt 同机制）；热门 TOP N 走 GET /tags/hot
  - ✅ 2026-10-17：① hotTags：Redis 短缓存（key=content:tag:hot:10，值=TagVO[] JSON，TTL 基准 300s ±10% 抖动防雪崩，缓存三防对齐 §七.6）→ miss 查库 status=NORMAL + del_flag=0 + use_count 倒序 LIMIT 10（TAG_HOT_TOP_N，走 idx_use_count）；② use_count 机制定位修正：挂载/摘除同事务原子增减（[6.1]，计数正确性来源，比异步回写更准），另建 TagCountReconcileJob 定时对账兜底（单条 UPDATE 自联仅修漂移行，默认 1h 间隔/5min 初延，litisle.content.tag.reconcile 可调）；CacheConstant 新增 CONTENT_TAG_HOT，ContentLimitConstant 新增 4 常量；细目见 status.md 板块六 [6.2]

------------

##### 板块七：推荐流

###### 7.1 zone=3 收录与缓存

- [x] 仅 ADMIN/运营写；缓存三防（互斥重建/空值短缓存/TTL 抖动）
  - ✅ 2026-10-17：① zone=3 收录校验核对确认已落地（[3.3] ZoneRouter 单点收口：checkPublishPermission RECOMMEND 分支，create 步骤 A0 + update 均调用，接口未暴露给普通作者）；② FeedServiceImpl.feed 全量落地（原 TODO 壳）：快路径读缓存 → miss 后单飞锁互斥重建（tryLock 2s + 双检回填 + finally unlock）→ 空结果短缓存 60s 防穿透（恶意翻页/冷页不持续打库）→ 正常结果 TTL 基准 300s ±10% 抖动防雪崩（litisle.content.feed.{cache-ttl-seconds,page-size} 外置 yml，ContentProperties 新增 Feed 嵌套类）；未抢到锁让渡 200ms 重读、仍 miss 兑底直查库不拒绝服务；③ 回源口径：zone=RECOMMEND + status=2 + visibility=PUBLIC + del_flag=0，createTime 倒序（article 无 publish_time 列，发布时间以 createTime 代理；ai 算法重排接入后替换数据源）；④ CacheConstant 新增 CONTENT_FEED；细目见 status.md 板块七 [7.1]

###### 7.2 展示前回查

- [x] 渲染前回查 status=2 且 visibility=1，下架/转私密即时剔除
  - ✅ 2026-10-17：核对确认已随 [7.1] 落地，零新增代码——FeedServiceImpl#recheckFilter（L131）：对缓存/回源所得条目批量回查权威态（in id + status=PUBLISHED + visibility=PUBLIC + del_flag=0，仅取 id 列走主键），不满足条目即时剔除；全链路三道防线叠加：① 缓存写入侧过滤（回源 SQL 同口径）② 每次读回查兑底（缓存 TTL 内下架/转私密内容防残留曝光）③ 下架四路径 content.unpublished（板块四收口）；标签聚合列表页端点未建（未在 §3.1 矩阵），复用同口径的约定迁移至阅读侧接口实现时落地；细目见 status.md 板块七 [7.2]

------------

##### 板块八：审核回写与联动消费

###### 8.1 result 回写与申诉改判

- [x] 三项（result 回写 / 双链路聚合 / 申诉改判）核对确认主体已落地 + 三缺口补齐
  - ✅ 2026-10-17：主体（板块三 3-5 期间实现）核对确认：ModerationResultConsumer / ModerationAppealResultConsumer（消费编排）+ ArticleReviewWriteBackServiceImpl（handleResult：bizType 自筛/scene 分流/陈旧性比对/CAS/last-hash；tryAggregate：Hash 槽双链路聚合；handleAppeal：改判）。本次补齐三缺口：① 2→4 复审下架补审计留痕（@AuditLog 切面 AOP 不覆盖 MQ 消费端私有调用，改同格式 JSON 结构化日志显式落痕，operator=system(mq)）；② 申诉改判补 4→2 两向（原仅 3→2，与 [8.1]/§2.3 状态机口径偏差；先 3→2 未生效再 4→2）+ 改判前 ReviewClient.getPunishStatus 封禁态回查 fail-fast（banned=true 拒改判，回查异常穿透交延迟重试）+ 4→2 成功后连带逐张 FileClient.unquarantine 恢复被隔离配图（失败留痕不阻断）；③ 申诉改判成功发 content.published 既有；陈旧性比对：appeal 事件契约无 contentHash 字段，条件性不适用（注记）。细目见 status.md 板块八 [8.1]

###### 8.2 封禁连带与图片复审

- [x] user.banned 连带下架（发 content.unpublished）；图片复审聚合判定收口 review 消费端（content 仅提供 article_image.idx_file_id 反查契约，不重复实现）——封禁连带：UserBannedEvent 契约+三向绑定+Consumer+handleUserBanned（批量 CAS 2→4+逐篇事件+三键失效+结构化审计留痕）；反查契约：ContentClient.listArticleIdsByFileId+Provider 实现（review 聚合重算随 3-5 联调接入）

------------

### 验证部分

#### content模块

##### V1 发布链路联调

- [ ] 发布 → review 送审 → result 回写全链路联调（依赖板块三/板块八，前置 litisle-ai chat 端点）

##### V2 可见性与缓存验证

- [ ] visibility 判定链、推荐流展示前回查验证；密码防爆破限频演练





-------------------------

## 暂不实现实现汇总

### 代码部分

#### content模块

------------

### 验证部分

#### content模块