# 文章分类枚举化改造清单（临时任务清单）

> 方案：方案A（@EnumValue 中文入库，DB 零迁移）+ 查询参数白名单校验（Controller 保持 String）
> 原则：文章链路先行，Book 链路待确认后跟进；每阶段完成后勾选 [x]
> 本文件为临时清单，全部完成后经确认可删除

> **归档标注（2026-10-23）**：本清单阶段1~9 已全量原样归档至 [docs/archive/content-implemention-records.md](../archive/content-implemention-records.md) 板块九；挂起/预留项已转移至 [docs/next/Temporarily_not_implemented.md](../next/Temporarily_not_implemented.md) content 内容模块节。
> 归档时点待执行项：①IDE 删除 ArticlePasswordVerifyDTO.java ②存量迁移 UPDATE article SET visibility=1 WHERE visibility=4（若现库有数据）③已有库 ALTER TABLE article DROP COLUMN access_password（docker 重建则自动）④编译与阶段5/6/9 回归点（见各阶段验证小节）。
> 本文件是否删除/保留待用户确认（遵循文件删除需确认纪律）。



## 阶段1：枚举补强（已完成 2026-10-21，isValid 委托 resolve 复用）
- [x] `ArticleCategoryEnum` 增加静态解析方法 `resolve(String name)`：
      按中文 name 精确匹配返回枚举，未命中返回 null（供 listPublic 白名单校验复用）
- [x] `ArticleCategoryEnum` 增加静态 `isValid(String name)` 或直接复用 resolve!=null
      （二选一，避免 Controller/Service 各写一套遍历）

## 阶段2：DTO 入参收紧（已完成 2026-10-21，四 DTO 已换枚举+import/@Size 清理到位）
- [x] `domain/dto/ArticlePublishDTO.java`：`String category` → `ArticleCategoryEnum`
      （删 @Size，@JsonValue 反序列化自动白名单校验，脏值 400）
- [x] `domain/dto/ArticleUpdateDTO.java`：同上
- [x] `domain/dto/DraftSaveDTO.java`：同上
- [x] `domain/dto/ArticleQueryDTO.java`：同上（可空=全部语义保留）

## 阶段3：实体与 VO（已完成 2026-10-21，两实体+四 VO 均已换枚举，yml:117 处理器在位）
- [x] `domain/entity/Article.java`：`String category` → `ArticleCategoryEnum`
      （MybatisEnumTypeHandler 已全局配置 application.yml:117，@EnumValue 映射 varchar 中文值）
- [x] `domain/entity/ArticleDraft.java`：同上
- [x] `domain/vo/ArticleCardVO.java` / `ArticleDetailVO.java` /
      `DraftCardVO.java` / `DraftDetailVO.java`：`String category` → 枚举
      （@JsonValue 序列化输出中文，前端展示无感）

## 阶段4：Service/Controller 逻辑适配（已完成 2026-10-21）
- [x] `ArticleServiceImpl` L184/L303：`setCategory(dto.getCategory())` 类型随 DTO 自动适配；
      发布/存草稿 category 为 null 时落 `ArticleCategoryEnum.DEFAULT`（对齐 BookServiceImpl 缺省口径）
- [x] `ArticleServiceImpl#listPublic`（L504-511）：签名 `String category` 保留；
      非空白时先 `ArticleCategoryEnum.resolve(category)` 校验，
      未命中抛 `BizException(ErrorCode.ARTICLE_PARAM_INVALID)`（替代静默空结果）
- [x] `DraftServiceImpl`：草稿保存/更新链路类型适配 + 缺省值口径对齐
      （insert 忽略 null 列落 DB default '散文'（=DEFAULT 同值）；update 禁改 DEFAULT 防自动保存误覆写，注释已记录）
- [x] `ArticleController` L124：`@RequestParam String category` **保持 String 不动**
      （不走 Jackson，改枚举会绑定英文 name 破坏前端 URL 中文口径；白名单校验下沉 Service 层 resolve）

## 阶段5：验证
- [ ] 编译：`mvn -pl litisle-content -am compile -DskipTests`（Win11 PowerShell 下同命令）
- [ ] 回归点：①发布（中文分类入库）②编辑改分类 ③草稿保存/恢复 ④列表 ?category=散文 过滤
      ⑤?category=不存在 → 400 而非静默空 ⑥详情/卡片 VO 回显中文
- [ ] 确认无其他模块跨服务引用 article.category（review/content-client 仅回查正文与 hash，不受影响）

## 待确认（阻塞 Book 链路，不阻塞本文清单）
- [ ] Book 分类集合是否与文章一致（DB default '小说'）？
      一致 → 复用 ArticleCategoryEnum；不一致 → 新建 BookCategoryEnum 后再改
      BookSaveDTO/Book/BookDetailVO/BookServiceImpl

