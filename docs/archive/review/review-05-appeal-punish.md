> **拆分归档**：本文件拆分自同目录索引页 [review-implemention-records.md](review-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。

### 板块五：申诉与处罚联动闭环（§4.2.3 / §4.5.2，2026-10-12 全量完成，自 next_update.md 转移）

- [x] 机审 REJECT 联动落 punish\_log(action=3 内容下架, operator\_id=0-系统) + violation\_score 累计；自动禁言/封禁阈值判定只重放 action∈{1,2} 生效行，机审 action=3 不计入（§4.2.3）（✅ 2026-10-12 实现落地：MachineRejectPunishGate 闸口 + RejectPunishAdapterHost 宿主 + 三处 REJECT 挂点接线，明细见 status.md 5-1）
- [x] moderation.punish.result 事件 + user/auth 消费：禁言态快照维护 + 封禁 Sa-Token kickout 即时下线 + 权威态拉取（Feign /api/review/punish/status 短 TTL 本地缓存；punishLogId 仅排障严禁作版本门控）（§4.2.3）（✅ 2026-10-12 落地：5-2.1 发布收口 + auth B1/B2 消费端 + 5-2.4 网关拦截路由与 checkSpeak 禁言校验契约，明细见 status.md 5-2）
- [x] punish.result 队列按 userId 串行消费（单消费者/一致性哈希路由）+ 失败走 DLQ + 双向对账 sweeper（正向补漏 kickout / 反向补漏解封）（§4.2.3）（✅ 2026-10-12 落地：auth 侧 punishListenerContainerFactory 单消费者串行（concurrency=1+prefetch=1）+ review 侧 PunishReconcileSweeper 双向对账（SETNX 互斥锁 + forceLogout 正向补踢 + refreshPunishSnapshot 反向补解封 SSOT 收口），明细见 status.md 5-3）
- [x] 申诉改判 PASS：发布 moderation.appeal.result（allow-list：3→2/4→2、comment 2→1、message 2→1、safe\_flag 0→1）+ 缓存层同步失效（text/img-result key + last-hash + 黑名单移除该 sha256）+ punish 撤销 + violation\_score 重算 + 按内容身份回写同内容全部活跃举报 status<3（§4.2.3/§2.1）（✅ 2026-10-12 实现落地：AppealServiceImpl.judgeAppeal 七步编排全链路 + ModerationAppealResultPublisher 发布收口，明细见 status.md 5-4）
- [x] 图片申诉对称恢复：safe\_flag 0→1 + 对象从 quarantine/ 移回原前缀（unquarantine 契约已备）（✅ 2026-10-12 落地：file 侧已随基线、review 侧 ImageAppealCacheInvalidator 缓存失效收口，明细见 status.md 5-5）

#### status.md 子项级进度勾选（2026-10-13 自 status.md 转移，去重后补录：父项与上方清单重复不迁，仅迁子项；挂起子项 5-4.4 在 Temporarily 文档跟踪不迁）

- [x] 5-1.1 REJECT 同一事务落 punish_log(action=3，operator_id=0) + violation_score 累计（✅ 2026-10-12 实现落地：三处挂点均在编程式事务内、任务 CAS 流转之后，处罚落库失败抛 IllegalStateException 回滚整个审核事务入 DLQ 延迟重试；userId 文本按回查 DTO/单图按 FileOwnerDTO/复审聚合按 ContentClient 回查归属）
- [x] 5-1.2 禁言/封禁阈值判定只重放 action∈{1,2}（action=3 仅落流水不计入）（✅ 2026-10-12 实现落地：宿主 RejectPunishAdapterHost 仅累加 violation_score.score 列，禁碰 last_punish_action/frozen_until，红线由落行口径收口）
- [x] 5-2.1 处罚落库同事务发 punish.result 事件（体含 userId/action/op/punishLogId/frozenUntil/traceId）（✅ 2026-10-12 落地：闸口组装事件 action=3/op=1/punishLogId 仅排障/frozenUntil=null，PunishResultPublisher.sendCritical 发布至 ROUTING_MODERATION_PUNISH；事务顺序铁律：落库失败不发、afterCommit 发送失败转 IllegalStateException 入 DLQ）
- [x] 5-2.2 user 消费：经 Feign 拉权威态覆盖本地（punishLogId 禁作版本门控，防吞 op=2 撤销）（✅ auth B1 已随 B 组 2026-10-11 落地，5-2.1 发布端已通，待联调）
- [x] 5-2.3 封禁 action=2 联动 Sa-Token kickout 强制下线全会话（✅ auth B2 已随 B 组 2026-10-11 落地，5-2.1 发布端已通，待联调）
- [x] 5-2.4 发布链路禁言态拦截 + 网关不配 /api/review/punish/status 路由（✅ 2026-10-12 安全防线+契约层落地：网关新增 block-review-punish-status 显式拦截路由（no://op + 404，置于 review 前缀路由前优先命中，不暴露端点存在性）+ AuthClient.checkSpeak 契约端点 + PunishSpeakCheckDTO（speakAllowed 唯一准入结论，细分态仅透传供文案，调用方禁二次判定）+ AuthClientFallbackFactory fail-fast 禁降级（静默放行=漏禁风险）+ auth 侧 PunishAuditService.checkSpeak 决策收口（banned 一票拒 / muted 永久或未到期拒）+ AuthFeignProviderController 接线；content/social 发布链路嵌入挂起随对应模块开发回接）
- [x] 5-3.1 队列固定单消费者串行（扩容按 userId 一致性哈希）（✅ 2026-10-12 已随 auth B4 落地：PunishConsumeConfig.punishListenerContainerFactory 固定 concurrentConsumers=1/maxConcurrentConsumers=1 + prefetch=1（处理完才取下一条，串行语义兜底）+ MANUAL ACK + 显式 Jackson 转换器（防原生反序列化毒丸），PunishResultConsumer 指定该工厂，失败 nack 入 DLQ）
- [x] 5-3.2 双向对账 sweeper：正向补漏 kickout / 反向补漏解封（挂 6-1 调度基建）（✅ 2026-10-12 PunishReconcileSweeper 落地（review 侧 sweeper 包）：cron 外置默认每 10 分钟 + SETNX 互斥锁（value=实例 token 防误删他锁）+ 正向补踢 reconcileForward 复用既有 forceLogout 契约（宁多补踢不漏踢）+ 反向补解封 reconcileBackward 经 refreshPunishSnapshot 回查覆盖（SSOT 收口禁直写快照）+ 单用户失败不阻断整轮；LitisleReviewApplication 开启 @EnableScheduling，litisle.moderation.reconcile.* 配置外置（batch-limit/revoke-lookback-days/lock-seconds））
- [x] 5-4.1 改判 CAS：appeal status=0→2（affected=0→70004）（✅ 2026-10-12 落地：CAS where id=? and status=0，affected=0 返 TASK_ALREADY_CLAIMED(70004)）
- [x] 5-4.2 同事务处罚撤销 + violation_score 重算（仅重放生效行）+ mod_type=6 双签留痕（✅ 2026-10-12 落地：punish_id 非空直用/否则按 review_task_id 反查生效行，逐行 CAS status 1→2+revoke_time；重算覆盖写回（score 含 action=3，last_punish_action/frozen_until 仅由 action∈{1,2} 生效行推导，update→insert→冲突重试两段式）；mod_type=6+verdict=PASS 留痕，operator_id=裁决人）
- [x] 5-4.3 事务后发 appeal.result（必带 reviewTaskId 作 notification 幂等键）（✅ 2026-10-12 落地：ModerationAppealResultPublisher.sendCritical 至 EXCHANGE_MODERATION+ROUTING_MODERATION_APPEAL_RESULT，事务顺序铁律：落库失败不发、发布失败 BizException→IllegalStateException 入 DLQ）
- [x] 5-4.5 缓存层同步失效：删 text-result/img-result/last-hash key + 黑名单移除 sha256（✅ 2026-10-12 落地：键版本段（wordlibVer/fewShotVer/provider/modelVersion）与机审判定同源取值防漂移；文本场景删 text-result+last-hash、bizType=6 删 img-result+黑名单移除 sha256；失败仅 error 留痕 TTL 兑底）
- [x] 5-4.6 按内容身份回写同内容全部活跃举报（biz→target 映射禁恒等，status<3）（✅ 2026-10-12 落地：错位映射 MESSAGE 5→4/FILE 6→5，按 target_type+target_id 批量置 3-已裁决+handler=裁决人，非仅 appeal.report_id 那一份）
- [x] 5-5.1 appeal.result(bizType=6)：safe_flag 0→1 + FileClient.unquarantine 移回原前缀（✅ 已随基线落地：ModerationResultListener.onAppealResult 消费 appealPass=true→unquarantine 幂等移回原前缀 + safe_flag 回 1，维持驳回无操作保持隔离）
- [x] 5-5.2 同步删 img-result 缓存 key + 黑名单移除该 sha256（✅ 2026-10-12 落地：新建 ImageAppealCacheInvalidator（moderation/cache 包）按 moderation_record 留痕行（biz_type=6+biz_id 全量 append-only）逐行重建 img-result/img-blacklist 键批量删除，版本段取留痕固化 provider/model_version/content_hash 零漂移，覆盖「配图聚合指纹错位漏删」与「单图审无 review_task 整体丢失」两缺口；AppealServiceImpl.invalidateModerationCache 的 bizType=6 分支前移委派；失败仅 error 留痕不阻断裁决事务，TTL 兑底）

### 板块五实施细案步骤（2026-10-13 自 next_update.md 转移）

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

