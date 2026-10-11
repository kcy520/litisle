
# 审核模块 · 进度看板（status）

> 用途：一眼看进度。**本文件只放勾选状态，不写步骤细节**。

> 详细步骤见同目录 `spec-*.md`；细案原件 `next_update.md`（只读，勿改）。

> 维护纪律：每条落地后回填 `[x]` 与日期。

## 当前执行锚点

- **进行中**：板块三代码落地收尾（3-7.1/3-8 全部完成；3-5 与 3-7.2/3-7.3 入口侧随 content/file 联动挂起）→ 下一步进入板块四

- **挂起项**：3-5 业务方回写矩阵消费端——litisle-content 为空壳（无 litisle-api/common-mq/web/mybatis 依赖）、litisle-message 模块不存在；触发条件=两模块正式开发时随实体层一并落地（2026-10-10 决策跳过转 3-6；完整步骤已移入 next_update.md「暂不实现」节）

- **前置阻塞**：litisle-ai chat 端点（`POST /api/feign/ai/chat`）未实现——3-1 联调前必须落地

- **推进顺序**：3-1→3-2→3-3→3-4→3-6→3-7→3-8 → 板块四 → 板块五 → 板块六 → 板块七（随三并行埋点；3-5 挂起待 content/message 落地）

## 现状基线（已完成，不再列入）

file 侧 safe_flag 四态闸口 / 拒传黑名单 / quarantine 契约 / result·appeal.result 消费回写 / getContent / 占位图 / 对账跳隔离前缀；litisle-api 契约层（MqConstant / ModerationConstant / 事件体 / FileClient）；review 侧人审域（claim·decide CAS、申诉防重三层、双人复核、举报撤回·诬告反制、词库表）。


## 板块三：MQ 异步机审链路（§4.2.1/§4.2.2/§4.5.2/§4.2.4）

- [x] 3-1 ModerationTextConsumer（细案 `spec-3-1.md`）
  - [x] 3-1.1 契约补齐：TextSubmitEvent / ContentClient / MessageClient + fallback 工厂注册 / MqConstant 补路由键
  - [x] 3-1.2 ReviewRabbitConfig：moderation 交换机/队列/绑定/DLX + x-delay 拓扑声明（与 file 侧参数对齐）
  - [x] 3-1.3 消费者骨架：监听 text.submit 队列 + prefetch + 手动 ACK + traceId 恢复
  - [x] 3-1.4 任务定位与脏消息分流：三元组定位；缺 scene / 任务缺失 / hash 错配 ACK 丢弃 + error 日志
  - [x] 3-1.5 正文回查：bizType∈{1,2}→ContentClient、5→MessageClient，fail-fast 不 ACK 延迟重试
  - [x] 3-1.6 幂等分流：status≠0 重投（hash 一致补发 result / 不一致丢弃）；status=0 CAS 认领
  - [x] 3-1.7 缓存查重 ModerationResultCache：Redis→record 兜底，verdict/provider/wordlib_ver/TTL 四条件校验
  - [x] 3-1.8 命中缓存仍追加留痕行（reason=命中缓存原记录ID，provider/wordlib_ver 取当前值）
  - [x] 3-1.9 在途短锁：SETNX dedup:{provider}:{contentHash}（60s），抢不到延迟重试
  - [x] 3-1.10 SPI 送审 + 超长分片聚合（LLM 留占位；REJECT>SUSPICIOUS>PASS，禁静默截断）
  - [x] 3-1.11 三分态落库同一事务：CAS 流转 review_task（1/4/2）+ 回填证据列 + 追加 moderation_record
  - [x] 3-1.12 事务提交后发 result 事件，publisher confirm 成功才 basicAck
  
