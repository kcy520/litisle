> **归档说明**：新建板块文件（批次6，2026-10-31 归档）——迁自 docs/temp/temp.md，正文原样搬运（含完成登记与 ⚠️ 遗留验收标注）。
> **来源**：temp.md「后续另立项」节 review 词库两件（该节 content 旧项已于批次5 归档至 [content-09](../content/content-09-temp-checklist.md)）+「内容域缺口修复批次」节 review DFA 同步闸门优化批次1~4（content Q1~Q5 已于 2026-10-30 归档至 content-09 板块九）。

## 板块八：词库治理与 DFA 同步闸门优化（2026-10-29 ~ 2026-10-31；批次6 于 2026-10-31 归档）

### 后续另立项（推荐方案整理，2026-10-28；不在阶段17，按优先级排序）

- [x] **wordlib_misreport 误报记录表（词级误报明细落库，替代「指标管趋势 + 日志管明细」轻量方案）**（2026-10-30 登记，2026-10-30 完成）——现状缺口：误报上报链路（`WordlibServiceImpl.reportMisreport`）仅做场景级 Counter 埋点（wordlib_misreport_total{scene}）+ warn 日志，不落库；代价：日志不可聚合、有保留期、无法按 reporter 去重（同一用户反复上报同一词重复 +1）、转正决策算不出单词误报率。方案：
  - 新增 `wordlib_misreport` 记录表：词级明细落库（word/scene/level 快照/reason/reporter_id/create_time），按 **reporter_id + word + scene 唯一约束去重**（重复上报只更新 last_report_time，防刷计数）；TTL 清理（复用 sweeper 组挂定时清理，保留期外置 yml）
  - 上报接口仍保留 Counter 埋点（趋势告警不断），落库为增量动作；校验口径不变（仅黑名单词可报，防瞎编词名刷计数）
  - 转正闭环：MID→HIGH 转正前直接 SQL 算「该词误报上报数 / 命中数」，与 wordlib_misreport_total{scene} 场景聚合形成「场景告警 → 表内定位词 → 转正/降级裁决」闭环；与 wordlib_change_audit（批次1 ②）打通「变更→误报→转正」全链路追溯
  - 预期效果：运营在审核后台可按词查看误报明细（当前只能翻日志），灰度转正从「拍脑袋+日志检索」升级为可量化裁决
  - 触发条件：词库运营频度上来（误报上报量级可感知）再做；当前规模场景计数 + 日志够用，**先闲置不动**，与灰度机制「先闲置后拆除」策略同理
  - 落地时 DDL 登记 Sql-Document.md litisle_review 章节；Entity/Mapper 落 domain/mapper 对应包；权限沿用词库只读（ADMIN）
  - ✅ **完成登记（2026-10-30，提前启动执行）**：DDL `wordlib_misreport` 已登记 review-schema.sql §5.3 + Sql-Document.md §5.3（uk_reporter_word=reporter_id+scene+word 去重、level_snapshot 词级别快照、report_count 累计、idx_last_report_time 支撑 TTL 扫描）；新增 [WordlibMisreport.java] 实体 + [WordlibMisreportMapper.java]（incrementReportCount 注解 SQL 原子累加，防读-改-写竞态）；[WordlibServiceImpl.reportMisreport] 改造——selectCount→selectOne 取 level 快照（校验口径不变：仅黑名单词可报）、落库 upsert（先累加后首插，并发首插撞唯一键降级累加）、**落库失败仅 error 不阻断上报**（明细为治理辅助数据，Counter 已计数 + warn 日志兜底）、reason 剥 CRLF + 截断 200；Counter 场景级埋点保留（趋势告警不断）；TTL 清理=新增 [WordlibMisreportCleanupSweeper.java]（天级 05:00 错峰 + SETNX+token 互斥锁，retention-days 非正数=保留全量）；`ModerationProperties` 新增 Wordlib.Misreport 组（retention-days=180 代码默认）+ application.yml `wordlib.misreport.retention-days` 注释登记；Review-Architecture §四/§五/§八 三处同步。验收：litisle-review 编译 ✅；⚠️ **建表 DDL 须先于功能上线执行**（docker 重建自动，存量环境手动执行一次 §5.3）；「上报→明细落库」运行时验证待中间件可用后随词库演练一并执行
  
