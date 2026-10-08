# spec-3-0 · 契约补齐前置（3-1 开工前置）

> **定位**：`spec-3-1` 依赖的 litisle-api 契约与 review 侧拓扑**当前并不存在**（原件误写为"既有"）。
> 本文件是开工 3-1 前的**前置补齐清单**，完成后方可执行 `spec-3-1.md`。
> 涉及模块：仅 `litisle-api`、`litisle-common`、`litisle-review`（不越界）。

## 一、缺口总览

| # | 项 | 现状 | 动作 | 落位 |
|---|---|---|---|---|
| 1 | `TextSubmitEvent` | ❌不存在 | 新建 | `litisle-api/event/moderation/` |
| 2 | `ContentDTO` | ❌不存在 | 新建 | `litisle-api/dto/content/` |
| 3 | `MessageDTO` | ❌不存在 | 新建 | `litisle-api/dto/message/` |
| 4 | `ContentClient` + Fallback | ❌不存在 | 新建 | `litisle-api/feign/` + `fallback/` |
| 5 | `MessageClient` + Fallback | ❌不存在 | 新建 | `litisle-api/feign/` + `fallback/` |
| 6 | `MqConstant` 文本路由/队列 | ⚠️仅有 image submit | 修改 | `litisle-api/constant/` |
| 7 | `ModerationConstant` 文本缓存 key | ⚠️仅有 img-result | 修改 | `litisle-api/constant/` |
| 8 | Fallback 注册 | ⚠️仅 4 个 Bean | 修改 | `litisle-api/config/FeignFallbackAutoConfiguration.java` |
| 9 | `ReviewRabbitConfig` 拓扑 | ❌不存在 | 新建 | `litisle-review/moderation/config/` |

## 二、litisle-api 新增

### 2.1 TextSubmitEvent（镜像 ImageSubmitEvent）

落位：`litisle-api/src/main/java/com/litisle/litisleapi/event/moderation/TextSubmitEvent.java`

字段（与 ImageSubmitEvent **逐一对齐**，仅语义注释改为文本口径）：
- 公共字段：`eventId`(UUID) / `eventTime`(LocalDateTime) / `traceId` / `version`(=1)
- 业务字段：`modType`(1-机审) / `bizType`(1-文章 2-评论 3-用户名 5-私信) / `bizId`(Long) / `scene`(String，必带) / `contentHash`(正文 SHA256) / `retryCount`

纪律：`@Data` + `implements Serializable` + `serialVersionUID`；**禁 @Builder**；事件体**不含正文**（消费端按 bizType+bizId+scene 回查）。

### 2.2 ContentDTO

落位：`litisle-api/src/main/java/com/litisle/litisleapi/dto/content/ContentDTO.java`

建议字段（供 3-1 回查正文 + 后续申诉 owner 校验/RAG 状态过滤复用）：
`bizType` / `bizId` / `title` / `body`(正文) / `ownerId`(作者) / `status`(发布态) / `visibility`(可见性) / `contentHash`(可选)

### 2.3 MessageDTO

落位：`litisle-api/src/main/java/com/litisle/litisleapi/dto/message/MessageDTO.java`

建议字段：`bizId` / `senderId` / `receiverId` / `content`(正文) / `status`

### 2.4 ContentClient（新 Feign）

落位：`litisle-api/src/main/java/com/litisle/litisleapi/feign/ContentClient.java`

- `@FeignClient(name = "litisle-content", path = "/api/feign/content", fallbackFactory = ContentClientFallbackFactory.class)`
- 方法：`Result<ContentDTO> getContent(@RequestParam("bizType") Integer bizType, @RequestParam("bizId") Long bizId)`
- 路径：`GET /api/feign/content/get`（**待与 content 服务确认**，网关不路由 `/api/feign/**`）

### 2.5 MessageClient（新 Feign）

落位：`litisle-api/src/main/java/com/litisle/litisleapi/feign/MessageClient.java`

- `@FeignClient(name = "litisle-message", path = "/api/feign/message", fallbackFactory = MessageClientFallbackFactory.class)`
- 方法：`Result<MessageDTO> getContent(@RequestParam("bizId") Long bizId)`
- 路径：`GET /api/feign/message/get`（**待与 message 服务确认**）

### 2.6 两个 FallbackFactory（新）

落位：`litisle-api/.../fallback/ContentClientFallbackFactory.java` / `MessageClientFallbackFactory.java`

