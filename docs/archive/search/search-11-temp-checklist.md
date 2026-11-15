> **归档说明**：新建板块文件（临时清单批次，2026-11-05 归档）——迁自 docs/temp/temp.md search 模块「搜索链路企业级优化（临时立项）」批次一~三已完成项：1.1、1.2、1.3、2.1、3.1；正文原样搬运（含 ✅ 摘要与涉及文件）。
> **来源**：[temp.md](../../temp/temp.md) search 模块临时立项；挂起项（1.4 标签入 ES completion、2.2 批量回查）见 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md)。

## 板块十一：搜索链路企业级优化（临时清单批次一~三，2026-11-05 归档）

### search模块

##### 1.1 联想最小输入长度门控（L0）

- [x] SuggestServiceImpl#suggest 增加最小输入长度校验：q 长度小于阈值直接返回空列表（当前仅校验空串与 >64 超长）；联想为高频击键场景，最小长度可在最前端砍掉大量单字符穿透请求，成本最低收益最大
  - ✅ 2026-11-04：`SearchBizConstant` 新增 `SUGGEST_MIN_KEYWORD_LENGTH=2`；`SuggestServiceImpl#suggest` 归一化后按「空串 / 少于 2 字 / 超过 64 字」统一返回空列表（联想不做报错，保持静默降级语义）
  - 涉及：litisle-search SuggestServiceImpl；长度阈值走 SearchBizConstant 常量（禁硬编码），风格对齐 content 侧 TAG_SUGGEST_KEYWORD_MAX_LENGTH

##### 1.2 标签联想 Redis 短缓存（L1，推荐主方案）

- [x] TagServiceImpl#searchByPrefix 增加 Redis 短缓存：命中 TTL 5~10min 带 ±10% 抖动（防同批 key 同时过期雪崩），空结果短 TTL 60s（防缓存穿透）；写法对齐同文件 hotTags 既有缓存范式（CacheConstant 新增前缀常量 + RedisUtil）
  - ✅ 2026-11-04：`CacheConstant` 新增 `CONTENT_TAG_SUGGEST="content:tag:suggest:"`；`ContentLimitConstant` 新增基准 TTL 480s（±10% 抖动后落在 8±0.8 分钟）与空结果 TTL 60s；`TagServiceImpl#searchByPrefix` 按「规范化前缀」读写 TagVO 列表 JSON，空结果走 60s 短缓存，非空走 `jitteredSuggestTtl()`；入库条件与排序不变（NORMAL + 未删 + use_count desc），只读聚合不主动失效
  - 涉及：litisle-content TagServiceImpl、commonredis CacheConstant
  - 口径：标签池变更（新建/封禁/删除）不主动失效缓存，靠短 TTL 自然过期收敛（联想场景允许分钟级陈旧）；search 侧 SuggestServiceImpl#collectTags 暂不加缓存（Feign 结果已随 content 侧缓存受益，避免双层缓存口径复杂化）

##### 1.3 /search/suggest 匿名限流（L5）

- [x] SearchController#suggest 补 @RateLimiter：IP 维度（建议 20 次/60s，阈值常量化），对齐 content TagController /tags 的 USER 维度限流风格；suggest 为匿名接口且无 IP 限流，可被脚本刷穿直连 MySQL（经 L1 缓存后 DB 压力已大幅缓解，限流为兜底防线）
  - ✅ 2026-11-04：`SearchBizConstant` 新增 `SUGGEST_RATE_WINDOW_SECONDS=60`、`SUGGEST_IP_MAX_COUNT=20`；`SearchController#suggest` 增加 `@RateLimiter(keyType=IP)`，限流按 IP 维度，超限走 common-redis 统一拒绝口径
  - 涉及：litisle-search SearchController


##### 2.1 索引写入版本比较（防乱序覆盖）

