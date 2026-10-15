
### 板块三：MQ 异步机审链路（§4.2.1/§4.2.2/§4.5.2/§4.2.4）

- [x] ModerationTextConsumer：幂等（bizType+bizId+scene 定位 + CAS status=0 + 按 content\_hash 分流补发/丢弃）+ 缓存查重（Redis→idx\_hash 表兜底，provider/wordlib\_ver/TTL 窗口比对；命中缓存仍补留痕行）+ 超长分片（段落分片→字符窗口 overlap 硬切，禁静默截断）（§4.2.1 ④⑤⑥）
- [x] ModerationImageConsumer：取图一律走 FileClient 内部字节流（严禁按用户 URL 抓取）+ 图片缓存 key 含 provider+modelVersion（§4.2.2/§2.1）（2026-10-09 完成：ModerationImageConsumer 消费编排 + ModerationImageService 业务编排，含单图审/复审两支线、聚合判定、CAS 流转、缓存读脏降级送审、TTL±10% 抖动、afterCommit 写缓存发事件）
- [x] 统一建任务 upsert：uk\_biz\_scene 复用同行 + 重置例外/目标态按场景分列 + 清机审证据列 + 多轮举报回写/反查一律 status<3 收敛（§4.2.2）（2026-10-10 完成：ReviewTaskUpsertService + Impl）
- [x] result 事件发布：事务顺序「本地事务提交→publisher confirm→ACK」；事件体必带 scene/contentHash/wordlibVer/reviewTaskId；bizType↔target\_type 映射表禁恒等（5↔4、6↔5 错位）（§4.5.2）（2026-10-10 完成：ModerationResultPublisher 统一发布器 + MqConstant 补三路由键 + ModerationResultEvent 补五字段；遗留：既有内联发事件切换 + file 侧绑定联动改造）
- [ ] 业务方回写矩阵消费端（content/social）：文章双链路联合聚合（含配图须图片复审 PASS；无图仅文本 PASS；纯图/空边界）+ 评论 CAS 带 del_flag=0 + 私信 1→2 CAS（§4.5.2）（⏸ 已移入「暂不实现」节含完整步骤：litisle-content 为空壳、social 未开发（私信归属 social，无独立 message 模块），触发条件=两模块正式开发时随实体层落地；2026-10-10 决策先转 3-6）
- [x] ModerationDlqConsumer：重试耗尽转人审兑底（review\_task.status=2 / safe\_flag=3 / mod\_verdict=3）（§4.2.4）（2026-10-10 完成：ModerationDlqConsumer 双 DLQ 监听 + ModerationDlqService 转人审三件套；IMAGE 单图审无 review\_task 直接发 SUSPICIOUS(3) 事件由 file 置 safe\_flag=3，仅 ARTICLE\_IMG\_RE\_REVIEW 走转人审 CAS；hash 错配防线防旧死信污染）
- [x] 成本护栏：全站 QPS 上限（2026-10-10 3-7.1 完成：ModerationGlobalQpsInterceptor（interceptor 包）+ ModerationWebConfig 注册 + ModerationProperties.Qps（litisle.moderation.qps.* 外置）；Redis 降级由 RedisRateLimiter 内置 LocalRateLimiter 兑底）+ 入口 @RateLimiter(USER)（⏸ 随 3-5/content-file 联动）+ 单内容 SETNX 在途去重短锁（消费端已随 3-1.9 落地 ModerationProperties.Dedup，入口侧预检随 3-5）（dedup.lock-seconds）（§2.6）
- [x] 故障降级：延迟插件指数退避重试 + Redis 不可用直调模型仍受全站 QPS 护栏与本地兑底计数约束，超限保持待审（§4.2.4）（2026-10-10 完成：重试编排收口 common-mq MqAckHelper.executeWithRetry 统一模板；新建 x-delayed-message 专用延迟交换机复用原路由键零波及现有拓扑；ModerationModelQpsGuard 模型直调护栏复用 RedisRateLimiter 本地兑底、超限抛非预期异常走延迟重试绝不放行；图片缓存读补异常捕获按未命中处理对齐宁严勿松口径；retry.max-count=5 / retry.base-delay-ms=30000 外置 yml）




#### status.md 子项级进度勾选（2026-10-13 自 status.md 转移，去重后补录：父项与上方清单重复不迁，仅迁子项；挂起子项 3-7.2/3-7.3 在 Temporarily 文档跟踪不迁）

