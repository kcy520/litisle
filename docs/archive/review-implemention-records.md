
### 板块三：MQ 异步机审链路（§4.2.1/§4.2.2/§4.5.2/§4.2.4）

- [x] ModerationTextConsumer：幂等（bizType+bizId+scene 定位 + CAS status=0 + 按 content\_hash 分流补发/丢弃）+ 缓存查重（Redis→idx\_hash 表兜底，provider/wordlib\_ver/TTL 窗口比对；命中缓存仍补留痕行）+ 超长分片（段落分片→字符窗口 overlap 硬切，禁静默截断）（§4.2.1 ④⑤⑥）
- [x] ModerationImageConsumer：取图一律走 FileClient 内部字节流（严禁按用户 URL 抓取）+ 图片缓存 key 含 provider+modelVersion（§4.2.2/§2.1）（2026-10-09 完成：ModerationImageConsumer 消费编排 + ModerationImageService 业务编排，含单图审/复审两支线、聚合判定、CAS 流转、缓存读脏降级送审、TTL±10% 抖动、afterCommit 写缓存发事件）
- [x] 统一建任务 upsert：uk\_biz\_scene 复用同行 + 重置例外/目标态按场景分列 + 清机审证据列 + 多轮举报回写/反查一律 status<3 收敛（§4.2.2）（2026-10-10 完成：ReviewTaskUpsertService + Impl）
- [x] result 事件发布：事务顺序「本地事务提交→publisher confirm→ACK」；事件体必带 scene/contentHash/wordlibVer/reviewTaskId；bizType↔target\_type 映射表禁恒等（5↔4、6↔5 错位）（§4.5.2）（2026-10-10 完成：ModerationResultPublisher 统一发布器 + MqConstant 补三路由键 + ModerationResultEvent 补五字段；遗留：既有内联发事件切换 + file 侧绑定联动改造）
- [ ] 业务方回写矩阵消费端（content/social）：文章双链路联合聚合（含配图须图片复审 PASS；无图仅文本 PASS；纯图/空边界）+ 评论 CAS 带 del_flag=0 + 私信 1→2 CAS（§4.5.2）（⏸ 已移入「暂不实现」节含完整步骤：litisle-content 为空壳、social 未开发（私信归属 social，无独立 message 模块），触发条件=两模块正式开发时随实体层落地；2026-10-10 决策先转 3-6）
- [x] ModerationDlqConsumer：重试耗尽转人审兑底（review\_task.status=2 / safe\_flag=3 / mod\_verdict=3）（§4.2.4）（2026-10-10 完成：ModerationDlqConsumer 双 DLQ 监听 + ModerationDlqService 转人审三件套；IMAGE 单图审无 review\_task 直接发 SUSPICIOUS(3) 事件由 file 置 safe\_flag=3，仅 ARTICLE\_IMG\_RE\_REVIEW 走转人审 CAS；hash 错配防线防旧死信污染）
- [x] 成本护栏：全站 QPS 上限（2026-10-10 3-7.1 完成：ModerationGlobalQpsInterceptor（interceptor 包）+ ModerationWebConfig 注册 + ModerationProperties.Qps（litisle.moderation.qps.* 外置）；Redis 降级由 RedisRateLimiter 内置 LocalRateLimiter 兑底）+ 入口 @RateLimiter(USER)（⏸ 随 3-5/content-file 联动）+ 单内容 SETNX 在途去重短锁（消费端已随 3-1.9 落地 ModerationProperties.Dedup，入口侧预检随 3-5）（dedup.lock-seconds）（§2.6）
- [x] 故障降级：延迟插件指数退避重试 + Redis 不可用直调模型仍受全站 QPS 护栏与本地兑底计数约束，超限保持待审（§4.2.4）（2026-10-10 完成：重试编排收口 common-mq MqAckHelper.executeWithRetry 统一模板；新建 x-delayed-message 专用延迟交换机复用原路由键零波及现有拓扑；ModerationModelQpsGuard 模型直调护栏复用 RedisRateLimiter 本地兑底、超限抛非预期异常走延迟重试绝不放行；图片缓存读补异常捕获按未命中处理对齐宁严勿松口径；retry.max-count=5 / retry.base-delay-ms=30000 外置 yml）




