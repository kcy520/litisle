# LitIsle Review 模块业务指南（功能开发向）

> 定位：**快速理解审核域业务 + 学习各板块实现逻辑**，格式对齐 [Content-Business-Guide.md](Content-Business-Guide.md)；决策推演详见 [Review-Architecture-Development-detailed.md](Review-Architecture-Development-detailed.md)。

> 阅读方式：每个板块固定六段——**功能描述**（速览）→ **功能描述——详细版** → **链路位置** → **详细版**（真实类名+关键逻辑）→ **通俗版**（无代码名）→ **代码部分**（类与包路径）。先读全景图，再按需跳板块。

## 〇、全景图：一次文章发布的完整旅程

发布请求\\

→ ①同步 DFA 闸门（HIGH 拦 70001，MID 标记放行）\\

→ ②前置校验（content\_hash=sha256(标题+正文)）\\

→ ③落库待审 + 发 text.submit（事件体不含正文，消费端回查复算）
\
→ ④机审消费（幂等 upsert → 缓存查重 → SPI 送 LLM → 三分态）\\

→ ⑤人审认领 CAS → 裁决终态 CAS；作者申诉改判联动（缓存失效/举报回写/处罚撤销）
\
→ ⑥sweeper 兑底（回池/重投/隔离清理/归档/对账）。
全程 moderation\_record append-only 留痕；result / appeal.result / punish 三事件驱动下游服务。

<br />

<br />

## 架构设计

```
litisle-review
└─ com.litisle.litislereview
   ├─ controller              # HTTP 入口（审核员/运营/作者操作端点）
   │  └─ feign                # 对外 Feign 提供方（处罚状态回查等跨服务端点）
   ├─ service                 # 业务编排六域（人审/举报/申诉/处罚/词库/任务幂等）
   │  └─ impl                 # 接口实现
   ├─ domain
   │  ├─ entity               # 表实体（review_task/moderation_record/appeal/punish_log 等）
   │  ├─ dto                  # 入参对象（裁决/申诉提交等）
   │  └─ vo                   # 出参对象（禁裸实体暴露内部字段）
   ├─ mapper                  # 持久层 Mapper
   ├─ enums                   # 领域枚举（任务状态/裁决类型/处罚动作/举报状态等）
   ├─ dfa                     # 词库数据源与热更（板块一 review 侧：装载/版本推进/订阅）
   ├─ moderation              # 异步机审域（板块二/四/六主体）
   │  ├─ consumer             # 送审消费者（文本/图片/DLQ）
   │  ├─ service              # 机审编排（查重→送审→落库→发布）与处罚记账宿主适配
   │  ├─ spi                  # 机审送审入口（分片/防注入编排）
   │  │  └─ dto               # 送审请求/结果对象
   │  ├─ adapter              # 模型适配器（LLM 文本 / ONNX 图片）
   │  ├─ cache                # 版本化结论缓存 / 改判缓存失效
   │  ├─ config               # 参数外置 / 装配开关 / Rabbit 声明 / 告警阈值
   │  └─ ModerationModelQpsGuard  # 模型 QPS 防护（直接在 moderation 包下）
   ├─ mq
   │  └─ publisher            # result / punish / appeal.result 三类事件发布收口
   ├─ sweeper                 # 四大定时兑底（板块六：回池/隔离清理/归档/对账）
   ├─ interceptor             # Feign 提供方全局 QPS 拦截
   └─ config                  # Web / SaToken 配置

litisle-api（契约层，宿主服务与 review 共用）
└─ com.litisle.litisleapi
   ├─ dfa                     # DFA 引擎门面/快照结构/装载 SPI（板块一判定口径 SSOT）
   ├─ moderation              # 发布侧门面 10 组件（板块七：闸门/免审/私信/收口）
   ├─ event.moderation        # 五类事件体（text/image.submit、result、appeal.result、punish）
   ├─ dto.moderation          # 处罚回查/取证等跨服务 DTO
   ├─ feign                   # ReviewClient 等跨服务客户端
   └─ constant                # MQ 路由键/缓存键/常量 SSOT（禁私自新建交换机）
```

**各包作用说明（litisle-review）：**

> - controller：HTTP 端点层，只做参数接收/登录态/限流，业务委托 service；其下 feign 子包是 review 作为提供方暴露给其他服务的 Feign 端点（如 auth 申诉前回查处罚状态）
> - service：六个业务域的编排层（人审任务/举报/申诉/处罚/词库/送审幂等），事务边界与 CAS 收口都在实现类；impl 子包为接口实现
> - domain：数据契约——entity 对应数据库表；dto 是入参（防内部字段透传）；vo 是出参（脱敏后返回）
> - mapper：MyBatis 持久层，CAS 条件更新（认领/裁决/改判）都定义在这层
> - enums：领域状态与类型枚举，状态机白名单（1→2/1→3/2→4 等）的代码化载体
> - dfa：板块一在 review 侧的三个职责——TableWordLibraryLoader 供词库数据、WordlibVersionService 版本 SSOT、WordlibChangeSubscriber 热更订阅
> - moderation：异步机审域主体，按职责再分六子包：consumer 接消息、service 编排、spi 定义送审入口、adapter 隔离具体模型、cache 管结论缓存、config 外置参数与 MQ 声明
>   - `moderation\service` 是 异步机审域的“业务逻辑层” ——它位于消费者（consumer，只负责接消息）和模型适配器（adapter，只负责调模型）之间，里面 5 个类各管一摊
>   - 编排（orchestration）= 按顺序协调多个步骤，但自己不做具体的专业工作 。拿`ModerationTextService` 消费一条文本的过程看，它像一个“流程调度员”：
>     ```
>     ① 缓存查重 ──→ 调 cache 包："这内容审过吗？"
>     ② dedup 抢锁 ─→ 调 Redis："没审过？我先锁上防并发"
>     ③ SPI 送审 ──→ 调 spi/adapter 包："交给 LLM 判一下"（它不关心 LLM 怎么实现的）
>     ④ 落库 ──────→ 调 mapper："结论写 review_task + moderation_record"
>     ⑤ 发事件 ────→ 调 mq/publisher："提交事务后通知下游"
>     ```
>   - 各个Service职责与作用:
>     ```
>      ├─ service              # 机审域业务逻辑层（流程调度员：只协调顺序与分支，不亲自查缓存/调模型/写 SQL）
>       │  ├─ ModerationTextService     # 文本机审主编排：缓存查重→dedup 抢锁（成本护栏）→SPI 送 LLM→三分态落库→发事件
>       │  ├─ ModerationImageService    # 图片机审编排：与文本同构，走 ONNX 适配器
>       │  ├─ ModerationDlqService      # 死信兜底处置：绝不默认放行
>       │  ├─ RejectPunishAdapterHost   # 处罚记账宿主适配：机审/人审双链路统一收口
>       │  └─ ModerationMetrics         # 指标埋点：标签收敛防基数爆炸
>     ```
> - mq：事件发布收口，全部 afterCommit 发布，禁止各业务类直接调 RabbitTemplate
> - sweeper：补偿型定时任务，绝不默认放行的最后一道网
> - interceptor / config：横切关注点——Feign 提供方限流拦截、Web 与鉴权配置

**各包作用说明（litisle-api，契约层参考）：**

> - dfa：引擎与快照结构下沉于此，宿主与 review 共用同一判定口径（板块一）
> - moderation：发布/注册/私信三链路前置决策的 SSOT（板块七），事件体组装、免审判定、处罚与缓存失效收口都在这里
> - event.moderation / dto.moderation：跨服务事件体与 DTO，事件体只含定位键不含正文
> - feign：宿主调 review 的客户端（处罚状态回查、取证留痕等）
> - constant：路由键/缓存键唯一来源，禁各服务私自新建交换机或硬拼 key

## **额外说明：**

### 不同模块的 dfa 包

