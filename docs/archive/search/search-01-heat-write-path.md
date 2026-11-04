> **归档说明**：新建板块文件（批次9，2026-11-03 归档）——迁自 docs/temp/temp.md search 模块「批次A：重大设计缺陷修正」节（A7），正文原样搬运（含 ✅ 文档层定版登记与 📐 工程细节定版）。
> **来源**：2026-11-03 search 设计审查（缺陷 A1~A6）立项批次A；A7 代码部分（IndexHeatFlushJob/pending 键）随批次D D2 落地，升级路径已登记 temporarily-04-deferred.md search 模块节。

## 板块一：互动热度 ES 写入频率优化（A7：Redis 累加 + 定时 _bulk，2026-11-03 文档层定版；批次9 于 2026-11-03 归档）

### search模块

> 批次划分依据：2026-11-03 search 设计审查（发现 6 项重大缺陷 A1~A6），批次A 为缺陷修正/方案定版；完整模块归属核查结论与批次推进顺序（A→B→C→D）见 [temp.md](../../temp/temp.md) 待办部分 search 模块节。

#### 批次A：重大设计缺陷修正（设计文档定版，落地前必须完成；全部为 2026-11-03 审查发现）

##### A7 互动热度 ES 写入频率优化（Redis 累加 + 定时 bulk，2026-11-03 复审发现）

- [x] liked/commented/view-count-flushed 三事件消费**不直写 ES**（article_idx 带 vec_dense(1024) 约 4KB，ES partial update 实为整文档重写，爆款逐条互动消费写放大严重：千次互动 = 千次带向量重写）：search 侧 Redis 累加（`HSET search:heat:pending:{articleId}` view_cnt 绝对值覆盖 + `HINCRBY` like/comment 增量）→ IndexHeatFlushJob 定时聚合（`litisle.search.heat.flush.interval-ms` 默认 15min、可调 15~30min，fixedDelay 不堆积）→ `_bulk` 批量 partial update heat（每篇每窗口至多 1 次写）；heat 仅为排序信号精度要求低，容忍 15~30min 滞后（A5 时间衰减口径不变）；可选增强「heat 相对变化超阈值才推」一期不做仅预留；消费幂等仍按 eventId 防重复累加（HINCRBY 不可重入，MqIdempotentHelper 仍必需）；**附属既定项（非新缺陷，不另行立项）**：content `/article/hot` 缓存三防为既定设计已落地（Content-Architecture.md §三接口表 + §八-6），无需再立项；定案后同步 Architecture §2.3 索引同步流程/§五事件表/§六配置（新增 heat.flush 配置行）+ guide 板块一/板块五
  - ✅ 2026-11-03（文档层定版，代码随批次D D2 落地）：Architecture §2.3 索引同步第 5 条改为「只累加 Redis 热度待刷新键 + IndexHeatFlushJob 定时 `_bulk`」、§五 liked/commented 与 view-count-flushed 两行口径同步、§六新增 `litisle.search.heat.flush.interval-ms / initial-delay-ms` 配置行；Guide 板块一第 4 条与通俗版改写、板块五新增「热度刷写的自我保护」；跨批次遗留：IndexHeatFlushJob 与 pending 键（批次D D2）、view 分量数据源依赖 B7 发布点接入（批次B B7）
  - 📐 2026-11-03（企业主流选型复核：Redis 累加 + 定时 `_bulk` 即「排序信号类高频字段」主流解，选型不变；三处工程细节定版，配置已落地 SearchProperties.Heat.Flush + application.yml + SearchBizConstant pending 键常量）：①**收益口径分两指标**——bulk/HTTP 请求次数 167 次/s → ~0.2 次/s（≈800×，来自分片并发）与单文档重写 167 次/s → 每篇 1 次/15min（≈50×，来自窗口聚合），真实收益大头是合并段 + 减少 refresh，V1 验收按两指标分别核对；②**`_bulk` 必须分片**——单批 batch-size=1000（主流区间 500~2000）+ 并发 concurrency=2（2~4），按字节数超限自动再分批，bulk 内用 update+doc 只改 heat 相关字段、禁用 index 整篇替换（保住 vec_dense 不重写），失败批次重试后落 DLQ；③**pending 键可靠性三兜底**——TTL 默认 2h（≥2 个刷写周期，`pending-key-ttl`，正常路径 GETDEL 原子取走 TTL 仅兜底）、消费端「先累加 Redis 成功再 ACK」后置纪律（已写入批次D D2）、Redis AOF 已确认开启（compose.yaml litisle-redis `--appendonly yes`，运维项闭环）；**升级路径**（触发条件：单轮 bulk 耗时逼近刷写周期 50%，或 ES 段合并/refresh 告警频繁）——走「召回 + 精排」：ES 只做 BM25/向量召回 Top ~1000，热度排序移到应用层用 content `CONTENT_FEED_HOT_ZSET` 重排（heat 字段退化为可选，彻底移出 ES 写路径），不走侧车索引（跨索引一致性 + enrich 复杂度高，当前量级不划算）；「heat 相对变化超阈值才推」维持一期不做（只减少写次数、不改变整文档重写本质，收益小）