## 阶段6：图片引用闭环（方案C content 侧补链，独立提交，与分类枚举改造分开）

> 背景：content 模块对 FileClient 只读不写（从未调 bindRef/unbindRef），引用登记两端断链：
> ①挂图未登记 file_ref → file 侧不知文章引用了图，「秒传共享防悬空」保护失效；
> ②编辑重挂直接 delete article_image，弃图不 unbindRef → 永久孤儿
> （对账任务只扫 status=0 与 del_flag=1，弃图 status=1/del_flag=0/无引用，两 Step 均扫不到，永不回收）

- [x] `ArticleServiceImpl#saveArticleImages`：落 article_image 同事务逐图
      `fileClient.bindRef(fileId, ARTICLE_IMAGE, articleId, ownerId)`
      （bind/unbind 契约降级 fail-fast：Feign 失败 → 本地事务回滚，无「挂图未登记」中间态）
      【落地】bind/unbind 统一收口至 `ArticleImageRefSupport#bindArticleRefs`（组件封装 FileClient）
- [x] `ArticleServiceImpl#update` 编辑重挂（L355-362）：删除前先查旧挂图 fileId 列表 →
      本地事务 delete+insert → **afterCommit** 对差集（旧列表−新列表）逐个
      `unbindRef(fileId, ARTICLE_IMAGE, articleId, ownerId)`（失败落告警日志不阻断：
      残留引用仅晚删/不删，保守方向不丢数据；file 侧「仍有引用跳过并告警」兑底）
      【落地】差集计算收口至 `ArticleImageRefSupport#diffOldMinusNew`，解绑走 `unbindRefsAfterCommit`
- [x] ⚠️ 四元组精确解绑铁律：unbindRef 必须传完整 (fileId,bizType,bizId,ownerId)，
      禁按 fileId 删——同一图可被秒传共享/多文引用，按 fileId 删会悬空他人引用（方案C 设计前提）
      【落地】`ArticleImageRefSupport#buildRef` 统一组装四元组，bind/unbind 共用同一口径
- [x] 文章软删链路：同口径对全部挂图 unbindRef（文章删了引用还在 → 文件永久泄漏）
      【落地】`ArticleServiceImpl#delete`：CAS 软删后查全量图行，afterCommit 逐图 unbind ARTICLE_IMG
- [x] 草稿链路：bizType 若区分 DRAFT_IMAGE 同口径 unbind；
      落地 `DraftServiceImpl` L347 TODO（删除草稿孤儿图片归属清理）
      【落地】新增 `StorageBizTypeEnum.DRAFT_IMAGE`（引用维度隔离，防 bind/unbind 维度错配）；
      草稿保存/重挂 bind DRAFT_IMAGE+bizId=draftId；`DraftServiceImpl#delete` 补齐实现：
      归属校验 → 删草稿行/图行 → afterCommit 全量 unbind
- [x] 修正 `DraftServiceImpl` L189 注释：「废弃孤儿图片由 file 对账链路兑底」
      描述的能力尚不存在（对账任务无扫无引用孤儿分支），改为描述实际机制
      （引用由 content 显式管理 + afterCommit unbind）
      【落地】remountDraftImages javadoc 与 save 步骤4 注释已同步修正（含发布归属切换新口径）

### 不采用方案（留档防止反复）
- content 直调物理删除：破坏方案C「解绑与删除分离」分层，绕过 7 天缓冲/隔离前缀
  保护（违规图证据保留），不可取
- file 对账任务加「扫无引用孤儿」分支：「无引用」≠「已弃用」——刚上传未挂文章、
  编辑器暂存的图会被误杀；引用生命周期必须由引用方显式管理

### 验证点
- [ ] 发布挂图 → file_ref 新增四元组行
- [ ] 编辑换图 → 弃图 unbind（file_ref 删行）+ 新图 bind
- [ ] 编辑时 Feign 失败 → 本地事务回滚，article_image 与 file_ref 无中间态
- [ ] 软删文章 → 全部挂图 unbind；对账任务 7 天后物理清理本体+缩略图
- [ ] 多文共享同一图：一文弃图后另一文仍可读（引用计数=1 未清本体）

### 第二轮全量盘点追加发现（发布/草稿/快照链路同类问题）
- [x] 【严重】草稿快照滚动清理泄漏挂图（`DraftServiceImpl#cleanupOldSnapshotsAfterCommit` L280-291）：
      只物理删 article_draft 旧快照行，未处理其 draft_id 维度挂载的 article_image 行
      → 快照行删后图行变无主：无人读 + 永不 unbindRef，文件引用永久泄漏；
      **每次自动保存推进版本号都在造新孤儿**。修复：清理旧快照时同步删其 draft_id 图行，
      差集（被删快照图 − 存留快照图）afterCommit unbindRef
      【落地】删前采集 removedIds/keepFileIds → 删快照行 → 逐快照删图行+差集 unbind；
      支撑组件增加 isSynchronizationActive 判定，afterCommit 上下文自动降级直接解绑