> **api\dfa 定规则，review\dfa 管数据，业务模块\dfa 做接入。**
>
> - api\dfa ：这个匹配引擎的代码本体 +`check` 方法 + 词库快照结构——所有模块共用这一份
> - review\dfa ：词库数据在哪（sensitive\_word\_library 表）、版本号怎么推进、变更怎么广播—— 喂词给引擎的源头
> - content\dfa ：怎么接收词库变更广播、把新词装进本服务的引擎快照—— 引擎在宿主侧的电池 （每次 check 用的都是本地装好的词库快照，不查库不查远程，所以才毫秒级）
>
> dfa 是 接口/实现关系 （api 定义 WordLibraryLoader，review/content 各写实现）；moderation 不是 ——api\moderation 的 12 个组件全是自带实现的可执行类，review\moderation 不实现它们，而是 流程下游的另一段独立实现 。两者靠 MQ 事件衔接，唯一真正的接口/实现只有`LastHashStore ← ContentLastHashRedisAdapter` 一处。
>
> 1. **litisle-api\dfa —— 引擎本体 + 共用合同（7 个类）**
>
> - 提供 DfaFacade.java （`check(scene, text)` 判定入口）、结果结构（DfaHitResult）、词库快照结构（WordlibSnapshot/WordEntry）、场景归一（DfaScenes）
> - 定义 WordLibraryLoader（SPI 接口） ：只规定「词从哪来」的合同，自己不查任何数据源
> - 所有模块调它做判定 ，保证「HIGH 拦截、MID 标记」口径全站唯一
> - 它 不含词库数据 ，也不管数据存哪
>
> 1. **litisle-review\dfa —— 词库数据源头 + 版本权威（6 个类）**
>
> - 词库存在 review 的`sensitive_word_library` 表里， review 是数据的唯一拥有方（写侧）
> - TableWordLibraryLoader.java ：SPI 接口的 review 实现—— 查数据库 供词
> - WordlibVersionService.java ： 版本 SSOT ——词变更同事务对 scene 级版本 +1 并广播`review:wordlib:change` ，这是全项目词库热更的唯一触发源
> - 其余类：自己订阅热更（review 也用 DFA）、启动种子装载、装配配置、埋点
>
> 1. **litisle-content\dfa —— 宿主接入方（4 个类）**
>
> - content 不拥有词库数据（读侧） ，包里全是「把 api 合同接到本服务运行时」的胶水
> - ContentRedisWordLibraryLoader.java：SPI 的 content 实现—— 从 Redis 读 review 广播出来的快照
> - WordlibChangeSubscriber.java：订阅热更消息 → 版本乐观比对（防乱序/重复）→ rebuild 本地 DFA 快照
> - ContentDfaConfig.java：本侧装配配置
> - ContentLastHashRedisAdapter.java：附带组件——契约层 last-hash（免审键）的 Redis 适配，因发布链路 DFA+免审一起用才放进这个包， 不属于 DFA 判定本体
>
> **三者协作（一次词库变更）**
>
> ```
> ① 运营在 review 改词
> ② review\dfa：查表取新词 → 版本+1 → 广播
> ③ content\dfa：收到广播 → 比对版本 → rebuild 本地快照
> ④ 之后 content 调 api\dfa\DfaFacade.check → 用的是新快照
> ```
>
> **一句话总结**
>
> 三个 dfa 包是**同一个 DFA 引擎的三个角色分工**：\
> litisle-api 定「引擎怎么判」（共用合同）→ litisle-review 管「词从哪来、版本怎么推进」（数据源头）→ litisle-content 管「引擎在本服务怎么装配」（宿主接入方）
>
> #### 各包职责
>
> ##### 1. litisle-api\dfa —— 引擎与契约（7 个类）
>
> 引擎本体 + 全项目共用的合同，不依赖任何业务模块：
>
> | 类                          | 职责                                                |
> | -------------------------- | ------------------------------------------------- |
> | DfaFacade.java             | 判定入口门面：`check(scene, text)` → 返回命中结果              |
> | DfaScenes.java             | 场景归一：去 `_RE_REVIEW` 后缀、FEATURED/COMMUNITY→ARTICLE |
> | DfaHitResult.java          | 判定结果结构（hit/maxLevel/hitWords/wordlibVer）          |
> | WordlibSnapshot.java       | 词库快照结构（不可变，rebuild 时整体替换）                         |
> | WordEntry.java             | 词条结构（词 + 级别 HIGH/MID）                             |
> | WordLibraryLoader.java     | 装载接口（SPI）：只定义「词从哪来」，不定义「从哪查」                      |
> | WordlibChangedMessage.java | 热更广播消息体                                           |
>
> 关键点：判定逻辑下沉在这里，content/auth/social/review 全部调同一个 `DfaFacade`——保证「HIGH 拦截、MID 标记」的口径全站唯一。
>
> ##### 2. litisle-review\dfa —— 数据源头与版本权威（6 个类）
>
> 词库数据在 review 服务的 `sensitive_word_library` 表里，review 是数据的拥有者：
>
> | 类                            | 职责                                              |
> | ---------------------------- | ----------------------------------------------- |
> | TableWordLibraryLoader.java  | api 层 `WordLibraryLoader` SPI 的 review 实现：查表供词  |
> | WordlibVersionService.java   | 版本 SSOT：词变更同事务对 `sensitive_word_version` +1 并广播 |
> | WordlibChangeSubscriber.java | review 自己也订阅热更消息 rebuild 本地快照（review 自己也要用 DFA） |
> | WordlibSnapshotSeeder.java   | 启动时种子装载（Redis 优先 + 持久化兑底）                       |
> | DfaConfig.java               | review 侧装配配置                                    |
> | WordlibMetrics.java          | 词库相关埋点                                          |
>
> ##### 3. litisle-content\dfa —— 宿主接入方——即其他模块（4 个类）
>
> content 不拥有词库数据，它的 dfa 包全是「把 api 层的合同接到本服务运行时」的胶水：
>
> | 类                                  | 职责                                                                                 |
> | ---------------------------------- | ---------------------------------------------------------------------------------- |
> | ContentRedisWordLibraryLoader.java | 同一个 SPI 的 content 实现：从 Redis 快照读词（读的是 review 广播出去的数据）                              |
> | WordlibChangeSubscriber.java       | 订阅 `review:wordlib:change` → 乐观比对版本号 → rebuild 本地 DFA 快照                           |
> | ContentDfaConfig.java              | content 侧装配配置                                                                      |
> | ContentLastHashRedisAdapter.java   | 不直接属于 DFA 判定——它是契约层 last-hash（免审键）的 Redis 适配实现，因板块一发布链路要用 DFA + 免审判定，就放在同一个 dfa 包里 |
>
> #### 三者如何协作（一次词库变更的旅程）
>
> ```
> ① 运营在 review 改词
> ② review\TableWordLibraryLoader 提供新词 → WordlibVersionService 版本+1 并广播
> ③ content\WordlibChangeSubscriber 收到 → 版本比对 → rebuild 本地快照（content\RedisWordLibraryLoader 提供装载通道）
> ④ 此后 content 发布文章调 api\DfaFacade.check → 用的是刚 rebuild 的新快照
> ```
>
> #### 区别总结表
>
> | 维度                   | api\dfa             | review\dfa                          | content\dfa                    |
> | -------------------- | ------------------- | ----------------------------------- | ------------------------------ |
> | 性质                   | 引擎本体 + SPI 合同       | 数据拥有方（写侧）                           | 数据消费方（读侧）                      |
> | 词库数据                 | 不管                  | 拥有 sensitive\_word\_library 表、推进版本号 | 从 Redis 读 review 广播的快照         |
> | WordLibraryLoader 实现 | 只是接口                | TableWordLibraryLoader（查库）          | RedisWordLibraryLoader（读缓存快照）  |
> | 谁调 DfaFacade 判定      | 定义它                 | review 自己也用                         | 发布/预检/标签新建时用                   |
> | 有无 LastHash 适配       | 定义 LastHashStore 接口 | 无                                   | 有（ContentLastHashRedisAdapter） |
>
> #### 概括版本的区别
>
> 一句话：**api\dfa 定判定规则，review\dfa 管词库数据，content\dfa 接入消费**。\
> api\dfa 是所有模块共用的引擎与接口定义（判定口径全站唯一，不含词库数据）；\
> review\dfa 拥有词库表并推进版本号，是词库变更的唯一写入方与广播方；\
> content\dfa 不持有词库，只负责订阅广播重建本地快照（读侧），并把契约层接口接到本服务的 Redis。\
> 三者分工即：接口定义（api）→ 数据源头（review）→ 宿主消费（content）。

***

### 不同模块的 moderation 包