- [x] **词库治理建议清单（词级命中统计 + 建议标记，运营确认转正/降级）**（2026-10-30 登记，2026-10-31 完成）——目标：人工只看推荐清单不翻全量词库（决策成本降一个数量级），风险闸门留在人手里；确认动作复用既有 updateWord（鉴权/审计/版本/广播天然齐备），**标记只是证据，禁触发任何自动动作**。决策依据（否决「Prometheus 计数器加词级标签」路线）：①词级误报明细已在 wordlib_misreport 表（reporter+scene+word 去重累加），SQL 聚合即得，Prometheus 是监控时序库不是业务查询库；②词级标签=数万 active series 且序列只增不减（删词残留），TSDB 膨胀换不来收益；③/metrics 端点暴露全量敏感词=词库 oracle 泄密，与基数是否可控无关，红线维持。现状缺口：
  - 误报率分母缺失：wordlib_misreport 只有分子（误报上报量），词级命中/拦截次数无统计——场景级 Counter 不够；review_task.hit_words 是任务粒度快照且截断保护，不可靠
  - 运营裁决无推荐清单：转正/降级需人工翻全量词库+手动聚合，gray-misreport-rate 配置（yml=0.01）未绑定 ModerationProperties 字段（悬空）
  - 方案（形态 A：listWords 加「建议标记」列，零新端点）：
    - 新增 `wordlib_hit_stat` 词级命中统计表：uk(scene, word) 唯一 + hit_count 原子累加 + last_hit_time；upsert 照抄 wordlib_misreport 模板（先累加→无行首插→撞唯一键降级累加）；落库失败仅 error 不阻断命中主链路
    - 命中累加接线点=**review 进程内异步机审建任务处**（ModerationTextService/ModerationImageService 建任务时 layer1Hit/hitWords 已解析，HIGH/MID 命中均累加，零额外扫描成本）——⚠️ 硬边界约束：同步闸门 HIGH 拦截发生在宿主本地（DfaFacade 下沉 litisle-api），宿主直写 review 库违反「业务方不跨库直查 review 库」红线，故**同步拦截词级分母一期不做**（二期可选：宿主侧命中事件经 MQ 批量上报，触发条件=误报量级上来、比率口径失真影响裁决）
    - 口径登记（已知局限，清单标注）：误报率数据只产生于 HIGH 阶段（MID 不拦截用户无感、无喊冤）——HIGH 词降级建议一期以**误报绝对量**（report_count+去重 reporter 数）排序为主（同步拦截分母缺失→误报率虚高，比率仅作参考标注异步口径）；MID 词转正建议依据**异步命中观察量**（所有送审内容过 review，数据完整）
    - 配置绑定补全：ModerationProperties.Wordlib 补 grayMisreportRate 字段（对齐既有 yml gray-misreport-rate: 0.01）+ 新增 minHitSample（样本量阈值 N，防小样本比率噪声）
    - 建议标记实时计算（**不定时任务**——统计表为词粒度聚合行，千~万级）：listWords 当前页词批量查 hit_stat + misreport 聚合（两个 IN 查询）内存算标记——HIGH+误报率≥阈值→建议降级；HIGH+样本≥N 且误报率<阈值→转正确认；MID+命中量≥N→建议转正；运营确认→复用 updateWord
    - 已知热点登记：热词单行 UPDATE 竞争（InnoDB 单行几千 TPS，当前规模可接受；规模上来再考虑 Redis 缓冲批量刷库，不过度设计）
  - 预期效果：运营在词库列表直接看到「建议转正/建议降级/转正确认」三类标记及证据数字，点确认走既有变更链路（自动审计+版本+广播），全程无自动动作
  - 任务清单：
    - [x] A1 DDL：wordlib_hit_stat 登记 review-schema.sql §5.4 + Sql-Document.md §5.4（uk_scene_word 唯一键 + hit_count + last_hit_time）
    - [x] A2 Entity/Mapper：WordlibHitStat + WordlibHitStatMapper（incrementHitCount 原子累加 upsert，含 DuplicateKeyException 降级）
    - [x] A3 接线：ModerationTextService/ModerationImageService 建任务处按 hitWords 逐词累加（落库失败仅 error 不阻断；MID/HIGH 均累加）
    - [x] A4 配置：ModerationProperties.Wordlib 补 grayMisreportRate 绑定 + 新增 minHitSample；application.yml 注释登记
    - [x] A5 清单计算：listWords 出参 SensitiveWordVO 增「建议标记+证据字段」（当前页批量 IN 查询，接口 javadoc 登记三类口径与局限）
    - [x] A6 文档同步：Review-Architecture §四（hit_stat 表行）/§八（口径局限）+ temp 完成登记
    - [x] A7 编译验证：mvn -pl litisle-review -am compile；运行时验证待中间件可用后执行
  - ✅ **完成登记（2026-10-31）**：
    - A1：DDL `wordlib_hit_stat` 已登记 review-schema.sql §5.4 + Sql-Document.md §5.4（uk_scene_word=scene+word 唯一键、hit_count 原子累加、first/last_hit_time DB 默认值兜底、idx_last_hit_time 支撑治理扫描）
    - A2：新增 [WordlibHitStat.java] 实体（scene 用 WordSceneEnum @EnumValue 映射）+ [WordlibHitStatMapper.java]（incrementHitCount 注解 SQL 原子累加，防读-改-写竞态）+ [WordMisreportStatDTO.java]（误报聚合出参：sum(report_count) 分子 + count(*) 去重上报人数，uk_reporter_word 保证按人去重）；[WordlibMisreportMapper] 新增 aggregateByWords 聚合查询（`<script>` foreach 批量 IN + scene 可空条件）
    - A3：新增 [WordlibHitStatRecorder.java]（dfa 包，与 WordlibMetrics 同域）——解析快照 hitWords CSV→去重→逐词 upsert（先累加→无行首插→撞唯一键降级累加，照抄 misreport 模板）；**接线点修正**：任务清单原文写「建任务处」，实际两服务均只定位任务不建任务（全库唯一建任务代码 ReviewTaskUpsertServiceImpl 无调用方）——实际接线= [ModerationTextService.persistAndPublish] 任务 CAS 流转成功后（recordId 非空即本次消费真实处理），经 requireWordScene 归一映射场景（ARTICLE_FEATURED 等→ARTICLE）累加；**图片服务不接线（结构性死代码）**：图片场景不走 DFA（hitWords 恒空），无词级证据可累加。落库失败单词仅 error 继续、整体不抛，绝不阻断审核主链路
    - A4：ModerationProperties.Wordlib 补 grayMisreportRate（代码默认 0.01，**绑定了既有 yml 悬空键** gray-misreport-rate）+ minHitSample（默认 50）；application.yml 两键注释登记
    - A5：新增 [WordSuggestionMarkEnum.java]（SUGGEST_DEMOTE 建议降级 / PROMOTE_CONFIRM 转正确认 / SUGGEST_PROMOTE 建议转正；@JsonValue desc、**不加 @EnumValue 因不入库**）；[SensitiveWordVO] 增 suggestionMark/hitCount/reportCount/reporterCount/misreportRate 证据字段；[WordlibServiceImpl.listWords] 附加 attachSuggestionEvidence——当前页词批量两次 IN 查询（hit_stat LambdaQueryWrapper + misreport 聚合）+ 内存按 (scene,word) key 匹配（分隔符 '\n' 为控制字符、词校验禁止出现，防拼接歧义），禁逐词查询；标记口径：**仅黑名单且启用词参与**——HIGH+误报次数>0→建议降级（绝对量为主，比率虚高仅参考）、HIGH+样本≥min-hit-sample 且误报率<gray-misreport-rate→转正确认、MID+样本≥min-hit-sample→建议转正；误报率=reportCount/hitCount（hit=0 或 report=0 置 null 防 0/0 误导），HALF_UP 保留 4 位；WordlibService.listWords javadoc 登记三类口径与同步闸门分母局限
    - A6：Review-Architecture §四 增 wordlib_hit_stat 表行（含口径局限）、§五 增 gray-misreport-rate/min-hit-sample 配置行、§八 增「词级证据口径注记」（否决 Prometheus 词级标签原因 + 异步口径局限 + 禁自动动作红线）
    - 验收：mvn -pl litisle-review -am compile ✅（EXIT=0）；⚠️ **建表 DDL 须先于功能上线执行**（docker 重建自动，存量环境手动执行一次 §5.4）；「命中累加→列表标记」运行时验证待中间件可用后随词库演练一并执行；已知热点（热词单行 UPDATE 竞争）按登记维持现状观察

