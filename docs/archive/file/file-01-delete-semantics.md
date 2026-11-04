> **归档说明**：新建板块文件（批次8，2026-11-03 归档）——迁自 docs/temp/temp.md「file 模块删除语义统一立项清单」节，正文原样搬运（含方案A 公共设计、决策点3 处、批次1~4 完成登记与挂起项标注）。
> **来源**：隔离图删除/恢复链路专项排查（2026-11-02 立项，当日批次1~4 全部完成）；挂起项④（申诉 14 天发起窗口）已登记 docs/next/temporarily/temporarily-04-deferred.md。

## 板块一：删除语义统一（隔离图软删缓冲 + 恢复链路完整，2026-11-02；批次8 于 2026-11-03 归档）

### file 模块删除语义统一立项清单（2026-11-02 登记；方案A 与三个决策点推荐项均已用户确认，待批次实施）

> 来源：隔离图删除/恢复链路专项排查。SeaweedFS 4.46 开源版源码核实：compose.yaml `-filer.recycleBinMaxHours=168` 为**企业版功能，开源版无效**（filer/server 旗标列表均无此参数，开源版无回收站）；FileModerationServiceImpl / FileReconcileTask / QuarantineSweeper / AppealServiceImpl / ArticleImageRefSupport 代码实况交叉核实。
> 本节仅登记立项与推荐方案（用户已确认 2026-11-02），按批次拆分执行，完成后按归档纪律迁移。

**缺口**：删除链路语义割裂——普通文件删除为「软删 del_flag=1 + 对账任务 7 天缓冲后物理清理」，有真实恢复窗口（FileReconcileTask 注释明确「此期间可恢复误删」）；隔离图到期删除（QuarantineSweeper → FileClient.deleteQuarantined）却**立即物理删 SeaweedFS 对象**，不可恢复。代码三处注释「SeaweedFS 回收站兜底 7 天（-filer.recycleBinMaxHours=168）」依赖的机制经源码核实**不存在**（企业版专属）。且 submitAppeal 未实现（无申诉发起窗口限制），申诉窗口与证据留存无策略对齐。

**方案A · 应用层软删缓冲（公共设计，批次1~3 共用，2026-11-02 用户确认）**：

目标与原则：一条删除纪律贯穿所有链路（任何删除先软删 del_flag=1，物理清理只由对账任务在缓冲期满后执行）；不引入新存储机制（无 trash 前缀、无新表），复用现有软删 + 对账 + 前缀移动三件套。

新时间线（可恢复窗口 17 天 = quarantine.retention-days 10 + physicalCleanDelayDays 7）：
- Day 0 机审 REJECT → 隔离（quarantine/ 前缀，对象与元数据完好）；期内平反 → unquarantine 完整恢复（现有逻辑不变）
- Day 10 保留期到期 → Sweeper 委派【软删】（不再删 SeaweedFS 对象）：file_meta del_flag=1 + delete_time=now + file_ref 引用全解绑（见决策点1）
- Day 10~17 软删缓冲期 → 对象仍在 quarantine/ 前缀；窗口内申诉改判成功 → 软删态恢复（新能力，见改动点②）
- Day 17+ 对账任务物理清理 → 删 SeaweedFS 对象 + 物理删 file_meta 行 → 永久丢失（唯一边界）
- 申诉挂起期间（status=0）删除被 Sweeper 豁免顺延（既有机制不变）