- [x] 3-1.1 契约补齐：TextSubmitEvent / ContentClient / MessageClient + fallback 工厂注册 / MqConstant 补路由键
- [x] 3-1.2 ReviewRabbitConfig：moderation 交换机/队列/绑定/DLX + x-delay 拓扑声明（与 file 侧参数对齐）
- [x] 3-1.3 消费者骨架：监听 text.submit 队列 + prefetch + 手动 ACK + traceId 恢复
- [x] 3-1.4 任务定位与脏消息分流：三元组定位；缺 scene / 任务缺失 / hash 错配 ACK 丢弃 + error 日志
- [x] 3-1.5 正文回查：bizType∈{1,2}→ContentClient、5→MessageClient，fail-fast 不 ACK 延迟重试
- [x] 3-1.6 幂等分流：status≠0 重投（hash 一致补发 result / 不一致丢弃）；status=0 CAS 认领
- [x] 3-1.7 缓存查重 ModerationResultCache：Redis→record 兑底，verdict/provider/wordlib_ver/TTL 四条件校验
- [x] 3-1.8 命中缓存仍追加留痕行（reason=命中缓存原记录ID，provider/wordlib_ver 取当前值）
- [x] 3-1.9 在途短锁：SETNX dedup:{provider}:{contentHash}（60s），抢不到延迟重试
- [x] 3-1.10 SPI 送审 + 超长分片聚合（LLM 留占位；REJECT>SUSPICIOUS>PASS，禁静默截断）
- [x] 3-1.11 三分态落库同一事务：CAS 流转 review_task（1/4/2）+ 回填证据列 + 追加 moderation_record
- [x] 3-1.12 事务提交后发 result 事件，publisher confirm 成功才 basicAck
- [x] 3-2.1 监听 image.submit + 场景分流：IMAGE 单图 / ARTICLE_IMG_RE_REVIEW 反查配图列表
- [x] 3-2.2 取图统一 FileClient.getContent 字节流（禁按用户 URL 抓取，SSRF 防线）
- [x] 3-2.3 单图缓存查重（provider+modelVersion 版本化 key）+ ONNX 送审异常延迟重试
- [x] 3-2.4 单图结论落 moderation_record（REJECT 的 safe_flag/隔离由 file 侧 result 消费处理）
- [x] 3-2.5 配图复审聚合判定 + CAS 流转复审任务
- [x] 3-3.1 uk_biz_scene upsert：无行 insert（初始 status / content_hash / retry_count=0）
- [x] 3-3.2 命中行分流：hash 变化或已终态→重置（清 assignee/decided_by/证据列/retry 归零）；非终态→复用
- [x] 3-3.3 重置目标态按 scene 分列：机审场景→0；人审复审 _RE_REVIEW→2；IMG_RE_REVIEW 属机审→0
- [x] 3-3 口径注记（2026-10-13 自 status.md 迁移）：superseded 留痕暂复用 MANUAL_REVIEW+SUSPICIOUS 承载，reason 固定前缀供过滤，后续如扩展 mod_type=7 需同步 Sql-Document.md
- [x] 3-4.1 路由分发：message / text / image 三 result 队列（resolveRoutingKey 按 bizType 分流；republish() 尽力而为补发供重投对账）
- [x] 3-4.2 口径锁定：contentHash=本次送审指纹、wordlibVer=判定时快照（错填则免审失效）
- [x] 3-4.3 事务顺序铁律：本地提交→confirm 发 result→basicAck（sendCritical 重试耗尽转 IllegalStateException 入 DLQ，防 MqAckHelper 误 ack）
- [x] 3-4.4 举报关联任务同事务回写 report（status<3，handler=0-系统）
- [x] 3-4.5 bizType↔target_type 映射表（禁恒等：5↔4、6↔5）
- [x] 3-6.1 监听 DLX 死信队列 + 按 (biz_type,biz_id,scene) 定位 review_task（任务缺失/hash 不一致→BizException ack 丢弃，DLQ 无 DLX nack 即物理丢，靠 6-2 补偿兑底）
- [x] 3-6.2 转人审三件套：status=2 + mod_verdict=3 + retry_count 回写（CAS where status=0，与 6-2 补偿重发天然互斥）；图片类 IMAGE 单图置 safe_flag=3（发 SUSPICIOUS 事件，发布失败不抛）、复审死信不发事件
- [x] 3-7.1 全站 QPS 拦截器 ModerationGlobalQpsInterceptor（阈值外置 + Redis 降级本地兑底）（2026-10-10 完成：interceptor 包落地，阈值 litisle.moderation.qps.* 外置 yml，Redis 故障期 RedisRateLimiter 内部切 LocalRateLimiter 本地兑底计数，宁严勿松）
- [x] 3-8.1 消费失败 x-delay 指数退避重投（retryCount 与 review_task.retry_count 对账；retry.max-count=5、retry.base-delay-ms=30000 外置 application.yml 可运营调整）
- [x] 3-8.2 重试耗尽路由 DLX → 3-6 转人审（未耗尽延迟重投成功 ack、重投失败/耗尽 nack 入 DLX；ModerationTextConsumer/ModerationImageConsumer 已切换重试感知 ACK 模板）
- [x] 3-8.3 Redis 不可用降级：跳缓存直调模型，仍受 QPS 护栏约束，超限延迟重试绝不放行（新增 ModerationModelQpsGuard 复用 RedisRateLimiter 本地兑底计数、与 HTTP 全站护栏隔离计数；ModerationImageService 缓存读取补异常捕获按未命中处理，对齐文本侧宁严勿松口径）

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