- [x] 【严重】发布链路「归属切换」从未实现：`DraftServiceImpl` L123/L189 注释声称
      「发布归属切换 draft_id→article_id 回填并清空 draft_id 属板块三 [3.1] 发布编排」，
      但实际发布走 `ArticleServiceImpl#saveArticleImages(dto.getImages(), articleId, null)`
      全新插入，草稿图行原样残留 → 同一文件 article_image 双行（draft 行 + article 行）；
      叠加上一条：旧快照被滚动清理后 draft 图行既无主也无引用管理。
      修复：发布链路补归属切换（草稿图行 article_id 回填 + draft_id 置空，或删 draft 图行），
      并同步修正两处失实注释
      【落地】采用「删 draft 图行」口径：ArticlePublishDTO 新增 draftId 可空锚点（@Positive），
      `ArticleServiceImpl#switchDraftImageOwnership`：草稿归属校验（article_draft.author_id）→
      差集 afterCommit unbind DRAFT_IMAGE → 删 draft_id 图行（防同文件双行）；两处注释已修正
- [x] 草稿重挂 `remountDraftImages`（L197-199）删旧图行不 unbindRef（与文章编辑重挂同病）；
      实施时需先定四元组口径：草稿阶段 bizId 用什么（draftId？articleId 可空？），
      与文章图 bizType 是否区分（DRAFT_IMAGE vs ARTICLE_IMG），防 bind/unbind 维度错配
      【落地】口径定案：DRAFT_IMAGE + bizId=draftId（决策 D1）；删前查旧图 fileId，
      重挂后同事务 bindDraftRefs + afterCommit 差集 unbindRefsAfterCommit
- [x] 【性能+重复代码】`checkImageOwnership` 双份实现（ArticleServiceImpl L576-585 /
      DraftServiceImpl L219-228）逐图循环 Feign getFileInfo，≤50 张 = 最多 50 次远程调用
      （N+1，对齐用户既有规范）。修复：file 侧仿 batchGetSha256/batchGetUrls 补
      批量归属查询接口（POST 批量 fileId → List<FileOwnerDTO>），content 两处抽公共方法复用
      【落地】file 侧新增 `batchGetOwners`（一次 in 查询+缺一 fail-fast，Set 包含判定防重复行漏检）
      + FileClient 契约 + fail-fast 降级；content 双份实现统一委托 `ArticleImageRefSupport#checkOwnership`
- [x] 发布后草稿快照保留策略一致性确认：发布不清草稿快照（当前行为），
      配合归属切换后需确认旧快照图行是否还有保留价值（编辑已发布文被方案B 拒，
      快照无法再用于重发 → 可考虑发布成功 afterCommit 顺带清理该维度旧快照+图行）
      【决策留档】采用「删 draft 图行、留快照行」口径：快照仅存文本价值（占位符图行已随
      发布切换清理）；不改动保留策略，后续若需完整快照回显另立项

## 阶段7：软删连带范围盘点（删除文章/方案B软删重发时，点赞/评论/图片是否被考虑）

### 现状结论（排查记录，2026-10-20；2026-10-21 阶段6 后复核更新）
- 软删实现：`ArticleServiceImpl#delete`：CAS del_flag=1 →
  **【阶段6 新增】查全量图行 afterCommit unbind ARTICLE_IMG 引用（补偿性 warn 留痕）** →
  unpublishedCacheCleaner 失效审核缓存 → afterCommit 发 content.unpublished（reason=1 删除）
- 事件消费方：仅 ai（向量库移除）+ search（预留）；**social 未消费**；
  ContentUnpublishedEvent.bizType 注释已预留评论扩展（1-文章；2-评论扩展位）
- 点赞/评论：**全仓未实现**（social 模块无 like/comment 代码；
  ArticleCounterUpdateDTO 为 social 单点回写预留契约，view/like/comment 计数冗余在 article 行）
  → 点赞/评论只需**预留思路**，不实施

### 图片（content 模块内，已有部分覆盖+补决策点）
- [x] 引用解绑：已在阶段6「文章软删链路：同口径对全部挂图 unbindRef」覆盖，此处不重复
      【落地确认】`ArticleServiceImpl#delete` 已含 afterCommit 全量 unbind（阶段6）
- [x] 决策点：article_image 行随软删的处理口径——**已确认：不动**（采纳建议）：
      软删=逻辑墓碑（无恢复接口但保留行做审计/法务取证，对齐方案C证据保留思想）；
      图行无人读无泄漏，泄漏风险全在 file_ref 侧，由阶段6 afterCommit unbind 收口；
      禁止软删时物理删图行/file 本体（破坏缓冲期与证据保留）