- [x] 3-2 ModerationImageConsumer
  - [x] 3-2.1 监听 image.submit + 场景分流：IMAGE 单图 / ARTICLE_IMG_RE_REVIEW 反查配图列表
  - [x] 3-2.2 取图统一 FileClient.getContent 字节流（禁按用户 URL 抓取，SSRF 防线）
  - [x] 3-2.3 单图缓存查重（provider+modelVersion 版本化 key）+ ONNX 送审异常延迟重试
  - [x] 3-2.4 单图结论落 moderation_record（REJECT 的 safe_flag/隔离由 file 侧 result 消费处理）
  - [x] 3-2.5 配图复审聚合判定 + CAS 流转复审任务
  
- [x] 3-3 统一建任务 upsert（ReviewTaskUpsertService）（2026-10-10 完成：接口+impl，@Transactional 原子提交；superseded 留痕暂复用 MANUAL_REVIEW+SUSPICIOUS 承载，reason 固定前缀供过滤，后续如扩展 mod_type=7 需同步 Sql-Document.md）
  - [x] 3-3.1 uk_biz_scene upsert：无行 insert（初始 status / content_hash / retry_count=0）
  - [x] 3-3.2 命中行分流：hash 变化或已终态→重置（清 assignee/decided_by/证据列/retry 归零）；非终态→复用
  - [x] 3-3.3 重置目标态按 scene 分列：机审场景→0；人审复审 _RE_REVIEW→2；IMG_RE_REVIEW 属机审→0
- [x] 3-4 result 事件发布（2026-10-10 完成：ModerationResultPublisher（mq/publisher 包）路由分发/必达发送/举报回写/错位映射 + MqConstant 补 text/message/image 三路由键 + ModerationResultEvent 补 scene/contentHash/wordlibVer/category/reason 五字段；遗留集成点：既有三处内联发事件暂保留待统一切换、file 侧 QUEUE_FILE_MODERATION_RESULT 绑定须同步改 moderation.image.result）
  - [x] 3-4.1 路由分发：message / text / image 三 result 队列（resolveRoutingKey 按 bizType 分流；republish() 尽力而为补发供重投对账）
  - [x] 3-4.2 口径锁定：contentHash=本次送审指纹、wordlibVer=判定时快照（错填则免审失效）
  - [x] 3-4.3 事务顺序铁律：本地提交→confirm 发 result→basicAck（sendCritical 重试耗尽转 IllegalStateException 入 DLQ，防 MqAckHelper 误 ack）
  - [x] 3-4.4 举报关联任务同事务回写 report（status<3，handler=0-系统）
  - [x] 3-4.5 bizType↔target_type 映射表（禁恒等：5↔4、6↔5）
- [ ] 3-5 业务方回写矩阵消费端（content / message）（⏸ 挂起：content 空壳/message 模块不存在，触发条件=两模块正式开发，见执行锚点挂起项）
  - [ ] 3-5.1 content 侧消费 text/image result + bizType 分发 CAS（article/comment）
  - [ ] 3-5.2 文章双链路联合聚合：文本 PASS 且图片复审 PASS 才放行 1→2
  - [ ] 3-5.3 REJECT 分流：首发 1→3 / 复审下架 2→4；评论 CAS 0→1/0→2 禁 2→1
  - [ ] 3-5.4 空/纯图边界：纯图文本链路 trivially PASS；正文配图全空拒
  - [ ] 3-5.5 message 侧消费：REJECT/SUSPICIOUS→1→2 拦截；人审 PASS→1 恢复
