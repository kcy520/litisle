## 暂不实现（注释中已声明的预留项）

- [ ] common-redis 库模块不引 Sentinel（保持轻量，仍用自研 LocalRateLimiter）——**业务模块已引 Sentinel**（Nacos 数据源规则已落地）；触发条件与落位见归档文档 archive/20260929-implementation-records.md §一·五（库模块当前不动）
- [ ] 审核模块——板块三 3-5 业务方回写矩阵消费端（content / social）（§4.5.2）（⏸ 已移入 `Temporarily_not_implemented.md`（2026-10-11），含完整步骤与 message→social 口径修正）

> 2026-10-11 转移注记：审核模块已实现任务（板块三/板块四契约层、auth 注册链路 A 组、auth 处罚消费端 B 组）及其实现步骤已归档至 `docs/archive/review-implemention-records.md`；板块三/四挂起项与预留项已移入 `docs/next/Temporarily_not_implemented.md`。

## 待实现（按优先级排序）

## 审核模块开发清单（板块化，2026-10-06 梳理自 Review-Architecture-Development-Plan.md）

> 现状基线（已完成，不再列入）：file 侧 safe\_flag 四态闸口/拒传黑名单/quarantine 契约/result·appeal.result 消费回写/getContent/占位图/对账跳隔离前缀；litisle-api 契约层（MqConstant/ModerationConstant/事件体/FileClient）；review 侧人审域（claim/decide CAS、申诉防重三层、双人复核、举报撤回/诬告反制、词库表）。
> 推进顺序：板块一 → 三 → 四/五；板块二独立可并行先行；板块六依赖三；板块七随三收尾。各条目括号内为计划文档章节号，可回溯细案。

注意**：**\
**业务代码中LLM审核部分目前留下注释预留占位即可，暂不实现**

### 板块四：发布侧文本链路接入（§4.2.1 ①①′② / §4.5.3）——已全部转移（2026-10-11）

> 板块四勾选清单与实施细案（4-1～4-7）已归档至 `docs/archive/review-implemention-records.md`；入口嵌入挂起项已移入 `docs/next/Temporarily_not_implemented.md`；进度勾选看 status.md 板块四。

### 板块五：申诉与处罚联动闭环（§4.2.3 / §4.5.2）

- [x] 机审 REJECT 联动落 punish\_log(action=3 内容下架, operator\_id=0-系统) + violation\_score 累计；自动禁言/封禁阈值判定只重放 action∈{1,2} 生效行，机审 action=3 不计入（§4.2.3）（✅ 2026-10-12 实现落地：MachineRejectPunishGate 闸口 + RejectPunishAdapterHost 宿主 + 三处 REJECT 挂点接线，明细见 status.md 5-1）
- [x] moderation.punish.result 事件 + user/auth 消费：禁言态快照维护 + 封禁 Sa-Token kickout 即时下线 + 权威态拉取（Feign /api/review/punish/status 短 TTL 本地缓存；punishLogId 仅排障严禁作版本门控）（§4.2.3）（✅ 2026-10-12 落地：5-2.1 发布收口 + auth B1/B2 消费端 + 5-2.4 网关拦截路由与 checkSpeak 禁言校验契约，明细见 status.md 5-2）
- [x] punish.result 队列按 userId 串行消费（单消费者/一致性哈希路由）+ 失败走 DLQ + 双向对账 sweeper（正向补漏 kickout / 反向补漏解封）（§4.2.3）（✅ 2026-10-12 落地：auth 侧 punishListenerContainerFactory 单消费者串行（concurrency=1+prefetch=1）+ review 侧 PunishReconcileSweeper 双向对账（SETNX 互斥锁 + forceLogout 正向补踢 + refreshPunishSnapshot 反向补解封 SSOT 收口），明细见 status.md 5-3）
- [x] 申诉改判 PASS：发布 moderation.appeal.result（allow-list：3→2/4→2、comment 2→1、message 2→1、safe\_flag 0→1）+ 缓存层同步失效（text/img-result key + last-hash + 黑名单移除该 sha256）+ punish 撤销 + violation\_score 重算 + 按内容身份回写同内容全部活跃举报 status<3（§4.2.3/§2.1）（✅ 2026-10-12 实现落地：AppealServiceImpl.judgeAppeal 七步编排全链路 + ModerationAppealResultPublisher 发布收口，明细见 status.md 5-4）
- [x] 图片申诉对称恢复：safe\_flag 0→1 + 对象从 quarantine/ 移回原前缀（unquarantine 契约已备）（✅ 2026-10-12 落地：file 侧已随基线、review 侧 ImageAppealCacheInvalidator 缓存失效收口，明细见 status.md 5-5）

