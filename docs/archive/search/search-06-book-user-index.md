> **归档说明**：新建板块文件（批次五，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次五：书籍索引链路」节，正文原样搬运（含 ✅ 摘要与偏差说明）。
> **来源**：[next_update.md](../../next/next_update.md) 批次五；存量回填（9.1）仍活跃于该文档。

## 板块六：书籍与用户索引链路（批次五，2026-11-05 归档）

### search模块

#### 批次五：书籍索引链路（依赖批次一；优先级仅次于文章，B4 契约随本批次收口）

##### 5.1 书籍索引变更事件契约（B4）

- [x] litisle-api 定义书籍创建/变更/完结/下架事件 + MqConstant 路由键/队列（对齐 ContentPublishedEvent 定位键纪律：事件体仅 bizType/bizId/contentHash）；content 侧书籍变更点 afterCommit 发布接入
  - ✅ 2026-11-04：新增 `ContentBookChangedEvent`（基座四字段 + `bookId` 定位键）与 `MqConstant.ROUTING_CONTENT_BOOK_CHANGED/QUEUE_CONTENT_BOOK_CHANGED`；创建/编辑/完结/下架/删除统一走一条 `content.book-changed`，消费端回查权威态后收敛（完结当前无独立端点，端点落地时复用同事件）。content 侧新增 `ContentEventProducer#sendBookChanged`（复用 outbox + sendCritical），`BookServiceImpl.create/update/delete` 接入发布：create/update 无事务即时发送，delete 的 `@Transactional` 注册 afterCommit 后发送
  - ✅ 2026-11-04：新增 `BookIndexDocDTO` + `ContentClient#getBookIndexDoc`（fail-fast 降级）+ `ContentInternalService/Impl#getBookIndexDoc`（显式 del_flag=0，不存在/已软删抛 `BOOK_NOT_FOUND`，作者昵称 best-effort）+ `ContentFeignProviderController` 端点 `/api/feign/content/book-index-doc`

##### 5.2 book_idx 同步消费

- [x] IndexSyncConsumer 扩展书籍事件消费：回查 → 门控 → 幂等 upsert/删除 book_idx（书名/作者/简介/分类；书籍不入 article_chunks，C2 定版）；V1 可见性红线验证覆盖 book_idx
  - ✅ 2026-11-04：新增 `BookIndexService/Impl`：`getBookIndexDoc` 回查 → 门控（`delFlag=0` 且 `status≠TAKEN_DOWN`）→ 按 bookId upsert / delete（删除前判存在，幂等）；`BOOK_NOT_FOUND` 收敛为删索引，ES/远程异常抛 `IllegalStateException` 交延迟重试
  - ✅ 2026-11-04：`SearchMqConfig` 补 `content.book-changed` 队列/绑定/延迟重投/DLX-DLQ；`IndexSyncConsumer#onBookChanged` 接入成功后 `evictAll` 失效热点缓存；书籍不入 `article_chunks`
  - ✅ 2026-11-04（批次四遗留）：`book_idx` mapping 补 `authorId`（keyword），`BookIndexDoc` 增字段，`SearchServiceImpl#toBookResult` 解析回填 `BookDocVO.authorId`；`EsIndexInitializer` 启动时对已存在索引幂等 `putMapping` 补字段。**存量影响**：新增字段可热更新 mapping、无需重建索引；但存量文档不回填，需事件重放/全量重建后 `authorId` 才有值，此前检索结果为 null（不影响命中与排序）

##### 5.3 用户索引同步（原批次B B5，本次随批次五一并落地）

- [x] 用户事件契约 + user 侧发布点 + user_idx 同步消费
  - ✅ 2026-11-04：`UserRegisteredEvent` 补业务字段（userId/username/uid/registerSource）；新增 `UserProfileUpdatedEvent`（userId 定位键）与 `MqConstant.ROUTING_USER_PROFILE_UPDATED/QUEUE_USER_REGISTERED_INDEX/QUEUE_USER_PROFILE_UPDATED_INDEX`；新增 `UserIndexDocDTO` + `UserClient#getUserIndexDoc`（fail-fast 降级）
  - ✅ 2026-11-04：`litisle-user` 引入 `common-mq`、补 `spring.rabbitmq` 配置、新增 `UserMqConfig`（声明 `EXCHANGE_USER`）与 `UserEventProducer`；`UserFeignProviderController#createUser` 成功后发 `user.registered`，`UserProfileServiceImpl#updateCurrentUserProfile` 在笔名/简介变更后发 `user.profile-updated`（均为写库后 best-effort）；新增 `/api/feign/user/index-doc` 回查端点（仅公开字段）
  - ✅ 2026-11-04：新增 `UserIndexService/Impl`：回查 → 门控（`status=NORMAL`、`delFlag=0`、笔名/简介至少一项非空）→ 按 userId upsert/delete；`USER_NOT_FOUND` 收敛为删索引；`SearchMqConfig` 补 user 两队列/绑定/延迟重投/DLX-DLQ，`IndexSyncConsumer#onUserRegistered/onUserProfileUpdated` 接入。说明：新注册用户通常无公开资料，注册事件按门控「删索引」幂等空操作，待资料更新事件再正式入索引