- [x] 3-6 ModerationDlqConsumer（2026-10-10 完成：ModerationDlqConsumer（consumer 包，@RabbitListener 双 DLQ 监听 + traceId/ACK 模板）+ ModerationDlqService（service 包，locateTask/transitionToManualReview/verifyHashMatch）；口径修正：IMAGE 单图审不建 review_task→无任务可 CAS，直接发 SUSPICIOUS(3) result 事件由 file 置 safe_flag=3；仅 ARTICLE_IMG_RE_REVIEW 走转人审 CAS；新增 hash 错配防线防旧死信污染 upsert 重置后的新任务）
  - [x] 3-6.1 监听 DLX 死信队列 + 按 (biz_type,biz_id,scene) 定位 review_task（任务缺失/hash 不一致→BizException ack 丢弃，DLQ 无 DLX nack 即物理丢，靠 6-2 补偿兑底）
  - [x] 3-6.2 转人审三件套：status=2 + mod_verdict=3 + retry_count 回写（CAS where status=0，与 6-2 补偿重发天然互斥）；图片类 IMAGE 单图置 safe_flag=3（发 SUSPICIOUS 事件，发布失败不抛）、复审死信不发事件
- [ ] 3-7 成本护栏
  - [x] 3-7.1 全站 QPS 拦截器 ModerationGlobalQpsInterceptor（阈值外置 + Redis 降级本地兑底）（2026-10-10 完成：interceptor 包落地，阈值 litisle.moderation.qps.* 外置 yml，Redis 故障期 RedisRateLimiter 内部切 LocalRateLimiter 本地兜底计数，宁严勿松）
  - [ ] 3-7.2 发布/上传入口 @RateLimiter(USER)（⏸ 挂起：发布入口在 content（空壳）、上传入口在 file（随 file 联动），触发条件同 3-5）
  - [ ] 3-7.3 单内容在途 SETNX 短锁（复用 3-1.9 key 口径）（消费端已随 3-1.9 落地 ModerationProperties.Dedup；入口侧预检随 3-5 挂起）
- [x] 3-8 故障降级（2026-10-10 完成：重试编排收口 common-mq MqAckHelper.executeWithRetry 统一模板（DelayedRetryPublisher 回调，业务消费者禁止手写重投模板）；新建独立 x-delayed-message 专用延迟交换机绑定 text/image 主队列、复用原路由键落回，零波及双侧共享交换机防 PRECONDITION_FAILED）
  - [x] 3-8.1 消费失败 x-delay 指数退避重投（retryCount 与 review_task.retry_count 对账；retry.max-count=5、retry.base-delay-ms=30000 外置 application.yml 可运营调整）
  - [x] 3-8.2 重试耗尽路由 DLX → 3-6 转人审（未耗尽延迟重投成功 ack、重投失败/耗尽 nack 入 DLX；ModerationTextConsumer/ModerationImageConsumer 已切换重试感知 ACK 模板）
  - [x] 3-8.3 Redis 不可用降级：跳缓存直调模型，仍受 QPS 护栏约束，超限延迟重试绝不放行（新增 ModerationModelQpsGuard 复用 RedisRateLimiter 本地兜底计数、与 HTTP 全站护栏隔离计数；ModerationImageService 缓存读取补异常捕获按未命中处理，对齐文本侧宁严勿松口径）

## 板块四：发布侧文本链路接入（§4.2.1 / §4.5.3）

- [ ] 4-1 发布前置校验
  - [ ] 4-1.1 空内容/标题校验：标题 trim 非空 + 正文去标签可见文本非空 + 标题≤100 字符
  - [ ] 4-1.2 送审文本=标题+正文拼接（hash 与模型同口径，防违规藏标题）
- [ ] 4-2 同步 DFA 闸门嵌入
  - [ ] 4-2.1 文章/评论/私信/用户名四链路注入 DfaFacade：HIGH 拒 70001 / MID 标记放行
  - [ ] 4-2.2 私信 HIGH 同步拒发且不投递 MQ（仅 MID/LLM 先发后审）
  - [ ] 4-2.3 USERNAME 注册同步拒绝即终态（联动 4-7）