### 板块六：Sweeper 基建 + quarantine 清理（§4.3 / §4.2.2，2026-10-12 全量完成，自 next_update.md 转移；即原「暂不实现」quarantine sweeper 条目，触发条件本板块达成）

> 2026-10-12 落地注记：板块六四项全量完成——ReviewTaskSweeper 四职责合并（6-1 认领超时回池/申诉超时维持/积压 Gauge 三态+DLQ 深度 + 6-2 status=0 超时补偿重发，同任务内分频执行）；QuarantineSweeper（6-3 扫描源=moderation_record+最新态过滤+申诉豁免+file 侧 deleteQuarantined 前缀校验契约+限速天级+双标签 Gauge）；ModerationRecordArchiveSweeper（6-4 原计划占位、实际全量实现：moderation_record_archive 归档表 DDL + 原子迁档事务 + 引用保护 + backlog Gauge，archive-enabled 默认 false 待显式启用）；明细见 status.md 板块六。

- [x] ReviewTaskSweeper：认领超时自动回池（claim.timeout-minutes）+ 申诉超时自动维持（appeal.timeout-days，宁严勿松）+ 积压 Gauge 定时刷新（§4.3/§4.2.3）（✅ 2026-10-12）
- [x] status=0 待审超时补偿重发：阈值 > MQ 最大退避总时长 + 重发前先抢 dedup.lock + 沿用 retry\_count 不归零（§4.3）（✅ 2026-10-12）
- [x] quarantine sweeper：扫 record(biz\_type=6) verdict=REJECT 超 retention-days → 反查 appeal(status=0) 待裁决则顺延 → FileClient.deleteQuarantined（file 侧前缀校验 + 删缩略图 + del\_flag=1 + 回收站兑底）→ fail-fast 幂等 + 批量限速 + 天级频率（§4.2.2）（✅ 2026-10-12）
- [x] moderation\_record 归档任务：retention-months=6 配置先行；归档 sweeper 已按契约全量实现（原计划二期，提前落地），archive-enabled=false 默认关闭（§4.4.1）（✅ 2026-10-12）

#### status.md 子项级进度勾选（2026-10-13 自 status.md 转移，去重后补录：父项与上方清单重复不迁，仅迁子项；板块六子项全部已实现无挂起）

- [x] 6-1.1 认领超时回池：status=2 超 claim.timeout-minutes→CAS 置回 0 清 assignee
- [x] 6-1.2 申诉超时自动维持：7 天未裁决置 1 + record(mod_type=6) + 通知作者
- [x] 6-1.3 积压 Gauge 三态全量刷新（pending/claimed/suspicious）+ DLQ 深度
- [x] 6-2.1 扫 status=0 超时任务（阈值必须 > MQ 退避总时长防系统性双投）
- [x] 6-2.2 重投前抢 dedup SETNX 短锁 + retryCount 保持原值不归零
- [x] 6-3.1 扫描源=moderation_record（biz_type=6 且 REJECT 超 retention-days）+ 待裁决申诉豁免顺延
- [x] 6-3.2 FileClient.deleteQuarantined：path 前缀校验→删本体+缩略图→del_flag=1（回收站兑底 7 天）
- [x] 6-3.3 限速/天级分频/单条失败下轮兑住 + quarantine 积压 Gauge；落地后勾「暂不实现」节条目
- [x] 6-4.1 落配置 retention-months=6 + 归档任务占位（二期：按 id 区间幂等迁移，删前跳过被申诉/处罚引用记录）（✅ 含归档表 DDL/实体/Mapper/原子迁档事务全量实现）

