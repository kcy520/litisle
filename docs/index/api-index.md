# api-index · litisle-api 契约速查表

> 用途：写码前查签名，**免去逐个 Read 契约文件**。
> 标记：✅ 已落地 ｜ 🆕 需新建（缺口）。
> SSOT 纪律：契约层 Javadoc 锁口径，改名字段须同步消费方。

## 一、常量类（`com.litisle.litisleapi.constant`）

### MqConstant ✅（全量）
- 交换机：`EXCHANGE_USER` / `EXCHANGE_CONTENT` / `EXCHANGE_NOTIFICATION` / `EXCHANGE_MODERATION` / `EXCHANGE_MODERATION_DLX` / `EXCHANGE_MODERATION_DELAY`(`litisle.moderation.delay.exchange`，x-delayed-message 延迟重试专用)
- 队列：`QUEUE_USER_REGISTERED` / `QUEUE_CONTENT_PUBLISHED` / `QUEUE_CONTENT_UNPUBLISHED` / `QUEUE_CONTENT_USER_BANNED(_DLQ)`（user.banned → content 封禁下架连带）/ `QUEUE_CONTENT_MODERATION_TEXT_RESULT(_DLQ)` / `QUEUE_CONTENT_MODERATION_APPEAL_RESULT(_DLQ)` / `QUEUE_FILE_MODERATION_RESULT(_DLQ)` / `QUEUE_FILE_MODERATION_APPEAL_RESULT(_DLQ)` / `QUEUE_MODERATION_TEXT_SUBMIT(_DLQ)` / `QUEUE_MODERATION_IMAGE_SUBMIT(_DLQ)` / `QUEUE_AUTH_MODERATION_PUNISH(_DLQ)`
- 路由键：`ROUTING_USER_REGISTERED` / `ROUTING_USER_BANNED(_DLQ)`(`user.banned`) / `ROUTING_CONTENT_PUBLISHED` / `ROUTING_CONTENT_UNPUBLISHED`(`content.unpublished`) / `ROUTING_MODERATION_RESULT`(`moderation.result`) / `ROUTING_MODERATION_RESULT_DLQ` / `ROUTING_MODERATION_APPEAL_RESULT(_DLQ)` / `ROUTING_MODERATION_TEXT_RESULT_DLQ`(`moderation.text.result.dlq`) / `ROUTING_MODERATION_{TEXT,IMAGE}_SUBMIT(_DLQ)` / `ROUTING_MODERATION_{TEXT,IMAGE,MESSAGE}_RESULT` / `ROUTING_MODERATION_PUNISH`(`moderation.punish.result`) / `ROUTING_MODERATION_PUNISH_DLQ`
- ⚠️ `ROUTING_MODERATION_IMAGE_RESULT`(`moderation.image.result`) 启用时 file 侧绑定须同步改为本键，防 PRECONDITION_FAILED
- search 索引同步路由键/队列已落地（temp search模块 批次B B2/B7，2026-11-03）：`content.article-updated` / `content.view-count-flushed`（B7，批次A A2/A5 定案）+ `EXCHANGE_SOCIAL`、`social.liked` / `social.commented`（B2）；书籍/用户索引变更事件仍**契约待落地**（B4/B5）