### 板块六：Sweeper 基建 + quarantine 清理任务（§4.3 / §4.2.2；即原「暂不实现」quarantine sweeper 条目，触发条件本板块达成）

> 2026-10-12 落地注记：板块六四项全量完成——ReviewTaskSweeper 四职责合并（6-1 认领超时回池/申诉超时维持/积压 Gauge 三态+DLQ 深度 + 6-2 status=0 超时补偿重发，同任务内分频执行）；QuarantineSweeper（6-3 扫描源=moderation_record+最新态过滤+申诉豁免+file 侧 deleteQuarantined 前缀校验契约+限速天级+双标签 Gauge）；ModerationRecordArchiveSweeper（6-4 原计划占位、实际全量实现：moderation_record_archive 归档表 DDL + 原子迁档事务 + 引用保护 + backlog Gauge，archive-enabled 默认 false 待显式启用）；明细见 status.md 板块六。

- [x] ReviewTaskSweeper：认领超时自动回池（claim.timeout-minutes）+ 申诉超时自动维持（appeal.timeout-days，宁严勿松）+ 积压 Gauge 定时刷新（§4.3/§4.2.3）（✅ 2026-10-12）
- [x] status=0 待审超时补偿重发：阈值 > MQ 最大退避总时长 + 重发前先抢 dedup.lock + 沿用 retry\_count 不归零（§4.3）（✅ 2026-10-12）
- [x] quarantine sweeper：扫 record(biz\_type=6) verdict=REJECT 超 retention-days → 反查 appeal(status=0) 待裁决则顺延 → FileClient.deleteQuarantined（file 侧前缀校验 + 删缩略图 + del\_flag=1 + 回收站兑底）→ fail-fast 幂等 + 批量限速 + 天级频率（§4.2.2）（✅ 2026-10-12）
- [x] moderation\_record 归档任务：retention-months=6 配置先行；归档 sweeper 已按契约全量实现（原计划二期，提前落地），archive-enabled=false 默认关闭（§4.4.1）（✅ 2026-10-12）

### 板块七：观测与告警收尾（§4.8 / §4.7）

- [ ] 指标：moderation\_total{verdict} + moderation\_cost Timer + moderation\_backlog{status=pending|claimed|suspicious 三态} + moderation\_dlq\_backlog + moderation\_punish\_total{action} + moderation\_cache\_hit{layer} + wordlib\_misreport\_total{scene}（仅 scene 标签防基数爆炸）；均 ObjectProvider optional 模式
- [ ] traceId 全链路贯穿：网关→发布→MQ 消息头→消费端→moderation\_record.trace\_id
- [ ] Alertmanager 六规则：存疑积压/在办超时/DLQ 堆积/机审失败率/通过率骤变/缓存命中率跌落；阈值全部外置（§4.7）
- [ ] §4.7 剩余配置项收尾：retry.*、cache.ttl-hours+jitter-ratio、reject-blacklist.ttl-days、last-hash.ttl-hours（落 litisle-shared.yaml）、qps.max-count、consumer.prefetch、appeal.window-days、report.abuse-threshold、claim.*、sweeper.\*、dedup.lock-seconds、llm.max-text-chars、image.model-version、few-shot-version

### 审核模块暂不做（⏸ 已移入 `Temporarily_not_implemented.md` 第三节，2026-10-11）

> 云 API 生产适配器 / 图文组合审核·OCR 多模态盲区 / 撤销改判公示 / 视频·音频审核 / quarantine sweeper 预留条目均已移入 `docs/next/Temporarily_not_implemented.md` 第三节（触发条件随附），本节不再重复维护。

### 四、待办

#### 代码修改类

**【生产上线前必做】**

**【暂不做（防过度工程，附触发条件）】**

- disk 冷热分层（`-disk=ssd/hdd` + `010` 规范）：生产 Volume 多机时一并设计，不单独排期。
- 存储 TTL：quarantine 保留期（需按 appeal 待裁决态顺延）与对账清理（需 file\_ref 活跃引用前置校验）
  均为业务条件删除，不适用存储 TTL；二期大文件分片的半途清理走 S3 桶生命周期规则（已定双保险方案）。
- [ ] Sentinel Dashboard 改规则只推内存、重启丢——操作纪律：规则增删改一律去 Nacos 控制台。
- [ ] 【生产部署】监控端口网络隔离（监控链路安全前提）：各服务 8xxx 端口的 /actuator/\*\*
  免登录放行的是「路径」而非「来源 IP」，生产环境必须通过防火墙/安全组确保业务端口
  仅对内网开放（Prometheus 所在网段可达即可，公网不可达）；或升级为
  management.server.port 独立管理端口 + 网络层仅放行 Prometheus IP 段（与网关
  8081 方案同一套纪律）。开发环境单机直连现状合规，无需处理。

