# content 模块实现记录（归档）

> **归档日期**：2026-10-20（板块一~八）/ 2026-10-23（板块九）
> **来源**：迁移自 docs/next/next_update.md（板块条目摘要）与 docs/next/status.md（实现细案），条目原样搬运不压缩，含口径修正与注意事项；板块九迁自 docs/temp/temp.md（临时任务清单，阶段1~9 全量）
> **互链**：活跃待办/验证清单见 [next_update.md](../next/next_update.md)；验证部分实现步骤见 [status.md](../next/status.md)；挂起项见 [Temporarily_not_implemented.md](../next/Temporarily_not_implemented.md)

> 通用纪律（适用板块一~八）：事件发送一律本地事务提交后 afterCommit；SQL 全走 #{}；所有 CAS 补 del_flag=0；CAS affected=0 一律静默跳过事件发送（不落库不发送，对齐 review publisher「落库失败不发」铁律，防免审直落并发双击/重复提交把重复事件打进 search/notification）。

## 板块一：模块基建与文章 CRUD

**落位**：litisle-content `domain/entity`、`mapper`、`service`、`controller`、`constant`、`exception`

**任务完成概览**（迁自 next_update.md）：

- [x] 1.1 Sql-Document §二 8 表实体与 Mapper；article 状态机（0-草稿/1-待审/2-已发布/3-驳回/4-下架终态）流转与 CAS 铁律（禁 2→1）；方案B（status=2 禁改正文/标题）
  - ✅ 2026-10-17：实体/Mapper、del_flag 逻辑删除配置、ArticleStateMachine（canTransit 白名单，禁 2→1/4 终态）落地；CAS 铁律与方案B 已随 [1.2]/[3.1] 后续落地（方案B 仅元数据禁改正文/标题；CAS 口径等价实现为「insert 1 → CAS 1→2」，见 LastHashExemptChecker），本条完成归档
- [x] 1.2 创建/编辑/软删；插图 fileId 归属校验（本人 + bizType=ARTICLE_IMG）；article_image 对齐权威 + `{img:file_id}` 占位符渲染链路
  - ✅ 2026-10-17：ArticleServiceImpl 7 方法全量实现 + ArticleHtmlSanitizer（utils/）+ ContentEventProducer + 插图归属校验/渲染收口；跨板块遗留：sendSubmitted（已随三3.4 封装收口）/sendPublished（四4.2）、FOLLOWERS 回查（六）、last-hash 失效（已随三3.4 方案A 收口）、标签挂载（三）；编译验证已通过（2026-10-17）
- [x] 1.3 ContentBizConstant、ARTICLE_* 错误码（✅ 2026-10-17：30007~30014 共 8 码扩展入 common-core ErrorCode）；治理动作 @AuditLog 审计留痕随 [1.2/1.3] Controller 项落地

**实现细案**（迁自 status.md）：

- [x] [1.1] 执行 Sql-Document §二 8 表 DDL；生成 Article/ArticleImage/ArticleDraft/Book/BookChapter/ReadingProgress/Tag/ArticleTag 实体与 Mapper（MyBatis-Plus，del_flag 逻辑删除）。（2026-10-17 落地：8 实体+8 Mapper 已生成；yml 已补全局 logic-delete-field: delFlag，仅对含 delFlag 的 article/book/tag 三表生效；DDL 执行状态请在库中自行核对）
- [x] [1.1] 枚举与状态机：ArticleStatus/Visibility/Zone 用 api 共享枚举；新建 `ArticleStateMachine.canTransit(from,to)` 流转白名单校验——禁 2→1（改判只认申诉来源）、4-下架为终态。（2026-10-17 落地：枚举落位为 content 本地 enums（api 未含该组共享枚举，无跨模块复用方，维持本地）；ArticleStateMachine 已新建于 domain/statemachine，白名单含 4→2 唯一出口并约束仅申诉来源调用）
- [x] [1.3] 常量与错误码：ContentBizConstant（切片上限/推荐流长度）+ ARTICLE_* 错误码枚举（对齐 Content-Architecture §六）。（2026-10-17 落地：ContentBizConstant（FILE_BIZ_TYPE_ARTICLE_IMG/IMG_PLACEHOLDER_PATTERN/RENDERED_IMG_CLASS_PREFIX/UNPUBLISH_REASON_{DELETE,TAKEN_DOWN,TO_PRIVATE}，切片与推荐流长度已在 ContentLimitConstant 按 SSOT 不重复持有）+ common-core ErrorCode 3xxxx 段扩展 ARTICLE_PARAM_INVALID(30007)~ARTICLE_TAG_EXCEEDED(30014) 共 8 码）
- [x] [1.2] ArticleService CRUD：创建/编辑（status=0/3 全字段可编；status=2 方案B 仅元数据——白名单字段拦截正文/标题变更）/软删（必发 content.unpublished，afterCommit）；sanitize 三层铁律（存储型 XSS 防线——review 后台内容预览直吐库中 HTML，净化必须入库前收口，禁仅出库净化）：①标题/摘要 strip tags 纯文本落库（防 <img onerror> 藏标题）；②正文入库前 HTML 白名单 sanitize——白名单必须原样保留 `{img:file_id}` 占位符纯文本节点（防净化破坏板块一渲染链路）；③回显输出编码双保险（净化在 content 侧收口，审核只判定不净化，对齐设计文档 §九.1）。（2026-10-17 落地：ArticleServiceImpl 7 方法全量实现——createAndPublish（A0 分区权限/A1 图文联合判定/sanitize/contentHash 落库）、update（owner+ADMIN、方案B 传入即拒、转私密 afterCommit 发 unpublished）、submit（状态机 canTransit 预校验+CAS 3→1）、delete（软删+发 unpublished）、getDetail（visibility 四分支判定链+占位符渲染）、listPublic/listHot（status=2+visibility=1，大字段延迟加载）；ArticleHtmlSanitizer 落位 utils/（包名与 import 一致）；ContentEventProducer（unpublished 发送收口，fillBase 基座四字段）。遗留 TODO 均跨板块：sendSubmitted/sendPublished（板块三3.4/四4.2）、FOLLOWERS 关注回查（板块六）、last-hash 失效（板块八）、标签挂载（板块三）、ADMIN @AuditLog（见下条 Controller 项）；编译验证已通过（2026-10-17 mvn -pl litisle-content -am compile + 启动成功））
- [x] [1.2] 插图链路：插图时经 FileClient 校验 fileId 归属本人且 bizType=ARTICLE_IMG（失败抛 ARTICLE_IMG_REFUSED）；article_image 落 position_order/align（对齐权威）；渲染时批量查表将 `{img:file_id}` 占位符替换为带对齐 class 的 `<img>`。（2026-10-17 落地：checkImageOwnership（fail-fast 双条件）+ saveArticleImages + renderImages（未知图保留占位符降级；URL 做 &/引号转义防属性注入；对齐权威=article_image.align））
- [x] [1.2/1.3] Controller + 全局异常处理 + @AuditLog（ADMIN 下架等治理动作留痕：操作人/时间/articleId/原因）；审计/操作日志的自由文本字段（下架原因、驳回理由透传等）落日志前剥 \r\n 防日志注入（对齐 review §七.1 UGC 落日志口径）。

**验收**：CRUD/状态机单测通过；越权插图被拒；status=2 改正文被 CAS 拦截。

------------

## 板块二：草稿系统

**落位**：`service/DraftService`、`controller/DraftController`

**任务完成概览**（迁自 next_update.md）：

- [x] 2.1 baseVersion 乐观锁 + 同版本幂等；归属校验防横向越权
  - ✅ 2026-10-17：DraftServiceImpl#save 四分支严格按序判定（落后冲突/超前非法/同版本幂等/同版本递增）+ locateDraft 归属校验，细目见下方 [2.1]；编译验证已通过
- [x] 2.2 快照保留与图片暂存
  - ✅ 2026-10-17：afterCommit 滚动清理（keep-versions=10 配置化）+ 草稿图片 draft_id 暂存整体重挂（ARTICLE_IMG 归属校验同口径），细目见下方 [2.2]；跨板块遗留：发布归属切换 draft_id→article_id（已随板块三 [3.1] 落地）、孤儿图片 file 对账兑底（file 侧）

**实现细案**（迁自 status.md）：

