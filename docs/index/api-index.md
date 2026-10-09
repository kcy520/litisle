# api-index · litisle-api 契约速查表

> 用途：写码前查签名，**免去逐个 Read 契约文件**。
> 标记：✅ 已落地 ｜ 🆕 需新建（缺口）。
> SSOT 纪律：契约层 Javadoc 锁口径，改名字段须同步消费方。

## 一、常量类（`com.litisle.litisleapi.constant`）

### MqConstant ✅（部分）
- 交换机：`EXCHANGE_USER` / `EXCHANGE_CONTENT` / `EXCHANGE_NOTIFICATION` / `EXCHANGE_MODERATION`(`litisle.moderation.exchange`) / `EXCHANGE_MODERATION_DLX`(`litisle.moderation.dlx`)
- 队列：`QUEUE_USER_REGISTERED` / `QUEUE_CONTENT_PUBLISHED` / `QUEUE_FILE_MODERATION_RESULT` / `QUEUE_FILE_MODERATION_RESULT_DLQ` / `QUEUE_FILE_MODERATION_APPEAL_RESULT` / `QUEUE_FILE_MODERATION_APPEAL_RESULT_DLQ`
- 路由键：`ROUTING_USER_REGISTERED` / `ROUTING_CONTENT_PUBLISHED` / `ROUTING_MODERATION_RESULT`(`moderation.result`) / `ROUTING_MODERATION_RESULT_DLQ` / `ROUTING_MODERATION_APPEAL_RESULT` / `ROUTING_MODERATION_APPEAL_RESULT_DLQ` / `ROUTING_MODERATION_IMAGE_SUBMIT`(`moderation.image.submit`)
- 🆕 缺口：`ROUTING_MODERATION_TEXT_SUBMIT`、`QUEUE_MODERATION_TEXT_SUBMIT(_DLQ)`、`ROUTING_MODERATION_{TEXT,IMAGE,MESSAGE}_RESULT`、`ROUTING_MODERATION_PUNISH_RESULT`、`ROUTING_CONTENT_UNPUBLISHED`

### ModerationConstant ✅（部分）
- 枚举口径：`MOD_TYPE_AUTO=1`、`BIZ_TYPE_IMAGE=6`、`SCENE_IMAGE="IMAGE"`、`VERDICT_PASS=1`、`VERDICT_REJECT=2`、`VERDICT_SUSPICIOUS=3`
- Redis key：`IMG_REJECT_BLACKLIST_KEY="review:img-blacklist:%s"`、`IMG_RESULT_KEY="review:img-result:%s:%s:%s"`（provider/modelVersion/sha256）、`WORDLIB_CHANGE_CHANNEL="review:wordlib:change"`、`WORDLIB_SNAPSHOT_KEY="review:wordlib-snapshot:%s"`
- 🆕 缺口：`TEXT_RESULT_KEY="review:text-result:..."`、`LAST_HASH_KEY="review:last-hash:..."`

### FeignConstant / HeaderConstant ✅
- `FeignConstant`：Feign 路径前缀常量（`/api/feign/**`，网关不路由，仅服务间可用）
- `HeaderConstant.TRACE_ID`：链路追踪 Header（Feign/MQ 透传同口径）

## 二、MQ 事件体（`com.litisle.litisleapi.event.moderation`）

- `ImageSubmitEvent` ✅：`eventId/eventTime/traceId/version + modType/bizType/bizId/scene/contentHash/retryCount`（**不含正文/图片地址**）
- `ModerationResultEvent` ✅：`bizType/bizId/scene/contentHash/wordlibVer/reviewTaskId/verdict/category/reason/traceId`（不含正文/图片地址）
- `ModerationAppealResultEvent` ✅：`eventId/eventTime/traceId/version + bizType/bizId/appealId/appealPass/reviewTaskId`
- 🆕 缺口：`TextSubmitEvent`（字段对齐 ImageSubmitEvent，文本版）

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

### 🆕 缺口
- `ContentClient`：回查文章/评论正文（`Result<ContentDTO> getContent(bizType, bizId)`），fallback fail-fast
- `MessageClient`：回查私信正文（`Result<MessageDTO> getContent(bizId)`），fallback fail-fast
- 两个 fallback 需在 `FeignFallbackAutoConfiguration` 注册

## 四、DTO（`com.litisle.litisleapi.dto.*`）

- `FileContentDTO` ✅：`fileId/mime/byte[] content`（Jackson 自动 Base64，≤10MB）
- `FileOwnerDTO` / `FileRefDTO` ✅
- `AiChatRequestDTO` / `AiChatResponseDTO` ✅
- user 域：`UserDTO` / `AuthUserDTO` / `RegisterUserDTO` ✅

## 五、DFA 契约（`com.litisle.litisleapi.dfa`）

- `DfaFacade` ✅：`initAll()` / `rebuild(scene)` / `check(scene, text) → DfaHitResult`
- `DfaHitResult` ✅：`hit/maxLevel/hitWords/wordlibVer`（70001 仅 HIGH；MID 只标记 layer1_hit=2）
- `WordLibraryLoader`（SPI）✅ / `WordEntry(word/level/blacklist)` ✅
- `WordlibSnapshot` / `WordlibChangedMessage` ✅ / `DfaScenes`（scene 归一，图片场景→null）✅

## 六、common-mq 消费工具（`com.litisle.commonmq`）

- `MqAckHelper.executeWithAck(queue, message, channel, Runnable)` ✅（业务失败 ACK、异常 nack 入 DLQ）
- `MqTraceSupport.restore(message)` / `clear()` ✅
- `MqIdempotentHelper.tryAcquire(queue, eventId)` / `tryAcquireStrict(...)` ✅
- `MqSendUtil.send(exchange, routingKey, event)` / `sendCritical(...)` ✅（`Jackson2JsonMessageConverter` 由自动配置提供）