### 板块六实施细案步骤（2026-10-13 自 next_update.md 转移）

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
3. 执行：FileClient.deleteQuarantined(fileId)（**review 无存储写凭证**，s3.json 三身份拆分纪律）→file 侧校验 path 确在 quarantine/ 前缀内→删本体+_thumb_* 缩略图→del_flag=1→SeaweedFS 回收站兑底 7 天。
4. 纪律：fail-fast 单条失败留 error 日志由下轮兜住（文件已删则前缀校验/不存在跳过，天然幂等）；批量限速（每秒 N 条可配）；天级频率（任务内部分频，60s 粒度无意义）。
5. 观测：quarantine 积压 Gauge(biz_type 标签)+清理 Counter；**落地后回填「暂不实现」节 quarantine sweeper 条目勾选**。（✅ 2026-10-12 落地：QuarantineSweeper 落位 sweeper 包，原文步骤 5 的「暂不实现」条目已回填勾选）

**注意事项**：file 侧 FileReconcileTask 已对 quarantine/ 前缀跳过自身 7 天清理（已落地），本任务是 quarantine/ 前缀唯一删除责任方，不得重复造删除入口。

#### 6-4 moderation_record 归档占位（§4.4.1）

**步骤**：仅落配置 litisle.moderation.record.retention-months=6（《网络安全法》≥6 月合规下限），归档任务二期：按 create_time 迁只读历史存储后删热表行；删前跳过被 appeal.status=0 或 punish_log.status=1 引用的记录；按 id 区间幂等可重跑；应用账号仅授 INSERT/SELECT。（✅ 2026-10-12 落地：原计划二期，实际全量提前落地，archive-enabled=false 默认关闭，明细见上方清单落地注记）

### 板块七：观测与告警收尾（§4.8/§4.7，2026-10-12 全量完成，自 next_update.md 转移）

- [x] 指标：moderation\_total{verdict} + moderation\_cost Timer + moderation\_backlog{status=pending|claimed|suspicious 三态} + moderation\_dlq\_backlog + moderation\_punish\_total{action} + moderation\_cache\_hit{layer} + wordlib\_misreport\_total{scene}（仅 scene 标签防基数爆炸）；均 ObjectProvider optional 模式（✅ 2026-10-12）
- [x] traceId 全链路贯穿：网关→发布→MQ 消息头→消费端→moderation\_record.trace\_id（✅ 2026-10-12）
- [x] Alertmanager 六规则：存疑积压/在办超时/DLQ 堆积/机审失败率/通过率骤变/缓存命中率跌落；阈值全部外置（§4.7）（✅ 2026-10-12）
- [x] §4.7 剩余配置项收尾：retry.*、cache.ttl-hours+jitter-ratio、reject-blacklist.ttl-days、last-hash.ttl-hours（落 litisle-shared.yaml）、qps.max-count、consumer.prefetch、appeal.window-days、report.abuse-threshold、claim.*、sweeper.\*、dedup.lock-seconds、llm.max-text-chars、image.model-version、few-shot-version（✅ 2026-10-12）

#### status.md 子项级进度勾选（2026-10-13 自 status.md 转移，去重后补录：父项与上方清单重复不迁，仅迁子项；板块七子项全部已实现无挂起）