- [x] [2.1] DraftSaveDTO 携带 baseVersion；保存流程：归属校验（draft/article 的 author_id 比对当前登录用户，防横向越权）→ 版本判定严格按序四分支（防重复请求误入冲突分支）：①相等且内容 hash 未变→幂等跳过（网络抖动重发同请求，不再递增）；②相等且内容有变→UPSERT+version+1；③baseVersion<draft_version→抛 ARTICLE_DRAFT_CONFLICT（后写不覆盖先写）；④baseVersion>draft_version→非法参数拒绝（客户端版本超前，理论不可达）。（2026-10-17 落地：DraftServiceImpl#save——locateDraft 定位+归属校验（articleId 传值→article.author_id 比对，非本人按不存在处理；null→复用本人最新未关联草稿）+ 严格按序四分支：cmp<0 抛 ARTICLE_DRAFT_CONFLICT、cmp>0 抛 ARTICLE_PARAM_INVALID、同版本 sameContent 全字段比对幂等跳过（visibility=null 沿用不构成变更）+ imagesUnchanged 图片比对、同版本变内容 UPSERT draft_version+1；单行 UPSERT 语义，DraftSaveVO.draftId 闭环）
- [x] [2.2] 快照保留：仅留最近 N 版（`litisle.content.draft.keep-versions` 默认 10），保存事务提交后（afterCommit）异步滚动清理最旧快照——清理不进主事务，防 longtext 删除拉长保存 RT；防 longtext 膨胀。（2026-10-17 落地：cleanupOldSnapshotsAfterCommit 注册 afterCommit（事务提交点后执行，不进主事务；无 @Async 基建不新引入线程池）+ dimensionWrapper 维度口径与 locateDraft 一致；主链路单行 UPSERT 正常仅 1 行，清理为并发/异常残留行防膨胀兑底；keep-versions 配置 litisle.content.draft.keep-versions:10 已入 application.yml）
- [x] [2.2] 草稿图片暂存：article_image.draft_id 关联；新草稿插图时 article_id 空置仅挂 draft_id（DDL article_id 已 nullable，发布归属切换时回填）；发布时归属切换 draft_id→article_id 并清空 draft_id；废弃草稿孤儿图片由 file 对账链路兑底（content 不直删）。（2026-10-17 落地：remountDraftImages——images 非 null 整体重挂（物理删旧+按序插新，article_image 无 del_flag）、article_id 空置仅挂 draft_id；checkImageOwnership 与 ArticleServiceImpl 同口径（本人+ARTICLE_IMG 双条件 fail-fast 抛 ARTICLE_IMG_REFUSED）；imagesUnchanged 排序后逐项比对防顺序抖动误判；遗留：发布归属切换 draft_id→article_id 属板块三 [3.1]、孤儿图片 file 对账兑底属 file 侧）

**验收**：并发保存乐观锁生效；版本冲突不覆盖先写；快照滚动清理正确。

------------

## 板块三：发布与送审链路

**落位**：`service/ArticleService`（发布编排）、`mq/ContentEventProducer`；切片/聚合职责收口 review 消费端（对齐 Review-Architecture v1.2），content 不持有 ContentSlicer；原前置阻塞：litisle-ai chat 端点 + review 送审契约（随 V1 联调验证）

**任务完成概览**（迁自 next_update.md）：

- [x] 3.1 发布编排——已落地：createAndPublish 编排 A0 分区权限（ZoneRouter）→ A1 校验（PublishTextPrecheck 契约门面+图文同空拒+插图归属）→ sanitize 三层铁律 → DFA 同步闸门（DfaPublishGate：HIGH 拦 70001 禁回吐命中词/MID 留痕，zone=2 跳 DFA）→ A2 免审三分支（LastHashExemptChecker.decide：EXEMPT_PASS 直落 CAS 1→2 不发事件、REJECT_HINT 前置拒、SUBMIT 照常送审；免文不免图 imgReReviewPassed 门控）→ A3 落库 status=1 + afterCommit 发单条 submitted；驳回重提 3→1 走 /submit 专口（状态机禁 2→1）。注：CAS 口径等价实现为「insert 1 → CAS 1→2」（免审键需 articleId）；content.published 随板块四 [4.2] 契约缺口接通；异步段 A4 为 review 消费端职责（板块八收口）。
- [x] 3.2 送审事件与配图复审——已落地：content.submitted afterCommit 单条发送（ContentEventProducer.sendSubmitted）；配图复审 image.submit（ContentEventProducer.sendImageSubmit，scene=ARTICLE_IMG_RE_REVIEW、contentHash=全部配图 sha256 字典序排序拼接后二次 SHA-256 聚合指纹，复用 SubmitTextAssembler.contentHash 口径）；免审图片连带校验经 LastHashExemptChecker.imgReReviewPassed 门控（免文不免图，EXEMPT_PASS 仅在配图复审已 PASS 时可达，不重复发事件）；新增 FileClient.batchGetSha256 契约端点（file 侧实现，审核链路 fail-fast）
- [x] 3.3 批量发布与分区——已落地：publishBatch（TransactionTemplate 逐篇独立事务+逐篇回执+publish_batch_id 回填，CAS 0→1 未命中幂等跳过）/batchVisibility（逐篇 CAS+转私密 unpublished 事件，转公开 status=2 不重审）/previewCheck（DfaFacade 同快照，仅回 {hit,risk}）/verifyPassword（BCrypt 不回显）四方法收口 ArticlePublishServiceImpl；ZoneRouter 分区权限单点收口（发布 A0+编辑分区变更复用）；ContentProperties.publish.batchMax 配置绑定（application.yml 默认 20，Nacos 可覆盖）。注：批量发布走送审路径不做免审直落
- [x] 3.4 事件生产——已落地（2026-10-17）：三事件封装收口（基座四字段统一 fillBase）；下架三路径接入 content 侧（软删/单篇转私密/批量转私密：afterCommit 发 unpublished + 事务内同步调 UnpublishedCacheCleaner 失效 last-hash/text/img-result 缓存键，方案 A SCAN 通配版本段，键拼 SSOT 新增 ModerationConstant 两 SCAN 常量，fail-safe 残留键 TTL 兑底）；复审驳回/封禁连带随板块八 [8.1]/[8.2] 复用接入；published 事件随板块四 [4.2] 契约接通

**实现细案**（迁自 status.md）：