***

## 限流故障降级 · 本地兜底限流（已定稿并完成，2026-09-28）

> 设计思路 / 实现细节 / 完成记录已压缩归档至
> [archive/20260929-implementation-records.md](archive/20260929-implementation-records.md) §一。

- [ ] 【待验证】停 Redis 一次合并验证：受限接口应正常放行且出现「进入降级」日志，
  第二次起走本地计数；同时观察登录防爆破「本地兜底降级」日志；Redis 恢复后冷却期
  探测成功，日志出现「恢复」回归 Redis 计数。
  （可同步用 Prometheus 观察：redis\_guard\_degraded 0→1→0，Alertmanager 告警
  inactive→pending→firing→resolved，一次演练同时验收告警链路。）

***

## 待办决策定稿（2026-09-28，已定稿并完成）

> 全量决策记录已压缩归档至
> [archive/20260929-implementation-records.md](archive/20260929-implementation-records.md) §二。

- [ ] 【运维侧验证】在 Dashboard 手动触发降级 / 观察熔断日志；确认 Dashboard 改 Nacos 规则热生效。
- [ ] 【待验证】停 Redis 一次：同时观察限流降级日志与登录防爆破「本地兜底降级」日志；
  恢复 Redis 后冷却期探测成功，日志出现「恢复」。
- [ ] `instance-count` 动态化时机：接 Nacos 服务发现自动计算实例数（扩缩容免人工同步）
  ——触发条件：file 服务多实例部署上线时。
  （Micrometer 指标埋点与暴露已于 2026-09-29 落地并验证通过，详见 commom-des.md §3.5，
  剩余 Prometheus/Alertmanager 为部署侧工作，零代码改动。）
- [ ] **file模块-replication ≥ 010 + Volume 多机**：用户上传原件不可再生，单副本（replication=001）不达
  生产标准；⚠️ 副本策略只影响新写入 volume，存量不自动补副本，须在首次生产写入前定稿。
- [ ] **file模块-Filer 元数据切 MySQL**：当前 leveldb 与数据同盘，盘坏 = 目录全丢（比丢文件更致命）；
  复用已有 MySQL 实例。开发期 leveldb 无所谓。

## 审核模块剩余待办 · 实施细案（2026-10-08 定稿，可直接照此写码，无需回查 Review 计划文档）

> **前置阻塞**：litisle-ai chat 端点（POST /api/feign/ai/chat，OpenAI 兼容转发）未实现——板块三 3-1 联调前必须落地（base-url/api-key/model 走 Nacos 外置，禁提交密钥；超时对齐 feign.client.config.litisle-ai.read-timeout）。业务代码中 LLM 审核部分留注释预留占位即可（用户决策）。
> **通用纪律（所有条目默认适用，后文不再重复）**：
> ① SQL 全走 MyBatis #{} 绑定禁拼接；排序/筛选列名服务端白名单枚举映射；LIKE 对 %/_/\\ 转义。
> ② Redis key 各段服务端派生（枚举名/数字 ID/SHA256），禁拼用户原串；value 一律 JSON，禁 Java 原生反序列化。
> ③ UGC/traceId 落日志前剥离 \r\n（防 CRLF 伪造日志行）。
> ④ 指标埋点 ObjectProvider<MeterRegistry> optional 注入，未接入不影响核心功能。
> ⑤ review 不跨库直写业务表，业务态回写一律 result 事件 + 业务方 CAS；跨库回查走 litisle-api 契约，fallbackFactory fail-fast（回查失败不 ACK 进延迟重试，禁静默吞错）。
> ⑥ 缓存/黑名单/last-hash TTL 一律 ±10% 抖动（防同批内容集体过期雪崩）。


> 板块四 4-1～4-7 实施细案（含 4-3 免审 last-hash、4-5 preview-check）已归档至 `docs/archive/review-implemention-records.md`；入口嵌入挂起项见 `Temporarily_not_implemented.md` 第二节。以下细案仅覆盖板块五/六/七剩余待办。

### 板块五：申诉与处罚联动闭环（落位：litisle-review workflow 包 + litisle-user / litisle-auth 消费端）

#### 5-1 机审 REJECT 联动处罚（§4.2.3）

