# LitIsle Review 模块业务指南（功能开发向）

> 定位：**快速理解审核域业务 + 功能开发导航**——每板块给出「业务是什么 / 代码在哪 / 新功能挂哪里」。
> 不承载决策推演与竞态论证（看 [Review-Architecture-Development-detailed.md](../Architecture/Review-Architecture-Development-detailed.md)），
> 机制结论与配置清单看 [Review-Architecture.md](../Architecture/Review-Architecture.md)，表结构 SSOT 看 [Sql-Document.md](../Sql-Document.md)。
>
> 阅读方式：每个板块固定两段——**详细版**（真实类名+关键逻辑）→ **通俗版**（无代码名）。先读全景图，再按需跳板块。

## 〇、全景图：一条内容从提交到处置

```
发布/上传/注册/私信
  ├─ 同步层（宿主本地毫秒级）：DFA/AC 关键词闸门
  │     HIGH 黑名单词 → 直接拒绝（70001）；MID 词 → 只标记留痕不拦截（灰度观察）
  ▼
  异步机审（review 消费 MQ）
  ├─ 幂等 upsert 建任务（bizType+bizId+scene 定位）→ 版本化缓存查重（终局结论复用）
  ├─ 文本 LLM / 图片 ONNX（SPI 适配器）→ PASS / REJECT / SUSPICIOUS 三分态
  ├─ PASS → 业务方 CAS 放行；REJECT → 驳回下架 + 处罚记账 + 通知（可申诉）
  ├─ SUSPICIOUS → review_task.status=2 进人工复审队列
  └─ 故障 → 延迟重试，耗尽进 DLQ 转人审兜底（绝不默认放行）
  ▼
  人审工作流：队列 → 认领（CAS 70004）→ 裁决（终态 CAS + 处罚/举报回写联动）
  ▼
  处罚与违规分：punish_log + violation_score 累计 → punish.result 事件（auth 强制下线等）
  ▼
  申诉改判：ADMIN 裁决 → appeal.result 事件 → 各业务方对称恢复（缓存/配图/索引）
```

**词库治理贯穿始终**（本模块独有的「审核供给侧」主线）：

```
词表变更（version+1 同事务）→ 变更审计落库 → 冷备 gz 落盘 → Redis 快照更新
  → Pub 广播全实例重建 AC 自动机
灰度闭环：新词默认 MID（观察）→ 命中统计 + 误报上报 → 词库列表出「建议标记」推荐 → 人工转正 MID→HIGH / 降级
```

## 架构设计

```
litisle-review
└─ com.litisle.litislereview
   ├─ controller              # HTTP 入口六域拆分（Report/ReviewTask/Review/Appeal/Punish/Wordlib）
   │  └─ feign                # 对外 Feign 提供方（处罚权威态回查等，网关不路由 /api/feign/**）
   ├─ service                 # 六域业务接口 + ReviewTaskUpsertService（建任务收敛）+ UsernameRejectRecordService
   │  └─ impl                 # 接口实现（事务边界/CAS 收口都在实现类）
   ├─ moderation              # 机审域
   │  ├─ spi                  # ContentModerationService 门面 + ModerationResult 三分态 + dto
   │  ├─ adapter              # LlmTextModerationAdapter（防注入三件套）/ OnnxImageModerationAdapter
   │  ├─ consumer             # ModerationTextConsumer / ModerationImageConsumer / ModerationDlqConsumer
   │  ├─ cache                # ModerationResultCache（版本化 key）/ ImageAppealCacheInvalidator
   │  ├─ service              # ModerationText/ImageService（建任务+三分态落库+结果发布）/ RejectPunishAdapterHost / DlqService / Metrics
   │  └─ config               # ModerationProperties（参数外置）/ 启动自检 fail-fast / QPS 护栏 / Rabbit 声明 / 阈值指标
   ├─ dfa                     # 词库治理域：版本服务/广播订阅/装载器/备份 SPI 与本地实现/快照播种/审计上下文/命中统计记录器/指标
   ├─ mq
   │  └─ publisher            # 结果/申诉结果/处罚三类事件发布（事务铁律：afterCommit 发、失败入 DLQ）
   ├─ sweeper                 # 兜底清扫五件套（任务回池/隔离清理/归档/处罚对账/误报 TTL）
   ├─ interceptor             # ModerationGlobalQpsInterceptor（送审入口全站 QPS 护栏）
   ├─ enums                   # 领域枚举（词级别/场景/三分态映射/处罚动作/治理建议标记等 23 个）
   ├─ domain                  # entity / dto / vo 分包
   └─ config                  # SaToken / Web（拦截器注册）

litisle-api（契约层，宿主服务与 review 共用）
└─ com.litisle.litisleapi
   ├─ dfa                     # 同步闸门全套：DfaFacade 门面 / AcdatTextScanEngine hankcs ACDAT 双数组 trie 引擎
   │                          #   / CharNormalizer 归一化 / TextScanEngine SPI / WordlibSnapshot 快照
   │                          #   / WordLibraryLoader 装载 SPI / WordlibChangedMessage 广播消息体
   ├─ moderation              # MachineRejectPunishGate（机审处罚记账收口）等发布侧门面
   └─ event.moderation        # 送审/结果/申诉结果/处罚 事件体契约（只含定位键不含正文）
```

**各包作用说明（litisle-review）：**

> - controller：HTTP 端点层，参数接收/登录态/@SaCheckRole 鉴权/@RateLimiter 限流，业务委托 service；feign 子包是 review 作为提供方的内部契约端点
> - service：六域业务编排（举报/人审任务/申诉/处罚/词库/用户名拒绝记录），事务边界与 CAS 统一在 impl；ReviewTaskUpsertService 是建任务唯一入口（幂等收口，禁旁路建任务）
> - moderation：异步机审全域——consumer 只做消费编排（手动 ACK 纪律），业务在 service 实现；adapter 是模型适配（换模型只动这层）；cache 是 Redis 读写边界
> - dfa：词库供给侧——任何词表变更都必须走 WordlibVersionService（version+1 同事务 + 审计 + 备份 + 广播），禁直改词表
> - mq/publisher：事件发布统一收口（禁业务类直调 RabbitTemplate）；sweeper：定时兜底（全部带 Redis SETNX+token 互斥锁，错峰调度）
> - interceptor + config：送审 QPS 护栏（防机审调用量洪峰烧模型费）与参数外置载体 ModerationProperties（Nacos 覆盖即时生效）

