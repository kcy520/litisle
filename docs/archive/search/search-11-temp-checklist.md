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
