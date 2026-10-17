

## 当前执行锚点

- **前置阻塞**：litisle-ai chat 端点（`POST /api/feign/ai/chat`）未实现——3-1 联调前必须落地（见 Temporarily_not_implemented.md 预留项）
- **推进状态**：板块五/六/七已全量落地并归档；板块三/四挂起项随 content/social 开发回接

> 维护纪律：后续新落地项直接写入归档文档对应板块，本页仅维护指针与锚点。
>
> 2026-10-17 追加：content 模块八板块已入 next_update.md，实现细案见下方「content 模块实现步骤」节；细案落地后随板块完成归档并从本页移除。

# content 模块实现步骤（对应 next_update.md 板块一~八）

> 通用纪律适用 next_update.md「代码部分」①~⑥；事件发送一律本地事务提交后 afterCommit；SQL 全走 #{}；所有 CAS 补 del_flag=0；CAS affected=0 一律静默跳过事件发送（不落库不发送，对齐 review publisher「落库失败不发」铁律，防免审直落并发双击/重复提交把重复事件打进 search/notification）。

## 板块一：模块基建与文章 CRUD

**落位**：litisle-content `domain/entity`、`mapper`、`service`、`controller`、`constant`、`exception`

- [x] [1.1] 执行 Sql-Document §二 8 表 DDL；生成 Article/ArticleImage/ArticleDraft/Book/BookChapter/ReadingProgress/Tag/ArticleTag 实体与 Mapper（MyBatis-Plus，del_flag 逻辑删除）。（2026-10-17 落地：8 实体+8 Mapper 已生成；yml 已补全局 logic-delete-field: delFlag，仅对含 delFlag 的 article/book/tag 三表生效；DDL 执行状态请在库中自行核对）
- [x] [1.1] 枚举与状态机：ArticleStatus/Visibility/Zone 用 api 共享枚举；新建 `ArticleStateMachine.canTransit(from,to)` 流转白名单校验——禁 2→1（改判只认申诉来源）、4-下架为终态。（2026-10-17 落地：枚举落位为 content 本地 enums（api 未含该组共享枚举，无跨模块复用方，维持本地）；ArticleStateMachine 已新建于 domain/statemachine，白名单含 4→2 唯一出口并约束仅申诉来源调用）
- [x] [1.3] 常量与错误码：ContentBizConstant（切片上限/推荐流长度）+ ARTICLE_* 错误码枚举（对齐 Content-Architecture §六）。（2026-10-17 落地：ContentBizConstant（FILE_BIZ_TYPE_ARTICLE_IMG/IMG_PLACEHOLDER_PATTERN/RENDERED_IMG_CLASS_PREFIX/UNPUBLISH_REASON_{DELETE,TAKEN_DOWN,TO_PRIVATE}，切片与推荐流长度已在 ContentLimitConstant 按 SSOT 不重复持有）+ common-core ErrorCode 3xxxx 段扩展 ARTICLE_PARAM_INVALID(30007)~ARTICLE_TAG_EXCEEDED(30014) 共 8 码）
- [x] [1.2] ArticleService CRUD：创建/编辑（status=0/3 全字段可编；status=2 方案B 仅元数据——白名单字段拦截正文/标题变更）/软删（必发 content.unpublished，afterCommit）；sanitize 三层铁律（存储型 XSS 防线——review 后台内容预览直吐库中 HTML，净化必须入库前收口，禁仅出库净化）：①标题/摘要 strip tags 纯文本落库（防 <img onerror> 藏标题）；②正文入库前 HTML 白名单 sanitize——白名单必须原样保留 `{img:file_id}` 占位符纯文本节点（防净化破坏板块一渲染链路）；③回显输出编码双保险（净化在 content 侧收口，审核只判定不净化，对齐设计文档 §九.1）。（2026-10-17 落地：ArticleServiceImpl 7 方法全量实现——createAndPublish（A0 分区权限/A1 图文联合判定/sanitize/contentHash 落库）、update（owner+ADMIN、方案B 传入即拒、转私密 afterCommit 发 unpublished）、submit（状态机 canTransit 预校验+CAS 3→1）、delete（软删+发 unpublished）、getDetail（visibility 四分支判定链+占位符渲染）、listPublic/listHot（status=2+visibility=1，大字段延迟加载）；ArticleHtmlSanitizer 落位 utils/（包名与 import 一致）；ContentEventProducer（unpublished 发送收口，fillBase 基座四字段）。遗留 TODO 均跨板块：sendSubmitted/sendPublished（板块三3.4/四4.2）、FOLLOWERS 关注回查（板块六）、last-hash 失效（板块八）、标签挂载（板块三）、ADMIN @AuditLog（见下条 Controller 项）；建议后续：update 标题分支补 TITLE_TOO_LONG 校验；编译验证已通过（2026-10-17 mvn -pl litisle-content -am compile + 启动成功））
- [x] [1.2] 插图链路：插图时经 FileClient 校验 fileId 归属本人且 bizType=ARTICLE_IMG（失败抛 ARTICLE_IMG_REFUSED）；article_image 落 position_order/align（对齐权威）；渲染时批量查表将 `{img:file_id}` 占位符替换为带对齐 class 的 `<img>`。（2026-10-17 落地：checkImageOwnership（fail-fast 双条件）+ saveArticleImages + renderImages（未知图保留占位符降级；URL 做 &/引号转义防属性注入；对齐权威=article_image.align））
- [x] [1.2/1.3] Controller + 全局异常处理 + @AuditLog（ADMIN 下架等治理动作留痕：操作人/时间/articleId/原因）；审计/操作日志的自由文本字段（下架原因、驳回理由透传等）落日志前剥 \r\n 防日志注入（对齐 review §七.1 UGC 落日志口径）。