### 板块三：MQ 异步机审链路（落位：litisle-review 新建 com.litisle.review.moderation 包：consumer/ + cache/ + service/）

#### 3-1 ModerationTextConsumer（§4.2.1 ④⑤⑥）

**落位**：review 模块 consumer 包。依赖 litisle-api 既有：MqConstant、submit 事件体（modType/bizType/bizId/scene/contentHash/retryCount/traceId——不含正文）、ContentClient、MessageClient（私信域回查契约，Provider 由 litisle-social 实现）。

**步骤**：
1. 监听 moderation.text.submit 队列；@RabbitListener 配 prefetch=litisle.moderation.consumer.prefetch(10)，手动 ACK。
2. 按 (biz_type, biz_id, scene) 三元组定位 review_task——scene 必带（复审任务与文本任务同 biz_type 同 biz_id 并存，缺 scene 定位歧义），缺 scene 直接 ACK 丢弃 + error 日志。
3. 任务缺失→error 日志后 ACK（脏消息）；回查正文：bizType∈{1,2}→ContentClient、bizType=5→MessageClient；回查异常→抛异常不 ACK 进延迟重试。
4. **幂等分流**（任务 status≠0 时）：事件 contentHash 与任务一致→视为「已处理后的重投」，补发 result 事件后 ACK（业务方回写幂等，重放无副作用）；hash 不一致→旧消息错配新任务，直接 ACK 丢弃。status=0→CAS：`update ... set status=处理中 where id=? and status=0`，affected=0→ACK（已被并发消费处理）。
5. **缓存查重**：Redis key=`review:text-result:{wordlibVer}:{fewShotVer}:{scene}:{contentHash}`，命中→解析 JSON 取 verdict；miss→查 moderation_record 兜底（idx_hash：`where content_hash=? and scene=? order by id desc limit 1`），命中行必须同时满足：verdict∈{1-PASS,2-REJECT}（SUSPICIOUS 行不参与复用，命中则置任务 status=2 挂既有人工任务不重复建）、provider=当前值（防 onnx→aliyun 切换复用旧结论）、wordlib_ver=当前值（防词库热更后旧结论放行）、create_time 在 cache.ttl-hours(72)±10% 窗口内——任一不符视为未命中。
6. 命中缓存→跳过模型调用，**但仍追加一行 moderation_record**（reason=「命中缓存，原记录ID=X」，provider/wordlib_ver 取当前值）→直接跳步骤 9。
7. 未命中→抢 SETNX 在途短锁 `dedup:{provider}:{contentHash}`（TTL=dedup.lock-seconds=60s，JSON/简单值均可）；抢不到→不 ACK 延迟重试（原请求在途，防并发穿透烧 N 倍模型费）。
8. SPI 送审（LLM 部分留注释占位）：文本超 llm.max-text-chars(6000)→按空行段落分片；单片仍超→字符窗口硬切（步长 5500、overlap 500，窗口参数可外置）；**聚合规则**：任一片 REJECT→整文 REJECT；含 SUSPICIOUS 片→整文 SUSPICIOUS；全 PASS→PASS。
9. **三分态落库（同一本地事务）**：CAS 流转 review_task（PASS→1 / REJECT→4 / SUSPICIOUS→2）+ 回填 mod_verdict/layer2_risk + 追加 moderation_record（mod_type=2-LLM、provider、wordlib_ver、content_hash、scene、trace_id、cost_ms、category、reason 截断 500）。
10. 事务提交后发 result 事件（见 3-4），publisher confirm 成功→basicAck；任何一步失败抛异常不 ACK。

**注意事项**：
- 命中缓存只省模型调用不省留痕行——漏留痕=该篇发布无审核凭证，监管抽查「为何发布」答不出。
- LLM 输出 verdict/category 为不可信输出，白名单校验越界→SUSPICIOUS 宁疑不放（适配器已兜底，消费端勿重复信任其原样落库）。
- 分片**禁静默截断**——违规段落写在末尾躲过机审是明确攻击面；overlap 防违规词跨切点被拆开漏检。

#### 3-2 ModerationImageConsumer（§4.2.2 / §2.1）