- [x] 7-1.1 moderation_total{verdict} Counter（判定落库处）+ moderation_cost Timer（SPI 包裹）
- [x] 7-1.2 moderation_backlog{status} / moderation_dlq_backlog Gauge（6-1 任务内定时刷新，随板块六 6-1 落地）
- [x] 7-1.3 moderation_punish_total{action} / moderation_cache_hit{layer} Counter
- [x] 7-2.1 网关→发布→submit 事件→MQ 头→消费→record 落库贯穿；与 provider_trace_id 分列；落日志前剥离 \r\n（✅ 基础设施全链路已通：网关/服务 Filter、Feign 透传、MQ Header 注入/恢复、record 落库均既有；本次新增 common-core TraceIdSanitizer 收口 CRLF 净化，网关/服务两 Filter 生成口径收敛 SSOT、MqTraceSupport 接线净化；既有发布点已按 setTraceId 惯例写入事件体，content 模块发布端随模块建设落地）
- [x] 7-3.1 六规则阈值外置落 Nacos（存疑积压/在办超时/DLQ 堆积/机审失败率/通过率跌幅/缓存命中跌落）（✅ 2026-10-12：阈值指标化方案——新增 ModerationProperties.Alert 五阈值组 + AlertThresholdMetrics 暴露 moderation_alert_threshold{rule} 阈值 Gauge（含复用 claim-timeout-minutes 共 6 个标签值）；alert-rules.yml 新增 litisle-moderation 规则组，表达式用 on(application) 引用阈值指标零写死数字；在办超时经新增 moderation_claim_timeout_repool_total 回池计数器表达；阈值 yml 值随 7-4.2 落 Nacos）
- [x] 7-4.1 litisle-shared.yaml 共享项：last-hash.ttl-hours / cache.ttl-hours / ttl-jitter-ratio / reject-blacklist.ttl-days（✅ 2026-10-12：四项已落 docs/nacos-yaml.md 共享模板 litisle.moderation 段，含归共享原因注释；Nacos 实际导入按模板执行；review 侧 cache/reject-blacklist 本地值已标注「共享默认值+环境覆盖」双源口径；last-hash 写入点在 content 服务随模块建设消费）
- [x] 7-4.2 review 服务项：retry / qps / consumer.prefetch / appeal / report / claim / sweeper / dedup / llm / model-version / alert.*（既有不生效配置保留加注释）（✅ 2026-10-12：绑定收口——ModerationProperties 新增 RejectBlacklist/Claim/Report 三组完成全量绑定；appeal.timeout-days 与 claim.timeout-minutes 口径分别收敛至 sweeper 组（永不生效保留备查）；消费点接线归属：认领配额/诬告反制随板块五 claimTask/decideTask，黑名单 TTL 写入随图片拒绝链路）

### 板块七实施细案步骤（2026-10-13 自 next_update.md 转移）

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

**落地注记（2026-10-12，7-1 已完成）**：新建 review 侧统一埋点组件 `ModerationMetrics`（对齐 WordlibMetrics 的 ObjectProvider optional 模式，未接 actuator 静默跳过）；moderation_total 在文本 `persistAndPublish` 判定映射后/图片单图 `persistSingleImage` 前/复审聚合判定后收口计数（缓存命中与真实送审两路均覆盖，非逐图重复计数）；moderation_cost 在文本分片送审与图片逐张 SPI 真实送审后按 result.costMs 预量测记录（缓存命中不计入）；moderation_cache_hit{layer=result} 在文本/单图/复审三处缓存命中分支计数；moderation_punish_total{action} 在 `RejectPunishAdapterHost` punish_log 落行成功后同源收口（全链路处罚落库无分支重复埋点）；moderation_cache_hit{layer=blacklist} 在 file 侧 `FileUploadServiceImpl.checkRejectBlacklist` 命中分支内联注册（跨模块不可复用 review 组件，指标名/标签口径与 review 侧一致）；标签均为枚举名小写派生（verdict/layer/action），无基数爆炸风险；moderation_backlog/moderation_dlq_backlog 两个 Gauge 已随板块六 6-1 落地。

**注意事项**：标签只允许 verdict/status/action/layer/scene 等限定枚举集合，**禁按词/内容 ID/用户打标签**（基数爆炸拖垮 Prometheus）。

#### 7-2 traceId 全链路贯穿

网关过滤器生成/透传→发布接口写入 submit 事件 traceId 字段→MQ 消息头→消费端解析→moderation_record.trace_id 落库；与厂商凭证 provider_trace_id **分列**（前者排内部链路、后者对外出证）；落日志前剥离 \r\n。

**落地注记（2026-10-12，7-2 已完成）**：新建 common-core `TraceIdSanitizer`（sanitize 逐字符剥离 \r\n 及全部 C0 控制字符与 DEL(\u007F) + 64 字符硬截断；sanitizeOrGenerate 净化后空值兑底生成 32 位无连字符 UUID）。三个 traceId 写入点全部接线：网关 TraceIdFilter 与 common-security TraceIdFilter 统一改走 sanitizeOrGenerate（本地 UUID 生成口径收敛 SSOT），common-mq MqTraceSupport.restore 写 MDC 前过 sanitize（保持缺失静默跳过既有口径）。既有发布点（file 侧 ImageSubmitEvent、review 侧补偿重投/申诉改判）已按 `evt.setTraceId(MDC.get("traceId"))` 惯例写入事件体；content 模块发布端接线随模块建设落地。