**验收**：CRUD/状态机单测通过；越权插图被拒；status=2 改正文被 CAS 拦截。

## 板块二：草稿系统

**落位**：`service/DraftService`、`controller/DraftController`

- [x] [2.1] DraftSaveDTO 携带 baseVersion；保存流程：归属校验（draft/article 的 author_id 比对当前登录用户，防横向越权）→ 版本判定严格按序四分支（防重复请求误入冲突分支）：①相等且内容 hash 未变→幂等跳过（网络抖动重发同请求，不再递增）；②相等且内容有变→UPSERT+version+1；③baseVersion<draft_version→抛 ARTICLE_DRAFT_CONFLICT（后写不覆盖先写）；④baseVersion>draft_version→非法参数拒绝（客户端版本超前，理论不可达）。（2026-10-17 落地：DraftServiceImpl#save——locateDraft 定位+归属校验（articleId 传值→article.author_id 比对，非本人按不存在处理；null→复用本人最新未关联草稿）+ 严格按序四分支：cmp<0 抛 ARTICLE_DRAFT_CONFLICT、cmp>0 抛 ARTICLE_PARAM_INVALID、同版本 sameContent 全字段比对幂等跳过（visibility=null 沿用不构成变更）+ imagesUnchanged 图片比对、同版本变内容 UPSERT draft_version+1；单行 UPSERT 语义，DraftSaveVO.draftId 闭环）
- [x] [2.2] 快照保留：仅留最近 N 版（`litisle.content.draft.keep-versions` 默认 10），保存事务提交后（afterCommit）异步滚动清理最旧快照——清理不进主事务，防 longtext 删除拉长保存 RT；防 longtext 膨胀。（2026-10-17 落地：cleanupOldSnapshotsAfterCommit 注册 afterCommit（事务提交点后执行，不进主事务；无 @Async 基建不新引入线程池）+ dimensionWrapper 维度口径与 locateDraft 一致；主链路单行 UPSERT 正常仅 1 行，清理为并发/异常残留行防膨胀兑底；keep-versions 配置 litisle.content.draft.keep-versions:10 已入 application.yml）
- [x] [2.2] 草稿图片暂存：article_image.draft_id 关联；新草稿插图时 article_id 空置仅挂 draft_id（DDL article_id 已 nullable，发布归属切换时回填）；发布时归属切换 draft_id→article_id 并清空 draft_id；废弃草稿孤儿图片由 file 对账链路兑底（content 不直删）。（2026-10-17 落地：remountDraftImages——images 非 null 整体重挂（物理删旧+按序插新，article_image 无 del_flag）、article_id 空置仅挂 draft_id；checkImageOwnership 与 ArticleServiceImpl 同口径（本人+ARTICLE_IMG 双条件 fail-fast 抛 ARTICLE_IMG_REFUSED）；imagesUnchanged 排序后逐项比对防顺序抖动误判；遗留：发布归属切换 draft_id→article_id 属板块三 [3.1]、孤儿图片 file 对账兑底属 file 侧）