**各包作用说明（litisle-api，契约层）：**

> - dfa：同步闸门是「宿主本地执行」的硬约束（发布/注册/私信链路不依赖 review 可用性），所以引擎与快照结构必须下沉共享包——宿主预检与 review 消费端同源同快照，防两侧口径漂移
> - moderation / event.moderation：机审处罚记账门面与事件体契约；事件体只带定位键，正文一律回查（防事件体膨胀 + 防敏感内容散落 MQ）

## 一、同步词闸门：hankcs ACDAT 引擎（litisle-api dfa 包）

**功能描述：**

> - 发布/注册/私信等链路的**本地毫秒级**关键词闸门，内核零传递依赖（hankcs ACDAT 单 jar）、不依赖 review 可用性
> - 命中 HIGH 黑名单词直接拒绝（错误码 70001）；MID 词只标记不拦截（灰度观察）
> - 构建期归一化展开变体（繁简/全半角），扫描期 allow 区间过滤 + 最长优先裁决

**功能描述——详细版：**

> **引擎与 SPI**
>
> - `TextScanEngine` 是引擎 SPI，`AcdatTextScanEngine` 是唯一生产实现（hankcs AhoCorasickDoubleArrayTrie 双数组 trie 内核；演进：houbb → 自研 AC 自动机（review-08 批次3 ⑤b 删 houbb 依赖）→ hankcs ACDAT（2026-11-01 DFA 引擎切换批次，影子比对归因归零后收口））；换引擎加实现类即可
> - `CharNormalizer` 构建期归一化：繁简/全半角展开变体 pattern（叠词只长不短，文本侧永不折叠）——防「ｆｕｃｋ」「ｆúｃｋ」类变体绕过
> - `DfaFacade` 是宿主调用的统一门面：扫描后按 allow 区间过滤命中、最长优先裁决，输出 `DfaHitResult`（命中级别+命中词，供业务侧只回 hit/risk 不回吐词）
>
> **数据源与快照**
>
> - `WordLibraryLoader` 装载 SPI：宿主侧 Redis 快照优先（`WordlibSnapshot`）+ 本地兜底文件；review 侧 `TableWordLibraryLoader` 直查词表
> - `WordlibChangedMessage` 是 Redis Pub 广播消息体：词表变更后全实例收到即重建引擎（详见板块二）

**通俗版**：\
所有要「发出去」的文字，在各自服务里先过一道本地敏感词闸门——不用联网问审核服务，毫秒级出结果。\
命中「高危词」直接拒绝；命中「观察期词」先记下来但放行。\
词库构建时会把繁体、全角符号这类「变装写法」提前展开，扫描时按最长匹配裁决，防止用怪符号绕过。

代码部分:

> - DfaFacade.java：\
>   litisle\_api\dfa\DfaFacade.java：\
>   作用：\
>   同步闸门统一门面（预检与发布闸门同源同快照），扫描 + allow 区间过滤 + 最长优先裁决
> - TextScanEngine.java / AcdatTextScanEngine.java：\
>   litisle\_api\dfa\TextScanEngine.java、AcdatTextScanEngine.java：\
>   作用：\
>   引擎 SPI 与 hankcs ACDAT 双数组 trie 内核实现（内核零传递依赖；新增引擎实现类即可切换）
> - CharNormalizer.java：\
>   litisle\_api\dfa\CharNormalizer.java：\
>   作用：\
>   构建期归一化——繁简/全半角展开变体 pattern，防变体绕过
> - WordlibSnapshot.java / WordLibraryLoader.java / WordlibChangedMessage.java：\
>   litisle\_api\dfa\ 同包：\
>   作用：\
>   词库快照结构、装载 SPI（Redis 优先+本地兜底）、Pub 广播消息体契约

## 二、敏感词库管理与热更新（WordlibService + dfa 治理域）

**功能描述：**

> - 敏感词 CRUD/导入导出/列表（ADMIN），变更与 version+1 同事务，提交后广播全实例重建
> - 导入防 XXE/逐词校验，导出防 CSV 公式注入，列表防 LIKE 通配符放大
> - 无有效变更不推进版本不广播（防版本空转触发无意义全实例重建）

**功能描述——详细版：**

