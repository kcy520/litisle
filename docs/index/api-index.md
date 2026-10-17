# api-index · litisle-api 契约速查表

> 用途：写码前查签名，**免去逐个 Read 契约文件**。
> 标记：✅ 已落地 ｜ 🆕 需新建（缺口）。
> SSOT 纪律：契约层 Javadoc 锁口径，改名字段须同步消费方。

## 一、常量类（`com.litisle.litisleapi.constant`）

### MqConstant ✅（全量）
- 交换机：`EXCHANGE_USER` / `EXCHANGE_CONTENT` / `EXCHANGE_NOTIFICATION` / `EXCHANGE_MODERATION` / `EXCHANGE_MODERATION_DLX` / `EXCHANGE_MODERATION_DELAY`(`litisle.moderation.delay.exchange`，x-delayed-message 延迟重试专用)
- 队列：`QUEUE_USER_REGISTERED` / `QUEUE_CONTENT_PUBLISHED` / `QUEUE_CONTENT_UNPUBLISHED` / `QUEUE_FILE_MODERATION_RESULT(_DLQ)` / `QUEUE_FILE_MODERATION_APPEAL_RESULT(_DLQ)` / `QUEUE_MODERATION_TEXT_SUBMIT(_DLQ)` / `QUEUE_MODERATION_IMAGE_SUBMIT(_DLQ)` / `QUEUE_AUTH_MODERATION_PUNISH(_DLQ)`
- 路由键：`ROUTING_USER_REGISTERED` / `ROUTING_CONTENT_PUBLISHED` / `ROUTING_CONTENT_UNPUBLISHED`(`content.unpublished`) / `ROUTING_MODERATION_RESULT`(`moderation.result`) / `ROUTING_MODERATION_RESULT_DLQ` / `ROUTING_MODERATION_APPEAL_RESULT(_DLQ)` / `ROUTING_MODERATION_{TEXT,IMAGE}_SUBMIT(_DLQ)` / `ROUTING_MODERATION_{TEXT,IMAGE,MESSAGE}_RESULT` / `ROUTING_MODERATION_PUNISH`(`moderation.punish.result`) / `ROUTING_MODERATION_PUNISH_DLQ`
- ⚠️ `ROUTING_MODERATION_IMAGE_RESULT`(`moderation.image.result`) 启用时 file 侧绑定须同步改为本键，防 PRECONDITION_FAILED

### ModerationConstant ✅（全量）
- 枚举/口径：`MOD_TYPE_AUTO=1`、`BIZ_TYPE_{ARTICLE=1,COMMENT=2,USERNAME=3,MESSAGE=5,IMAGE=6}`、`SCENE_IMAGE` / `SCENE_ARTICLE_IMG_RE_REVIEW`、`VERDICT_{PASS=1,REJECT=2,SUSPICIOUS=3}`、`DFA_LEVEL_{HIGH=1,MID=2}`、`TITLE_MAX_LENGTH=100`、`TEXT_SUBMIT_SEPARATOR="\n"`、`MESSAGE_STATUS_{RECALLED=0,NORMAL=1}`、`STRANGER_QUOTA_LIMIT=3`
- Redis key：`IMG_REJECT_BLACKLIST_KEY="review:img-blacklist:%s"`、`IMG_RESULT_KEY`（provider/modelVersion/sha256）、`TEXT_RESULT_KEY="review:text-result:%s:%s:%s:%s"`（wordlibVer/fewShotVer/scene/contentHash）、`LAST_HASH_KEY="review:last-hash:%s:%s:%s:%s"`（wordlibVer/bizType/scene/bizId，值字段 `LAST_HASH_FIELD_HASH/VERDICT`）、`STRANGER_QUOTA_KEY="biz:message:stranger-quota:%s"`、`WORDLIB_CHANGE_CHANNEL`、`WORDLIB_SNAPSHOT_KEY`

### FeignConstant / HeaderConstant ✅
- `FeignConstant`：Feign 路径前缀常量（`/api/feign/**`，网关不路由，仅服务间可用）
- `HeaderConstant.TRACE_ID`：链路追踪 Header（Feign/MQ 透传同口径）

## 二、MQ 事件体（`com.litisle.litisleapi.event.moderation`）