**验收**：并发保存乐观锁生效；版本冲突不覆盖先写；快照滚动清理正确。

## 板块三：发布与送审链路

**落位**：`service/ArticleService`（发布编排）、`mq/ContentEventProducer`；⚠️ 前置阻塞：litisle-ai chat 端点 + review 送审契约（见 Temporarily_not_implemented.md）。切片/聚合职责收口 review 消费端（对齐 Review-Architecture v1.2），content 不持有 ContentSlicer

- [x] [3.1] 发布编排同步段：A1 校验（trim 非空+去标签可见文本非空+标题≤100+正文/配图不同空）→ A2 免审直落判定（last-hash+content_hash 命中且未变且 del_flag=0 → CAS `status in (0,3)→2` 发 content.published，免文不免图）→ A3 未命中 CAS `status in (0,3)→1` 落库，afterCommit 发 content.submitted，接口即时返回「审核中」（驳回后修改重提 3→1 复用同链路，CAS 口径对齐设计文档 §4.1）；A1 前同步 DFA 闸门（DfaFacade 本地执行）：HIGH 命中拒 70001 且错误信息仅含错误码/泛化提示，禁回吐命中词——发布同步拒绝与 preview-check 是词库 oracle 的两个独立试探入口，必须同口径封堵（防绕过 preview-check 限频走发布接口无限试探）。——已落地（ArticleServiceImpl.createAndPublish 编排 A0→A3）：① A1 校验经 PublishTextPrecheck 契约门面（hasVisibleText+check，标题 trim 非空/≤100+去标签可见文本非空）+图文同空拒绝 CONTENT_EMPTY+插图归属校验；② sanitize 三层铁律后 DFA 同步闸门（DfaPublishGate SSOT 决策：HIGH 拦 70001 禁回吐命中词/MID 仅留痕），zone=2 作家分区跳 DFA 全量人工；③ A2 免审三分支 LastHashExemptChecker.decide（EXEMPT_PASS 直落 CAS 1→2 不发事件；REJECT_HINT 前置拒 REVIEW_REJECTED 事务回滚连带 insert 回滚省机审；SUBMIT 照常送审），免文不免图由 imgReReviewPassed 聚合指纹门控收口；④ A3 落库 status=1 + afterCommit 发单条 submitted（事件体不含正文）；⑤ 驳回重提 3→1 走 /submit 专口（owner+状态机预校验禁 2→1+CAS 3→1+afterCommit 发 submitted）。注：CAS 口径等价实现为「insert status=1 → CAS 1→2」（免审键需 articleId，须先落库再判定；新文章 0 与驳回 3 入口全覆盖，并发兜底不变）；content.published 事件随板块四 [4.2] 契约缺口接通（当前免审直落仅 DB CAS）；异步段 A4 为 review 消费端职责（板块八 result 回写收口，content 无异步消费段）。
- [x] [3.2] 送审收口（对齐 review v1.2，无 content 侧异步消费段）：content 仅发单条 content.submitted（afterCommit，事件体不含正文，review 经 ContentClient 契约回查正文）；切片（review llm.max-text-chars 阈值分片硬切，禁静默截断）与聚合判定均在 review 消费端落地，content 不重复实现。聚合结论与状态回写唯一收口在板块八 result 消费端（防双写竞态）。——已落地（含配图发布复审收口）：① content.submitted 单条 afterCommit（ContentEventProducer.sendSubmitted）；② 配图复审 image.submit（ContentEventProducer.sendImageSubmit，scene=ARTICLE_IMG_RE_REVIEW，contentHash=全部配图 sha256 字典序排序拼接后二次 SHA-256 聚合指纹，AggregatedImgHash 私有方法收口）；③ 免审连带校验 LastHashExemptChecker.imgReReviewPassed 门控（免文不免图，EXEMPT_PASS 仅在配图复审已 PASS 时可达，免审直落不发事件）；④ 新增 FileClient.batchGetSha256 契约端点（file 侧 FileModerationService 实现 + FeignProvider 委托，审核链路 fail-fast）。配图复审任务创建仍为 review 侧遗留项。
- [x] [3.3] 批量发布：单批 ≤publish.batch-max=20（超限抛 ARTICLE_BATCH_EXCEEDED）；逐篇独立事务+逐篇回执 `{articleId,success,errorCode}`；已发布篇目 CAS 不满足自动跳过（幂等）。批量改可见性同口径：逐篇 CAS + 每篇独立发 content.unpublished/published 事件（设计 §6.3）。——已落地：① ArticlePublishServiceImpl.publishBatch（TransactionTemplate 编程式事务逐篇独立边界，规避同类自调用 @Transactional 失效；IdWorker 生成 publish_batch_id 逐篇回填；归属校验+状态机预校验 0→1+contentHash 缺失 fail-fast；CAS 0→1 未命中幂等跳过；afterCommit 发 submitted）；② batchVisibility（逐篇 CAS where visibility=旧值；转公开限定 status=2 不重审（方案B 禁改正文，原审核结论仍有效）；转私密对齐单篇口径 afterCommit 发 unpublished；批量转 PASSWORD 缺密码入参该篇失败）；③ previewCheck（DfaScenes 归一仅文本三场景，SubmitTextAssembler 同源拼接，DfaFacade 同快照判定，仅回 {hit,risk} 禁回吐命中词）；④ verifyPassword（BCrypt 比对不回显，未设密码与密码错误统一拒绝不暴露配置状态；失败限频挂 Controller @RateLimiter）。注：批量发布走送审路径不做免审直落（宁多审不漏审）；content.published 事件随板块四 [4.2] 契约缺口接通，转公开当前仅 DB CAS。
- [x] [3.3] 分区路由：ZoneRouter 校验（zone=2 需 AUTHOR、zone=3 仅 ADMIN/运营、一篇仅一分区冲突拒绝）；zone=2 跳 DFA 全量人工。——已落地：ZoneRouter.checkPublishPermission 单点收口（domain/router，权限口径防多入口漂移），createAndPublish A0 与 update 分区变更两处内联校验统一替换；「一篇仅一分区」由 Article.zone 单值字段天然保证；zone=2 跳 DFA 全量人工已在发布链路 A1 后收口。
- [x] [3.4] 事件收口：ContentEventProducer 统一封装 submitted/published/unpublished；下架四路径（删除/转私密/复审驳回/封禁连带）统一收口必发 unpublished，同路径调 ContentUnpublishedInvalidator（litisle-api 门面）失效 last-hash + text/img-result 缓存键（review §2.5 红线，挂起项 4-4 回收）。——已落地：① 三事件封装收口（ContentEventProducer 基座四字段统一 fillBase，禁各自拼装）；② published 随板块四 [4.2] 补 ContentPublishedEvent 契约后接通；③ 下架路径三处接入 content 侧（软删/单篇转私密/批量转私密，均 afterCommit 发 unpublished + 事务内同步失效缓存），复审驳回/封禁连带随板块八 [8.1]/[8.2] 复用 invalidateArticle 一行接入；④ 缓存失效落地为 UnpublishedCacheCleaner（方案 A：last-hash 精确删复用 LastHashStore.remove + text/img-result 按 *_SCAN_PATTERN 通配版本段 SCAN 删，键拼 SSOT 收口 ModerationConstant 新增两常量；fail-safe 失败留痕不阻断主流程，残留键 TTL 兜底；配图 sha256 经 FileClient.batchGetSha256 回查，失败降级跳过图片键）；Temporarily_not_implemented.md 签名澄清项已闭环。
- [x] [3.2] 提供方端点补齐（Api-Architecture §4.6）：`GET /api/feign/content/get`（机审回查正文，review 消费端依赖，V1 联调前置）、`GET /api/feign/content/article-images`（配图复审反查）、`POST /api/feign/content/counters/update`（social 计数回写 view/like/comment_cnt，幂等绝对值 SET——并发覆盖由 social 聚合侧单点回写规避，content 不做增量累加）；均 fail-fast。——已落地：ContentFeignProviderController implements ContentClient（对齐 FileFeignProviderController 范例：仅日志留痕+委托 ContentInternalService+包装 Result，无鉴权注解，BizException 经全局处理器转 Result.fail 不触发降级工厂）；ContentInternalService/Impl 收口三端点业务（①回查：body=入库 sanitize 后 contentHtml 与发布侧 SubmitTextAssembler.assemble 同源保 review 重算 hash 与事件 contentHash 一致，title=剥标签标题，ownerId=authorId，评论 bizType=2 模块未落地抛 COMMENT_NOT_FOUND；②配图反查：position_order 升序=发布登记顺序，fileMetaId 置 null——review 单图支线经 FileClient.getFileInfo 自行解析、复审聚合仅依赖 fileId 取图重算 sha256；③计数回写：幂等绝对值 SET 仅非空字段进 SET 子句，rows=0 抛 ARTICLE_NOT_FOUND）；三方法均显式补 del_flag=0；契约补齐：ContentClient 新增 updateCounters 方法 + ArticleCounterUpdateDTO（social 聚合侧未落地，消费方随板块六接入）。