### 内容域缺口修复批次（2026-10-29 登记；摘要机审 + 挂载状态校验）

> **归档标注（2026-10-30）**：本节 content 模块部分（Q1~Q5 缺口修复，全部完成）已按归档规则原样迁至 [docs/archive/content/content-09-temp-checklist.md](../content/content-09-temp-checklist.md) 板块九「content 模块缺口修复批次 Q1~Q5」节；本文件仅保留 review DFA 批次记录与遗留验收项。

#### review模块（DFA 同步闸门优化批次，2026-10-29 登记；分 4 批次执行）

> 来源：审核模块优化方案评审（用户确认）：DFA→AC 自动机 + 词库备份/变更审计 + 预留 NLM/LLM。
> 关联：Review-Architecture.md §一（技术选型）/§1.1/§四/§五 · 详细版防绕过推演；content 侧 Q1/Q2 已于批次2/批次3 完成后跟进落地（Q1~Q5 全部完成，归档见上方标注）。
> 批次总览：**批次1 词库治理基建（纯新增，引擎无关）→ 批次2 AC 引擎核心（houbb 共存）→ 批次3 对照回归与依赖收口（影子比对通过才删 houbb）→ 批次4 文档同步**；每批次以编译+启动验证收口。

##### 批次1｜词库治理基建（①②⑦：纯新增不动现有逻辑，可独立交付）

