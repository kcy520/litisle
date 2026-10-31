> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。
> **板块九说明**：迁自 docs/temp/temp.md（临时任务清单）；批次1~3（阶段1~14）已于 2026-10-28 归档，批次4（阶段15~17）、批次5（后续另立项）归档追加至本文件，content 模块缺口修复批次 Q1~Q5（2026-10-30）归档追加至本文件（dfa 批次记录仍留 docs/temp/temp.md 未归档）。

## 板块九：临时清单（docs/temp/temp.md）——分类枚举化与 content 模块补链（2026-10-20 ~ 2026-10-30；批次3 阶段10~14、批次4 阶段15~17、批次5 后续另立项 于 2026-10-28 归档；content 模块缺口修复批次 Q1~Q5 于 2026-10-30 归档）

> **来源**：迁自 docs/temp/temp.md（阶段1~17 全量 + 后续另立项批次5 原样搬运，含落地注记与决策留档；批次3 阶段10~14、批次4 阶段15~17、批次5 后续另立项 于 2026-10-28 追加）
> **背景**：以「文章分类枚举化改造」立项（方案A：@EnumValue 中文入库，DB 零迁移），执行中滚动扩入图片引用闭环（阶段6）、软删连带盘点（阶段7）、计数/封面/展示缺口盘点（阶段8）、密码可见功能下线（阶段9）、content 模块全量审查修复（阶段10）、草稿域单行化收尾（阶段11）、zone 分区语义修复（阶段12）、推荐流热度榜骨架与切流（阶段13~14）、阅读历史语义升级单表双类型（阶段15）、文章/书籍收藏一期 content 临时承载（阶段16）、书籍阅读闭环三读端点（阶段17）、后续另立项批次5（发布异常保稿/热度榜增量优化/书籍发现入口落地；阅读足迹与 social 依赖项核实关闭）
> **挂起/预留项**：已按归档规则转移至 [Temporarily_not_implemented.md](../../next/temporarily/Temporarily_not_implemented.md) content 内容模块节（批次4 新增 1 条迁入项：「阶段17 后续另立项余项」；批次5 无新增迁入项——热度榜全量灌榜优化与书籍发现入口已落地，更新该条与「书籍阅读配套缺口」条目状态）；待用户手动执行项见本板块「待用户手动执行项汇总」节

**落位**：litisle-content `enums`、`domain/dto`、`domain/entity`、`domain/vo`、`service/impl`、`support`（ArticleImageRefSupport/ViewCounterSupport/ViewCountFlushJob）、`mapper`、`controller`、`constant`；`docker/init/mysql/content-schema.sql`；common-core ErrorCode（保留项）
mulu
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
- [x] `domain/dto/ArticlePublishDTO.java`：`String category` → `ArticleCategoryEnum`
      （删 @Size，@JsonValue 反序列化自动白名单校验，脏值 400）
- [x] `domain/dto/ArticleUpdateDTO.java`：同上
- [x] `domain/dto/DraftSaveDTO.java`：同上
- [x] `domain/dto/ArticleQueryDTO.java`：同上（可空=全部语义保留）

### 阶段3：实体与 VO
- [x] `domain/entity/Article.java`：`String category` → `ArticleCategoryEnum`
      （MybatisEnumTypeHandler 已全局配置 application.yml:117，@EnumValue 映射 varchar 中文值）
- [x] `domain/entity/ArticleDraft.java`：同上
- [x] `domain/vo/ArticleCardVO.java` / `ArticleDetailVO.java` /
      `DraftCardVO.java` / `DraftDetailVO.java`：`String category` → 枚举
      （@JsonValue 序列化输出中文，前端展示无感）

### 阶段4：Service/Controller 逻辑适配
- [x] `ArticleServiceImpl` L184/L303：`setCategory(dto.getCategory())` 类型随 DTO 自动适配；
      发布/存草稿 category 为 null 时落 `ArticleCategoryEnum.DEFAULT`（对齐 BookServiceImpl 缺省口径）
- [x] `ArticleServiceImpl#listPublic`（L504-511）：签名 `String category` 保留；
      非空白时先 `ArticleCategoryEnum.resolve(category)` 校验，
      未命中抛 `BizException(ErrorCode.ARTICLE_PARAM_INVALID)`（替代静默空结果）
- [x] `DraftServiceImpl`：草稿保存/更新链路类型适配 + 缺省值口径对齐
      （insert 忽略 null 列落 DB default '散文'（=DEFAULT 同值）；update 禁改 DEFAULT 防自动保存误覆写，注释已记录）
- [x] `ArticleController` L124：`@RequestParam String category` **保持 String 不动**
      （不走 Jackson，改枚举会绑定英文 name 破坏前端 URL 中文口径；白名单校验下沉 Service 层 resolve）

### 阶段5：验证
- [ ] 编译：`mvn -pl litisle-content -am compile -DskipTests`（Win11 PowerShell 下同命令）
- [ ] 回归点：①发布（中文分类入库）②编辑改分类 ③草稿保存/恢复 ④列表 ?category=散文 过滤
      ⑤?category=不存在 → 400 而非静默空 ⑥详情/卡片 VO 回显中文
- [ ] 确认无其他模块跨服务引用 article.category（review/content-client 仅回查正文与 hash，不受影响）

### 待确认（阻塞 Book 链路，不阻塞本文清单）
- [ ] Book 分类集合是否与文章一致（DB default '小说'）？
      一致 → 复用 ArticleCategoryEnum；不一致 → 新建 BookCategoryEnum 后再改
      BookSaveDTO/Book/BookDetailVO/BookServiceImpl
      （⏳ 挂起项已于 2026-10-23 迁移至 Temporarily_not_implemented.md content 内容模块节，此处原样留档）

### 阶段6：图片引用闭环（方案C content 侧补链，独立提交，与分类枚举改造分开）

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

#### 不采用方案（留档防止反复）
- content 直调物理删除：破坏方案C「解绑与删除分离」分层，绕过 7 天缓冲/隔离前缀
  保护（违规图证据保留），不可取
- file 对账任务加「扫无引用孤儿」分支：「无引用」≠「已弃用」——刚上传未挂文章、
  编辑器暂存的图会被误杀；引用生命周期必须由引用方显式管理

#### 验证点
- [ ] 发布挂图 → file_ref 新增四元组行
- [ ] 编辑换图 → 弃图 unbind（file_ref 删行）+ 新图 bind
- [ ] 编辑时 Feign 失败 → 本地事务回滚，article_image 与 file_ref 无中间态
- [ ] 软删文章 → 全部挂图 unbind；对账任务 7 天后物理清理本体+缩略图
- [ ] 多文共享同一图：一文弃图后另一文仍可读（引用计数=1 未清本体）

#### 第二轮全量盘点追加发现（发布/草稿/快照链路同类问题）
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

### 阶段7：软删连带范围盘点（删除文章/方案B软删重发时，点赞/评论/图片是否被考虑）

#### 现状结论（排查记录，2026-10-20；2026-10-21 阶段6 后复核更新）
- 软删实现：`ArticleServiceImpl#delete`：CAS del_flag=1 →
  **【阶段6 新增】查全量图行 afterCommit unbind ARTICLE_IMG 引用（补偿性 warn 留痕）** →
  unpublishedCacheCleaner 失效审核缓存 → afterCommit 发 content.unpublished（reason=1 删除）
- 事件消费方：仅 ai（向量库移除）+ search（预留）；**social 未消费**；
  ContentUnpublishedEvent.bizType 注释已预留评论扩展（1-文章；2-评论扩展位）
- 点赞/评论：**全仓未实现**（social 模块无 like/comment 代码；
  ArticleCounterUpdateDTO 为 social 单点回写预留契约，view/like/comment 计数冗余在 article 行）
  → 点赞/评论只需**预留思路**，不实施

#### 图片（content 模块内，已有部分覆盖+补决策点）
- [x] 引用解绑：已在阶段6「文章软删链路：同口径对全部挂图 unbindRef」覆盖，此处不重复
      【落地确认】`ArticleServiceImpl#delete` 已含 afterCommit 全量 unbind（阶段6）
- [x] 决策点：article_image 行随软删的处理口径——**已确认：不动**（采纳建议）：
      软删=逻辑墓碑（无恢复接口但保留行做审计/法务取证，对齐方案C证据保留思想）；
      图行无人读无泄漏，泄漏风险全在 file_ref 侧，由阶段6 afterCommit unbind 收口；
      禁止软删时物理删图行/file 本体（破坏缓冲期与证据保留）

#### 编辑方案B软删重发的互动数据悬空（关键边界，预留）
> 方案B：已发布文章改正文=软删旧文+重发新文（新 articleId）。
> 点赞/评论挂旧 articleId → 重发后**旧互动全部悬空丢失**
- [x] 预留决策点：接受丢失（正文已变，旧点赞/评论语义失效——**推荐**，互动不可迁移到新正文）
      vs social 侧迁移（旧 articleId → 新 articleId 批量改绑，仅标题/元数据级编辑才允许保留）
      【决策留档】默认采纳推荐口径（接受丢失）；实施时如产品要求元数据级编辑保留互动，再启用迁移方案
- [x] 预留兜底：方案B 重发事件若落地（如 content.replaced 事件），social 按需消费做隐藏/迁移；
      当前不实施，仅在 social 点赞/评论设计文档中记录该边界
      【留档】social 点赞/评论设计文档尚未创建（social 模块无 like/comment 代码）；
      该边界已在本清单留档，待 social 模块立项时随设计文档固化

#### 点赞/评论（social 模块，预留不实施）
- [x] 预留：social 订阅 content.unpublished（reason=1 删除），文章维度点赞记录/评论树置
      「随文隐藏」状态（软删标记，不物理删——法务取证对齐），列表按隐藏状态过滤
      【预留留档】设计意图已固化于本条，实施随 social 模块立项
- [x] 预留：悬空兜底——点赞列表/评论入口反查 content 详情，ARTICLE_NOT_FOUND 时静默过滤
      （双保险：即使事件丢失/乱序，用户端也不感知悬空）
      【预留留档】双保险口径已固化于本条，实施随 social 模块立项
- [x] 预留：ContentUnpublishedEvent.bizType=2（评论）扩展位已有，评论软删时复用同一事件口径，
      其下点赞连带隐藏（评论维度生命周期独立于文章）
      【预留留档】扩展位已核实存在（ContentUnpublishedEvent L27/L58 注释），实施随 social 模块立项

#### 计数冗余（无需处理，留档）
- article 行软删后 like_cnt/comment_cnt/view_cnt 冗余字段不再对外
  （详情可见性链路已拒绝 del_flag=1），无需清理；
  未来做「账号注销/作者清空」时再补批量口径

### 阶段8：第三轮缺口盘点（计数链路/封面图/展示层）

#### ①【功能缺失】浏览计数链路完全未实现（view_cnt 永远为 0）
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

#### ②【泄漏】封面图 coverFileId 完全游离在引用管理之外
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

#### ③【展示缺口】FeedItemVO 缺 commentCnt
- Card/Detail VO 均有 viewCnt/likeCnt/commentCnt 三计数，FeedItemVO 只有前两个
- [x] 补 commentCnt 对齐三 VO 口径；若 feed 流设计上不展示评论数，则注释留档设计意图
      【落地】FeedItemVO 补 `private Long commentCnt;`（likeCnt 之后，对齐 Card/Detail 三计数口径）；
      填充值随 social 落地后 feed 聚合链路补充（当前 social 无 like/comment 表，预留字段）

#### ④【预留确认】updateCounters 对已删文章抛 ARTICLE_NOT_FOUND
- updateCounters 带 del_flag=0 条件，rows=0 抛异常；social 未来回写软删文章计数会失败
- [x] social 落地时约定：收 ARTICLE_NOT_FOUND 视为成功吞掉（文章已删计数无意义），
      避免重试风暴；写入 social 设计文档预留
      【留档】本阶段不实施（social 模块尚未开发），仅作跨模块契约约定：
      social 侧 updateCounters 回写失败时，对 ErrorCode=ARTICLE_NOT_FOUND
      记 info 日志后视为成功（文章已软删，计数无意义），其余错误走重试/DLQ

### 阶段9：删除文章密码可见功能（visibility=4 PASSWORD 整链下线；代码项已完成 2026-10-23）

> 决策：建表脚本采用方案A（content-schema.sql 直接删 access_password 列，本地环境重建即生效）
> ⚠️ 原始指令笔误留档：用户指向 review-schema.sql，实际该列定义在 content-schema.sql（已核实）
> ⚠️ 手动项待执行：ArticlePasswordVerifyDTO.java 删除（已核实文件仍在）、存量数据迁移确认、已有库 ALTER 删列、编译验证

#### 前置（先于代码删除，防越权窗口）
- [ ] 存量数据迁移：现有 visibility=4 的文章删枚举后会落入 getDetail default 分支按 PUBLIC 放行
      （越权风险），删代码前先执行
      `UPDATE article SET visibility = 1 WHERE visibility = 4;`
      （或确认现库无 visibility=4 数据后跳过）
      【待用户执行】代码删除已落地，若现库存在 visibility=4 数据须立即补执行此迁移

#### 枚举与常量
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

#### 验密链路（整条删除）
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

#### 发布/编辑/详情链路
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

#### 建表脚本（方案A）
- [x] `content-schema.sql`：删除 `article.access_password` 列定义（L40）+
      visibility 列注释中「4-密码访问」描述（L39）+ 尾部字段说明（L175）；
      本地环境需重建（或手动 `ALTER TABLE article DROP COLUMN access_password;`）
      【落地】脚本已同步（新环境重建即生效）；【待用户执行】已有库手动 ALTER
      （docker mysql init 重建则自动）

#### 验证
- [ ] 编译：`mvn compile -pl litisle-content -am -q`（Win11 PowerShell）
      【待执行】建议先删 ArticlePasswordVerifyDTO.java 后编译
- [ ] 回归点：①发布（visibility 仅 1/2/3 可选，传 4 → 400）②编辑改可见性 ③详情三档可见性判定
      （公开/仅自己/仅关注者）④全仓 grep PASSWORD 无 content 侧残留引用
      ⑤ verify-password 端点 404

### 待用户手动执行项汇总（2026-10-27 登记阶段5/6/9，2026-10-28 批次3 追加阶段13/14、批次4 追加阶段15/16/17；全部完成前板块九归档不闭环）

> 汇总范围：阶段5/6/9 未勾选验证项与手动操作项；批次3 追加阶段13/14、批次4 追加阶段15/16/17（与 temp.md 头注「归档时点待执行项」同源，此处为板块九收口视图）。

- 阶段5：编译 `mvn -pl litisle-content -am compile -DskipTests`；六项回归点（①发布中文分类入库 ②编辑改分类
  ③草稿保存/恢复 ④?category=散文 过滤 ⑤非法分类 400 ⑥VO 中文回显）；确认无跨服务引用 article.category
- 阶段6：五项验证点（发布挂图 file_ref 落行 / 编辑换图差集 unbind+bind / Feign 失败本地事务回滚无中间态 /
  软删全量 unbind / 多文共享同一图引用计数不清本体）