**验收**：发布 RT 不被审核绑架；submitted 事件体不含正文且仅单条；批量部分失败可逐篇重提。

## 板块四：可见性控制与安全

**落位**：`service/ArticleService`（判定链）、`controller`（限流注解）

- [ ] [4.1] visibility 判定链：详情/正文接口收口——1 放行；2 仅 owner（非本人按不存在处理，不暴露存在性）；3 回查 social 关注关系契约（fallbackFactory fail-fast，回查失败不静默放行；回查结果加短 TTL 缓存 ≤60s ±10% 抖动，防 social 抖动放大到内容读链路；取关后最长 TTL 窗口内仍判关注者属可接受弱一致性——follow.changed 事件联动留二期，见 Temporarily content 节）；4 元信息可见、正文需密码。
- [ ] [4.2] 密码访问：BCrypt 比对不回显；失败限频 attempt-limit=5/60s（对齐 auth 登录防爆破口径），超限抛 10005；access_password 不落草稿快照（article_draft 无此列，属发布参数随发布请求携带），防误从草稿继承取到 null。
- [ ] [4.2] 可见性变更：仅 owner；转非公开必发 content.unpublished；转公开（visibility 2/3/4→1，**status 不变**——数字与 status 字典同值易误读，禁与「4-下架终态」流转混淆）不重审——content_hash 校验内容未变+方案B 禁改正文，发 content.published；转公开不重建/不重写任何审核缓存（content_hash 未变原键仍有效，直落命中口径不变）；CAS 限定 `where status=2 and del_flag=0`（仅已发布态可改可见性，防 4-下架态仍变更 visibility 触发事件）。
- [ ] [4.3] 限流矩阵落地：@RateLimiter(USER) 全端点按 Content-Architecture §3.1 阈值注解（阈值外置 yml），顺带回收挂起项 3-7.2。
- [ ] [4.3] preview-check 端点（POST /api/content/preview-check）：复用 DfaFacade 同 scene 同词库版本，返回体 `{hit: boolean, risk: 0-无/1-MID/2-HIGH}`（值域钉死防实现自造），禁回吐命中词 + 本人草稿鉴权——回收 review 挂起项 4-5.1/4-5.2（判定契约 LastHashExemptChecker 已备）。