- [x] **① 词库备份（WordlibBackupStore SPI + LocalGzipBackupStore）**（2026-10-29 完成）——现状缺口：Redis 快照仅存最新版（热备/冷启动兜底角色），不可作为词库备份（无版本化、不可回滚）。方案：
  - 新增 `WordlibBackupStore` 接口（save/restore）+ `LocalGzipBackupStore` 一期实现：`{backup-dir}/{scene}/{scene}-v{version}.csv.gz`（词库纯文本 gz 压缩率约 1/5~1/10，全版本保留无压力）
  - 触发点：`WordlibVersionService` 变更事务 afterCommit 异步落盘；备份失败仅告警不阻断变更
  - `@ConditionalOnProperty` 激活（`litisle.moderation.wordlib.backup.*`：enabled/dir/retention-versions，yml 预留登记）；二期私有化部署上 MinIO 实现同一接口零代码切换（企业标准答案=对象存储，当前规模本地磁盘够用）
  - 快照/备份角色共存：Redis 快照=热备，备份文件=冷备；恢复走人工 runbook（禁自动回滚，防旧备份把新词库误回滚）；备份完成后做一次「恢复到 v-N」演练（没演练过的备份=没有备份）
  - ⚠️ 备份目录必须是**挂载持久卷**（容器化部署下容器本地盘随实例销毁，备份会丢；yml 注释明示）；restore 路径必须复用导入校验链（限长+字符校验，§七.2），防被污染的备份文件绕过校验回灌
  - ✅ **完成登记（2026-10-29）**：dfa 包新增三件——`WordlibBackupStore`（save/restore SPI，恢复纪律红线=restore 只读返回文本、禁自动回滚、回灌必须走既有导入入口）/ `LocalGzipBackupStore`（`{dir}/{scene}/{scene}-v{version}.csv.gz`，临时文件+原子 move 与快照同纪律，retention-versions 自动清理过期版本，CSV 契约 word,level,blacklist 首列可直接回导入且不做公式前缀转义保词内容零失真）/ `WordlibBackupTrigger`（单线程 daemon 池串行落盘，ObjectProvider 可选依赖未启用零开销，失败仅告警）；`WordlibVersionService.publishSnapshotAndNotify` 改 finally 挂接（快照/广播失败不阻断已提交词表的备份）；`ModerationProperties` 新增 Wordlib.Backup 组（enabled 代码默认 false + yml 置 true、dir、retention-versions=50）；application.yml backup.* 注释登记（持久卷⚠️/角色分工/恢复 runbook 口径）。验收进展：全模块编译 ✅ + review fat jar 打包 ✅（未触碰引擎链路，纯新增）；启动验证受阻于本机中间件未运行（Nacos 8848 / Redis 6380 连接被拒，失败点=redisson/Nacos 基础设施 Bean，与本次改动无关）；「词库单条变更→gz 落盘」与「恢复到 v-N」演练待中间件可用后人工执行