### ModerationConstant ✅（全量）
- 枚举/口径：`MOD_TYPE_AUTO=1`、`BIZ_TYPE_{ARTICLE=1,COMMENT=2,USERNAME=3,MESSAGE=5,IMAGE=6}`、`SCENE_IMAGE` / `SCENE_ARTICLE_IMG_RE_REVIEW`、`VERDICT_{PASS=1,REJECT=2,SUSPICIOUS=3}`、`DFA_LEVEL_{HIGH=1,MID=2}`、`TITLE_MAX_LENGTH=100`、`TEXT_SUBMIT_SEPARATOR="\n"`、`MESSAGE_STATUS_{RECALLED=0,NORMAL=1}`、`STRANGER_QUOTA_LIMIT=3`
- Redis key：`IMG_REJECT_BLACKLIST_KEY="review:img-blacklist:%s"`、`IMG_RESULT_KEY`（provider/modelVersion/sha256）、`TEXT_RESULT_KEY="review:text-result:%s:%s:%s:%s"`（wordlibVer/fewShotVer/scene/contentHash）、`LAST_HASH_KEY="review:last-hash:%s:%s:%s:%s"`（wordlibVer/bizType/scene/bizId，值字段 `LAST_HASH_FIELD_HASH/VERDICT`）、`STRANGER_QUOTA_KEY="biz:message:stranger-quota:%s"`、`WORDLIB_CHANGE_CHANNEL`、`WORDLIB_SNAPSHOT_KEY`
- SCAN 辅助：`TEXT_RESULT_SCAN_PATTERN` / `IMG_RESULT_SCAN_PATTERN`（SCAN 模式匹配前缀，供批量失效）
- 指标名：`METRIC_MODERATION_CACHE_HIT` / `METRIC_TAG_LAYER` / `METRIC_LAYER_RESULT` / `METRIC_LAYER_BLACKLIST`（审核命中率/词层级埋点）

### BizCacheKey ✅
- 跨服务共享业务缓存 Key（`biz:{模块}:{业务}:{标识}`，与 common 层技术 Key 分层，禁两侧私拼）：`USER_PUBLIC`(`biz:user:public:`) / `USER_ROLE`(`biz:user:role:`) / `ARTICLE_DETAIL`(`biz:article:detail:`) / `SOCIAL_FOLLOWER_CNT`(`biz:social:follower:count:`)

### FeignConstant / HeaderConstant ✅
- `FeignConstant`：Feign 路径前缀常量（`/api/feign/**`，网关不路由，仅服务间可用）
- `HeaderConstant.TRACE_ID`：链路追踪 Header（Feign/MQ 透传同口径）

## 二、MQ 事件体（`com.litisle.litisleapi.event.*`）

- `UserRegisteredEvent` ⚠️（event.user 包）：**空类**，Schema（eventId/eventTime/traceId/version + userId/username/uid/registerSource）已按 §7 规范在 Javadoc 约定，须事务 AFTER_COMMIT 后发布
- `UserBannedEvent` ✅（event.user 包）：`eventId/eventTime/traceId/version + userId/reason`（user 发布 → content 消费封禁下架连带）
- `ImageSubmitEvent` ✅：`eventId/eventTime/traceId/version + modType/bizType/bizId/scene/contentHash/retryCount`（**不含正文/图片地址**）
- `ModerationResultEvent` ✅：`bizType/bizId/scene/contentHash/wordlibVer/reviewTaskId/verdict/category/reason/traceId`（不含正文/图片地址）
- `ModerationAppealResultEvent` ✅：`eventId/eventTime/traceId/version + bizType/bizId/appealId/appealPass/reviewTaskId`
- `TextSubmitEvent` ✅：字段对齐 ImageSubmitEvent（`modType/bizType/bizId/scene/contentHash/retryCount`，不含正文）
- `PunishResultEvent` ✅：`eventId/eventTime/traceId/version/retryCount + userId/action/op/punishLogId/frozenUntil`（仅触发信号，消费端回查权威态覆盖快照）
- `ContentUnpublishedEvent` ✅（event.content 包）：`eventId/eventTime/traceId/version + bizType/bizId/contentHash/reason`（删除/下架/转私密三路径发布，ai/search 消费）
- `ContentPublishedEvent` ✅（event.content 包）：`eventId/eventTime/traceId/version + bizType/bizId/contentHash`（审核 PASS/免审直落/转公开不重审发布，search/ai/notification 消费）
- `ContentArticleUpdatedEvent` ✅（temp search批次B B7 落地 2026-11-03，event.content 包）：批次A A2 定案——已发布文章元数据编辑（title/tags/summary/cover）afterCommit 发布，`eventId/eventTime/traceId/version + bizType/bizId/contentHash`（定位键纪律，事件体不携带元数据内容，search 轻量回查更新）；路由键 `ROUTING_CONTENT_ARTICLE_UPDATED`(`content.article-updated`)；content 侧发布点待接入
- `ContentViewCountFlushedEvent` ✅（temp search批次B B7 落地 2026-11-03，event.content 包）：批次A A5 定案——浏览量 flush 任务落库后补推，`bizId + viewCount`（最新绝对值，消费端覆盖 SET 非累加），search 覆盖更新并重算 heat；路由键 `ROUTING_CONTENT_VIEW_COUNT_FLUSHED`(`content.view-count-flushed`)；content 侧发布点待接入
- `SocialLikedEvent` / `SocialCommentedEvent` ✅（temp search批次B B2 落地 2026-11-03，event.social 包）：`eventId/eventTime/traceId/version + bizType/bizId/bizUserId`；路由键 `ROUTING_SOCIAL_LIKED`(`social.liked`) / `ROUTING_SOCIAL_COMMENTED`(`social.commented`)，发布至 `EXCHANGE_SOCIAL`；social 落地时 AFTER_COMMIT 发布，search heat 增量消费

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