- [ ] 4-3 免审 last-hash
  - [ ] 4-3.1 content 消费 result 写 last-hash（TTL 与 cache.ttl 对齐 + ±10% 抖动）
  - [ ] 4-3.2 发布三分支判定：PASS 免审直落并置 review_task 终态 / REJECT 前置提示 / 键缺失照常发 MQ
  - [ ] 4-3.3 免审状态排除按 bizType 分列（文章排除 3/4、评论排除 2，补 del_flag=0）
  - [ ] 4-3.4 含配图文章连带校验：IMG_RE_REVIEW last-hash 存在 + 聚合 hash 一致 + PASS 三者齐备
- [ ] 4-4 content.unpublished 同步失效
  - [ ] 4-4.1 删除/下架/转私密三路径发 unpublished 事件并删 last-hash + text/img-result 缓存键
  - [ ] 4-4.2 免审直落排除条件补 del_flag=0
- [ ] 4-5 POST /api/content/preview-check
  - [ ] 4-5.1 端点实现：复用 DfaFacade 同 scene 同词库版本
  - [ ] 4-5.2 返回仅 {hit,risk} 禁回吐命中词 + 本人草稿鉴权 + @RateLimiter(USER)
- [ ] 4-6 私信撤回 CAS + 陌生人配额
  - [ ] 4-6.1 撤回 CAS：仅本人仅正常态 status=1→0，不物理删正文
  - [ ] 4-6.2 陌生人配额：Redis INCR 为主，失败降级 DB 原子条件更新 a_quota_used<3
- [ ] 4-7 USERNAME 同步拒绝
  - [ ] 4-7.1 DFA HIGH 同步拒绝不建任务，只落 moderation_record(biz_id=0) 取证
  - [ ] 4-7.2 明示不可申诉设计（换名重试，无改名路径防绕过）

## 板块五：申诉与处罚联动闭环（§4.2.3 / §4.5.2）

- [ ] 5-1 机审 REJECT 联动处罚
  - [ ] 5-1.1 REJECT 同一事务落 punish_log(action=3，operator_id=0) + violation_score 累计
  - [ ] 5-1.2 禁言/封禁阈值判定只重放 action∈{1,2}（action=3 仅落流水不计入）
- [ ] 5-2 moderation.punish.result 事件 + user/auth 消费
  - [ ] 5-2.1 处罚落库同事务发 punish.result 事件（体含 userId/action/op/punishLogId/frozenUntil/traceId）
  - [ ] 5-2.2 user 消费：经 Feign 拉权威态覆盖本地（punishLogId 禁作版本门控，防吞 op=2 撤销）
  - [ ] 5-2.3 封禁 action=2 联动 Sa-Token kickout 强制下线全会话
  - [ ] 5-2.4 发布链路禁言态拦截 + 网关不配 /api/review/punish/status 路由
- [ ] 5-3 punish.result 串行消费 + 双向对账 sweeper
  - [ ] 5-3.1 队列固定单消费者串行（扩容按 userId 一致性哈希）
  - [ ] 5-3.2 双向对账 sweeper：正向补漏 kickout / 反向补漏解封（挂 6-1 调度基建）
- [ ] 5-4 申诉改判 PASS
  - [ ] 5-4.1 改判 CAS：appeal status=0→2（affected=0→70004）
  - [ ] 5-4.2 同事务处罚撤销 + violation_score 重算（仅重放生效行）+ mod_type=6 双签留痕
  - [ ] 5-4.3 事务后发 appeal.result（必带 reviewTaskId 作 notification 幂等键）
  - [ ] 5-4.4 content/file/message allow-list CAS 恢复（article 3→2/4→2、comment 2→1、message 2→1、safe_flag 0→1）
  - [ ] 5-4.5 缓存层同步失效：删 text-result/img-result/last-hash key + 黑名单移除 sha256
  - [ ] 5-4.6 按内容身份回写同内容全部活跃举报（biz→target 映射禁恒等，status<3）
- [ ] 5-5 图片申诉对称恢复
  - [ ] 5-5.1 appeal.result(bizType=6)：safe_flag 0→1 + FileClient.unquarantine 移回原前缀
  - [ ] 5-5.2 同步删 img-result 缓存 key + 黑名单移除该 sha256