- [x] **② 敏感词更新审计表（wordlib_change_audit）**（2026-10-29 完成）——单条变更逐词审计 / 批量导入聚合 1 条（op_type=BATCH_IMPORT + word_count + 备份文件引用列，词清单本身在备份文件里不重复落库）；红线：与词表变更**同事务**插入（审计不同事务=不可信）；收口位置=`WordlibVersionService` 变更事务；DDL 登记 Sql-Document.md；可与 wordlib_misreport_total（误报灰度转正）闭环追溯
  - ✅ **完成登记（2026-10-29）**：新增 `WordlibOpTypeEnum`（ADD/UPDATE/DELETE/BATCH_IMPORT，String code DictEnum）/ `WordlibChangeAudit` 实体 + `WordlibChangeAuditMapper` / `WordlibAuditContext`（dfa 包 record：opType/word/detail/wordCount）；收口=`WordlibVersionService.bumpAndBroadcast` 新增审计重载——`insertAuditInTx` 在版本推进**同事务**内插审计行（审计失败=变更一并回滚），backup_file 引用列经 `WordlibBackupStore.backupFileName` 统一口径生成（与备份 SPI 落盘文件零漂移）；`WordlibServiceImpl` 五变更入口全接线（addWord=ADD、updateWord=UPDATE+detail 逐字段 变更前→变更后、deleteWord/deleteWordByContent=DELETE、importWords=BATCH_IMPORT+word_count=净新增）；顺手修复 deleteWordByContent 缺 @Transactional（原词表删除在事务外，违背同事务红线）。DDL 已登记 Sql-Document.md §四 #5.2。⚠️ 运维注意：**建表须先于功能上线**（同事务语义下审计插入失败会连带词表变更回滚）。验收：编译 ✅；「变更→审计落库」待中间件可用后随批次1验收演练
- [x] **⑦ NLM 预留 + LLM 确认零改动**（2026-10-30 完成）——`ModerationProperties` 加 `nlm-provider` 配置键（默认空=未激活，注释预留用途）；NLM 正确位置=异步层（与 LLM 并列/级联，走同一 @ConditionalOnProperty + 启动自检 fail-fast 三件套），**禁进同步闸门**（同步层本地毫秒级执行是硬约束，语义模型必然远程调用）；LLM 异步机审链路（moderation.spi）已存在，零改动
  - ✅ **完成登记（2026-10-30）**：`ModerationProperties` 新增 `nlmProvider`（与 text/image-provider 同区，默认空串=未激活，激活前无任何链路消费此键，纯占位）；javadoc + yml 双注释登记预留口径（二期接线位置=异步层 moderation.spi 适配器、与 LLM 并列/级联、同 @ConditionalOnProperty + 启动自检 fail-fast 三件套激活、⚠️ 禁进同步闸门红线）；LLM 异步机审链路零改动（确认未触碰 moderation.spi/adapters/consumers）；编译 ✅

批次1 验收：编译通过 + 四宿主（review/auth/user/content）启动正常（未触碰引擎链路）；词库单条变更→审计落库 + 备份 gz 文件落盘；yml 预留键注释齐全。
> **验收进展（2026-10-30）**：①②⑦ 全部完成，全模块编译 ✅ + review fat jar 打包 ✅ + yml 预留键注释齐全 ✅；四宿主启动验证受阻于本机中间件未运行（Nacos 8848 / Redis 6380 连接被拒，失败点=redisson/Nacos 基础设施 Bean，与批次1改动无关，auth/user/content 未触碰）；「词库单条变更→审计落库 + 备份 gz 落盘 + 恢复到 v-N 演练」待中间件可用后执行；⚠️ wordlib_change_audit 建表须先于功能上线（同事务语义下审计插入失败会连带词表变更回滚）

##### 批次2｜AC 引擎核心（③④⑤a：TextScanEngine SPI + AC + 归一化 + 依赖新增，houbb 共存不删）