**验收**：visibility 四级判定单测；密码爆破被限频；转私密后 search/ai 事件可达。

## 板块五：书籍合集与阅读进度

**落位**：`service/BookService`、`controller/BookController`

- [ ] [5.1] 书籍/章节：book CRUD（status 0-连载/1-完结/2-下架）；章节挂载校验文章归属本人；chapter_no 写入依赖 uk(book_id,chapter_no) 冲突拒绝，序号即排序键。
- [ ] [5.2] 阅读进度：PUT 上报——chapter_id 归属 book_id 反查校验（拒绝伪造跨书章节，抛 BOOK_CHAPTER_MISMATCH）→ UPSERT（uk_user_book 冲突转 update）→ 仅前进不回退（reading_progress 无 chapter_no 列，update 前经 book_chapter 反查当前/新 chapter_id 的 chapter_no 比对，仅前进才 update，防多端乱序回退）；@RateLimiter(USER)=2 配合前端 ≥30s 节流。
- [ ] [5.2] 续读：GET 返回最后阅读章节。

**验收**：跨书伪造章节被拒；多端乱序上报不回退。

## 板块六：标签系统

**落位**：`service/TagService`、`controller`（/tags 端点）；⚠️ 前置依赖：DfaFacade 注入随 content 开发回接（review 挂起项 4-2.1，随板块四一并落地）

