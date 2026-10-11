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

## Feign（FileClient，Provider=file 侧 FileFeignProviderController）
getContent(String fileId): Result<FileContentDTO{fileId,mime,byte[] content}>   // 机审取图，不过 safe_flag 闸口
quarantine(fileId) / unquarantine(fileId): Result<Void>                          // REJECT 隔离 / 申诉改判恢复，幂等
batchGetSafeFlags(List<String>): Result<Map<String,Integer>>                     // 复审聚合/申诉恢复，缺失=未知态

## 常量（ModerationConstant 在 litisle-api，file 侧同源消费）
IMG_RESULT_KEY  = "review:img-result:%s:%s:%s"          // provider,modelVersion,sha256
TEXT_RESULT_KEY = "review:text-result:%s:%s:%s:%s"      // wordlibVer,fewShotVer,scene,contentHash
IMG_REJECT_BLACKLIST_KEY = "review:img-blacklist:%s"    // REJECT 后写 TTL 30d，file 上传闸门探测
MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT(_DLQ) / ROUTING_MODERATION_IMAGE_SUBMIT(_DLQ) / ROUTING_MODERATION_RESULT / EXCHANGE_MODERATION(_DLX)

## Properties(litisle.moderation，ModerationProperties)
textProvider=llm; imageProvider=onnx; image.modelVersion=v1
onnx: modelPath; timeoutMs=10000; maxConcurrency=CPU核数; rejectThreshold=0.80; suspiciousThreshold=0.60
llm: temperature=0; fewShotVersion=v1; maxTextChars=6000; chunkStepChars=5500; chunkOverlapChars=500
cache: ttlHours=72; ttlJitterRatio=0.1(±10%抖动); dedup.lockSeconds=60; wordlib.snapshotDir=~/.litisle/review/dfa-snapshot

## 跨服务口径（易踩坑）
file 侧读图片缓存 = Integer.parseInt(value) → review 写缓存 value 必须是纯整数字符串
缓存 key 版本段：图片含 provider+modelVersion、文本含 wordlibVer+fewShotVer+scene，版本变更即缓存隔离
图片逐图送 SPI、不逐图建 biz_type=6 任务；ARTICLE_IMG_RE_REVIEW 聚合判定：全PASS→PASS/任一REJECT→REJECT/含SUSPICIOUS→SUSPICIOUS
图片场景（IMAGE/ARTICLE_IMG_RE_REVIEW）不走 DFA（DfaScenes 归一 null），勿误调词库

## 枚举全量
上列 7 个为跨模块高频；全量 21 个见 com.litisle.litislereview.enums，另含举报/申诉/处罚/词库域：
ReportStatus/AbuseFlag/AppealStatus/AuthorApplyStatus/PunishAction/PunishStatus/LastPunishAction/TargetType/
Layer1Hit/ModerationCategory/WordScene/WordLevel/WordListType/WordStatus