- 阶段9：① IDE 删除 ArticlePasswordVerifyDTO.java（已核实文件仍在，当前无引用不影响编译）
      ② 存量迁移 `UPDATE article SET visibility = 1 WHERE visibility = 4;`（若现库有数据须先执行——
      防删枚举后落 default 分支按 PUBLIC 放行的越权窗口）
      ③ 已有库 `ALTER TABLE article DROP COLUMN access_password;`（docker mysql init 重建则自动）
      ④ 编译 `mvn compile -pl litisle-content -am -q`（建议先删 DTO）+ 五项回归点
      （①发布 visibility 仅 1/2/3 可选传 4 → 400 ②编辑改可见性 ③详情三档判定
      ④全仓 grep PASSWORD 无 content 侧残留 ⑤verify-password 端点 404）

- 阶段13：编译 `mvn compile -pl litisle-content -am -q`（随阶段12 验证一并执行）
- 阶段14：C8 五项回归点（①榜空降级时间倒序不空流 ②下架/转私密即时被 recheckFilter 剔除
  ③翻页无重复/无跳漏（灌榜重算分数漂移引发页间微移，可接受留档）④多实例下灌榜不重复执行
  ⑤编译 `mvn compile -pl litisle-content -am -q`（Win11 PowerShell））

- 阶段15：编译 `mvn compile -pl litisle-content -am -q`；SQL 重建核对（docker init 后 reading_history 只建一次；
  uk_user_biz / idx_user_time / idx_user_time_all 三索引存在；表/列注释为「阅读历史」口径）+ 回归点①~⑬
  （①PUT 上报：首插/仅前进不回退/伪造跨书章节 400/限频 2/60s ②续读：有历史返回章节信息/无历史 null/退章 null
  ③列表：书籍/文章/全部三 tab 倒序、书籍行含章节信息、容错不丢行、空页/分页边界/非法 400 ④软删过滤与 total 一致
  ⑤读独立文章落 biz_type=2 ⑥读章节文章落 biz_type=1 ⑦多书挂载不自动落、书籍页 PUT 可落
  ⑧匿名不落 ⑨自读不落 ⑩写入异常详情仍正常返回（fail-safe）⑪⑫全仓 grep 旧表名/旧术语/「阅读进度」零残留
  ⑬越权：列表/续读/上报均以登录人为准）
- 阶段16：编译 `mvn compile -pl litisle-content -am -q`；SQL 重建核对（docker init 后 content 第9张表 favorite 存在；
  uk_user_target / idx_target / idx_user_time 生效）+ 回归点①~⑩（①收藏/取消幂等 ②匿名 401
  ③不存在/已软删/不可见目标拒（ARTICLE_NOT_FOUND/BOOK_NOT_FOUND）④私有仅作者、FOLLOWERS 回查、social 不可用 fail-fast
  ⑤我的收藏三 tab/空页/分页边界/非法 400 ⑥目标软删不出列表、转私密/下架仍在列表但详情兜底
  ⑦文章详情 favorited 正确、书籍详情随阶段17、列表无 N+1 ⑧限频 20/60s、网关不放行匿名
  ⑨全仓 grep 无 social 收藏夹字段误入 ⑩越权：均以登录人为准）
- 阶段17：编译 `mvn compile -pl litisle-content -am -q` + 回归点①~⑨（①书籍详情：匿名可读/作者可读下架书/非作者 404/
  空书全 null ②章节列表：升序/分页/空页/三档可见性 ③章节详情：prev/next 只指可见章节/跨过不可见/MISMATCH 400/
  文章不可见 404/末章 next=null ④上次阅读：登录返回 lastRead、不可见置 null、匿名不返回
  ⑤读章节落 biz_type=1 且 chapter_id 正确、重复阅读一行 ⑥浏览计数含匿名/作者 ⑦书籍 favorited 匿名 false
  ⑧网关：三 GET 匿名可访问、写端点仍 401 ⑨无重复历史写入路径）

### 阶段10：content 模块整体业务逻辑审查发现（2026-10-25，待修复确认）

> 全模块审查（发布编排/审核回写/三个 MQ 消费者/计数链路/图片引用/草稿/事件/Feed/
> ContentInternal/Book/Tag/ReadingProgress）后确认的 2 个重大 bug + 2 个中等问题；
> 其余链路（软删红线/submit 重提/ACK 纪律/缓存失效/回查同源/进度 UPSERT）确认无问题

#### 重大
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

#### 中等
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

### 阶段11：草稿域单行化与 MP 规范化收尾（2026-10-26，修复已落地，编译待验证）

> 背景：草稿模型定案为「多草稿并存 × 每个草稿单行 UPSERT 只存最新版」（draft_version 仅作乐观锁），
> 滚动清理旧快照链路已整体删除；配图占位符渲染抽共享组件 ArticleImageRenderSupport；
> service/impl 全部 .last("LIMIT..") 已收口为 Page.of 标准写法。
> 本阶段为本轮改动后的收尾项与决策项。



#### P1 作废配置与死代码（已完成 2026-10-26）
- [x] application.yml 的 `litisle.content.draft.keep-versions: 10`：
      【落地】注释改为【已作废】标注（单行 UPSERT 模型定案，代码不再读取；按 yml 纪律保留备查）
- [x] ContentBizConstant.SQL_LIMIT_1 / SQL_LIMIT_PREFIX：
      【落地】已删（零引用死常量，防 .last 拼 SQL 回潮）
- [x] ArticleServiceImpl 残留 import 清理：
      【落地】Matcher 已删（无其他使用点）；Map/Collections/fileClient 核实仍有使用点，保留

#### P2 决策项
- [x] DraftController.java 类头孤立注释「修改草稿」：
      【落地】已删（实际编辑闭环=详情+save，注释失实）

#### P3 可选项（不阻塞）

- [x] 错误码拆分：ARTICLE_IMG_REFUSED 混装「文件不存在」与「引用他人文件」两种失败
      【决策留档】暂缓拆分：fileId 唯一来源是上传接口，「不存在」仅在文件被物理清理/
      ID 错误的边缘场景出现，前端对保存失败统一提示即可；拆分需 file 侧契约联动，
      收益低，待前端实际反馈再立项
- [x] article_draft 表注释同步：
      【落地】content-schema.sql 三处（draft_version 列注释/表注释/尾部说明）已同步
      单行 UPSERT 口径；已有库注释不自动生效，重建容器或手动 ALTER（仅注释，不急）

### 阶段12：zone 分区语义修复——取消 zone=3 分区，推荐流改为 zone 1/2 聚合视图（2026-10-26，diff 已生成待验收）

> 背景与决策：原设计把「推荐流」当成文章分区（zone=3，仅 ADMIN/运营可写），语义错位——
> 分区是文章的归属属性（1-读者区/2-作家区），推荐流是一种浏览视图（zone 1/2 混合聚合）。
> 修复口径：**取消 zone=3 分区**（ZoneEnum 删 RECOMMEND），/feed 查询条件改 `zone in (1,2)`。
> 推荐排序方式（时间倒序 vs 热度分预计算 vs ai 个性化）另立项决策，本阶段不动排序逻辑。

#### 待拍板决策（已确认）
- [x] zone=3 枚举项处理：**直接删除**（请求传 zone=3 时反序列化失败报参数错误，即期望的拒绝行为）；

- [x] 存量数据迁移口径：无存量数据（免迁移）

#### 代码修改（litisle-content + common-core，共 9 处）【落地 2026-10-26，diff 已生成】
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

#### SQL 修改【落地 2026-10-26，diff 已生成】
- [x] content-schema.sql L32：zone 列注释改为「1-普通分区 2-作家分区（推荐流为 zone 1/2 聚合视图，非分区）」
      （列本身与 idx_zone_status 保留，索引取值域收缩为 {1,2} 仍有效）


#### 文档同步【落地 2026-10-26，diff 已生成】
- [x] Content-Architeche.md：§2.1 分区管理行、§2.2 zone 字典（取值域改 1/2）、
      §六 错误码行；「推荐流」统一改「聚合视图（zone 1/2）」
- [x] Sql-Document.md L388：zone 列注释同步
- [x] Content_Function_Develop.md L31 分区管理行 / §6.5 推荐流写权限改视图口径 / L176 标签权重行
- [x] Function-Document.md L194：「推荐流分区（zone=3）」→「zone 1/2 聚合视图」
- [x] Content-Business-Guide.md L82/L137/L190：ZoneRouter 口径描述删除 zone=3
- [x] temp.md 本阶段：执行状态已更新；全部验收后随阶段 12 整体转 archive

#### 明确不改（防误伤）
- docs/archive/content-implemention-records.md：历史落地记录保持原貌（zone=3 当时的实现口径如实留档）
- ArticleMyQueryDTO / ArticlePublicQueryDTO 等 zone 透传字段：取值域随枚举自然收缩为 1/2，无 zone=3 硬编码



#### 后续另立项（不在本阶段）
- 【决策定案 2026-10-26】推荐排序方式：**热度榜**（Hacker News 型时间衰减公式 + Redis ZSet 预计算）；
  social 模块落地前仅骨架预留（阶段13），启用接线点见 FeedHotRankSupport 类注释；
  ai 个性化重排留三期演进
- zone 1/2 区域列表端点（GET /api/content/articles?zone=1|2）：复用缓存三防 + recheckFilter 回查机制，
  与推荐流共用模板方法、缓存键分离——随热度榜启用一并立项

### 阶段13：推荐流热度榜骨架预留（2026-10-26，social 未开发，零行为变化）

> 决策：/feed 排序终局=热度榜；当前 like/comment 恒 0（social 未开发），公式退化为纯浏览量不值得启用，
> 故仅落骨架（公式真实现 + 接线点注释），/feed 现仍时间倒序，本阶段零调用零影响。

- [x] CacheConstant 新增 CONTENT_FEED_HOT_ZSET（ZSet 键预留，common-redis）
- [x] ContentProperties.Feed 新增 Hot 嵌套配置（enabled 默认 false；权重/衰减参数外置可调）
- [x] 新建 FeedHotRankSupport（service/support，待用户创建文件）：computeScore 纯公式实现 +
      refillHotRank/topArticleIds TODO 预留，类注释写清 social 落地后四步接线点
      （回写触发→灌榜任务→切流→开开关）
- [x] FeedServiceImpl#loadFromDb 注释标注热度榜切换点与降级口径（ZSet 空降级时间倒序）
- [ ] 编译：`mvn compile -pl litisle-content -am -q`（随阶段12 验证一并执行）
- [ ] social 模块落地时回看本类四步接线点启用热度榜

### 阶段14：推荐流热度榜切流实现（2026-10-26 清单落定；A/B 组已落地，C 验证/文档待执行）

> 前置：阶段13 骨架已就位（FeedHotRankSupport/ZSet 键/Hot 配置）。
> 执行顺序：A1 → B2 → B3 → B4 → B5 → C 一轮即可上线（纯 view 信号排序）；
> social 未落地期间 like/comment 恒 0，榜退化为「浏览量+时间衰减」排序，链路先行跑通，
> social 落地后计数回写→下轮灌榜生效，无需返工。

#### A. 公共支撑（common-redis）
- [x] A1 RedisUtil 补 ZSet 封装（✅ 2026-10-26）：`zReverseRangePage(key, offset, count)`
      （按分数倒序分页取成员）+ `zAddBatch`（批量灌榜）+ `rename`（原子换榜）；
      口径微调：榜容量裁剪改在灌榜内存完成 top N，无需 zCard/ZREMRANGEBYRANK

#### B. content 模块开发（4 必做 + 2 可选）
- [x] B2 新建灌榜定时任务 FeedHotRankRefillJob（✅ 2026-10-26，job 包，对齐 ViewCountFlushJob 范式：
      @Scheduled + enabled 开关 + 分布式锁单点）：批量查 zone 1/2 且 status=2
      且 visibility=1 且未软删文章（含三冗余计数）→ 逐篇 computeScore → 内存裁剪 top N →
      影子键 ZADD → rename 原子换榜 → 榜 TTL 30min（任务死亡后自动过期降级）
- [x] B3 补齐 FeedHotRankSupport 两处 TODO（✅ 2026-10-26）：refillHotRank（批量查→算分→
      内存 top N→影子键 rename 换榜→重置 TTL，任一步失败保留旧榜）+
      topArticleIds（zReverseRangePage 取本页文章 ID，榜空返回空列表）
- [x] B4 FeedServiceImpl#loadFromDb 切流（✅ 2026-10-26）：hot.enabled 且 topArticleIds 非空 →
      loadByHotRank 按榜组装 VO（in 查询不保序，按 ZSet 返回顺序重排，缺失跳过）→
      榜空/开关关降级 loadByTimeDesc 时间倒序直查（兑底契约已注释）；
      缓存键已升 v2（content:feed:page:v2:）防切流瞬间新旧排序混存；
      抽取 toFeedItem 两条回源链路共用装配
- [x] B5 yml 配置（✅ 2026-10-26）：litisle.content.feed.hot.enabled=true + 权重/衰减参数显式声明
      （ContentProperties 默认值已备好）；灌榜 refill-enabled / interval-ms / initial-delay-ms / top-n

#### C. 验证与文档
- [ ] C8 回归点：①榜空降级时间倒序不空流 ②下架/转私密即时被 recheckFilter 剔除
      ③翻页无重复/无跳漏（灌榜重算分数漂移引发页间微移，可接受留档）
      ④多实例下灌榜不重复执行 ⑤编译 `mvn compile -pl litisle-content -am -q`（Win11 PowerShell）
- [x] C9 文档同步（✅ 2026-10-26）：架构文档 §2.4 补推荐流热度榜核心流程（灌榜 R1-R4/读榜/兕底）
      + §五 配置表补 feed.hot 六行 + §三 /feed 端点描述更新 + §七 缓存三防补 ZSet 灌榜口径
      （影子键 rename 换榜、禁在线榜增量写防残榜、TTL 30min 自愈）；
      Function-Document 推荐流行补热度分公式与降级兕底；Sql-Document 三计数列注释
      补热度榜信号说明 + 设计原因补榜数据源口径（排序在 Redis 不落库）

#### D. social 模块落地后回看（预留）
- [ ] D10 ContentInternal#updateCounters 回写 like/comment 后触发该文章重算 ZADD
      （实时性优于等下轮灌榜；对齐「viewCnt 禁用传 null」同处契约注释）
      ⏳ 已登记 docs/next/Temporarily_not_implemented.md（updateCounters 已留 TODO 接线点）
- [ ] D11 权重调参：真实互动数据进来后按效果调 likeWeight/commentWeight/viewWeight
      （yml 热调无需发版）
      ⏳ 已登记 docs/next/Temporarily_not_implemented.md「推荐流热度榜 social 信号补齐与调参」

### 阶段15：阅读进度 → 阅读历史语义升级（单表双类型：书籍历史含章节 + 文章历史 + 阅读即落历史）