> 2026-10-11 落地注记：契约层骨架+挂点注释已落地（实现挂起）——litisle-api 新增 MachineRejectPunishGate（recordRejectPunish 闸口编排 + RejectPunishContext（含 score 分值快照，供 5-4 重放重算确定可算）+ RejectPunishAdapter 列口径收口，模式同 UsernameRejectGate，方法体 UnsupportedOperationException 挂起）；litisle-review 三处挂点注释已插入：ModerationTextService.persistAndPublish（3-1 文本 REJECT）、ModerationImageService.persistSingleImage（单图 REJECT）/ 配图复审聚合 REJECT（均在编程式事务内、afterCommit 注册之前，与留痕/CAS 同生共死）；PunishService 类注补收口计划。落行实现（含 userId 归属解析）与 5-2.1 punish.result 事件发布属同一批事务改造，合并落地避免二次触碰事务模板。

> 2026-10-12 实现落地注记：5-1 与 5-2.1 已全量落地——litisle-api 契约层 MachineRejectPunishGate.recordRejectPunish 闸口（入参 fail-fast + 事务活动态校验 + afterCommit 发布编排）正式实现（移除 UnsupportedOperationException 挂起）；litisle-review 侧新建 RejectPunishAdapterHost 宿主（punish_log 落行 action=3/operator_id=0/status=1 + violation_score 原子累加 setSql 参数化绑定 + 首插 DuplicateKeyException 转累加竞态兑底，仅累加 score 列不碰 last_punish_action/frozen_until）+ PunishResultPublisher 发布器（sendCritical 必达，BizException→IllegalStateException 入 DLQ，口径对齐 ModerationResultPublisher）；三处 REJECT 挂点全部接线：文本侧 fetchText 返回 FetchedText 透传 ownerId、单图支线 FileOwnerDTO 同取 fileMetaId+ownerId、配图复审聚合 REJECT 分支按 ContentClient 回查文章归属（bizId 取 task.getBizId() Long 口径），回查失败/落库失败均回滚整个审核事务入 DLQ 延迟重试。

**步骤**：
1. 3-1/3-2 消费端判 REJECT 的**同一本地事务内**：落 punish_log(action=3-内容下架, operator_id=0-系统, score=本次记入分值快照, status=1) + violation_score 累计。
2. **自动禁言/封禁阈值判定只重放 action∈{1,2} 的 status=1 生效行**——机审 action=3 不计入。

**注意事项**：
- action=3 仅落流水不改 violation_score.last_punish_action（不影响登录态）；若误计入→机审误判在申诉裁决前就触发真实封禁，误伤放大。
- 若机审驳回不处罚→违规者零分累计可低成本重复违规，违背「机审挡量、人审兑疑」漏斗设计。

#### 5-2 moderation.punish.result 事件 + user/auth 消费（§4.2.3/§4.5.2）

**步骤**：
1. 处罚落库同一本地事务内发事件，体={userId, action, op(1-生效/2-撤销), punishLogId, frozenUntil, traceId}。（✅ 2026-10-12 落地：发布收口 PunishResultPublisher，闸口 afterCommit 阶段发送，事件组装口径 action=3/op=1/frozenUntil=null/punishLogId 仅排障；落库失败不发、发布失败转 IllegalStateException 入 DLQ）
2. user 消费：按 userId 经 Feign 调 review 内部端点 GET /api/review/punish/status 拉权威态（禁言/封禁快照）**覆盖本地**——事件仅作触发信号，幂等与到达顺序无关；端点短 TTL 本地缓存；**punishLogId 仅排障，严禁作版本门控**（撤销就地改同一行、id 不变不单调，按其丢弃会吞 op=2 撤销事件致误禁持续）。
3. 封禁(action=2)：auth/user 消费后额外执行 Sa-Token kickout 强制下线全部会话（封禁即时生效）。
4. 执行点在发布链路：content/social（私信）发布前经 user 契约校验禁言态拦截发言，不能只落 punish_log。
5. 网关**不配置** /api/review/punish/status 路由（内部契约端点防公网直查处罚态）。

#### 5-3 punish.result 串行消费 + 双向对账 sweeper（§4.2.3）

**步骤**：
1. 队列固定单消费者（低频足够）；扩容时按 userId 一致性哈希路由——并发消费下「拉取→写入」可乱序，「不依赖到达顺序」仅在串行下成立。
2. 消费失败不 ACK→DLQ；配对账 sweeper（挂 6-1 调度基建）：正向=比对 punish_log status=1 封禁行 vs auth 活跃会话**补漏 kickout**；反向=比对 status=2 已撤销行 vs user/auth 仍封禁快照**补漏解封**。

**注意事项**：漏封是安全风险、漏解封是误伤，两向都要收敛；永久封禁 expire_time=null 无到期自愈，撤销事件丢失只能靠反向对账恢复。

#### 5-4 申诉改判 PASS（§4.2.3/§2.1）

