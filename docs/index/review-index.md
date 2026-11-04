## 枚举（值→含义）
ReviewSceneEnum: ARTICLE_FEATURED/ARTICLE_COMMUNITY/ARTICLE_RE_REVIEW/COMMENT/COMMENT_RE_REVIEW/USERNAME/MESSAGE/MESSAGE_RE_REVIEW/IMAGE/ARTICLE_IMG_RE_REVIEW（code==枚举名）
ModerationBizTypeEnum: ARTICLE(1) COMMENT(2) USERNAME(3) AUTHOR_APPLY(4) MESSAGE(5) FILE(6)  // 6 时 biz_id=file_meta.id
ModTypeEnum: DFA(1) LLM(2) LOCAL_IMAGE_MODEL(3) CLOUD_API(4) MANUAL_REVIEW(5) APPEAL_REJUDGE(6)
VerdictEnum: NONE(0) PASS(1) REJECT(2) SUSPICIOUS(3)
ReviewTaskStatusEnum: PENDING(0) AUTO_PASS(1) MANUAL_PENDING(2) PASS(3) REJECT(4)
Layer2RiskEnum: NOT_SUBMITTED(0) LOW(1) MID(2) HIGH(3)
ModerationProviderEnum: ONNX("onnx") ALIYUN("aliyun") LLM("llm")

## SPI（moderation/spi，适配器按 text-provider/image-provider 条件装配，启动自检 fail-fast）
ContentModerationService#moderateText(String 单片文本):ModerationResult   // 防注入包裹+强制JSON，解析失败按SUSPICIOUS
ContentModerationService#moderateImage(byte[]):ModerationResult           // 取图唯一入口=FileClient内部契约，禁用户URL
ModerationResult(record): verdict(VerdictEnum三分态) category(ModerationCategoryEnum) reason() providerTraceId() costMs()
适配器：llm→LlmTextModerationAdapter；onnx→OnnxImageModerationAdapter（nsfw-mobilenetv2 [1,224,224,3]→[1,5]）；aliyun 二期
Provider组件（ObjectProvider惰性注入，provider=none 可启动、消费期缺失抛 IllegalStateException→延迟重试入DLQ）：
  TextModerationAdapterProvider / ImageModerationAdapterProvider（收敛类型筛选+异常处理，禁业务类重写）
启动自检：ModerationProviderStartupChecker（fail-fast）  ｜  spi/dto：TextModerationRequest / ImageModerationRequest
结果缓存：ModerationResultCache（终局结论 1/2 才缓存，SUSPICIOUS 不缓存；value 纯整数字符串，file 侧 Integer.parseInt 同源）

## Feign
【出站调用】FileClient（取图/隔离/安全标记，见 api-index）+ ContentClient（回查正文/配图反查，fail-fast）+ SocialClient（私信回查，fail-fast）
【入站 Provider】ReviewFeignProviderController implements ReviewClient（com.litisle.litislereview.controller.feign，网关不路由）：
insertUsernameRejectRecord(UsernameRejectRecordDTO): Result<Void>   // 注册拒绝取证落库，不建 review_task、不进 MQ
getPunishStatus(userId): Result<PunishStatusDTO>                    // 处罚权威态快照，撤销时返回全空，消费端覆盖式写快照

## 常量（ModerationConstant 在 litisle-api，file 侧同源消费）
IMG_RESULT_KEY  = "review:img-result:%s:%s:%s"          // provider,modelVersion,sha256
TEXT_RESULT_KEY = "review:text-result:%s:%s:%s:%s"      // wordlibVer,fewShotVer,scene,contentHash
IMG_REJECT_BLACKLIST_KEY = "review:img-blacklist:%s"    // REJECT 后写 TTL 30d，file 上传闸门探测
LAST_HASH_KEY = "review:last-hash:%s:%s:%s:%s"          // wordlibVer,bizType,scene,bizId；免审键，content 写/读，下架+改判PASS 失效
MqConstant.QUEUE_MODERATION_{TEXT,IMAGE}_SUBMIT(_DLQ) / ROUTING_MODERATION_{TEXT,IMAGE}_SUBMIT(_DLQ)
MqConstant.ROUTING_MODERATION_{TEXT,IMAGE,MESSAGE}_RESULT / ROUTING_MODERATION_PUNISH(_DLQ) / EXCHANGE_MODERATION(_DLX,DELAY)

## Properties(litisle.moderation，ModerationProperties)
textProvider=llm; imageProvider=onnx; image.modelVersion=v1
onnx: modelPath; timeoutMs=10000; maxConcurrency=CPU核数; rejectThreshold=0.80; suspiciousThreshold=0.60
llm: temperature=0; fewShotVersion=v1; maxTextChars=6000; chunkStepChars=5500; chunkOverlapChars=500
cache: ttlHours=72; ttlJitterRatio=0.1(±10%抖动); dedup.lockSeconds=60; wordlib.snapshotDir=~/.litisle/review/dfa-snapshot
qps: 全站QPS护栏（Redis不可用切本地兑底）  ｜  retry: 消费端指数退避（max-count/间隔，sweeper 阈值须大于退避总时长）
sweeper: claimTimeoutMinutes=30; appealTimeoutDays=7; stuckResubmitMinutes=30; stuckResubmitEveryRounds=30; batchLimit=100; lockSeconds=30
quarantine: retentionDays=10(⚠️ 2026-11-02 过期值修正，原文档误记 30); batchLimit=100; deleteRatePerMinute=30; lockSeconds=300（到期软删由 Sweeper 委派 file 侧，物理清理统一交 file 侧对账任务，可恢复窗口=10+7=17 天）
record: retentionMonths=6; archiveEnabled=false(默认关); batchLimit=500; lockSeconds=300
rejectBlacklist.ttlDays=30(默认值归 shared.yaml)  ｜  claim.maxActivePerReviewer=20  ｜  report.abuseThreshold=3
alert: suspiciousBacklogThreshold=50; dlqBacklogThreshold=100; moderationFailRate=0.2; passRateDropRatio=0.3; cacheHitDropRatio=0.5