### 编辑方案B软删重发的互动数据悬空（关键边界，预留）
> 方案B：已发布文章改正文=软删旧文+重发新文（新 articleId）。
> 点赞/评论挂旧 articleId → 重发后**旧互动全部悬空丢失**
- [x] 预留决策点：接受丢失（正文已变，旧点赞/评论语义失效——**推荐**，互动不可迁移到新正文）
      vs social 侧迁移（旧 articleId → 新 articleId 批量改绑，仅标题/元数据级编辑才允许保留）
      【决策留档】默认采纳推荐口径（接受丢失）；实施时如产品要求元数据级编辑保留互动，再启用迁移方案
- [x] 预留兜底：方案B 重发事件若落地（如 content.replaced 事件），social 按需消费做隐藏/迁移；
      当前不实施，仅在 social 点赞/评论设计文档中记录该边界
      【留档】social 点赞/评论设计文档尚未创建（social 模块无 like/comment 代码）；
      该边界已在本清单留档，待 social 模块立项时随设计文档固化

### 点赞/评论（social 模块，预留不实施）
- [x] 预留：social 订阅 content.unpublished（reason=1 删除），文章维度点赞记录/评论树置
      「随文隐藏」状态（软删标记，不物理删——法务取证对齐），列表按隐藏状态过滤
      【预留留档】设计意图已固化于本条，实施随 social 模块立项
- [x] 预留：悬空兜底——点赞列表/评论入口反查 content 详情，ARTICLE_NOT_FOUND 时静默过滤
      （双保险：即使事件丢失/乱序，用户端也不感知悬空）
      【预留留档】双保险口径已固化于本条，实施随 social 模块立项
- [x] 预留：ContentUnpublishedEvent.bizType=2（评论）扩展位已有，评论软删时复用同一事件口径，
      其下点赞连带隐藏（评论维度生命周期独立于文章）
      【预留留档】扩展位已核实存在（ContentUnpublishedEvent L27/L58 注释），实施随 social 模块立项

### 计数冗余（无需处理，留档）
- article 行软删后 like_cnt/comment_cnt/view_cnt 冗余字段不再对外
  （详情可见性链路已拒绝 del_flag=1），无需清理；
  未来做「账号注销/作者清空」时再补批量口径

## 阶段8：第三轮缺口盘点（计数链路/封面图/展示层）

### ①【功能缺失】浏览计数链路完全未实现（view_cnt 永远为 0）
- 现状：ArticleMapper 注释声称「setSql 原子自增异步回刷」，但接口体为空（仅 BaseMapper），
  全仓无任何 INCR/累加调用；getDetail 不累加 → view_cnt 恒 0，
  热门文章按 view_cnt 倒序（ArticleController L132）排序形同虚设（又一处注释与实现脱节）
- [x] 【口径决策】浏览量=进入次数（PV）+ 去重窗口，**不采用纯 UV**：
      浏览量是热度信号而非独立访客统计，UV 压缩信号失真；匿名场景拿不到 userId
      （热门接口匿名可访问），UV 需设备指纹归一复杂度陗增；PV+窗口兼顾直观与防刷
      （业界主流：知乎/掘金/CSDN 同类变体）
      【落地】口径已实装：登录访客键 `u{userId}`，匿名键 `a{SHA-256(ip+UA)截断}`；
      去重窗口 TTL 默认 86400s，可配 `litisle.content.view.dedup-ttl-seconds`
- [x] 修复思路：详情命中后 Redis INCR（view:{articleId}）；
      防刷去重：SETNX view:dedup:{articleId}:{userId}，匿名用 ip+UA 哈希归一，
      TTL 24h（可配），键存在=窗口期内重复进入不计数；
      → 定时任务合并刷回 article 行（对齐建表注释「Redis 异步刷新」设计意图）；
      ArticleMapper 补 setSql 原子自增方法兑现注释（禁先查后写，并发丢更新）
      【落地】① ArticleMapper 新增 `incrViewCnt`（@Update `view_cnt = view_cnt + #{delta}`，
      带 del_flag=0 条件，rows=0 静默丢弃已删文章增量）；
      ② 新建 ViewCounterSupport：SETNX 去重抢位成功才 INCR pending 键，
      整链 fail-safe 吞异常（非核心链路不阻断读）；
      ③ ArticleController#getDetail 成功后 recordView
      （X-Forwarded-For 首段优先取客户端 IP，空退化 remoteAddr）；
      ④ 新建 ViewCountFlushJob：SCAN pending 键（禁 KEYS）→ GETDEL 原子取出 →
      incrViewCnt 合并刷回，异常 fail-safe 下轮重试；
      刷回间隔/初次延迟可配（默认 300s/60s，对齐 TagCountReconcileJob 范式，
      需启动类 @EnableScheduling 配套）