### ContentClient ✅（`name="litisle-content"`，提供方端点已落地：ContentFeignProviderController）
- `Result<ContentDTO> getContent(bizType, bizId)` — GET `/api/feign/content/get`（机审回查正文，fail-fast）
- `Result<List<ArticleImageRefDTO>> listArticleImages(articleId)` — GET `/api/feign/content/article-images`（配图复审反查，fail-fast）
- `Result<List<Long>> listArticleIdsByFileId(fileId)` — GET `/api/feign/content/article-ids-by-file`（按 idx_file_id 反查引用文章，图片复审再聚合用，fail-fast；聚合判定收口 review 消费端，板块八 8.2）
- `Result<Void> updateCounters(ArticleCounterUpdateDTO dto)` — POST `/api/feign/content/counters/update`（social 计数回写冗余列 view/like/comment cnt，幂等绝对值 SET，fail-fast）
- ⚠️ search 建索引回查字段充足性 + **审核终态/DFA 结论获取方式**待核（temp search批次B B6，契约待落地）；不足则扩提供方端点（禁事件体塞大字段）

### SocialClient ✅（`name="litisle-social"`，提供方端点待 social 实现）
- `Result<MessageDTO> getContent(bizId)` — GET `/api/feign/message/get`（私信机审回查，fail-fast；降级工厂类名 `MessageClientFallbackFactory`）
- `Result<Boolean> isFollowing(ownerId, viewerId)` — GET `/api/feign/follow/check`（visibility=3 仅关注者判定回查，fail-fast；业务 fail 按非关注者处理宁严勿松）

### SearchClient ✅（temp search批次B B1 落地 2026-11-03；缺口 G1 闭环；`name="litisle-search"`，`/api/feign/search/**`，网关不路由；提供方端点待 search 服务实现）
- `Result<List<SimilarArticleVO>> similar(articleId, topK)` — GET `/api/feign/search/similar`，相似推荐（ai RAG / 前端相关文章复用；KNN 强制 PUBLIC 过滤，批次C C3；fail-fast）
- `Result<SearchResultVO> search(SearchRequestDTO dto)` — POST `/api/feign/search/search`，服务间检索（非降级链路；fail-fast）
- fallback 工厂 `SearchClientFallbackFactory` + `FeignFallbackAutoConfiguration` 注册已补；DTO/VO 按批次A A6 归属入 api 包（`dto/search` / `vo/search`）

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
- content 域：`ContentDTO` / `ArticleImageRefDTO` / `ContentPreviewCheckDTO` / `ContentPreviewCheckVO` / `ArticleCounterUpdateDTO`（social 计数回写入参）✅
- social 域：`MessageDTO` ✅ ｜ auth 域：`TokenVO` / `OAuthBindDTO` / `OAuthAuthorizeVO` ✅ ｜ captcha 域：`ChangeEmailVerifyDTO` ✅
- vo 域：`UserRolesVO` / `UserBriefVO` ✅ ｜ enums：`ImageAuditTriggerEnum` / `RegisterSourceEnum` ✅
- search 域：`SearchRequestDTO`（`dto/search`）与 `SearchResultVO` / `SimilarArticleVO` / `ArticleDocVO` / `BookDocVO` / `UserDocVO`（`vo/search`）✅ 落地（temp search批次B B1，2026-11-03；`SearchResultVO` 命中列表按 type 拆 `articleList/bookList/userList` 三强类型字段落地）；`SuggestVO` / `SearchHistoryVO` 留 search/domain 仅 REST 用（未建）

