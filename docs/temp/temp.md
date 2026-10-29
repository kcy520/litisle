# 文章分类枚举化改造清单（临时任务清单）

> 方案：方案A（@EnumValue 中文入库，DB 零迁移）+ 查询参数白名单校验（Controller 保持 String）
> 原则：文章链路先行，Book 链路待确认后跟进；每阶段完成后勾选 [x]
> 本文件为临时清单，全部完成后经确认可删除

> **归档标注（2026-10-23 登记，2026-10-27 起分批次执行）**：本清单阶段1~17 按批次归档至 [docs/archive/content/content-09-temp-checklist.md](../archive/content/content-09-temp-checklist.md) 板块九（各板块索引见 [content-implemention-records.md](../archive/content/content-implemention-records.md)，2026-10-28 拆分）——批次1~4（阶段1~17）已全部完成；批次5（「后续另立项」推荐方案整理：P1 发布异常保稿/热度榜增量、P2 书籍发现入口/阅读足迹、P3×3 social 依赖项，全部落地或核实关闭）已归档（均 2026-10-28）。挂起/预留项已转移至 [docs/next/temporarily/Temporarily_not_implemented.md](../next/temporarily/Temporarily_not_implemented.md)（索引）+ temporarily-04-deferred.md content 内容模块节（批次3 新增 3 条迁入项、批次4 新增 1 条余项条目；批次5 无新增迁入项——落地项同步更新既有 2 条条目状态）。
> 归档时点待执行项：①IDE 删除 ArticlePasswordVerifyDTO.java ②存量迁移 UPDATE article SET visibility=1 WHERE visibility=4（若现库有数据）③已有库 ALTER TABLE article DROP COLUMN access_password（docker 重建则自动）④编译与阶段5/6/9 回归点（见各阶段验证小节）⑤编译与阶段13/14 回归点（阶段12 验收一并执行，见阶段13/14 验证小节）⑥编译与阶段15 回归点（见阶段15 验证小节）⑦编译与阶段16 回归点（见阶段16 验证小节）⑧编译与阶段17 回归点（见阶段17 验证小节）。
> 本文件是否删除/保留待用户确认（遵循文件删除需确认纪律）。






### 后续另立项（推荐方案整理，2026-10-28；不在阶段17，按优先级排序）

- [ ]（预留位，无内容）

### 内容域缺口修复批次（2026-10-29 登记；摘要机审 + 挂载状态校验）


#### review模块（DFA 同步闸门优化批次，2026-10-29 登记；分 4 批次执行）

> 来源：审核模块优化方案评审（用户确认）：DFA→AC 自动机 + 词库备份/变更审计 + 预留 NLM/LLM。
> 关联：Review-Architecture.md §一（技术选型）/§1.1/§四/§五 · 详细版防绕过推演；content 侧 Q1/Q2 待批次2（③）+ 批次3（⑥）完成后跟进（见下方 content 模块节）。
> 批次总览：**批次1 词库治理基建（纯新增，引擎无关）→ 批次2 AC 引擎核心（houbb 共存）→ 批次3 对照回归与依赖收口（影子比对通过才删 houbb）→ 批次4 文档同步**；每批次以编译+启动验证收口。

##### 批次1｜词库治理基建（①②⑦：纯新增不动现有逻辑，可独立交付）

- [ ] **① 词库备份（WordlibBackupStore SPI + LocalGzipBackupStore）**——现状缺口：Redis 快照仅存最新版（热备/冷启动兜底角色），不可作为词库备份（无版本化、不可回滚）。方案：
  - 新增 `WordlibBackupStore` 接口（save/restore）+ `LocalGzipBackupStore` 一期实现：`{backup-dir}/{scene}/{scene}-v{version}.csv.gz`（词库纯文本 gz 压缩率约 1/5~1/10，全版本保留无压力）
  - 触发点：`WordlibVersionService` 变更事务 afterCommit 异步落盘；备份失败仅告警不阻断变更
  - `@ConditionalOnProperty` 激活（`litisle.moderation.wordlib.backup.*`：enabled/dir/retention-versions，yml 预留登记）；二期私有化部署上 MinIO 实现同一接口零代码切换（企业标准答案=对象存储，当前规模本地磁盘够用）
  - 快照/备份角色共存：Redis 快照=热备，备份文件=冷备；恢复走人工 runbook（禁自动回滚，防旧备份把新词库误回滚）；备份完成后做一次「恢复到 v-N」演练（没演练过的备份=没有备份）
  - ⚠️ 备份目录必须是**挂载持久卷**（容器化部署下容器本地盘随实例销毁，备份会丢；yml 注释明示）；restore 路径必须复用导入校验链（限长+字符校验，§七.2），防被污染的备份文件绕过校验回灌