> **api\moderation 管发布前决策，review\moderation 管送审后机审。**
>
> 一句话：**api\moderation 是守门员，review\moderation 是审判庭**。\
> 前者在宿主进程内同步跑——发布/注册/私信请求进来当场裁决（拦截/免审/标记/收口），不依赖 review 在不在线；\
> 后者在 review 进程内异步跑——消费送审事件、调模型、出三分态终局结论。\
> 两侧靠事件体衔接：api 产出 text/image.submit，review 产出 moderation.result / punish；共用的两个交点是 LastHashStore（免审键口径）与 MachineRejectPunishGate（处罚统一收口）。
>
> ```
> api的moderation作初步审核，
> 然后异步发给review的moderation进一步审核。
> 然后业务模块各自的 MQ 消费者直接监听 moderation.result，按 bizType（1-文章/2-评论…）和 scene 自筛后消费 ——content 拿到结论做回写（发布/驳回/下架）
> ```
>
> **审核完整链路（api\moderation ↔ review\moderation 视角）**
>
> ```
> ┌─────────────────────────────────────────────────────────────────┐
> │ 阶段一~二：api\moderation（宿主进程，同步，毫秒级）                   |
> │在「内容还没离开宿主服务」时，用最快的方式先做一轮筛选。                  |
> └─────────────────────────────────────────────────────────────────┘
>
> content 发布文章
> │
> ├─ ① SubmitTextAssembler.assemble(title, body)
> │       统一拼文本 + 算 content_hash（两侧共用，保证消费端能算出同一个 hash）
> │
> ├─ ② PublishTextPrecheck.check
> │       空内容 / 标题>100 → 直接拒绝，链路终止
> │
> ├─ ③ LastHashExemptChecker.decide
> │       content_hash 未变 + 上次 PASS + 非驳回态
> │       → 免审直落：CAS 0→2，直接发布，不发 MQ【链路在此分叉结束】
> │
> ├─ ④ DfaPublishGate.decide（调 api\dfa\DfaFacade）
> │       HIGH → 拦截 70001，链路终止【第二个分叉】
> │       MID  → 标记 layer1_hit=2 放行（后续机审二次判定）
> │       未命中 → 放行
> │
> └─ ⑤ 落库待审（status=1）→ afterCommit 发 text.submit 事件
>         事件体只含定位键（bizType/bizId/scene/contentHash），不含正文
>         ← 此时 api\moderation 的工作结束，发布接口即时返回「审核中」
>
> ┌─────────────────────────────────────────────────────────────────┐
> │ 阶段三：review\moderation（review 进程，异步消费，秒~分钟级）        │
> └─────────────────────────────────────────────────────────────────┘
>
> MQ moderationTextSubmitQueue
> │
> ├─ ⑥ ModerationTextConsumer 手动 ACK 接消息
> │       只做编排：traceId 恢复 + 委托 service
> │
> ├─ ⑦ service 层五步流水线（ModerationTextService）：
> │       a. 凭定位键回查原文（Feign 调 content，拿到标题+正文重新拼）
> │       b. ModerationResultCache 版本化查重（wordlibVer+fewShotVer+scene）
> │          命中终局结论 → 直接复用，不调模型
> │       c. dedup 短锁（contentHash 维度）——并发双投只放一个进模型
> │       d. ContentModerationService（spi）：防注入包装 + 超长文本 overlap 分片
> │       e. LlmTextModerationAdapter（adapter）真实判定
> │          解析失败 → 宁疑不放，出 SUSPICIOUS
> │
> └─ ⑧ 三分态落库 + 发布：
>         review_task 状态变更 + moderation_record 追加留痕（append-only）
>         → afterCommit 发 moderation.result 事件
>         ← review\moderation 的工作结束
>
> ┌─────────────────────────────────────────────────────────────────┐
> │ 阶段四~六：结果回流，两个包再次交接                                  │
> └─────────────────────────────────────────────────────────────────┘
>
> moderation.result
> │
> ├─ PASS     → content 回写 status=2 → 发 content.published
> │             → content 侧写 last-hash（经 LastHashStore 接口
> │               ← 这个接口定义在 api\moderation，实现是 content\dfa 适配器）
> │
> ├─ REJECT   → content 回写驳回/下架
> │             → api\moderation\MachineRejectPunishGate 统一收口处罚
> │             → review\moderation\RejectPunishAdapterHost 记账 punish_log
> │             → punish 事件 → auth/user 强制下线禁言
> │
> ├─ SUSPICIOUS → review 人审域接手（不在 moderation 包内）
> │               人审 REJECT 同样走 MachineRejectPunishGate 收口
> │
> └─ 申诉改判 → appeal.result 事件
>               → content 删 last-hash 键 + 恢复状态（4→2/3→2）
>               → ImageAppealCacheInvalidator 失效图片结论缓存           
> ```
>
> **SSOT 是什么**
> SSOT = Single Source of Truth，单一事实来源 （也译「唯一可信源」）。
> 含义： 某类数据或决策逻辑，全系统只允许存在一份权威定义，任何人要用都必须从这一处取 ，禁止在别处再抄一份。
>
> **各包作用**
>
> 1. **litisle-api\moderation —— 发布侧门面（12 个组件，宿主进程内同步执行）**
>
> - 发布/注册/私信三条链路 前置决策的 SSOT ，content/auth/social 直接调用同一份代码，防各宿主各自实现导致口径漂移
> - 发布链 ： SubmitTextAssembler.java （拼送审文本+算 content\_hash，发布/消费两侧共用）→ PublishTextPrecheck（空内容/标题≤100）→ LastHashExemptChecker.java （hash 未变+PASS+非驳回态→免审直落）→ DfaPublishGate.java （HIGH 拦 70001 / MID 标记放行）
> - 注册链 ：UsernameModerationGate / BioModerationGate → UsernameRejectGate（拒绝取证编排，取证失败仅留痕不影响拦截）
> - 私信链 ：StrangerQuotaGate（陌生人配额，Redis 主计数+DB 降级）→ MessageRecallGate（仅本人、仅正常态 CAS 撤回）
> - 两个收口 ：MachineRejectPunishGate（机审拒绝处罚统一发布口，禁双链路各自记账）→ ContentUnpublishedInvalidator（下架/删除/转私密批量失效审核缓存键）
> - 不依赖 review 可用性 ——全部本地执行，review 宕机不影响发布闸门
>
> 1. **litisle-review\moderation —— 异步机审执行域（6 子包 23 类，review 进程内消费驱动）**
>
> - MQ 消费驱动的机审主体，按职责分层：
>   - consumer ：三消费者（文本/图片/DLQ），只接消息+委托，手动 ACK
>   - service ：编排层（查重→dedup 抢锁→送审→落库→发事件）+ RejectPunishAdapterHost（处罚记账）+ ModerationMetrics（埋点）
>   - spi ：送审入口 ContentModerationService（防注入、超长文本 overlap 分片）
>   - adapter ：模型隔离层（LLM 文本 / ONNX 图片 nsfw-mobilenetv2）
>   - cache ：ModerationResultCache（版本化查重）+ ImageAppealCacheInvalidator（改判失效）
>   - config ：参数外置 / Rabbit 声明 / 适配器装配 / 启动检查（缺适配器拒绝启动）
> - 绝不默认放行 ：解析失败宁疑不放、消费耗尽绝不自动 ack 放行、无适配器拒绝启动
>
> ### 两者协作（一次文本送审）
>
> `① content 发布 → api\moderation 各闸门放行 → 发 text.submit（事件体仅定位键，不含正文）
> ② review\moderation：consumer 接消息 → service 编排 → cache 查重 → spi 分片 → adapter 判定
> ③ 三分态（PASS/REJECT/SUSPICIOUS）落库 → 发 moderation.result → content 消费回写
> ④ 若 REJECT → api\moderation\MachineRejectPunishGate 收口处罚 → punish 事件`
>
> litisle-api\moderation 定「发布前怎么决策」（同步闸门与收口，宿主进程内执行）
> → litisle-review\moderation 定「送审后怎么审」（异步机审执行域，消费驱动）
>
> #### 各包职责
>
> ##### 1. litisle-api\moderation —— 发布侧门面（12 个组件）
>
> 发布/注册/私信三链路前置决策的 SSOT，宿主服务本地调用、不依赖 review 可用性（板块七）：
>
> | 类                                                    | 职责                                                      |
> | ---------------------------------------------------- | ------------------------------------------------------- |
> | SubmitTextAssembler.java                             | 统一拼接送审文本（title+分隔符+body）并算 content\_hash，发布/消费两侧共用防口径漂移 |
> | PublishTextPrecheck.java                             | 发布前置校验：空内容（trim+去标签可见文本）/标题≤100，不发 MQ                   |
> | LastHashExemptChecker.java                           | 免审判定：hash 未变 + 上次 PASS + 非驳回态 → 免审直落                    |
> | LastHashStore.java                                   | last-hash（免审键）读写删门面，具体 Redis 适配由各宿主提供                   |
> | DfaPublishGate.java                                  | DFA 发布闸门：HIGH 拦 70001 / MID 只标记放行的 decide 收口            |
> | UsernameModerationGate.java / BioModerationGate.java | 注册链用户名/简介 DFA 闸门                                        |
> | UsernameRejectGate.java                              | 注册拒绝取证编排（取证失败仅留痕、不影响拦截结论）                               |
> | StrangerQuotaGate.java                               | 陌生人私信配额（Redis INCR 主计数，故障降级 DB 原子更新）                    |
> | MessageRecallGate.java                               | 私信撤回：仅本人、仅正常态 CAS 撤回                                    |
> | MachineRejectPunishGate.java                         | 机审拒绝处罚统一发布收口（禁机审/人审双链路各自记账）                             |
> | ContentUnpublishedInvalidator.java                   | 下架/删除/转私密时批量失效 text-result/img-result 与 last-hash 键     |
>
> ##### 2. litisle-review\moderation —— 异步机审执行域（6 子包，23 个类）
>
> MQ 消费驱动的机审主体（板块二/四/六），按职责分层：
>
> **根包**
>
> | 类                            | 职责                                   |
> | ---------------------------- | ------------------------------------ |
> | ModerationModelQpsGuard.java | 模型 QPS 防护：控制对 LLM/ONNX 的调用速率，防打爆模型服务 |
>
> **consumer（接消息，只编排不干活）**
>
> | 类                            | 职责                                              |
> | ---------------------------- | ----------------------------------------------- |
> | ModerationTextConsumer.java  | 手动 ACK 接 text.submit，委托 ModerationTextService   |
> | ModerationImageConsumer.java | 手动 ACK 接 image.submit，委托 ModerationImageService |
> | ModerationDlqConsumer.java   | 死信消费：重试耗尽后一律 ack，任务停留 status=0 交 sweeper        |
>
> **service（业务编排层，流程调度员）**
>
> | 类                            | 职责                                                  |
> | ---------------------------- | --------------------------------------------------- |
> | ModerationTextService.java   | 文本机审主编排：查重→dedup 抢锁→SPI 送审→三分态落库→发事件                |
> | ModerationImageService.java  | 图片机审编排：与文本同构，走 ONNX 适配器                             |
> | ModerationDlqService.java    | 死信兑底处置（转人审/留待重投），绝不默认放行                             |
> | RejectPunishAdapterHost.java | REJECT 处罚记账宿主适配：落 punish\_log + 发 punish 事件，双链路统一收口 |
> | ModerationMetrics.java       | 指标埋点：total/cost/结论分布，标签收敛防基数爆炸                      |
>
> **spi（送审入口与契约）**
>
> | 类                                                                | 职责                                                 |
> | ---------------------------------------------------------------- | -------------------------------------------------- |
> | ContentModerationService.java                                    | 机审送审入口：防注入三件套、超长文本 overlap 分片，任一片 REJECT 整文 REJECT |
> | ModerationResult.java                                            | 三分态结果结构（PASS/REJECT/SUSPICIOUS）                    |
> | dto\TextModerationRequest.java / dto\ImageModerationRequest.java | 文本/图片送审请求对象                                        |
>
> **adapter（模型适配器，隔离具体模型实现）**
>
> | 类                               | 职责                                                        |
> | ------------------------------- | --------------------------------------------------------- |
> | LlmTextModerationAdapter.java   | LLM 文本判定：防注入提示词、解析失败宁疑不放（→SUSPICIOUS）                     |
> | OnnxImageModerationAdapter.java | ONNX 图片判定（nsfw-mobilenetv2）：reject≥0.80 / suspicious≥0.60 |
>
> **cache（结论缓存）**
>
> | 类                                | 职责                                                                   |
> | -------------------------------- | -------------------------------------------------------------------- |
> | ModerationResultCache.java       | 版本化缓存查重（key 含 wordlibVer+fewShotVer+scene），未命中降级查 moderation\_record |
> | ImageAppealCacheInvalidator.java | 申诉改判后按留痕行重建键失效图片结论缓存                                                 |
>
> **config（装配与参数）**
>
> | 类                                                                        | 职责                                   |
> | ------------------------------------------------------------------------ | ------------------------------------ |
> | ModerationProperties.java                                                | 机审参数外置（dedup 锁时长/QPS 阈值/TTL 等外置 yml） |
> | ReviewRabbitConfig.java                                                  | 队列/交换机/死信绑定声明，路由键引用 api\constant     |
> | TextModerationAdapterProvider.java / ImageModerationAdapterProvider.java | 文本/图片适配器装配提供方                        |
> | ModerationProviderStartupChecker.java                                    | 启动检查：适配器缺失即拒绝启动，绝不 default-allow     |
> | AlertThresholdMetrics.java                                               | 告警阈值埋点（阈值外置 yml）                     |
>
> #### 三者如何协作（一次文本送审的旅程）
>
> ```
> ① content 发布 → api\DfaPublishGate 放行 → 发 text.submit（仅定位键）
> ② review\consumer\ModerationTextConsumer 接消息 → 委托 service\ModerationTextService
> ③ service：cache\ModerationResultCache 查重 → dedup 抢锁 → spi\ContentModerationService 分片
> → adapter\LlmTextModerationAdapter 判定
> ④ 三分态落库 → 发 moderation.result → content 回写
> ⑤ 若 REJECT → api\MachineRejectPunishGate 收口 → RejectPunishAdapterHost 记账 → punish 事件
> ```
>
> #### 区别总结表
>
> | 维度   | api\moderation                                            | review\moderation           |
> | ---- | --------------------------------------------------------- | --------------------------- |
> | 性质   | 发布侧决策门面（宿主调用）                                             | 机审执行域（消费驱动）                 |
> | 运行位置 | 宿主服务进程内同步执行                                               | review 服务进程内异步执行            |
> | 输入   | 发布/注册/私信请求原文                                              | MQ 送审事件（仅定位键，凭键回查原文）        |
> | 输出   | 拦截决定/免审结论/送审事件体                                           | PASS·REJECT·SUSPICIOUS 终局结论 |
> | 依赖   | dfa 门面 + 宿主 Redis 适配                                      | spi→adapter 模型、cache、MQ 发布  |
> | 交集   | 事件体契约 + MachineRejectPunishGate 处罚收口 + LastHashStore 两侧共用 | 同左                          |

***

### 审核时的2次缓存

