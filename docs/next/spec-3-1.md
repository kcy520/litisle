
# spec-3-1 · ModerationTextConsumer（板块三 3-1）

> 章节：§4.2.1 ④⑤⑥｜落位：litisle-review `moderation` 包

> 前置阻塞：litisle-ai chat 端点（`POST /api/feign/ai/chat`）未实现——联调前必须落地。

> LLM 审核部分**留注释占位即可**（用户决策）。

## 一、产出物（文件清单）

### 需新建（litisle-review）

- `litisle-review/src/main/java/com/litisle/litislereview/moderation/consumer/ModerationTextConsumer.java`

- `litisle-review/src/main/java/com/litisle/litislereview/moderation/cache/ModerationResultCache.java`（缓存查重：Redis→moderation_record 兜底）

- `litisle-review/src/main/java/com/litisle/litislereview/moderation/service/ModerationTextService.java`（幂等分流 + CAS + 三分态落库，供 consumer 调用）

- `litisle-review/src/main/java/com/litisle/litislereview/moderation/config/ReviewRabbitConfig.java`（声明 moderation 交换机/队列/绑定/DLX + x-delay；review 侧当前**无任何 RabbitListener/拓扑声明**）

### 需修改（litisle-review）

- 无（若复用既有 ModerationRecordMapper / ReviewTaskMapper 即可）

### 需新建（litisle-api 契约缺口，见 §二）

- `litisle-api/.../event/moderation/TextSubmitEvent.java`

- `litisle-api/.../feign/ContentClient.java`（+ `fallback/ContentClientFallbackFactory.java`）

- `litisle-api/.../feign/MessageClient.java`（+ `fallback/MessageClientFallbackFactory.java`）

### 需修改（litisle-api 契约缺口）

- `litisle-api/.../constant/MqConstant.java`（补 text submit 路由键/队列/DLQ）

- `litisle-api/.../config/FeignFallbackAutoConfiguration.java`（注册新 fallback 工厂）

## 二、契约依赖（已存在 vs 需新建）

> ⚠️ 原件写「依赖既有 ContentClient/MessageClient」，但**实际不存在**，须按本节补齐。

| 契约 | 现状 | 签名/说明 |
|---|---|---|
| `MqConstant.EXCHANGE_MODERATION` | ✅ | `litisle.moderation.exchange` |
| `MqConstant.ROUTING_MODERATION_IMAGE_SUBMIT` | ✅ | `moderation.image.submit` |
| `MqConstant.ROUTING_MODERATION_TEXT_SUBMIT` | 🆕 | 建议 `moderation.text.submit` |
| `MqConstant.QUEUE_MODERATION_TEXT_SUBMIT` | 🆕 | 建议 `litisle.moderation.text.submit.queue` |
| `MqConstant.QUEUE_MODERATION_TEXT_SUBMIT_DLQ` | 🆕 | 建议 `litisle.moderation.text.submit.dlq.queue` |
| `ImageSubmitEvent`（modType/bizType/bizId/scene/contentHash/retryCount/traceId） | ✅ | 文本版需新建 `TextSubmitEvent` |
| `FileClient.getContent(fileId)` | ✅ | `Result<FileContentDTO>`（图片用，本条不涉及） |
| `ContentClient` | 🆕 | 回查正文：`Result<ContentDTO> getContent(bizType, bizId)` |
| `MessageClient` | 🆕 | 回查私信：`Result<MessageDTO> getContent(bizId)` |
| `MqAckHelper.executeWithAck(queue, message, channel, Runnable)` | ✅ | common-mq，手动 ACK 纪律 |
| `MqTraceSupport.restore/clear(message)` | ✅ | common-mq，traceId 恢复 |
| `MqIdempotentHelper.tryAcquire(queue, eventId)` | ✅ | common-mq（可选） |
| `DfaFacade.check(scene, text)` | ✅ | 返回 `DfaHitResult(hit/maxLevel/hitWords/wordlibVer)` |
| `ContentModerationService.moderateText(String)` | ✅ | 返回 `ModerationResult(verdict/category/reason/providerTraceId/costMs)`，LLM 留占位 |
| `ModerationProperties` | ✅ | `llm.max-text-chars`(6000)/`dedup.lock-seconds` 等 |
| `ReviewTask`（bizType/bizId/scene/status/contentHash/retryCount/modVerdict/layer1Hit/hitWords/layer2Risk） | ✅ | 实体已备 |
| `ModerationRecord`（verdict/provider/wordlibVer/contentHash/scene/traceId/costMs/category/reason/modType） | ✅ | 实体已备 |
| `VerdictEnum`(0/1/2/3) / `ModTypeEnum.LLM=2` / `ModerationProviderEnum.LLM` / `ReviewTaskStatusEnum` | ✅ | SSOT 枚举已备 |
| `ModerationResultEvent`（bizType/bizId/scene/contentHash/wordlibVer/reviewTaskId/verdict/category/reason/traceId） | ✅ | 3-4 发布用 |