> 决策（2026-10-27 定案，v2 单表方案；取代 v1「仅书籍、每人每书一行」方案）：
> - 表：`reading_progress` → `reading_history`，单表承载两类历史：
>   `biz_type=1` 书籍历史（`biz_id=book.id`，`chapter_id` 有值）；
>   `biz_type=2` 独立文章历史（`biz_id=article.id`，`chapter_id` 为 null）。
> - 唯一键 `uk_user_biz(user_id, biz_type, biz_id)` = 每人每类型每对象一行；`update_time` 即最后阅读时间。
> - 不分表：report/续读/分页/UPSERT 四机制在两表下完全同构，代码双份漂移风险 > 判别列复杂度；
>   量级（每人每对象一行 + 30s 节流 + 2/60s 限频双闸）撑得起单表。
> - 书籍历史按书聚合：一行 = 该书最后一次阅读，章节信息（chapter_id / chapter_no / chapter_title）随行返回；
>   **不按章节拆行**（避免书架被同一本书刷屏）。如后续要「章节级足迹」明细，另建明细表，不在本阶段。
> - 文章历史只记「独立文章」；章节文章归书籍历史。阅读章节文章时按 `book_chapter.article_id` 归属判定：
>   唯一归属 → 落书籍历史；无归属 → 落文章历史；多书归属（歧义）→ 不自动落，交由书籍阅读页 PUT 显式上报。
> - 阅读即落历史：`GET /article/{id}` 详情命中（登录、非本人、可见性通过）后自动落历史，fail-safe 不阻断详情。
> - `PUT /reading-history` 仍是书籍阅读页的显式上报入口，保留「章节归属校验 + 限频 2/60s + 仅前进不回退」；
>   自动落历史按「最后一次阅读」刷新（回读旧章可回退），两条路径共用 UPSERT，靠 `uk_user_biz` 幂等。
> - URL：`/api/content/reading-progress` → `/api/content/reading-history`（项目未上线，无前端兼容负担）。

#### 决策沿革（保留留痕，不再执行）

- v1（2026-10-27 上午）：仅把 `reading_progress` 改名 `reading_history`，每人每书一行，新增书籍历史列表；
  未覆盖独立文章历史，已废弃。
- v2（本清单）：单表 + `biz_type` 双类型，书籍历史含章节，文章详情自动落历史；以下任务均按 v2 执行。

#### A. SQL 与数据模型（先行，防旧表名残留）

- [x] `docker/init/mysql/content-schema.sql` 第6张表 v1 落地（2026-10-27）：
      表名改 `reading_history`；表注释「阅读进度表」→「阅读历史表」；
      列注释「最后阅读章节ID」补「即上次阅读记录，update_time 即最后阅读时间」口径
      【落地】content-schema.sql L130-142（v2 又改）
- [x] `docs/Sql-Document.md` 第二模块第6张表 v1 同步（2026-10-27）
      【落地】Sql-Document.md L506-518
- [x] 存量库迁移（无历史数据，无需迁移）
      【落地 2026-10-27】项目未上线，现库 reading_progress 无存量数据，免执行 RENAME；
      docker init 重建自动以 reading_history 建表生效
- [x] 【单表返工 v1→v2】content-schema.sql + Sql-Document.md 第6张表同步改造（双文件同步改）：
      ① `book_id` 列 → `biz_id` bigint unsigned not null
         comment '阅读对象ID（biz_type=1→book.id，biz_type=2→article.id）'
      ② `user_id` 与 `biz_id` 之间新增 `biz_type` tinyint not null default 1
         comment '阅读对象类型：1-书籍 2-独立文章'
      ③ `chapter_id` 改为可空，注释补「仅 biz_type=1 有值，指向 book_chapter.id」
      ④ `uk_user_book(user_id, book_id)` → `uk_user_biz(user_id, biz_type, biz_id)`
      ⑤ 删除 `idx_book_id`（按 biz_id 反查对象走 uk_user_biz 最左前缀，无独立查询场景）
      ⑥ `idx_user_time(user_id, update_time)` → `idx_user_time(user_id, biz_type, update_time)`
         （书籍 tab / 文章 tab 倒序分页免 filesort）
      ⑦ 表头/表尾注释改「单表承载书籍+独立文章两类阅读历史，每人每类型每对象一行=上次阅读记录」
      【落地】content-schema.sql L131-144；Sql-Document.md L506-518
- [x] 【新增·脚本去重】`content-schema.sql` 当前 `reading_history` 出现两段完全重复的 `create table`
      （约 L132-144 与 L146-157），文件头 L9-19 模块说明也重复；删除重复段/重复头，保留一份，
      避免 `docker init` 执行冗余 DDL、后续改表漏改一段
      【落地 2026-10-28】建表段与文件头各保留一份；文件头说明「阅读进度」→「阅读历史」顺带对齐 v2 语义
- [x] 【已拍板 2026-10-27·索引落地】保留「全部」混合视图，补
      `idx_user_time_all(user_id, update_time)`；原 `idx_user_time(user_id, biz_type, update_time)`
      保留给书籍/文章单类型 tab。两索引并存，分别覆盖混合时间线与类型 tab 的倒序分页。
      【落地 2026-10-28】content-schema.sql 第6张表补索引，表注释同步双索引分工说明
- [x] 【已拍板 2026-10-27·分页过滤落地】采用自定义 Mapper join 查询：
      左连 book / article 并过滤 `del_flag=0`，在 SQL 层完成分页与 total，
      避免「先分页后内存过滤」导致 total 虚高/空页。
      【落地 2026-10-28】ReadingHistoryMapper#selectMyHistoryPage 自定义 @Select（B 节同名任务一并完成）
- [x] `docs/Sql-Document.md` 同步 `idx_user_time_all` 新增与表注释变更
      【落地 2026-10-28】第二模块第6张表索引与表注释已同步
- [x] 若其他环境已有 `reading_progress` 存量数据，留档迁移脚本（当前库无数据，不执行）：
      `RENAME TABLE reading_progress TO reading_history;`
      `ALTER TABLE reading_history ADD COLUMN biz_type tinyint not null default 1 AFTER user_id, CHANGE COLUMN book_id biz_id bigint unsigned not null, MODIFY COLUMN chapter_id bigint unsigned null;`
      `UPDATE reading_history SET biz_type = 1 WHERE biz_type IS NULL;`
      再按 v2 重建 `uk_user_biz` / `idx_user_time` / `idx_user_time_all`（先删旧索引）
      【落地 2026-10-28】脚本以注释形态留档 Sql-Document.md 第6张表之后

#### B. 实体与 Mapper

- [x] `domain/entity/ReadingHistory.java` 已新建（2026-10-27）：
      `@TableName("reading_history")`，字段 `userId / bizType / bizId / chapterId / updateTime`，
      类头唯一键注释 `uk_user_biz`，`chapterId` 注明仅 biz_type=1 有值
- [x] `mapper/ReadingHistoryMapper.java` 已新建（2026-10-27）：
      javadoc 已用 `uk_user_biz` / `biz_type` + `biz_id` 描述；
      续读/历史列表/upsert/限流说明同步
- [x] 【已拍板】`ReadingHistoryMapper#selectMyHistoryPage(Page<ReadingHistory>, userId, bizType)`：
      自定义 @Select 左连 book / article，过滤 `del_flag=0`，
      `order by update_time desc, id desc`（id 作稳定排序 tie-breaker，防翻页漂移），
      SQL 层分页与 total 准确；bizType 条件用 `<if>` 动态拼接；
      多参数方法用 @Param 显式命名。
      【落地 2026-10-28，随 A 节分页过滤项一并完成】
- [x] 【新增】`BookChapterMapper#selectByArticleId(Long articleId)`：
      返回该文章的全部挂载行（最多取 2 条即可判定 0/1/多书归属）；
      **不要只取第一条**，避免多书挂载时把历史记到错误书籍；
      用 @Select 明确 SQL，保留 `idx_article_id` 支撑
      【落地 2026-10-28】limit 2 取判定所需最少行；类头 javadoc 同步——
      旧「selectOne(article_id)」口径改为 selectByArticleId（禁止只取第一条），
      「reading_progress 进度容错」术语同步为 reading_history 历史行容错
- [x] 旧链路文件删除（B/C/D 节新文件落地、引用归零后执行；**执行前需用户确认**）：
      `domain/entity/ReadingProgress.java`、`mapper/ReadingProgressMapper.java`、
      `service/ReadingProgressService.java`、`service/impl/ReadingProgressServiceImpl.java`、
      `domain/vo/ReadingProgressVO.java`、`controller/ReadingProgressController.java`
      【落地 2026-10-28】用户已确认；删除前 grep 引用归零（26 处全在 6 文件内部互引），
      删除后 mvn compile 通过；litisle-content 内仅剩 ReadingHistory 实体 L14「原 reading_progress
      语义升级」留痕注释（刻意保留的迁移说明，非旧引用）

#### C. Service 层（重命名 + 历史列表 + 阅读落历史）

- [x] `service/ReadingProgressService.java` → 重命名 `ReadingHistoryService.java`：
      ① `report` 保留（javadoc「进度上报」→「历史上报」，机制描述不变；落库固定 biz_type=1、bizId=bookId）
      ② `getLastChapter` 保留（续读=查上次阅读章节；按 biz_type=1 + biz_id=bookId 查）
      ③ 新增 `getLastRead(Long userId, Long bookId)`：
         返回 `ReadingHistoryVO`（bookId/chapterId/chapterNo/chapterTitle/lastReadTime），
         供阶段17 书籍详情展示「上次读到第N章」；章节/文章已删返回 null，
         可见性由阶段17 调用方按书籍可见性 scope 复核
      ④ 新增 `pageMyHistory(Long userId, Integer bizType, long pageNo, long pageSize)`：
         分页返回当前用户阅读历史，update_time 倒序；
         bizType 过滤：null=全部（混合时间线）/ 1=书籍 / 2=独立文章；
         非法值（非 1/2/null）抛 `PARAM_ERROR(10001)`
      ⑤ 新增 `recordFromArticle(Long userId, Long articleId)`：
         文章详情被阅读后落历史，内部完成「归属判定 + UPSERT」，供 ArticleController/Support 调用
      ⑥ 新增 `recordBookRead(Long userId, Long bookId, Long chapterId)`：
         章节详情被阅读后落书籍历史（显式 book 上下文，避免多书归属歧义）；
         校验 chapter 归属 book 后按 biz_type=1 UPSERT；供阶段17 章节详情端点调用
      【落地 2026-10-28】新文件与旧 ReadingProgress* 并存（旧文件待 D 节落地、引用归零后经确认删除）
- [x] `service/impl/ReadingProgressServiceImpl.java` → 重命名 `ReadingHistoryServiceImpl.java`：
      ① report/getLastChapter 逻辑原样迁移（ReadingProgress→ReadingHistory，bookId→biz_type=1+biz_id），
         注释术语同步
      ② 实现 getLastRead：
         - 按 user_id + biz_type=1 + biz_id=bookId 查 reading_history
         - 查 book_chapter 取 chapter_no/article_id，再查 article 取 title
         - 章节行已删/文章已删 → 返回 null；可见性由阶段17 调用方按书籍可见性 scope 复核
      ③ 实现 pageMyHistory：
         - Step1：按 A 节分页口径查 reading_history（user_id + 可选 biz_type，
           `update_time desc, id desc` 稳定倒序）；
           分页用 `Page.of(...)`，不要走 PageQuery 默认 update_time 排序（SQL 已显式排序）
         - Step2：按 biz_type 分组收集 bizId——1→批量查 book、2→批量查 article
           （取 title/cover_file_id，过滤 del_flag=1；对象已软删的历史行静默跳过，不物理删历史行）
         - Step3：仅 biz_type=1 行收集 chapterId 批量查 book_chapter（取 chapter_no + article_id），
           再批量查 article 取章节标题；章节行已删 → chapter_no/title 均 null；
           章节行在但文章已删 → chapter_no 保留、title 为 null；
           两种情况都不阻断书籍行展示（书名+最后阅读时间仍返回）
         - Step4：组装 `ReadingHistoryItemVO`（内存按 Step1 顺序重排，防 in 查询乱序）
         - 空历史返回空页（不抛异常）
      ④ 实现 recordFromArticle：
         - Step1：`BookChapterMapper#selectByArticleId(articleId)` 取挂载行（最多 2 条）
         - Step2：0 条 → biz_type=2、biz_id=articleId、chapter_id=null（独立文章）
         - Step3：1 条 → biz_type=1、biz_id=book_id、chapter_id=chapter.id（章节文章）
         - Step4：>1 条 → 多书归属歧义，跳过自动落历史（debug 日志留痕），交由书籍阅读页 PUT 上报
         - Step5：按 uk_user_biz 做 UPSERT（存在则更新 chapter_id + update_time，不存在则插入；
           唯一键冲突转更新，复用 report 的冲突处理口径）
         - Step6：自动路径不做「仅前进」校验（「最后一次阅读」语义，回读旧章可回退）；
           PUT 路径保留「仅前进不回退」
         - Step7：极端乱序边界——延迟到达的 PUT 仍可能按「仅前进」覆盖自动回退结果；
           如需严格 last-write-wins，后续可加客户端上报时间戳，当前接受该边界
      ⑤ 实现 recordBookRead：
         - Step1：校验 `book_chapter` 行存在且 bookId/chapterId 匹配；不匹配抛 BOOK_CHAPTER_MISMATCH
         - Step2：按 uk_user_biz 做 UPSERT（biz_type=1、biz_id=bookId、chapter_id=chapterId）
         - Step3：不做「仅前进」校验（最后一次阅读语义），update_time 刷新
      ⑥ 抽私有 `upsertHistory(userId, bizType, bizId, chapterId, onlyForward)`：
         report / recordFromArticle / recordBookRead 三处共用同一套 UPSERT 与唯一键冲突处理，
         避免三份逻辑漂移；onlyForward 仅 report=true
      【新增·性能】同章节重复阅读可加轻量节流（如 update_time 在 60s 内且 chapterId 未变则跳过 update），
      避免详情高频读放大 DB 写；非必需，压测后再定
      【落地 2026-10-28】实现齐备；⑥ upsertHistory 增加 chapter 行参数（仅前进比对复用，免重复查）；
      update_time 显式 SET 刷新——DB「on update current_timestamp」仅行值变化时触发，
      同章节/同文章重读无列变化，不显式 SET 则时间线不挪位（且避免 MP updateById 空 SET 子句报错）；
      节流未加（压测后再定）
- [x] 出参 VO：
      ① `domain/vo/ReadingProgressVO.java` → 重命名 `ReadingHistoryVO.java`：
         续读/书籍详情出参；字段建议 `bookId / chapterId / chapterNo / chapterTitle / lastReadTime`
      ② 新建 `domain/vo/ReadingHistoryItemVO.java`：
         `bizType / bizId / title / coverFileId / lastChapterId / lastChapterNo / lastChapterTitle / lastReadTime`
         （title/coverFileId 按 biz_type 分支取自 book 或 article；
          lastChapterId/lastChapterNo/lastChapterTitle 仅 biz_type=1 有值；
          章节序号来自 book_chapter，章节标题来自章节文章 title；章节已删则后两者为 null）
      【落地 2026-10-28】两 VO 新建；原 VO 原样更新时间字段更名为 lastReadTime
- [x] 【新增】`ReadingHistorySupport`（`service/support`，与 ViewCounterSupport 并列）：
      封装「匿名/自读过滤 + 归属判定 + UPSERT + fail-safe 异常吞掉」，
      供 ArticleController 调用；避免 Controller 直接操作 Mapper/Service 细节
      【落地 2026-10-28】`recordArticleHistory(viewerId, articleId, authorId)`；归属判定+UPSERT 委托
      Service#recordFromArticle；Controller 接线在 E 节执行

#### D. Controller 层（重命名 + 新增端点 + 旧文件清理）