- [ ] **② 敏感词更新审计表（wordlib_change_audit）**——单条变更逐词审计 / 批量导入聚合 1 条（op_type=BATCH_IMPORT + word_count + 备份文件引用列，词清单本身在备份文件里不重复落库）；红线：与词表变更**同事务**插入（审计不同事务=不可信）；收口位置=`WordlibVersionService` 变更事务；DDL 登记 Sql-Document.md；可与 wordlib_misreport_total（误报灰度转正）闭环追溯
- [ ] **⑦ NLM 预留 + LLM 确认零改动**——`ModerationProperties` 加 `nlm-provider` 配置键（默认空=未激活，注释预留用途）；NLM 正确位置=异步层（与 LLM 并列/级联，走同一 @ConditionalOnProperty + 启动自检 fail-fast 三件套），**禁进同步闸门**（同步层本地毫秒级执行是硬约束，语义模型必然远程调用）；LLM 异步机审链路（moderation.spi）已存在，零改动

批次1 验收：编译通过 + 四宿主（review/auth/user/content）启动正常（未触碰引擎链路）；词库单条变更→审计落库 + 备份 gz 文件落盘；yml 预留键注释齐全。

##### 批次2｜AC 引擎核心（③④⑤a：TextScanEngine SPI + AC + 归一化 + 依赖新增，houbb 共存不删）

- [ ] **③ AC 自动机替换 houbb（同步闸门引擎）**——api dfa 包新增：`TextScanEngine` SPI（build/scan）+ `AcTextScanEngine` + `CharNormalizer` 归一化。设计决策（对照 houbb 六开关等价能力，防绕过红线）：
  - **归一化双向分治**：无损宽化（1:1/1:N：大小写/全半角/⓿→0/⑩→10/繁简/字母序号）做文本侧预处理；有损折叠（N:1：重复字符）**禁做文本侧全局归一**（会把「谢谢」折叠成「谢」致单字误命中+位置错乱）——重复变体改在**构建期展开为变体 pattern 进 trie**，文本侧永不折叠
  - **变体展开方向与上限（⚠️ 只长不短）**：只注册**重复长形式**（长度≥原词：「谢谢」→注册「谢谢谢/谢谢谢谢」），**禁注册折叠短形式**（若注册「谢」，文本单个「谢」字即命中「谢谢」变体，正常单字全误报）；展开必须有界：每字符额外重复次数上限 + 每词变体总数封顶（防 3^L 组合爆炸撑爆 trie），超限退化为仅原形 + 告警
  - **词库侧 pattern 同过归一化（隐含前提显式化）**：trie 注册的 pattern（含变体展开结果）必须先经 CharNormalizer 归一——漏掉此步则词库繁体/全角词以原始形式进 trie、文本侧已全部归一，繁简/全半角开关**静默全失效（变形词全部漏审）**
  - **hitWords 留痕语义（选型 A）**：hitWords 存**词库规范词**（审计证据=命中哪个黑名单词已足够）；归一片段不回映原文（⑩→10 等宽化使归一位置≠原文位置，回映需归一→原文索引映射，复杂度不值）；`DfaHitResult` 注释同步修正（现注释写「原文片段」）
  - **重叠匹配裁决规则（先定再实现）**：AC 返回所有子串命中，需定义去重+最长优先（「AB」「B」同库时 text=AB 只报 AB）+ allow 区间过滤（deny 命中完全落在 allow 命中区间内则剔除，覆盖嵌套与部分重叠场景）；规则不定则 ⑥ 对照差异无法归因
  - 命中词查级别：wordLevels 查不到（变体 pattern 命中）按 HIGH 兜底（宁严勿松，与现实现语义一致）
  - `DfaFacade` 内部改造（Engine record 中 SensitiveWordBs→TextScanEngine），**对外签名零变化**（check/currentVersion/rebuild 不动，content/auth/user 四宿主业务代码零改动）