## 板块六：Sweeper 基建 + quarantine 清理（§4.3 / §4.2.2）

- [ ] 6-1 ReviewTaskSweeper
  - [ ] 6-1.1 认领超时回池：status=2 超 claim.timeout-minutes→CAS 置回 0 清 assignee
  - [ ] 6-1.2 申诉超时自动维持：7 天未裁决置 1 + record(mod_type=6) + 通知作者
  - [ ] 6-1.3 积压 Gauge 三态全量刷新（pending/claimed/suspicious）+ DLQ 深度
- [ ] 6-2 status=0 待审超时补偿重发
  - [ ] 6-2.1 扫 status=0 超时任务（阈值必须 > MQ 退避总时长防系统性双投）
  - [ ] 6-2.2 重投前抢 dedup SETNX 短锁 + retryCount 保持原值不归零
- [ ] 6-3 quarantine sweeper
  - [ ] 6-3.1 扫描源=moderation_record（biz_type=6 且 REJECT 超 retention-days）+ 待裁决申诉豁免顺延
  - [ ] 6-3.2 FileClient.deleteQuarantined：path 前缀校验→删本体+缩略图→del_flag=1（回收站兜底 7 天）
  - [ ] 6-3.3 限速/天级分频/单条失败下轮兜住 + quarantine 积压 Gauge；落地后勾「暂不实现」节条目
- [ ] 6-4 moderation_record 归档占位
  - [ ] 6-4.1 落配置 retention-months=6 + 归档任务占位（二期：按 id 区间幂等迁移，删前跳过被申诉/处罚引用记录）

## 板块七：观测与告警收尾（§4.8 / §4.7）

- [ ] 7-1 指标埋点（均 ObjectProvider optional 模式）
  - [ ] 7-1.1 moderation_total{verdict} Counter（判定落库处）+ moderation_cost Timer（SPI 包裹）
  - [ ] 7-1.2 moderation_backlog{status} / moderation_dlq_backlog Gauge（6-1 任务内定时刷新）
  - [ ] 7-1.3 moderation_punish_total{action} / moderation_cache_hit{layer} Counter
- [ ] 7-2 traceId 全链路贯穿
  - [ ] 7-2.1 网关→发布→submit 事件→MQ 头→消费→record 落库贯穿；与 provider_trace_id 分列；落日志前剥离 \r\n
- [ ] 7-3 Alertmanager 六规则
  - [ ] 7-3.1 六规则阈值外置落 Nacos（存疑积压/在办超时/DLQ 堆积/机审失败率/通过率跌幅/缓存命中跌落）
- [ ] 7-4 §4.7 剩余配置项收尾
  - [ ] 7-4.1 litisle-shared.yaml 共享项：last-hash.ttl-hours / cache.ttl-hours / ttl-jitter-ratio / reject-blacklist.ttl-days
  - [ ] 7-4.2 review 服务项：retry / qps / consumer.prefetch / appeal / report / claim / sweeper / dedup / llm / model-version / alert.*（既有不生效配置保留加注释）

## 暂不实现（预留项）

- [x] safe_flag 审核链路（2026-10-02 接入完成）

- [ ] CDN 接入——public-url-base 已分离，届时仅改配置

- [ ] common-redis 库模块不引 Sentinel（库模块当前不动）

- [ ] review 侧 quarantine sweeper——触发条件 = 板块六达成

- [ ] litisle-ai chat 端点——触发条件 = 板块三 3-1 联调前

## 审核模块暂不做（触发条件随附）

- 云 API 生产适配器（生产切换时）

- 图文组合审核 / OCR 多模态盲区（二期，人审详情页图文并排兜底）

- 撤销改判公示（二期，仅脱敏摘要）

- 视频 / 音频审核（一期显式不支持）