- [ ] [6.1] 挂载：POST /article/{id}/tags——owner 校验 → ≤tag.max-per-article=5（超限抛 ARTICLE_TAG_EXCEEDED）→ uk(article_id,tag_id) 冲突幂等跳过。
- [ ] [6.2] 标签池：GET /tags?keyword= 前缀搜索已有标签池（防碎片化）；确无命中才允许新建——新建文本过 DFA 闸门（同步，命中拒挂）；存量挂载零审核成本。
- [ ] [6.2] use_count 冗余计数异步刷新（与 view_cnt 同机制）；热门 TOP N 走 GET /tags/hot。
- [ ] [6.1] 方案B 关系：status=2 允许增删标签（不参与审核聚合、不影响 content_hash），新增仍过 DFA。

**验收**：DFA 命中标签拒挂；超限拒绝；封禁标签不再聚合展示。

## 板块七：推荐流

**落位**：`service/FeedService`、`controller/FeedController`

- [ ] [7.1] 收录：zone=3 仅 ADMIN/运营可写，接口不暴露给普通作者。
- [ ] [7.1] 缓存三防：互斥重建（单飞锁）防击穿+空结果短缓存防穿透+TTL ±10% 抖动防雪崩（cache-ttl-seconds=300/page-size=20 外置 yml）。
- [ ] [7.2] 展示前回查：渲染前回查 status=2 且 visibility=1，已下架/转私密即时剔除（防残留曝光）；标签聚合列表页复用同口径。

**验收**：缓存击穿演练通过；下架文章从推荐位即时消失。

## 板块八：审核回写与联动消费

**落位**：`mq/consumer`；依赖 review 板块三 3-5 result 消费端联调；消费端外部回查（user 封禁态/file 图片状态等）失败一律不 ACK 进延迟重试（对齐 Temporarily 通用纪律⑤，禁静默吞错）