> 
>
> ---
>
> **last-hash 存什么**
>
> key （4 段）：`review:last-hash:{wordlibVer}:{bizType}:{scene}:{bizId}`
> last-hash = 「这个业务对象（某篇文章）上一次审核通过的版本指纹 + 结论」 ，是发布侧免审的依据。
>
> | **字段**    | **存什么**                                  | **为什么**                                                   |
> | :---------- | :------------------------------------------ | :----------------------------------------------------------- |
> | **hash**    | 判定当时送审文本的指纹（sha256(标题+正文)） | 防「换了标题或正文却还命中旧键」——发布时重新算 hash 与键里的比对，不一致就不免审 |
> | **verdict** | 上一次终局结论（1-PASS / 2-REJECT）         | 免审判定要求 verdict=PASS 且当前非驳回态才放行               |
>
> 指纹（contentHash）只是 text-result 键的第 4 段 ，键里同时锁定了「当时的词库版本 + few-shot 版本 + 场景」，任一版本变更即换 key，旧结论自动失效（防词库热更后旧 PASS 继续放行）。
> 
>
> 一句话： last-hash 存的是「这个业务对象上一个版本的终局结论」（机审或人审给的），「直接发布」是在内容未变 + 口径未变 + 状态正常三重前提下，复用这份已经生效过的通过结论。
>
> ---
>
>  **text-result 存什么**
>
> value ：机审终局结论（1-PASS / 2-REJECT；SUSPICIOUS 非终局禁写），TTL 72h±10% 抖动。
>
> key ：`review:text-result:{wordlibVer}:{fewShotVer}:{scene}:{contentHash}`
>
> 即： 「这段文字（按指纹定位）在当前词库/few-shot 版本下，最近一次机器审出来的结论」 。词库或 few-shot 一热更，版本段变了，旧 key 全部作废——旧结论不可能被新口径复用。
>
> > fewShotVer：FewShot 版本：基于少样本学习（Few-Shot Learning）策略实现的审核/判定版本
>
> 
>
> ---
>
>  **为什么「查两次」——因为它们根本不是同一次查询**
>
> 这是理解的关键： last-hash 和 text-result 不在同一步执行，甚至不在同一个进程里 ，是链路上前后两道独立的关卡：
>
> ````
> `content 发布请求（同步）
>    │
>    ├─ 【关卡1】content 查 last-hash        ← 只在发布请求内
>    │        命中(hash未变+PASS+非驳回态) → 直接发布，结束
>    │        未命中 ↓
>    └─ 发 text.submit，落库待审 ──────────── MQ 分界线 ────────────
> 
> review 消费（异步）
>    │
>    └─ 【关卡2】review 查 text-result       ← 只有内容真送审了才会执行
>             命中 → 复用结论，不调 LLM
>             未命中 → 真调模型花钱`
> ````
>
> 
>
> 关卡 1 未命中，关卡 2 才有机会执行；关卡 1 命中，关卡 2 根本不会发生。 所以不是「一次审核查两次」，而是「两层各查各的，前一层挡住了后一层就不用跑」。
> 
>
> **三个场景看两道关卡怎么分工**
>
> 场景 A：作者没改任何字，重新点发布
>
> `关卡1：hash 未变 + 上次 PASS → 命中 → 直接发布
> 关卡2：根本不会发生（没发 MQ）`
>
> 场景 B：文章之前被下架（last-hash 被删），作者重发
>
> `关卡1：last-hash 已被删 → 未命中 → 正常送审
> 关卡2：review 查 text-result——若 72h 内审过且版本没变 → 复用结论，省一次 LLM`
>
> 场景 C：全新内容
>
> `关卡1：没有 last-hash → 未命中 → 送审
> 关卡2：text-result 没有这个 hash → 未命中 → 真调 LLM`
> 
>
> **为什么不能合并成一个键**
>
> 1. 维度不同 ：last-hash 按 bizId 定位（「这篇文章」），text-result 按 contentHash 定位（「这段文字」）——同一段文字若被不同业务对象送审，last-hash 按对象各存一份帮不上忙，text-result 一查就中
> 2. 读写方不同 ：last-hash 是 content 发布时同步读（要求毫秒响应），text-result 是 review 消费时读（异步不怕排队）——合并就要跨服务读写同一个键，边界变浑
> 3. 语义不同 ：last-hash 的 value 带「指纹+结论」且判定要配合「当前非驳回态」等业务状态；text-result 是纯「这段文本审过没」的事实记录
>
> 一句话 text-result 存的是「某指纹的机审终局结论」；查两次是因为 链路上有两道不同位置的关卡 ——last-hash 在发布口拦截「没改别再审」（体验），text-result 在模型调用前拦截「审过别重复花钱」（成本）。前一关命中，后一关压根不会执行。
>
> ---
>
> **关键疑问：**
>
> - 语义不同 ：last-hash 的 value 带「指纹+结论」且判定要配合「当前非驳回态」等业务状态；text-result 是纯「这段文本审过没」的事实记录 
>    什么意思？没改过不用审核了，不就是已经审核，指纹一直无需再审的意思吗？
>
> 你说的对了一半—— 指纹没变确实无需重审，但前提是「旧结论现在仍然作数」 。「语义不同」说的正是这个前提：text-result 只知道「审过」，不知道「现在还算不算数」。
>
>  关键：结论会被「事后推翻」，而 text-result 对此一无所知
>
> 用一条时间线看最清楚：
>
> ```
> `T1  作者发布文章A → 机审 PASS
>       ├─ text-result[hash1] = PASS          ← 机审事实，写入了
>       └─ last-hash[文章A]  = {hash1, PASS}  ← 免审依据，写入了
> 
> T2  人工复审发现机审漏判 → REJECT 下架（status 2→4）
>       ├─ last-hash[文章A] 被删除            ← 免审依据同步撤销
>       └─ text-result[hash1] 仍是 PASS       ← 没人删它！
> 
> T3  此时如果发布侧只查「这段文字审过没」：
>       text-result 命中 PASS → 直接放行
>       → 已经被人工定罪的违规文章复活了 ❌`
> ```
>
> 
>
> text-result 键里根本没有 bizId ——它不知道这篇文章存在，更不知道它已被下架。它忠实地记录着一个历史事实：「机审在 T1 时刻对这个指纹判了 PASS」。人工后来推翻，不改变这个历史事实，所以缓存里也不会变。
>
> 语义不同」的全部含义：
>
> - text-result = 事实记录 ：「机审对这个指纹的当时结论」——不管后来发生了什么，它只负责「审过就别重复花钱调模型」
> - last-hash = 放行凭证 ：「这个对象当前允许免审」——它和业务对象的状态绑定，对象一被下架/驳回，凭证立即作废（删键），重发时就必须重新走审核
>
>  回到你的原话逐句校对 「没改过不用审核了」——对，但完整条件是「没改过 且结论没被推翻 且对象不在驳回态」 「已经审核」——text-result 也知道这个 「指纹一直无需再审」——不完全对：指纹没变 ≠ 无需再审。 词库热更了要重审（版本段换 key）、人工复审推翻了要重审（驳回态拦截）、下架后重发要重审（last-hash 已删） 。「指纹没变」只是免审的必要条件之一，不是充分条件。
>
> **一句话 :**
>
> 「审过」和「现在还能免审」是两件事。text-result 只回答前者（事实记录），last-hash 回答后者（放行凭证）——所以 last-hash 的 value 才要带上指纹和结论，判定才要配合「当前非驳回态」，下架申诉才要 主动删键 。少了这层「与业务状态联动」，仅凭指纹缓存放行，T2 那种被人工推翻的内容就会借旧 PASS 复活。
> 
>
> ---



















## 完整链路走读（按时间序，先看这里再看板块）

> 两个 moderation 包是**同一条审核链路的上下游两段**：\
> litisle-api\moderation 定「发布前怎么决策」（同步闸门与收口，宿主进程内执行）→ litisle-review\moderation 定「送审后怎么审」（异步机审执行域，消费驱动）
>
> #### 各包职责
>
> ##### 1. litisle-api\moderation —— 发布侧门面（12 个组件）
>
> 发布/注册/私信三链路前置决策的 SSOT，宿主服务本地调用、不依赖 review 可用性（板块七）：
>
> | 类                                                    | 职责                                                      |
> | ---------------------------------------------------- | ------------------------------------------------------- |
> | SubmitTextAssembler.java                             | 统一拼接送审文本（title+分隔符+body）并算 content\_hash，发布/消费两侧共用防口径漂移 |
> | PublishTextPrecheck.java                             | 发布前置校验：空内容（trim+去标签可见文本）/标题≤100，不发 MQ                   |
> | LastHashExemptChecker.java                           | 免审判定：hash 未变 + 上次 PASS + 非驳回态 → 免审直落                    |
> | LastHashStore.java                                   | last-hash（免审键）读写删门面，具体 Redis 适配由各宿主提供                   |
> | DfaPublishGate.java                                  | DFA 发布闸门：HIGH 拦 70001 / MID 只标记放行的 decide 收口            |
> | UsernameModerationGate.java / BioModerationGate.java | 注册链用户名/简介 DFA 闸门                                        |
> | UsernameRejectGate.java                              | 注册拒绝取证编排（取证失败仅留痕、不影响拦截结论）                               |
> | StrangerQuotaGate.java                               | 陌生人私信配额（Redis INCR 主计数，故障降级 DB 原子更新）                    |
> | MessageRecallGate.java                               | 私信撤回：仅本人、仅正常态 CAS 撤回                                    |
> | MachineRejectPunishGate.java                         | 机审拒绝处罚统一发布收口（禁机审/人审双链路各自记账）                             |
> | ContentUnpublishedInvalidator.java                   | 下架/删除/转私密时批量失效 text-result/img-result 与 last-hash 键     |
>
> ##### 2. litisle-review\moderation —— 异步机审执行域（6 子包，23 个类）
>
> MQ 消费驱动的机审主体（板块二/四/六），按职责分层：
>
> **根包**
>
> | 类                            | 职责                                   |
> | ---------------------------- | ------------------------------------ |
> | ModerationModelQpsGuard.java | 模型 QPS 防护：控制对 LLM/ONNX 的调用速率，防打爆模型服务 |
>
> **consumer（接消息，只编排不干活）**
>
> | 类                            | 职责                                              |
> | ---------------------------- | ----------------------------------------------- |
> | ModerationTextConsumer.java  | 手动 ACK 接 text.submit，委托 ModerationTextService   |
> | ModerationImageConsumer.java | 手动 ACK 接 image.submit，委托 ModerationImageService |
> | ModerationDlqConsumer.java   | 死信消费：重试耗尽后一律 ack，任务停留 status=0 交 sweeper        |
>
> **service（业务编排层，流程调度员）**
>
> | <br /> | <br /> |
> | ------ | ------ |
> | <br /> | <br /> |
> | <br /> | <br /> |
> | <br /> | <br /> |
> | <br /> | <br /> |
> | <br /> | <br /> |

**阶段一：发布侧同步闸门（api\moderation，宿主进程内）**

**做什么**：在「内容还没离开宿主服务」时，用最快的方式先做一轮筛选。

这一段解决的核心问题是：**不能所有内容都无脑送机器审**——那样 LLM 调用成本爆炸，发布响应也被拖慢。所以先用四道「便宜又快」的检查把明显不用审、明显不能发的分流掉。

```
content 发布文章
   │
   ├─ ① SubmitTextAssembler.assemble(title, body)
   │       统一拼文本 + 算 content_hash（两侧共用，保证消费端能算出同一个 hash）
   │
   ├─ ② PublishTextPrecheck.check
   │       空内容 / 标题>100 → 直接拒绝，链路终止
   │
   ├─ ③ LastHashExemptChecker.decide
   │       content_hash 未变 + 上次 PASS + 非驳回态
   │       → 免审直落：CAS 0→2，直接发布，不发 MQ【链路在此分叉结束】
   │
   ├─ ④ DfaPublishGate.decide（调 api\dfa\DfaFacade）
   │       HIGH → 拦截 70001，链路终止【第二个分叉】
   │       MID  → 标记 layer1_hit=2 放行（后续机审二次判定）
   │       未命中 → 放行
   │
   └─ ⑤ 落库待审（status=1）→ afterCommit 发 text.submit 事件
           事件体只含定位键（bizType/bizId/scene/contentHash），不含正文
           ← 此时 api\moderation 的工作结束，发布接口即时返回「审核中」
```

