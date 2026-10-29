> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。
> 通用纪律（适用板块一~八）：事件发送一律本地事务提交后 afterCommit；SQL 全走 #{}；所有 CAS 补 del_flag=0；CAS affected=0 一律静默跳过事件发送（不落库不发送，对齐 review publisher「落库失败不发」铁律，防免审直落并发双击/重复提交把重复事件打进 search/notification）。

## 板块七：推荐流

**落位**：`service/FeedService`、`controller/FeedController`

**任务完成概览**（迁自 next_update.md，均 2026-10-17 落地）：

- [x] 7.1 zone=3 收录与缓存
  - ✅ 2026-10-17：① zone=3 收录校验核对确认已落地（[3.3] ZoneRouter 单点收口：checkPublishPermission RECOMMEND 分支，create 步骤 A0 + update 均调用，接口未暴露给普通作者）；② FeedServiceImpl.feed 全量落地（原 TODO 壳）：快路径读缓存 → miss 后单飞锁互斥重建（tryLock 2s + 双检回填 + finally unlock）→ 空结果短缓存 60s 防穿透（恶意翻页/冷页不持续打库）→ 正常结果 TTL 基准 300s ±10% 抖动防雪崩（litisle.content.feed.{cache-ttl-seconds,page-size} 外置 yml，ContentProperties 新增 Feed 嵌套类）；未抢到锁让渡 200ms 重读、仍 miss 兑底直查库不拒绝服务；③ 回源口径：zone=RECOMMEND + status=2 + visibility=PUBLIC + del_flag=0，createTime 倒序（article 无 publish_time 列，发布时间以 createTime 代理；ai 算法重排接入后替换数据源）；④ CacheConstant 新增 CONTENT_FEED；细目见下方 [7.1]
- [x] 7.2 展示前回查
  - ✅ 2026-10-17：核对确认已随 [7.1] 落地，零新增代码——FeedServiceImpl#recheckFilter：对缓存/回源所得条目批量回查权威态（in id + status=PUBLISHED + visibility=PUBLIC + del_flag=0，仅取 id 列走主键），不满足条目即时剔除；全链路三道防线叠加：① 缓存写入侧过滤（回源 SQL 同口径）② 每次读回查兑底（缓存 TTL 内下架/转私密内容防残留曝光）③ 下架四路径 content.unpublished（板块四收口）；标签聚合列表页端点未建（未在 §3.1 矩阵），复用同口径的约定迁移至阅读侧接口实现时落地；细目见下方 [7.2]

**实现细案**（迁自 status.md）：

- [x] [7.1] 收录：zone=3 仅 ADMIN/运营可写，接口不暴露给普通作者。——核对确认已落地（2026-10-17）：[3.3] ZoneRouter.checkPublishPermission 单点收口（domain/router/ZoneRouter.java，RECOMMEND 分支拒非 ADMIN/运营），create 步骤 A0 + update 均调用；本板块零新增代码，仅核对勾选。
- [x] [7.1] 缓存三防：互斥重建（单飞锁）防击穿+空结果短缓存防穿透+TTL ±10% 抖动防雪崩（cache-ttl-seconds=300/page-size=20 外置 yml）。——已落地（2026-10-17）：FeedServiceImpl.feed 全量实现（原 TODO 壳）：① 页参兑底：pageNo≥1、pageSize 上限收口 ContentProperties.Feed.pageSize(20)（防恶意大分页）；② 快路径：命中缓存直接进回查过滤（热路径不经过锁）；③ 防击穿：miss 后单飞锁 distributedLock.tryLock(lock:content:feed:page:x:y, 2s)——抢到锁双检缓存（防重复回源）→ loadFromDb → 回填 + finally unlock；未抢到锁让渡 200ms 重读缓存，仍 miss 兑底直查库（不拒绝服务，宁多一次查库）；④ 防穿透：空结果短缓存 60s（EMPTY_CACHE_TTL_SECONDS，远短于正常 TTL），恶意翻页/冷页不持续打库；⑤ 防雪崩：正常结果 TTL 基准 ContentProperties.Feed.cacheTtlSeconds(300s) ±10% 抖动；⑥ 回源口径：zone=RECOMMEND(3) + status=PUBLISHED + visibility=PUBLIC + del_flag=0，createTime 倒序（article 表无 publish_time 列，以 createTime 代理；一期口径，ai 侧算法重排接入后替换推荐位数据源）；⑦ CacheConstant 新增 CONTENT_FEED（content:feed:page:{pageNo}:{pageSize}，值=FeedItemVO[] JSON）。
- [x] [7.2] 展示前回查：渲染前回查 status=2 且 visibility=1，已下架/转私密即时剔除（防残留曝光）；标签聚合列表页复用同口径。——已落地（2026-10-17）：FeedServiceImpl#recheckFilter：对缓存/回源所得条目批量回查权威态（in id + status=PUBLISHED + visibility=PUBLIC + del_flag=0 仅取 id 列），不满足条目即时剔除（缓存 TTL 内下架/转私密内容防残留曝光）；全链路三道防线叠加：缓存写入侧过滤（回源 SQL 同口径）+ 每次读回查兑底 + 下架四路径 content.unpublished（板块四收口）；标签聚合列表页端点未建（按标签聚合文章的阅读侧接口未在矩阵，5.1 注记同口径），本复用约定迁移至阅读侧接口实现时落地。

**验收**：缓存击穿演练通过；下架文章从推荐位即时消失。

------------