### ②【泄漏】封面图 coverFileId 完全游离在引用管理之外
- 现状：coverFileId 仅出现在 domain 层（DTO/VO/Entity），service 层零处理：
  发布/存草稿不校验归属（checkImageOwnership 只查正文 images → 可挂他人文件当封面）；
  阶段6 bindRef 只覆盖正文挂图（ARTICLE_IMAGE），封面从未登记引用；
  编辑换封面无差集解绑、软删不解绑 → 孤儿文件（比正文图更隐蔽：连 article_image 行都没有）
- [x] 修复：并入阶段6方案C——新增 COVER bizType（四元组 bizId=articleId/draftId），
      发布/编辑/软删/草稿清理与正文图同链路 bind/unbind；
      归属校验补 coverFileId（复用批量归属查询，见阶段6 N+1 改造）；
      Book.coverFileId 同病，随「待确认 Book 分类」一并确认
      【落地】① StorageBizTypeEnum 新增 COVER("cover", "封面图")；
      ② ArticleImageRefSupport 扩展 checkCoverOwnership（单文件复用 batchGetOwners 一次往返）
      与 bindSingleRef（null/空放行）；
      ③ ArticleServiceImpl：发布链路（校验+bind+bizId=articleId）、编辑链路封面差集
      （MP not_null：update 传 null=不修改封面，旧封面解绑按差集口径）、
      软删链路封面解绑、发布归属切换联动 unbindDraftCoverIfReplaced
      （草稿封面→文章封面交接，同封面沿用不解绑）；
      ④ DraftServiceImpl：save 链路（变更才校验+bind COVER+bizId=draftId+旧封面差集 unbind，
      MP not_null 传 null=沿用旧封面）、滚动清理被删快照封面差集解绑
      （封面不在任何存留快照上才算弃用）、delete 链路草稿封面解绑

### ③【展示缺口】FeedItemVO 缺 commentCnt
- Card/Detail VO 均有 viewCnt/likeCnt/commentCnt 三计数，FeedItemVO 只有前两个
- [x] 补 commentCnt 对齐三 VO 口径；若 feed 流设计上不展示评论数，则注释留档设计意图
      【落地】FeedItemVO 补 `private Long commentCnt;`（likeCnt 之后，对齐 Card/Detail 三计数口径）；
      填充值随 social 落地后 feed 聚合链路补充（当前 social 无 like/comment 表，预留字段）

### ④【预留确认】updateCounters 对已删文章抛 ARTICLE_NOT_FOUND
- updateCounters 带 del_flag=0 条件，rows=0 抛异常；social 未来回写软删文章计数会失败
- [x] social 落地时约定：收 ARTICLE_NOT_FOUND 视为成功吞掉（文章已删计数无意义），
      避免重试风暴；写入 social 设计文档预留
      【留档】本阶段不实施（social 模块尚未开发），仅作跨模块契约约定：
      social 侧 updateCounters 回写失败时，对 ErrorCode=ARTICLE_NOT_FOUND
      记 info 日志后视为成功（文章已软删，计数无意义），其余错误走重试/DLQ

## 阶段9：删除文章密码可见功能（visibility=4 PASSWORD 整链下线；代码项已完成 2026-10-23）

> 决策：建表脚本采用方案A（content-schema.sql 直接删 access_password 列，本地环境重建即生效）
> ⚠️ 原始指令笔误留档：用户指向 review-schema.sql，实际该列定义在 content-schema.sql（已核实）
> ⚠️ 手动项待执行：ArticlePasswordVerifyDTO.java 删除（已核实文件仍在）、存量数据迁移确认、已有库 ALTER 删列、编译验证

### 前置（先于代码删除，防越权窗口）
- [ ] 存量数据迁移：现有 visibility=4 的文章删枚举后会落入 getDetail default 分支按 PUBLIC 放行
      （越权风险），删代码前先执行
      `UPDATE article SET visibility = 1 WHERE visibility = 4;`
      （或确认现库无 visibility=4 数据后跳过）
      【待用户执行】代码删除已落地，若现库存在 visibility=4 数据须立即补执行此迁移

### 枚举与常量
- [x] `VisibilityEnum`：删除 `PASSWORD(4, "密码访问")` 枚举值 + 类注释同步
      【落地】枚举仅剩 PUBLIC/PRIVATE/FOLLOWERS，注释标注下线与存量迁移口径
- [x] `ContentBizConstant`：删除 5 个密码相关常量
      （PASSWORD_RETRY_IDENTIFIER_PREFIX / PASSWORD_RETRY_MAX_ATTEMPTS /
      PASSWORD_LOCK_SECONDS / PWD_PASS_MARK_KEY_PREFIX / PWD_PASS_MARK_TTL_SECONDS）
      【落地】「密码访问防爆破与验证凭证」常量区整段已删
