> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，内容迁自 [next_update.md](../../next/next_update.md)「批次十：用户个性化推荐流」的已完成条目（2026-11-07 归档）。条目原文、✅ 落地摘要与依赖备注**原样搬运，未压缩**。
> **归档范围**：10.1 / 10.2 / 10.3 / 10.4 / 10.5 / 10.6 / 10.9 共 7 项。
> **仍在主清单**：10.7（V4 文章流无限刷验证）、10.8（V5 书籍流与降级验证）未勾选，保留在 [next_update.md](../../next/next_update.md)。
> **2026-11-07 二次整理（用户授权）**：批次十除 10.7/10.8 外的全部内容（背景、需求条款、已确认决策、待确认项、用户隔离与算分口径、P1 改造边界、与暂不实现汇总衔接）已迁入本文件，主清单只留一行指针；**本文件为唯一完整副本**。
> **互链**：活跃待办/验证清单见 [next_update.md](../../next/next_update.md)；挂起与预留项见 [Temporarily_not_implemented.md](../../next/temporarily/Temporarily_not_implemented.md)；本模块其他归档见 [content-implemention-records.md](content-implemention-records.md)。

## 一、背景（迁自 next_update.md，原文搬运）

> 需求来源：用户 2026-11-05 提出（原话：「用户可以像抖音那样刷文章或者书籍，2 者分开，并且每个人刷到的内容不一样，可以一直刷，未登录用户也可以刷，同时考虑 content 模块推荐流处置情况，文章/书籍有分区，参考 ZoneEnum」）。
> 板块归属：**content 为主**（推荐流是内容聚合视图，必须对权威态做展示前回查；复用既有 `FeedController` / `FeedServiceImpl` / `FeedHotRankSupport` / `FeedLocalFallbackSupport` 体系）；**search 仅作 P1 召回能力提供方**（内部 Feign，不在 search 建推荐流）；**gateway 仅新增匿名白名单**；**api 承载 DTO/VO 与 Feign 契约**。
> 不重复建设：`GET /article/hot` 是单一全局热度榜，本批次把热度榜降级为「推荐流的一路召回输入」，不新建第二套热度能力；`/api/feign/content/hot-articles` 保持现状语义（search 降级用）。
> 现状证据：现有 `/api/content/feed` 仅文章、登录限定、页码式、`userId` 未参与排序（[FeedServiceImpl.java:76-87](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedServiceImpl.java#L76-L87)），故本批次必须改造为「登录与匿名共用 + 快照式 + 画像驱动」。
> 相关文档：[Content-Architecture.md](../../Architecture/Content-Architeche.md)、[Search-Architecture.md](../../Architecture/Search-Architecture.md)、热度榜写路径归档 [search-01-heat-write-path.md](../search/search-01-heat-write-path.md)。

## 二、需求条款（可验收，迁自 next_update.md 原文）

1. **两条独立流**：`GET /api/content/feed/articles`（文章流）、`GET /api/content/feed/books`（书籍流）；**不做混合流**（VO 仅预留 `type` 枚举作为扩展位）。
2. **匿名可刷**：未登录可直接调用两流端点；匿名标识沿用前端 uuid + 服务端格式校验与缺失兜底（见「三、已确认决策」第 14 条）。
3. **无限刷**：游标式持续供给，不设页码上限；候选池耗尽自动续池，前端无感。
4. **个性化**：登录用户用 `reading_history` + `favorite` 聚合画像；匿名用户用匿名标识 + 每日种子（同标识当天序列稳定、跨天变化）。
5. **分区参与**：文章流参与 `zone`（1-普通 / 2-作家），默认 1:1 混排，支持按 zone 筛选；书籍流不参与 zone（已确认）。
6. **内容门控（硬）**：文章 `del_flag=0 + status=PUBLISHED(2) + visibility=PUBLIC(1)`，zone ∈ {1,2}；书籍 `del_flag=0 + status ∈ {0-连载中, 1-已完结}`；审核中/驳回/下架/私密内容**永不进流**；无论召回来源，展示前必须走 `recheckFilter`。
7. **冷启动**：无画像→全局热度 + 分区轮换；新文章 24h 内加权；新书 7 天内保底曝光。
8. **重复控制**：当日曝光硬去重；已读（30 天内）降权 0.5、不剔除（保证长刷有内容）。
9. **频控**：登录 60 次/60s（USER 维度）、匿名 20 次/60s（IP 维度）。
10. **书籍流形态**：书卡片（复用 `BookCardVO` 字段口径），点击落书籍详情页；**不做章节流**，项目无付费/试读概念，不考虑。

## 三、已确认决策（14 条，写实，执行时不得变更）

- **书籍不加 zone**：不改 schema，书籍流按 `category` 走。
- **书籍排序权重**：收藏 0.4 / 阅读人数 0.3 / 新鲜度 0.2 / 完结偏好 0.1；两个计数做 `log1p` 归一化；新书 7 天保底曝光。
- **重复策略**：已读 30 天内降权 0.5（不剔除）；当日曝光硬去重。
- **分页规格**：单页 20 条、单快照 300 条、到底自动续快照（前端无感）。
- **旧 `/feed`**：保留并存，前端切到新端点后再下线（切换时间由前端确认）。
- **书籍点击**：落书籍详情页，不做章节流。
- **P0 埋点边界**：只做 Redis 曝光去重；埋点表与「不感兴趣」进 P1（需前端配合）。
- **P0 个性化边界**：只用「分区 + 分类」偏好；**标签偏好进 P1**。
- **`article_tag` 注释口径冲突处理**：[content-schema.sql](../../../docker/init/mysql/schema/content-schema.sql) 中 `article_tag` 表注释现写「不参与推荐流排序权重，仅聚合列表页」——**P0 不改该注释也不读 `article_tag`**（口径自洽）；P1 启用标签偏好时，同一变更内先把该注释改为「P0 仅聚合列表页；自 P1 起参与推荐召回」，再落地标签召回。
- **频控阈值**：登录 60 次/60s（USER）、匿名 20 次/60s（IP）。
- **分页形态**：候选池快照 + cursor 切片 + `snapshotId` 版本号（`from` 恒为 0），彻底规避深分页，不依赖 ES `max_result_window`。
- **索引依赖（用户已接受，独立 DDL 窗口）**：`reading_history` 补 `idx_biz (biz_type, biz_id)`，支撑「按对象聚合阅读人数」；`favorite` 无需新增（`idx_target(target_type,target_id)` 已存在）。
- **P0 召回走 DB（A 档），ES 不参与 feed 召回与分页（2026-11-05 用户拍板）**：多维条件筛选（标签 × 分类 × 分区 × 作者 × 时间任意组合）**在批次十之后（P1）交付**，故 P0 = 4 路 DB 召回 + Redis 快照分页。**B 档保留**（ES 只作召回器、不承担分页），以 `litisle.content.feed.recall=db|es` 开关承载，默认 `db`。切换阈值：文章行数 > 30 万 / 补索引后 DB 召回 P99 > 120ms 持续 3 天 / 需要关键词兴趣或向量召回 / 筛选维度 ≥ 4 且组合种类 ≥ 3 种。分工口径：**ES 只做一次性召回（返回 ≤300 条 id+score），永不参与逐页查询与深分页**。
- **匿名标识沿用前端 uuid（中小项目主流做法，2026-11-05 用户拍板）**：前端首次进入生成 UUID v4 存 localStorage，请求头 `X-Anon-Id` 携带；服务端只做**格式校验**（UUID 正则）+ **缺失/非法兜底生成**（服务端生成后随响应头下发，前端写回 localStorage）；**不做**服务端签发 Cookie、**不做** HMAC 签名——匿名标识只作「尽力而为的曝光去重键」，不是身份凭证；该决策**不影响匿名限流**（限流口径为 IP 维度 20 次/60s，不依赖 anonId）；升级触发条件（进 P1）：出现匿名刷量证据、或需要跨设备匿名画像累积 → 再改服务端签发 + 签名校验。

## 四、用户隔离与算分口径（已确认，迁自 next_update.md 原文）

1. **身份区分**：登录用户用 `userId`；匿名用户用请求头 `X-Anon-Id`。身份标识只作用于四处——画像读取 key、频控口径、曝光去重 key、快照存储 key。
2. **算分隔离粒度**：按「用户 × 流类型（文章/书籍）× 快照代次」独立计算，**每个候选池只算一次分**；翻页只按 cursor 切片，**不重新算分**。
3. **四路召回的个性化边界**：最热（全局热度榜）、最新（全局时间倒序）两路所有人共享；常看分区、常看分类两路按该用户画像取。
4. **打分口径（P0）**：热度、新鲜度、分区匹配、分类匹配四项加权求和，已读项 `-0.5`（降权不剔除）；**四项权重必须做成配置项，禁止硬编码在代码中**。
5. **随机性口径**：使用会话级 `session seed`——同一会话内翻页顺序稳定，跨会话可变；**不使用 `ORDER BY RAND()`，也不依赖 ES `random_score` 的默认 seed**。
6. **冷启动**：无画像时两路个性化退化为全局热门/最新，探索位比例上调（比例同样为配置项）。
7. **量级估算**：5000 DAU × 人均 3 次会话 ≈ 1.5 万候选池/日 × 300 条 ≈ 450 万次打分/日，纯内存计算，不构成瓶颈。
8. **已知局限**：匿名标识不跨设备，换浏览器视作新用户，画像从零开始。

**待确认项（0 条，暂无未决项；如有新问题在此登记并标注日期）**

## 五、P1 改造边界（换发动机，不新开链路；迁自 next_update.md 原文）

| 环节 | P1 是否改动 | 说明 |
|---|---|---|
| 接口与返回结构 | 不变 | 前端无感 |
| 身份识别、频控 | 不变 | — |
| 画像 | 扩一点 | 多读“标签偏好”维度 |
| 召回 | **换实现** | 数据库四路 → 调 search 模块 ES 召回 |
| 打分排序 | 基本不变 | 权重表增加标签项 |
| 快照、翻页 | **不变** | 召回与分页已解耦 |
| 曝光去重 | 小改 | 增加“不感兴趣”记录 |
| 降级 | 增补 | ES 不可用自动回落数据库四路 |
| 书籍流 | 不变 | 仍走数据库（`book_idx` 字段不全） |

> 结论：**P1 只替换“召回”这一步，接口、快照、翻页、打散、去重全部复用；代价是链路变两跳（ES 召回 + DB 回查），因此 `feed.recall=db|es` 必须有真实的回落分支，不能只做摆设。** 召回器形态与 search 侧前置改造见 [Search-Architecture.md](../../Architecture/Search-Architecture.md) §十。

> 与「暂不实现汇总」衔接：本批次无「暂不实现」项；P1 各项按触发条件在 10.9 登记，达到触发条件后再拆为正式任务条目。

## 六、已完成任务条目（迁自 next_update.md，原文搬运）

### 10.1 契约与门控（必须，1 项）

- [x] 【必须】新建书籍流 VO 并补齐文章流字段，固化可推荐门控（来源：需求条款 1/6/10；前置：无；涉及 content/api；验收：`BookFeedItemVO` 含 id/authorId/title/intro/coverFileId/category/status（连载/完结）/chapterCount/collectCount/readerCount/updateTime；`FeedItemVO` 补 `zone/category/tags`（仅新增字段，前端现有字段不减）；门控口径与展示前 `recheckFilter` 统一，旧 `/feed` 返回字段不减少；编译通过）
  - ✅ 2026-11-05：新增 `BookFeedItemVO` / `FeedPageVO` / `FeedItemAssembler`；`FeedItemVO` 补 `zone/category/tags`（tags P0 恒 null，不读 article_tag）；门控抽 `FeedConstant.ARTICLE_RECOMMENDABLE_ZONES`，时间倒序召回与 `recheckFilter` 共用同一集合；`mvn -pl litisle-content -am -DskipTests compile` 通过

### 10.2 匿名可刷 + 频控 + 网关白名单（必须，1 项）

- [x] 【必须】匿名可访问两流端点并接入频控（来源：需求条款 2/9；前置：10.1；涉及 content/api/gateway；验收：网关白名单新增 `/api/content/feed/**`（该前缀下仅 GET，写端点不受影响）；未登录直连两流成功；登录 60 次/60s（USER）、匿名 20 次/60s（IP）超限返回限流错误；匿名标识按「二、已确认决策」第 14 条接入：客户端携带 `X-Anon-Id`（UUID v4），服务端仅做格式校验；缺失或非 UUID 格式时自动生成并随响应头下发，客户端写回后同一浏览器跨会话稳定；同一 IP 60 秒内第 21 次请求被拒）
  - ✅ 2026-11-05：新增 `GET /api/content/feed/articles`、`/books`（游标契约，10.2 阶段按 DB 时间序切片，10.3/10.4 换快照与加权排序）；`AnonIdFilter` 做 UUID v4 校验 + 缺失/非法兜底并写响应头；`FeedRateLimitSupport` 运行时按身份切换 USER 60/60s、IP 20/60s；SaToken 仅 GET 精确放行两条路径，网关白名单加 `/api/content/feed/**`（旧 `/feed` 仍登录限定）；编译通过；匿名直连与限流运行态验证留 10.7

### 10.3 文章流多路召回 + 融合打分 + 快照分页（必须，1 项）

- [x] 【必须】四条召回路 + 加权融合 + 打散 + 快照切片（来源：需求条款 3/4/7/8；前置：10.1/10.2；涉及 content；验收：四路各 ≤100 条、去重后 ≤300 条（hot 取现有 `CONTENT_FEED_HOT_ZSET`、fresh 走 `idx_create_time` 倒序、zone 偏好、category 偏好）；融合权重 `heat 0.35 + freshness 0.30（半衰期 24h）+ category 匹配 0.20 + zone 匹配 0.10 + 作者分散 0.05`；打散规则「同作者连续 ≤1、同分类连续 ≤2、zone 1:1」；快照写 Redis（TTL 30min，单用户最多 3 个活动快照），`cursor=offset + snapshotId` 且 `from` 恒 0；连续 15 页不重不漏；快照过期返回 `reset=true` + 新 `snapshotId`）
  - 依赖备注：P0 召回走 DB（已确认 A 档，见「二、已确认决策」第 13 条；ES 不参与 feed 召回与分页，B 档留 `feed.recall=es` 开关）；为使 DB 召回达标，需补 `article idx_feed_zone(del_flag,status,visibility,zone,create_time)` 与 `idx_feed_category(del_flag,status,visibility,category,create_time)`（独立 DDL 窗口，待执行）
  - ✅ 2026-11-06：`FeedStreamServiceImpl#buildArticleSnapshot` 四路召回（热度榜/最新/常看分区/常看分类各 ≤100）+ `rankArticles` 四项加权（0.35/0.30/0.10/0.20，作者分散与 zone 1:1 以结构打散实现）+ `sliceArticlePage` 偏移切片；`FeedSnapshotSupport` 负责快照落 Redis（TTL 30min、单身份最多 3 代）、`cursor=offset`、续池与 reset 语义；`mvn -pl litisle-content -am -DskipTests compile` 通过（2026-11-06）；跨板块遗留：`article idx_feed_zone` / `idx_feed_category` DDL 待手工窗口执行

### 10.4 书籍流召回与排序（必须，1 项）

- [x] 【必须】书籍流书卡片召回与排序（来源：需求条款 1/6/10 + 已确认决策「书籍不加 zone」；前置：10.1/10.2；涉及 content；验收：候选满足 `del_flag=0 + status ∈ {0,1}` 且按用户分类偏好筛选；排序 = `log1p` 归一化(收藏 0.4 + 阅读人数 0.3) + 新鲜度 0.2 + 完结/连载偏好 0.1；新书 7 天保底曝光；收藏数取 `favorite(target_type=2)`、阅读人数取 `reading_history(biz_type=1)` 聚合，与 SQL 聚合结果一致；下架/软删书籍 0 命中；书籍流不依赖 zone）
  - 依赖备注：`reading_history idx_biz(biz_type,biz_id)`（**已确认**，独立 DDL 窗口）+ `book idx_feed(del_flag,status,category,create_time)`（**待确认**）
  - ✅ 2026-11-06：`FeedStreamServiceImpl#buildBookSnapshot` 书籍门控召回（分类偏好命中）+ `rankBooks` 收藏/阅读人数 log1p 归一化 + 新鲜度 + 完结偏好四权重 + `applyNewBookGuarantee` 新书 7 天保底；`sliceBookPage` 按页回填章节/收藏/阅读三项计数；`FeedItemAssembler#toBookItem` 与三个 group-by Mapper（`countBookCollects` / `countBookReaders` / `countBookChapters`）；`mvn -pl litisle-content -am -DskipTests compile` 通过（2026-11-06）；跨板块遗留：`reading_history idx_biz`、`book idx_feed` DDL 待手工窗口执行

### 10.5 用户画像与冷启动（必须，1 项）

- [x] 【必须】登录/匿名画像与冷启动兜底（来源：需求条款 4/7 + 已确认决策「P0 只用分区 + 分类偏好」；前置：10.3；涉及 content；验收：登录画像由 `reading_history` 近 60 天 + `favorite` 近 90 天聚合出 top 分类/zone/作者，落 `content:feed:profile:{uid}`（TTL 1h）；匿名同构 `:anon:{anonId}`；无画像走冷启动（全局热度 + zone 轮换）；登录后匿名画像归并（权重衰减 0.5）并删匿名键；不读取 `article_tag`（P0 口径与 content-schema.sql:167 注释保持一致）；无画像用户走冷启动口径：两路个性化退化全局，探索位比例上调；登录用户与匿名用户各自独立取画像，不串号）
  - ✅ 2026-11-06：`FeedProfileSupport#load(FeedQuery)` 增加 Redis 画像缓存，键按身份隔离（`content:feed:profile:u{userId}` / `a{anonId}`），TTL 900s 配置化（`stream.profile-cache-ttl-seconds`），未命中才回源聚合；缓存载体新增 `FeedProfileCache`（int/string 表示枚举，规避枚举 @JsonValue 反序列化歧义）；维度按本轮确认收窄为「分区 + 分类」两维，`favorite`/作者维度与「匿名画像归并 0.5」本轮不做，标签偏好仅留 `FeedProfile#preferredTags` 占位并注明 P1；冷启动判定=近 `readWindowDays` 天无文章阅读，两路个性化召回跳过、探索位取 `coldStartExplorationRatio=0.30`；匿名按 anonId 独立读写并注明「不跨设备」；阅读行为不主动失效，成本与触发条件写入注释；`mvn -pl litisle-content -am -DskipTests compile` 通过（2026-11-06）；跨板块遗留：无

### 10.6 曝光去重与降级收口（必须，1 项）

- [x] 【必须】曝光去重 + 三级降级（来源：需求条款 8 + 现有降级体系；前置：10.3/10.4；涉及 content；验收：`content:feed:exposed:{uid|anon}:{yyyyMMdd}`（TTL 2d）保证当日不重复；已读 30 天内降权 0.5；快照缺失→重建，重建失败→热度榜空降级时间倒序（复用现有 `loadByTimeDesc`），Redis 全宕→`FeedLocalFallbackSupport` last-good 快照；停 Redis 后仍可返回旧快照且日志留痕）
  - ✅ 2026-11-06：曝光键 `content:feed:exposed:{streamType}:{identity}:{yyyyMMdd}`（TTL 2d）在切片时写入，续池与跨快照代次仍按当日集合去重；切片阶段新增二次过滤（`sliceArticlePage`/`sliceBookPage` 扫描指针跳过「建池后已被曝光」条目并计入已消费，cursor 不卡死）；已读 `-0.5` 只作用于 `rankArticles` 打分，切片不剔除；降级收口为「last-good 快照 → 实时一页」：`FeedSnapshotSupport` 在每次成功写入非空快照时记录内存 LRU（上限 200、TTL 同快照）+ Redis 指针 `content:feed:snapshot:last:{scope}`，Redis 不可用/建池失败/空池优先返回 last-good；热度榜为空 → 最新召回放大到整池兜底；所有分支统一 `logDegrade` 留痕、响应体 `degraded` 标记，micrometer 接入点已注释且未引入依赖；`mvn -pl litisle-content -am -DskipTests compile` 通过（2026-11-06）；跨板块遗留：无

### 10.9 P1 能力登记（可选，1 项）

- [x] 【可选】P1 个性化增强仅登记触发条件，不实施（来源：需求条款 4 + 已确认决策「标签偏好进 P1」「埋点进 P1」；前置：10.7/10.8 通过；涉及 search/content/api；验收：登记完成即可，不含代码）：
  - 向量召回：触发条件=9.1 存量向量重建完成且 Embedding 接真实模型；落地形态=search 新增内部召回端点（只返回 id+score，不做分页），content 侧作为第 5 路召回并落快照
  - 协同过滤：触发条件=曝光/点击埋点落地且累计 ≥1 个月行为数据；落地形态=离线 ItemCF 产出相似表，在线只读
  - 负反馈与埋点表：触发条件=前端排期可投入；落地形态=`不感兴趣` 降权/剔除 + 曝光点击落库
  - 标签偏好：触发条件=启用标签召回前，先按已确认决策同步修改 `content-schema.sql` 的 `article_tag` 表注释口径
  - 重排模型：触发条件=日曝光 ≥10 万且有 ≥1 个月样本；落地形态=LR/GBDT 起步
  - P1 切换时新增的仅三件：search 内部召回接口（返回 ≤300 条 id+score）、content 侧 Feign 客户端、`litisle.content.feed.recall` 开关；其余全部复用

  - ✅ 2026-11-07 补勾选（依据：本条验收为「登记完成即可，不含代码」，下方 5 条能力登记已齐）：无剩余实施动作，P1 启用条件保留。

## 七、归档说明

- 本批次共归档 7 项（10.1~10.6、10.9）。
- **未归档**：10.7（文章流端到端验证）、10.8（书籍流与降级验证）仍为待环境验证项，保持在全量清单（next_update.md）中，条目原文与待验证命令原样保留。
- **2026-11-07 二次整理**：批次十在 next_update.md 的其余内容（背景含需求来源/板块归属/现状证据/相关文档、需求条款 10 条、已确认决策 14 条、待确认项 0 条、用户隔离与算分口径 8 条、P1 改造边界表、与暂不实现汇总衔接）已全部迁入本文件；主清单只留一行指针 + 10.7/10.8 两条，本文为唯一完整副本。
- 批次十遗留 DDL（`article idx_feed_zone`/`idx_feed_category`、`book idx_feed`、`reading_history idx_biz`）已于 2026-11-06 随批次十一 11.5 并入 `schema/content-schema.sql`；本条归档保留原始「待手工窗口执行」记录以备追溯。