> 2026-10-12 实现落地注记：review 侧全量落地——AppealServiceImpl.judgeAppeal 七步编排（步骤0入参闸门→步骤1 ADMIN/原裁决人回避校验→步骤2高风险 confirm=true 双签事务外预检→步骤3改判 CAS（affected=0 返 70004）→步骤4撤销分支同事务联动（逐行 CAS 撤销 status 1→2 + violation_score 重放生效行重算覆盖写回（update→insert→冲突重试两段式；score 含 action=3，last_punish_action/frozen_until 仅由 action∈{1,2} 生效行推导）+ mod_type=6 双签留痕）→步骤5缓存同步失效（text-result/img-result/last-hash 键删除 + 黑名单移除 sha256，键版本段与机审判定同源取值防漂移，失败仅 error 留痕）→步骤6按内容身份错位映射批量回写活跃举报）→步骤7事务提交后发 appeal.result（ModerationAppealResultPublisher.sendCritical 收口，BizException→IllegalStateException 入 DLQ）+ 被撤销封禁行补发 punish.result op=2 恢复事件（复用 PunishResultPublisher 收口，禁言撤销不发靠 frozen_until 清空+对账兑底）；步骤4属下游消费侧职责：file 侧 safe_flag 0→1+unquarantine 已随基线落地，content/message 随对应模块开发回接。

**步骤**：
1. judge 端点改判分支：CAS `update appeal set status=2, judge_by=?, judge_opinion=?, decide_time=now() where id=? and status=0`，affected=0→70004（@Idempotent 防不住跨人并发裁决）。
2. 同一本地事务：punish 撤销定位（appeal.punish_id 非空直用；否则按 review_task_id 反查 punish_log 生效行）→status=2 撤销 + violation_score 重算（重放 status=1 生效行 append-only 流水：展示/预警分含 action=3；自动处罚阈值仅 action∈{1,2}；action=1 撤销清 frozen_until、action=2 撤销发恢复事件）+ record(mod_type=6)。
3. 事务提交后发 moderation.appeal.result，体={bizType, bizId, scene, contentHash, reviewTaskId, action=1, traceId}——**必带 reviewTaskId**（notification 幂等键=A-{reviewTaskId}-{contentHash}-{verdict}）。
4. content/file/social 消费按 allow-list CAS（与机审 result 分列，防机审 PASS 绕过驳回）：article 3→2/4→2、comment 2→1、message（私信表）2→1、safe_flag 0→1。
5. **缓存层同步失效**：删 review:text-result / review:img-result 对应 key + 删 last-hash key + 从 review:img-blacklist 移除该 sha256——否则平反内容 TTL 窗口内仍判 REJECT，作者无法重发，申诉闭环断裂。
6. **按内容身份回写举报**：按 biz_type→target_type 映射（禁恒等，5↔4、6↔5）`update report set status=3, result='申诉改判PASS' where target_type=? and target_id=? and status<3`——收敛同内容全部活跃举报，非仅 appeal.report_id 那一份（防同一内容多路径举报交叉申诉绕过防重）。

**注意事项**：改判前业务态维持驳回/下架（受理仅登记不改业务态，防「未决先复活」）；原驳回裁决人（decided_by）不得裁决该内容申诉（含 ADMIN）；封禁/违规分清零类改判走双人复核四硬约束（ADMIN+回避+confirm=true+mod_type=6 双签留痕）。

#### 5-5 图片申诉对称恢复（§4.2.3）

**步骤**：
1. appeal.result 消费（bizType=6）：safe_flag 0→1（file 侧既有 appeal.result 消费链路已备）+ 调 FileClient.unquarantine(fileId) 把对象从 quarantine/ 移回原前缀；del_flag 全程 0 无需回滚。
2. 同步删 review:img-result key + 黑名单移除该 sha256（同 5-4 第 5 步）。

**✅ 落地注记（2026-10-12）**：步骤1 file 侧已随基线落地（ModerationResultListener.onAppealResult：appealPass=true→unquarantine 幂等移回原前缀 + safe_flag 回 1）；步骤2 review 侧新建 ImageAppealCacheInvalidator（moderation/cache 包），按 moderation_record 留痕行逐行重建 img-result/img-blacklist 键（版本段取留痕固化 provider/model_version/content_hash，零版本漂移，append-only 全量取覆盖历史版本键），修复既有粗粒度拼键两缺口（① 配图复审聚合指纹错位漏删；② 单图审无 review_task 缓存失效整体丢失，已前移至 reviewTask 判空之前委派）；AppealServiceImpl.invalidateModerationCache 的 bizType=6 分支委派接入后直接返回（图片场景无文本键）；删除失败仅 error 留痕不阻断裁决事务，TTL 兑底。遗留：resolveImageProvider() 私有方法已无调用方，是否删除待确认。