- [x] **③ AC 自动机替换 houbb（同步闸门引擎）**（2026-10-30 完成）——api dfa 包新增：`TextScanEngine` SPI（build/scan）+ `AcTextScanEngine` + `CharNormalizer` 归一化。设计决策（对照 houbb 六开关等价能力，防绕过红线）：
  - **归一化双向分治**：无损宽化（1:1/1:N：大小写/全半角/⓿→0/⑩→10/繁简/字母序号）做文本侧预处理；有损折叠（N:1：重复字符）**禁做文本侧全局归一**（会把「谢谢」折叠成「谢」致单字误命中+位置错乱）——重复变体改在**构建期展开为变体 pattern 进 trie**，文本侧永不折叠
  - **变体展开方向与上限（⚠️ 只长不短）**：只注册**重复长形式**（长度≥原词：「谢谢」→注册「谢谢谢/谢谢谢谢」），**禁注册折叠短形式**（若注册「谢」，文本单个「谢」字即命中「谢谢」变体，正常单字全误报）；展开必须有界：每字符额外重复次数上限 + 每词变体总数封顶（防 3^L 组合爆炸撑爆 trie），超限退化为仅原形 + 告警
  - **词库侧 pattern 同过归一化（隐含前提显式化）**：trie 注册的 pattern（含变体展开结果）必须先经 CharNormalizer 归一——漏掉此步则词库繁体/全角词以原始形式进 trie、文本侧已全部归一，繁简/全半角开关**静默全失效（变形词全部漏审）**
  - **hitWords 留痕语义（选型 A）**：hitWords 存**词库规范词**（审计证据=命中哪个黑名单词已足够）；归一片段不回映原文（⑩→10 等宽化使归一位置≠原文位置，回映需归一→原文索引映射，复杂度不值）；`DfaHitResult` 注释同步修正（现注释写「原文片段」）
  - **重叠匹配裁决规则（先定再实现）**：AC 返回所有子串命中，需定义去重+最长优先（「AB」「B」同库时 text=AB 只报 AB）+ allow 区间过滤（deny 命中完全落在 allow 命中区间内则剔除，覆盖嵌套与部分重叠场景）；规则不定则 ⑥ 对照差异无法归因
  - 命中词查级别：wordLevels 查不到（变体 pattern 命中）按 HIGH 兜底（宁严勿松，与现实现语义一致）
  - `DfaFacade` 内部改造（Engine record 中 SensitiveWordBs→TextScanEngine），**对外签名零变化**（check/currentVersion/rebuild 不动，content/auth/user 四宿主业务代码零改动）
  - ✅ **完成登记（2026-10-30）**：新增 [TextScanEngine.java]（SPI+语义契约注释）/[AcTextScanEngine.java]（自研 AC：trie+BFS fail 链+输出聚合，扫描 O(文本长度+命中数)）/[CharNormalizer.java]（无损宽化：小写/全半角/数字写法 ⓿①⑩ 1:1 与 1:N/字母序号 Ⓐⓕ/繁简走 opencc4j 同源引擎=批次3 影子比对口径一致）；DfaFacade Engine record 换 TextScanEngine、houbb import 移除（依赖保留）；DfaHitResult/WordEntry 注释同步；六开关等价映射已在 CharNormalizer/引擎注释中逐项标注（ignoreRepeat 不做文本侧、改构建期变体）。**变体展开落地参数**：回溯式预算展开（每字符额外≤2 次、全词总插入重复≤3、变体封顶 128 超限退化仅原形+告警；组合量 3^L 压到 O(L³)）——初版 frontier 生成算法有「单位置重复变体漏生成」缺陷，单测暴露后重写为回溯式。**依赖**：AC 自研零新增库；实际新增 com.github.houbb:opencc4j:1.8.1（父 pom dependencyManagement + api pom compile，繁简与 houbb 同源且批次3 删 houbb 后独立存活；content 经 api 传递自动获得，⑤a 的 content houbb 运行时缺口顺带消除）。**验证**：litisle-api 8 项语义契约单测全过（归一化五维/基础命中/归一命中/重复变体回映/allow 区间过滤/最长优先/多词命中/空安全）+ api/review/content 编译全过（四宿主零改动）。⑥ 影子比对为上线前置门槛（批次3）