**阶段二：异步送审（MQ 交接）**

**做什么**：把「该审的任务」投递出去，完成同步段与异步段的解耦。

这一段解决的核心问题是：**发布接口不能原地等审核结果**——LLM 判定可能几秒到几十秒，发布响应会被拖垮；把「快但便宜」与「慢但聪明」的检查切开，各自独立伸缩。

```
闸门放行 → 发 TextSubmitEvent
   │
   ├─ litisle.moderation.exchange（Topic，routing key text.submit）→ moderationTextSubmitQueue
   │
   └─ 事件体只含 eventId/bizType/bizId/scene/contentHash/retryCount，不含正文
           消费端凭定位键回查复算，前提是发布/消费两侧共用同一个 SubmitTextAssembler
           ← 发布接口即时返回「审核中」，api\moderation 的工作到此结束
```

**阶段三：review 机审消费（review\moderation）**

**做什么**：真正调用 AI 模型给内容下结论，并把结论可靠地存下来、通知出去。

这一段解决的核心问题是：**模型调用是全链路唯一「花钱」的环节**——必须层层节流（查重省调用、抢锁防双投、分片控上限），且失败时宁疑不放。

```
MQ moderationTextSubmitQueue
   │
   ├─ ⑥ ModerationTextConsumer 手动 ACK 接消息
   │       只做编排：traceId 恢复 + 委托 service
   │
   ├─ ⑦ service 层五步流水线（ModerationTextService）：
   │       a. 凭定位键回查原文（Feign 调 content，重新拼文本算出同一 hash）
   │       b. ModerationResultCache 版本化查重（wordlibVer+fewShotVer+scene）
   │          命中终局结论 → 直接复用，不调模型；未命中降级查 moderation_record
   │       c. dedup 短锁（contentHash 维度）——并发双投只放一个进模型
   │       d. ContentModerationService（spi）：防注入三件套 + 超长文本 overlap 分片
   │       e. LlmTextModerationAdapter（adapter）真实判定
   │          解析失败 → 宁疑不放，出 SUSPICIOUS；任一分片 REJECT 整文 REJECT
   │
   └─ ⑧ 三分态落库 + 发布：
           review_task 状态变更 + moderation_record 追加留痕（append-only）
           → afterCommit 发 moderation.result，统一路由，下游按 bizType 自筛
```

**阶段四：结果分流（事件驱动，两包再次交接）**

**做什么**：按三种结论各走各的后续，把「结论」变成「实际效果」。

这一段解决的核心问题是：**结论的产生可以多样（机器/人工），但处罚的执行必须只有一条路**——防止机审链、人审链各自记账导致重复扣分。

```
moderation.result
   │
   ├─ PASS       → content 回写 status=2 → 发 content.published
   │               → 写 last-hash（经 api 定义 LastHashStore 接口，供下次免审）
   │
   ├─ REJECT     → MachineRejectPunishGate 统一处罚收口（api 定义口径）
   │               → RejectPunishAdapterHost 记账 punish_log → punish 事件
   │               → auth/user 按 userId 串行消费并拉权威态（禁机审/强制下线）
   │
   └─ SUSPICIOUS → status=2 进人审队列（review 人审域接手）
                   人审 REJECT 同样走 MachineRejectPunishGate 收口
```

**阶段五：人审与申诉（review 人审域）**

**做什么**：处理机器拿不准的「灰色地带」，以及给作者提供不服气的救济渠道。

这一段解决的核心问题是：**人也是并发风险点**——两个审核员抢同一单、重复裁决，全靠数据库 CAS 条件更新保证安全。

```
SUSPICIOUS 任务（status=2）
   │
   ├─ 认领 CAS：where id=? and status=2 and assignee is null
   │       affected=0 → 70004（他人已抢）；在办配额 max-active-per-reviewer=20
   │
   ├─ 裁决终态 CAS（同事务写 decided_by/opinion/decide_time）
   │       终态后 CAS 不再命中，天然防重复裁决；REJECT 走阶段四同一条处罚链路
   │
   └─ 作者申诉 → AppealServiceImpl 七步编排：
           入参闸门 → ADMIN+原裁决人回避 → 高风险 confirm 双签（mod_type=6）
           → 改判 CAS → 同事务联动（处罚撤销 CAS 1→2 + violation_score 仅重放 action∈{1,2}）
           → 缓存失效 → 举报批量回写 → afterCommit 发 appeal.result
           改判 PASS 不重新机审（人工裁决即终态）
```

**阶段六：兑底与观测（sweeper + 埋点）**

**做什么**：处理前面所有环节的「意外」，保证没有任何任务被静默丢掉。

这一段解决的核心问题是：**前面五个阶段假设「一切顺利」，阶段六假设「一切都会出错」**——MQ 会丢、消费会崩、审核员会忘记，但任务绝不能凭空消失，更绝不能因此默认放行。

```
定时清扫（四大 sweeper）
   │
   ├─ ReviewTaskSweeper：认领超时回池；stuck 重投（dedup 短锁防双投，与 DLQ 互斥）
   ├─ QuarantineSweeper：隔离图按保留期清理（申诉中豁免，限速删除）
   ├─ ModerationRecordArchiveSweeper：04:00 错峰归档（archive-enabled 默认关）
   ├─ PunishReconcileSweeper：处罚双向对账（正向补踢 forceLogout / 反向回查权威态）
   └─ ModerationMetrics + AlertThresholdMetrics：埋点告警，误报反哺板块五词库转正
```

> 全程两条硬约束：① moderation\_record append-only，任何改判只增不改；② result / appeal.result / punish 三类事件驱动下游，服务间不直调写库。

<br />

<br />

## 一、同步 DFA 闸门（dfa 包 + litisle\_api 契约）

**功能描述：**

> - 敏感词全量快照装载与多场景归一，供 DFA 引擎毫秒级同步判定
> - 词库版本热更：变更推进版本号并广播，各宿主仅在版本更新时重建本地引擎

**功能描述——详细版：**

> **快照装载**
>
> - `TableWordLibraryLoader`（SPI 实现）查 sensitive\_word\_library 取全量词条供数据；快照装载 Redis 优先 + 本地持久化兑底\
>   空词库拒绝构建（绝不 default-allow）
>
> **版本热更**
>
> - `WordlibVersionService` 变更同事务对 scene 级版本 +1 再广播 review:wordlib:change\
>   `WordlibChangeSubscriber` 订阅后 version 乐观比对（仅接受更新版本，防乱序/重复消费）→ rebuild DFA 快照

**链路位置**：完整链路阶段一步骤 1-3（数据侧/版本推进/宿主热更）+ 步骤 4 请求判定

> 1. 数据侧：运营在板块五改词库 → `TableWordLibraryLoader`（SPI）查 sensitive\_word\_library 取全量词条
> 2. 版本推进：`WordlibVersionService` 变更同事务对 sensitive\_word\_version（scene 级）+1 → 广播 review:wordlib:change
> 3. 宿主热更：各宿主 `WordlibChangeSubscriber` 收到消息 → version 乐观比对（仅接受更新版本）→ rebuild DFA 快照
> 4. 请求判定：宿主调 `DfaFacade.check(scene, text)` → `DfaScenes` 场景归一\
>    去 \_RE\_REVIEW 后缀、FEATURED/COMMUNITY→ARTICLE、图片场景→null 不走词库\
>    HIGH→拦截 70001；MID→layer1\_hit=2 标记放行；未命中→放行

 

**详细版**：

- 引擎下沉 litisle\_api：\
  `DfaFacade.check(scene, text)` 返回 `DfaHitResult`（hit/maxLevel/hitWords/wordlibVer）；HIGH 拦 70001，MID 只标记（layer1\_hit=2）
- `DfaScenes` 场景归一：\
  去 \_RE\_REVIEW 后缀、FEATURED/COMMUNITY→ARTICLE；图片场景→null 不走词库
- review 侧 `TableWordLibraryLoader`（SPI 实现）：\
  查 sensitive\_word\_library 表供数据；快照装载 Redis 优先 + 本地持久化兑底，空词库拒绝构建（绝不 default-allow）
- `WordlibVersionService`：\
  变更同事务 +1 版本再广播；`WordlibChangeSubscriber` 订阅 review:wordlib:change，各宿主 rebuild（version 乐观比对防乱序）

**通俗版**：\
各服务在内容入库前本地执行关键词同步检查，不依赖审核服务是否在线。\
词库按用途分五套（文章/评论/用户名/私信/简介）；同类内容的首审与复审、不同栏目共用同一套词库，不重复建。\
词库任何变更都会让该场景版本号加一并广播，各服务仅在广播版本更新时才重建本地引擎（防乱序或重复广播导致旧覆盖新）。\
命中严重级直接拒绝发布；命中轻微级仅标记放行，后续仍进异步机审二次判定；未命中正常放行。\
任何场景词库为空或装载失败时拒绝启动，不允许无词库放行。

代码部分:

> - TableWordLibraryLoader.java：\
>   litisle\_review\dfa\TableWordLibraryLoader.java：\
>   作用：\
>   词库装载 SPI 实现，查 sensitive\_word\_library 全量词条供 DFA 快照构建
> - WordlibVersionService.java：\
>   litisle\_review\dfa\WordlibVersionService.java：\
>   作用：\
>   词库版本 SSOT，变更同事务对 scene 级版本 +1 再广播热更消息
> - WordlibChangeSubscriber.java：\
>   litisle\_review\dfa\WordlibChangeSubscriber.java：\
>   作用：\
>   订阅 review:wordlib:change，version 乐观比对后 rebuild 本地快照
> - WordlibSnapshotSeeder.java：\
>   litisle\_review\dfa\WordlibSnapshotSeeder.java：\
>   作用：\
>   启动时快照种子装载，Redis 优先 + 本地持久化兑底
> - WordlibMetrics.java：\
>   litisle\_review\dfa\WordlibMetrics.java：\
>   作用：\
>   词库装载/热更指标埋点
> - WordSceneEnum.java：\
>   litisle\_review\enums\WordSceneEnum.java：\
>   作用：\
>   词库场景枚举（文章/评论/用户名/私信/简介五套）
> - DfaFacade.java：\
>   litisle\_api\dfa\DfaFacade.java：\
>   作用：\
>   DFA 判定门面，返回 DfaHitResult（hit/maxLevel/hitWords/wordlibVer）
> - DfaScenes.java：\
>   litisle\_api\dfa\DfaScenes.java：\
>   作用：\
>   场景归一（首审/复审/栏目共用词库，图片场景不走词库）
> - WordlibSnapshot.java / WordLibraryLoader.java：\
>   litisle\_api\dfa\WordlibSnapshot.java、WordLibraryLoader.java：\
>   作用：\
>   快照结构与装载 SPI 契约，宿主侧注入实现供数据