改动点：
① `FileModerationServiceImpl.deleteQuarantined`：保留幂等双闸口（元数据不存在/已软删跳过）+ quarantine/ 前缀校验；**删除 listKeys + 逐 key delete 段（不碰 SeaweedFS）**；新增解绑该 fileId 全部 file_ref 引用行（见决策点1）
② `FileModerationServiceImpl.unquarantine`：查不到活跃记录时**回查含软删记录**（del_flag=1），命中则前缀移回（quarantine/ → 原前缀，复用 moveBetweenQuarantinePrefix）+ safe_flag=1 + del_flag=0 + 清空 delete_time；恢复前 listKeys 验对象存在（防「对象已删元数据行残留」边缘态；对象不在则维持现行为——抛错由 content 侧 try-catch 接住，文章照常恢复配图死链）
③ `FileReconcileTask.physicallyCleanDeleted`：**取消 quarantine/ 前缀豁免**跳过分支，隔离图软删满 7 天后走与其他文件完全相同的物理清理；「两套保留期打架」根源消除；yml `skip-quarantine-cleanup` 保留加注释（标记停用及原因，遵循 yml 配置纪律）
④ `AppealServiceImpl.submitAppeal`（**挂起**：随 submitAppeal 实现批次落地）：加 14 天发起窗口校验（基准 review_task.decide_time；14 < 17 留 3 天余量吸收对账延迟/重试）——「申诉成功但图已物理删」策略上不可能发生
⑤ 清理虚假假设：compose.yaml 无效旗标（见决策点3）；三处「回收站兜底 7 天」注释（QuarantineSweeper 类头 / FileModerationServiceImpl / FileReconcileTask）统一改为真实口径「软删缓冲 7 天内可人工恢复，期满物理删除不可恢复」

**决策点（3 处，均已确认推荐项，2026-11-02）**：
1. 引用解绑方式：**按 fileId 全删**——新增 moderation 专用 `unbindAllRefs(fileId)`，突破「四元组精确解绑」铁律并注释说明理由（铁律保护的是正常业务流防误删他人引用；审核侧终结违规证据属管理动作，file_ref 为纯计数登记按 fileId 全删不伤数据）。企业对照：证据到期时关联关系一并终结，一致。
2. 恢复后引用补登记：**补**——content 侧文章恢复链路（unquarantineImages 恢复成功后）复用 `ArticleImageRefSupport.bindRefByArticle` 幂等补登记，引用计数恢复完整（不补的后果：恢复图后 owner 删文时该图被立即软删而非走引用计数，隐性数据不一致）。企业对照：恢复即完整恢复全部关联关系，一致。
3. compose 旗标：**确认企业版功能无效后删除** `-filer.recycleBinMaxHours=168`（第三方软件启动参数非自有 yml 配置项；留着误导后来者以为有回收站兜底；将来回收站能力靠本方案应用层软删或换企业版，而非此无效参数）。企业对照：配置审查清掉无效项，一致。

**边界与并发**：
- 缓冲期内改判 → 软删态恢复 + content 补登记，配图完整回来
- 改判与物理清理赛跑 → 恢复前验对象存在性；对象已删 → 既有死链兜底，文章照常恢复
- 物理清理后（Day 17+）才改判 → 配图永久丢失（元数据行也没了），策略上被改动点④的 14 天窗口挡住
- Sweeper 重复投递删除指令 → 幂等闸口②（已软删跳过）拦截
- 存储占用：违规证据实际留存 10→17 天，存量增长可忽略（违规图占少数）

**企业方案对照**（机制层面与企业同构，唯一差距为运维工具）：
- 存储层：GCS/Azure Soft Delete「删除→非活动态保留窗口→永久清除」= 本方案「del_flag 软删 + 对账缓冲期满物理清理」——企业在存储引擎层做，本方案在应用层做（开源版无引擎层能力，应用层软删为业界标准替代）
- 策略层：申诉截止期 + 挂起冻结执行 + 证据留存 ≥ 申诉窗口 = 挂起豁免（既有）+ 14 天发起窗口 < 17 天留存窗口
- 承诺边界：无企业承诺窗口外可恢复（GCS 最长 365 天到期即失），本方案 Day 17 后永久丢失走死链兜底，一致
- 差距：企业有 Admin UI 一键扫描恢复，本方案恢复靠人工 SQL/S3 操作（工具便利性差距非机制缺失；量小可人工，将来要工具化再加管理端接口）