- [x] `ContentLimitConstant`：删除 `VERIFY_PASSWORD_MAX_COUNT`
      【落地】已删（端点下线后无消费方）
- [x] `ErrorCode.ARTICLE_PASSWORD_ERROR`（common-core 公共契约）**保留不删**：
      公共枚举可能被其他模块/前端契约引用，删除影响面外溢（决策留档）
      【留档】未动，随公共契约后续治理再评估

### 验密链路（整条删除）
- [x] `ArticlePublishController`：删除 `POST /article/{id}/verify-password` 端点 + 接口表注释行
      【落地】端点/方法/DTO import/接口表行均已删
- [x] `ArticlePublishService`：删除 `verifyPassword` 接口声明 + 注释
      【落地】声明与顶部方法索引行均已删
- [x] `ArticlePublishServiceImpl`：删除 `verifyPassword` 实现；
      连带删除 `passwordCrypto`/`loginRetryLimiter` 注入（删后无其他使用点）；
      批量发布对 target=PASSWORD 的单篇失败逻辑改为拦截非法值
      【落地】方法体整删；PasswordCrypto/LoginRetryLimiter/StringRedisTemplate/Duration
      依赖全清；批量可见性 step4 PASSWORD 拦截块删（枚举已无此值），javadoc 同步
- [ ] 删除 `ArticlePasswordVerifyDTO.java` 整个文件（专用入参 DTO）
      【待用户手动执行】已核实文件仍存在（IDE 中删除），当前无引用不影响编译

### 发布/编辑/详情链路
- [x] `ArticlePublishDTO`：删除 `accessPassword` 字段 + 注释中「=4 时必填」描述
      【落地】已删；顺带修复阶段6遗留缺陷——draftId 字段重复定义
      （旧 @Size 版残留，@Size 不支持 Long 会抛 UnexpectedTypeException）
- [x] `ArticleUpdateDTO`：删除 `accessPassword` 字段
- [x] `ArticleServiceImpl`：① 发布校验（visibility=PASSWORD 且密码空白拦截）；
      ② 发布 `setAccessPassword(encode)`；③ 编辑链路密码分支；
      ④ getDetail `case PASSWORD` 分支；⑤ `needPassword` 局部变量与 VO 赋值；
      ⑥ `passwordCrypto` 注入（删后无使用点）；⑦ 类注释可见性判定链第 4 条
      【落地】⑦处全清（含 StringRedisTemplate 注入）；getDetail 收口为三级判定链
- [x] `ArticleDetailVO`：删除 `needPassword` 字段 + 注释
- [x] `Article` 实体：删除 `accessPassword` 字段 + 注释同步；
      `ArticleDraft` 仅注释提及，同步修正
      【落地】两实体注释均已标注阶段9 下线

### 建表脚本（方案A）
- [x] `content-schema.sql`：删除 `article.access_password` 列定义（L40）+
      visibility 列注释中「4-密码访问」描述（L39）+ 尾部字段说明（L175）；
      本地环境需重建（或手动 `ALTER TABLE article DROP COLUMN access_password;`）
      【落地】脚本已同步（新环境重建即生效）；【待用户执行】已有库手动 ALTER
      （docker mysql init 重建则自动）

### 验证
- [ ] 编译：`mvn compile -pl litisle-content -am -q`（Win11 PowerShell）
      【待执行】建议先删 ArticlePasswordVerifyDTO.java 后编译
- [ ] 回归点：①发布（visibility 仅 1/2/3 可选，传 4 → 400）②编辑改可见性 ③详情三档可见性判定
      （公开/仅自己/仅关注者）④全仓 grep PASSWORD 无 content 侧残留引用
      ⑤ verify-password 端点 404

## 阶段10：content 模块整体业务逻辑审查发现（2026-10-25，待修复确认）

> 全模块审查（发布编排/审核回写/三个 MQ 消费者/计数链路/图片引用/草稿/事件/Feed/
> ContentInternal/Book/Tag/ReadingProgress）后确认的 2 个重大 bug + 2 个中等问题；
> 其余链路（软删红线/submit 重提/ACK 纪律/缓存失效/回查同源/进度 UPSERT）确认无问题

### 重大
- [x] 【严重·事件漏发】免审直落（EXEMPT_PASS）不发 content.published：
      `ArticleServiceImpl` createAndPublish 的 EXEMPT_PASS 分支 CAS 1→2 后直接 return，
      不发事件（注释写「随板块四 [4.2] 补契约后接通」，但该契约已存在）——
      对照：编辑转公开分支调 publishPublishedAfterCommit、回写 finishPass 发 sendPublished、
      申诉改判 handleAppeal 发 sendPublished，免审直落是唯一漏发路径且无日志痕迹；
      后果：免审文章 DB 已发布态但 search 索引/ai 向量库/notification 全收不到，永远不进搜索与 RAG；
      附带：该分支 CAS 结果 rows 被忽略（并发 CAS 失败仍返回成功）；
      修复：rows>0 时补调 publishPublishedAfterCommit(article) 再 return（注意勿与 review 回写重复发送）
      【落地】CAS 结果改判：rows=0 抛 ARTICLE_PARAM_INVALID；rows>0 后 publishPublishedAfterCommit(article)
      （免审直落不发 submitted → review 链路不会处理该文，与 finishPass 无重复发送风险；注释同步修正）