## 五、moderation 门面组件（`com.litisle.litisleapi.moderation`）✅

- `SubmitTextAssembler`：`assemble(title, body)`（标题+正文按 `TEXT_SUBMIT_SEPARATOR` 拼接）/ `contentHash(text)`（SHA256）
- `PublishTextPrecheck`：`check(title, content)`（空内容/标题≤100 发布闸门）/ `hasVisibleText` / `stripHtmlTags`
- `DfaPublishGate`：`decide(scene, DfaHitResult) → DfaGateDecision(blocked, midMark, hitResult)`（70001 仅 HIGH）
- `UsernameRejectGate`：`recordReject(username, hitResult, RecordAdapter)`（取证失败仅留痕，不影响拦截结论）
- `UsernameModerationGate`：`review(username, dfaFacade, RecordAdapter)`（用户名链路编排：HIGH 拦截 + 拒绝取证 + 原值无状态收敛问题）
- `BioModerationGate`：`review(text, dfaFacade)`（简介类文本闸门：BIO 场景词库同步快筛，HIGH 拦 70001 不取证不建任务；空串=清空语义直接放行）
- `LastHashStore`：`write/read/remove(wordlibVer, bizType, scene, bizId, RedisAdapter)` + `LastHashValue(contentHash, verdict)`
- `LastHashExemptChecker`：`decide(...)` / `statusExcluded(bizType, bizStatus, delFlag)` / `imgReReviewPassed(...)`（免审判定 SSOT）
- `StrangerQuotaGate`：`tryAcquire(conversationId, RedisIncrAdapter, DbQuotaAdapter)`（Redis INCR 主计数，故障降级 DB 原子更新）
- `MessageRecallGate`：`recall(messageId, senderId, RecallCasAdapter)`（仅本人、仅正常态可撤回）
- `MachineRejectPunishGate`：`recordRejectPunish(context, adapter, ...)`（机审处罚联动统一收口，afterCommit 发 punish 事件）
- `ContentUnpublishedInvalidator`：`invalidateAll(wordlibVer, fewShotVer, bizType, scene, ...)`（下架失效 text/img-result + last-hash 键）

## 六、DFA 契约（`com.litisle.litisleapi.dfa`）

- `DfaFacade` ✅：`initAll()` / `rebuild(scene)` / `check(scene, text) → DfaHitResult`
- 引擎层 ✅：`TextScanEngine`（扫描引擎接口）/ `AcdatTextScanEngine`（AC 自动机+DAT 实现）/ `CharNormalizer`（字符归一化）——由 `DfaFacade` 内部编排，宿主不直接依赖
- `DfaHitResult` ✅：`hit/maxLevel/hitWords/wordlibVer`（70001 仅 HIGH；MID 只标记 layer1_hit=2）
- `WordLibraryLoader`（SPI）✅ / `WordEntry(word/level/blacklist)` ✅
- `WordlibSnapshot` / `WordlibChangedMessage` ✅ / `DfaScenes`（scene 归一：文章族三态→ARTICLE；COMMENT/USERNAME/MESSAGE 直映；**BIO 直审场景直映自身**（2026-10-17 补：原 switch 漏映射致 BIO 引擎在 textScenes 全集却不可达）；图片/未知场景→null 不走 DFA）✅

## 七、common-mq 消费工具（`com.litisle.commonmq`）

- `MqAckHelper.executeWithAck(queue, message, channel, Runnable)` ✅（业务失败 ACK、异常 nack 入 DLQ）
- `MqTraceSupport.restore(message)` / `clear()` ✅
- `MqIdempotentHelper.tryAcquire(queue, eventId)` / `tryAcquireStrict(...)` ✅
- `MqSendUtil.send(exchange, routingKey, event)` / `sendCritical(...)` ✅（`Jackson2JsonMessageConverter` 由自动配置提供）