**核查结论（2026-11-02 全量排查：file / content / review 三模块 + 契约 + 文档交叉核实）**：
- 代码改动面收敛：仅下方批次1~3 所列落点，其余全部零改动；user / social 模块无隔离链路调用，零改动
- content 零改动核实：`ModerationAppealResultConsumer` / `handleAppeal` / `UserBannedConsumer`（消费签名与行为不变，单张失败 try-catch 兜底保留，触发面收窄为仅「物理清理后改判」）；`ArticleImageRefSupport.batchGetOwners`（FILE_NOT_FOUND 语义不变，软删期内不触发归属校验）；`ArticleImageRenderSupport`（渲染走既有「缺失 fileId 静默空 Map」兜底）
- review 零改动核实：`ModerationImageConsumer` / `OnnxImageModerationAdapter` / `ImageAppealCacheInvalidator` / `ModerationAppealResultPublisher` / `ReviewTaskSweeper` 均不受 file 侧删除语义影响
- 文档同步面核实：Content-Architeche.md 无隔离/unquarantine 描述（零同步）；docs/index 无 content-index.md；Sql-Document 无 DB 变更（无新表新列）
- 确认无影响面：`ErrorCode.FILE_NOT_FOUND` 读路径语义不放松；`FileManageServiceImpl.delete` 本就「软删+7 天缓冲」天然同构；`SeaweedFsClient` 无新能力需求；秒传判重池恢复后 del_flag=0 自动回池（idx_dedup 普通索引无唯一冲突）
- ⚠️ **既有缺口（不在本方案范围，登记待用户定夺是否立项）**：封面图（`article.cover_file_id`，COVER 上传即审）被隔离后，文章 4→2 平反只恢复 article_image 配图，**封面不在 `unquarantineImages` 连带恢复范围**——属申诉链路既有行为缺口，方案A 不扩大范围

#### 批次1 · 删除语义统一——file 侧核心（P1 · 一致性/可恢复性）（✅ 2026-11-02 完成，编译 litisle-file 通过）

- [x] 改动点①：`FileModerationServiceImpl.deleteQuarantined` 改软删——保留幂等双闸口（元数据不存在/已软删跳过）+ quarantine/ 前缀校验，**删除 listKeys + 逐 key delete 段（不碰 SeaweedFS）**，新增解绑引用调用（配套调整：`FileClient` Feign 契约无需变化，deleteQuarantined / unquarantine 签名不变）
- [x] 决策点1：新增 moderation 专用 `unbindAllRefs(fileId)`（`FileModerationService` 接口 + impl，归属域实施时按 service 拆分边界定；Javadoc 注明按 fileId 全删突破四元组铁律的理由——审核终结证据属管理动作，file_ref 纯计数登记不伤数据）
- [x] 改动点②：`unquarantine` 软删态恢复——`FileMetaSupport` 新增含软删查询（如 `getMetaIncludeDeletedOrThrow`），命中则前缀移回（复用 moveBetweenQuarantinePrefix）+ safe_flag=1 + del_flag=0 + 清空 delete_time；恢复前 listKeys 验对象存在（防「对象已删元数据行残留」边缘态）；**既有 getMetaOrThrow 与全部读路径语义不动**（不因恢复能力放松）
- [x] 改动点③：`FileReconcileTask` 取消 quarantine/ 前缀豁免——删跳过分支，类头注释 L29-31「跳过 quarantine/ 前缀记录」同步改（遗漏落点）
- [x] 契约层（file-index 纪律「契约层 Javadoc 锁口径」）：`FileClient.deleteQuarantined` L106 Javadoc 回收站口径 → 真实口径（软删 + 解绑引用，不删对象；**方法签名不变**，`FileClientFallbackFactory` / `ModerationAppealResultEvent` 零改动）
- [x] 接口注释（遗漏落点）：`FileModerationService` L16 类头契约注释 + L40 Javadoc「回收站兑底 7 天」→ 真实口径
- [x] 注释核对（预期零改动）：`FileFeignProviderController.deleteQuarantined` L129-131、`ModerationResultListener` L25-30/L97/L113 仅核对无「物理删除」表述（核对结果：Listener 零改动；Provider 注释含「删本体/缩略图」已随行为同步改为「软删 + 解绑引用」）
- [x] 配置注释：`FileProperties.ReconcileTask.skipQuarantineCleanup`（L100）字段注释标「已停用，保留仅为 yml 兼容」+ file application.yml `skip-quarantine-cleanup` 注释标记停用（配置保留，遵循 yml 纪律）
- [x] 验证：编译 litisle-file（`mvnw compile -pl litisle-file -am` exit 0）