#### 7-3 Alertmanager 六规则（阈值全部外置 §4.7 alert.*）

①存疑积压>suspicious-backlog-threshold(50)；②在办超时=claimed 超 claim.timeout-minutes 未裁决；③DLQ 堆积>dlq-backlog-threshold(100)；④机审失败率>moderation-fail-rate(0.2)；⑤通过率相对基线跌幅>pass-rate-drop-ratio(0.3)；⑥缓存命中率跌落>cache-hit-drop-ratio(0.5)。括号内为建议初值，均落 Nacos。

**落地注记（2026-10-12，7-3 已完成，阈值指标化方案）**：Prometheus 规则文件无法直读 Nacos，故采用社区标准做法——①`ModerationProperties` 新增 `Alert` 嵌套组（五阈值，在办超时复用既有 sweeper.claimTimeoutMinutes 不另立配置防口径漂移）；②新建 `AlertThresholdMetrics`（moderation/config 包）@PostConstruct 惰性注册阈值 Gauge `moderation_alert_threshold{rule=...}`（suspicious-backlog/claim-timeout-minutes/dlq-backlog/moderation-fail-rate/pass-rate-drop/cache-hit-drop 六个固定标签值防基数爆炸，Gauge 绑定 properties getter，Nacos 重绑后抓取即时生效）；③`alert-rules.yml` 新增 litisle-moderation 规则组六条，表达式 `on(application)` 引用阈值指标零写死数字，④⑤⑥分母为零时结果 NaN 不触发（天然防初启动误报）；④`ReviewTaskSweeper` 补回池 Counter `moderation_claim_timeout_repool_total`（批量按轮 increment(reclaimed)），规则②用 `increase(...[15m]) > 0` 表达持续出现认领超时——claim.timeout-minutes 变更在 Nacos 改即间接改变告警触发点；⑤alert.* 五项阈值 yml 值随 7-4.2 落 Nacos，alertmanager.yml dev-null 接收器维持现状（通知渠道后续接）。

#### 7-4 §4.7 剩余配置项收尾（落位分两类）

- **litisle-shared.yaml（跨服务共享）**：litisle.moderation.last-hash.ttl-hours=72（content 写/review 声明，须与 cache.ttl-hours 对齐防口径漂移）、cache.ttl-hours=72、cache.ttl-jitter-ratio=0.1、reject-blacklist.ttl-days=30。
- **review 服务 yml/Nacos**：retry.max-count=5 / retry.base-delay-ms=30000、qps.max-count、consumer.prefetch=10、appeal.window-days=7 / appeal.timeout-days=7、report.abuse-threshold=3、claim.timeout-minutes=30 / claim.max-active-per-reviewer=20、sweeper.interval-seconds=60 / sweeper.stuck-resubmit-minutes=30、dedup.lock-seconds=60、llm.max-text-chars=6000、image.model-version、few-shot-version、alert.* 五项。

**注意事项**：既有暂不生效的 yml 配置保留并加注释说明（用户规则，不删）。

**落地注记（2026-10-12，7-4 已完成）**：①7-4.1 四项已落 docs/nacos-yaml.md 的 litisle-shared.yaml 模板（litisle.moderation 段，含归共享原因注释：last-hash 双侧读同一 TTL 防漂移、cache 与 last-hash 严格对齐），Nacos 实际导入按模板执行；②7-4.2 绑定收口：盘点发现 retry/qps/prefetch/sweeper/dedup/llm/model-version/alert.* 八组早已绑定生效，真实缺口为 reject-blacklist/claim/report 无绑定类——本次在 ModerationProperties 新增 RejectBlacklist/Claim/Report 三组完成全量绑定（yml 默认值不变）；③口径收敛决策：appeal.timeout-days 与 claim.timeout-minutes 分别与 sweeper.appeal-timeout-days / sweeper.claim-timeout-minutes 重复，统一收敛读 sweeper 组（yml 两项标注永不生效保留备查，防双源漂移）；④消费点接线归属：claim.max-active-per-reviewer（认领配额校验）与 report.abuse-threshold（诬告反制）随板块五 claimTask/decideTask 实现接线，reject-blacklist.ttl-days 写入点（REJECT 落黑名单 TTL）随图片拒绝链路接线，last-hash.ttl-hours 写入在 content 服务随模块建设消费；⑤review application.yml 的 cache/reject-blacklist 本地值标注「共享默认值+环境特定覆盖」双源语义。