- [x] ArticleIndexServiceImpl 写入前增加版本比较：回查 DTO 已带 publishTime（=上游 updateTime），写入前读 ES 现有文档 updateTime，小于等于现值则跳过写入；消除「发布/更新事件乱序到达 → 旧快照覆盖新快照」的最终一致窗口（现状为按 articleId 无条件 upsert 整篇覆盖）
  - ✅ 2026-11-04：新增 `IndexVersionGuard`（只按主键读 `updateTime` 的 sourceIncludes 查询）：现有版本 >= 新快照（含相等，幂等跳过）→ 跳过写入；文档不存在/任一侧 updateTime 缺失 → 照常写入（不漏同步）；读 ES 失败抛可恢复异常交 MQ 重试
  - ✅ 2026-11-04：`ArticleIndexServiceImpl` 发布路径（跳过时同步跳过切片重建）与元数据 partial update 路径均接入；`BookIndexServiceImpl`、`UserIndexServiceImpl` 按同口径跟进（清单要求的 book_idx/user_idx 一致性）
  - 涉及：litisle-search ArticleIndexServiceImpl（article_idx 优先落地，book_idx/user_idx 同口径跟进）
  - 备选方案（暂不采用）：同一 articleId 事件固定队列 + 单消费者串行消费——约束更强但牺牲吞吐，且多队列扩容时失效


#### 批次三：user 事件发送时机纪律（登记，不改代码）

##### 3.1 注册/资料更新事件发送时机纪律

- [x] 纪律登记（本条即为登记动作本身，不产生代码改动）：UserFeignProviderController#createUser 当前无 @Transactional，「先落库后发事件」成立，现有实现正确；若将来为 createUser 加事务，必须改为 afterCommit 发送（参考 litisle-content BookServiceImpl#publishBookChangedAfterCommit 写法），否则出现「事件已发、事务回滚」的脏事件导致 user_idx 脏数据
  - ✅ 2026-11-04：已登记入清单；触发时机（createUser 引入事务时）由 code review 按本条执行
  - 涉及：litisle-user UserFeignProviderController

## 板块十二：索引同步并发竞态决策记录（迁自 [temp.md](../../temp/temp.md)，2026-11-15 归档）

> **归档说明**：原样搬运自 temp.md「search 模块：索引同步并发竞态决策记录（2026-11-05）」；P0（E 并发回退 + D 下架墓碑）代码已落地，P2 条件项与 updateTime 待拍板项见 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md)（9.8 与 9.9-E 条目）。

### search 模块：索引同步并发竞态决策记录（2026-11-05）

> 背景：批次九 9.7 将消费者并发从串行提升为 4/8 后，版本守卫出现「同一文章旧快照覆盖新文档」的竞态窗口。本轮完成风险评估并定稿处置方案，代码已落地。

**一、风险评估结论**

- 同队列竞态（并发引入）：版本守卫是「先 GET 当前 updateTime → 比较 → 写入」，GET 与写入不是原子操作。并发 >1 时同一 articleId 的多条事件可被不同线程同时处理，较旧快照可能在较新快照写完之后落地，造成索引文档回退（标题/正文/标签短时陈旧）。
- 跨队列竞态（并发无关）：发布/元数据事件与下架事件位于不同队列。即使串行消费，下架删除索引后仍可能有一条早已入队的发布事件被消费，把已下架文章重新写回索引。该问题属隐私红线（已下架/私密内容不得出现在检索结果），优先级高于吞吐。
- 影响面：串行时前者不存在，后者始终存在；4/8 配置把前者变成真实窗口，收益只是吞吐，而当前量级远未成为瓶颈。

**二、方案分层与决策**

| 层级  | 方案                                    | 处置        |
| --- | ------------------------------------- | --------- |
| P0  | E：索引同步消费者并发回退为串行 1/1                  | 立即实施      |
| P0  | D：文章下架 Redis 墓碑 + 写前校验                | 立即实施      |
| P2  | A：ES external version；B：update 脚本条件判断 | 条件触发，暂不实施 |
| 不采纳 | C：按 articleId 路由到同一消费者                | 不实施       |