- [x] [3.1] 发布编排同步段：A1 校验（trim 非空+去标签可见文本非空+标题≤100+正文/配图不同空）→ A2 免审直落判定（last-hash+content_hash 命中且未变且 del_flag=0 → CAS `status in (0,3)→2` 发 content.published，免文不免图）→ A3 未命中 CAS `status in (0,3)→1` 落库，afterCommit 发 content.submitted，接口即时返回「审核中」（驳回后修改重提 3→1 复用同链路，CAS 口径对齐设计文档 §4.1）；A1 前同步 DFA 闸门（DfaFacade 本地执行）：HIGH 命中拒 70001 且错误信息仅含错误码/泛化提示，禁回吐命中词——发布同步拒绝与 preview-check 是词库 oracle 的两个独立试探入口，必须同口径封堵（防绕过 preview-check 限频走发布接口无限试探）。——已落地（ArticleServiceImpl.createAndPublish 编排 A0→A3）：① A1 校验经 PublishTextPrecheck 契约门面（hasVisibleText+check，标题 trim 非空/≤100+去标签可见文本非空）+图文同空拒绝 CONTENT_EMPTY+插图归属校验；② sanitize 三层铁律后 DFA 同步闸门（DfaPublishGate SSOT 决策：HIGH 拦 70001 禁回吐命中词/MID 仅留痕），zone=2 作家分区跳 DFA 全量人工；③ A2 免审三分支 LastHashExemptChecker.decide（EXEMPT_PASS 直落 CAS 1→2 不发事件；REJECT_HINT 前置拒 REVIEW_REJECTED 事务回滚连带 insert 回滚省机审；SUBMIT 照常送审），免文不免图由 imgReReviewPassed 聚合指纹门控收口；④ A3 落库 status=1 + afterCommit 发单条 submitted（事件体不含正文）；⑤ 驳回重提 3→1 走 /submit 专口（owner+状态机预校验禁 2→1+CAS 3→1+afterCommit 发 submitted）。注：CAS 口径等价实现为「insert status=1 → CAS 1→2」（免审键需 articleId，须先落库再判定；新文章 0 与驳回 3 入口全覆盖，并发兑底不变）；content.published 事件随板块四 [4.2] 契约缺口接通；异步段 A4 为 review 消费端职责（板块八 result 回写收口）。
- [x] [3.2] 送审收口（对齐 review v1.2，无 content 侧异步消费段）：content 仅发单条 content.submitted（afterCommit，事件体不含正文，review 经 ContentClient 契约回查正文）；切片（review llm.max-text-chars 阈值分片硬切，禁静默截断）与聚合判定均在 review 消费端落地，content 不重复实现。聚合结论与状态回写唯一收口在板块八 result 消费端（防双写竞态）。——已落地（含配图发布复审收口）：① content.submitted 单条 afterCommit（ContentEventProducer.sendSubmitted）；② 配图复审 image.submit（ContentEventProducer.sendImageSubmit，scene=ARTICLE_IMG_RE_REVIEW，contentHash=全部配图 sha256 字典序排序拼接后二次 SHA-256 聚合指纹，AggregatedImgHash 私有方法收口）；③ 免审连带校验 LastHashExemptChecker.imgReReviewPassed 门控（免文不免图，EXEMPT_PASS 仅在配图复审已 PASS 时可达，免审直落不发事件）；④ 新增 FileClient.batchGetSha256 契约端点（file 侧 FileModerationService 实现 + FeignProvider 委托，审核链路 fail-fast）。配图复审任务创建仍为 review 侧遗留项。
- [x] [3.3] 批量发布：单批 ≤publish.batch-max=20（超限抛 ARTICLE_BATCH_EXCEEDED）；逐篇独立事务+逐篇回执 `{articleId,success,errorCode}`；已发布篇目 CAS 不满足自动跳过（幂等）。批量改可见性同口径：逐篇 CAS + 每篇独立发 content.unpublished/published 事件（设计 §6.3）。——已落地：① ArticlePublishServiceImpl.publishBatch（TransactionTemplate 编程式事务逐篇独立边界，规避同类自调用 @Transactional 失效；IdWorker 生成 publish_batch_id 逐篇回填；归属校验+状态机预校验 0→1+contentHash 缺失 fail-fast；CAS 0→1 未命中幂等跳过；afterCommit 发 submitted）；② batchVisibility（逐篇 CAS where visibility=旧值；转公开限定 status=2 不重审（方案B 禁改正文，原审核结论仍有效）；转私密对齐单篇口径 afterCommit 发 unpublished；批量转 PASSWORD 缺密码入参该篇失败）；③ previewCheck（DfaScenes 归一仅文本三场景，SubmitTextAssembler 同源拼接，DfaFacade 同快照判定，仅回 {hit,risk} 禁回吐命中词）；④ verifyPassword（BCrypt 比对不回显，未设密码与密码错误统一拒绝不暴露配置状态；失败限频挂 Controller @RateLimiter）。注：批量发布走送审路径不做免审直落（宁多审不漏审）；content.published 事件随板块四 [4.2] 契约缺口接通。
- [x] [3.3] 分区路由：ZoneRouter 校验（zone=2 需 AUTHOR、zone=3 仅 ADMIN/运营、一篇仅一分区冲突拒绝）；zone=2 跳 DFA 全量人工。——已落地：ZoneRouter.checkPublishPermission 单点收口（domain/router，权限口径防多入口漂移），createAndPublish A0 与 update 分区变更两处内联校验统一替换；「一篇仅一分区」由 Article.zone 单值字段天然保证；zone=2 跳 DFA 全量人工已在发布链路 A1 后收口。
- [x] [3.4] 事件收口：ContentEventProducer 统一封装 submitted/published/unpublished；下架四路径（删除/转私密/复审驳回/封禁连带）统一收口必发 unpublished，同路径调 ContentUnpublishedInvalidator（litisle-api 门面）失效 last-hash + text/img-result 缓存键（review §2.5 红线，挂起项 4-4 回收）。——已落地：① 三事件封装收口（ContentEventProducer 基座四字段统一 fillBase，禁各自拼装）；② published 随板块四 [4.2] 补 ContentPublishedEvent 契约后接通；③ 下架路径三处接入 content 侧（软删/单篇转私密/批量转私密，均 afterCommit 发 unpublished + 事务内同步失效缓存），复审驳回/封禁连带随板块八 [8.1]/[8.2] 复用 invalidateArticle 一行接入；④ 缓存失效落地为 UnpublishedCacheCleaner（方案 A：last-hash 精确删复用 LastHashStore.remove + text/img-result 按 *_SCAN_PATTERN 通配版本段 SCAN 删，键拼 SSOT 收口 ModerationConstant 新增两常量；fail-safe 失败留痕不阻断主流程，残留键 TTL 兜底；配图 sha256 经 FileClient.batchGetSha256 回查，失败降级跳过图片键）；Temporarily_not_implemented.md 签名澄清项已闭环。
- [x] [3.2] 提供方端点补齐（Api-Architecture §4.6）：`GET /api/feign/content/get`（机审回查正文，review 消费端依赖，V1 联调前置）、`GET /api/feign/content/article-images`（配图复审反查）、`POST /api/feign/content/counters/update`（social 计数回写 view/like/comment_cnt，幂等绝对值 SET——并发覆盖由 social 聚合侧单点回写规避，content 不做增量累加）；均 fail-fast。——已落地：ContentFeignProviderController implements ContentClient（对齐 FileFeignProviderController 范例：仅日志留痕+委托 ContentInternalService+包装 Result，无鉴权注解，BizException 经全局处理器转 Result.fail 不触发降级工厂）；ContentInternalService/Impl 收口三端点业务（①回查：body=入库 sanitize 后 contentHtml 与发布侧 SubmitTextAssembler.assemble 同源保 review 重算 hash 与事件 contentHash 一致，title=剥标签标题，ownerId=authorId，评论 bizType=2 模块未落地抛 COMMENT_NOT_FOUND；②配图反查：position_order 升序=发布登记顺序，fileMetaId 置 null——review 单图支线经 FileClient.getFileInfo 自行解析、复审聚合仅依赖 fileId 取图重算 sha256；③计数回写：幂等绝对值 SET 仅非空字段进 SET 子句，rows=0 抛 ARTICLE_NOT_FOUND）；三方法均显式补 del_flag=0；契约补齐：ContentClient 新增 updateCounters 方法 + ArticleCounterUpdateDTO（social 聚合侧未落地，消费方随板块六接入）。

**验收**：发布 RT 不被审核绑架；submitted 事件体不含正文且仅单条；批量部分失败可逐篇重提。

------------

## 板块四：可见性控制与安全

**落位**：`service/ArticleService`（判定链）、`controller`（限流注解）

**任务完成概览**（迁自 next_update.md，均 2026-10-17 落地）：

- [x] 4.1 visibility 判定链
  - ✅ 2026-10-17：FOLLOWERS 分支接通 FollowRelationCache（短 TTL 60s±10% 抖动 + 负缓存 + Redis 故障降级直查；social 回查 fail-fast 不静默放行，非关注者按不存在处理）；SocialClient 补 isFollowing 契约 + MessageClientFallbackFactory 对称降级（提供方端点待 social 实现，联调随 social 落地验证）；转公开不重审落地：update 单篇 visibility 2/3/4→1 且 status=2 走独立 CAS（where status=2）+ afterCommit 发 content.published（ContentPublishedEvent 契约补齐 + ContentEventProducer.sendPublished）；细目见下方 [4.1]
- [x] 4.2 密码访问与下架收口
  - ✅ 2026-10-17：新增端点 POST /article/{id}/verify-password（@RateLimiter 5/60s + LoginRetryLimiter 连 5 次失败锁 60s 双层防爆破，identifier=article-pwd:{userId} 隔离命名空间，超限抛 10005）；验密通过写短时效凭证标记 content:pwd-pass:{operatorId}:{articleId}（TTL 10min），getDetail PASSWORD 分支命中放行正文；access_password 不落草稿快照验证性确认（DraftSaveDTO/article_draft 均无密码字段，天然满足）；批量路径 batchVisibility 转公开分支补发 content.published（单篇转公开 [4.1] 已落地），转私密/删除/封禁连带/复审驳回四路径 unpublished 收口既有；细目见下方 [4.2]
- [x] 4.3 限流矩阵
  - ✅ 2026-10-17：§3.1 矩阵八端点全量核对——POST /article 与 /article/{id}/submit（10）、/draft/save（10）、/article/publish-batch（3）、/preview-check（10）、/article/{id}/verify-password（5，[4.2] 刚挂）、PUT /reading-progress（2）、GET /feed（60）均已挂 @RateLimiter(USER) 且 count 与 ContentLimitConstant 一致；本次补唯一缺口 GET /tags?keyword=（TAG_SEARCH_MAX_COUNT=30，矩阵外补充防爬）；阈值统一收口 ContentLimitConstant（@RateLimiter 注解参数需编译期常量，yml 外置需切面运行时配置支持，属 common-redis 增强项不在本清单）；3-7.2 挂起项可回收

**实现细案**（迁自 status.md）：