- [ ] **④ rebuild 异步构建（仅热更路径）+ 失败兜底**——现 rebuild 在 Pub/Sub 订阅线程同步构建（大词库会阻塞订阅线程、拖慢同频道广播）；改独立单线程池执行，**⚠️ 仅限热更路径：initAll 必须保持同步调 rebuild（启动 fail-fast 红线：装载失败中断启动；整体异步化=带病启动，首个请求才炸）**；per-scene **串行+去重**（同场景连续广播只建最新版；并发构建会竞态 engines.put 致旧版本后写回退，串行队列保证版本单调）；热更失败保留旧引擎（现有行为天然如此）+ 补失败指标（WordlibMetrics）
- [ ] **⑤a pom 依赖新增（houbb 共存）**——根 pom dependencyManagement 加 org.ahocorasick:ahocorasick:0.6.3；api pom 加依赖（scope 建议 compile：AC 库零传递依赖几十 KB，经 api 传递后消除 content 存量缺口）；**本批次 houbb 保留在场**（批次3 影子比对需要）；⚠️ **content pom 存量缺口顺手修复确认**：api 侧 sensitive-word 为 provided 不传递、content pom 从未声明也无 houbb import（靠偶然路径在跑），批次2 验收须显式验证 content 启动 DFA 引擎初始化正常

批次2 验收：编译 + 四宿主启动 DFA 初始化正常（引擎已切 AC，houbb 在场可随时单点切回）；**批次3 ⑥ 未通过前不上线部署**（防未对照引擎直接投产）。

##### 批次3｜对照回归与依赖收口（⑥⑤b：影子比对通过后才删 houbb）
- [ ] **⑥ 双引擎对照回归（先于 5b 删除执行）**——同一批文本 houbb vs AC 影子比对命中差异，差异项集中在 CharNormalizer 变体场景（本次改造唯一「悄悄漏审」风险环节，宁严勿松靠这轮对照守住）；变体绕过用例照搬 houbb 六开关场景 + **叠词正反样本（谢谢/谢谢谢 命中、单字「谢」「天」不误命中——验证变体只长不短）+ 词库繁体/全角词样本（验证词库侧 pattern 归一化前提）**；⚠️ 全库无任何既有 DFA 测试基建，本项从零搭建（影子比对方式：临时双引擎 Bean 或一次性对照脚本），工作量按新写测试预估
- [ ] **⑤b pom 依赖删除（待 ⑥ 通过后执行）**——根 pom sensitive-word 版本行 + api/review/auth/user 四处 pom 的 sensitive-word 声明（已核实全库 houbb import 仅 DfaFacade 实际使用 + DefaultWordlibExporter 注释段，删除安全；test 目录零 DFA 引用）；`DefaultWordlibExporter` 内已注释的 SensitiveWordBs 代码段删除需用户确认（禁随意删注释红线）

批次3 验收：影子比对全通过（差异归因修复后重跑至零差异/确认可接受）+ ⑤b 删除后全模块编译 + 四宿主启动复验。

##### 批次4｜文档同步收口（⑧：反映前三批全部变更）
- [ ] **⑧ 文档同步**——Review-Architecture：§一技术选型行（sensitive-word→AC 自动机）/**§一 houbb 能力描述按代码实际口径收敛（现文档写「拼音/跳词」但代码只启用六开关，拼音/跳词未开——文档对齐实现，不补实现）**/§1.1 同步闸门描述/§四数据模型（新增审计表）/§五配置清单（wordlib.backup.* + nlm-provider）；Content-Architeche Q1 条目补「摘要扫描走新引擎门面，零额外工作」

批次4 验收：Review-Architecture / Sql-Document / Content-Architeche 文档与实现对齐。

#### content模块，待dfa批次2（③）+批次3（⑥）完成后修改（暂不考虑；Q2 类与 DFA 无关可随时并行）

