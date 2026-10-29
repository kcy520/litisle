> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。
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