- [x] [4.1] visibility 判定链：详情/正文接口收口——1 放行；2 仅 owner（非本人按不存在处理，不暴露存在性）；3 回查 social 关注关系契约（fallbackFactory fail-fast，回查失败不静默放行；回查结果加短 TTL 缓存 ≤60s ±10% 抖动，防 social 抖动放大到内容读链路；取关后最长 TTL 窗口内仍判关注者属可接受弱一致性——follow.changed 事件联动留二期，见 Temporarily content 节）；4 元信息可见、正文需密码。——已落地（2026-10-17）：① SocialClient 补 isFollowing(ownerId, viewerId) 契约（GET /api/feign/follow/check，网关不路由；提供方端点待 social 实现）+ MessageClientFallbackFactory 对称降级 fail-fast（REMOTE_SERVICE_ERROR 穿透，禁静默放行）；② 新建 cache/FollowRelationCache：Redis 短 TTL 缓存（键 content:follow:relation:{ownerId}:{viewerId}，值 1/0 含负缓存防穿透，TTL 基准 60s±10% 抖动防雪崩），miss 回查 social，缓存读写失败降级直查不阻断判定链；ContentBizConstant 补两常量；③ getDetail FOLLOWERS 分支替换 TODO 降级：isFollowing=false 抛 ARTICLE_NOT_FOUND（不暴露存在性）；④ 转公开不重审（本板块 [4.2] 可见性变更的前半段，随 [4.1] 一并落地）：update 单篇 visibility 2/3/4→1 且 status=2 走独立 CAS（where status=2，并发下架/删除 fail-fast）+ afterCommit 发 content.published；ContentPublishedEvent 契约补齐（镜像 Unpublished 结构无 reason）+ ContentEventProducer.sendPublished 收口（EXCHANGE_CONTENT/ROUTING_CONTENT_PUBLISHED），不重建/不重写任何审核缓存。注：转公开事件消费方（search/ai/notification）随各宿主模块接入。
- [x] [4.2] 密码访问：BCrypt 比对不回显；失败限频 attempt-limit=5/60s（对齐 auth 登录防爆破口径），超限抛 10005；access_password 不落草稿快照（article_draft 无此列，属发布参数随发布请求携带），防误从草稿继承取到 null。——已落地（2026-10-17）：① 新增端点 POST /article/{id}/verify-password（ArticlePublishController，@SaCheckLogin + @RateLimiter 5/60s，常量 VERIFY_PASSWORD_MAX_COUNT）；② 双层防爆破：@RateLimiter 固定窗口防高频 + LoginRetryLimiter 失败计数（identifier=article-pwd:{userId} 前缀隔离命名空间，连 5 次锁 60s，锁定闸口前置抛 TOO_MANY_REQUESTS，成功 resetFailure）；③ 验密通过写短时效凭证标记 content:pwd-pass:{operatorId}:{articleId}（TTL 10min），getDetail PASSWORD 分支命中放行正文、未命中 needPassword=true——判定链仍收口 getDetail 零渲染复制；④ access_password 不落草稿快照验证性确认：DraftSaveDTO 无密码字段、article_draft 无列，天然满足零改动；⑤ 新建本地 DTO ArticlePasswordVerifyDTO（content 模块 domain/dto，非跨服务契约不放 litisle-api）。
- [x] [4.2] 可见性变更：仅 owner；转非公开必发 content.unpublished；转公开（visibility 2/3/4→1，**status 不变**——数字与 status 字典同值易误读，禁与「4-下架终态」流转混淆）不重审——content_hash 校验内容未变+方案B 禁改正文，发 content.published；转公开不重建/不重写任何审核缓存（content_hash 未变原键仍有效，直落命中口径不变）；CAS 限定 `where status=2 and del_flag=0`（仅已发布态可改可见性，防 4-下架态仍变更 visibility 触发事件）。——已落地（2026-10-17）：① 单篇转公开 [4.1] 已随落地（独立 CAS where status=2 + afterCommit content.published）；② 本次补齐批量路径 batchVisibility→doChangeVisibility 转公开分支：afterCommit 发 content.published（sendPublished 收口），与 toPrivate 分支（unpublished + invalidateArticle）并列；③ 转公开不重建/不重写任何审核缓存（原键仍有效）；④ 仅 owner 校验既有（ADMIN 不代改）。注：content.published 消费方（search/ai/notification）随各宿主模块接入。
- [x] [4.3] 限流矩阵落地：@RateLimiter(USER) 全端点按 Content-Architecture §3.1 阈值注解（阈值外置 yml），顺带回收挂起项 3-7.2。——已落地（2026-10-17）：§3.1 矩阵八端点全量核对——POST /article 与 /article/{id}/submit（PUBLISH_MAX_COUNT=10）、POST /draft/save（DRAFT_SAVE_MAX_COUNT=10）、POST /article/publish-batch（BATCH_PUBLISH_MAX_COUNT=3）、POST /preview-check（PREVIEW_CHECK_MAX_COUNT=10）、POST /article/{id}/verify-password（VERIFY_PASSWORD_MAX_COUNT=5，[4.2] 随端点挂载）、PUT /reading-progress（PROGRESS_MAX_COUNT=2）、GET /feed（FEED_MAX_COUNT=60）均已挂 @RateLimiter(KeyType.USER) 且 count 与 ContentLimitConstant 收口常量一致，窗口统一 60s；本次补唯一缺口 GET /tags?keyword=（TAG_SEARCH_MAX_COUNT=30，矩阵外补充防爬；/tags/hot 与 Book 域矩阵未列，不扩散）。阈值外置 yml 说明：@RateLimiter 注解参数须编译期常量，当前口径=ContentLimitConstant 集中收口（改常量即全矩阵生效）；yml 运行时可调需 common-redis 切面支持动态配置，属公共库增强项不在本清单（ContentLimitConstant 类注释已预留说明）。挂起项 3-7.2 可回收：content 侧限流范式已就绪，review 侧成本护栏（@RateLimiter+RedisGuard+LocalRateLimiter）随板块八消费端接入时按同范式落地。
- [x] [4.3] preview-check 端点（POST /api/content/preview-check）：复用 DfaFacade 同 scene 同词库版本，返回体 `{hit: boolean, risk: 0-无/1-MID/2-HIGH}`（值域钉死防实现自造），禁回吐命中词 + 本人草稿鉴权——回收 review 挂起项 4-5.1/4-5.2（判定契约 LastHashExemptChecker 已备）。——已落地（2026-10-17，随板块三预检开发实现，本条核对确认）：① 端点 ArticlePublishController POST /preview-check（@SaCheckLogin + @RateLimiter 10/60s）✅；② 复用同宿主 DfaFacade Bean（预检与发布闸门同源同快照，scene 归一化后 USERNAME 拒绝、图片场景 normalize=null 拒绝）✅；③ 返回体 {hit, risk}：hit=DfaHitResult.hit()、risk=DfaHitResult.maxLevel()，禁回吐命中词（拼接口径同 SubmitTextAssembler 防漂移）✅；④ 入参为纯文本（title/content/scene）不含草稿 ID，无横向越权面，登录鉴权由 @SaCheckLogin 承接；review 挂起项 4-5.1/4-5.2 可回收。

**验收**：visibility 四级判定单测；密码爆破被限频；转私密后 search/ai 事件可达。

------------

## 板块五：书籍合集与阅读进度

**落位**：`service/BookService`、`controller/BookController`

**任务完成概览**（迁自 next_update.md，均 2026-10-17 落地）：

- [x] 5.1 书籍/章节管理
  - ✅ 2026-10-17：BookServiceImpl 五方法全量落地（Controller/实体/DTO/VO 既有，服务层原为 TODO 壳）：① create 默认 status=0 连载中 + category 空落默认「小说」；② update owner 校验 + 非 null 字段更新；③ delete 软删 CAS（where del_flag=0，重复删幂等）+ 物理清挂载行（挂载关系无独立业务价值，文章本体不动）@Transactional；④ addChapter 四重校验：书籍归属 requireOwnedBook 复用 → 文章归属本人（挂他人文章 NO_PERMISSION，非已发布 ARTICLE_PARAM_INVALID，软删按不存在）→ uk 冲突预检 → 落挂载行（并发双挂 DB uk 兑底转 30015）；⑤ removeChapter 书籍归属 + 章节归属（chapter_id 不属 book_id 拒 BOOK_CHAPTER_MISMATCH，防伪造跨书章节）仅删挂载行不删文章；新增 ErrorCode BOOK_CHAPTER_NO_CONFLICT(30015)；细目见下方 [5.1]
