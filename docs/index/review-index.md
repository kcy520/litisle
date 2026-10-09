## 枚举（值→含义）
ReviewSceneEnum: ARTICLE_FEATURED/ARTICLE_COMMUNITY/ARTICLE_RE_REVIEW/COMMENT/COMMENT_RE_REVIEW/USERNAME/MESSAGE/MESSAGE_RE_REVIEW/IMAGE/ARTICLE_IMG_RE_REVIEW（code==枚举名）
ModerationBizTypeEnum: ARTICLE(1) COMMENT(2) USERNAME(3) AUTHOR_APPLY(4) MESSAGE(5) FILE(6)  // 6 时 biz_id=file_meta.id
ModTypeEnum: DFA(1) LLM(2) LOCAL_IMAGE_MODEL(3) CLOUD_API(4) MANUAL_REVIEW(5) APPEAL_REJUDGE(6)
VerdictEnum: NONE(0) PASS(1) REJECT(2) SUSPICIOUS(3)
ReviewTaskStatusEnum: PENDING(0) AUTO_PASS(1) MANUAL_PENDING(2) PASS(3) REJECT(4)
Layer2RiskEnum: NOT_SUBMITTED(0) LOW(1) MID(2) HIGH(3)
ModerationProviderEnum: ONNX("onnx") ALIYUN("aliyun") LLM("llm")

## SPI
ContentModerationService#moderateImage(byte[]):ModerationResult
ModerationResult(record): verdict() category() reason() providerTraceId() costMs()

## Feign
FileClient#getContent(String fileId): Result<FileContentDTO{fileId,mime,byte[] content}>

## 常量
ModerationConstant.IMG_RESULT_KEY  = "review:img-result:%s:%s:%s" // provider,modelVersion,sha256
ModerationConstant.TEXT_RESULT_KEY = ...
MqConstant.QUEUE_MODERATION_IMAGE_SUBMIT / ROUTING_MODERATION_RESULT / EXCHANGE_MODERATION

## Properties(litisle.moderation)
imageProvider=onnx; image.modelVersion=v1; dedup.lockSeconds=60; llm.fewShotVersion=v1

## 跨服务口径（易踩坑）
file 侧读图片缓存 = Integer.parseInt(value) → review 写缓存 value 必须是纯整数字符串