- P0（E）并发回退：把 `spring.rabbitmq.listener.simple.concurrency` 与 `max-concurrency` 由 4/8 降回 1/1。理由：当前量级串行吞吐有数十倍余量；上一轮 4/8 属提前消费，换来一个真实存在的写覆盖窗口，不划算。企业/社区主流在小体量索引同步任务上也默认串行消费，等积压成为真瓶颈再横向扩。恢复条件（引用下方 P2 触发条件）：积压持续 >5 分钟或峰值持续 >50 条/秒，且确认串行成为瓶颈，先落地 P2 后再提高该值。
- P0（D）下架墓碑：下架事件删除索引后写 Redis 墓碑 `search:index:tombstone:article:{id}`，TTL 10 分钟；发布（整篇 upsert）与元数据 partial update 写前先查墓碑，命中则重新回查门控。理由：这是与并发无关的跨队列竞态，串行也挡不住，且属隐私红线，优先级高于吞吐优化。
  - TTL 取值依据：消费端最多 5 次延迟重投 × 30 秒退避 ≈ 150 秒，再加处理与网络余量；10 分钟可覆盖最大重投窗口，同时避免墓碑长期残留。
  - 生效范围：只对文章生效；书籍/用户没有「下架」语义，删除即最终态。
  - 失败语义：写/查失败抛可恢复异常交 MQ 延迟重投，不静默吞掉；查询失败宁可重试也不放行（防隐私泄露）。
- P2（A+B）条件项：ES external version + update 脚本条件判断，暂不实施。二者必须配合——ES Update API 不支持 external version，只上 A 会漏掉元数据 partial update 路径，故 B（update 脚本内嵌 updateTime 条件）为 A 的必要补充，并需统一处理 409 `version_conflict_engine_exception`（旧事件跳过并 ack）。触发条件：积压持续 >5 分钟或峰值持续 >50 条/秒，且确认串行成为瓶颈后再做。
- 不采纳（C）按 id 路由同一消费者：标准 RabbitMQ 不保证 key 顺序，需一致性哈希插件或一 id 一队列，跨模块代价过高。
- 待拍板（本轮不实施）：`updateTime` 精度。当前秒级，同一秒内两次编辑会被版本守卫判为「旧」，建议后续补毫秒或引入单调递增版本号。

**三、实施落点**

- 并发：`litisle-search/src/main/resources/application.yml` → `concurrency: 1`、`max-concurrency: 1`，保留原注释并追加回退说明与恢复条件。
- 墓碑键：`SearchBizConstant.ARTICLE_TOMBSTONE_KEY_PREFIX`（`search:index:tombstone:article:`）。
- 墓碑 TTL：`SearchProperties.Index.tombstoneTtl`（默认 `Duration.ofMinutes(10)`），yml `litisle.search.index.tombstone-ttl: 10m`。
- 新增 `ArticleTombstoneService` / `ArticleTombstoneServiceImpl`：`markUnpublished(articleId)`、`exists(articleId)`，写/查失败记 error 日志并抛 `IllegalStateException`。
- `ArticleIndexServiceImpl`：`handleUnpublished` 删除索引成功后、删切片前写墓碑；`handlePublished` / `handleArticleUpdated` 在门控之后、版本比较之前查墓碑，命中则重新回查：非公开跳过（元数据路径走下架清理），仍公开则改用最新快照继续并留痕。

**四、对既有语义的影响**

- 手动 ACK、幂等 upsert、5 次延迟重投、DLQ 语义均不变。
- 新增一次 Redis 读（发布/元数据）与一次 Redis 写（下架）；Redis 故障时按可恢复异常重投，不静默吞消息。
- 墓碑命中不改变既有门控口径，只是把「跨队列旧发布事件」重新拉回门控流程，避免误伤合法的二次发布。