- [x] 【严重·并发丢更新】attachTags 的 use_count 非原子自增：
      `TagServiceImpl#attachTags` 用 `set(Tag::getUseCount, tag.getUseCount() + 1)` 绝对值 SET，
      useCount 来自 Java 快照（existMap 早前批量查询/新建实体 0），
      并发给不同文章挂同一标签时双方基于同一旧值计算 → +1 丢失，热门标签排行持续偏差；
      同文件 detachTags 已用正确口径 `setSql("use_count = GREATEST(use_count - 1, 0)")`，
      两处口径不一致；修复：attach 侧改 `setSql("use_count = use_count + 1")`
      【落地】已改 SQL 原子自增（setSql），与 detachTags 的 GREATEST 口径同源；注释同步说明修复原因

### 中等
- [x] 【注释与实现不符】ViewCountFlushJob 类注释声称「失败侧补偿回写（INCR 回退）」，
      但 flush() 实现中 GETDEL 后 incrViewCnt 失败/rows=0 直接 continue，无补偿代码；
      决策二选一：补实现（GETDEL 前先 GET、失败 SET 回写，有竞态窗口成本高）
      或改注释如实说明「增量丢弃，接受损失」（浏览数丢一点可接受，推荐后者）
      【决策 2026-10-26】选「改注释如实说明」：浏览计数是非核心 fail-safe 链路，设计意图即
      「丢一点可接受」；补 GET/SET 回写在 GETDEL 与 SET 之间存在竞态窗口（旧值覆盖窗口内新增量），
      复杂度高收益低；且注释必须与实现一致（对齐阶段6 L189 注释修正口径）
      【落地】类注释已如实写明「无补偿回写（阶段10 决策：接受丢弃）」+ 竞态窗口原因
- [x] 【跨服务契约风险】计数回写双写冲突：ContentInternalServiceImpl#updateCounters
      允许 social 回写 viewCnt 绝对值，而 content 自身 ViewCountFlushJob 对 view_cnt
      做 SQL 原子自增——若 social 真传 viewCnt 两者并发互相覆盖；
      约定 social 契约：viewCnt 永远传 null（PV 归属 content 内部闭环），仅回写 like/comment；
      代码可加注释留档该契约
      【决策 2026-10-26】采纳约定口径：当前 social 未开发，先在
      ContentInternalServiceImpl#updateCounters 注释留档契约，social 立项时随设计文档固化
      【落地】updateCounters javadoc/方法体注释已有「viewCnt 禁用传 null，仅回写 like/comment」契约

## 阶段11：草稿域单行化与 MP 规范化收尾（2026-10-26，修复已落地，编译待验证）

> 背景：草稿模型定案为「多草稿并存 × 每个草稿单行 UPSERT 只存最新版」（draft_version 仅作乐观锁），
> 滚动清理旧快照链路已整体删除；配图占位符渲染抽共享组件 ArticleImageRenderSupport；
> service/impl 全部 .last("LIMIT..") 已收口为 Page.of 标准写法。
> 本阶段为本轮改动后的收尾项与决策项。



### P1 作废配置与死代码（已完成 2026-10-26）
- [x] application.yml 的 `litisle.content.draft.keep-versions: 10`：
      【落地】注释改为【已作废】标注（单行 UPSERT 模型定案，代码不再读取；按 yml 纪律保留备查）
- [x] ContentBizConstant.SQL_LIMIT_1 / SQL_LIMIT_PREFIX：
      【落地】已删（零引用死常量，防 .last 拼 SQL 回潮）
- [x] ArticleServiceImpl 残留 import 清理：
      【落地】Matcher 已删（无其他使用点）；Map/Collections/fileClient 核实仍有使用点，保留

### P2 决策项
- [x] DraftController.java 类头孤立注释「修改草稿」：
      【落地】已删（实际编辑闭环=详情+save，注释失实）

### P3 可选项（不阻塞）

- [x] 错误码拆分：ARTICLE_IMG_REFUSED 混装「文件不存在」与「引用他人文件」两种失败
      【决策留档】暂缓拆分：fileId 唯一来源是上传接口，「不存在」仅在文件被物理清理/
      ID 错误的边缘场景出现，前端对保存失败统一提示即可；拆分需 file 侧契约联动，
      收益低，待前端实际反馈再立项