**落位**：consumer 包。取图唯一入口=FileClient.getContent(fileId) 内部字节流端点（已落地，不过 safe_flag 闸口——申诉复核需重取隔离图）。

**步骤**：
1. 监听 moderation.image.submit（事件体：bizType=6 / bizId=file_meta.id / scene / contentHash）。scene=IMAGE→单图审；scene=ARTICLE_IMG_RE_REVIEW→按文章 bizId 经 ContentClient 反查 article_image 配图列表逐张送审（任务 content_hash=全部配图 sha256 排序拼接后再 sha256 的聚合指纹，聚合判定在本条步骤 5）。
2. **取图一律 FileClient.getContent 字节流**，byte[] 经 Jackson 自动 Base64 编解码——严禁按任何用户 URL 抓取（SSRF 防线，二期云 API 同样优先传字节流）。
3. 单图缓存查重 key=`review:img-result:{provider}:{modelVersion}:{sha256}`（版本化隔离：onnx→aliyun 切换、同 provider 模型换版均不复用旧结论）；miss→送 OnnxImageModerationAdapter（抛异常→延迟重试）；缓存 value 格式异常→降级送审。
4. 单图结论落 moderation_record（mod_type=3、content_hash=该图文件 sha256）；单图 REJECT 的 safe_flag=0+隔离由 file 侧消费 result 事件处理，review 侧只发事件。
5. ARTICLE_IMG_RE_REVIEW 全部图审完→聚合：全 PASS→PASS / 任一 REJECT→REJECT / 含 SUSPICIOUS→SUSPICIOUS，CAS 流转复审任务（biz_type=1+scene=ARTICLE_IMG_RE_REVIEW 的 review_task）。

**注意事项**：
- 图片场景（IMAGE/ARTICLE_IMG_RE_REVIEW）不走 DFA（DfaScenes 归一为 null），勿误调词库。
- 逐图送 SPI、不逐图另建 biz_type=6 任务（防任务爆炸）。
- Semaphore/超时已在适配器（板块一）落地，消费端只需异常→延迟重试编排。

#### 3-3 统一建任务 upsert（§4.2.2）

**落位**：review 模块 service 包 ReviewTaskUpsertService（发布侧建任务/举报转人工/消费端重置共用）。

**步骤**：
1. 按 uk_biz_scene(biz_type, biz_id, scene) upsert：无行→insert（status 按场景初始值、content_hash、retry_count=0）。
2. **命中行分流（★核心）**：
   - a. content_hash 变化（编辑重发）或原任务已终态（status∈{1,3,4}）→重置：旧任务未终态时先追加 record 标 superseded；然后 update 置目标态 + 清 assignee/decided_by/opinion + 更新 content_hash + retry_count 归零 + **清机审证据列**（mod_verdict=0、layer1_hit=0、hit_words=null、layer2_risk=0）。
   - b. hash 未变且非终态（status∈{0,2}，同内容重复举报/复审）→只复用不重置：保留 status/assignee，仅回填调用方关联列（如 report.review_task_id）。
3. **重置目标态按 scene 分列**：待机审场景（ARTICLE_FEATURED / ARTICLE_COMMUNITY / COMMENT / MESSAGE / **ARTICLE_IMG_RE_REVIEW**）→status=0（重跑机审）；举报复审场景（ARTICLE_RE_REVIEW / COMMENT_RE_REVIEW / MESSAGE_RE_REVIEW）→status=2（直进人审不重跑机审）。

**注意事项**：
- 不清 assignee→被重置任务的旧认领人无需重新认领即可裁决（decide CAS 以 assignee 判权）；不清证据列→人审看到张冠李戴的旧命中词/风险分级。
- ARTICLE_IMG_RE_REVIEW 虽带 _RE_REVIEW 后缀但属机审复审（逐图送 SPI），**不得并入「直进人审」一类**。
- 多轮举报收敛：decide 回写/反查举报一律带 `and status<3` 活跃态过滤，禁裸用 review_task_id 全量 UPDATE（1 行任务对多轮多份 report 是 1:N，会覆盖历史轮 result/abuse_flag）。
- USERNAME 同步拒绝**不建任务**（唯一例外），只落 moderation_record(biz_id=0)，防注册失败堆废任务。