- [ ] [8.1] result 回写：消费统一单队列 moderation.result（对齐 review v1.2：结果事件统一路由，消费端自筛 bizType=1 文章）；事件体含 scene，按 scene 分流——发布场景=文本链路、ARTICLE_IMG_RE_REVIEW=图片复审链路（复审任务与文本任务同 bizType=1，scene 是唯一区分键，防两路混流）。陈旧事件防线：CAS 前先比对事件体 contentHash 与 article.content_hash，不一致视为陈旧事件静默 ACK（防 MQ 延迟乱序——旧结论覆盖新提交、迟到 PASS 卡死驳回态；review result 事件体自带 contentHash 即为此用途）。CAS article.status：PASS→1→2；REJECT 按现态分流——status=1→3 首发驳回 / status=2→4 复审下架兑底（宁严勿松：防 PASS 先落 2 后另路 REJECT 到达时 CAS 1→3 不满足致违规内容滞留已发布态）；2→4 复审下架属下架四路径之一，必须同发 content.unpublished + 调 ContentUnpublishedInvalidator 失效 last-hash/text/img-result 缓存键 + @AuditLog 留痕（对齐板块三 3.5 与 8.4 收口口径）；CAS 条件 `where id=? and status=? and del_flag=0`，affected=0 静默 ACK 不重试；PASS 回写成功（affected=1）后 afterCommit 经 LastHashStore.write 写 last-hash 免审键（回收挂起项 4-3.1；SUSPICIOUS/REJECT 禁写；TTL=last-hash.ttl-hours(72)±10% 抖动）——漏写则板块三 A2 免审直落永 miss（免审机制断链），且禁把写入放 review 侧（review 不跨库直写业务态）。
- [ ] [8.1] 文章双链路聚合：文本 PASS 且（无配图 或 图片复审 PASS）才放行 1→2；任一 REJECT→整文驳回；两路并发到达先落驳回则 PASS 不得覆盖（CAS 白名单天然拦截）。聚合态先落暂存（Redis 短暂暂存或回查 review_task 两行现态），聚合判定后再 CAS article。空/纯图边界：正文去 HTML 标签后可见文本为空且含配图→文本链路 trivially PASS（仅看图片复审）；正文与配图同时为空→拒（发布侧 A1 已拦截，双保险）。
- [ ] [8.1] 申诉改判：消费 moderation.appeal.result（自筛 bizType=1）→ allow-list 对称恢复：article 3→2 / 4→2 仅此两向（对齐 review §2.6，挂起项 5-4.4 回收）；事件体若携带 contentHash 同样先做陈旧性比对（防迟到旧申诉改判覆盖新内容状态，与 8.1 同防线）；改判前回查 user 封禁态（fail-fast，回查失败不放行）——防申诉链路晚到翻转封禁连带结果（封禁后 4→2 恢复曝光属治理漏洞，review 裁决端源头校验为双保险）；CAS 口径同上，affected=0 静默 ACK；改判成功（affected=1）后 afterCommit 发 content.published（恢复 search/ai(RAG) 索引对称性——与下架必发 unpublished 对偶，RAG 门控回查 visibility 兜底不变）；若配图曾因复审驳回被 quarantine（file 侧 safe_flag=0），改判 4→2 成功后连带逐张调 FileClient.unquarantine 恢复（幂等，防申诉平反后配图永久隔离——file 侧不消费文章域申诉事件，不会自动恢复）。
- [ ] [8.2] 封禁连带：消费 user.banned → 该作者全部 status=2 文章批量置 4-下架 + 逐篇发 content.unpublished 并调 ContentUnpublishedInvalidator 失效审核缓存键 + @AuditLog 留痕。
- [ ] [8.2] 图片复审再聚合：review 按 article_image.idx_file_id 反查引用文章，重算 ARTICLE_IMG_RE_REVIEW 聚合结论（content 仅提供 idx_file_id 反查契约，不做聚合判定）。

**验收**：双链路聚合判定正确；改判仅认申诉来源（5-4）；封禁连带全量下架。

# 验证部分实现步骤（对应 next_update.md V1/V2）

## V1 发布链路联调

- [ ] [V1] 前置：litisle-ai chat 端点落地、review 送审契约就绪。
- [ ] [V1] 全链路：发布→content.submitted→review 消费端切片送审→review 判定→moderation.result 回写→状态流转→作者通知；分别验证 PASS/REJECT/SUSPICIOUS 三分支。
- [ ] [V1] 免审直落：同内容二次发布验证 last-hash 命中 0→2 直落。

## V2 可见性与缓存验证

- [ ] [V2] visibility 四级逐一验证（含 visibility=3 关注关系回查、visibility=4 密码限频）。
- [ ] [V2] 推荐流展示前回查：下架/转私密后推荐位与标签聚合页即时剔除。
- [ ] [V2] 缓存三防演练：并发重建/空值穿透/TTL 抖动观察。


