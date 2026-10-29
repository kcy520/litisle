> **拆分归档**：本文件拆分自同目录索引页 [review-implemention-records.md](review-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。

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