#### 3-4 result 事件发布（§4.5.2）

**落位**：review 模块 mq/publisher 类；事件体类已备（litisle-api）：{bizType, bizId, scene, contentHash, wordlibVer, reviewTaskId, verdict, category, reason, traceId}——**不含正文/图片地址**（防大报文与敏感数据过 MQ）。

**步骤**：
1. 路由分发：bizType=5(MESSAGE)→moderation.message.result；文本 bizType∈{1,2}→moderation.text.result；图片 bizType=6→moderation.image.result。
2. contentHash 必须是**本次实际送审的指纹**（非编辑后新值）；wordlibVer 取判定时的词库版本快照——二者错填则 content 侧 last-hash 免审键落位错误，免审机制失效。
3. **事务顺序铁律**：本地事务提交→publisher confirm 发 result→basicAck；任何一步失败抛异常不 ACK 走重投（3-1 步骤 4 的「hash 一致补发 result」幂等闭环依赖此顺序，写反即断裂）。
4. 若 review_task 被 report.review_task_id 引用（举报触发的任务）：机审终审落库**同一本地事务内**回写 `update report set status=3, handler=0, result=... where review_task_id=? and status<3`（handler=0-系统）。

**注意事项**：
- bizType↔target_type 映射**禁恒等**：1↔1、2↔2、3↔3 同值；5(私信)↔4、6(图片)↔5 错位；4-作家申请无映射。建任务（target→biz）与反查举报（biz→target）两方向都必须走映射表——恒等实现使反查举报零行→举报永久卡 status=2、uk_content_active 不释放。
- reason 为 LLM 透传的不可信输出：落库/事件前截断 500 + 剥离 \r\n。

#### 3-6 ModerationDlqConsumer（§4.2.4）

**步骤**：
1. 监听 moderation.dlx 死信队列→解析事件→按 (biz_type,biz_id,scene) 定位 review_task。
2. **转人审三件套**（绝不自动通过）：CAS 置 review_task.status=2 + mod_verdict=3(SUSPICIOUS) + retry_count 回写；图片类 task 配套经 file 侧既有 safe_flag 消费链路置 safe_flag=3（存疑占位图）。

**注意事项**：与板块六 6-2 补偿重发（只扫 status=0）天然互斥；残留竞态窗口靠消费端 CAS 收敛。

#### 3-7 成本护栏（§2.6）

**步骤**：
1. 全站 QPS：仿 FileGlobalQpsInterceptor 做 ModerationGlobalQpsInterceptor，阈值 litisle.moderation.qps.max-count 外置，Redis 降级走 LocalRateLimiter 本地兜底计数（宁严勿松）。
2. 发布/上传入口 @RateLimiter(USER)（防单用户刷审核费）。
3. 单内容在途去重：同 provider+content_hash 送审前 SETNX 短锁（3-1 步骤 7 消费端已实现，入口侧预检可选复用同 key 口径）。

**注意事项**：缓存只挡「已完成」挡不住「在途」；短锁防击穿（同 key 并发穿透），雪崩（不同 key 集体过期）靠 TTL ±10% 抖动。

#### 3-8 故障降级（§4.2.4）

**步骤**：
1. 消费失败不 ACK→重投携带 x-delay 指数退避（retry.max-count=5、retry.base-delay-ms=30000；消息头 retryCount 递增并与 review_task.retry_count 对账）。
2. 耗尽（retryCount>max）→路由 moderation.dlx 落死信→3-6 消费转人审。
3. **Redis 不可用**：缓存查重跳过直调模型（只多花钱不漏审），但模型直调仍受全站 QPS 护栏+本地兜底计数约束（缓存全失效=调用量放大 N 倍）；超限→不 ACK 保持待审延迟重试，绝不 default-allow。

**注意事项**：各适配器调用级超时（llm/onnx api.timeout-ms）板块一已落地，本条只需消费端重试编排与护栏约束。

### 板块四：发布侧文本链路接入（契约层落地记录，2026-10-11，自 next_update.md 转移）