- [x] **④ rebuild 异步构建（仅热更路径）+ 失败兜底**（2026-10-30 完成）——现 rebuild 在 Pub/Sub 订阅线程同步构建（大词库会阻塞订阅线程、拖慢同频道广播）；改独立单线程池执行，**⚠️ 仅限热更路径：initAll 必须保持同步调 rebuild（启动 fail-fast 红线：装载失败中断启动；整体异步化=带病启动，首个请求才炸）**；per-scene **串行+去重**（同场景连续广播只建最新版；并发构建会竞态 engines.put 致旧版本后写回退，串行队列保证版本单调）；热更失败保留旧引擎（现有行为天然如此）+ 补失败指标（WordlibMetrics）
  - ✅ **完成登记（2026-10-30）**：新增 [WordlibRebuildExecutor.java]（dfa 包）——单线程 daemon 池（wordlib-rebuild-* 具名线程）跨场景严格串行防 engines.put 竞态；同场景去重（queuedScenes 标记，任务开始时先摘标记再构建→构建期间新广播可重新入队收敛到最新版）；失败兜底（仅 error + 失败指标不抛出，rebuild 失败发生在引擎原子替换前→旧引擎天然保留）；@PreDestroy 优雅停机。[WordlibChangeSubscriber.java] 订阅端改投递任务（订阅线程零阻塞，DfaFacade 直调移除）；[WordlibMetrics.java] 补 wordlib.rebuild{scene,result=success|fail} 计数（失败率突增=实例词库停更告警源）；initAll→rebuild 同步路径零改动（启动 fail-fast 红线保持）；编译 ✅
- [x] **⑤a pom 依赖新增（houbb 共存）**（2026-10-30 完成）——根 pom dependencyManagement 加 org.ahocorasick:ahocorasick:0.6.3；api pom 加依赖（scope 建议 compile：AC 库零传递依赖几十 KB，经 api 传递后消除 content 存量缺口）；**本批次 houbb 保留在场**（批次3 影子比对需要）；⚠️ **content pom 存量缺口顺手修复确认**：api 侧 sensitive-word 为 provided 不传递、content pom 从未声明也无 houbb import（靠偶然路径在跑），批次2 验收须显式验证 content 启动 DFA 引擎初始化正常
  - ⚠️ **③ 完成后的范围修订**：AC 已改为自研实现（AcTextScanEngine），**无需引入 org.ahocorasick**；本项剩余动作=确认 opencc4j 已登记（③ 已完成父 pom + api pom）+ content pom 存量缺口验收确认（AC 自研后 houbb 运行时缺口已天然消除，content 启动 DFA 初始化验证仍须执行）
  - ✅ **完成登记（2026-10-30）**：依赖实证 = `mvn dependency:tree`（content）显示 `litisle-api:compile → opencc4j:1.8.1:compile(+heaven/nlp-common)` 且 **sensitive-word 已不在 content 运行时依赖树**（AC 自研后 DfaFacade 零 houbb 运行时引用，存量缺口消除坐实；opencc4j 三 jar 传递总量可控）；content pom 无需任何改动；houbb 在 api/review 声明保留至批次3 ⑤b；「四宿主启动 DFA 初始化正常」运行时验证随批次2验收统一执行（本机中间件未运行，同批次1验收受阻项）

批次2 验收：编译 + 四宿主启动 DFA 初始化正常（引擎已切 AC，houbb 在场可随时单点切回）；**批次3 ⑥ 未通过前不上线部署**（防未对照引擎直接投产）。
> **验收进展（2026-10-30）**：③④⑤a 全部完成，api 8 项引擎语义契约单测 ✅ + api/review/content 编译 ✅（四宿主零改动）+ content 依赖树实证（opencc4j 可达/sensitive-word 已脱离 content 运行时）；「四宿主启动 DFA 初始化正常」待中间件可用后执行（同批次1受阻项）；⑥ 影子比对未执行前**不上线部署**

##### 批次3｜对照回归与依赖收口（⑥⑤b：影子比对通过后才删 houbb）
- [x] **⑥ 双引擎对照回归（先于 5b 删除执行）**（2026-10-30 通过）——同一批文本 houbb vs AC 影子比对命中差异，差异项集中在 CharNormalizer 变体场景（本次改造唯一「悄悄漏审」风险环节，宁严勿松靠这轮对照守住）；变体绕过用例照搬 houbb 六开关场景 + **叠词正反样本（谢谢/谢谢谢 命中、单字「谢」「天」不误命中——验证变体只长不短）+ 词库繁体/全角词样本（验证词库侧 pattern 归一化前提）**；⚠️ 全库无任何既有 DFA 测试基建，本项从零搭建（影子比对方式：临时双引擎 Bean 或一次性对照脚本），工作量按新写测试预估
  - ✅ **完成登记（2026-10-30，对照通过，⑤b 放行）**：新增 [WordlibEngineShadowCompareTest.java]（review test，双引擎影子比对常驻回归）——25 用例（六开关等价/叠词正反/拆写/allow 区间/乱序不误伤/空安全）+ 差异钉死断言（documented 差异必须保持 AC 更严方向，AC 漏审方向差异立即失败）。**探针实证归因（HoubbRepeatProbeTest 一次性探针，结论已钉入测试注释）**：两引擎仅 6 处差异且全部为「AC 更严」方向（houbb 漏审/AC 命中），根因唯一=houbb ignoreRepeat=true 文本折叠与词库原形失配（含重复字符的词连精确原形都漏审，text=谢谢 repeatON=[] repeatOFF=[谢谢]）——旧生产配置对含重复字符词系统性漏审，AC 修正之；其余开关（大小写/全半角/数字写法/字母序号/繁简双向/allow 区间过滤）两引擎语义一致；极端多位置重复两引擎同漏（残余风险异步 LLM 兜底，已登记）。**结论：零 AC 漏审方向差异，对照通过**