### 板块六：Sweeper 基建 + quarantine 清理（落位：litisle-review workflow 包 ReviewTaskSweeper + cleanup 包）

#### 6-1 ReviewTaskSweeper（§4.3/§4.2.3）

**步骤**（@Scheduled，间隔 sweeper.interval-seconds=60 外置，三职责合并）：
1. **认领超时回池**：扫 status=2 且 claim_time 早于 now-claim.timeout-minutes(30)→CAS 置回 status=0 清 assignee（decide 的 CAS 条件天然拦截回池后原审核员裁决）。
2. **申诉超时自动维持**：扫 appeal.status=0 且 create_time 早于 now-appeal.timeout-days(7)→置 status=1 + record(mod_type=6，裁决=超时维持) + 通知作者（宁严勿松，防内容/处罚态悬空）。
3. **积压 Gauge 定时刷新**：moderation_backlog{status=pending|claimed|suspicious} **三态全量**——只统计 status=2 会漏「已认领未裁决」与「status=0 卡死」两态。

#### 6-2 status=0 待审超时补偿重发（§4.3）

**步骤**：
1. 同任务内分频执行：扫 status=0 且 create_time（或最近重置时间）早于 sweeper.stuck-resubmit-minutes(30) 的任务——**阈值必须 > MQ 最大退避总时长**（retry.max-count 次指数退避累加，按实际参数计算），否则对仍在延迟重试中的消息系统性双投。
2. 重发前抢 dedup:{provider}:{contentHash} SETNX 锁，失败→跳过本轮（原消息在途）。
3. 重投 submit 事件消息头 retryCount=review_task.retry_count 原值（**不归零**，防已耗尽重试的任务被重发又拉起整轮重试）。

**注意事项**：双投由 3-1 步骤 4 的 CAS `where status=0` 去重（affected=0 直接 ACK），代价仅多一次模型调用、result 事件幂等；只扫 status=0，与 DLQ 转人审（置 2）天然互斥。

#### 6-3 quarantine sweeper（§4.2.2）

**步骤**：
1. **扫描源=moderation_record（非 review_task）**：biz_type=6 且 verdict=2-REJECT 且 create_time 早于 now-quarantine.retention-days(10)→fileId 集合——record 留痕表口径比 review_task 状态机更稳（task 会被复审/申诉改写），以 record 落库时间为基准只会顺延删除、宁晚勿早。
2. 删除前反查 appeal：该图（biz_type=6+biz_id=fileId）存在 status=0 待裁决申诉→本轮跳过顺延（retention 10>申诉窗口 7 仅是缓冲，兜不住「窗口末期申诉、缓冲期满未裁决」，须按申诉态动态豁免）。
3. 执行：FileClient.deleteQuarantined(fileId)（**review 无存储写凭证**，s3.json 三身份拆分纪律）→file 侧校验 path 确在 quarantine/ 前缀内→删本体+_thumb_* 缩略图→del_flag=1→SeaweedFS 回收站兜底 7 天。
4. 纪律：fail-fast 单条失败留 error 日志由下轮兜住（文件已删则前缀校验/不存在跳过，天然幂等）；批量限速（每秒 N 条可配）；天级频率（任务内部分频，60s 粒度无意义）。
5. 观测：quarantine 积压 Gauge(biz_type 标签)+清理 Counter；**落地后回填「暂不实现」节 quarantine sweeper 条目勾选**。

**注意事项**：file 侧 FileReconcileTask 已对 quarantine/ 前缀跳过自身 7 天清理（已落地），本任务是 quarantine/ 前缀唯一删除责任方，不得重复造删除入口。

#### 6-4 moderation_record 归档占位（§4.4.1）

**步骤**：仅落配置 litisle.moderation.record.retention-months=6（《网络安全法》≥6 月合规下限），归档任务二期：按 create_time 迁只读历史存储后删热表行；删前跳过被 appeal.status=0 或 punish_log.status=1 引用的记录；按 id 区间幂等可重跑；应用账号仅授 INSERT/SELECT。

### 板块七：观测与告警收尾（§4.8/§4.7）

#### 7-1 指标埋点（均 ObjectProvider optional 模式）