- [x] `controller/ReadingProgressController.java` → 重命名 `ReadingHistoryController.java`：
      `@RequestMapping("/api/content/reading-history")`，类头接口表同步
      ① PUT `/reading-history` 上报：原样迁移（@RateLimiter + @SaCheckLogin 保留；bookId/chapterId 入参）
      ② GET `/{bookId}` 续读：返回 `ReadingHistoryVO`（或保持 Long；与 ③ 路径不冲突）
      ③ 新增 GET `/reading-history`（列表，注意与 GET /{bookId} 路径不冲突）：@SaCheckLogin，
         参数建议用 `ReadingHistoryQueryDTO extends PageQuery`
         （`bizType` 可选 1/2/null；`pageNo/pageSize` 默认 1/10，PageQuery 已做上限保护）
      【落地 2026-10-28】新控制器与旧 ReadingProgressController 并存（旧文件待确认后删）；
      ①③ 同为基础路径靠 HTTP 方法区分（与旧控制器 PUT 空子路径同构），列表 GET 无子路径与 /{bookId} 层级不同不冲突；
      ② 取 ReadingHistoryVO（续读出参升级；旧 /reading-progress/{bookId} 端点仍返回 Long 直至删除）；
      ReadingHistoryQueryDTO 落 domain/dto（@EqualsAndHashCode(callSuper) 对齐 ArticleMyQueryDTO 约定）