#### 批次2 · 删除语义统一——content 侧配套（P1 · 引用一致性）（✅ 2026-11-02 完成，编译 litisle-content 通过）

- [x] `ArticleImageRefSupport` 新增 public 入口（如 `bindArticleImageRefs(fileIds, articleId, ownerId)`）复用 private `bindRefs`（L167-171 幂等逐个 bindRef，重试安全）；`bindSingleRef`（封面专用）不动
- [x] `ArticleReviewWriteBackServiceImpl.unquarantineImages`（L310-324）：L319 `unquarantine(fileId)` 成功（不抛异常）的 fileId 收集 → 循环后统一补登记；单张失败仍只留日志不中断
- [x] ownerId 获取：`unquarantineImages` 仅持 articleId，方法内回查 `article.owner_id` 一次（或调用方 handleAppeal 传入，实施取改动最小路径）（实施：调用方传入 article.getAuthorId()，改动最小路径）
- [x] fail-safe：补登记失败 error 留痕不阻断文章恢复
- [x] 验证：编译 litisle-content（`mvnw compile -pl litisle-content -am` exit 0）

#### 批次3 · 删除语义统一——review 侧 + 配置收尾（P2 · 收尾）（✅ 2026-11-02 完成，编译根聚合通过）

- [x] `QuarantineSweeper` 类头 + 方法体注释（L170 附近「file 侧执行物理删除 + 回收站兜底」）改真实口径「软删缓冲 7 天内可人工恢复，期满物理删除不可恢复」；顺带说明限速 `delete-rate-per-minute` 的「防打爆 SeaweedFS」理由减弱（配置保留不动）（实施：类头【职责】【为什么扫描源】【限速】+ 方法 Javadoc + 阶段三注释 + 步骤3行注释全同步，天级 cron 03:00 错峰信息保留）
- [x] 决策点3：compose.yaml 删除 `-filer.recycleBinMaxHours=168`（删除前 docker logs 复核容器启动无旗标报错）（核对结论：docker 无运行容器无法现场 logs 复核，依立项时 SeaweedFS 4.46 开源版源码核实结论——旗标为企业版专属、开源版无效——执行删除，并在原位加注释说明应用层软删承担该职责）
- [x] 文档 File-Architecture.md（版本 v1.3→v1.4）：§3.2 L170 deleteQuarantined 能力行「删本体/缩略图+软删」→「解绑引用+软删」+ 补 unbindAllRefs 能力行；§五 L251 skip-quarantine-cleanup 标停用；§七 L287 审核联动补软删缓冲口径 + 17 天恢复窗口；§2.1 删除行补「隔离图到期删除同走软删缓冲」一句；§4.2 file_ref 设计要点补 unbindAllRefs 说明
- [x] 文档 file-index.md：§五 L76 delete-quarantined 描述同步；§六 FileModerationService 能力行 +unbindAllRefs；§九 L109 reconcileTask 行标停用；§十二 Step2「跳过 quarantine/ 前缀」→ 取消豁免统一口径；§十三 L158 导航行同步
- [x] 文档 Review-Architecture.md（版本 v1.2→v1.3）：§五 L211 quarantine.* 行「review 侧专属清理，file 侧跳过该前缀」→「到期软删由 Sweeper 委派，物理清理统一交 file 侧对账任务（不再豁免）」+ **retention-days 30→10（⚠️ 过期值修正，实际 yml=10，17 天窗口=10+7 计算基准）**；§2.3 L97 隔离描述补「到期软删」一句（可选）（已补）
- [x] 文档 review-index.md：L41 quarantine 配置行 retentionDays 30→10 + 软删口径（⚠️ 同上过期值）；L72 QuarantineSweeper 一行描述同步
- [x] 文档 Review-Architecture-Development-detailed.md / Review-Business-Guide.md：6-3 隔离清理节等隔离删除表述若有则同步口径（核对后可能零改动）（核对结论：Review-Architecture-Development-detailed.md 全库不存在该文件，零改动；Review-Business-Guide.md L605 Sweeper 表行同步「到期软删」+ retention 30→10 + 限速理由）
- [x] 文档 Content-Business-Guide.md 板块八：补一句恢复窗口口径（可选增强：到期软删后 7 天缓冲期内改判仍可完整恢复含补登记；物理清理后改判配图死链；L105/L725/L739/L751 主体表述无需改写）（实施：L755-760 通俗版补「配图恢复窗口」一句；额外同步 Sql-Document.md L1978 file_meta.delete_time 列注释为「清理任务不再豁免 quarantine/ 前缀」，仅列注释无 DDL 变更）
- [x] 验证：编译根聚合（`mvnw compile -DskipTests` exit 0）