| 指标 | 类型/标签 | 埋点位置 |
|---|---|---|
| moderation_total{verdict=pass\|reject\|suspicious} | Counter | 3-1/3-2 判定落库处 |
| moderation_cost | Timer | SPI 调用包裹 |
| moderation_backlog{status=pending\|claimed\|suspicious} | Gauge | 6-1 第 3 步定时刷新 |
| moderation_dlq_backlog | Gauge | 6-1 同任务刷新（DLX 队列深度） |
| moderation_punish_total{action} | Counter | 5-1/5-4 punish_log 落库同源 |
| moderation_cache_hit{layer=result\|blacklist} | Counter | 3-1/3-2 缓存命中处 |
| wordlib_misreport_total{scene} | Counter | 已落地（板块二），灰度转正唯一数据源 |

**注意事项**：标签只允许 verdict/status/action/layer/scene 等限定枚举集合，**禁按词/内容 ID/用户打标签**（基数爆炸拖垮 Prometheus）。

#### 7-2 traceId 全链路贯穿

网关过滤器生成/透传→发布接口写入 submit 事件 traceId 字段→MQ 消息头→消费端解析→moderation_record.trace_id 落库；与厂商凭证 provider_trace_id **分列**（前者排内部链路、后者对外出证）；落日志前剥离 \r\n。

#### 7-3 Alertmanager 六规则（阈值全部外置 §4.7 alert.*）

①存疑积压>suspicious-backlog-threshold(50)；②在办超时=claimed 超 claim.timeout-minutes 未裁决；③DLQ 堆积>dlq-backlog-threshold(100)；④机审失败率>moderation-fail-rate(0.2)；⑤通过率相对基线跌幅>pass-rate-drop-ratio(0.3)；⑥缓存命中率跌落>cache-hit-drop-ratio(0.5)。括号内为建议初值，均落 Nacos。

#### 7-4 §4.7 剩余配置项收尾（落位分两类）

- **litisle-shared.yaml（跨服务共享）**：litisle.moderation.last-hash.ttl-hours=72（content 写/review 声明，须与 cache.ttl-hours 对齐防口径漂移）、cache.ttl-hours=72、cache.ttl-jitter-ratio=0.1、reject-blacklist.ttl-days=30。
- **review 服务 yml/Nacos**：retry.max-count=5 / retry.base-delay-ms=30000、qps.max-count、consumer.prefetch=10、appeal.window-days=7 / appeal.timeout-days=7、report.abuse-threshold=3、claim.timeout-minutes=30 / claim.max-active-per-reviewer=20、sweeper.interval-seconds=60 / sweeper.stuck-resubmit-minutes=30、dedup.lock-seconds=60、llm.max-text-chars=6000、image.model-version、few-shot-version、alert.* 五项。

**注意事项**：既有暂不生效的 yml 配置保留并加注释说明（用户规则，不删）。

### 完成后动作

- 每条落地后回填本文档上方清单对应 [x] 与日期。
- 板块六 6-3 落地后：勾选「暂不实现」节 quarantine sweeper 条目。
- 板块三联调前置：先落 litisle-ai chat 端点（「暂不实现」节条目）。
- 推进顺序：3-1→3-2→3-3→3-4→3-5→3-6→3-7→3-8 → 板块四（与 3-4/3-5 联调）→ 板块五（复用 result 事件）→ 板块六 → 板块七随三收尾并行埋点。

## 文档设计方案修复清单


# 临时清单

## auth 模块待办整理（2026-10-11，源：板块四/五条目按落位归集，完成一项勾一项）

### A 组：注册链路审核嵌入（触发条件同 3-5；依赖契约已全备，无其他阻塞）

- [x] A1 用户名空内容校验（4-1 嵌入点）：注册入口对用户名做 trim 非空校验（标题≤100 不适用，仅空校验）——用 `PublishTextPrecheck`（✅ 2026-10-11 落地：AuthServiceImpl.register 步骤 0 嵌入 PublishTextPrecheck.check(null, username)）
- [x] A2 DFA 闸门注入（4-2.1 嵌入点）：USERNAME 链路调 `DfaFacade.check` → `DfaPublishGate.decide`：HIGH 拒 70001 / MID 放行仅留痕（USERNAME 不建任务，layer1\_hit=2 无异步承接，同音/跳词变体靠注册后举报+人审兑底）（✅ 2026-10-11 落地：register 步骤 0.1 嵌入 check+decide；auth 侧装配四件套 AuthDfaConfig/RedisWordLibraryLoader/WordlibChangeSubscriber——Redis 快照优先+本地文件兑底，词库热更订阅 review:wordlib:change）
- [x] A3 注册同步拒绝 + 取证（4-2.3/4-7 嵌入点）：`blocked=true` 时调 `UsernameRejectGate.recordReject` 落取证行（biz\_id=0 不建任务，取证行不落用户名明文），拒绝即终态、提示换名重试、禁回显命中词；取证失败仅 error 留痕不影响拦截（✅ 2026-10-11 落地：register 步骤 0.2 先取证后抛 70001；取证链路 UsernameRejectRecordAdapter → ReviewClient 契约（含降级工厂 fail-fast）→ review 侧 ReviewFeignProviderController + UsernameRejectRecordServiceImpl 落 moderation_record(biz_id=0, mod_type=1-DFA, verdict=2-REJECT)，append-only）
- [x] A4 编排顺序：空校验 → DFA 闸门 → 拒绝取证/放行注册（顺序收口在 DfaPublishGate 类注）（✅ 2026-10-11 落地：register 方法 Javadoc 收口编排顺序铁律与依据，对齐 DfaPublishGate 类注落位口径）