## 三、实施步骤

1. **监听** `moderation.text.submit` 队列：`@RabbitListener` 配 `prefetch=litisle.moderation.consumer.prefetch`（默认 10），手动 ACK。

2. **定位任务**：按 `(biz_type, biz_id, scene)` 三元组定位 `review_task`——scene 必带（复审任务与文本任务同 biz_type 同 biz_id 并存，缺 scene 定位歧义），缺 scene 直接 ACK 丢弃 + error 日志。

3. **任务缺失**→error 日志后 ACK（脏消息）；**回查正文**：`bizType∈{1,2}`→`ContentClient`、`bizType=5`→`MessageClient`；回查异常→抛异常不 ACK 进延迟重试。

4. **幂等分流**（status≠0 时）：事件 `contentHash` 与任务一致→视为「已处理后的重投」，补发 result 事件后 ACK（业务方回写幂等，重放无副作用）；hash 不一致→旧消息错配新任务，直接 ACK 丢弃。`status=0`→CAS：`update ... set status=处理中 where id=? and status=0`，affected=0→ACK（已被并发消费处理）。

5. **缓存查重**：Redis key=`review:text-result:{wordlibVer}:{fewShotVer}:{scene}:{contentHash}`，命中→解析 JSON 取 verdict；miss→查 `moderation_record` 兜底（idx_hash：`where content_hash=? and scene=? order by id desc limit 1`）。命中行必须**同时**满足：`verdict∈{1-PASS,2-REJECT}`（SUSPICIOUS 不参与复用，命中则置任务 status=2 挂既有人工任务不重复建）、`provider=当前值`（防 onnx→aliyun 切换复用旧结论）、`wordlib_ver=当前值`（防词库热更后旧结论放行）、`create_time` 在 `cache.ttl-hours(72)±10%` 窗口内——任一不符视为未命中。

6. 命中缓存→跳过模型调用，**但仍追加一行 moderation_record**（reason=「命中缓存，原记录ID=X」，provider/wordlib_ver 取当前值）→直接跳步骤 9。

7. 未命中→抢 SETNX 在途短锁 `dedup:{provider}:{contentHash}`（TTL=`dedup.lock-seconds`=60s）；抢不到→不 ACK 延迟重试（原请求在途，防并发穿透烧 N 倍模型费）。

8. **SPI 送审**（LLM 部分留注释占位）：文本超 `llm.max-text-chars`(6000)→按空行段落分片；单片仍超→字符窗口硬切（步长 5500、overlap 500，窗口参数可外置）；**聚合规则**：任一片 REJECT→整文 REJECT；含 SUSPICIOUS 片→整文 SUSPICIOUS；全 PASS→PASS。

9. **三分态落库（同一本地事务）**：CAS 流转 review_task（PASS→1 / REJECT→4 / SUSPICIOUS→2）+ 回填 `mod_verdict`/`layer2_risk` + 追加 moderation_record（`mod_type=2-LLM`、provider、wordlib_ver、content_hash、scene、trace_id、cost_ms、category、reason 截断 500）。

10. **事务提交后发 result 事件**（见 3-4），publisher confirm 成功→`basicAck`；任何一步失败抛异常不 ACK。

## 四、注意事项

- 命中缓存**只省模型调用不省留痕行**——漏留痕 = 该篇发布无审核凭证，监管抽查「为何发布」答不出。

- LLM 输出 verdict/category 为**不可信输出**，白名单校验越界→SUSPICIOUS 宁疑不放（适配器已兜底，消费端勿重复信任其原样落库）。

- 分片**禁静默截断**——违规段落写在末尾躲过机审是明确攻击面；overlap 防违规词跨切点被拆开漏检。

- 通用纪律（默认适用）：SQL 全 `#{}` 绑定；Redis key 各段服务端派生；UGC/traceId 落日志前剥离 `\r\n`；指标 `ObjectProvider<MeterRegistry>` optional；回查走契约 fallbackFactory fail-fast（失败不 ACK 进延迟重试）；TTL 一律 ±10% 抖动。

## 五、验收清单

- [ ] 监听/ACK 纪律：业务失败 ACK 丢弃、非预期异常 nack 入 DLQ（复用 MqAckHelper）

- [ ] 缺 scene / 任务缺失 / hash 错配 三类脏消息均 ACK 丢弃且留 error 日志

- [ ] status≠0 重投：hash 一致补发 result 且 ACK；hash 不一致 ACK 丢弃

- [ ] 缓存命中仍追加留痕行；provider/wordlib_ver/TTL 窗口四条件任一不符视为未命中

- [ ] 超长文本分片聚合正确（REJECT 优先 > SUSPICIOUS > PASS），无静默截断

- [ ] 三分态落库与 review_task 流转同一事务；SUSPICIOUS→status=2

- [ ] 事务提交后才发 result；发布失败不 ACK