> **写操作铁律（所有变更入口同一套）**
>
> - 词合法性校验（长度 ≤64 + 禁空白/控制字符）→ 同场景查重 → 落 `sensitive_word_library` 与 `sensitive_word_version` version+1 **同事务** → 提交后写 Redis 快照 + Pub 广播重建 DFA
> - `updateWord` 返回是否有有效变更：修改前后全字段一致时返回 false，不写库、不推进版本、不广播
> - 服务端字段（updatedBy/version/createTime）服务端固化，防请求体越权改写；scene 沿用原行不可改
>
> **导入导出安全**
>
> - 导入：限类型大小、解析不启外部实体（防 XXE）、逐词校验；单词冲突跳过并计数，整体成功后 version+1 一次
> - 导出：CSV 文本 6 列；`=+/ -@` 开头单元格前置 `'`（防公式注入，审核员本地打开即执行）；含逗号/引号/换行按 CSV 标准转义
> - 列表：keyword 模糊查询对 `%_\` 转义（ESCAPE 声明），防通配符放大全表扫；出参 VO 不回吐审计字段，且附治理建议标记+证据字段（当前页词批量 IN 查询计算，详见板块三）
>
> **热更新链路**
>
> - `WordlibVersionService`：事务收口 + 推版本 + 审计落库（板块三）+ 触发备份（板块四）+ 写快照 + 发布 `WordlibChangedMessage`
> - `WordlibChangeSubscriber`：各实例订阅广播 → `WordlibRebuildExecutor` 重建本地 DFA 引擎（hankcs ACDAT 内核；重建期间旧引擎继续服务，原子切换）

**通俗版**：\
管理员对敏感词做的任何增删改，都严格遵守「改词表和升版本号必须同一个事务、提交后通知所有机器换词库」——保证全站每台机器的词库永远一致。\
导入词库时防止恶意文件骗解析器（XXE），导出的表格防止被 Excel 当公式执行；改了但等于没改的操作不会浪费一次全站重建。

代码部分:

> - WordlibService.java / WordlibServiceImpl.java：\
>   litisle\_review\service\WordlibService.java、service\impl\WordlibServiceImpl.java：\
>   作用：\
>   词库六域接口与实现——addWord（不传 level 默认 MID 灰度）/updateWord（无有效变更短路）/deleteWord（按 id 与按内容双入口）/import/export/list（listWords 出参附治理建议清单，板块三）+ reportMisreport（板块三）
> - WordlibController.java：\
>   litisle\_review\controller\WordlibController.java：\
>   作用：\
>   词库 HTTP 入口（ADMIN 端点 + 误报上报登录端点 @RateLimiter 1 分钟 5 次）
> - WordlibVersionService.java：\
>   litisle\_review\dfa\WordlibVersionService.java：\
>   作用：\
>   词表变更事务收口——version+1、审计、备份触发、快照写入、广播发布全在这一个门面
> - WordlibChangeSubscriber.java / WordlibRebuildExecutor.java：\
>   litisle\_review\dfa\ 同包：\
>   作用：\
>   订阅 Pub 广播并重建本地引擎（旧引擎服务到重建完成，原子切换）
> - TableWordLibraryLoader.java / DfaConfig.java：\
>   litisle\_review\dfa\ 同包：\
>   作用：\
>   review 侧查表装载实现与引擎装配配置
> - SensitiveWordLibrary.java / SensitiveWordVersion.java：\
>   litisle\_review\domain\entity\ 同包：\
>   作用：\
>   词表实体（level：1-HIGH 2-MID；isBlacklist 黑白名单；status 启停）与版本表实体

## 三、词库灰度治理与误报闭环（MID 观察 + wordlib\_misreport + wordlib\_hit\_stat 建议清单）

**功能描述：**

> - 新词默认 MID（只标记不拦截）观察，误报率达标后人工转正 HIGH；HIGH 误报可降级回 MID
> - 误报上报：HIGH 同步拒发的内容未发布走不了 appeal，独立入口回流
> - 词级命中统计：异步机审建任务处按快照 hitWords 逐词原子累加（wordlib\_hit\_stat，HIGH/MID 均统计）
> - 治理建议清单：词库列表出参附「建议标记+证据字段」（建议降级/转正确认/建议转正），运营只看清单不翻全量词库——标记只是证据，禁自动动作
> - 双数据源互补：Prometheus Counter 管场景级趋势告警（禁词级标签），wordlib\_misreport + wordlib\_hit\_stat 表管词级明细（转正/降级裁决证据）

**功能描述——详细版：**

> **灰度纪律（§2.5）**
>
> - `WordLevelEnum`：HIGH(1)=同步拦截（70001）/ MID(2)=只留痕不拦截；`addWord` 未显式传 level 默认 MID，`importWords` 必须显式传
> - MID 词依然被 AC 自动机持续命中（快照落 `review_task.layer1_hit=2` + hit\_words 留痕），只是裁决层不放杀权——用户无感知，所以 MID 阶段不存在「误报」
>
> **误报上报（reportMisreport）**
>
> - 校验：词必须存在于该场景**黑名单**（白/灰名单词不参与拦截，上报即污染数据），不存在抛 WORD\_NOT\_FOUND
> - 计数：`WordlibMetrics.incrementMisreport(scene)` —— 场景级 Counter `wordlib_misreport_total{scene}`，**标签只带 scene 不带 word**（防基数爆炸 + 防词库 oracle 泄密）
> - 落库：写 `wordlib_misreport` 明细行，失败仅 error 日志不阻断用户反馈动作（Counter 已计数 + warn 日志兜底）
>
> **明细表防刷设计（wordlib\_misreport）**
>
> - 唯一键 `(reporter_id, scene, word)`：同一人重复上报不新增行；Mapper 注解 SQL 原子 `report_count+1`（先累加→无行首插→并发撞唯一键降级再累加，防读-改-写竞态）
> - `level_snapshot` 固化上报时词级别：后续级别变更/删词不污染历史行裁决口径
> - reason 应用层防护：剥 CRLF（防日志注入）+ 截断 200
> - TTL：sweeper 每日 05:00 按 `last_report_time` 清理过期行（活跃误报不被误删；保留期 `retention-days` 默认 180，非正数=保留全量）
>
> **词级命中统计（wordlib\_hit\_stat，§5.4）**
>
> - 接线点：`ModerationTextService.persistAndPublish` 任务 CAS 流转成功后（recordId 非空=本次消费真实处理），`WordlibHitStatRecorder` 解析任务快照 hitWords（逗号 CSV）→ 去重（同任务同词只计一次，防 DFA 多次命中虚高）→ 逐词 upsert（先累加→无行首插→并发撞唯一键降级累加，与 misreport 同模板）
> - HIGH/MID 命中均累加：MID 转正建议的唯一量化依据就是异步命中观察量；图片场景不走 DFA 无 hitWords，结构性不接线
> - 口径局限（已知）：仅异步机审累加——同步闸门 HIGH 拦截在宿主本地不建任务不统计，HIGH 词分母缺失、误报率虚高，降级建议以误报绝对量为主、比率仅参考
> - 可靠性：单词失败仅 error 继续、整体不抛——统计属运营证据，绝不阻断审核主链路
>
> **治理建议清单（listWords 出参，标记只是证据）**
>
> - 三类口径（仅黑名单且启用中的词参与）：`SUGGEST_DEMOTE` 建议降级 = HIGH + 误报次数>0（绝对量为主）；`PROMOTE_CONFIRM` 转正确认 = HIGH + 样本≥min-hit-sample 且误报率\<gray-misreport-rate；`SUGGEST_PROMOTE` 建议转正 = MID + 样本≥min-hit-sample
> - 证据字段同 VO 回吐：hitCount（hit\_stat）/ reportCount+reporterCount（misreport 聚合，按人去重）/ misreportRate = report/hit（hit=0 或 report=0 时置 null，防 0/0 与误导性 0.0）
> - 实现：当前页词批量两次 IN 查询 + 内存按 (scene, word) 匹配（分隔符 '\n' 为词校验禁止的控制字符，防拼接歧义），禁逐词查询；`WordSuggestionMarkEnum` 纯展示不入库（无 @EnumValue）
> - Prometheus 词级标签路线已否决：序列基数膨胀 + /metrics 端点泄词库 oracle
>
> **转正/降级人工闭环**
>
> - 转正 MID→HIGH：ADMIN 调 updateWord；导航入口 = 建议清单「建议转正/转正确认」标记 + Counter 趋势（误报率低于 `gray-misreport-rate` 阈值）+ 明细表按 scene+word 聚合 report\_count 算词级误报率——**决策始终是人，标记/Counter/明细只提供证据，不触发自动动作**
> - 降级 HIGH→MID：确认误报后同一端点收回杀权（清单「建议降级」标记按误报绝对量>0 推荐）
> - 全部变更走板块二铁律：审计落 `wordlib_change_audit`（摘要形如 level: MID→HIGH）+ version+1 + 广播重建

**通俗版**：\
新敏感词不是一来就拦人，先进「试用期」（MID）：照常识别、留痕，但不拦，用户完全无感。\
每个词被机器审到多少次都有台账（命中统计）；真正被「高危词」拦住的用户可以喊冤（误报上报）——平台一边用计数器看每个场景误报多不多（趋势告警），一边在表里记下「谁、对哪个词、喊了几次冤」（转正证据）。\
同一个词同一个人喊一百次也只算一个人，但次数会累加；词以后降级或删了，历史记录里的级别快照不变。\
词库列表页会把台账汇总成「建议」标签：误报多的高危词标「建议降级」，观察期词命中量够了标「建议转正」——标签只是把证据摆到运营眼前，转不转正始终由运营拍板，系统绝不自动改词。

代码部分:

> - WordlibMisreport.java / WordlibMisreportMapper.java：\
>   litisle\_review\domain\entity\WordlibMisreport.java、mapper\WordlibMisreportMapper.java：\
>   作用：\
>   误报明细实体（level\_snapshot 快照列）与 Mapper（注解 SQL 原子累加，避免读-改-写竞态）
> - WordlibMisreportCleanupSweeper.java：\
>   litisle\_review\sweeper\WordlibMisreportCleanupSweeper.java：\
>   作用：\
>   每日 05:00 按 last\_report\_time 清理过期误报行（SETNX+token 互斥；retention-days 非正数=保留全量）
> - WordlibMetrics.java：\
>   litisle\_review\dfa\WordlibMetrics.java：\
>   作用：\
>   场景级埋点（误报 Counter + 重建 Counter，ObjectProvider optional 模式）；标签受控禁按 word 打点
> - WordlibHitStat.java / WordlibHitStatMapper.java / WordlibHitStatRecorder.java：\
>   litisle\_review\domain\entity\WordlibHitStat.java、mapper\WordlibHitStatMapper.java、dfa\WordlibHitStatRecorder.java：\
>   作用：\
>   词级命中统计实体 / 原子累加 Mapper（scene+word 唯一键去重）/ 记录器（dfa 包，异步机审任务流转成功后接线，整体不抛不阻断审核）
> - WordMisreportStatDTO.java / WordSuggestionMarkEnum.java：\
>   litisle\_review\domain\dto\WordMisreportStatDTO.java、enums\WordSuggestionMarkEnum.java：\
>   作用：\
>   误报聚合出参（sum(report\_count) 分子 + count(\*) 去重人数）与建议标记枚举（纯展示不入库，无 @EnumValue）
> - WordlibChangeAudit.java / WordlibChangeAuditMapper.java / WordlibAuditContext.java：\
>   litisle\_review\domain\entity、mapper 与 dfa 包：\
>   作用：\
>   词库变更审计表/持久层/审计上下文承载——「变更→误报→转正」闭环的变更侧留痕
> - 配置口：`litisle.moderation.wordlib.misreport.retention-days`（ModerationProperties.Wordlib.Misreport）；`gray-misreport-rate` / `min-hit-sample`（Wordlib 组，建议标记口径阈值，改 Nacos 即时生效）

## 四、词库备份与恢复（WordlibBackupStore SPI + 快照播种）

**功能描述：**

> - 每次词库变更事务提交后异步落 gz 冷备（按版本全量保留、可人工恢复）
> - review 启动时播种 Redis 词库快照，修复宿主（auth 等）冷启动死锁
> - 三层兜底互补：Redis 快照（热备）+ 本地文件（装载兜底）+ gz 冷备（按版本恢复）

**功能描述——详细版：**

> **备份链路（批次1 ①）**
>
> - `WordlibBackupStore` 是备份存储 SPI（`.csv.gz`，文件名统一口径 `{scene}-v{version}.csv.gz`）；实现类经 `@ConditionalOnProperty(wordlib.backup.enabled=true)` 注册，未激活时零开销跳过
> - `WordlibBackupTrigger` 挂在 WordlibVersionService afterCommit 之后：独立单线程 daemon 池串行落盘（防并发写同 scene 目录）；失败仅 error 告警不阻断变更；二期上 MinIO 新增实现类即可，调用方零改动
> - `LocalGzipBackupStore` 是本地磁盘实现
>
> **恢复纪律（红线）**
>
> - 恢复走**人工 runbook，禁自动回滚**（防旧备份把新词库误回滚）；`restore` 只读返回备份 CSV 文本，回灌必须经既有词库导入入口（逐词校验链自动生效），防被污染的备份绕过校验直写库；实现类禁止任何直写词表行为
>
> **快照播种（冷启动修复）**
>
> - `WordlibSnapshotSeeder`（SmartInitializingSingleton）：review 启动全单例就绪后逐场景检查 Redis 快照 key，缺失才从 DB 装载写入；已存在幂等跳过（不覆盖更新版本，避免与变更链路乱序竞争）——否则新环境 Redis 清空后 auth 侧两级数据源全空，initAll fail-fast 起不来

**通俗版**：\
每次词库一变更，后台自动把当前版本全量打包存档（不打扰主流程）；真出事时由人按手册取历史版本，走正常导入流程回灌——绝不让程序自动「时光倒流」，防止旧备份覆盖新词库。\
另外服务启动时会检查 Redis 里的词库快照，发现没有就自动从数据库补一份，否则其他服务冷启动会因为没有词库数据而拒绝启动。

代码部分:

> - WordlibBackupStore.java：\
>   litisle\_review\dfa\WordlibBackupStore.java：\
>   作用：\
>   备份存储 SPI（save/restore + 文件名统一口径 + 恢复红线注释）；新增存储后端实现此接口即可
> - LocalGzipBackupStore.java：\
>   litisle\_review\dfa\LocalGzipBackupStore.java：\
>   作用：\
>   本地 gz 冷备实现
> - WordlibBackupTrigger.java：\
>   litisle\_review\dfa\WordlibBackupTrigger.java：\
>   作用：\
>   afterCommit 异步备份触发器（单线程串行 + 失败不阻断 + 未激活零开销）
> - WordlibSnapshotSeeder.java：\
>   litisle\_review\dfa\WordlibSnapshotSeeder.java：\
>   作用：\
>   Redis 快照播种器（冷启动缺口修复，幂等不覆盖）
> - 配置口：`litisle.moderation.wordlib.backup.*`

## 五、异步机审：文本/图片消费者与三分态（moderation 包）

**功能描述：**

> - MQ 消费驱动 LLM 文本 / ONNX 图片模型，输出 PASS / REJECT / SUSPICIOUS 三分态
> - 幂等 upsert 建任务 + 版本化缓存查重 + 切片聚合（任一片 REJECT 即整文 REJECT）
> - 故障降级宁严勿松：重试耗尽转人审，绝不默认放行

**功能描述——详细版：**

> **消费编排**
>
> - `ModerationTextConsumer` / `ModerationImageConsumer`：手动 ACK 纪律（成功 ack / 业务性失败 warn 后 ack 防毒丸 / 非预期异常指数退避经延迟交换机重投，耗尽 nack 入 DLQ）
> - `ModerationDlqConsumer`：DLQ 消费失败一律 ack，任务停留 status=0 由 sweeper 兜底转人审
>
> **建任务幂等（ReviewTaskUpsertService，唯一入口）**
>
> - 定位键 = bizType + bizId + scene（复审与文本任务同 biz 同 id 并存，scene 必带）；唯一键 `uk_biz_scene`
> - content\_hash 变化或旧任务已终态 → 追加 superseded 留痕后重置（清 assignee/机审证据列，retry\_count 归零）；hash 未变且在审 → 只复用回填，**不打断已认领任务**
> - 重置目标态：待机审场景置 0；举报复审场景（除图片复审外）置 2 直进人审
>
> **SPI 与适配器**
>
> - `ContentModerationService` 门面 + `LlmTextModerationAdapter`（防注入三件套）/ `OnnxImageModerationAdapter`；换模型只动 adapter 层
> - 文本按段落分片（字符窗口 overlap 硬切），任一片 REJECT 即整文 REJECT
> - provider 配置错配由启动自检 fail-fast 拦截（`ModerationProviderStartupChecker`）
>
> **三分态落库与联动**
>
> - `ModerationTextService` / `ModerationImageService`：三分态落 `moderation_record`（append-only 留痕）+ `ModerationResultPublisher` 发 result 事件（业务方 CAS 回写，review 不跨库直写业务表）
> - REJECT 联动处罚：`RejectPunishAdapterHost` 经 litisle-api `MachineRejectPunishGate` 适配收口（同事务 punish\_log(action=3) + violation\_score 累计；事件 afterCommit 发）
> - 用户名场景特殊：USERNAME 同步 DFA 拒绝不建任务，只落 record + `UsernameRejectRecordService` 留痕

**通俗版**：\
异步机审像一条流水线：内容进队列，先查「这道题以前判过没有」（缓存），没判过才叫模型来判。\
文字太长就切段，任何一段不合格整篇不合格。\
模型挂了就一直重试，实在不行把内容转给人工——**宁可多等人，绝不放过去**。\
判「违规」的同时顺手记账（违规分+1、处罚流水落行），但通知别人的消息一定等账记完才发。

代码部分:

> - ModerationTextConsumer.java / ModerationImageConsumer.java / ModerationDlqConsumer.java：\
>   litisle\_review\moderation\consumer\ 同包：\
>   作用：\
>   三消费者（消费编排 + 手动 ACK 纪律），业务委托 moderation.service
> - ReviewTaskUpsertService.java / ReviewTaskUpsertServiceImpl.java：\
>   litisle\_review\service\ 及 impl：\
>   作用：\
>   建任务唯一入口（uk\_biz\_scene 幂等 upsert / superseded 留痕 / 复用不打断已认领）
> - ModerationTextService.java / ModerationImageService.java：\
>   litisle\_review\moderation\service\ 同包：\
>   作用：\
>   机审业务实现（调 SPI/切片聚合/三分态落库/result 事件发布；文本任务 CAS 流转成功后按快照 hitWords 累加词级命中统计，板块三）；图片机审取图一律按 fileId 走 FileClient 字节流（SSRF 防线）
> - ContentModerationService.java / ModerationResult.java：\
>   litisle\_review\moderation\spi\ 同包：\
>   作用：\
>   机审 SPI 门面与三分态结果契约
> - LlmTextModerationAdapter.java / OnnxImageModerationAdapter.java：\
>   litisle\_review\moderation\adapter\ 同包：\
>   作用：\
>   模型适配（LLM 防注入三件套；ONNX 信号量并发闸）；换模型只动这层
> - RejectPunishAdapterHost.java：\
>   litisle\_review\moderation\service\RejectPunishAdapterHost.java：\
>   作用：\
>   机审 REJECT 处罚记账适配收口（经 MachineRejectPunishGate，禁另起分支）
> - ModerationProviderStartupChecker.java / TextModerationAdapterProvider.java / ImageModerationAdapterProvider.java：\
>   litisle\_review\moderation\config\ 同包：\
>   作用：\
>   启动自检 fail-fast（配置与 Bean 不匹配即启动失败）与 ObjectProvider 惰性注入收敛
> - ModerationProperties.java：\
>   litisle\_review\moderation\config\ModerationProperties.java：\
>   作用：\
>   机审域参数外置载体（provider 选择/QPS/重试/词库/告警阈值/保留期等全组配置的挂载点，新功能参数统一加在这里）

## 六、结果缓存与免审键（版本化 key）

**功能描述：**

> - 四类 Redis key 全版本化：文本结果/图片结果/拒传黑名单/发布侧免审
> - 仅缓存终局结论（PASS/REJECT），SUSPICIOUS 挂既有任务不缓存
> - TTL ±10% 随机抖动防雪崩；删除/下架/转私密必须失效相关键

**功能描述——详细版：**

> - 文本结果 `review:text-result:{wordlibVer}:{fewShotVer}:{scene}:{hash}`：词库或 prompt 换版自动失效旧结论；命中仍追加 moderation\_record（留痕不省）
> - 图片结果 `review:img-result:{provider}:{modelVersion}:{sha256}`：换模型不复用旧结论
> - 拒传黑名单 `review:img-blacklist:{sha256}`：REJECT 图片 TTL 30 天不随版本失效；申诉平反时移除
> - 免审键 `review:last-hash:{wordlibVer}:{bizType}:{scene}:{bizId}`：hash 未变 + PASS + 非驳回态才免审（宿主侧 `LastHashExemptChecker` 消费，**免文不免图**——配图仍发复审）
> - `ImageAppealCacheInvalidator`：申诉按留痕行重建键精确失效（防聚合指纹错位漏失效）
> - Redis 不可用时跳过缓存直调模型（多花钱不漏审）

**通俗版**：\
判过的内容把结论记在小本本上，本子按「词库版本+模型版本」编页码——词库或模型一升级，旧结论自动作废重判。\
被拒过的图片记进黑名单短期内不许再传；申诉成功就把名字从黑名单划掉。\
内容没改且上次过了，下次发布直接放行——但配图永远要重新审。缓存挂了就多花钱直调模型，绝不因为省钱漏审。

代码部分:

> - ModerationResultCache.java：\
>   litisle\_review\moderation\cache\ModerationResultCache.java：\
>   作用：\
>   版本化缓存读写收口（四类 key 拼装口径、TTL 抖动、仅终局结论入缓存）
> - ImageAppealCacheInvalidator.java：\
>   litisle\_review\moderation\cache\ImageAppealCacheInvalidator.java：\
>   作用：\
>   申诉平反按留痕行重建缓存键精确失效（防聚合指纹错位）

## 七、人审工作流（ReviewTaskService + ReviewTaskSweeper）

**功能描述：**

> - SUSPICIOUS/举报复审/DLQ 兜底统一进 review\_task 人工队列
> - 队列 → 详情 → 认领（CAS 防抢）→ 裁决（终态 CAS + 处罚/举报回写联动）
> - sweeper 兜底：认领超时回池、申诉超时维持、stuck 分频重投、三态 Gauge

**功能描述——详细版：**

> - `listQueue`：REVIEWER/ADMIN；layer2\_risk 降序 + create\_time 升序；配额校验（max-active-per-reviewer=20 防单人占满队列）
> - `getTask`：含机审三分态/命中词快照/证据图（证据经 FileClient 批量取预签名 URL）
> - `claimTask`：CAS `where id=? and status=2 and assignee is null`，affected=0 抛 70004（已被他人认领）；写 assignee + claim\_time（sweeper 超时回池基准）
> - `decideTask`：终态 CAS（where status=2）置 {3,4}，天然拦截重复裁决；REJECT 联动内容下架事件 + punish\_log 落行 + violation\_score 原子累加 + 举报回写；人审直接封禁需 `confirm=true` 双签留痕（moderation\_record mod\_type=6）+ punish.result 事件（auth 消费强制下线全会话）
> - `ReviewTaskSweeper`（fixedDelay 60s 默认，四职责顺序执行互不依赖）：认领超时回池 / 申诉超时维持 / 三态 Gauge 埋点 / stuck 任务分频重投

**通俗版**：\
机器拿不准的内容都堆进人工待办池。审核员先看队列、再看详情（带着机器给的证据），点「认领」——手慢了会被同事抢走（系统直接提示已被认领）。\
裁决按状态条件更新，两个人同时裁决也只有一个人成功；要直接封号这种重操作，必须额外勾确认，并留下双签记录。\
审核员领了任务却睡着了？定时任务会把超时任务收回来重新进池。

代码部分:

> - ReviewTaskService.java / ReviewTaskServiceImpl.java：\
>   litisle\_review\service\ReviewTaskService.java、service\impl\ReviewTaskServiceImpl.java：\
>   作用：\
>   人审四步接口与实现（队列/详情/CAS 认领/终态裁决+联动）
> - ReviewTaskController.java：\
>   litisle\_review\controller\ReviewTaskController.java：\
>   作用：\
>   人审 HTTP 入口（REVIEWER/ADMIN）
> - ReviewTaskSweeper.java：\
>   litisle\_review\sweeper\ReviewTaskSweeper.java：\
>   作用：\
>   人审兜底清扫（认领超时回池/申诉超时维持/Gauge/stuck 重投，间隔外置）
> - ReviewTask.java / ReviewTaskVO.java / Layer1HitEnum.java / Layer2RiskEnum.java：\
>   litisle\_review\domain\ 与 enums\：\
>   作用：\
>   任务实体（layer1\_hit 快照 0/1/2）、出参、DFA 命中级别与二级风险枚举

## 八、举报链路（ReportService）

**功能描述：**

> - 提交举报：防自举报 + 活跃举报去重 + 证据登记 + DFA 初审分流
> - 撤回：仅本人、仅未裁决，已建任务不撤销

**功能描述——详细版：**

> - `submitReport`：reporter ≠ 目标 owner → 活跃举报去重（active\_key 兜底）→ 落 report + 证据关联（report\_evidence）→ 同步 DFA 初审分流：HIGH 命中建 review\_task 转人工 / 低风险机审直处理
> - `withdrawReport`：CAS `where reporter_id=? and status<3` 置 4-已撤回；已建 review\_task 不撤销（进池的任务由人审闭环收口）
> - `getInternalReport`：内部契约提供方（ReviewFeignProviderController；网关不路由 /api/feign/\*\*）

**通俗版**：\
用户可以举报违规内容：不能自己举报自己、同一目标未结案不能重复举报、证据要登记。\
举报内容本身也要先过敏感词初筛——明显违禁的直接转人工。\
举报提交后可以撤回，但只限于还没出结论的；已经进了审核池的，撤回也撤不掉，由审核员按流程收口。

代码部分:

> - ReportService.java / ReportServiceImpl.java：\
>   litisle\_review\service\ReportService.java、service\impl\ReportServiceImpl.java：\
>   作用：\
>   举报接口与实现（防自举报/active\_key 去重/证据登记/DFA 初审分流/CAS 撤回）
> - ReportController.java：\
>   litisle\_review\controller\ReportController.java：\
>   作用：\
>   举报 HTTP 入口（登录 + 写限频）
> - Report.java / ReportEvidence.java：\
>   litisle\_review\domain\entity\ 同包：\
>   作用：\
>   举报主表与证据关联表实体
> - ReportStatusEnum.java / AbuseFlagEnum.java：\
>   litisle\_review\enums\ 同包：\
>   作用：\
>   举报状态与诬告标记枚举（诬告反制参数 abuse-threshold 随人审 decide 接线）

## 九、申诉链路（AppealService）

**功能描述：**

> - 提交申诉：归属校验 + 双层防重；裁决：仅 ADMIN + 回避 + 高风险双签
> - 改判联动：撤销处罚/违规分重算/活跃举报回写/缓存失效/appeal.result 事件

**功能描述——详细版：**

> - `submitAppeal`：appellant 与内容 owner 一致校验（biz\_type→report.target\_type 映射回查归属，防替别人申诉）→ 双层防重（关联列级 + 内容级 status=0）→ 落 appeal
> - `judgeAppeal`：仅 ADMIN（REVIEWER 不可自裁）+ 原驳回裁决人回避 + 改判撤销高风险需 confirm=true 双签
>   - 维持 → status=1；撤销 → status=2 + punish\_log 撤销 + violation\_score 重算 + 同内容活跃举报批量回写 status=3（按 target\_type+target\_id，非仅本举报）
>   - 缓存同步失效（text-result/img-result/last-hash + 黑名单移除 sha256）
>   - 两分支事务提交后均发 `appeal.result`（reviewTaskId 作 notification 幂等键）；下游恢复由 file/content/message 消费侧执行

**通俗版**：\
被驳回/下架的内容，作者可以申诉——但只有本人能申诉、同一内容未结案不能重复申诉。\
裁决必须换人（原审核员回避），要翻「封禁」这种重判必须二次确认。\
申诉成功是「连坐式恢复」：处罚撤销、违规分重算、相关举报全部标记误报、各类缓存失效，最后广播出去让文章/图片/私信各服务各自恢复。

代码部分:

> - AppealService.java / AppealServiceImpl.java：\
>   litisle\_review\service\AppealService.java、service\impl\AppealServiceImpl.java：\
>   作用：\
>   申诉接口与实现（归属校验/双层防重/ADMIN 回避双签/改判全量联动/事件发布）
> - AppealController.java：\
>   litisle\_review\controller\AppealController.java：\
>   作用：\
>   申诉 HTTP 入口（提交=作者本人；裁决=ADMIN）
> - ModerationAppealResultPublisher.java：\
>   litisle\_review\mq\publisher\ModerationAppealResultPublisher.java：\
>   作用：\
>   appeal.result 事件发布收口（afterCommit 铁律）
> - Appeal.java / AppealStatusEnum.java：\
>   litisle\_review\domain\entity 与 enums：\
>   作用：\
>   申诉实体与状态枚举

## 十、处罚与违规分（PunishService + punish.result）

**功能描述：**

> - punish\_log 处罚流水 + violation\_score 违规分累计；禁言/封禁阈值按生效行重放判定
> - 处罚权威态快照供 auth/content 覆盖式回查；punish.result 事件驱动 auth 强制下线
> - 双向对账 sweeper 校正 review 与 auth/user 两侧处罚状态漂移

**功能描述——详细版：**

> - 机审 REJECT：action=3（记分不计入阈值判定）；禁言/封禁阈值判定只重放 action∈{1,2} 的 status=1 生效行
> - `getPunishStatusSnapshot`：聚合 action∈(1,2) 且 status=1 行为快照；无生效行返回全空快照——auth 消费端**无论撤销/生效一律以本快照覆盖本地**（天然幂等=同步解封）
> - `punish.result` 事件（userId/action/op/punishLogId/frozenUntil/traceId）：人审封禁 frozenUntil=null 永久，auth 消费端 kickout 强制下线全会话；发布端收口在 MachineRejectPunishGate 步骤4 + 人审 decideTask（同一口径禁另起分支）
> - `PunishReconcileSweeper`（默认 10 分钟）：review 与 auth/user 两侧处罚状态双向对账
> - `listMyPunish` / `getActivePunishes`：仅本人 / 仅 status=1 生效行（登录判定/发帖前拦截消费）

**通俗版**：\
违规就记账：轻的扣分，重的禁言/封号。\
其他服务想知道「这人现在能不能发言」，不猜、不缓存旧结论，直接来要一份「当前处罚快照」，拿到什么就覆盖成什么——所以解封即时生效，不会有「明明解封了还被拦」。\
封号消息一发出去，登录服务立刻把他所有设备踢下线。\
两边账目每天自动对好几轮，对不上的以 review 为准修正。

代码部分:

> - PunishService.java / PunishServiceImpl.java：\
>   litisle\_review\service\PunishService.java、service\impl\PunishServiceImpl.java：\
>   作用：\
>   处罚接口与实现（流水查询/生效态查询/权威态快照）
> - PunishReconcileSweeper.java：\
>   litisle\_review\sweeper\PunishReconcileSweeper.java：\
>   作用：\
>   处罚双向对账（cron 外置，默认 10 分钟）
> - PunishResultPublisher.java：\
>   litisle\_review\mq\publisher\PunishResultPublisher.java：\
>   作用：\
>   punish.result 事件发布收口
> - PunishLog.java / ViolationScore.java / PunishActionEnum.java / PunishStatusEnum.java / LastPunishActionEnum.java：\
>   litisle\_review\domain\entity 与 enums：\
>   作用：\
>   处罚流水/违规分实体与动作/状态/末次动作枚举
> - ReviewFeignProviderController.java：\
>   litisle\_review\controller\feign\ReviewFeignProviderController.java：\
>   作用：\
>   处罚权威态等内部契约提供方（getPunishStatus 供 content 申诉改判前回查：封禁中不放行）

## 十一、兜底清扫与观测告警（sweeper 组 + QPS 护栏 + 指标）

**功能描述：**

> - 五个定时清扫任务错峰兜底（多实例部署时靠「Redis 抢锁+令牌」保证同一时刻只有一台在干活：抢不到锁直接跳过本轮、锁超时自动失效、释放前核对令牌归属防误删别人的锁）
> - 全站 QPS 限流护栏 + 模型级并发闸，防机审调用量洪峰打穿模型后端
> - AlertThresholdMetrics 把各条告警的触发阈值做成监控指标，告警规则引用指标而非写死数字

**功能描述——详细版：**

> **sweeper 错峰时刻表**
>
> | Sweeper                        | 调度                   | 具体做什么                            |
> | ------------------------------ | -------------------- | ----------------------------------- |
> | ReviewTaskSweeper              | 60 秒一轮（间隔可配置）  | ①审核员认领后超时未裁决→任务收回待审池、重新可被认领 ②申诉超 7 天无人裁决→系统自动按「维持原判」收口并通知作者 ③刷新三条积压监控读数（待机审/无人认领/已认领未裁决）与死信队列堆积深度 ④疑似丢消息、长期卡在待审的任务→定期重投 MQ 重新触发机审 |
> | PunishReconcileSweeper         | 默认每 10 分钟（可配置） | ①已被封禁但账号还在线→补踢下线（防「踢下线」事件丢失导致漏封） ②处罚已撤销但登录侧还记着「封禁/禁言」→触发登录侧回查解封（防「解封」事件丢失导致误伤） |
> | QuarantineSweeper              | 每天 03:00（可配置）     | 委派文件服务「到期软删」超过保留期（默认 10 天）、最新结论仍是违规、且没有待裁决申诉的隔离图片（申诉中的先不删、顺延下轮）；软删只退出业务可用态、不删存储对象，软删缓冲 7 天内可人工恢复，期满由文件侧对账任务物理删除（不可恢复）；批量删除按每分钟 N 张限速（软删已不碰存储，限速主要保护文件服务与数据库） |
> | WordlibMisreportCleanupSweeper | 05:00                | 删除「最后一次上报时间」超过保留期（默认 180 天）的误报反馈记录；仍被用户持续上报的活跃记录不会删（重复上报会刷新时间戳） |
> | ModerationRecordArchiveSweeper | 归档占位                 | **默认关**（archive-enabled）、二期占位未实现：计划把超过合规留存期（6 个月）的机审留痕迁到历史表后删热表行，防热表无限膨胀 |
>
> **这些任务解决什么问题**：正常业务由 MQ 消费和用户操作驱动，但消息可能丢、审核员可能离线、申诉可能没人管、数据总有该清的——sweeper 只负责捞这些「例外」，是补偿兜底而非主链路（系统健康时每轮只是几次索引查询，毫秒级返回）。
>
> **QPS 护栏两层——第一层管「内容进来的速度」，第二层管「钱花出去的速度」**
>
> - `ModerationGlobalQpsInterceptor`（第一层·入口限流）：拦 HTTP 送审接口的提交流量——流量超限时新内容继续排队等机审，不拒绝用户，只是结果晚一点出
> - `ModerationModelQpsGuard`（第二层·模型调用限流）：拦 MQ 消费里「真实调用 AI 模型」这个花钱动作——文本按段落逐片调、图片逐张调，**每次真调模型前先申请一个全站调用名额，名额用完就先不调**。它是烧钱前的最后一道闸，专门防 Redis 缓存故障的放大场景：缓存挂了，「查缓存免重复调用」就失效，所有内容都直调模型，调用量瞬间放大 N 倍
>   - 超限了怎么办：不拒绝用户、不丢内容——抛异常让 MQ 消息延迟重试，内容保持「待机审」，晚点再调模型，绝不默认放行
>   - Redis 也挂了怎么办：自动切本地计数兜底（阈值更严格），宁可多花点钱直调模型，不漏审
>   - 与第一层的关系：两层计数各自独立、互不挤占配额；阈值共用同一配置口（`litisle.moderation.qps.*`，Nacos 改了即时生效）
>
> **监控埋点三组**
>
> - `ModerationMetrics`：机审域计数——调用量、模型耗时、缓存命中、处罚联动次数等；项目未接监控组件时自动跳过，零开销
> - `WordlibMetrics`：词库域计数——误报次数、词库重建次数；**统计只到「场景」粒度、不按具体词打标**——按词拆分会让监控数据量无限膨胀，且监控接口会把词库内容泄露出去
> - `AlertThresholdMetrics`：把六条告警的触发阈值（存疑积压/死信堆积/机审失败率/通过率回滑/缓存命中跌落等）做成监控指标暴露出去，告警规则引用指标而非写死数字——阈值改在 yml/Nacos 里即时生效，不用改告警规则文件

**通俗版**：\
五个定时任务各管一摊、时间错开跑（高频的 60 秒/10 分钟一轮，清理类的排在半夜 3 点和 5 点互不打架）；多实例部署时靠「抢锁+令牌」保证同一时刻只有一台在干活——抢不到的直接下班，锁坏了靠过期时间自愈。\
它们全是「补漏工」：正常流程轮不到它们出手，只在「消息丢了、审核员离线了、申诉没人管、数据该清了」时兜底收口。\
所有机审调用都有两层闸门限速，防止一波流量把模型后端打垮、烧掉冤枉钱。\
告警阈值放在配置里而不是写死在规则文件，运营改个数字立马生效。

代码部分:

> - sweeper 包五件套：\
>   litisle\_review\sweeper\ReviewTaskSweeper.java、PunishReconcileSweeper.java、QuarantineSweeper.java、WordlibMisreportCleanupSweeper.java、ModerationRecordArchiveSweeper.java：\
>   作用：\
>   见上表；新 sweeper 照此模板：Redis 抢锁+令牌互斥 / 单条异常 catch 住不外抛（下轮重试）/ 调度参数用配置占位符直读（Nacos 可调）
> - ModerationGlobalQpsInterceptor.java + ModerationWebConfig.java：\
>   litisle\_review\interceptor\ 与 config\：\
>   作用：\
>   送审全站 QPS 护栏与拦截器注册
> - ModerationModelQpsGuard.java：\
>   litisle\_review\moderation\ModerationModelQpsGuard.java：\
>   作用：\
>   模型级 QPS/并发闸（ONNX 信号量 permits≈CPU 核数）
> - ModerationMetrics.java / WordlibMetrics.java / AlertThresholdMetrics.java：\
>   litisle\_review\moderation\service、dfa\ 与 moderation\config\：\
>   作用：\
>   三组监控计数载体（业务类经 ObjectProvider 可选注入，监控未接入零开销）
> - 配置总口：ModerationProperties（前缀 `litisle.moderation`）+ application.yml；新增可调参数一律挂这里，禁散落硬编码