### B 组：punish.result 处罚消费端（触发条件：review 侧 5-2.1 事件发布先行）

- [x] B1 处罚消费 + 权威态拉取（5-2.2）：消费 `moderation.punish.result`，经 Feign GET /api/review/punish/status 拉权威态覆盖本地禁言快照（短 TTL 本地缓存）；punishLogId 仅排障严禁作版本门控（防吞 op=2 撤销）（✅ 2026-10-11 落地：PunishMqConfig 拓扑（主队列+DLQ+双绑定，参数与 review 侧对齐，延迟交换机不声明由 review 侧提供）+ PunishResultConsumer 编排（MqTraceSupport 链路恢复 → executeWithRetry 指数退避模板 → 回查 → 覆盖快照 → kickout；重试参数外置 litisle.moderation.punish.retry.*；降级 BizException 转 IllegalStateException 走重试/DLQ 防漏封静默吞错）+ PunishResultEvent 补 retryCount 与 ACTION\_MUTE/ACTION\_BAN 常量）
- [x] B2 封禁即时下线（5-2.3）：action=2 → Sa-Token kickout 强制下线该用户全部会话（✅ 2026-10-11 落地：PunishResultConsumer 步骤5——以回查权威态 banned 为准（非事件 action 判定，双保险防撤销事件误踢），StpUtil.logout(userId) 踢出全部会话，幂等重复执行无害）
- [x] B3 禁言态快照维护（5-2 关联）：本地快照供发布链路拦截用（拦截点在 content，快照源在 auth/user）；撤销处罚后快照须同步解封（✅ 2026-10-11 落地：PunishSnapshotService/Impl——Redis key auth:punish:snapshot:{userId}（AuthCacheKey 常量），value JSON 覆盖式 SET（无 read-modify-write、无 delete 分支，撤销=全空快照覆盖=同步解封），TTL 外置默认 300s±10% 抖动；写失败上抛转重试防漏封，读失败全空快照兑底；本地 Caffeine 暂不引入，待发布链路拦截接入后按需）
- [x] B4 串行消费配置（5-3.1）：punish.result 队列固定单消费者串行；扩容按 userId 一致性哈希；消费失败不 ACK → DLQ（✅ 2026-10-11 落地：PunishConsumeConfig 串行容器工厂——单消费者+prefetch=1+手动 ACK+显式挂 common-mq Jackson 转换器（关键坑：Spring Boot 自动配置仅覆盖主工厂，自定义工厂漏设必成反序列化毒丸），PunishResultConsumer containerFactory 接线；扩容预留 x-consistent-hash 一致性哈希扩展点，类注已收口口径）
- [x] B5 对账查询供给（5-3.2 关联）：双向对账 sweeper（挂 6-1 调度）比对 punish\_log 封禁行 vs auth 活跃会话，auth 侧需提供会话查询契约或配合补漏 kickout（正向补踢人/反向补解封）（✅ 2026-10-11 落地：契约侧 PunishAuditDTO + AuthClient 两端点 getPunishAudit/refreshPunishSnapshot（/api/feign/** 网关不配路由）+ 降级工厂两端点 fail-fast 禁降级（对账假「无异常」会致漏补漏）；auth 侧 PunishAuditService/Impl——活跃会话判定 Sa-Token 登录态 ∥ 设备索引非空、任一判定故障保守按活跃上报（宁多补踢不漏踢）；反向补解封 SSOT 收口仅经回查覆盖禁直写；正向补踢复用既有 forceLogout 不新增端点）

### 依赖与注意事项

- A 组可独立先行（auth 注册功能动工时一次性嵌入）；B 组等板块五 review 侧启动，按 5-2.1 → B1/B2 → B4 → B5 顺序推进（✅ 2026-10-11 注记：B1-B5 代码已全部落地，仅待 review 侧 5-2.1 事件发布先行后联调）
- 网关联动：/api/review/punish/status 不得配外网路由（5-2.4，gateway 配置项非 auth 侧），但 B1/B5 内部调用依赖该端点可达