- [x] **⑤b pom 依赖删除（2026-10-30 完成，⑥ 放行后执行）**——根 pom sensitive-word 版本行 + dependencyManagement 条目已删（opencc4j 全保留，CharNormalizer 繁简归一独立承载）；api（provided 块）/auth/user（compile 声明）三处 sensitive-word 声明已删；review 改 **test scope**（版本显式 0.27.1——dependencyManagement 已删无法托管，仅影子比对 WordlibEngineShadowCompareTest + 语义探针 HoubbRepeatProbeTest 引用，用户确认探针保留）；`DefaultWordlibExporter.java` 整文件删除（149 行全为已注释的 houbb 词库导出一次性工具，用户已确认）。全库 pom sensitive-word 残留仅剩 review test scope 一处（Grep 核实）

批次3 验收：影子比对全通过（差异归因修复后重跑至零差异/确认可接受）+ ⑤b 删除后全模块编译 + 四宿主启动复验。
> **验收进展（2026-10-30）**：⑥ 影子比对通过（零 AC 漏审方向差异）+ ⑤b 依赖删除完成——全模块 `mvn compile` ✅、AcTextScanEngineTest 8/8 ✅、WordlibEngineShadowCompareTest 回归 ✅（test scope 下 houbb 可达，review 主代码 123 源文件编译通过=生产零 houbb）；「四宿主启动复验」待中间件可用后执行（同批次1 受阻项）。批次3 代码动作全部完成，**批次4 文档同步可开始**

##### 批次4｜文档同步收口（⑧：反映前三批全部变更）
- [x] **⑧ 文档同步（2026-10-30 完成）**——Review-Architecture：§一技术选型行（sensitive-word→**自研 AC 自动机**，注明批次3 ⑤b 已删 houbb 依赖）/§1.1 同步闸门行（载体补 AcTextScanEngine，说明构建期归一化变体展开只长不短 + allow 区间过滤 + 最长优先裁决口径）/§四数据模型（新增 wordlib_change_audit 审计表行，指向 Sql-Document §5.2）/§五配置清单（新增 wordlib.backup.* 三键 + nlm-provider 预留键两行）；**「houbb 能力描述收敛（拼音/跳词）」子项核实：现文档已无该描述**（全 docs 仅 archive/review-04-publish-auth.md 存一处「跳词」历史表述，归档件不改），目标状态已达成无需动作；Content-Architeche：§2.4 方案B 行后新增「☆ 待建 Q1 摘要同步闸门」条目（扫描走 DfaFacade 门面=AC 引擎零额外工作；**实现时**才修正方案B「仅…可改」表述，防文档先于实现）

批次4 验收：Review-Architecture / Sql-Document / Content-Architeche 文档与实现对齐。
> **验收进展（2026-10-30）**：Review-Architecture 四处更新 ✅、Content-Architeche Q1 待建条目 ✅、Sql-Document §5.2 wordlib_change_audit DDL 批次1 ② 已登记与实现对齐 ✅。**dfa 同步闸门优化四批次全部完成**；遗留：四宿主启动复验 + 词库变更审计/备份演练 + 恢复到 v-N 演练待中间件可用后执行；wordlib_change_audit 建表 DDL 先于上线执行；content 模块 Q1~Q5 已完成并归档（[archive/content/content-09-temp-checklist.md](../content/content-09-temp-checklist.md)）