- [x] 5.2 阅读进度
  - ✅ 2026-10-17：ReadingProgressServiceImpl 两方法全量落地（Controller/实体/Mapper/VO 既有，服务层原为 TODO 壳）：① report：chapter_id 归属 book_id 反查（章节行缺失或 bookId 不匹配拒 BOOK_CHAPTER_MISMATCH，伪造跨书章节被拒；书籍软删时挂载行随删，无需额外 book 查询）→ UPSERT：selectOne uk_user_book 无则 insert（并发首报 catch DuplicateKeyException 重读转 update，uk 兑底）→ 仅前进不回退：progress 无 chapter_no 列，经 book_chapter 反查双方序号比对，新序号 ≤ 当前序号静默忽略（多端乱序不回退），当前章节行被退章移除时容错放行；② getLastChapter：无进度返回 null（前端落第一章），进度指向章节已被退章移除时按 null 返回前端按最新章容错；③ 节流：Controller @RateLimiter(USER)=2/60s 既有（[4.3] 已核对），配合前端 30s 节流；细目见下方 [5.2]

**实现细案**（迁自 status.md）：

- [x] [5.1] 书籍/章节：book CRUD（status 0-连载/1-完结/2-下架）；章节挂载校验文章归属本人；chapter_no 写入依赖 uk(book_id,chapter_no) 冲突拒绝，序号即排序键。——已落地（2026-10-17）：BookServiceImpl 五方法全量实现（Controller/实体/DTO/VO/Mapper 既有，服务层原为 TODO 壳）：① create：authorId=登录人、status=SERIALING(0)、delFlag=0、category 空落默认「小说」，返回自增 ID；② update：requireOwnedBook 复用（非本人 NO_PERMISSION、软删按 BOOK_NOT_FOUND 不暴露存在性）+ 非 null 字段更新（BookSaveDTO 全字段可空）；③ delete：软删 CAS（LambdaUpdateWrapper where del_flag=0，并发重复删幂等跳过）+ 物理清 book_chapter 挂载行（挂载关系无独立业务价值，文章本体不动）@Transactional 包裹两写；④ addChapter 四重校验链：书籍归属 → 文章归属本人（挂他人文章 NO_PERMISSION；status≠2 已发布抛 ARTICLE_PARAM_INVALID；软删按 ARTICLE_NOT_FOUND）→ uk 冲突预检（selectCount book_id+chapter_no >0 抛 BOOK_CHAPTER_NO_CONFLICT 新码 30015）→ insert（并发双挂同序号 catch DuplicateKeyException 转 30015，DB uk 兑底）；⑤ removeChapter：书籍归属 + 章节归属（chapter_id 不属 book_id 拒 BOOK_CHAPTER_MISMATCH 30012，防伪造跨书章节）仅删挂载行不删文章。新增 ErrorCode BOOK_CHAPTER_NO_CONFLICT(30015,「章节序号已被占用」）。注：挂载仅校验文章已发布，书籍 status（连载/完结）不影响挂载（完结书补章合法，下架书查询侧不可见）；书籍详情/章节列表 GET 端点未在 §3.1 矩阵（阅读侧接口随 5.2/前端需求另议）。
- [x] [5.2] 阅读进度：PUT 上报——chapter_id 归属 book_id 反查校验（拒绝伪造跨书章节，抛 BOOK_CHAPTER_MISMATCH）→ UPSERT（uk_user_book 冲突转 update）→ 仅前进不回退（reading_progress 无 chapter_no 列，update 前经 book_chapter 反查当前/新 chapter_id 的 chapter_no 比对，仅前进才 update，防多端乱序回退）；@RateLimiter(USER)=2 配合前端 ≥30s 节流。——已落地（2026-10-17）：ReadingProgressServiceImpl#report 全量实现：① 归属反查：selectById(chapterId) 行缺失或 bookId 不匹配抛 BOOK_CHAPTER_MISMATCH(30012)（书籍软删时挂载行已随删，章节行缺失自然被拒，无需额外 book 查询）；② UPSERT：selectOne(user_id+book_id) 无则 insert，并发双端首报 catch DuplicateKeyException 重读转 update（uk_user_book 兑底），极端并发（冲突行恰被删）本次跳过下次兑底；③ 仅前进：经 book_chapter 反查当前/新章节序号比对，新序号 ≤ 当前序号静默忽略（含同章节重复上报幂等）；当前章节行被退章移除（selectById=null）无法比对时容错放行更新；④ 节流：Controller @RateLimiter(USER)=2/60s 既有（[4.3] 已核对）。
- [x] [5.2] 续读：GET 返回最后阅读章节。——已落地（2026-10-17）：ReadingProgressServiceImpl#getLastChapter：selectOne(uk_user_book) 无进度返回 null（前端落第一章）；进度指向章节已被退章移除时 selectById=null 按 null 返回，前端按最新章容错（对齐 BookChapterMapper 注释口径「章节缺失按最新章处理」）；Controller GET /reading-progress/{bookId} 既有。

**验收**：跨书伪造章节被拒；多端乱序上报不回退。

------------

## 板块六：标签系统

**落位**：`service/TagService`、`controller`（/tags 端点）；⚠️ 前置依赖：DfaFacade 注入随 content 开发回接（review 挂起项 4-2.1，随板块四一并落地）

**任务完成概览**（迁自 next_update.md，均 2026-10-17 落地）：

- [x] 6.1 标签挂载
  - ✅ 2026-10-17：TagServiceImpl#attachTags/detachTags 全量落地（Controller/实体/Mapper 既有，服务层原为 TODO 壳）：① attachTags：owner 校验（requireOwnedArticle 复用，软删按 ARTICLE_NOT_FOUND 不暴露存在性）→ 同名单请求内去重收敛 + 已有挂载数与新增合计 >5 抛 ARTICLE_TAG_EXCEEDED（新常量 TAG_MAX_PER_ARTICLE=5 收口）→ 仅池内命中可挂（in name + status=NORMAL + del_flag=0；封禁标签拒挂不给违规词引流）→ 已挂载过滤后逐条 insert（并发双挂 catch DuplicateKeyException 幂等跳过不报错不计数，清单口径与 addChapter 的冲突拒绝不同）→ 挂载成功才 use_count 原子 +1 @Transactional；② detachTags：owner 校验 → 先查实际存在的挂载行（幂等重复摘除不多扣）→ 删挂载行 → use_count 原子 -1（GREATEST 兑底不为负）；③ 方案B：requireOwnedArticle 不校验文章 status（status=2 允许增删标签，不影响 content_hash 不参与审核聚合）；池中无命中标签名的新建 DFA 闸门随 [6.2] 接入；细目见下方 [6.1]
- [x] 6.2 标签池与闸门
  - ✅ 2026-10-17：① searchByPrefix：空参空列表 → likeRight 前缀（%/_/\ 转义防通配符全池拖出）+ status=NORMAL + del_flag=0 + use_count 倒序 LIMIT 10（TAG_SEARCH_RESULT_LIMIT）；② attachTags 接 DFA 闸门：池内全量按名查询（不过滤状态）→ 正常态可挂/封禁已删静默跳过/池外名字同步 dfaFacade.check(DfaScenes.BIO, name)（BIO 短文本词库，标签/简介/代表作共用）→ 任一命中即抛 SENSITIVE_WORD_DETECTED(70001)（标签是聚合锚点宁严勿松，不分 HIGH/MID）→ 未命中落池（useCount=0，uk_name 并发冲突重查幂等）；③ 名称规范化：trim + 空串跳过 + >64 字拒（TAG_NAME_MAX_LENGTH 与词库词条同口径）；④ 契约缺口修复：DfaScenes.normalize 未映射 BIO（BIO 在 textScenes 全集但 check(BIO) 被 default 判非文本抛异常，直审场景不可达）→ 补 case BIO -> BIO 直映；⑤ 顺带修复 6.1 遗留编译问题：tagIdsOf 占位方法及三处调用全部内联替换
- [x] use_count 冗余计数异步刷新（与 view_cnt 同机制）；热门 TOP N 走 GET /tags/hot
  - ✅ 2026-10-17：① hotTags：Redis 短缓存（key=content:tag:hot:10，值=TagVO[] JSON，TTL 基准 300s ±10% 抖动防雪崩，缓存三防对齐 §七.6）→ miss 查库 status=NORMAL + del_flag=0 + use_count 倒序 LIMIT 10（TAG_HOT_TOP_N，走 idx_use_count）；② use_count 机制定位修正：挂载/摘除同事务原子增减（[6.1]，计数正确性来源，比异步回写更准），另建 TagCountReconcileJob 定时对账兜底（单条 UPDATE 自联仅修漂移行，默认 1h 间隔/5min 初延，litisle.content.tag.reconcile 可调）；CacheConstant 新增 CONTENT_TAG_HOT，ContentLimitConstant 新增 4 常量；细目见下方 [6.2]

**实现细案**（迁自 status.md）：

- [x] [6.1] 挂载：POST /article/{id}/tags——owner 校验 → ≤tag.max-per-article=5（超限抛 ARTICLE_TAG_EXCEEDED）→ uk(article_id,tag_id) 冲突幂等跳过。——已落地（2026-10-17）：TagServiceImpl#attachTags/detachTags 全量实现（服务层原为 TODO 壳）：① attachTags 链路：入参空集直接返回 → requireOwnedArticle 复用校验（软删按 ARTICLE_NOT_FOUND、非本人 NO_PERMISSION）→ 同名单请求内 HashSet 去重收敛 → 已有挂载 selectCount + 新增去重数 >5（新常量 TAG_MAX_PER_ARTICLE=5）抛 ARTICLE_TAG_EXCEEDED(30014) → 池内命中查询（in name + status=NORMAL + del_flag=0；封禁标签拒挂）→ 已挂载 tag_id 过滤 → 剩余逐条 insert + use_count 原子 +1（setSql "use_count = use_count + 1"，挂载成功才计数）@Transactional(rollbackFor)；② uk 冲突双层：预过滤已挂载（体验）+ 并发双挂 catch DuplicateKeyException 幂等跳过（不报错不重复计数，对齐清单「冲突幂等跳过」口径，区别于 addChapter 的冲突拒绝）；③ detachTags：先 selectList 实际存在的挂载行（幂等重复摘除不多扣）→ 删挂载行 → use_count 原子 -1（setSql "GREATEST(use_count - 1, 0)" 防对账空窗期透支）@Transactional；④ use_count 说明：本次为挂载/摘除时原子增减（计数正确性来源），「异步刷新」指 [6.2] 的定时对账校正机制，两机制互补不冲突。
- [x] [6.1] 方案B 关系：status=2 允许增删标签（不参与审核聚合、不影响 content_hash），新增仍过 DFA。——已落地（2026-10-17）：① requireOwnedArticle 仅校验归属与软删，不校验文章 status（status=2 已发布允许增删标签，status=0/3 同理开放，标签属元数据不在方案B 禁改清单）；② 不影响 content_hash：标签变更不触碰 article.content_hash，免审直落命中口径不变；不参与审核聚合：挂载不发任何 MQ 事件；③ 新增仍过 DFA 随 [6.2] 新建闸门接入（池中无命中的标签名当前静默跳过，仅挂池内已有标签，留 TODO 注记）。
- [x] [6.2] 标签池：GET /tags?keyword= 前缀搜索已有标签池（防碎片化）；确无命中才允许新建——新建文本过 DFA 闸门（同步，命中拒挂）；存量挂载零审核成本。——已落地（2026-10-17）：① searchByPrefix：空参空列表；likeRight 前缀搜索（escapeLike 转义 %/_/\\ 防通配符全池拖出）+ status=NORMAL + del_flag=0 + use_count 倒序 LIMIT TAG_SEARCH_RESULT_LIMIT(10)；② 新建闸门：attachTags 池内按名全量查询（不过滤状态，区分封禁/池外）：正常态未删→挂载；封禁/已删→静默跳过（不给违规词引流）；池外名字→dfaFacade.check(DfaScenes.BIO, name) 同步判定→任一命中抛 SENSITIVE_WORD_DETECTED(70001)（标签是聚合锚点，不分 HIGH/MID 宁严勿松）→未命中新建落池（useCount=0/status=NORMAL，uk_name 并发冲突重查幂等）后挂载；③ 名称规范化：trim 空串跳过、>TAG_NAME_MAX_LENGTH(64) 拋 ARTICLE_PARAM_INVALID（与词库词条长度同口径）；④ 契约缺口：DfaScenes.normalize 未映射 BIO（textScenes 含 BIO 但 check(BIO) 被 default 判非文本抛 IllegalArgumentException，直审场景不可达）→ 补 case BIO -> BIO（litisle-api 变更，需 review 侧同步确认无影响：normalize 仅影响 BIO 场景，review 现有调用不传 BIO）；⑤ 顺带修复 6.1 混合态遗留：tagIdsOf 占位方法及三处调用内联替换（原文件无法编译）。
- [x] [6.2] use_count 冗余计数异步刷新（与 view_cnt 同机制）；热门 TOP N 走 GET /tags/hot。——已落地（2026-10-17）：① hotTags：Redis 短缓存（CacheConstant.CONTENT_TAG_HOT + TOP N；值=TagVO[] JSON 序列化，避免依赖 RedisTemplate 泛型反序列化；TTL 基准 TAG_HOT_CACHE_TTL_SECONDS(300s) ±10% 抖动防雪崩）→ miss 查库 status=NORMAL + del_flag=0 + use_count 倒序 LIMIT TAG_HOT_TOP_N(10)（走 idx_use_count）；挂载/摘除/新建均不主动失效热门缓存（TTL 短缓存容忍毫秒级偏差，与 view_cnt 同为最终一致）；② use_count 机制定位：同事务原子增减为主（[6.1]，正确性来源）+ TagCountReconcileJob 定时对账兑底（新 job/ 包，@EnableScheduling；单条 UPDATE 自联仅修 use_count≠实际挂载数的漂移行；间隔默认 1h/初延 5min，litisle.content.tag.reconcile.{enabled,interval-ms,initial-delay-ms} 外置 yml 可调）。
- [x] [6.1] 方案B 关系：status=2 允许增删标签（不参与审核聚合、不影响 content_hash），新增仍过 DFA。——已落地（2026-10-17，详见上方 [6.1] 挂载条目②③）。

**验收**：DFA 命中标签拒挂；超限拒绝；封禁标签不再聚合展示。

------------

## 板块七：推荐流

**落位**：`service/FeedService`、`controller/FeedController`

**任务完成概览**（迁自 next_update.md，均 2026-10-17 落地）：

- [x] 7.1 zone=3 收录与缓存
  - ✅ 2026-10-17：① zone=3 收录校验核对确认已落地（[3.3] ZoneRouter 单点收口：checkPublishPermission RECOMMEND 分支，create 步骤 A0 + update 均调用，接口未暴露给普通作者）；② FeedServiceImpl.feed 全量落地（原 TODO 壳）：快路径读缓存 → miss 后单飞锁互斥重建（tryLock 2s + 双检回填 + finally unlock）→ 空结果短缓存 60s 防穿透（恶意翻页/冷页不持续打库）→ 正常结果 TTL 基准 300s ±10% 抖动防雪崩（litisle.content.feed.{cache-ttl-seconds,page-size} 外置 yml，ContentProperties 新增 Feed 嵌套类）；未抢到锁让渡 200ms 重读、仍 miss 兑底直查库不拒绝服务；③ 回源口径：zone=RECOMMEND + status=2 + visibility=PUBLIC + del_flag=0，createTime 倒序（article 无 publish_time 列，发布时间以 createTime 代理；ai 算法重排接入后替换数据源）；④ CacheConstant 新增 CONTENT_FEED；细目见下方 [7.1]
- [x] 7.2 展示前回查
  - ✅ 2026-10-17：核对确认已随 [7.1] 落地，零新增代码——FeedServiceImpl#recheckFilter：对缓存/回源所得条目批量回查权威态（in id + status=PUBLISHED + visibility=PUBLIC + del_flag=0，仅取 id 列走主键），不满足条目即时剔除；全链路三道防线叠加：① 缓存写入侧过滤（回源 SQL 同口径）② 每次读回查兑底（缓存 TTL 内下架/转私密内容防残留曝光）③ 下架四路径 content.unpublished（板块四收口）；标签聚合列表页端点未建（未在 §3.1 矩阵），复用同口径的约定迁移至阅读侧接口实现时落地；细目见下方 [7.2]

**实现细案**（迁自 status.md）：

- [x] [7.1] 收录：zone=3 仅 ADMIN/运营可写，接口不暴露给普通作者。——核对确认已落地（2026-10-17）：[3.3] ZoneRouter.checkPublishPermission 单点收口（domain/router/ZoneRouter.java，RECOMMEND 分支拒非 ADMIN/运营），create 步骤 A0 + update 均调用；本板块零新增代码，仅核对勾选。
- [x] [7.1] 缓存三防：互斥重建（单飞锁）防击穿+空结果短缓存防穿透+TTL ±10% 抖动防雪崩（cache-ttl-seconds=300/page-size=20 外置 yml）。——已落地（2026-10-17）：FeedServiceImpl.feed 全量实现（原 TODO 壳）：① 页参兑底：pageNo≥1、pageSize 上限收口 ContentProperties.Feed.pageSize(20)（防恶意大分页）；② 快路径：命中缓存直接进回查过滤（热路径不经过锁）；③ 防击穿：miss 后单飞锁 distributedLock.tryLock(lock:content:feed:page:x:y, 2s)——抢到锁双检缓存（防重复回源）→ loadFromDb → 回填 + finally unlock；未抢到锁让渡 200ms 重读缓存，仍 miss 兑底直查库（不拒绝服务，宁多一次查库）；④ 防穿透：空结果短缓存 60s（EMPTY_CACHE_TTL_SECONDS，远短于正常 TTL），恶意翻页/冷页不持续打库；⑤ 防雪崩：正常结果 TTL 基准 ContentProperties.Feed.cacheTtlSeconds(300s) ±10% 抖动；⑥ 回源口径：zone=RECOMMEND(3) + status=PUBLISHED + visibility=PUBLIC + del_flag=0，createTime 倒序（article 表无 publish_time 列，以 createTime 代理；一期口径，ai 侧算法重排接入后替换推荐位数据源）；⑦ CacheConstant 新增 CONTENT_FEED（content:feed:page:{pageNo}:{pageSize}，值=FeedItemVO[] JSON）。
- [x] [7.2] 展示前回查：渲染前回查 status=2 且 visibility=1，已下架/转私密即时剔除（防残留曝光）；标签聚合列表页复用同口径。——已落地（2026-10-17）：FeedServiceImpl#recheckFilter：对缓存/回源所得条目批量回查权威态（in id + status=PUBLISHED + visibility=PUBLIC + del_flag=0 仅取 id 列），不满足条目即时剔除（缓存 TTL 内下架/转私密内容防残留曝光）；全链路三道防线叠加：缓存写入侧过滤（回源 SQL 同口径）+ 每次读回查兑底 + 下架四路径 content.unpublished（板块四收口）；标签聚合列表页端点未建（按标签聚合文章的阅读侧接口未在矩阵，5.1 注记同口径），本复用约定迁移至阅读侧接口实现时落地。

**验收**：缓存击穿演练通过；下架文章从推荐位即时消失。

------------

## 板块八：审核回写与联动消费

**落位**：`mq/consumer`；依赖 review 板块三 3-5 result 消费端联调；消费端外部回查（user 封禁态/file 图片状态等）失败一律不 ACK 进延迟重试（对齐 Temporarily 通用纪律⑤，禁静默吞错）

**任务完成概览**（迁自 next_update.md，均 2026-10-17 落地）：

- [x] 8.1 result 回写与申诉改判
  - ✅ 2026-10-17：主体（板块三 3-5 期间实现）核对确认：ModerationResultConsumer / ModerationAppealResultConsumer（消费编排）+ ArticleReviewWriteBackServiceImpl（handleResult：bizType 自筛/scene 分流/陈旧性比对/CAS/last-hash；tryAggregate：Hash 槽双链路聚合；handleAppeal：改判）。本次补齐三缺口：① 2→4 复审下架补审计留痕（@AuditLog 切面 AOP 不覆盖 MQ 消费端私有调用，改同格式 JSON 结构化日志显式落痕，operator=system(mq)）；② 申诉改判补 4→2 两向（原仅 3→2，与 [8.1]/§2.3 状态机口径偏差；先 3→2 未生效再 4→2）+ 改判前 ReviewClient.getPunishStatus 封禁态回查 fail-fast（banned=true 拒改判，回查异常穿透交延迟重试）+ 4→2 成功后连带逐张 FileClient.unquarantine 恢复被隔离配图（失败留痕不阻断）；③ 申诉改判成功发 content.published 既有；陈旧性比对：appeal 事件契约无 contentHash 字段，条件性不适用（注记）。细目见下方 [8.1]
- [x] 8.2 封禁连带与图片复审反查
  - ✅ 2026-10-17：① 封禁连带已落地：UserBannedEvent 契约 + MqConstant 队列/路由常量 + ContentRabbitConfig 三向绑定（user 交换机/延迟/死信）+ UserBannedConsumer（MqAckHelper 口径同 8.1）+ handleUserBanned（双态候选「已发布+已下架」→ 批量 CAS 2→4 → 逐篇 unpublished+三键失效自愈补发）；审计留痕因 @AuditLog 切面不覆盖 MQ 消费线程改显式结构化 JSON 日志（同 8.1 口径）；user 侧发布端待 user 模块接入（契约先行，迁入 Temporarily）；② 图片复审反查已落地 content 侧：ContentClient.listArticleIdsByFileId（GET /api/feign/content/article-ids-by-file，fail-fast 降级）+ ContentInternalServiceImpl 实现（去重 + 过滤已删文章）+ Provider 端点；review 消费端聚合重算随 review 板块三 3-5 联调时接入（对齐「content 不重复实现聚合判定」边界）；细目见下方 [8.2]

**实现细案**（迁自 status.md）：

- [x] [8.1] result 回写：消费统一单队列 moderation.result（对齐 review v1.2：结果事件统一路由，消费端自筛 bizType=1 文章）；事件体含 scene，按 scene 分流——发布场景=文本链路、ARTICLE_IMG_RE_REVIEW=图片复审链路（复审任务与文本任务同 bizType=1，scene 是唯一区分键，防两路混流）。陈旧事件防线：CAS 前先比对事件体 contentHash 与 article.content_hash，不一致视为陈旧事件静默 ACK（防 MQ 延迟乱序——旧结论覆盖新提交、迟到 PASS 卡死驳回态；review result 事件体自带 contentHash 即为此用途）。CAS article.status：PASS→1→2；REJECT 按现态分流——status=1→3 首发驳回 / status=2→4 复审下架兑底（宁严勿松：防 PASS 先落 2 后另路 REJECT 到达时 CAS 1→3 不满足致违规内容滞留已发布态）；2→4 复审下架属下架四路径之一，必须同发 content.unpublished + 调 ContentUnpublishedInvalidator 失效 last-hash/text/img-result 缓存键 + @AuditLog 留痕（对齐板块三 3.5 与 8.4 收口口径）；CAS 条件 `where id=? and status=? and del_flag=0`，affected=0 静默 ACK 不重试；PASS 回写成功（affected=1）后 afterCommit 经 LastHashStore.write 写 last-hash 免审键（回收挂起项 4-3.1；SUSPICIOUS/REJECT 禁写；TTL=last-hash.ttl-hours(72)±10% 抖动）——漏写则板块三 A2 免审直落永 miss（免审机制断链），且禁把写入放 review 侧（review 不跨库直写业务态）。——核对确认已落地（板块三 3-5 期间实现，2026-10-17 补齐审计留痕）：ModerationResultConsumer（消费编排：traceId 恢复+executeWithRetry ACK 纪律+延迟重投耗尽入 DLQ）+ ArticleReviewWriteBackServiceImpl#handleResult（bizType 自筛/scene 分流/陈旧性比对/CAS 1→2、1→3、2→4/unpublished+invalidator 配套/last-hash 写入）。两处口径注记：① 审计留痕：@AuditLog 切面（@annotation AOP）不覆盖同类内部私有调用与无登录态 MQ 消费线程，2→4 落点改用与切面同格式 JSON 结构化日志显式落痕（auditTakeDown，operator=system(mq)，落点一致；重试自愈路径不重复落痕）；② last-hash 写入时机：跟随 result 事件到达写入（fail-safe 不阻断）而非 afterCommit（落地记录第 4 条：避免图片路触发聚合拿不到文本指纹的数据依赖），幂等重写安全。
- [x] [8.1] 文章双链路聚合：文本 PASS 且（无配图 或 图片复审 PASS）才放行 1→2；任一 REJECT→整文驳回；两路并发到达先落驳回则 PASS 不得覆盖（CAS 白名单天然拦截）。聚合态先落暂存（Redis 短暂暂存或回查 review_task 两行现态），聚合判定后再 CAS article。空/纯图边界：正文去 HTML 标签后可见文本为空且含配图→文本链路 trivially PASS（仅看图片复审）；正文与配图同时为空→拒（发布侧 A1 已拦截，双保险）。——核对确认已落地（板块三 3-5 期间实现）：tryAggregate（Redis Hash 槽 content:article:review-agg:{articleId}，field text/img→verdict TTL 2h；REJECT 到达即驳回不等另一路；文本路未终局不聚合；有无配图以 article_image 表现状为准防事件与落库竞态；CAS 白名单天然拦截 PASS 覆盖驳回）；空/纯图边界由发布侧 A2 段保证（正文可见文本为空且含配图时不发文本送审仅走配图复审），消费端无需重判（Temporarily 落地记录第 3 条）。
- [x] [8.1] 申诉改判：消费 moderation.appeal.result（自筛 bizType=1）→ allow-list 对称恢复：article 3→2 / 4→2 仅此两向（对齐 review §2.6，挂起项 5-4.4 回收）；事件体若携带 contentHash 同样先做陈旧性比对（防迟到旧申诉改判覆盖新内容状态，与 8.1 同防线）；改判前回查 user 封禁态（fail-fast，回查失败不放行）——防申诉链路晚到翻转封禁连带结果（封禁后 4→2 恢复曝光属治理漏洞，review 裁决端源头校验为双保险）；CAS 口径同上，affected=0 静默 ACK；改判成功（affected=1）后 afterCommit 发 content.published（恢复 search/ai(RAG) 索引对称性——与下架必发 unpublished 对偶，RAG 门控回查 visibility 兑底不变）；若配图曾因复审驳回被 quarantine（file 侧 safe_flag=0），改判 4→2 成功后连带逐张调 FileClient.unquarantine 恢复（幂等，防申诉平反后配图永久隔离——file 侧不消费文章域申诉事件，不会自动恢复）。——已落地（2026-10-17 补齐三缺口，原实现仅 3→2）：ModerationAppealResultConsumer + handleAppeal：① 封禁态回查 fail-fast：改判前经 ReviewClient.getPunishStatus(authorId)（处罚权威态，网关不路由）回查 banned=true 拒绝改判；回查异常穿透交 consumer 延迟重试（禁静默吞错）；② 两向 CAS：先 3→2 未生效再 4→2（4 为终态，申诉改判唯一业务恢复出口，ArticleStateMachine 白名单既有 4→2）；③ 4→2 成功后连带逐张 FileClient.unquarantine 恢复被隔离配图（单张失败 error 留痕不阻断改判主体，可人工补偿）；④ 成功后发 content.published（对偶对称性）；⑤ 陈旧性比对：ModerationAppealResultEvent 契约无 contentHash 字段（api-index §二），条件性要求当前不适用，契约演进后补充（注记）；⑥ 改判不通过维持原判仅删 last-hash 免审键（防 REJECT_HINT 误提示）。
- [x] [8.2] 封禁连带：消费 user.banned → 该作者全部 status=2 文章批量置 4-下架 + 逐篇发 content.unpublished 并调 ContentUnpublishedInvalidator 失效审核缓存键 + @AuditLog 留痕。——已落地：UserBannedEvent 契约 + MqConstant 队列/路由常量 + ContentRabbitConfig 三向绑定（user 交换机/延迟/死信）+ UserBannedConsumer（MqAckHelper 口径同 8.1）+ handleUserBanned（双态候选「已发布+已下架」→ 批量 CAS 2→4 → 逐篇 unpublished+三键失效自愈补发）；审计留痕因 @AuditLog 切面不覆盖 MQ 消费线程改显式结构化 JSON 日志（同 8.1 口径）。user 侧发布端待 user 模块接入（契约先行）。
- [x] [8.2] 图片复审再聚合：review 按 article_image.idx_file_id 反查引用文章，重算 ARTICLE_IMG_RE_REVIEW 聚合结论（content 仅提供 idx_file_id 反查契约，不做聚合判定）。——已落地 content 侧反查契约：ContentClient.listArticleIdsByFileId（GET /api/feign/content/article-ids-by-file，fail-fast 降级）+ ContentInternalServiceImpl 实现（去重 + 过滤已删文章）+ Provider 端点；review 消费端聚合重算随 review 板块三 3-5 联调时接入（对齐「content 不重复实现聚合判定」边界）。

**验收**：双链路聚合判定正确；改判仅认申诉来源（5-4）；封禁连带全量下架。

------------

## 板块九：临时清单（docs/temp/temp.md）——分类枚举化与 content 模块补链（2026-10-20 ~ 2026-10-23）

> **来源**：迁自 docs/temp/temp.md（阶段1~9 全量原样搬运，含落地注记与决策留档）
> **背景**：以「文章分类枚举化改造」立项（方案A：@EnumValue 中文入库，DB 零迁移），执行中滚动扩入图片引用闭环（阶段6）、软删连带盘点（阶段7）、计数/封面/展示缺口盘点（阶段8）、密码可见功能下线（阶段9）
> **挂起/预留项**：已按归档规则转移至 [Temporarily_not_implemented.md](../next/Temporarily_not_implemented.md) content 内容模块节；待用户手动执行项见本板块末尾汇总

**落位**：litisle-content `enums`、`domain/dto`、`domain/entity`、`domain/vo`、`service/impl`、`support`（ArticleImageRefSupport/ViewCounterSupport/ViewCountFlushJob）、`mapper`、`controller`、`constant`；`docker/init/mysql/content-schema.sql`；common-core ErrorCode（保留项）

**任务完成概览**（迁自 temp.md 阶段标题）：

- 阶段1 枚举补强 —— ✅ 2026-10-21（isValid 委托 resolve 复用）
- 阶段2 DTO 入参收紧 —— ✅ 2026-10-21（四 DTO 换枚举，@JsonValue 反序列化自动白名单，脏值 400）
- 阶段3 实体与 VO —— ✅ 2026-10-21（两实体+四 VO 换枚举，yml 全局 MybatisEnumTypeHandler 在位）
- 阶段4 Service/Controller 逻辑适配 —— ✅ 2026-10-21（listPublic 白名单校验、缺省 DEFAULT 口径、@RequestParam 保持 String）
- 阶段5 验证 —— ⏳ 未执行（编译+回归点，见待执行项汇总）
- 阶段6 图片引用闭环（方案C content 侧补链）—— ✅ 2026-10-21（含第二轮全量盘点追加发现；验证点未执行）
- 阶段7 软删连带范围盘点 —— ✅ 2026-10-20 排查 / 2026-10-21 复核（决策留档，点赞/评论预留不实施）
- 阶段8 第三轮缺口盘点（计数链路/封面图/展示层）—— ✅ 2026-10-23（浏览计数实装、封面并入引用管理、FeedItemVO 补 commentCnt）
- 阶段9 删除文章密码可见功能（visibility=4 整链下线）—— ✅ 2026-10-23 代码项（4 项手动操作待执行）

**实现细案**（迁自 temp.md，原样搬运）：

### 阶段1：枚举补强
- [x] `ArticleCategoryEnum` 增加静态解析方法 `resolve(String name)`：按中文 name 精确匹配返回枚举，未命中返回 null（供 listPublic 白名单校验复用）
- [x] `ArticleCategoryEnum` 增加静态 `isValid(String name)` 或直接复用 resolve!=null（二选一，避免 Controller/Service 各写一套遍历）

### 阶段2：DTO 入参收紧
- [x] `ArticlePublishDTO/ArticleUpdateDTO/DraftSaveDTO/ArticleQueryDTO`：`String category` → `ArticleCategoryEnum`（删 @Size，@JsonValue 反序列化自动白名单校验，脏值 400；QueryDTO 可空=全部语义保留）

### 阶段3：实体与 VO
- [x] `Article.java`/`ArticleDraft.java`：`String category` → `ArticleCategoryEnum`（MybatisEnumTypeHandler 已全局配置 application.yml:117，@EnumValue 映射 varchar 中文值）
- [x] `ArticleCardVO/ArticleDetailVO/DraftCardVO/DraftDetailVO`：同上（@JsonValue 序列化输出中文，前端展示无感）

### 阶段4：Service/Controller 逻辑适配
- [x] `ArticleServiceImpl`：`setCategory(dto.getCategory())` 类型随 DTO 自动适配；发布/存草稿 category 为 null 时落 `ArticleCategoryEnum.DEFAULT`（对齐 BookServiceImpl 缺省口径）
- [x] `ArticleServiceImpl#listPublic`：签名 `String category` 保留；非空白时先 `resolve(category)` 校验，未命中抛 `BizException(ErrorCode.ARTICLE_PARAM_INVALID)`（替代静默空结果）
- [x] `DraftServiceImpl`：草稿保存/更新链路类型适配 + 缺省值口径对齐（insert 忽略 null 列落 DB default '散文'（=DEFAULT 同值）；update 禁改 DEFAULT 防自动保存误覆写，注释已记录）
- [x] `ArticleController`：`@RequestParam String category` **保持 String 不动**（不走 Jackson，改枚举会绑定英文 name 破坏前端 URL 中文口径；白名单校验下沉 Service 层 resolve）