**落位**：litisle-api moderation 包 / event/content 包 / dto/content 包 + MqConstant/ModerationConstant 常量补齐；入口嵌入挂起部分见 next_update.md「暂不实现」节审核模块条目（auth 注册链路已嵌入，见下 A 组）。

- [x] 4-1 发布前置校验：PublishTextPrecheck.check/hasVisibleText/stripHtmlTags + SubmitTextAssembler.assemble/contentHash（SHA-256 小写十六进制）+ ErrorCode 新增 CONTENT\_EMPTY(30005)/TITLE\_TOO\_LONG(30006) + ModerationTextService#fetchText 分隔符收口 TEXT\_SUBMIT\_SEPARATOR（标题与正文拼接同口径防违规藏标题；空内容 content\_hash 恒定，校验必须在 hash 计算与送审之前）
- [x] 4-2 同步 DFA 闸门契约：DfaPublishGate.decide 三态决策（HIGH 拦截/MID 标记/未命中放行，入参判空 fail-fast）+ DfaFacade 兑底级别收口 DFA\_LEVEL\_HIGH；私信 HIGH 拒发不投递 MQ、USERNAME 同步拒绝即终态、MID 只标记不拦截、scene 传原始值由 DfaScenes 归一口径入 Javadoc
- [x] 4-3 免审 last-hash：LastHashStore（RedisAdapter 函数式注入零 redis 依赖 / write 终局 fail-fast 含 ttlSeconds / read 正则强校验异常按未命中 / remove 单键删除）+ LastHashExemptChecker（decide 五步三分支：PASS 免审直落并置 review\_task 终态 / REJECT 前置提示 / 键缺失照常发 MQ；statusExcluded 按 bizType 分列未定义口径 fail-fast，评论排除 2、文章排除 3/4 补 del\_flag=0；imgReReviewPassed 复用 LAST\_HASH\_KEY 连带校验：键存在+聚合 hash 一致+PASS 三者齐备）+ ModerationConstant 补 BIZ\_TYPE\_ARTICLE/COMMENT/MESSAGE 与 SCENE\_ARTICLE\_IMG\_RE\_REVIEW
- [x] 4-4 content.unpublished 同步失效：event/content 包 ContentUnpublishedEvent（@Data+Serializable，Schema 四公共字段+bizType/bizId/contentHash/reason）+ ContentUnpublishedInvalidator.invalidateAll 三步矩阵（last-hash/text-result/img-result 全删；RedisKeyDeleter 函数式注入零 redis 依赖；deleter 判空 fail-fast；失败语义=宿主 try/catch 仅 error 留痕不回滚下架事务，残留键 TTL 过期兑底）+ MqConstant 补 QUEUE/ROUTING\_CONTENT\_UNPUBLISHED（防「PASS→下架→删除→重发同 hash」命中旧 PASS 缓存绕过下架决定的历史攻击面）
- [x] 4-5 preview-check 契约：dto/content 包 ContentPreviewCheckDTO（scene/title/content 三字段）+ ContentPreviewCheckVO（仅 {hit,risk} 两字段，禁回吐命中词防词库 oracle 探测）
- [x] 4-6 私信撤回/陌生人配额契约：MessageRecallGate.recall（适配器/入参 fail-fast + CAS 结果判定，affected=0 统一失败禁区分原因）+ StrangerQuotaGate.tryAcquire（五步编排：双适配器 fail-fast→Redis INCR 为主→null 降级 DB 条件更新 a\_quota\_used<3→上限比对，漂移口径留档；REJECT 拦截仍占配额）+ ModerationConstant 补 MESSAGE\_STATUS\_*/STRANGER\_QUOTA\_*
- [x] 4-7 USERNAME 同步拒绝契约：UsernameRejectGate.recordReject（HIGH 命中 fail-fast 校验 + SubmitTextAssembler 全站口径派生 contentHash + RecordAdapter 取证落库；不建 review\_task/biz\_id=0 占位，取证行不落用户名明文；不可申诉=明示设计非遗漏，换名重试无改名路径防绕过）+ ModerationConstant 补 BIZ\_TYPE\_USERNAME=3（对齐 review 侧 ModerationBizTypeEnum.USERNAME(3)）

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