- `ImageSubmitEvent` ✅：`eventId/eventTime/traceId/version + modType/bizType/bizId/scene/contentHash/retryCount`（**不含正文/图片地址**）
- `ModerationResultEvent` ✅：`bizType/bizId/scene/contentHash/wordlibVer/reviewTaskId/verdict/category/reason/traceId`（不含正文/图片地址）
- `ModerationAppealResultEvent` ✅：`eventId/eventTime/traceId/version + bizType/bizId/appealId/appealPass/reviewTaskId`
- `TextSubmitEvent` ✅：字段对齐 ImageSubmitEvent（`modType/bizType/bizId/scene/contentHash/retryCount`，不含正文）
- `PunishResultEvent` ✅：`eventId/eventTime/traceId/version/retryCount + userId/action/op/punishLogId/frozenUntil`（仅触发信号，消费端回查权威态覆盖快照）
- `ContentUnpublishedEvent` ✅（event.content 包）：`eventId/eventTime/traceId/version + bizType/bizId/contentHash/reason`（删除/下架/转私密三路径发布，ai/search 消费）
- `ContentPublishedEvent` ✅（event.content 包）：`eventId/eventTime/traceId/version + bizType/bizId/contentHash`（审核 PASS/免审直落/转公开不重审发布，search/ai/notification 消费）

## 三、Feign 客户端（`com.litisle.litisleapi.feign`）

### FileClient ✅（`name="litisle-file"`，`/api/feign/file/**`）
- `Result<FileOwnerDTO> getFileInfo(String fileId)` — GET `/{fileId}/info`（归属校验，fail-fast）
- `Result<Void> bindRef(FileRefDTO dto)` / `unbindRef(FileRefDTO dto)` — 引用登记（fail-fast）
- `Result<Map<String,String>> batchGetUrls(List<String> fileIds)` — 展示类读，静默空 Map
- `Result<Void> quarantine(String fileId)` / `unquarantine(String fileId)` — 违规图隔离/恢复（fail-fast）
- `Result<Map<String,Integer>> batchGetSafeFlags(List<String> fileIds)` — 读，静默空 Map（缺失=未知态保守）
- `Result<FileContentDTO> getContent(String fileId)` — GET `/{fileId}/content`（**机审取图唯一入口**，不过 safe_flag 闸口，fail-fast）

### AiClient ✅（契约就绪，提供方端点未实现）
- `Result<AiChatResponseDTO> chat(AiChatRequestDTO dto)` — POST `/api/feign/ai/chat`（fail-fast）

### UserClient ✅ / AuthClient ✅
- `UserClient`：`getUserById` / `createUser` / `findByAccount` / `findByEmail` / `getAuthUserInfo`
- `AuthClient`：`forceLogout(userId)` / `verifyChangeEmail(dto)`

### ContentClient ✅（`name="litisle-content"`，提供方端点待 content 实现）
- `Result<ContentDTO> getContent(bizType, bizId)` — GET `/api/feign/content/get`（机审回查正文，fail-fast）
- `Result<List<ArticleImageRefDTO>> listArticleImages(articleId)` — GET `/api/feign/content/article-images`（配图复审反查，fail-fast）
- `Result<List<Long>> listArticleIdsByFileId(fileId)` — GET `/api/feign/content/article-ids-by-file`（按 idx_file_id 反查引用文章，图片复审再聚合用，fail-fast；聚合判定收口 review 消费端，板块八 8.2）

### SocialClient ✅（`name="litisle-social"`，提供方端点待 social 实现）
- `Result<MessageDTO> getContent(bizId)` — GET `/api/feign/message/get`（私信机审回查，fail-fast；降级工厂类名 `MessageClientFallbackFactory`）
- `Result<Boolean> isFollowing(ownerId, viewerId)` — GET `/api/feign/follow/check`（visibility=3 仅关注者判定回查，fail-fast；业务 fail 按非关注者处理宁严勿松）

### ReviewClient ✅（`name="litisle-review"`）
- `Result<Void> insertUsernameRejectRecord(UsernameRejectRecordDTO)` — POST `/api/feign/moderation/insert-username-reject-record`（注册拒绝取证，fail-fast）
- `Result<PunishStatusDTO> getPunishStatus(userId)` — GET `/api/review/punish/status`（处罚权威态回查，网关不路由防公网直查，fail-fast）

- 全部 fallbackFactory 已在 `FeignFallbackAutoConfiguration` 注册（`META-INF/spring/...AutoConfiguration.imports` 生效）