<br />

<br />

## 二、异步机审（moderation 包）

**功能描述：**

> - MQ 消费驱动的文本/图片异步审核，输出 PASS/REJECT/SUSPICIOUS 三分态
> - 幂等建任务、版本化缓存查重、模型送审与结果发布全链路收口

**功能描述——详细版：**

> **消费与幂等**
>
> - `ModerationTextConsumer`/`ModerationImageConsumer` 手动 ACK 接 text.submit / image.submit\
>   `ReviewTaskUpsertService` 按定位键 bizType+bizId+scene（uk\_biz\_scene）幂等 upsert\
>   hash 变化/已终态 → 重置（清 assignee、retry 归零）；在审 → 只回填不打断已认领任务
>
> **缓存查重与送审**
>
> - `ModerationResultCache` 版本化 key（文本含 wordlibVer+fewShotVer+scene；图片含 provider+modelVersion）\
>   仅复用终局结论；未命中降级查 moderation\_record（双层兑底）\
>   文本送 LLM（防注入三件套、解析失败宁疑不放；超长 overlap 分片，任一片 REJECT 整文 REJECT）\
>   图片送 ONNX（nsfw-mobilenetv2，reject≥0.80/suspicious≥0.60）
>
> **落库发布**
>
> - 三分态写 review\_task + moderation\_record（append-only）\
>   `ModerationResultPublisher` afterCommit 发 moderation.result（统一路由，消费端按 bizType 自筛）

**链路位置**：完整链路阶段二（接收 text.submit/image.submit）+ 阶段三（机审消费全流程）+ 阶段四（三分态分流入口）

> 1. 消费入口：`ModerationTextConsumer` / `ModerationImageConsumer` 手动 ACK 接 text.submit / image.submit
> 2. 幂等 upsert：`ReviewTaskUpsertService` 按定位键 uk\_biz\_scene——hash 变化或已终态→重置；在审→只回填不打断
> 3. 查重：`ModerationResultCache` 版本化 key 仅复用终局结论；未命中降级查 moderation\_record
> 4. SPI 送审：`ContentModerationService` → 文本 `LlmTextModerationAdapter` / 图片 `OnnxImageModerationAdapter`；解析失败宁疑不放→SUSPICIOUS
> 5. 落库发布：三分态写 review\_task + moderation\_record → `ModerationResultPublisher` afterCommit 发 moderation.result
> 6. 异常路径：消费异常→nack 进 DLQ → `ModerationDlqConsumer` 重试仍失败→一律 ack\
>    任务停留 status=0，交板块六 sweeper 重投（与 stuck 重投互斥）

<br />

**详细版**：

- 入口三消费者：\
  `ModerationTextConsumer` / `ModerationImageConsumer` / `ModerationDlqConsumer`（手动 ACK；DLQ 消费失败一律 ack，任务停留 status=0 交板块六兑底）
- 幂等建任务：\
  定位键 bizType+bizId+scene（uk\_biz\_scene），`ReviewTaskUpsertService` 统一 upsert——hash 变化/已终态→重置（清 assignee、retry 归零）；在审→只回填不打断已认领任务
- 缓存查重：\
  `ModerationResultCache` 版本化 key，仅复用终局结论；未命中降级查 moderation\_record（双层兑底）
- SPI 送审：\
  `ContentModerationService` → `LlmTextModerationAdapter`（防注入三件套，解析失败宁疑不放）/ `OnnxImageModerationAdapter`（nsfw-mobilenetv2，reject≥0.80/suspicious≥0.60）；超长文本分片 overlap 硬切，任一片 REJECT 整文 REJECT
- 落库发布：\
  三分态写 review\_task + moderation\_record（append-only 留痕）→ `ModerationResultPublisher` afterCommit 发 moderation.result（统一路由，消费端按 bizType 自筛）

**通俗版**：\
同步闸门通过后，向消息队列发送只含定位信息的送审事件，不携带正文，消费端按定位键回查原文。\
按定位键建立或更新审核任务，同内容重复送审按规则合并或重置，不打断已认领任务。\
相同内容且词库/模型版本未变且已有终局结论的直接复用；未命中降级查历史留痕，仍没有才真正送审。\
文本送大语言模型、图片送识别模型；超长文本分段送审，任一段违规则整篇违规；模型解析失败时按可疑处理而非放行。\
结论写入任务表与留痕表（只增不改），提交后发事件供下游按业务类型自行订阅；反复失败的任务进死信处理，最终交定时任务兑底。

代码部分:

> - ModerationTextConsumer.java / ModerationImageConsumer.java：\
>   litisle\_review\moderation\consumer\ModerationTextConsumer.java、ModerationImageConsumer.java：\
>   作用：\
>   文本/图片送审事件消费者，手动 ACK，业务委托 ModerationTextService / ModerationImageService
> - ModerationDlqConsumer.java：\
>   litisle\_review\moderation\consumer\ModerationDlqConsumer.java：\
>   作用：\
>   死信消费者，重试仍失败一律 ack，任务停留 status=0 交 sweeper 兑底（不再死循环）
> - ReviewTaskUpsertService.java / ReviewTaskUpsertServiceImpl.java：\
>   litisle\_review\service\ReviewTaskUpsertService.java、service\impl\ReviewTaskUpsertServiceImpl.java：\
>   作用：\
>   幂等建任务/重置/回填收口，定位键 uk\_biz\_scene，hash 变化或终态重置、在审只回填
> - ModerationResultCache.java：\
>   litisle\_review\moderation\cache\ModerationResultCache.java：\
>   作用：\
>   版本化结论缓存，key 含 wordlibVer/fewShotVer/scene（图片含 provider/modelVersion），仅复用终局结论
> - ContentModerationService.java：\
>   litisle\_review\moderation\spi\ContentModerationService.java：\
>   作用：\
>   机审 SPI 入口，编排分片/防注入/适配器路由，返回 ModerationResult
> - LlmTextModerationAdapter.java：\
>   litisle\_review\moderation\adapter\LlmTextModerationAdapter.java：\
>   作用：\
>   文本 LLM 适配器，防注入三件套 + overlap 分片，解析失败宁疑不放
> - OnnxImageModerationAdapter.java：\
>   litisle\_review\moderation\adapter\OnnxImageModerationAdapter.java：\
>   作用：\
>   图片 ONNX 适配器（nsfw-mobilenetv2），reject≥0.80/suspicious≥0.60 阈值判定
> - ModerationTextService.java / ModerationImageService.java：\
>   litisle\_review\moderation\service\ModerationTextService.java、ModerationImageService.java：\
>   作用：\
>   文本/图片机审业务编排（查重→送审→落库→发布），消费者与 sweeper 重投共用
> - ModerationResultPublisher.java：\
>   litisle\_review\mq\publisher\ModerationResultPublisher.java：\
>   作用：\
>   审核结果事件统一发布收口，afterCommit 发 moderation.result
> - ModerationModelQpsGuard.java / ModerationGlobalQpsInterceptor.java：\
>   litisle\_review\moderation\ModerationModelQpsGuard.java、interceptor\ModerationGlobalQpsInterceptor.java：\
>   作用：\
>   模型 QPS 限流防护（送审侧守门 + Feign 提供方全局拦截），防 LLM 过载
> - ModerationProperties.java / ReviewRabbitConfig.java：\
>   litisle\_review\moderation\config\ModerationProperties.java、ReviewRabbitConfig.java：\
>   作用：\
>   机审参数外置（阈值/分片/重试）与 review 侧队列/交换机绑定声明（路由键引用 MqConstant）

<br />

<br />

## 三、人审工作流（service 六域）

**功能描述：**

> - SUSPICIOUS 任务的人审队列：认领、裁决、终态回写
> - 举报受理/复审/撤回与诬告反向治理

**功能描述——详细版：**

> **认领与裁决**
>
> - 认领：先查在办数（< max-active-per-reviewer=20）→ CAS `set assignee=? where id=? and status=2 and assignee is null`\
>   affected=0 → 70004（他人已抢）\
>   裁决：终态 CAS `where id=? and status=2`，同事务写 decided\_by/opinion/decide\_time；终态后 CAS 不再命中，天然防重复裁决
>
> **举报域**
>
> - `ReportService` 受理→复审（同内容多举报只回填不重置）→本人撤回（status<3→4）\
>   诬告 abuse\_flag 累计 3 次反向落警告+降信誉；机审留痕 handler 固定 0-系统、不判 abuse\_flag

**链路位置**：完整链路阶段三步骤 4（SUSPICIOUS 进人审池）+ 阶段五（认领→裁决→分流）

> 1. 进池：机审 SUSPICIOUS → review\_task status=2 进人审池
> 2. 认领：先查审核员在办数（<20）→ CAS `set assignee=? where id=? and status=2 and assignee is null`\
>    affected=0 → 70004（他人已抢）
> 3. 裁决：终态 CAS `where id=? and status=2`，同事务写 decided\_by/opinion/decide\_time；终态后 CAS 不再命中，天然防重复裁决
> 4. 分流：PASS→放行；REJECT→走板块四处罚链路

<br />

**详细版**：

- 认领 CAS：\
  `where id=? and status=2 and assignee is null`，affected=0 → 70004；在办配额 max-active-per-reviewer=20
- 裁决终态 CAS：\
  `where id=? and status=2`，同事务写 decided\_by/opinion/decide\_time，终态特性天然防重复裁决
- `ReportService`：\
  举报受理/复审（多举报同内容只回填不重置）/本人撤回（status<3→4）；诬告 abuse\_flag 累计 3 次反向落警告+降信誉
- 机审留痕 handler 固定 0-系统、不判 abuse\_flag

**通俗版**：\
机审拿不准的内容进入人工队列，审核员先到先得地认领（有在办数上限防一个人揽爆），只能对未决任务执行一次裁决，执行时同事务记录裁决人、意见和时间。\
终态后不可再改，作者不服走申诉流程。\
同一内容多次举报只补充信息不重置流程；举报人在处理结束前可撤回；恶意举报累计三次后举报人自身受警告并降信誉。\
机器自动留痕的记录处理人固定为系统，不参与恶意举报判定。

代码部分:

> - ReviewTaskService.java / ReviewTaskServiceImpl.java：\
>   litisle\_review\service\ReviewTaskService.java、service\impl\ReviewTaskServiceImpl.java：\
>   作用：\
>   人审任务域——队列查询/认领 CAS/裁决终态 CAS/申诉超时自动维持，在办配额校验
> - ReviewService.java / ReviewServiceImpl.java：\
>   litisle\_review\service\ReviewService.java、service\impl\ReviewServiceImpl.java：\
>   作用：\
>   人审业务编排，认领/裁决后触发结果发布与处罚链路分流
> - ReportService.java / ReportServiceImpl.java：\
>   litisle\_review\service\ReportService.java、service\impl\ReportServiceImpl.java：\
>   作用：\
>   举报域——受理/复审（多举报只回填不重置）/本人撤回/诬告 abuse\_flag 累计治理
> - ReviewTaskController.java / ReviewController.java / ReportController.java：\
>   litisle\_review\controller\ReviewTaskController.java、ReviewController.java、ReportController.java：\
>   作用：\
>   人审任务/裁决/举报 HTTP 入口，审核员操作端点
> - ReviewFeignProviderController.java：\
>   litisle\_review\controller\feign\ReviewFeignProviderController.java：\
>   作用：\
>   review 对外 Feign 提供方（处罚状态回查等给宿主服务的端点）
> - ReviewTask.java / Report.java / ReportEvidence.java：\
>   litisle\_review\domain\entity\ReviewTask.java、Report.java、ReportEvidence.java：\
>   作用：\
>   审核任务（status/assignee/decided\_by 终态字段）、举报单与举报取证三表实体

<br />

<br />

## 四、申诉与处罚联动

**功能描述：**

> - 作者申诉改判：普通/双签/回避/同事务联动与缓存失效
> - 机审拒绝自动处罚统一收口与跨服务同步

**功能描述——详细版：**

> **机审处罚链**
>
> - `MachineRejectPunishGate` 统一发布收口（禁机审/人审双链路各写，防重复扣分）\
>   `RejectPunishAdapterHost` 记账（参数化绑定，DuplicateKeyException 转累加）→ `PunishResultPublisher` sendCritical 发 punish\
>   auth/user 按 userId 串行消费并拉权威态（禁言/强制下线），不依赖到达顺序
>
> **申诉改判链（`AppealServiceImpl`** **七步）**
>
> - ①入参闸门 ②ADMIN+原裁决人回避 ③高风险 confirm 双签（mod\_type=6 留痕）④改判 CAS\
>   ⑤同事务联动：处罚撤销 CAS 1→2 + violation\_score 仅重放 action∈{1,2}\
>   ⑥`ImageAppealCacheInvalidator` 按留痕行重建键失效缓存 ⑦举报批量回写（biz→target 错位映射 5↔4、6↔5）→ afterCommit 发 appeal.result

**链路位置**：完整链路阶段四（REJECT 处罚链）+ 阶段五步骤 2（申诉改判）

> 1. 机审处罚链：机审 REJECT → `MachineRejectPunishGate`（统一发布收口）→ `RejectPunishAdapterHost` 记账 → `PunishResultPublisher` sendCritical 发 punish\
>    auth/user 按 userId 串行消费并拉权威态（不依赖到达顺序）→ 禁言/强制下线
> 2. 申诉改判链：见功能描述——详细版七步；改判 PASS 后缓存失效**不会主动触发机审**——平反内容由消费端按事件直接恢复状态，人工裁决即终态\
>    清缓存只是保证该内容日后若再次送审（如编辑后重新发布）时不被旧违规结论误拒
> 3. 兑底：`PunishReconcileSweeper` 正向补踢（复用 forceLogout）/ 反向回查权威态覆盖本地快照

<br />

**详细版**：

- 机审处罚链：\
  机审 REJECT → `MachineRejectPunishGate`（统一发布收口，禁机审/人审双链路各写）→ `RejectPunishAdapterHost` 记账（参数化绑定；DuplicateKeyException 转累加）→ `PunishResultPublisher` sendCritical 发 punish → auth/user 按 userId 串行消费并拉权威态
- 申诉改判链（`AppealServiceImpl` 七步）：\
  ①入参闸门 ②ADMIN+原裁决人回避 ③高风险 confirm 双签（mod\_type=6 留痕）④改判 CAS ⑤同事务联动：处罚撤销 CAS 1→2 + violation\_score 仅重放 action∈{1,2} ⑥`ImageAppealCacheInvalidator` 按留痕行重建键失效缓存 ⑦举报批量回写（biz→target 错位映射 5↔4、6↔5）→ afterCommit 发 appeal.result
- 兑底：\
  `PunishReconcileSweeper` 正向补踢（复用 forceLogout）/ 反向回查权威态覆盖本地快照

**通俗版**：\
机器判违规的内容由统一入口记账并发处罚事件（禁止机审/人审两条链路各自记账，防重复扣分）；\
消费端按用户维度串行处理并回查权威状态，保证禁言、强制下线等动作最终一致。\
作者对裁决不服可申诉。普通管理员可处理但原裁决人必须回避；重大改判需第二人确认才生效。\
改判通过后同一事务内撤销处罚（状态条件更新）并回退相应扣分（仅重放部分动作类型），随后清理相关缓存、批量更新关联举报状态，提交后广播改判结果。\
改判为通过的内容由下游按改判事件直接恢复状态，不重新机审（清缓存仅为日后再次送审时不被旧违规结论误拒）。\
另有定时对账任务双向核对处罚状态，修正两端不一致。

代码部分:

> - AppealService.java / AppealServiceImpl.java：\
>   litisle\_review\service\AppealService.java、service\impl\AppealServiceImpl.java：\
>   作用：\
>   申诉域——七步编排（闸门/回避/双签/改判 CAS/同事务联动/缓存失效/举报回写），afterCommit 发 appeal.result
> - AppealController.java：\
>   litisle\_review\controller\AppealController.java：\
>   作用：\
>   申诉提交与裁决 HTTP 入口
> - MachineRejectPunishGate.java：\
>   litisle\_api\moderation\MachineRejectPunishGate.java：\
>   作用：\
>   机审拒绝处罚统一发布收口，禁机审/人审双链路各写
> - RejectPunishAdapterHost.java：\
>   litisle\_review\moderation\service\RejectPunishAdapterHost.java：\
>   作用：\
>   处罚记账宿主适配（参数化绑定，DuplicateKeyException 转累加），复用宿主服务已有处罚能力
> - PunishResultPublisher.java / ModerationAppealResultPublisher.java：\
>   litisle\_review\mq\publisher\PunishResultPublisher.java、ModerationAppealResultPublisher.java：\
>   作用：\
>   punish 事件（sendCritical）与 appeal.result 事件发布收口
> - ImageAppealCacheInvalidator.java：\
>   litisle\_review\moderation\cache\ImageAppealCacheInvalidator.java：\
>   作用：\
>   图片申诉改判后按留痕行重建键失效审核缓存
> - PunishService.java / PunishServiceImpl.java：\
>   litisle\_review\service\PunishService.java、service\impl\PunishServiceImpl.java：\
>   作用：\
>   处罚域——处罚记录/撤销（CAS 1→2）/violation\_score 重放/处罚状态对外回查
> - PunishController.java：\
>   litisle\_review\controller\PunishController.java：\
>   作用：\
>   处罚记录查询/申诉相关 HTTP 入口
> - Appeal.java / PunishLog.java / ViolationScore.java：\
>   litisle\_review\domain\entity\Appeal.java、PunishLog.java、ViolationScore.java：\
>   作用：\
>   申诉单/处罚流水（append-only）/违规积分三表实体

<br />

<br />

## 五、词库运营（wordlib 域）

**功能描述：**

> - 敏感词 CRUD/CSV 导入导出/版本推进/误报上报
> - 版本推进是板块一 DFA 热更的唯一触发方

**功能描述——详细版：**

> **日常运营**
>
> - 敏感词 CRUD；出参 `SensitiveWordVO`、更新走 `WordUpdateDTO`（禁裸实体暴露内部字段）\
>   新增词默认为观察级（只记录不拦截），误报率达标后再人工升级为拦截级
>
> **导入导出**
>
> - 导入：CSV 解析（引号转义还原）；场景版本查询移出循环防 N+1；重复词跳过，有实际新增才推进版本并广播\
>   导出：=/+/-/@ 前缀加 ' 防公式注入
>
> **变更生效与误报闭环**
>
> - 增/删/改任一操作 → 同事务对 sensitive\_word\_version（scene 级）+1 → 广播 review:wordlib:change → 板块一各宿主 rebuild\
>   误报上报（仅黑名单词可报）→ 计入 wordlib\_misreport\_total{scene} → 作为灰度转正数据源反哺词库优化

**链路位置**：完整链路阶段一步骤 1 的上游数据源与热更触发方（板块一热更的唯一触发源）

> 1. 运营在 wordlib 域增/删/改词库
> 2. 同事务推进该场景版本号并广播 review:wordlib:change
> 3. 板块一各宿主 rebuild 快照（任何变更含删除都推进版本，这是热更的唯一触发源）

<br />

**详细版**：

- 版本 SSOT = sensitive\_word\_version（scene 级），变更同事务 +1 再广播（驱动板块一热更）
- 导入防 N+1（场景版本查询移出循环）、CSV 引号转义还原；导出防公式注入（=/+/-/@ 前置 '）
- 出参 `SensitiveWordVO`、更新 `WordUpdateDTO`（禁裸实体暴露内部字段）；删除后同事务 +1 并广播
- 误报上报：仅黑名单词可报；灰度转正数据源 = wordlib\_misreport\_total{scene}

**通俗版**：\
提供敏感词的增删改与批量导入导出。新增词默认为观察级（只记录不拦截），误报率达标后再人工升级为拦截级。\
导入逐词清洗校验（长度与字符限制、引号转义还原），重复词跳过；有实际新增才推进版本并广播，无新增不动版本。\
导出对特殊前缀字符加转义符，防止表格公式注入。\
任何变更（含删除）都同事务推进该场景版本号并广播，这是板块一热更的唯一触发源。\
误报上报仅针对拦截级词，统计数据作为观察词转正与词库优化的依据。

代码部分:

> - WordlibService.java / WordlibServiceImpl.java：\
>   litisle\_review\service\WordlibService.java、service\impl\WordlibServiceImpl.java：\
>   作用：\
>   词库域——敏感词 CRUD/导入导出/误报上报，变更同事务推进版本并广播
> - WordlibController.java：\
>   litisle\_review\controller\WordlibController.java：\
>   作用：\
>   词库运营 HTTP 入口（列表/增删改/导入导出/误报）
> - DefaultWordlibExporter.java：\
>   litisle\_review\DefaultWordlibExporter.java：\
>   作用：\
>   CSV 导出实现，公式注入防护（=/+/-/@ 前缀加 '）
> - SensitiveWordVO.java / WordUpdateDTO.java：\
>   litisle\_review\domain\vo\SensitiveWordVO.java、dto\WordUpdateDTO.java：\
>   作用：\
>   词库出参/更新入参对象，禁裸实体暴露内部字段
> - SensitiveWordLibrary.java / SensitiveWordVersion.java：\
>   litisle\_review\domain\entity\SensitiveWordLibrary.java、SensitiveWordVersion.java：\
>   作用：\
>   词条全量表（level/scene/status）与场景级版本表（SSOT）
> - SensitiveWordLibraryMapper.java / SensitiveWordVersionMapper.java：\
>   litisle\_review\mapper\SensitiveWordLibraryMapper.java、SensitiveWordVersionMapper.java：\
>   作用：\
>   词条与版本两表持久层 Mapper（Loader 查全量词条、版本推进原子 +1）

<br />