- [x] `litisle-gateway/src/main/resources/application.yml`：`/api/content/**` 路由已覆盖
      `/api/content/reading-history/**`，无需新增；确认该路径**不在**公开白名单
      （阅读历史必须登录，匿名不可访问）
      【落地 2026-10-28】已核实 L65 路由覆盖；白名单仅 article/list、article/*、article/hot、category/**，
      reading-history 不在名单，yml 零改动
- [x] `constant/ContentLimitConstant.java`：
      `PROGRESS_MAX_COUNT` → 改名 `HISTORY_MAX_COUNT`（值 2/60s 不变），注释同步「阅读历史」
      【落地 2026-10-28】旧 ReadingProgressController 的 @RateLimiter 引用同步改为 HISTORY_MAX_COUNT
      （旧文件删除前须保持编译绿）
- [x] `service/BookService.java` L11/L19 与 `BookServiceImpl` L135/L141：
      注释「阅读进度」→「阅读历史」；删书/退章注释补「历史行 chapter_id 悬空由列表容错」口径
      【落地 2026-10-28】清单点名 4 处齐改；同口径顺带补齐——BookServiceImpl#removeChapter
      流程注释新增「④ 历史联动」+ 步骤3 补口径、ChapterAddDTO L13 术语同步；
      L19 职责边界服务名同步为 ReadingHistoryService
- [x] 旧链路文件删除（同 B 节清单；**执行前需用户确认**）
      【落地 2026-10-28，与 B 节删除项一并完成】

#### E. 阅读入口适配（阅读即落历史）

- [x] `controller/ArticleController#detail`（GET `/api/content/article/{id}`）：
      详情返回后自动落历史；建议通过 `ReadingHistorySupport` 调用，避免 Controller 膨胀
      【落地 2026-10-28】L148-153 接线；recordView 之后、return 之前（可见性已判定通过）；
      `detail != null` 防御包裹后传 authorId
- [x] 前置过滤：
      ① 匿名（viewerId=null）跳过，不落历史
      ② 自读不计（`detail.getAuthorId()` == viewerId 时跳过），避免自己的文章占满书架
      ③ 仅在详情可见性判定通过后落历史（getDetail 抛异常/不可见/已软删时不落）
      ④ 详情返回 null 时不落（防御性判断）
      【落地 2026-10-28】①② 由 Support#recordArticleHistory 承接；③ 由接线位置保证
      （getDetail 异常即中断）；④ Controller 层 null 判断
- [x] 归属判定（章节 or 独立文章）：
      ① 独立文章（book_chapter 无挂载）→ biz_type=2、biz_id=articleId、chapter_id=null
      ② 章节文章（唯一挂载）→ biz_type=1、biz_id=book_id、chapter_id=chapter.id
      ③ 多书挂载（>1 条）→ 不自动落历史（歧义），由书籍阅读页 PUT 上报；debug 日志留痕
      ④ 一篇文章挂多本书时**禁止**「取最早挂载那条」猜书（会把历史记到错误书籍）；
         若搜索/推荐场景也需要落书籍历史，后续再评估在详情请求带 bookId/chapterId 上下文（另行决策）
      【落地 2026-10-28】C 节 recordFromArticle 已实现（selectByArticleId limit 2 判定，禁止猜书）
- [x] 文章先以独立文章被读、后挂书：旧 biz_type=2 历史行保留（历史事实）；
      新阅读落 biz_type=1；列表不去重。如需合并/隐藏旧行，另立产品决策
      【确认 2026-10-28】单表双类型 + uk_user_biz 含 biz_type，两行天然并存、列表不去重，无需额外代码
- [x] 章节阅读说明：本项目「章节即文章」（book_chapter.article_id → article.id）；
      阶段15 的 GET /article/{id} 仍按归属判定落书籍历史；阶段17 另加书籍章节详情端点
      GET /book/{id}/chapter/{chapterId}，显式调用 recordBookRead（book 上下文明确，避免多书歧义）；
      仅打开书籍详情页/章节目录页不算阅读，不落历史（以章节/文章正文详情命中为准）
      【确认 2026-10-28】recordBookRead 已就绪；端点接线属阶段17，不扩大本阶段
- [x] 与 PUT 的边界：PUT 仍是书籍阅读页的显式上报入口（含前进校验 + 限频）；
      详情自动落历史是补充路径；两条路径写同一张表，靠 uk_user_biz 幂等；
      书籍页读章节时两者命中同一行，不产生重复
      【确认 2026-10-28】设计已满足，无额外代码
- [x] 【已拍板 2026-10-27】文章软删时 `book_chapter` 挂载暂不联动清理：
      本阶段只保证历史列表对 chapter_id 悬空容错展示（chapter_no/title null）；
      「文章软删联动清理挂载」登记 docs/next（见 G 节），不扩大本阶段改动
- [x] 【已拍板 2026-10-27】退章/删书对历史行的影响：
      退章 → chapter_id 悬空，列表展示书名+最后阅读时间，章节信息留空；不物理删历史行
      删书 → book 软删，列表静默跳过该书历史行；历史行保留（审计/恢复预留）
      不新增跨域回写，避免 BookService 依赖阅读历史域
      【落地 2026-10-28】pageMyHistory 容错 + D 节 BookServiceImpl 注释口径（退章④/删书）齐备
- [x] fail-safe 口径：历史写入异常只记日志，详情接口仍正常返回；
      建议同步 try-catch（不引入 @Async 基础设施）；如后续压测显示详情 RT 受影响，再评估异步
      【落地 2026-10-28】Support 同步 try-catch 吞异常（log.warn），未引入 @Async

#### F. 配置与常量

- [x] `application-dev.yaml` L20 / `application-local.yaml` L45：
      库表清单注释 `reading_progress` → `reading_history`
      【落地 2026-10-28】两处注释已同步
- [x] `application.yml`：确认无 progress 相关配置残留（grep 已核实仅两处 yaml 注释）
      【落地 2026-10-28】全仓 yaml grep 复核：reading_progress/reading-progress 仅上述两处，均已改
- [ ] 如启用同章节节流/Redis 去重，补 `ContentProperties`/`ContentBizConstant` + yml 配置说明
      （默认关闭或 60s；遵循 yml 纪律：暂不生效的配置保留并注释用途）
      （2026-10-28：节流未启用，压测后再定，本项暂缓不配置）

#### G. 文档同步

- [x] `docs/Architecture/Content-Architeche.md` L15/L62/L156/L200/L212：
      「阅读进度」→「阅读历史」、表名、URL、限频行描述；§2.1 功能清单改为
      「书籍历史（含最后章节）/ 独立文章历史 / 阅读即落历史」；
      §2.2 功能清单补「历史列表」端点与「阅读即落历史」说明（详情接口副作用）
      【落地 2026-10-28】5 处齐改；L156 数据模型行同步双索引与容错口径；
      L200 端点行补历史列表 + 阅读即落历史副作用说明
- [x] `docs/overall_documen/Function-Document.md` L192 / `docs/overall_documen/Design-Document.md` L434：
      「阅读进度」→「阅读历史」，补双类型与章节口径
      【落地 2026-10-28】L192 功能行补双类型与自动落历史；L434 核心功能行同步；
      顺带修正 L436 数据模型行 reading_progress → reading_history（grep 未点名残留）
- [x] `docs/Study/Content-Business-Guide.md` 第四节（约 L375-421）：
      标题「书籍合集与阅读进度」→「书籍合集与阅读历史」；
      详细版/通俗版补「书籍历史含章节 / 文章历史 / 阅读即落历史」；
      代码清单 ReadingProgress* → ReadingHistory*，补 ReadingHistoryItemVO、ReadingHistorySupport
      【落地 2026-10-28】标题/功能描述/详细版/通俗版/代码清单五处齐改；
      代码清单新增 ReadingHistoryItemVO + ReadingHistorySupport 条目
- [x] `docs/temp/Content_Function_Develop.md` §6.6 与功能表 L17/L35：
      「阅读进度」→「阅读历史」；补单表 biz_type/biz_id、书籍章节、文章自动落历史
      【落地 2026-10-28】L17/L35/L51 数据模型行 + §6.6 整节重写（单表双类型/阅读即落历史/
      参数合法性/节流/一致性/容错六要点）
- [x] `docs/Sql-Document.md`：第二模块第6张表 + 模块说明 L376/L382/L396
      「阅读进度」→「阅读历史」
      【落地 2026-10-28】模块说明 3 处 replace_all 同步（第6张表 A 节已同步过）
- [x] `docs/index/`：全目录 grep 无阅读相关条目，确认无需改动
      （若后续新增 content-index，再补阅读历史条目）
      【落地 2026-10-28】grep 复核无匹配，零改动
- [x] `docs/archive/content-implemention-records.md`：**不改**（历史归档保持原貌，对齐阶段12 纪律）
- [x] `docs/next/Temporarily_not_implemented.md`：登记「章节级足迹明细 / 历史清空或删除 /
      多书归属上下文 / 文章软删联动清理 book_chapter」等待定项（本阶段不做）
      【落地 2026-10-28】新增「阅读历史四项待定扩展（迁自 temp.md 阶段15）」条目，
      按既有格式登记 4 项 + 各自触发条件

#### 已拍板口径（2026-10-27，按此执行）

- 混合「全部」tab：保留；补 `idx_user_time_all`。已定。
- 列表软删过滤：SQL join 过滤后分页。已定。
- 多书挂载：不自动落历史，交书籍阅读页 PUT；禁止猜「最早挂载」。已定。
- 自动路径回读旧章：允许回退（最后一次阅读）；PUT 保留仅前进；极端乱序接受。已定。
- 文章软删联动清理 `book_chapter`：本阶段不做，登记 docs/next；本阶段只保证历史列表容错。已定。
- 章节级足迹明细（每章一条）：本阶段不做。已定。

#### H. 验证

- [ ] 编译：`mvn compile -pl litisle-content -am -q`（Win11 PowerShell）
- [ ] SQL 重建：docker init 后 `reading_history` 只建一次；`uk_user_biz` / `idx_user_time` /
      `idx_user_time_all` 均存在；表/列注释为「阅读历史」口径
- [ ] 回归点：
      ① PUT 上报：首次插入 / 仅前进不回退 / 伪造跨书章节 400 / 限频 2/60s
      ② GET /{bookId} 续读：有历史返回章节信息 / 无历史 null / 章节已退章 null
      ③ GET 列表：书籍 tab 倒序正确 / 文章 tab 倒序正确 / 全部 tab 混合倒序正确 /
         书籍行含 chapterNo+chapterTitle / 章节已删仅缺章节信息不丢书籍行 /
         空历史空页 / 分页边界 / bizType 非法 400 / pageSize 超限被 PageQuery 截断
      ④ 软删过滤：书软删后书籍历史不再出现（历史行保留）/ 文章软删后文章历史不再出现 /
         total 与实际可见条数一致（按 A 节分页口径）
      ⑤ 读独立文章：历史新增 biz_type=2 行；重复阅读仍一行、update_time 刷新
      ⑥ 读章节文章（唯一归属）：历史落到对应书籍 biz_type=1 行，chapter_id 为该章节
      ⑦ 读多书挂载文章：不自动落历史；书籍阅读页 PUT 能落到正确书籍
      ⑧ 匿名读文章：不落历史
      ⑨ 作者读自己的文章：不落历史
      ⑩ 历史写入异常：详情接口仍正常返回（fail-safe 生效）
      ⑪ 全仓 grep `reading_progress` / `ReadingProgress` / `reading-progress` / `PROGRESS_MAX_COUNT`
         零残留（archive 文档、本清单历史审查记录/决策沿革除外）
      ⑫ 文档 grep「阅读进度」零残留（archive 文档、本清单历史审查记录/决策沿革除外）
      ⑬ 越权：列表/续读/上报均以当前登录人 userId 为准，不接受前端传 userId；
         A 用户看不到 B 用户阅读历史
- [ ] 本清单执行状态更新；全部完成后随阶段15 整体转 archive

### 阶段16：文章/书籍收藏（一期 content 临时承载，social 收藏夹后续迁移）

> 归属决策（2026-10-27）：
> - 架构上收藏归 social（设计文档 favorite_folder/favorite_item），但 litisle-social 目前仅空壳
>   （pom 只有 starter/test，无 web/mybatis/mysql/security/redis，application.yml 全注释），
>   为一期收藏去 bootstrap social 成本高，且目标校验要跨服务回查 content。
> - 一期决策：**content 模块临时承载单层收藏**（无收藏夹分组），接口 `/api/content/favorite`，
>   表 `favorite` 放 `litisle_content`；文章/书籍存在性与可见性同域校验，零跨服务。
> - social 立项后迁移：`favorite` → social `favorite_item`（可建默认收藏夹），
>   或确认 content 为收藏 SSOT、social 只做聚合展示；迁移方案另立项，本阶段不预埋复杂结构。
> - 收藏夹分组/佳句收藏仍归 social，本阶段不做。

#### A. SQL 与枚举

- [x] `docker/init/mysql/content-schema.sql` 新增第9张表 `favorite`（放在 `article_tag` 之后）：
      `id` / `user_id` / `target_type`（1-文章 2-书籍）/ `target_id` / `create_time`；
      `unique key uk_user_target(user_id, target_type, target_id)` 防重复收藏；
      `index idx_target(target_type, target_id)` 支撑按目标反查；
      `index idx_user_time(user_id, create_time)` 支撑我的收藏倒序分页；
      表注释注明「一期 content 临时承载，单层收藏；social 收藏夹后续迁移」；
      文件头「说明：文章/草稿/书籍/章节/图片/阅读历史」补「收藏」
      【落地 2026-10-28】favorite 落地（L166-178）：uk_user_target + idx_target + idx_user_time；
      表注释注明一期临时承载/单层无分组/social 迁移口径；文件头补「/ 收藏」；
      设计原因段补 favorite 设计行（索引用途 + 迁移口径留痕）
- [x] `docs/Sql-Document.md` 第二模块新增第9张表；social 模块收藏夹章节补注
      「一期 content `favorite` 临时承载，social 立项后迁移/合并」
      【落地 2026-10-28】第二模块 L555-567 新增第9张表 + 设计原因补行；
      模块说明行「/ 收藏」同步（replace_all 3 处）；social 模块「# 4. 收藏夹表」补注 2 行
- [x] 新建 `enums/FavoriteTargetTypeEnum`：
      `ARTICLE(1, "文章")` / `BOOK(2, "书籍")`；
      `@EnumValue` 入库 code、`@JsonValue` 出参 desc，提供 `resolve(Integer code)` 白名单解析
      （请求 DTO 用 Integer，Service 统一 resolve，避免枚举反序列化歧义）
      【落地 2026-10-28】对齐 VisibilityEnum 模式 + ArticleCategoryEnum resolve 先例
      （非法值返回 null 由 Service 抛参数错误）；mvn compile 通过

#### B. 实体、Mapper 与只读状态组件

- [x] 新建 `domain/entity/Favorite.java`：
      `@TableName("favorite")`，字段 `id / userId / targetType / targetId / createTime`
      【落地 2026-10-28】对齐 ReadingHistory 实体风格（@Data + @Accessors + @TableId(AUTO)）
- [x] 新建 `mapper/FavoriteMapper.java`：
      ① `selectMyFavorites(Page<FavoriteItemVO> page, userId, targetType)`：
         自定义 @Select 左连 article / book，按 target_type 分支取 title/cover/summary/author_id，
         过滤 `del_flag=0`，`order by create_time desc, id desc`，SQL 层分页与 total；
         targetType 条件用 `<if>` 动态拼接（不要依赖 `? IS NULL` 的驱动行为）；
         投影到 VO 的 `targetType` 枚举依赖全局 MybatisEnumTypeHandler，
         若自定义映射不生效则改 Integer 并在 Service 转枚举；
         多参数方法用 @Param 显式命名
      ② 收藏状态查询可直接用 BaseMapper `selectCount`（uk_user_target 唯一键等值）
      【落地 2026-10-28】① 多态 join 用 case when 分支投影 + ON 条件判别 target_type，
      del_flag 过滤按 (target_type=1 and a.del_flag=0) or (target_type=2 and b.del_flag=0) 分支写；
      targetType 投影直接采用 Integer 路径（VO.targetType=Integer，注释说明不走枚举 TypeHandler）；
      ② 状态等值查询收口在 FavoriteStateSupport（LambdaQueryWrapper 防字段名字符串硬编码）
- [x] 新建 `service/support/FavoriteStateSupport.java`：
      只依赖 `FavoriteMapper`，提供 `isFavorited(userId, targetType, targetId)`；
      供 ArticleServiceImpl / BookServiceImpl 回填详情 VO 的 `favorited` 字段；
      `FavoriteServiceImpl#isFavorited` 也委托该组件，保持单一等值查询口径；
      **避免循环依赖**：详情 Service → FavoriteStateSupport → FavoriteMapper；
      FavoriteServiceImpl 另依赖 ArticleMapper/BookMapper/FollowRelationCache 做写入校验
      【落地 2026-10-28】isFavorited 用 uk 等值 selectCount 实现；
      B① Mapper 依赖的 FavoriteItemVO 随本节提前新建（domain/vo/FavoriteItemVO.java，
      即 C 节③，C 节执行时不再重复建）；mvn compile 通过

#### C. Service 层

- [x] 新建 `service/FavoriteService.java`：
      ① `favorite(Long userId, Integer targetType, Long targetId)`：收藏（幂等，重复收藏视为成功）
      ② `unfavorite(Long userId, Integer targetType, Long targetId)`：取消收藏（幂等，未收藏也成功）
      ③ `pageMyFavorites(Long userId, Integer targetType, long pageNo, long pageSize)`：
         我的收藏分页，targetType null=全部 / 1=文章 / 2=书籍，非法值抛 PARAM_ERROR
      ④ `isFavorited(Long userId, Integer targetType, Long targetId)`：收藏状态（匿名返回 false）
- [x] 新建 `service/impl/FavoriteServiceImpl.java`：
      ① 写入前目标校验（同域，按文章详情可见性口径；可抽轻量可见性 Support，
         避免直接调 getDetail 渲染正文/配图）：
         - 文章：`ArticleMapper.selectById` → del_flag=0；本人直接放行；
           非本人要求 status=2；可见性 PUBLIC 放行 / PRIVATE 拒 / FOLLOWERS 回查 FollowRelationCache
           （social 不可用时 fail-fast，不允许收藏自己看不到的内容）
         - 书籍：`BookMapper.selectById` → del_flag=0；status=TAKEN_DOWN 时仅作者可收藏
         - 目标不存在/不可见 → ARTICLE_NOT_FOUND / BOOK_NOT_FOUND
         - 允许作者收藏自己的作品（一期无收藏计数，不存在刷计数问题；后续加计数时再定）
      ② 收藏：insert；捕获 DuplicateKeyException 按成功处理（幂等）
      ③ 取消收藏：按 uk_user_target 条件 delete；影响行数 0 也按成功处理（幂等）
      ④ 我的收藏：校验 targetType 后走 FavoriteMapper 分页；空结果返回空页，不抛异常；
         分页用 `Page.of(pageNo, pageSize)`，不要走 PageQuery 默认 update_time 排序
         （SQL 已按 create_time desc 排序，避免叠加第二个 ORDER BY）
      ⑤ 收藏列表只过滤目标 `del_flag=1`；目标转私密/下架后的展示策略：
         本阶段保留在列表（用户自己收藏过），点击详情时按详情可见性兜底；
         如需列表直接隐藏，另立产品决策
      【落地 2026-10-28】① 可见性校验收口为私有方法 checkTargetReadable（checkArticleReadable
      对齐 getDetail 判定链：del_flag/发布态/三态可见性，FOLLOWERS 回查 FollowRelationCache
      且 social 不可用 fail-fast 上抛 REMOTE_SERVICE_ERROR；checkBookReadable：del_flag +
      TAKEN_DOWN 仅作者）；类型校验 requireValidType/resolveOptionalType 统一 resolve 白名单
      （非法抛 PARAM_ERROR）；②③ 幂等按清单口径；④ Page.of 裸分页 + pageNo/pageSize 手动钳制
      （Service 直收 long 入参不经过 PageQuery 兜底）；⑤ 列表过滤口径由 Mapper SQL 分支
      del_flag 条件承接；mvn compile 通过
- [x] 请求/出参对象：
      ① 新建 `domain/dto/FavoriteDTO.java`：`@NotNull Integer targetType`、
         `@NotNull @Positive Long targetId`
      ② 新建 `domain/dto/FavoriteQueryDTO.java`：`extends PageQuery`，可选 `Integer targetType`
      ③ 新建 `domain/vo/FavoriteItemVO.java`：
         `targetType / targetId / title / coverFileId / summary / authorId / favoriteTime`
         （文章 summary 取 article.summary；书籍 summary 取 book.intro）
      【落地 2026-10-28】①② jakarta validation + PageQuery 继承齐备；
      ③ 随 B 节提前新建（Mapper 编译依赖），targetType 定为 Integer（注释说明不走枚举 TypeHandler）

#### D. Controller 与详情状态适配

- [x] 新建 `controller/FavoriteController.java`，`@RequestMapping("/api/content/favorite")`：
      ① POST `/api/content/favorite` 收藏：@RequestBody @Valid FavoriteDTO，@SaCheckLogin，
         @RateLimiter(time=LIMIT_WINDOW_SECONDS, count=FAVORITE_MAX_COUNT, keyType=USER)
      ② DELETE `/api/content/favorite` 取消收藏：@RequestParam targetType/targetId，@SaCheckLogin，幂等
      ③ GET `/api/content/favorite` 我的收藏：FavoriteQueryDTO，@SaCheckLogin，分页
      ④ `GET /check` 收藏状态：@RequestParam targetType/targetId，@SaCheckLogin，返回 Boolean
      【落地 2026-10-28】对齐 ReadingHistoryController 风格（类头接口表/越权口径注释/
      StpUtil.getLoginIdAsLong）；DELETE 同挂 20/60s 限频（写操作同防刷）
- [x] 网关：`/api/content/**` 路由已覆盖；收藏接口必须登录，**不进**公开白名单
      【落地 2026-10-28】application.yml L161-165 复核：content 公开白名单仅 article/list、
      article/*、article/hot、category/**，favorite 不在名单，匿名不可访问，零改动
- [x] `domain/vo/ArticleDetailVO.java` 新增 `Boolean favorited`；
      `ArticleServiceImpl#getDetail` 末尾通过 `FavoriteStateSupport` 回填
      （viewerId=null 时为 false）；列表 VO 不逐条回填，避免 N+1（列表收藏状态由前端单独 check）
      【落地 2026-10-28】字段 + 注入 + 回填三处落地（return 链式 setFavorited）；
      列表 VO 未动（前端列表态走 GET /check）
- [x] `domain/vo/BookDetailVO.java` 新增 `Boolean favorited`；由阶段17 书籍详情回填
      （匿名 false；登录用户按 FavoriteStateSupport 查询）
      【落地 2026-10-28】字段已加，注释标明回填接线留阶段17
- [x] `constant/ContentLimitConstant.java` 新增 `FAVORITE_MAX_COUNT = 20`（60s，防刷收藏）
      【落地 2026-10-28】mvn compile 通过

#### E. 文档同步

- [x] `docs/Architecture/Content-Architeche.md` §一 边界行「不负责…收藏（social）」补注
      「一期 content 临时承载单层收藏，social 立项后迁移」；§2.1 功能清单新增
      「文章/书籍收藏（一期临时承载）」；
      §四 数据模型「8 表摘要」→「9 表摘要」并补 `favorite`；§三 接口清单补 `/favorite` 端点；
      注明 social 迁移
      【落地 2026-10-28】边界行/功能清单/9 表摘要+favorite 行/接口行/§3.1 限流矩阵补
      POST·DELETE /favorite 20 行（清单未点名，限流一致性延伸）六处齐改
- [x] `docs/overall_documen/Function-Document.md` content 功能清单新增收藏行；
      social 模块收藏夹行补「一期 content 临时承载」注记
      【落地 2026-10-28】L193 收藏行 + L213 收藏夹行注记
- [x] `docs/overall_documen/Design-Document.md` content 模块补 FavoriteService/表；
      social 模块收藏行补迁移注记
      【落地 2026-10-28】content 关键组件加 FavoriteService、数据模型加 favorite；
      social 主要职责行收藏夹处补注
- [x] `docs/Study/Content-Business-Guide.md` 新增「收藏（一期临时承载）」小节：
      收藏/取消/我的收藏/状态查询、单层收藏与 social 收藏夹的关系
      【落地 2026-10-28】插于第四节末（L427-454）：详细版/通俗版/代码清单/social 关系说明
- [x] `docs/temp/Content_Function_Develop.md` 功能表「不负责」行补注
      「一期收藏临时承载，长期归 social」；新增 §6.8 收藏（content 临时承载）
      【落地 2026-10-28】L20 边界行 + §6.8 六要点（归属/幂等/目标校验/列表/状态回填/限频）
- [x] `docs/Sql-Document.md` content 第9张表；social 收藏夹章节补迁移注记
      【A 节已同步 2026-10-28：第二模块 L555-567 第9张表 + 设计原因行 + social L649-651 补注，本节零重复改动】
- [x] `docs/next/Temporarily_not_implemented.md` 登记：
      social 收藏夹分组、佳句收藏、content→social 收藏迁移
      【落地 2026-10-28】新增「content 收藏迁移 social 与收藏夹扩展（迁自 temp.md 阶段16）」条目，
      ①迁移/合并方案 ②收藏夹分组+佳句 ③收藏计数，触发条件 social 立项
- [x] `docs/index/`：无 content 索引文件，确认无需改动
      【2026-10-28 复核：阶段15 G 节已 grep 确认无匹配，零改动】

#### F. 验证

- [ ] 编译：`mvn compile -pl litisle-content -am -q`（Win11 PowerShell）
- [ ] SQL 重建：docker init 后 content 第9张表 `favorite` 存在；
      `uk_user_target` / `idx_target` / `idx_user_time` 生效
- [ ] 回归点：
      ① 收藏文章/书籍成功；重复收藏仍一行（幂等）；取消收藏幂等
      ② 匿名调用收藏接口 401；未登录不能收藏
      ③ 收藏不存在/已软删/不可见文章 → ARTICLE_NOT_FOUND；不存在/已删/下架书籍 → BOOK_NOT_FOUND
      ④ 私有文章仅作者可收藏；关注者文章按 FollowRelationCache 判定；social 不可用时 fail-fast
      ⑤ 我的收藏：文章/书籍/全部 tab 倒序正确；空页；分页边界；targetType 非法 400
      ⑥ 目标软删后不出现在收藏列表；目标转私密/下架仍在列表但详情按可见性兜底
      ⑦ 文章详情 `favorited` 状态正确（登录/匿名）；书籍详情 `favorited` 随阶段17 验证；
         列表 VO 未被 N+1 拖慢
      ⑧ 限频 20/60s 生效；网关未放行匿名收藏
      ⑨ 全仓 grep 无 `favorite_folder` / `folder_id` 等 social 收藏夹字段误入 content
      ⑩ 越权：收藏/取消/列表/状态均以当前登录人 userId 为准，不接受前端传 userId；
         A 用户看不到 B 用户收藏
- [ ] 本清单执行状态更新；全部完成后随阶段16 整体转 archive

### 阶段17：书籍阅读闭环（章节列表 / 章节详情 / 上一章下一章 / 进入书籍看上次阅读）

> 目标：补齐「进入书籍 → 选章 → 读章节 → 下一章」链路。
> 现状：`BookDetailVO` / `BookChapterVO` 已定义但无人使用；`BookService` 只有
> create/update/delete/addChapter/removeChapter，没有书籍详情/章节列表/章节详情；
> `BookController` 没有 GET；阅读历史只有续读单章接口。
> 前置：阶段15（`ReadingHistoryService` / `getLastRead` / `recordBookRead`）、
> 阶段16（收藏 `favorited`）已落地或接口就绪。
> 明确不做：书籍发现入口（公开书籍列表/我的书籍）当前缺失，本阶段先按 ID 进入阅读；
> 书籍列表/搜索另立项（见文末「后续另立项」）。

#### A. 接口设计（三读端点）

- [x] `GET /api/content/book/{id}` 书籍详情（可选登录）→ `BookDetailVO`：
      书籍元数据 + `chapterCount` + `firstChapterId/No` + `latestChapterId/No`
      + `lastReadChapterId/No/Title/Time` + `favorited`
- [x] `GET /api/content/book/{id}/chapters` 章节列表（可选登录，分页）→ `PageResult<BookChapterVO>`：
      `chapterId / articleId / chapterNo / title`，按 `chapterNo` 升序；
      默认 `pageSize=50`（章节选择页），`PageQuery` 统一上限 100
- [x] `GET /api/content/book/{id}/chapter/{chapterId}` 章节详情（可选登录）→ `BookChapterDetailVO`：
      `bookId / bookTitle / bookAuthorId / chapterId / chapterNo / chapterTitle / chapterCount`
      + `article`（复用 `ArticleDetailVO`：正文/配图/作者/计数等）
      + `prevChapterId/No` + `nextChapterId/No`
- [x] 进入书籍的页面流：
      ① 并行调 `GET /book/{id}` 与 `GET /book/{id}/chapters`
      ② 详情页展示书名/封面/简介/章节总数；有 `lastReadChapterId` 时展示「继续阅读 第N章」
      ③ 点「继续阅读」或章节项 → `GET /book/{id}/chapter/{chapterId}`
      ④ 章节页「上一章/下一章」直接用详情返回的 ID，不再额外查目录
- [x] 章节详情不新增「下一章」独立端点：相邻章节信息随详情一次返回，少一次往返
- [x] 注意：章节详情内嵌 `article.favorited` 是「文章收藏」状态，不代表「书籍收藏」；
      书籍收藏看 `BookDetailVO.favorited`，前端不要混用
- [x] 书籍发现入口（书籍列表/我的书籍）缺失，本阶段不补；先由前端已有入口或后续阶段提供书籍 ID
      【设计定稿 2026-10-28·前提核对通过】① PageConstant.MAX_PAGE_SIZE=100 与「上限 100」一致，
      pageSize=50 为章节列表业务默认（Service 覆盖 PageQuery 默认 10）；
      ② BookController 现仅 5 个写端点（POST/PUT/DELETE/加章/退章），三新 GET 零冲突
      （/{id}/chapter/{chapterId} 与 DELETE 同路径靠方法区分）；
      ③ BookDetailVO 现状含未使用全量 chapters（B 节删）+ favorited 已就位（阶段16），新增字段全兼容

#### B. Mapper 与 VO

- [x] 不新增表；`book_chapter` 现有 `uk_book_chapter_no(book_id, chapter_no)` 已覆盖
      「按书 + 章节号排序」，`article` 走主键 join，无需新索引
- [x] `BookChapterMapper` 新增以下方法（统一带 `visibilities` 可见性范围参数，
      由 Service 按「作者/匿名/关注者/非关注者」计算；SQL 用 `<foreach>` 拼 IN；
      多参数方法用 @Param 显式命名；类头 javadoc 补可见性/分页/相邻章节查询说明）：
      ① `selectVisibleChapterPage(Page<BookChapterVO>, bookId, visibilities)`：
         join article，条件 `a.status=2 AND a.del_flag=0 AND a.visibility IN (visibilities)`，
         `order by bc.chapter_no asc`
      ② `countVisibleChapters(bookId, visibilities)`
      ③ `selectFirstVisible(bookId, visibilities)`：`chapter_no asc limit 1`
      ④ `selectLatestVisible(bookId, visibilities)`：`chapter_no desc limit 1`
      ⑤ `selectVisibleChapter(bookId, chapterId, visibilities)`：校验当前章节可见
      ⑥ `selectPrevVisible(bookId, chapterNo, visibilities)`：`chapter_no < ? desc limit 1`
      ⑦ `selectNextVisible(bookId, chapterNo, visibilities)`：`chapter_no > ? asc limit 1`
      ⑧ `existsFollowersChapter(bookId)`：是否存在 `status=2 AND del_flag=0 AND visibility=3`
         的章节，用于决定是否需要回查关注关系（避免每个登录用户都打 social）
      【落地 2026-10-28】①-⑦ 统一 text block + `<script>` + `<foreach>` 模板
      （status=2/del_flag=0/visibility=3 硬编码均带枚举 code 注释），投影统一 BookChapterVO
      （bc.id→chapterId / bc.article_id→articleId / bc.chapter_no→chapterNo / a.title→title）；
      ⑧ 用 select exists(select 1 ...) 返回 Boolean；visibilities 参数 Collection&lt;Integer&gt;；
      类头 javadoc 补可见性/分页/相邻章节/关注章节存在性四组说明
- [x] 复用阶段15 的 `BookChapterMapper#selectByArticleId(articleId)`（原样保留，类头已述）
- [x] `BookDetailVO` 调整：
      删除未使用的全量 `chapters` 字段；新增
      `chapterCount / firstChapterId / firstChapterNo / latestChapterId / latestChapterNo / lastReadChapterId / lastReadChapterNo / lastReadChapterTitle / lastReadTime`
      （`favorited` 字段阶段16 已新增，本阶段只负责回填）；
      类注释同步：不再含全量目录，章节改走分页接口
      【落地 2026-10-28】chapters + List import 已删，9 字段齐加（各带口径注释），
      favorited 注释补「与 article.favorited 区分」
- [x] 新建 `domain/vo/BookChapterDetailVO.java`：
      `bookId / bookTitle / bookAuthorId / chapterId / chapterNo / chapterTitle / chapterCount / article(ArticleDetailVO) / prevChapterId / prevChapterNo / nextChapterId / nextChapterNo`
      【落地 2026-10-28】12 字段齐备，article 字段注释带 favorited 语义警示
- [x] `BookChapterVO` 类注释「BookDetailVO.chapters 列表项」→「章节列表接口出参」
- [x] `BookChapterVO` 沿用现有字段；如前端需要「上次读到」标记，由详情页的
      `lastReadChapterId` 对比，不在列表逐项回填（避免额外字段/查询）
      【落地 2026-10-28】类注释补 lastReadChapterId 对比口径；mvn compile 通过

#### C. Service 层

- [x] `service/BookService.java` 新增：
      ① `BookDetailVO getDetail(Long bookId, Long viewerId)`
      ② `PageResult<BookChapterVO> pageChapters(Long bookId, Long viewerId, long pageNo, long pageSize)`
      ③ `BookChapterDetailVO getChapterDetail(Long bookId, Long chapterId, Long viewerId)`
      类头方法索引与职责边界同步补「章节阅读编排」
      【落地 2026-10-28】3 方法齐加（javadoc 含异常矩阵）；类头索引 3 行 +
      职责边界改「章节阅读编排（详情/列表/章节详情的可见性计算与组装）」
- [x] `service/impl/BookServiceImpl.java`：
      ① 抽私有 `requireReadableBook(bookId, viewerId)`：
         `bookMapper.selectById` → `del_flag=0`；`status=TAKEN_DOWN` 且非作者 → `BOOK_NOT_FOUND`；
         作者可看自己下架书（用于作者自查，不对外暴露）
      ② 抽私有 `resolveChapterVisibilities(book, viewerId)`：
         - viewer == book.authorId → `[1,2,3]`（作者看自己的全部可见性）
         - viewer == null → `[1]`
         - 登录非作者：先 `existsFollowersChapter`；无 3 号章节 → `[1]`；
           有 → `FollowRelationCache#isFollowing(book.authorId, viewerId)`：
           关注 → `[1,3]`；未关注 → `[1]`；
           social 不可用时 catch 后降级 `[1]` 并 warn（列表不因 social 抖动整体失败）
      ③ `getDetail`：
         - requireReadableBook → resolveChapterVisibilities
         - `countVisibleChapters` / `selectFirstVisible` / `selectLatestVisible`
         - viewerId=null 时跳过 getLastRead，lastRead* 全 null；
           登录时 `getLastRead(viewerId, bookId)` → 用 `selectVisibleChapter(bookId, chapterId, visibilities)`
           复核可见性；有历史且章节可见 → 回填 lastRead*；否则置 null（不阻断详情）
         - `favoriteStateSupport.isFavorited(viewerId, BOOK, bookId)` → favorited
         - 组装 BookDetailVO；无章节时 count=0、first/latest/lastRead 均 null
      ④ `pageChapters`：
         - requireReadableBook → resolveChapterVisibilities
         - 走 `selectVisibleChapterPage`；空页正常返回
      ⑤ `getChapterDetail`：
         - requireReadableBook
         - `bookChapterMapper.selectById(chapterId)`；不存在或 `bookId` 不匹配 →
           `BOOK_CHAPTER_MISMATCH`
         - `articleService.getDetail(chapter.articleId, viewerId)`：
           复用文章可见性判定链（status/visibility/配图渲染）；不可见抛 `ARTICLE_NOT_FOUND`
         - 计算 visibilities；`countVisibleChapters` + `selectPrevVisible` / `selectNextVisible`
         - 组装 BookChapterDetailVO（chapterTitle 取 article 标题）
      ⑥ 不在 Service 内写浏览计数/历史（HTTP 信息与副作用放 Controller/Support，见 D/E 节）
      【落地 2026-10-28】① DelFlag 枚举判定对齐 requireOwnedBook；② 可见性 code 全走
      VisibilityEnum 枚举取值（无字面量）；③④⑤ 六步注释齐备，分页 Page.of 钳制 +
      PageResult.of 工厂；新增注入 ArticleService/ReadingHistoryService/FavoriteStateSupport/
      FollowRelationCache（依赖单向无循环）；⑥ getChapterDetail 注释显式声明副作用不在此处
- [x] `ReadingHistoryService#getLastRead`（阶段15）返回值需含
      `chapterId / chapterNo / chapterTitle / lastReadTime`，供 getDetail 直接回填；
      章节已删/文章已删时返回 null
      【核对 2026-10-28】ReadingHistoryVO 四字段齐备（阶段15 C 节落地），getDetail 已按
      selectVisibleChapter 复核口径消费，无需改动
- [x] `FavoriteStateSupport#isFavorited`（阶段16）供 BookDetailVO 回填 `favorited`
      【核对 2026-10-28】阶段16 D 节已就绪，getDetail 步骤5 直接复用；mvn compile 通过

#### D. Controller 层

- [x] `controller/BookController.java` 新增三个 GET（可选登录，不强制 @SaCheckLogin）：
      ① `GET /{id}`：`Long viewerId = StpUtil.isLogin() ? StpUtil.getLoginIdAsLong() : null;`
      ② `GET /{id}/chapters`：`BookChapterQueryDTO`（`extends PageQuery`；
         构造器调用 `setPageSize(50)` 设默认值，不要重复声明同名字段——
         `PageQuery#normalizePageSize` 读父类字段；仍受 PageConstant.MAX_PAGE_SIZE=100 钳制）；
         分页构造用 `Page.of(...)` 或 `toMpPage(false, OrderItem.asc("chapter_no"))`，
         不要走 PageQuery 默认 update_time 排序（会与 SQL 的 chapter_no 排序叠加）
      ③ `GET /{id}/chapter/{chapterId}`：`HttpServletRequest` 取 IP/UA 供浏览计数
      【落地 2026-10-28】三 GET 齐加（类头接口表 3 行 + @Operation 匿名口径）；
      ② DTO 用实例初始化块 setPageSize(50)（不重复声明字段），排序在 Service SQL 收口
      （Controller 只传页参，不走 toMpPage——避免与 SQL chapter_no 排序叠加）；
      ③ 调 getChapterDetail 后 recordChapterRead（articleId 取 detail.getArticle().getId()）
- [x] 章节详情成功后调用 `BookReadSupport#recordChapterRead(...)`：
      浏览计数 + 书籍历史落库，均 fail-safe；不影响章节响应
      【落地 2026-10-28】随 Support 新建一并接线（见 E 节）
- [x] 类头接口表补三行 GET；Swagger `@Operation` 描述「匿名可读/登录带上次阅读」
      【落地 2026-10-28】接口表 3 行齐加；越权口径补「读端点可选登录/写端点不放宽」
- [x] 抽 `ClientIpSupport`（或 `WebRequestSupport`）：
      把 `ArticleController#resolveClientIp` 的 X-Forwarded-For 首段逻辑收口复用，
      `ArticleController` 与 `BookController` 共用，避免两份 IP 解析
      【落地 2026-10-28】service/support/ClientIpSupport 新建；ArticleController 私有方法删除
      （留迁移注释）改注入调用；BookController 同注入
- [x] `litisle-gateway/src/main/resources/application.yml` 公开白名单新增
      （Sa-Token 用 `*` 单段通配，不支持 Spring `{id}` 语法）：
      `/api/content/book/*`（书籍详情）
      `/api/content/book/*/chapters`（章节列表）
      `/api/content/book/*/chapter/*`（章节详情）
      注意：白名单只跳过网关 JWT 校验，写端点（PUT/DELETE/POST chapter）仍由
      Controller `@SaCheckLogin` 保护，不得因通配放宽
      【落地 2026-10-28】三条齐加（L166-169）+ 安全项注释（写端点仍登录）+
      通配覆盖层级说明（book/* 不覆盖多层路径）
- [x] 书籍写端点（创建/编辑/删除/挂章/退章）保持 `@SaCheckLogin` 不变
      【核对 2026-10-28】五个写端点注解原样未动；mvn compile 通过

#### E. 阅读副作用（浏览计数 + 历史）

- [x] 新建 `service/support/BookReadSupport.java`：
      ① `recordChapterRead(bookId, chapterId, articleId, bookAuthorId, viewerId, ip, ua)`：
         - 浏览计数：所有访问者都记（含匿名/作者本人），复用 `ViewCounterSupport#recordView`
         - 阅读历史：仅 `viewerId != null && !viewerId.equals(bookAuthorId)` 时记，
           调用 `ReadingHistoryService#recordBookRead(viewerId, bookId, chapterId)`（显式 book 上下文）
         - 两步各自 try-catch 只记日志，任一失败不影响章节响应
      ② 与 `ArticleController#detail` 的浏览计数口径保持一致（PV+去重窗口，Redis 异步刷回）
      【落地 2026-10-28】随 D 节接线一并新建（D② 编译前置）：两步独立 try-catch、
      类头注释双口径（计数含匿名/作者、历史仅登录非作者）
- [x] 章节详情端点不再调用 `/article/{id}` 详情，避免章节正文被 article 自动落历史逻辑
      重复记账；书籍历史只由 `recordBookRead` 写入
      【核对 2026-10-28】getChapterDetail 走 Service 组装（articleService.getDetail 为
      Service 内部调用，不带 ArticleController#detail 的 recordView/recordArticleHistory
      Controller 层副作用）；历史只由 BookReadSupport#recordBookRead 写
- [x] 若前端仍用 `/article/{id}` 读章节，阶段15 的自动归属逻辑继续生效；
      两条路径共用 `uk_user_biz`，不会产生重复行；但前端不要同时调章节详情和文章详情，
      否则浏览计数会按两次 PV 计（历史仍幂等一行）
      【核对 2026-10-28】设计说明条目，代码已满足；口径已写入 §6.9（G 节）供前端知悉

#### F. 可见性与边界

- [x] 书籍：软删 → `BOOK_NOT_FOUND`；`TAKEN_DOWN` → 非作者 `BOOK_NOT_FOUND`，作者可读
      【核对 2026-10-28】requireReadableBook（C 节）两分支齐备，delFlag 走 DelFlag 枚举判定
- [x] 章节：不属于该书 → `BOOK_CHAPTER_MISMATCH`
      【核对 2026-10-28】getChapterDetail 步骤2：chapter==null || !bookId.equals → MISMATCH
- [x] 章节文章：未发布/已删/可见性拦截 → `ARTICLE_NOT_FOUND`（由 `getDetail` 统一抛）
      【核对 2026-10-28】章节场景书作者=文章作者（挂载校验保证），viewerId 判定走
      article.authorId 与书籍口径一致；getDetail 判定链原样复用
- [x] 空书：`chapterCount=0`，first/latest/lastRead 全 null；章节列表空页；前端显示「暂无章节」
      【核对 2026-10-28】getDetail hasChapters 分支（count=0 跳过首尾查询）；pageChapters
      SQL 分页空页正常返回（不抛异常）
- [x] 上次阅读章节不可见/已删：书籍详情不返回 lastRead（置 null），前端回退第一章；
      不物理删历史行（阶段15 口径）
      【核对 2026-10-28】getDetail 步骤4 selectVisibleChapter 复核，不可见置 null；
      历史行无任何删除操作
- [x] 上一章/下一章只取「当前 viewer 可见」的相邻章节，禁止直接按 chapter_no 取相邻后
      让用户点进 404；跨过不可见章节时自动跳到下一个可见章节
      【核对 2026-10-28】selectPrev/NextVisible 均带 visibilities IN 过滤（SQL 层跳过不可见章）
- [x] 章节被退章/重排：当前章节行缺失时详情报 `BOOK_CHAPTER_MISMATCH`；
      列表按最新 chapter_no 排序，不保留旧序号
      【核对 2026-10-28】退章物理删挂载行（selectById null → MISMATCH）；
      列表 order by bc.chapter_no asc 实时取最新序号
- [x] 作者读自己的章节：不落历史（阶段15 自读口径），但浏览计数仍记
      【核对 2026-10-28】BookReadSupport 步骤2 viewerId.equals(bookAuthorId) 过滤；
      步骤1 计数无过滤
- [x] 匿名读章节：不落历史，浏览计数仍记
      【核对 2026-10-28】BookReadSupport 步骤2 viewerId==null 短路；步骤1 匿名照记
      （去重走 ip+ua 基准）
- [x] visibility=3 依赖 social 关注关系：
      social 未实现时章节列表降级为仅公开章节；章节详情仍走现有 `getDetail` fail-fast 口径，
      不在本阶段补 social
      【核对 2026-10-28】resolveChapterVisibilities：social 不可用 catch BizException 降级 [1]+warn；
      关注章节存在性判定 existsFollowersChapter 前置省回查；详情层 getDetail 独立判定不受影响
- [x] 书籍详情 `favorited`：匿名 false；登录按阶段16 收藏表查询
      【核对 2026-10-28】getDetail 步骤5：viewerId!=null && favoriteStateSupport.isFavorited；
      F 节为纯核对条目，无代码改动

#### G. 文档同步

- [x] `docs/Architecture/Content-Architeche.md` §2.1 功能清单新增
      「书籍阅读闭环（章节列表/章节详情/上一章下一章/上次阅读）」；
      §三 接口清单补三个 GET；§2.4 核心流程补「进入书籍→选章→读章→下一章」
      【落地 2026-10-28】§2.1 收藏行后加书籍阅读闭环行；§2.4 推荐流段后加流程块
      （D1/D2 并行调、visibilities 计算、C1-C3 读章三步）；§三 接口清单补 GET 行
- [x] `docs/overall_documen/Function-Document.md` content 模块补书籍阅读端点与流程
      【落地 2026-10-28】收藏行后加「书籍阅读」功能行
- [x] `docs/overall_documen/Design-Document.md` content 模块补 BookService 新方法与 VO
      【落地 2026-10-28】数据模型行后加「章节阅读编排（阶段17）」行（三读方法/visibilities/
      BookReadSupport/两 VO）
- [x] `docs/Study/Content-Business-Guide.md` 第四节补：
      章节列表分页、章节详情聚合 ArticleDetailVO、上一章/下一章、进入书籍看上次阅读；
      代码清单补 BookChapterDetailVO、BookReadSupport、BookChapterMapper 新方法
      【落地 2026-10-28】收藏小节后插「书籍阅读闭环（阶段17）」小节（详细版/通俗版/代码清单三项）
- [x] `docs/temp/Content_Function_Develop.md` 新增 §6.9 书籍阅读闭环
      【落地 2026-10-28】§6.8 后插 §6.9 六要点（三读端点/visibilities/正文复用/副作用/上次阅读/空书）
- [x] `docs/Sql-Document.md` 补 `book_chapter` 索引说明（uk_book_chapter_no 支撑按书排序）；
      无新表
      【落地 2026-10-28】book_chapter 表定义后加索引说明 3 行（uk 排序+防重双职责/
      idx_article_id 反查挂载）；无新表确认
- [x] `litisle-gateway` 配置注释补三条公开白名单及「写端点仍登录」说明
      【D 节已同步 2026-10-28：application.yml L166-173 三条白名单 + 安全项注释 + 通配层级说明，零重复改动】
- [x] `docs/next/Temporarily_not_implemented.md` 登记：
      书籍发现入口/公开书籍列表/我的书籍、章节级阅读足迹、书籍搜索
      【落地 2026-10-28】新增「书籍阅读配套缺口（迁自 temp.md 阶段17）」条目三项各带触发条件
- [x] `docs/index/`：无 content 索引文件，确认无需改动
      【2026-10-28 复核：无匹配，零改动】

#### H. 验证

- [ ] 编译：`mvn compile -pl litisle-content -am -q`（Win11 PowerShell）
- [ ] 回归点：
      ① 书籍详情：匿名可读；作者可读自己下架书；非作者读下架书 404；
         chapterCount/first/latest 正确；无章节时全 null
      ② 章节列表：按 chapterNo 升序；分页正确；空页；作者可见私有/关注者章节；
         匿名仅公开；登录非关注者仅公开；关注者可见公开+关注者（social 可用时）
      ③ 章节详情：正文/配图正确；prev/next 只指向可见章节；跨过不可见章节正确；
         章节不属于该书 400；文章不可见/已删 404；空书/末章 next=null
      ④ 上次阅读：登录用户读书后再次进入书籍详情返回 lastRead；章节不可见时置 null；
         匿名不返回 lastRead
      ⑤ 阅读历史：章节详情读后落 biz_type=1 行且 chapter_id 正确；重复阅读一行、
         update_time 刷新；匿名/作者自读不落历史
      ⑥ 浏览计数：章节详情访问后 view_cnt 待刷回增量增加；flush 后 article.view_cnt 增加；
         匿名/作者访问也计数（与文章详情口径一致）
      ⑦ 收藏：书籍详情 `favorited` 正确；匿名 false
      ⑧ 网关：匿名可访问三个 GET；未登录写端点仍 401
      ⑨ 全仓 grep 无新增 `/book/{id}/chapter` 与 `ArticleController#detail` 的重复历史写入路径
- [ ] 本清单执行状态更新；全部完成后随阶段17 整体转 archive

#### 后续另立项（不在阶段17）

- [ ] 书籍发现入口：公开书籍列表（分页/分类/热度）、我的书籍、书籍搜索
- [ ] 文章或书籍的阅读足迹（书籍需要包括最近阅读章节），不要阅读进度百分比
- [ ] 书籍收藏数/收藏夹分组（阶段16 已做单层收藏，计数与分组随 social）
- [ ] 章节评论/段评（social 评论落地后接入）
- [ ] 推荐流热度榜骨架，目前是全量查询，效率极低，必须优化
- [ ] 文章或书籍，添加了点赞功能

### 后续另立项（推荐方案整理，2026-10-28；不在阶段17，按优先级排序）

> 整理口径：每条给推荐方案 + 归属 + 优先级；content 侧可独立开工的进阶段18 候选，
> 依赖 social/search 的标「外部依赖」；重复项与已登记项合并（避免与
> Temporarily_not_implemented.md 重复登记，此处保留执行视角）。

- [x] **P1 发布异常保稿（content，独立可开工）**——方案判断结论：
      **推荐方案B（发布前置草稿 UPSERT）为主 + 不需要方案A 的异常补偿**：
      - 方案A（异常后 catch 补存草稿）：失败点分散（A1 校验/A3 CAS/图片校验），
        每个失败点都要包 try-catch 才能兜住，补偿逻辑易漏
      - 方案B（发布链路第一步先把请求内容 UPSERT 到 article_draft 单行表，再走校验/流转）：
        一个入口一个动作、幂等（每草稿仅一行）、任何一步失败内容必已在草稿——
        与现状 article_draft 单行 UPSERT 设计完全同构，零新表
      - 现状补充：前端发布前通常已有 10s 节流自动保存草稿，方案B 是后端兜底
        （不依赖前端行为），两者叠加后「内容必不丢」
      - 落地要点：①发布接口入口（校验前）先 `draftUpsert(请求内容)` fail-safe（落失败仅 warn 不阻断发布主链路）；
        ②图片异常指明报错图片：图片校验/落库失败时错误信息携带 `file_id + position_order`
        （「第 N 张图片(file_id=xxx) 校验失败」），前端可定位到编辑器对应位置；
        ③发布成功后草稿行仍保留 article_id 回填（现状口径不变）
      - **「发布已创建文章」分支结论（2026-10-28 补全，方案B 机制统一化）**：
        按发布请求是否携带正文分两种形态，不做无差别快照：
        - 纯流转（请求仅 articleId/visibility/批次，不带正文）：内容已在 article 建档行
          （status=0 草稿态 / 3 驳回态），校验拒绝/CAS 并发失败/图片校验失败时
          行原样保留——**内容天然不丢，零额外动作**（多落一次草稿快照是纯冗余）
        - 携带内容修改的发布（发布时顺带改稿）：失败时修改内容丢失（行里还是旧内容）
          ——唯一缺口，统一走①的 draftUpsert 快照再流转
        - 判定规则一句话：**凡发布请求携带正文 content → 入口先 draftUpsert；
          不携带 → article 建档行天然保稿，不落快照**。
          编辑驳回文章走编辑接口（updateById 原子，失败行不丢），与本机制解耦
      - **【落地 2026-10-28】**（编译验证 `mvn compile -pl litisle-content -am -q` 通过 ✓）：
        - 方案B 落地：新建 [PublishDraftSnapshotSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/PublishDraftSnapshotSupport.java)
          （REQUIRES_NEW 独立事务——主链路校验失败回滚不连带快照回滚；调用方 fail-safe try-catch 仅 warn 不阻断），
          ArticleServiceImpl#createAndPublish 入口（所有校验前）先快照；定位口径对齐 DraftServiceImpl.locateDraft：
          draftId 非空覆盖本人草稿行（非本人/已删则新建独立兜底行防越权）、为空新建兜底行；
          **不搬运图片行/file_ref**（正文占位符 {img:file_id} 已含 fileId 锚点，发布失败时归属切换未执行、
          原草稿图行未被触碰，快照重挂图行反引入新建兜底行的图行/引用残留清理联动）
        - 纯流转分支零动作核实：publishBatch/submit（article 行 status=0/3 天然保稿）、
          saveDraftRow（建档行）/update（编辑原子）均未改动
        - 图片异常定位：ArticleImageRefSupport#checkOwnership 改按 images 原列表遍历，
          报错携带「第 N 张图片(file_id=xxx)」（position_order+file_id，前端定位编辑器位置）；
          #checkCoverOwnership 携带封面 file_id；ArticleServiceImpl#aggregatedImgHash 指纹缺图携带缺失 fileId 列表；
          文案模板收口 ContentBizConstant（IMG_CHECK_FAIL_MSG / COVER_CHECK_FAIL_MSG / IMG_HASH_MISSING_MSG），
          草稿保存链路共用 checkOwnership 同步受益
        - **发布回填补齐（【落地 2026-10-29】，编译验证通过 ✓）**：核实发现「③发布成功后 article_id 回填」
          历史代码从未实现（文档口径写了、代码缺失；P1 快照使缺口显性化——直发场景快照新建的兜底行
          发布成功后成为 article_id=null 游离行，用户草稿列表出现与已发布文章同内容的「无名草稿」）；
          按用户确认「补回填」方案补齐：snapshotForPublish 返回值 void→Long（返回实际写入的草稿行ID，
          覆盖行=原行ID/新建兜底行=新行ID），ArticleServiceImpl#createAndPublish 文章建档
          （articleMapper.insert 拿到 id）后主事务内回填 article_draft.article_id——草稿行保留带已发布标识
          （前端可据 article_id 展示「已发布」或过滤），直发兜底行不再游离；回填随主事务提交，
          后续任一步失败（免审提示/配图登记/CAS 并发）事务回滚连带回填回滚，草稿行回到未关联状态
          （保证「仅发布成功才带已发布标识」），与 REQUIRES_NEW 已提交的快照内容互不影响
- [x] **P1 推荐流热度榜增量优化（content，独立可开工）**——现状：灌榜 R1 每 5min 全量拉
      zone 1/2 候选集（随文章量线性膨胀）；推荐方案（增量水位 + 低频全量校正混合）：
      - 高频增量（5min）：Redis 记灌榜水位 `last_refill_watermark`（上次灌榜时间），
        每轮只拉 `update_time > 水位` 的变更文章（新发布/编辑/计数回写）重算 ZADD，水位前推
      - 低频全量（1h）：全量重算一轮兜底分数衰减（增量只升不降，衰减靠全量轮收敛），
        可错峰到低峰时段执行
      - 出榜剔除维持现状（recheckFilter 展示前回查 + 下架 ZREM 事件）不变
      - 预期：候选集查询从「全量」降到「增量delta」，DB 压力随变更量而非存量走
      - **【落地 2026-10-28】**（编译验证通过 ✓；口径修正：用户先选「水位+浏览刷回事件联动」混合方案，
        后核实 article.update_time 为 **ON UPDATE CURRENT_TIMESTAMP**（DB 层维护）——浏览量自增/
        计数回写/CAS/编辑等任何 UPDATE 均刷新 update_time，纯水位即可完整覆盖全部变更源，
        事件联动与灌榜同为 5min 周期无时延收益，按事实修正回到**纯水位口径**，效果等同混合方案）：
        - [FeedHotRankSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java)：
          新增 `refillAuto()` 编排三分支——①正式榜缺失→全量重建（防增量 ZADD 造残缺榜）；
          ②距上次全量 ≥ `fullRefillIntervalMs`（默认 1h）→全量校正衰减收敛；③其余轮→增量；
          新增 `refillDelta()`：读增量水位→捞 `update_time ≥ 水位`（含 5s 安全回退兜时钟偏差，
          重复 ZADD 幂等）榜条件变更文章→算分 ZADD 现有榜→`ZREMRANGEBYRANK` 裁剪保 TopN
          →水位前推+榜 TTL 续期；ZADD 失败水位不推（下轮重捞），水位键缺失/值非法走全量兜底；
          全量/增量共用 `rankCandidateQuery`/`computeScores`（消除重复）；
          水位键：`CONTENT_FEED_HOT_ZSET + ":delta-watermark"/":full-watermark"`（epoch millis）
        - [RedisUtil.java](../../litisle-common/common-redis/src/main/java/com/litisle/commonredis/util/RedisUtil.java)：
          补通用 `zRemoveRangeByRank`（ZREMRANGEBYRANK，榜尾裁剪用）
        - [ContentProperties.java](../../litisle-content/src/main/java/com/litisle/litislecontent/config/ContentProperties.java)：
          Hot 加 `fullRefillIntervalMs`（默认 3600000=1h，yml 未配走默认）
        - [FeedHotRankRefillJob.java](../../litisle-content/src/main/java/com/litisle/litislecontent/job/FeedHotRankRefillJob.java)：
          `refillHotRank()` → `refillAuto()`，注释同步
        - 出榜剔除维持现状（核实：现状无「下架 ZREM 事件」实现，实际口径=recheckFilter 展示前回查
          + 全量轮换榜自然不写=出榜；增量轮不换榜不清存量，沿用同口径）
        - D10 接线点（updateCounters 回写后即时 ZADD）保持登记不落地——增量已覆盖，随 social 联调再评估
- [x] **P2 书籍发现入口（content，独立可开工；阶段18 候选）**：
      ① 公开书籍列表：status != TAKEN_DOWN && del_flag=0，分页 + category 过滤，
      排序一期 create_time/update_time 倒序（book 无计数冗余列，热度排序待 ③ 计数落地）；
      ② 我的书籍：author_id 硬收口（复用 my 文章列表模式）；
      ③ 书籍搜索：search 模块扩展书籍索引为外部依赖，一期不做 SQL LIKE 兜底
      （无索引全表扫，书籍量小可接受；量大再加 title 前缀索引）
      - **【落地 2026-10-28】**（编译验证通过 ✓；①② 落地、③ 维持外部依赖不落地）：
        - 新建 [BookQueryDTO](../../litisle-content/src/main/java/com/litisle/litislecontent/domain/dto/BookQueryDTO.java)
          （继承 PageQuery，category 精确过滤；匿名端点 sortBy 仅通用时间列白名单）/ 
          [BookMyQueryDTO](../../litisle-content/src/main/java/com/litisle/litislecontent/domain/dto/BookMyQueryDTO.java)
          （category/status/keyword）/ [BookCardVO](../../litisle-content/src/main/java/com/litisle/litislecontent/domain/vo/BookCardVO.java)
          （无章节明细；book 无计数冗余列，热度字段待计数落地扩展）
        - BookService/BookServiceImpl 新增 `listPublic`（status ne TAKEN_DOWN + del_flag eq NOT_DELETED 硬编码，
          与 requireReadableBook 下架口径一致；排序走 PageQuery 默认 update_time 倒序——
          清单「create_time/update_time 倒序」由 PageQuery 默认排序三级优先级收口，sortBy 白名单内可传时间列）
        - `listMy`（author_id=当前登录人硬收口 + 未软删（软删书不展示，回收站概念未立项）；
          含下架书作者自查；keyword 书名模糊——idx_author_id 前缀过滤后量小；复用文章 my 编排模式）
        - BookController 新增 `GET /book/list`（匿名）+ `GET /book/my`（@SaCheckLogin）；
          网关零改动——`/api/content/book/*` 一层通配已覆盖 /book/list（写端点 @SaCheckLogin 兜底不受通配影响）
        - ③ 书籍搜索：维持 search 模块外部依赖，一期不做（清单口径不变）
- [x] **P2 阅读足迹（前端展示为主，content 零改动）**——阶段15 已覆盖：
      reading_history 单表双类型（书籍历史含最近章节 chapter_id/chapter_no/chapter_title、
      文章历史），无阅读进度百分比字段 ✓；本条实为前端「足迹页」展示需求
      （历史列表分页接口已有 /reading-history），content 无新开发；
      若后续要「章节级足迹」明细，按阶段15 待定项另建明细表（不动主表）
      - **【核实留痕 2026-10-28】**：content 侧确认零改动——历史列表分页接口
        （GET /reading-history，阶段15 落地）已支撑「足迹页」展示；
        本条剩余工作为纯前端需求，不涉及 content 开发，清单勾选关闭
- [x] **P3 书籍收藏数/收藏夹分组（social 依赖）**：分组/佳句/计数口径随 social 立项统一定
      （favorite_folder/favorite_item 结构决定计数实现），content 不预埋；
      若书籍列表需要按收藏数排序/展示，届时 book 加 favorite_cnt 冗余列
      （异步刷新，对齐 article 计数模式）——触发条件：social 立项 或 书籍热度排序需求
      - **【核实留痕关闭 2026-10-28】**：litisle-social 仅启动类骨架（零业务代码），
        favorite_folder/favorite_item 仅 Sql-Document.md 设计稿未落地；content 一期 favorite
        单层收藏已临时承载（表注释已留「social 收藏夹立项后迁移 favorite_item 或另定合并方案」口径）；
        favorite_cnt 触发条件均未满足（social 未立项 + 书籍一期时间倒序排序无热度需求）——
        维持不预埋，待 social 立项触发
- [x] **P3 章节评论/段评（social 依赖）**：social 评论以 target_type 区分文章/章节评论，
      章节正文=article（book_chapter.article_id 归属），评论挂 article_id 即可，
      content 侧零改动；content 唯一可能的工作是章节详情 VO 加 comment_cnt 展示
      （随 social 评论联调时定）
      - **【核实留痕关闭 2026-10-28】**：comment 表仅设计稿，social 评论未立项；
        article.comment_cnt 冗余列已建（恒 0）、热度公式 comment 权重已留（阶段14）；
        章节 VO comment_cnt 展示无数据源（恒 0 无展示意义）——待 social 评论联调时再定
- [x] **P3 点赞功能（social 依赖）**：点赞本身归 social 立项（like 表/去重/计数）；
      content 侧工作 = 计数回写消费端（social 点赞事件 → content 更新 article.like_cnt /
      book 点赞数冗余列），事件矩阵接线点已留（阶段14 D10 updateCounters TODO），
      随 social 联调落地；推荐流热度榜 like_cnt 权重自动生效（阶段14 已留）
      - **【核实留痕关闭 2026-10-28】**：like_record 仅设计稿，social 点赞未立项（无事件源）；
        D10 接线点已登记 [Temporarily_not_implemented.md](../../next/temporarily/Temporarily_not_implemented.md)
        social 回收项；P1 热度榜增量已核实 update_time 为 DB ON UPDATE 维护——social 计数回写
        触发的 UPDATE 会自动进下一轮增量灌榜，content 消费端仅剩 D10 即时重算增强项——
        待 social 联调落地
- [ ]（预留位，无内容）

### content 模块缺口修复批次 Q1~Q5（2026-10-30 归档；迁自 docs/temp/temp.md「内容域缺口修复批次·content模块」节，原样搬运）

> **来源**：迁自 docs/temp/temp.md content 模块节（2026-10-30 全部完成后归档；含来源说明/口径修正/dfa 方案影响核查与逐项完成登记，内容零修改）
> **背景**：dfa 同步闸门批次2/批次3 完成后启动 content 适配（Q1 依赖 DfaFacade 门面=AC 引擎产物，dfa 批次记录暂留 docs/temp/temp.md 未归档）；Q2~Q5 与 DFA 无关可独立执行
> **挂起/预留项**：无（Q1~Q5 全部落地，无挂起项迁入 Temporarily 清单）

#### content模块，待dfa批次2（③）+批次3（⑥）完成后修改（暂不考虑；Q2 类与 DFA 无关可随时并行）

> 来源：Content_Function_Develop.md 文档核查发现两个缺口（用户确认修复口径）。
> 口径修正说明：用户先选「摘要纳入机审」，后经方案对比（A1 新摘要立即生效有暴露窗口 / A2 待生效回写需新基建 / 完整异步机审须跨 content+review 契约改造）修正为下述方案1——已发布可单独改摘要 + 同步 DFA 闸门，风险面与摘要（≤500 字已过审正文的派生文本）匹配，留 2b 独立送审单元升级路径。
> dfa 方案影响核查（2026-10-29）：content 审核 4 个接触点（发布/重提、preview-check、标签闸门、currentVersion×4 处）全部引擎无关、无 hitWords 消费——dfa 四批次对 content **零代码改动、零编排重排**；Q1 实现时直接调用改造后 DfaFacade 门面（届时=AC 引擎），零额外适配。

- [x] **Q1 摘要 DFA 同步闸门（已发布可单独改摘要，2026-10-30 完成）**——现状缺口：送审文本=标题+正文
  （SubmitTextAssembler SSOT 口径），DFA/LLM/content_hash 均不含摘要；编辑接口已发布态
  拒改标题/正文/配图但放行摘要——摘要=「从不审核+已发布可随意改」的内容域
  （≤500 字富文本，展示于列表/详情卡片）。修复方案（已发布可单独改摘要 + 方式1 同步 DFA 闸门）：
  - 理由：①风险面匹配——摘要 stripTags 后纯文本、且是已过审正文的派生文本（正文合规则
    摘要违规概率低），违规主风险=敏感词，DFA 覆盖大头，LLM 语义审在 500 字摘要上边际收益很低；
    ②同步拦截=零暴露窗口（不产生「新摘要先展示后驳回」的合规硬伤，也省掉待生效回写基建）；
    ③留升级路径——摘要扫描收口成独立方法（场景码外置），未来升级 2b 独立送审单元不必推倒重来
  - 落地要点：①发布 createAndPublish / 建档 saveDraftRow / 编辑 update 三处对摘要
    （stripTags 后）单独跑 DFA check（调用 DfaFacade 门面，dfa 批次2 后=AC 引擎，调用方式不变）——黑名单命中拦截、观察词仅留痕，不动
    assemble(title, body) 口径、content_hash 零影响（存量文章免审缓存不失配）；
    ②已发布态方案B拒改列表维持标题/正文/配图，摘要放行但过 DFA 同步闸门
    （改摘要唯一出路口径=同步扫描通过即可改，无需软删重发）；
    ③文档同步修正「仅封面/摘要/分类/visibility/书籍挂载等元数据可改」表述
    （摘要移出元数据白名单描述 + 「书籍挂载」实际不在编辑接口的失真描述一并修正）
  - ✅ **完成登记（2026-10-30）**：[ArticleServiceImpl.checkSummarySensitiveWords] 收口方法（场景码外置 SUMMARY_DFA_SCENE=DfaScenes.ARTICLE，升级 2b 独立送审单元仅改常量）+ 三处接线（createAndPublish 内容清洗后 / saveDraftRow 步骤④后 / update 摘要分支内——已发布态放行但改前必过闸门）；黑名单 70001 拦截、观察词仅留痕（复用 DfaPublishGate 裁决口径）；摘要不进 assemble、content_hash 零影响（存量免审缓存不失配）；空/空白摘要直接放行。litisle-content 编译 ✅。Content-Architeche 方案B 表述已同步修正（禁改列表=标题/正文/配图，摘要移出白名单 + 删「书籍挂载」失真描述）
- [x] **Q2 挂载书籍状态校验（下架书拒挂，2026-10-30 完成；与 DFA 无关）**——现状缺口：addChapter 仅校验书籍归属+未软删
  （requireOwnedBook）+ 文章本人已发布未软删 + 章节序号冲突，未校验书籍自身 status——
  下架书（TAKEN_DOWN）可继续挂新章节（状态一致性缺口；内容安全无虞因文章须已过审）。
  修复方案（用户确认）：addChapter 补 book.status 校验——下架书拒挂新章节，
  新增错误码 30016 BOOK_STATUS_INVALID；软删书已有校验（BOOK_NOT_FOUND）不重复
  - ✅ **完成登记（2026-10-30）**：[ErrorCode.BOOK_STATUS_INVALID(30016)]（common-core，content 章节码段 30015 后顺延）+ [BookServiceImpl.addChapter] 步骤1 归属校验后补 book.status==TAKEN_DOWN 拒挂（复用 requireOwnedBook 返回值零二次查询；软删书仍按 BOOK_NOT_FOUND 拒绝，不复用本码）。litisle-content/common-core 编译 ✅

- [x] 检查Q2类似的书籍状态校验缺口（已排查 2026-10-29）

> 排查结论（扫描范围：Book/Tag/ReadingHistory/Favorite 四个 ServiceImpl 的状态校验路径）：
> **无缺口确认**：读路径三端点（getDetail/pageChapters/getChapterDetail）统一走 requireReadableBook（下架书仅作者可读，BookServiceImpl L503）；delete/removeChapter 是收窄暴露面的治理方向不立项；attachTags 对封禁/已删标签拒挂（L207）已收口；submit 走状态机白名单；create/update 无 status 入参（owner 不能自改书状态）；favorite/listPublic 已挡下架。**不立项记录**：attachTags 不校验文章状态（下架/驳回文可挂标签致 use_count 虚增，文章不可见无暴露面，微小）。
> **发现 3 项缺口**：

- [x] **Q3 书籍封面 fileId 归属校验缺失（2026-10-30 完成；实质缺口，content 侧优先级最高）**——BookServiceImpl create（L103）/update（L141-142）直接 setCoverFileId 落库，无任何 fileId 校验；对比文章侧封面/插图有归属+bizType 校验（ARTICLE_IMG_REFUSED）——不对称缺口。风险：①任意引用他人文件（盗图）；②引用 file 侧被拒/隔离的违规图 fileId（若封面出图链路不按 safe_flag 过滤，即为绕过图片审核的展示面——修复时顺带核实 file 出图口径）。修复：create/update 对 coverFileId 补与文章封面同口径校验（归属本人+bizType=COVER，复用文章侧校验逻辑，错误码 BOOK_COVER_REFUSED）；与 DFA 无关可随时执行
  - ✅ **完成登记（2026-10-30）**：[ErrorCode.BOOK_COVER_REFUSED(30017)]（content 码段 30016 后顺延）+ [BookServiceImpl] 注入 ArticleImageRefSupport，create 步骤0 / update coverFileId 分支各补 checkCoverOwnership（与文章封面完全同口径：归属本人 + bizType=ARTICLE_IMG；null/空封面放行）。**file 出图口径核实（清单顺带项）**：file 出图不按 safe_flag 过滤（FileAccessService 预留注释「审核启用后：返回前校验」未实现），但 REJECT 违规图走**物理隔离**（ModerationResultListener 移入 quarantine/ 前缀 → 原 URL 404），绕审展示面已被物理封堵——safe_flag 闸口属预留增强非缺口；SUSPICIOUS(3)/待检测(2) 占位图由 content 展示侧处理（Review-Architecture §四 file_meta.safe_flag 行口径）。litisle-content 编译 ✅
- [x] **Q4 updateBook 不校验书籍状态（2026-10-30 完成；下架书元数据可编辑）**——update 仅 requireOwnedBook（归属+软删），下架书 owner 仍可改 title/intro/cover/category（对外不可见，暴露面弱；但与 Q2 同构=状态一致性缺口，且若未来有恢复路径，下架期间元数据可被替换）；修复：补 status!=TAKEN_DOWN 校验（复用 30016 BOOK_STATUS_INVALID），或明确「下架书允许编辑」口径并留痕；与 DFA 无关可随时执行
  - ✅ **完成登记（2026-10-30）**：[BookServiceImpl.update] 步骤1 归属校验后补 `status==TAKEN_DOWN` 拒改（采用清单第一选项=补校验，与 Q2 addChapter 拒挂同构收口；复用 30016 BOOK_STATUS_INVALID 零新码）。litisle-content 编译 ✅
- [x] **Q5 阅读历史上报不校验书籍状态（2026-10-30 完成）**——ReadingHistoryServiceImpl.report 仅校验章节归属（L78-81），下架书手动 PUT 上报仍成功（读路径已挡→「读不了但能报进度」不一致）；修复：report 书籍维度补书状态校验（下架书非作者拒绝）；低优先级；与 DFA 无关可随时执行
  - ✅ **完成登记（2026-10-30）**：[ReadingHistoryServiceImpl.report] 步骤1 章节归属校验后补 bookMapper 查书状态——`TAKEN_DOWN 且非作者` 拒报（复用 30016 BOOK_STATUS_INVALID；作者本人放行=与读路径「下架书仅作者可读」requireReadableBook 同口径；book 为 null 跳过校验不阻塞上报，防章节校验后书被软删的极窄窗口 NPE）。litisle-content 编译 ✅