## 跨服务口径（易踩坑）
file 侧读图片缓存 = Integer.parseInt(value) → review 写缓存 value 必须是纯整数字符串
缓存 key 版本段：图片含 provider+modelVersion、文本含 wordlibVer+fewShotVer+scene，版本变更即缓存隔离
图片逐图送 SPI、不逐图建 biz_type=6 任务；ARTICLE_IMG_RE_REVIEW 聚合判定：全PASS→PASS/任一REJECT→REJECT/含SUSPICIOUS→SUSPICIOUS
图片场景（IMAGE/ARTICLE_IMG_RE_REVIEW）不走 DFA（DfaScenes 归一 null），勿误调词库
机审结果发布事务铁律：本地事务提交 → publisher confirm 发 result → basicAck；失败抛异常不 ACK 走重投；reason 剥换行+截断500
bizType→举报targetType 映射错位：MESSAGE(5)→4、FILE(6)→5，禁恒等
机审拒绝处罚联动统一收口：MachineRejectPunishGate 契约（禁两链路各写）+ RejectPunishAdapterHost 记账 + PunishResultPublisher sendCritical
申诉改判PASS 七步编排见 AppealServiceImpl（改判CAS→处罚撤销→缓存失效→举报批量回写 status<3→afterCommit 发 appeal.result）
单图审(scene=IMAGE)死信无 review_task，直接发 SUSPICIOUS 事件置 safe_flag=3；DLQ 消费失败一律 ack，任务停留 status=0 由 sweeper 兑底
5-5 图片申诉缓存失效按留痕行重建键（ImageAppealCacheInvalidator），防聚合指纹错位/漏删黑名单
评价content.unpublished须失效 text/img-result + last-hash 键（ContentUnpublishedInvalidator，litisle-api 门面）

## 服务/Controller 拆分（逐字保留原注释纪律）
Service：ReportService / ReviewTaskService / AppealService / PunishService / WordlibService（五域拆分）
  + ReviewTaskUpsertService（建任务收敛） / UsernameRejectRecordService / ReviewService
Controller：ReportController / ReviewTaskController / AppealController / PunishController / WordlibController / ReviewController / feign/ReviewFeignProviderController
词库域：WordlibVersionService（版本）/ WordlibChangeSubscriber（热更广播，version 乐观比对）/ TableWordLibraryLoader / WordlibMetrics / DefaultWordlibExporter

## MQ 消费/发布（moderation 包）
消费：ModerationTextConsumer（QUEUE_MODERATION_TEXT_SUBMIT）/ ModerationImageConsumer（取图走 FileClient 字节流，缓存 key 含 provider+modelVersion）
  ｜ ModerationDlqConsumer（失败 ack，sweeper 兑底重投）
发布：ModerationResultPublisher / ModerationAppealResultPublisher / PunishResultPublisher（均为事务后 afterCommit + 失败入 DLQ 口径）

## Sweeper（sweeper 包，板块六）
ReviewTaskSweeper：认领超时回池 / 申诉超时维持原判 / 三态 Gauge+DLQ 监控 / 任务内分频 stuck 重投（dedup 短锁防双投，retryCount 不归零）
QuarantineSweeper：违规图到期软删（委派 file 侧软删+解绑引用，不删对象；缓冲 7 天内可恢复，期满物理清理），申诉豁免、限速删除、双标签 Gauge
ModerationRecordArchiveSweeper：留痕归档（archiveEnabled=false 占位，id 区间推进，引用保护跳过）
PunishReconcileSweeper：处罚双向对账，复用 forceLogout 契约正向补踢，反向仅回查覆盖

## 指标与护栏
ModerationMetrics（ObjectProvider 惰性，未接 actuator 静默跳过）：incrementTotal(落库入口计一次)/recordCost(真实SPI后)/incrementCacheHit/incrementPunish
AlertThresholdMetrics（7-3 告警阈值转 Prometheus 指标，规则文件引指标不写死）
ModerationModelQpsGuard（模型直调全站 QPS+本地兑底计数）/ ModerationGlobalQpsInterceptor（复用 common-redis RedisRateLimiter）

## 审核域 DTO/VO
DTO：ReportSubmitDTO / TaskDecideDTO / AppealSubmitDTO / AppealDecideDTO / WordUpdateDTO（禁传实体）
VO：ReportVO / ReviewTaskVO / PunishLogVO / SensitiveWordVO（禁返实体）
归档：ModerationRecordArchive 实体 + ModerationRecordArchiveMapper

## 枚举全量
上列 7 个为跨模块高频；全量 21 个见 com.litisle.litislereview.enums，另含举报/申诉/处罚/词库域：
ReportStatus/AbuseFlag/AppealStatus/AuthorApplyStatus/PunishAction/PunishStatus/LastPunishAction/TargetType/
Layer1Hit/ModerationCategory/WordScene/WordLevel/WordListType/WordStatus