> 来源：Content_Function_Develop.md 文档核查发现两个缺口（用户确认修复口径）。
> 口径修正说明：用户先选「摘要纳入机审」，后经方案对比（A1 新摘要立即生效有暴露窗口 / A2 待生效回写需新基建 / 完整异步机审须跨 content+review 契约改造）修正为下述方案1——已发布可单独改摘要 + 同步 DFA 闸门，风险面与摘要（≤500 字已过审正文的派生文本）匹配，留 2b 独立送审单元升级路径。
> dfa 方案影响核查（2026-10-29）：content 审核 4 个接触点（发布/重提、preview-check、标签闸门、currentVersion×4 处）全部引擎无关、无 hitWords 消费——dfa 四批次对 content **零代码改动、零编排重排**；Q1 实现时直接调用改造后 DfaFacade 门面（届时=AC 引擎），零额外适配。

- [ ] **Q1 摘要 DFA 同步闸门（已发布可单独改摘要）**——现状缺口：送审文本=标题+正文
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
- [ ] **Q2 挂载书籍状态校验（下架书拒挂；与 DFA 无关，可先于 dfa 批次执行）**——现状缺口：addChapter 仅校验书籍归属+未软删
  （requireOwnedBook）+ 文章本人已发布未软删 + 章节序号冲突，未校验书籍自身 status——
  下架书（TAKEN_DOWN）可继续挂新章节（状态一致性缺口；内容安全无虞因文章须已过审）。
  修复方案（用户确认）：addChapter 补 book.status 校验——下架书拒挂新章节，
  新增错误码 30016 BOOK_STATUS_INVALID；软删书已有校验（BOOK_NOT_FOUND）不重复

- [x] 检查Q2类似的书籍状态校验缺口（已排查 2026-10-29）

> 排查结论（扫描范围：Book/Tag/ReadingHistory/Favorite 四个 ServiceImpl 的状态校验路径）：
> **无缺口确认**：读路径三端点（getDetail/pageChapters/getChapterDetail）统一走 requireReadableBook（下架书仅作者可读，BookServiceImpl L503）；delete/removeChapter 是收窄暴露面的治理方向不立项；attachTags 对封禁/已删标签拒挂（L207）已收口；submit 走状态机白名单；create/update 无 status 入参（owner 不能自改书状态）；favorite/listPublic 已挡下架。**不立项记录**：attachTags 不校验文章状态（下架/驳回文可挂标签致 use_count 虚增，文章不可见无暴露面，微小）。
> **发现 3 项缺口**：

- [ ] **Q3 书籍封面 fileId 归属校验缺失（实质缺口，content 侧优先级最高）**——BookServiceImpl create（L103）/update（L141-142）直接 setCoverFileId 落库，无任何 fileId 校验；对比文章侧封面/插图有归属+bizType 校验（ARTICLE_IMG_REFUSED）——不对称缺口。风险：①任意引用他人文件（盗图）；②引用 file 侧被拒/隔离的违规图 fileId（若封面出图链路不按 safe_flag 过滤，即为绕过图片审核的展示面——修复时顺带核实 file 出图口径）。修复：create/update 对 coverFileId 补与文章封面同口径校验（归属本人+bizType=COVER，复用文章侧校验逻辑，错误码 BOOK_COVER_REFUSED）；与 DFA 无关可随时执行
- [ ] **Q4 updateBook 不校验书籍状态（下架书元数据可编辑）**——update 仅 requireOwnedBook（归属+软删），下架书 owner 仍可改 title/intro/cover/category（对外不可见，暴露面弱；但与 Q2 同构=状态一致性缺口，且若未来有恢复路径，下架期间元数据可被替换）；修复：补 status!=TAKEN_DOWN 校验（复用 30016 BOOK_STATUS_INVALID），或明确「下架书允许编辑」口径并留痕；与 DFA 无关可随时执行
- [ ] **Q5 阅读历史上报不校验书籍状态**——ReadingHistoryServiceImpl.report 仅校验章节归属（L78-81），下架书手动 PUT 上报仍成功（读路径已挡→「读不了但能报进度」不一致）；修复：report 书籍维度补书状态校验（下架书非作者拒绝）；低优先级；与 DFA 无关可随时执行  