<br />

## 六、sweeper 兑底与观测

**功能描述：**

> - 四大定时清扫（回池/隔离清理/归档/处罚对账）
> - 指标埋点与阈值告警，「绝不默认放行」的最后一道网

**功能描述——详细版：**

> **任务兑底**
>
> - `ReviewTaskSweeper`：认领超时回池 / 申诉超时自动维持原裁决 / 三态 Gauge 上报\
>   每 30 轮分频执行 stuck 重投——扫 status=0 超时任务按场景分流重发，dedup 短锁防双投，retryCount 不归零，与 DLQ 消费互斥\
>       ReviewTaskSweeper 是一个 Spring 定时任务组件，每 60 秒执行一轮，负责：\
>        1.认领超时回池 \
>        2.申诉超时自动维持 \
>        3.审核积压监控指标刷新 \
>        4.待审任务超时补偿重发 \
>        5.它保证审核任务不会因为人工离线、消息丢失、申诉无人处理而永久卡死。\
>   \
>   `（dedup（deduplicate，去重）：短锁 = 一个带自动过期时间的 Redis 锁 ，用 SETNX + TTL  抢占，防止同一条内容被并发送审两次，导致 LLM 被重复调用）`\
>   ` (「双投」= 同一条内容被投递给消费者两次)  `\
>   \
>   DLQ 配合：`ModerationDlqConsumer` 消费失败一律 ack（不再死循环），任务停留 status=0 等 sweeper 重投
>
> **四大清扫与观测**
>
> - `QuarantineSweeper`：按保留期扫隔离图，申诉中豁免，限速删除防 DB&#x20;
>
>       QuarantineSweeper 是一个定时补偿任务，负责扫描“超期且未被申诉推翻”的违规隔离图片，限速、
>   安全地委派 file 服务物理删除，并通过 Redis 互斥锁和 Micrometer 指标保证多实例安全与可观测性。
> - `ModerationRecordArchiveSweeper`：04:00 错峰、id 区间推进、被引用行跳过（引用保护）；archive-enabled 默认
>
>       将超期（create\_time 早于 now-retention-months）的审核留痕行从热表迁移只读历史存储后删热表行，
>   防热表无限增长拖垮审核主链路查询，同时保住《网络安全法》≥6 月合规留存
> - `PunishReconcileSweeper`：正向补踢（复用 forceLogout）+ 反向回查权威态覆盖本地快
>
>       **定期把「审核处罚系统的权威记录」和「认证/会话系统的实时状态」做双向对账，发现不一致就自动补漏，确保用户不会被漏封、也不会被误伤。**
> - `ModerationMetrics`（total 仅落库入口计一次、cost 仅真实送审后记、标签仅 verdict·action·layer 防基数爆炸 —— 责把机审判定、SPI 耗时、缓存命中、处罚落库等关键行为，以 Prometheus 指标的形式安全、低侵入地暴露出来。）\\
> - `AlertThresholdMetrics`（阈值外置 yml）→ 异常率反哺板块五误报转正

**链路位置**：完整链路阶段六（兑底与观测）；板块二 DLQ 与卡死任务的兑底出口

> 1. 消费失败任务停留 status=0 → `ReviewTaskSweeper` 分频 stuck 重投（与 DLQ 互斥）
> 2. 认领超时回池 / 申诉超时自动维持 / 隔离图到期清理 / 历史记录归档 / 处罚双向对账
> 3. 指标观测：埋点统计审核总量、耗时与结论分布，标签维度收敛防指标基数爆炸，超过阈值告警；观测数据反哺词库误报修正

<br />

**详细版**：

- `ReviewTaskSweeper`：\
  认领超时回池 / 申诉超时自动维持原裁决 / 三态 Gauge 上报；每 30 轮分频执行 stuck 重投——扫 status=0 超时任务按场景分流重发，dedup 短锁防双投，retryCount 不归零，与 DLQ 消费互斥
- DLQ 配合：\
  `ModerationDlqConsumer` 消费失败一律 ack（不再死循环），任务停留 status=0 等 sweeper 重投
- `QuarantineSweeper`：\
  按保留期扫隔离图，申诉中豁免，限速删除防 DB 压力
- `ModerationRecordArchiveSweeper`：\
  04:00 错峰、id 区间推进、被引用行跳过（引用保护）；archive-enabled 默认关
- `PunishReconcileSweeper`：\
  正向补踢（复用 forceLogout）+ 反向回查权威态覆盖本地快照
- 观测：\
  `ModerationMetrics`（total 仅落库入口计一次、cost 仅真实送审后记、标签仅 verdict·action·layer 防基数爆炸）+ `AlertThresholdMetrics`（阈值外置 yml）→ 异常率反哺板块五误报转正

**通俗版**：\
审核任务超时未处理自动回池；长期停留待处理状态的任务按场景重新投递，短时锁防重复投递，重试次数持续累计，与死信重试互斥。\
隔离图片按保留期清理，申诉中的豁免，删除限速。\
历史审核记录定期归档，凌晨错峰、分区间推进、被引用的跳过，默认关闭。\
处罚数据与权威状态双向对账，正向补发强制下线，反向以权威态覆盖本地快照。\
埋点统计审核总量、耗时与结论分布，标签维度收敛防指标基数爆炸，超过阈值告警；观测数据反哺词库误报修正。

代码部分:

> - ReviewTaskSweeper.java：\
>   litisle\_review\sweeper\ReviewTaskSweeper.java：\
>   作用：\
>   任务兑底主 sweeper——认领超时回池/申诉超时维持/三态 Gauge/stuck 分频重投（dedup 短锁防双投）
> - QuarantineSweeper.java：\
>   litisle\_review\sweeper\QuarantineSweeper.java：\
>   作用：\
>   隔离图按保留期清理，申诉中豁免，限速删除
> - ModerationRecordArchiveSweeper.java：\
>   litisle\_review\sweeper\ModerationRecordArchiveSweeper.java：\
>   作用：\
>   moderation\_record 历史归档（04:00 错峰/id 区间推进/引用保护，默认关）
> - PunishReconcileSweeper.java：\
>   litisle\_review\sweeper\PunishReconcileSweeper.java：\
>   作用：\
>   处罚双向对账——正向补踢（复用 forceLogout）/反向回查权威态覆盖本地快照
> - ModerationDlqService.java：\
>   litisle\_review\moderation\service\ModerationDlqService.java：\
>   作用：\
>   DLQ 死信业务处理，重试后仍失败一律 ack 交 sweeper 兑底
> - ModerationMetrics.java / AlertThresholdMetrics.java：\
>   litisle\_review\moderation\service\ModerationMetrics.java、config\AlertThresholdMetrics.java：\
>   作用：\
>   机审指标埋点（total/cost/verdict·action·layer 标签收敛）与阈值告警（阈值外置 yml）
> - ModerationRecordArchive.java / ModerationRecordArchiveMapper.java：\
>   litisle\_review\domain\entity\ModerationRecordArchive.java、mapper\ModerationRecordArchiveMapper.java：\
>   作用：\
>   归档表实体与 Mapper，配合引用保护跳过被引用行

<br />

<br />

## 七、发布侧门面组件（litisle\_api · moderation 包，参考）

**功能描述：**

> - 发布/注册/私信三条链路共用审核前置逻辑，决策口径的 SSOT
> - 落在契约层供 content/auth/social 宿主接入，防止同一决策两侧各写漂移（本模块为主、本节仅列契约层类供参考）

**详细版**：

- 全景图阶段一/二（同步闸门、免审判定、事件组装）的真实载体；板块一 DFA 引擎的调用方；板块二缓存键的失效动作由 `ContentUnpublishedInvalidator` 承接（双向呼应：发布侧写免审键、下架侧删免审键）
- 三条宿主链路：\
  文章发布链 `SubmitTextAssembler` → `PublishTextPrecheck` → `LastHashExemptChecker` → `DfaPublishGate.decide` → 落库待审 + 发 TextSubmitEvent；\
  注册链 `UsernameModerationGate`/`BioModerationGate` → HIGH → `UsernameRejectGate` 取证（失败仅留痕不拦截结论）；\
  私信链 `StrangerQuotaGate`（Redis INCR 主计数，故障降级 DB，上限 3）→ `MessageRecallGate`（仅本人/仅正常态 CAS 撤回）

**通俗版**：\
将三条链路共用的审核前置逻辑统一下沉到公共契约层，各服务调用同一实现，避免决策口径漂移。\
两个收口动作：机器判违规的处罚统一从一处发出；内容下架/删除/转私密时批量清除审核结果缓存与免审标记，防止已删内容靠旧免审标记绕过审核。

代码部分:

> - DfaPublishGate.java：\
>   litisle\_api\moderation\DfaPublishGate.java：\
>   作用：\
>   发布闸门决策封装，HIGH 拦 70001/MID 只标记的 decide 结论，替代宿主手写 if 分支
> - UsernameModerationGate.java / BioModerationGate.java / UsernameRejectGate.java：\
>   litisle\_api\moderation\UsernameModerationGate.java、BioModerationGate.java、UsernameRejectGate.java：\
>   作用：\
>   注册链闸门（用户名/简介 DFA 判定）与注册拒绝取证编排（取证失败仅留痕不影响拦截结论）
> - LastHashStore.java / LastHashExemptChecker.java：\
>   litisle\_api\moderation\LastHashStore.java、LastHashExemptChecker.java：\
>   作用：\
>   免审键读写门面（value 为 JSON 含 hash+verdict，SUSPICIOUS 禁写）与三种免审判定
> - SubmitTextAssembler.java / PublishTextPrecheck.java：\
>   litisle\_api\moderation\SubmitTextAssembler.java、PublishTextPrecheck.java：\
>   作用：\
>   送审文本与 contentHash 统一组装（发布/消费两侧共用）、空内容与标题长度前置校验
> - StrangerQuotaGate.java / MessageRecallGate.java：\
>   litisle\_api\moderation\StrangerQuotaGate.java、MessageRecallGate.java：\
>   作用：\
>   陌生人消息每日配额（Redis 主计数降级 DB）与私信撤回（仅本人/仅正常态 CAS）
> - ContentUnpublishedInvalidator.java：\
>   litisle\_api\moderation\ContentUnpublishedInvalidator.java：\
>   作用：\
>   下架/删除/转私密时批量失效 text-result/img-result 与 last-hash 键（与发布侧写键双向呼应）
> - TextSubmitEvent.java / ImageSubmitEvent.java / ModerationResultEvent.java / ModerationAppealResultEvent.java / PunishResultEvent.java：\
>   litisle\_api\event\moderation\ 下同名五个事件体：\
>   作用：\
>   送审/结果/改判/处罚五类事件契约，事件体不含正文，路由键统一引用 MqConstant

> 本文档与 [Review-Business-Guide.md](Review-Business-Guide.md)（学习向三段式）互为补充：业务推演以架构文档为准，速查配套见 [review-index.md](../index/review-index.md) 与 [api-index.md](../index/api-index.md)。