**挂起项（不在批次内，随 submitAppeal 实现批次落地）**：
- 改动点④：`AppealServiceImpl.submitAppeal` 加 14 天发起窗口校验（基准 review_task.decide_time；14 < 17 留 3 天余量吸收对账延迟/重试——「申诉成功但图已物理删」策略上不可能发生）；登记 docs/next/temporarily/temporarily-04-deferred.md（随 submitAppeal 本体）
- ⚠️ 实现时区分两个「7 天」：`appeal-timeout-days=7` 是**申诉裁决超时**（超时自动维持，既有）；14 天是**申诉发起窗口**（以 decide_time 为基准，预留错误码 70002 申诉窗口外）——新增独立 window 配置键，勿复用既有键

#### 批次4 · 封面图恢复缺口修复（P2 · 恢复链路完整性）（✅ 2026-11-02 完成，编译 litisle-content 通过）

> 缺口（原 L194 ⚠️ 登记）：封面图（`article.cover_file_id`，COVER 上传即审）被隔离后，文章 4→2 申诉平反只恢复 article_image 配图，封面不在 `unquarantineImages` 连带恢复范围——平反后封面永久隔离，缓冲期满物理删后封面死链。推荐**立项为独立批次4**：缺口真实且后果明确、改动小边界清晰（仅 content 侧恢复链路一处）、不依赖 submitAppeal 批次、与删除语义统一同源（恢复链路完整性），趁上下文新鲜落地。

- [x] 改动点⑤：`ArticleReviewWriteBackServiceImpl.handleAppeal` 步骤6（L286 附近）——`fromTakenDown` 分支调用 `unquarantineImages(articleId, article.getAuthorId(), article.getCoverFileId())`，封面 fileId 调用方已在手零额外回查
- [x] `unquarantineImages` 签名扩展（articleId, ownerId, coverFileId）：coverFileId 非空且不在配图列表时，先调 `fileClient.unquarantine(coverFileId)`（幂等，单张失败 error 留痕不中断，纪律与配图一致）；恢复成功则单独 `articleImageRefSupport.bindSingleRef(coverFileId, ContentBizConstant.FILE_BIZ_TYPE_COVER, articleId, ownerId)` 补登记（file 侧到期软删解绑了全部引用，含封面；补登记 fail-safe 留痕不阻断）
- [x] 注释同步：方法 Javadoc「连带恢复被隔离配图」→「连带恢复被隔离配图与封面」；失败留痕日志与既有 `[3-5]` 前缀对齐（实施：handleAppeal 流程注释⑥与步骤6注释同步补封面）
- [x] 文档同步：Content-Business-Guide.md 板块八 L755-760 通俗版「配图恢复窗口」句补「含封面」两字（实施：通俗版 2 句 + 详细版 5 处「恢复隔离配图」→「配图与封面」；File-Architecture.md §3.2 核对无 unquarantineImages 能力行，file 侧仅描述 unquarantine 契约能力，零改动）
- [x] 验证：编译 litisle-content（`mvnw compile -pl litisle-content -am -DskipTests` exit 0）