- [x] article_draft 表注释同步：
      【落地】content-schema.sql 三处（draft_version 列注释/表注释/尾部说明）已同步
      单行 UPSERT 口径；已有库注释不自动生效，重建容器或手动 ALTER（仅注释，不急）

## 阶段12：zone 分区语义修复——取消 zone=3 分区，推荐流改为 zone 1/2 聚合视图（2026-10-26，diff 已生成待验收）

> 背景与决策：原设计把「推荐流」当成文章分区（zone=3，仅 ADMIN/运营可写），语义错位——
> 分区是文章的归属属性（1-读者区/2-作家区），推荐流是一种浏览视图（zone 1/2 混合聚合）。
> 修复口径：**取消 zone=3 分区**（ZoneEnum 删 RECOMMEND），/feed 查询条件改 `zone in (1,2)`。
> 推荐排序方式（时间倒序 vs 热度分预计算 vs ai 个性化）另立项决策，本阶段不动排序逻辑。

### 待拍板决策（已确认）
- [x] zone=3 枚举项处理：**直接删除**（请求传 zone=3 时反序列化失败报参数错误，即期望的拒绝行为）；

- [x] 存量数据迁移口径：无存量数据（免迁移）

### 代码修改（litisle-content + common-core，共 9 处）【落地 2026-10-26，diff 已生成】
- [x] ZoneEnum：删除 `RECOMMEND(3, "推荐流")` 枚举项；类注释改为 NORMAL(1)/WRITER(2)，
      补充「推荐流为 zone 1/2 聚合视图」语义说明
- [x] ZoneRouter：删除 `zone == ZoneEnum.RECOMMEND` 的 ADMIN 校验分支；
      口径注释删除 zone=3 描述，标注阶段12 语义修复来源
- [x] FeedServiceImpl#loadFromDb：`.eq(Article::getZone, ZoneEnum.RECOMMEND)` →
      `.in(Article::getZone, NORMAL, WRITER)`；
      L54/L122 两处注释「zone=3 预计算链路」→「预计算推荐链路（zone 1/2 聚合）」（核心逻辑）
- [x] ArticleServiceImpl L193/L362：注释删除「zone=3 仅 ADMIN/运营」表述（逻辑随 ZoneRouter 自动收口）
- [x] FeedController @Tag description：「zone=3 预计算链路」→「预计算推荐链路，zone 1/2 聚合视图」
- [x] FeedService L18：接口 Javadoc 同步
- [x] FeedItemVO L10：Javadoc「zone=3 预计算链路出参」→「预计算推荐链路出参，zone 1/2 聚合视图」
- [x] Article 实体 L49：字段 Javadoc 删除 `RECOMMEND(3)-推荐流` 取值，标注阶段12 口径
- [x] ErrorCode.ARTICLE_ZONE_DENIED（common-core）L216：注释删除「zone=3 非 ADMIN/运营」；
      **错误码本身保留不删**（zone=2 非 AUTHOR 的分区权限拒绝语义仍需要）

### SQL 修改【落地 2026-10-26，diff 已生成】
- [x] content-schema.sql L32：zone 列注释改为「1-普通分区 2-作家分区（推荐流为 zone 1/2 聚合视图，非分区）」
      （列本身与 idx_zone_status 保留，索引取值域收缩为 {1,2} 仍有效）


### 文档同步【落地 2026-10-26，diff 已生成】
- [x] Content-Architeche.md：§2.1 分区管理行、§2.2 zone 字典（取值域改 1/2）、
      §六 错误码行；「推荐流」统一改「聚合视图（zone 1/2）」
- [x] Sql-Document.md L388：zone 列注释同步
- [x] Content_Function_Develop.md L31 分区管理行 / §6.5 推荐流写权限改视图口径 / L176 标签权重行
- [x] Function-Document.md L194：「推荐流分区（zone=3）」→「zone 1/2 聚合视图」
- [x] Content-Business-Guide.md L82/L137/L190：ZoneRouter 口径描述删除 zone=3
- [x] temp.md 本阶段：执行状态已更新；全部验收后随阶段 12 整体转 archive

### 明确不改（防误伤）
- docs/archive/content-implemention-records.md：历史落地记录保持原貌（zone=3 当时的实现口径如实留档）
- ArticleMyQueryDTO / ArticlePublicQueryDTO 等 zone 透传字段：取值域随枚举自然收缩为 1/2，无 zone=3 硬编码



### 后续另立项（不在本阶段）
- 推荐排序方式决策：区域列表（zone 1/2）时间倒序 / 推荐流热度分预计算（Redis ZSet + 定时任务，
  兜底降级时间倒序）/ 二期 ai 个性化重排——分区修复落地后单独评估
- zone 1/2 区域列表端点（GET /api/content/articles?zone=1|2）：复用缓存三防 + recheckFilter 回查机制，
  与推荐流共用模板方法、缓存键分离——随排序方式决策一并立项
