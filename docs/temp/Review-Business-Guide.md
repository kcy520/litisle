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
> - mq：事件发布收口，全部 afterCommit 发布，禁止各业务类直接调 RabbitTemplate
> - sweeper：补偿型定时任务，绝不默认放行的最后一道网
> - interceptor / config：横切关注点——Feign 提供方限流拦截、Web 与鉴权配置

**各包作用说明（litisle-api，契约层参考）：**

> - dfa：引擎与快照结构下沉于此，宿主与 review 共用同一判定口径（板块一）
> - moderation：发布/注册/私信三链路前置决策的 SSOT（板块七），事件体组装、免审判定、处罚与缓存失效收口都在这里
> - event.moderation / dto.moderation：跨服务事件体与 DTO，事件体只含定位键不含正文
> - feign：宿主调 review 的客户端（处罚状态回查、取证留痕等）
> - constant：路由键/缓存键唯一来源，禁各服务私自新建交换机或硬拼 key

<br />

<br />

## 完整链路走读（按时间序，先看这里再看板块）

**阶段一 · 发布侧同步闸门（宿主本地执行，载体=litisle\_api moderation 包，不依赖 review 可用性）**

> 1. 发布接口调 `SubmitTextAssembler.assemble(title, body)`：\
>    统一拼接送审文本（title+分隔符+body），算 content\_hash=sha256(送审文本)；标题随正文送审，防违规藏标题躲过模型
> 2. `PublishTextPrecheck` 前置校验：\
>    空内容（trim+去标签后可见文本）/标题>100 → 直接拒绝，不发 MQ
> 3. 免审判定 `LastHashExemptChecker`：\
>    hash 未变 + 上次 PASS + 非驳回态 → 免审直落（SUSPICIOUS 时 LastHashStore 禁写 last-hash，不会误免审）
> 4. DFA 同步闸门 `DfaFacade.check(scene, text)`：\
>    HIGH → 拦截 70001，链路终止；MID → layer1\_hit=2 标记放行（后续仍进机审二次判定）；未命中 → 放行

**阶段二 · 异步送审（MQ）**

> 1. 闸门放行后发 `TextSubmitEvent` → litisle.moderation.exchange（Topic，routing key text.submit）→ moderationTextSubmitQueue\
>    事件体只含 eventId/bizType/bizId/scene/contentHash/retryCount，**不含正文**——消费端凭定位键回查复算，前提是发布/消费两侧共用同一个 `SubmitTextAssembler`

**阶段三 · review 机审消费**

> 1. `ModerationTextConsumer` 手动 ACK 消费 → `ReviewTaskUpsertService` 幂等 upsert（定位键 bizType+bizId+scene，uk\_biz\_scene）\
>    hash 变化或已终态 → 重置清 assignee/retry 归零；在审中 → 只回填不打断已认领任务
> 2. `ModerationResultCache` 版本化缓存查重（key 含 wordlibVer+fewShotVer+scene，仅复用终局结论）\
>    未命中降级查 moderation\_record（双层兑底）
> 3. SPI 送审 `ContentModerationService` → `LlmTextModerationAdapter`\
>    防注入三件套、解析失败宁疑不放；超长文本 overlap 分片，任一片 REJECT 整文 REJECT
> 4. 三分态写 review\_task + moderation\_record（append-only）→ `ModerationResultPublisher` afterCommit 发 moderation.result\
>    统一路由，下游按 bizType 自筛

**阶段四 · 结果分流（事件驱动）**

> 1. PASS → 放行并写 last-hash（供下次免审）\
>    REJECT → `MachineRejectPunishGate` 统一处罚收口 → `RejectPunishAdapterHost` 记账 → `PunishResultPublisher` sendCritical 发 punish\
>    auth/user 按 userId 串行消费并拉权威态（禁机审/强制下线）\
>    SUSPICIOUS → status=2 进人审队列

**阶段五 · 人审与申诉（review 服务六域）**

> 1. 认领 CAS `where id=? and status=2 and assignee is null`，affected=0 → 70004；在办配额 20\
>    裁决终态 CAS（同事务写 decided\_by/opinion/decide\_time）；REJECT 走阶段四同一条处罚链路
> 2. 作者申诉 → `AppealServiceImpl` 七步编排：\
>    入参闸门 → ADMIN+原裁决人回避 → 高风险 confirm 双签（mod\_type=6）→ 改判 CAS\
>    同事务联动（处罚撤销 CAS 1→2 + violation\_score 仅重放 action∈{1,2}）→ 缓存失效 → 举报批量回写 → afterCommit 发 appeal.result

**阶段六 · 兑底与观测（sweeper，绝不默认放行的最后一道网）**

> 1. `ReviewTaskSweeper` 认领超时回池/stuck 重投；`ModerationDlqConsumer` 消费失败一律 ack，任务停留 status=0 交 sweeper 重投\
>    `QuarantineSweeper` 隔离图清理、`ModerationRecordArchiveSweeper` 04:00 错峰归档、`PunishReconcileSweeper` 双向对账\
>    `ModerationMetrics`+`AlertThresholdMetrics` 埋点告警，误报数据反哺板块五词库转正

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
>
>   <br />
> - `PunishReconcileSweeper`：正向补踢（复用 forceLogout）+ 反向回查权威态覆盖本地快
>
>       **定期把「审核处罚系统的权威记录」和「认证/会话系统的实时状态」做双向对账，发现不一致就自动补漏，确保用户不会被漏封、也不会被误伤。**
>
>   <br />
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