**降级语义 = fail-fast**（机审回查是审核核心依赖，读不到不能当"无内容"放行；由 review 侧不 ACK 进延迟重试）：
抛 `BizException(ErrorCode.REMOTE_SERVICE_ERROR, "内容服务暂不可用，请稍后重试", cause)`。

## 三、litisle-api 修改

### 3.1 MqConstant（补文本送审路由/队列）

在 `ROUTING_MODERATION_IMAGE_SUBMIT` 之后追加：
- `ROUTING_MODERATION_TEXT_SUBMIT = "moderation.text.submit"`
- `ROUTING_MODERATION_TEXT_SUBMIT_DLQ = "moderation.text.submit.dlq"`
- `QUEUE_MODERATION_TEXT_SUBMIT = "litisle.moderation.text.submit.queue"`
- `QUEUE_MODERATION_TEXT_SUBMIT_DLQ = "litisle.moderation.text.submit.dlq.queue"`

（image 侧消费队列 `QUEUE_MODERATION_IMAGE_SUBMIT(_DLQ)` 属 3-2 范围，可同期补或留至 3-2。）

### 3.2 ModerationConstant（补文本缓存 key）

在 `IMG_RESULT_KEY` 之后追加：
- `TEXT_RESULT_KEY = "review:text-result:%s:%s:%s:%s"`（依次 `wordlibVer`、`fewShotVer`、`scene`、`contentHash`）

（`LAST_HASH_KEY` 属板块四 4-3 免审，暂不补。）

### 3.3 FeignFallbackAutoConfiguration（注册两个新 Fallback）

在 `aiClientFallbackFactory()` 之后并列追加两个 `@Bean`：`contentClientFallbackFactory()` / `messageClientFallbackFactory()`。

## 四、litisle-review 新增

### 4.1 ReviewRabbitConfig（review 侧拓扑声明）

落位：`litisle-review/src/main/java/com/litisle/litislereview/moderation/config/ReviewRabbitConfig.java`

review 侧当前**无任何 RabbitListener / 拓扑声明**，需补：
- `TopicExchange EXCHANGE_MODERATION`（`MqConstant.EXCHANGE_MODERATION`，durable）
- `DirectExchange EXCHANGE_MODERATION_DLX`（`MqConstant.EXCHANGE_MODERATION_DLX`，durable）
- 文本送审队列：durable + `x-dead-letter-exchange`=DLX + `x-dead-letter-routing-key`=`ROUTING_MODERATION_TEXT_SUBMIT_DLQ`
- 文本 DLQ：绑定 DLX（routing=`ROUTING_MODERATION_TEXT_SUBMIT_DLQ`）
- 绑定：`ROUTING_MODERATION_TEXT_SUBMIT` → 文本队列
- `Jackson2JsonMessageConverter`（若 common-mq 自动配置已提供则不重复声明，**先确认**）

## 五、注意事项

- 事件/DTO 一律 `@Data` + `Serializable` + `serialVersionUID`，**禁 @Builder**（项目 DTO 规范）。
- 契约层（litisle-api）**不引 UI 增强依赖**；新增 Feign 仅依赖 spring-cloud-openfeign（已在）。
- Feign fallback **必须**在 `FeignFallbackAutoConfiguration` 注册并在 `META-INF/spring/...AutoConfiguration.imports` 生效（已在）。
- 队列/交换机声明**幂等**：Spring AMQP 声明已存在同名同参资源不报错；参数不一致会报 `PRECONDITION_FAILED`，务必与 file 侧拓扑参数对齐。
- 本文件只补"让 3-1 能编译并跑通"的最小契约；结果回写矩阵（3-5）所需队列属后续板块，**不在此展开**。

## 六、验收清单

- [ ] `litisle-api` 编译通过，`TextSubmitEvent`/`ContentDTO`/`MessageDTO` 字段与注释齐全
- [ ] `ContentClient`/`MessageClient` 签名 + FallbackFactory（fail-fast）就绪，并在自动配置注册
- [ ] `MqConstant`/`ModerationConstant` 新增常量有 Javadoc 且 key 口径与消费端一致
- [ ] `ReviewRabbitConfig` 声明拓扑通过（无 `PRECONDITION_FAILED`），文本队列带 DLX
- [ ] `litisle-review` 可启动并成功监听 `moderation.text.submit`（3-1 前置达成）