## 四、DTO（`com.litisle.litisleapi.dto.*`）

- `FileContentDTO` ✅：`fileId/mime/byte[] content`（Jackson 自动 Base64，≤10MB）
- `FileOwnerDTO` / `FileRefDTO` ✅
- `AiChatRequestDTO` / `AiChatResponseDTO` ✅
- user 域：`UserDTO` / `AuthUserDTO` / `RegisterUserDTO` ✅
- moderation 域：`PunishStatusDTO` / `PunishAuditDTO` / `PunishSpeakCheckDTO` / `UsernameRejectRecordDTO` ✅
- content 域：`ContentDTO` / `ArticleImageRefDTO` / `ContentPreviewCheckDTO` / `ContentPreviewCheckVO` ✅
- social 域：`MessageDTO` ✅ ｜ auth 域：`TokenVO` / `OAuthBindDTO` / `OAuthAuthorizeVO` ✅ ｜ captcha 域：`ChangeEmailVerifyDTO` ✅
- vo 域：`UserRolesVO` / `UserBriefVO` ✅ ｜ enums：`ImageAuditTriggerEnum` / `RegisterSourceEnum` ✅

## 五、moderation 门面组件（`com.litisle.litisleapi.moderation`）✅

- `SubmitTextAssembler`：`assemble(title, body)`（标题+正文按 `TEXT_SUBMIT_SEPARATOR` 拼接）/ `contentHash(text)`（SHA256）
- `PublishTextPrecheck`：`check(title, content)`（空内容/标题≤100 发布闸门）/ `hasVisibleText` / `stripHtmlTags`
- `DfaPublishGate`：`decide(scene, DfaHitResult) → DfaGateDecision(blocked, midMark, hitResult)`（70001 仅 HIGH）
- `UsernameRejectGate`：`recordReject(username, hitResult, RecordAdapter)`（取证失败仅留痕，不影响拦截结论）
- `LastHashStore`：`write/read/remove(wordlibVer, bizType, scene, bizId, RedisAdapter)` + `LastHashValue(contentHash, verdict)`
- `LastHashExemptChecker`：`decide(...)` / `statusExcluded(bizType, bizStatus, delFlag)` / `imgReReviewPassed(...)`（免审判定 SSOT）
- `StrangerQuotaGate`：`tryAcquire(conversationId, RedisIncrAdapter, DbQuotaAdapter)`（Redis INCR 主计数，故障降级 DB 原子更新）
- `MessageRecallGate`：`recall(messageId, senderId, RecallCasAdapter)`（仅本人、仅正常态可撤回）
- `MachineRejectPunishGate`：`recordRejectPunish(context, adapter, ...)`（机审处罚联动统一收口，afterCommit 发 punish 事件）
- `ContentUnpublishedInvalidator`：`invalidateAll(wordlibVer, fewShotVer, bizType, scene, ...)`（下架失效 text/img-result + last-hash 键）

## 六、DFA 契约（`com.litisle.litisleapi.dfa`）

- `DfaFacade` ✅：`initAll()` / `rebuild(scene)` / `check(scene, text) → DfaHitResult`
- `DfaHitResult` ✅：`hit/maxLevel/hitWords/wordlibVer`（70001 仅 HIGH；MID 只标记 layer1_hit=2）
- `WordLibraryLoader`（SPI）✅ / `WordEntry(word/level/blacklist)` ✅
- `WordlibSnapshot` / `WordlibChangedMessage` ✅ / `DfaScenes`（scene 归一：文章族三态→ARTICLE；COMMENT/USERNAME/MESSAGE 直映；**BIO 直审场景直映自身**（2026-10-17 补：原 switch 漏映射致 BIO 引擎在 textScenes 全集却不可达）；图片/未知场景→null 不走 DFA）✅

## 七、common-mq 消费工具（`com.litisle.commonmq`）

- `MqAckHelper.executeWithAck(queue, message, channel, Runnable)` ✅（业务失败 ACK、异常 nack 入 DLQ）
- `MqTraceSupport.restore(message)` / `clear()` ✅
- `MqIdempotentHelper.tryAcquire(queue, eventId)` / `tryAcquireStrict(...)` ✅
- `MqSendUtil.send(exchange, routingKey, event)` / `sendCritical(...)` ✅（`Jackson2JsonMessageConverter` 由自动配置提供）