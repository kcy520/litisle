> **拆分归档**：本文件拆分自同目录索引页 [review-implemention-records.md](review-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。

### 板块四：发布侧文本链路接入（契约层落地记录，2026-10-11，自 next_update.md 转移）

**落位**：litisle-api moderation 包 / event/content 包 / dto/content 包 + MqConstant/ModerationConstant 常量补齐；入口嵌入挂起部分见 next_update.md「暂不实现」节审核模块条目（auth 注册链路已嵌入，见下 A 组）。

- [x] 4-1 发布前置校验：PublishTextPrecheck.check/hasVisibleText/stripHtmlTags + SubmitTextAssembler.assemble/contentHash（SHA-256 小写十六进制）+ ErrorCode 新增 CONTENT\_EMPTY(30005)/TITLE\_TOO\_LONG(30006) + ModerationTextService#fetchText 分隔符收口 TEXT\_SUBMIT\_SEPARATOR（标题与正文拼接同口径防违规藏标题；空内容 content\_hash 恒定，校验必须在 hash 计算与送审之前）
- [x] 4-2 同步 DFA 闸门契约：DfaPublishGate.decide 三态决策（HIGH 拦截/MID 标记/未命中放行，入参判空 fail-fast）+ DfaFacade 兑底级别收口 DFA\_LEVEL\_HIGH；私信 HIGH 拒发不投递 MQ、USERNAME 同步拒绝即终态、MID 只标记不拦截、scene 传原始值由 DfaScenes 归一口径入 Javadoc
- [x] 4-3 免审 last-hash：LastHashStore（RedisAdapter 函数式注入零 redis 依赖 / write 终局 fail-fast 含 ttlSeconds / read 正则强校验异常按未命中 / remove 单键删除）+ LastHashExemptChecker（decide 五步三分支：PASS 免审直落并置 review\_task 终态 / REJECT 前置提示 / 键缺失照常发 MQ；statusExcluded 按 bizType 分列未定义口径 fail-fast，评论排除 2、文章排除 3/4 补 del\_flag=0；imgReReviewPassed 复用 LAST\_HASH\_KEY 连带校验：键存在+聚合 hash 一致+PASS 三者齐备）+ ModerationConstant 补 BIZ\_TYPE\_ARTICLE/COMMENT/MESSAGE 与 SCENE\_ARTICLE\_IMG\_RE\_REVIEW
- [x] 4-4 content.unpublished 同步失效：event/content 包 ContentUnpublishedEvent（@Data+Serializable，Schema 四公共字段+bizType/bizId/contentHash/reason）+ ContentUnpublishedInvalidator.invalidateAll 三步矩阵（last-hash/text-result/img-result 全删；RedisKeyDeleter 函数式注入零 redis 依赖；deleter 判空 fail-fast；失败语义=宿主 try/catch 仅 error 留痕不回滚下架事务，残留键 TTL 过期兑底）+ MqConstant 补 QUEUE/ROUTING\_CONTENT\_UNPUBLISHED（防「PASS→下架→删除→重发同 hash」命中旧 PASS 缓存绕过下架决定的历史攻击面）
- [x] 4-5 preview-check 契约：dto/content 包 ContentPreviewCheckDTO（scene/title/content 三字段）+ ContentPreviewCheckVO（仅 {hit,risk} 两字段，禁回吐命中词防词库 oracle 探测）
- [x] 4-6 私信撤回/陌生人配额契约：MessageRecallGate.recall（适配器/入参 fail-fast + CAS 结果判定，affected=0 统一失败禁区分原因）+ StrangerQuotaGate.tryAcquire（五步编排：双适配器 fail-fast→Redis INCR 为主→null 降级 DB 条件更新 a\_quota\_used<3→上限比对，漂移口径留档；REJECT 拦截仍占配额）+ ModerationConstant 补 MESSAGE\_STATUS\_*/STRANGER\_QUOTA\_*
- [x] 4-7 USERNAME 同步拒绝契约：UsernameRejectGate.recordReject（HIGH 命中 fail-fast 校验 + SubmitTextAssembler 全站口径派生 contentHash + RecordAdapter 取证落库；不建 review\_task/biz\_id=0 占位，取证行不落用户名明文；不可申诉=明示设计非遗漏，换名重试无改名路径防绕过）+ ModerationConstant 补 BIZ\_TYPE\_USERNAME=3（对齐 review 侧 ModerationBizTypeEnum.USERNAME(3)）

#### 子项级进度勾选（2026-10-13 自 status.md 迁移；未实现挂起子项未随迁，见 next/Temporarily_not_implemented.md）

- [x] 4-1.1 空内容/标题校验：标题 trim 非空 + 正文去标签可见文本非空 + 标题≤100 字符（2026-10-11 契约层实现落地，入口嵌入随发布链路）
- [x] 4-1.2 送审文本=标题+正文拼接（hash 与模型同口径，防违规藏标题）（2026-10-11 契约层实现落地，消费侧 fetchText 已同步 SSOT 常量）
- [x] 4-2.3 USERNAME 注册同步拒绝即终态（联动 4-7）（✅ 2026-10-11 注册链路嵌入已随 auth A2/A3 落地，见下文 auth A 组记录）
- [x] 4-3.3 免审状态排除按 bizType 分列（文章排除 3/4、评论排除 2，补 del_flag=0）（2026-10-11 statusExcluded 实现落地，入口嵌入随发布链路）
- [x] 4-3.4 含配图文章连带校验：IMG_RE_REVIEW last-hash 存在 + 聚合 hash 一致 + PASS 三者齐备（2026-10-11 imgReReviewPassed 实现落地，调用方聚合指纹随 content 链路传入）
- [x] 4-4.1 删除/下架/转私密三路径发 unpublished 事件并删 last-hash + text/img-result 缓存键（2026-10-11 契约层实现落地：ContentUnpublishedEvent 全字段 POJO（@Data+Serializable，Schema 四公共字段+bizType/bizId/contentHash/reason 四业务字段）+ ContentUnpublishedInvalidator.invalidateAll 三步矩阵（RedisKeyDeleter 函数式注入，契约层零 redis 依赖）+ MqConstant 补 QUEUE/ROUTING_CONTENT_UNPUBLISHED；三路径发布入口嵌入挂起待 content 开发，触发条件同 3-5）
- [x] 4-4.2 免审直落排除条件补 del_flag=0（2026-10-11 已随 LastHashExemptChecker.statusExcluded 实现落地：delFlag≠0 一票排除）
- [x] 4-6.1 撤回 CAS：仅本人仅正常态 status=1→0，不物理删正文（2026-10-11 recall 实现落地，CAS SQL 由宿主适配器收口，端点嵌入挂起待 message）
- [x] 4-6.2 陌生人配额：Redis INCR 为主，失败降级 DB 原子条件更新 a_quota_used<3（2026-10-11 tryAcquire 实现落地，发送链路嵌入挂起待 message）
- [x] 4-7.1 DFA HIGH 同步拒绝不建任务，只落 moderation_record(biz_id=0) 取证（2026-10-11 recordReject 实现落地，取证列口径由宿主适配器收口，取证行不落用户名明文）
- [x] 4-7.2 明示不可申诉设计（换名重试，无改名路径防绕过）（2026-10-11 类注留档：无 review_task 即无申诉载体，端点天然查不到，零拦截逻辑；二期若引入改名须重新过 USERNAME 场景闸门）

### auth 模块注册链路审核嵌入（A1-A4，2026-10-11 落地，自 next_update.md 临时清单转移）

- [x] A1 用户名空内容校验（4-1 嵌入点）：AuthServiceImpl.register 步骤 0 嵌入 PublishTextPrecheck.check(null, username)
- [x] A2 DFA 闸门注入（4-2.1 嵌入点）：register 步骤 0.1 嵌入 DfaFacade.check+DfaPublishGate.decide（HIGH 拒 70001/MID 放行仅留痕；USERNAME 不建任务无异步承接，同音/跳词变体靠注册后举报+人审兑底）；auth 侧装配 AuthDfaConfig/RedisWordLibraryLoader/WordlibChangeSubscriber——词库装载 Redis 快照优先+本地持久化快照兑底、空词库拒绝构建引擎 fail-fast（防空库 default-allow 放行），热更订阅 review:wordlib:change 广播、version 乐观比对防乱序/重复
- [x] A3 注册同步拒绝+取证（4-2.3/4-7 嵌入点）：register 步骤 0.2 先取证后抛 70001；链路 UsernameRejectRecordAdapter→ReviewClient（降级工厂 fail-fast）→review 侧 UsernameRejectRecordServiceImpl 落 moderation\_record(biz\_id=0, mod\_type=1, verdict=2) append-only；取证失败仅 error 留痕不影响拦截结论（宁严勿松），不携带用户名明文 PII
- [x] A4 编排顺序铁律：空内容校验（A1）→DFA 闸门（A2）→拒绝取证/放行注册（A3），register 方法 Javadoc 收口三条时序依据，对齐 DfaPublishGate 类注落位口径

### auth 模块 punish.result 处罚消费端（B1-B5，2026-10-11 落地，自 next_update.md 临时清单转移；待 review 侧 5-2.1 事件发布后联调）

- [x] B1 处罚消费 + 权威态拉取（5-2.2）：消费 `moderation.punish.result`，经 Feign GET /api/review/punish/status 拉权威态覆盖本地禁言快照（短 TTL 本地缓存）；punishLogId 仅排障严禁作版本门控（防吞 op=2 撤销）（✅ 2026-10-11 落地：PunishMqConfig 拓扑（主队列+DLQ+双绑定，参数与 review 侧对齐，延迟交换机不声明由 review 侧提供）+ PunishResultConsumer 编排（MqTraceSupport 链路恢复 → executeWithRetry 指数退避模板 → 回查 → 覆盖快照 → kickout；重试参数外置 litisle.moderation.punish.retry.*；降级 BizException 转 IllegalStateException 走重试/DLQ 防漏封静默吞错）+ PunishResultEvent 补 retryCount 与 ACTION_MUTE/ACTION_BAN 常量）
- [x] B2 封禁即时下线（5-2.3）：action=2 → Sa-Token kickout 强制下线该用户全部会话（✅ 2026-10-11 落地：PunishResultConsumer 步骤5——以回查权威态 banned 为准（非事件 action 判定，双保险防撤销事件误踢），StpUtil.logout(userId) 踢出全部会话，幂等重复执行无害）
- [x] B3 禁言态快照维护（5-2 关联）：本地快照供发布链路拦截用（拦截点在 content，快照源在 auth/user）；撤销处罚后快照须同步解封（✅ 2026-10-11 落地：PunishSnapshotService/Impl——Redis key auth:punish:snapshot:{userId}（AuthCacheKey 常量），value JSON 覆盖式 SET（无 read-modify-write、无 delete 分支，撤销=全空快照覆盖=同步解封），TTL 外置默认 300s±10% 抖动；写失败上抛转重试防漏封，读失败全空快照兑底；本地 Caffeine 暂不引入，待发布链路拦截接入后按需）
- [x] B4 串行消费配置（5-3.1）：punish.result 队列固定单消费者串行；扩容按 userId 一致性哈希；消费失败不 ACK → DLQ（✅ 2026-10-11 落地：PunishConsumeConfig 串行容器工厂——单消费者+prefetch=1+手动 ACK+显式挂 common-mq Jackson 转换器（关键坑：Spring Boot 自动配置仅覆盖主工厂，自定义工厂漏设必成反序列化毒丸），PunishResultConsumer containerFactory 接线；扩容预留 x-consistent-hash 一致性哈希扩展点，类注已收口口径）
- [x] B5 对账查询供给（5-3.2 关联）：双向对账 sweeper（挂 6-1 调度）比对 punish_log 封禁行 vs auth 活跃会话，auth 侧需提供会话查询契约或配合补漏 kickout（正向补踢人/反向补解封）（✅ 2026-10-11 落地：契约侧 PunishAuditDTO + AuthClient 两端点 getPunishAudit/refreshPunishSnapshot（/api/feign/** 网关不配路由）+ 降级工厂两端点 fail-fast 禁降级（对账假「无异常」会致漏补漏）；auth 侧 PunishAuditService/Impl——活跃会话判定 Sa-Token 登录态 ∥ 设备索引非空、任一判定故障保守按活跃上报（宁多补踢不漏踢）；反向补解封 SSOT 收口仅经回查覆盖禁直写；正向补踢复用既有 forceLogout 不新增端点）

### 板块四实施细案步骤（2026-10-11 自 next_update.md 转移；契约层已实现，入口嵌入挂起项见 next/Temporarily_not_implemented.md）

#### 4-1 发布前置校验（§4.2.1 ①′）

**步骤**：
1. 发布/评论/私信接口内、DFA 之前执行：空内容=标题 trim 非空 且 正文去 HTML 标签后可见文本非空（防 `<p></p>` 空壳绕过）；标题≤100 字符（对齐 varchar(100)）。
2. 送审文本=标题+正文拼接（DFA 与 LLM 同口径；标题只进 hash 不进模型=违规藏标题躲过 LLM 漏审）。

**注意事项**：空内容校验必须在 hash 计算与送审之前——空内容 content_hash 恒定，全站共享同一 hash，一条 PASS 缓存即污染全部空内容免审。

#### 4-2 同步 DFA 闸门嵌入（§4.2.1 ①）

**步骤**：
1. 文章/评论/私信/用户名四条发布链路注入 DfaFacade（板块二已下沉 litisle-api，本地执行免网络调用）。
2. check(scene, 文本)→DfaHitResult：HIGH 命中→拒 70001；MID→不拦截，layer1_hit=2 随任务送异步层。
3. **私信特殊**：HIGH 命中同步拒发 70001 且**不投递 MQ**（不进异步库）；仅 MID/LLM 走先发后审。
4. USERNAME：注册路径同步拒绝即终态（见 4-7）。

**注意事项**：scene 传原始值由 DfaScenes 归一（去 _RE_REVIEW 后缀等），勿自行映射；MID 只标记不拦截（新增词灰度观察位）。

#### 4-3 免审 last-hash（§4.2.1 ②）

**步骤**：
1. content 消费 result 事件时写 Redis `review:last-hash:{wordlibVer}:{bizType}:{scene}:{bizId}`，value={hash,verdict} JSON，TTL=last-hash.ttl-hours(72)±10% 抖动，与 cache.ttl-hours 对齐（防「消费端已过期重审、发布侧仍免审」口径漂移）。
2. 发布时判定顺序：算新 hash→查 key（wordlibVer 取当前 DfaFacade 该 scene 版本）→三分支：hash 一致且 verdict=PASS 且状态排除→**免审直落 status=2，不发 MQ，且 review_task 就地置 status=1 终态**；hash 一致且 verdict=REJECT→前置提示「内容未修改仍将驳回」，不落库不发 MQ；key 缺失/SUSPICIOUS→照常发 MQ（Redis 丢数据宁多审不漏审）。
3. **状态排除按 bizType 分列**：文章 status∉{3-驳回,4-下架}；评论 status≠2-驳回（评论驳回态=2，套文章口径会漏排除→被驳评论原样重发复活）；均补 del_flag=0。
4. **含配图文章连带校验** `review:last-hash:{wordlibVer}:1:ARTICLE_IMG_RE_REVIEW:{articleId}` 存在、其聚合 hash=当前配图 sha256 排序拼接后再 sha256、verdict=PASS，三者缺一不可。
5. 免审直落必须置 review_task 终态（status=1）。

**注意事项**：
- 缺「聚合 hash 一致」比对→仅换图不改文时旧 PASS 键命中，架空图片复审。
- 缺「置终态」→sweeper（6-2）把免审任务当卡死重投 MQ，可能机审 REJECT 触发已发布文章 2→4 下架。

#### 4-4 content.unpublished 同步失效（§2.1）

**步骤**：
1. 删除(del_flag=1)/下架(status=4)/转私密 三处发 content.unpublished 事件的同一事务/紧随其后：删 `review:last-hash:{...}:{bizId}` + 删该 content_hash 的 text-result/img-result 缓存键。
2. 免审直落排除条件补 del_flag=0（同 id 复活场景）。

**注意事项**：防「PASS→人工/法务下架→删除→重发同内容（新 bizId 同 hash）」命中旧 PASS 缓存绕过下架决定——历史已定性的攻击面，必须闭环。

#### 4-5 POST /api/content/preview-check（§4.5.3）

**步骤**：
1. content 侧新增端点：入参=草稿/待发内容标识（或标题+正文），复用 DfaFacade 同 scene、同词库版本同步校验。
2. 返回仅 {hit: boolean, risk: HIGH|MID|none}，**禁回吐命中词**。
3. 鉴权：登录用户+仅本人草稿/待发内容；@RateLimiter(USER) 限频。
4. Javadoc 明示：预览仅为体验优化，**发布侧同步闸门必须独立执行，不替代发布校验**。

**注意事项**：回吐命中词=词库 oracle，攻击者可逐词探测黑名单后以同音/插字变体绕过；与发布同 scene 同词库版本保证口径一致。

#### 4-6 私信撤回 CAS + 陌生人配额（§4.2.1）

**步骤**：
1. 撤回：`update message set status=0 where id=? and sender_id=? and status=1`——仅本人、仅正常态；不物理删正文（留存审计），对方仅见「消息已撤回」。
2. 陌生人配额：Redis INCR 为主；Redis 失败→DB 原子条件更新 `update conversation set a_quota_used=a_quota_used+1 where id=? and a_quota_used<3`（禁读-改-写两段式）；REJECT 拦截消息仍占配额（投递行为已发生）。

**注意事项**：撤回与审核 1→2 CAS 并发互斥；撤回成功后审核 result 的 2→1 因 status=0 天然 no-op。

#### 4-7 USERNAME 同步拒绝（§4.2.2）

**步骤**：
1. 注册路径 DFA HIGH 命中→同步拒绝，**不建 review_task**，只落 moderation_record(biz_type=3, biz_id=0, hit_words 自包含取证)。
2. 不可申诉（appeal 三关联列至少其一非空才可建）——用户名被拒换名重试即可，明示设计非遗漏；无改名路径防绕过审核。

**注意事项**：同音/跳词变体漏网靠注册后举报+人审兜底（板块二误报入口可辅助运营观察）。

