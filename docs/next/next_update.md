# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 任务内容与落地摘要直接记录在本文档条目内，不引用 status.md 细案
- **不拆分「代码部分/验证部分」**：模块直接挂在「待办部分」下，代码任务与验证任务（V{n}）统一按 模块 → 板块/任务 组织

### 文档模版

** next_update.md模版: **

```
# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 任务内容与落地摘要直接记录在本文档条目内，不引用 status.md 细案
- 不拆分代码/验证部分：验证项（V{n}）直接挂在对应模块下，与板块任务并列

### 文档模版

（本节：书写 next_update.md 的模版说明）

### 归档与暂不实现（板块完成后的文档迁移规则）

（沿用本文档既有迁移规则章节，新增文档原样保留）

--------------------------

## 待办部分

#### {模块名}模块

> 板块划分依据：{设计文档名，如 XX_Function_Develop.md / XX-Architecture.md}；推进顺序：板块一 → 二 → ...（有依赖的板块注明前置）

##### 板块{n}：{板块名称}

###### {n}.{m} {任务短标题}

- [ ] {任务内容一句话 + 关键口径/铁律 + 涉及的表/事件/常量}
  - ✅ {yyyy-MM-dd}：{落地摘要：核心类/方法 + 关键分支决策}；跨板块遗留：{遗留项（所属板块）}；编译验证已通过（{yyyy-MM-dd}）

------------

（每个板块之间用 ------------ 分隔线隔开）

##### V{n} {验证主题}

- [ ] {验证内容}（依赖{前置板块/模块}）

-------------------------

## 暂不实现汇总

#### {模块名}模块
```

### 归档与暂不实现（板块完成后的文档迁移规则）

**相关文档职责**：

| 文档 | 职责 |
| --- | --- |
| `docs/next/next_update.md`（本文档） | 仅收纳「待办 + 待验证」的活跃任务，按 模块 → 板块/任务（含验证项 V{n}）组织 |
| `docs/next/temporarily/` | 收纳暂不实现/暂不做/挂起项（索引 Temporarily_not_implemented.md + temporarily-01~04 正文文件，2026-10-28 拆分），每条必须附触发条件 |
| `docs/archive/` | 已完成板块的实现记录归档：按模块分子目录（如 `content/`、`review/`），每目录一个同名索引页 + 按板块拆分的板块文件（如 `content/content-09-temp-checklist.md`）；日期型归档（如 `20260929-implementation-records.md`）放 archive/ 根 |

**板块完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将该板块的实现步骤/完成记录（原散落在本文档条目内）整理移入 `docs/archive/` 对应归档文档（新模块新建归档子目录 + 同名索引页，遵循上方命名惯例；已有同模块归档则追加进对应板块文件，如临时清单批次追加进 content-09-temp-checklist.md）。
2. **本文档回填**：将已完成条目从本文档删除（或标注 ✅ 与日期后移除），保持本文档只留活跃待办。
3. **挂起项转移**：板块中因宿主模块未开发而无法落地的事项（如契约已备但入口未建），移入 `docs/next/temporarily/` 对应部分文件（代码类挂起进 temporarily-01-code.md，预留/暂不实现进 temporarily-04-deferred.md），并写明触发条件，供宿主模块开发时迁回本文档。
4. **预留项登记**：板块规划中明确「暂不实现/暂不做」的事项（含触发条件与决策原因），一并移入 temporarily-04-deferred.md，不在本文档保留。

> 纪律：迁移时条目内容原样搬运（含口径修正、注意事项），不压缩关键细节；归档文档与本文档/Temporarily 清单之间用相对路径互链，保证可回溯。

--------------------------

## 待办部分

#### content模块

> 板块划分依据：Content_Function_Develop.md / Content-Architecture.md；推进顺序：板块一 → 二 → 三 → 四/五/六（可并行）→ 七 → 八（依赖 review 板块三 3-5 消费端联调）

> **板块一~八已全部完成并归档**（2026-10-20）：实现记录见 [content-implemention-records.md](../archive/content/content-implemention-records.md)（next_update 条目摘要与实现细案，原样搬运；2026-10-28 拆分为九板块文件，本页为索引）

##### V1 发布链路联调

- [ ] 发布 → review 送审 → result 回写全链路联调（依赖板块三/板块八，前置 litisle-ai chat 端点）

##### V2 可见性与缓存验证

- [ ] visibility 判定链、推荐流展示前回查验证；密码防爆破限频演练

-------------------------

#### search模块

> 板块划分依据：[Search-Architecture.md](../Architecture/Search-Architecture.md) §九 + [Search-Business_Guide.md](../Study/Search-Business_Guide.md) §四缺口清单（G1~G8）+ temp.md search模块 批次A~D + 2026-11-03 设计审查（发现 6 项重大缺陷 A1~A6）；推进顺序：批次一 → 二 → 三 → 四 → 五 → 六 → 七 → 八。
> **搜索归属核查结论（2026-11-03，精准扫描 content 控制器）**：
> - 不迁移（保留 content）：`GET /article/my`、`GET /book/my` 的 keyword 过滤（作者管理侧列表，author_id 硬收口，DB 直查合理）；`GET /tags?keyword=` 前缀搜索（tag 池为 content 域元数据，SearchTypeEnum 无 TAG，标签搜索≠全文检索）；`GET /article/list`、`/article/hot`（结构化筛选列表，无关键词）。
> - 归属 search（既定设计，`BookController` `/book/list` 注释已声明「一期不做 keyword 搜索（search 模块书籍索引为外部依赖）」）：公开文章/书籍/用户的全文与多维度检索 + 相似推荐。
> - ~~**发现的遗漏**：book_idx / user_idx 无索引同步事件来源（书籍创建/变更/完结/下架无事件、UserRegisteredEvent 为空类未发布）~~：**2026-11-04 已随批次五收口**（书籍事件 B4 + 用户事件 B5 + book_idx/user_idx 消费）。
> **优先级口径（2026-11-03 定）**：优先 content 文章与书籍相关链路（批次一~五），联想/相似/降级随后；user_idx 同步链路已于 2026-11-04 随批次五 5.3 落地（见批次五）。
> **批次A（缺陷修正定版 A1~A7）/ 批次B 契约 B1/B2/B3/B7（SearchClient、social 事件、SEARCH_* 错误码、article-updated/view-count-flushed 事件）/ 批次C 决策（C1~C4）已于 2026-11-03 完成**；批次B 余项 B6（回查字段核对）已于 2026-11-04 随批次二 2.1 收口，B4（书籍事件）/B5（用户事件）随批次五 收口。



##### 批次八：降级链路与观测（依赖批次四；对齐批次D D6）

> 已完成并归档（8.1~8.2），见 [search-09-degrade-observability.md](../archive/search/search-09-degrade-observability.md)（2026-11-05 迁出）。

##### V1 索引同步与可见性红线验证（依赖批次二/三/五）

- [ ] 四条下架路径发出 `content.unpublished` 后 article_idx 文档与 article_chunks 旧块均被移除；乱序门控（published 迟到不写回）；未过审/私密文章永不入索引；元数据编辑后索引同步生效；heat 累加-定时 bulk 链路生效（pending 键 GETDEL 清理 + 每篇每窗口至多 1 次 ES 写 + 重复消费不重复累加）

##### V2 检索功能验证（依赖批次四/五/六/七）

- [ ] 检索参数组合（tag/category/zone/时间范围）生效；空结果兜底返回且 source=fallback 标记、兜底不缓存；联想去重合并；搜索历史记录/去重/上限懒删且 keyword 未出现在任何 ES 索引文档；KNN 结果无私密/下架内容（C3 口径）

##### V3 降级验证（依赖批次八）

- [ ] 停 ES 后按 A3 方案降级生效且结果标记来源；降级关闭/降级失败返回 SEARCH_ES_UNAVAILABLE

##### 批次九：收尾、验证与遗漏补齐（依赖批次一~八；2026-11-05 复核）

> **复核口径**：按 批次1-2 / 3-4 / 5-6 / 7-8 四组逐条对照代码核查；已勾选条目未发现「勾选但代码缺失」，以下汇总未勾选验证项、只做一半的实现、过期标注与部署/体验缺口。
> 复核结论计数：批次1-2 发现 2 项、批次3-4 发现 2 项、批次5-6 发现 1 项、批次7-8 发现 6 项、content 跨模块 2 项，去重合并后见各分类清单。
> 类型说明：【必须】= 上线/联调前必须完成；【可选】= 优化项或已挂起项，按触发条件择机推进。

###### 9.1 运行态验证（必须，7 项）

- [ ] 【必须】content V1：发布 → review 送审 → result 回写全链路联调（来源：content V1；前置：litisle-ai chat 端点、review 消费链路；验收：一篇草稿从发布到审核终态，文章状态、索引文档与前端展示一致）
- [ ] 【必须】content V2：visibility 判定链、推荐流展示前回查验证 + 密码防爆破限频演练（来源：content V2；前置：content/review 联调环境；验收：PUBLIC/PRIVATE/FOLLOWERS 三档可见性正确，连续错误密码触发限频）
- [ ] 【必须】search V1：索引同步与可见性红线验证（来源：批次二/三/五 + search V1；前置：content/review/search 联调环境；验收：四条下架路径删净 article_idx 与 article_chunks、published 迟到不写回、未过审/私密永不入索引、元数据编辑同步生效、heat 累加→定时 bulk 链路生效且重复消费不重复累加）
- [ ] 【必须】search V2：检索功能验证（来源：批次四/五/六/七 + search V2；前置：批次四~七代码已编译；验收：tag/category/zone/时间范围组合生效，空结果 source=fallback 且不写缓存，联想去重合并，搜索历史去重/上限懒删且 keyword 不出现在任何 ES 文档，KNN 结果无私密/下架内容）
- [ ] 【必须】search V3：ES 降级实测（来源：批次八 8.1 + search V3；前置：content 热门端点可用；验收：停 ES 后 ARTICLE 返回 content 热门且 source=DEGRADED，BOOK/USER 空页+DEGRADED，`fallback.enabled=false` 或热门获取失败返回 SEARCH_ES_UNAVAILABLE）
- [ ] 【必须】向量存量重建（来源：批次一 1.1 跨板块遗留 + 批次七 7.1 重建框架；前置：Embedding 配置可用、管理端点可登录调用；验收：存量 article_idx `vec_dense` 与 article_chunks `chunk_vector` 非空且 1024 维，两索引 `_meta.embedding_model` 更新为真实模型名）
- [ ] 【必须】book_idx authorId 存量回填（来源：批次五 5.2 明确「存量文档不回填」；前置：书籍事件重放或全量重建；验收：检索结果 `BookDocVO.authorId` 不再为 null，抽查与全量统计均通过）

###### 9.2 代码补全（可选，4 项）

> —9.2 a/b 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（2026-11-05）。

> —9.2-c/d 已迁出至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（2026-11-05）。

###### 9.3 文档同步（可选，2 项）

> —已归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（2026-11-05）。

###### 9.4 部署配置（必须，3 项）

- [ ] 【必须】联调环境与中间件就绪（来源：批次一~八运行前置；前置：ES 8.18、Redis、RabbitMQ、MySQL、Nacos 与 content/review/user/search 服务可启动；验收：search 启动无致命错误，四索引创建成功，MQ 队列/绑定声明无冲突）
- [ ] 【必须】Embedding 配置与连通性（来源：批次七 7.1；前置：`LITISLE_EMBEDDING_API_KEY` 注入、base-url/model=bge-m3/dimension=1024 配置完成；验收：`VectorService#available()` 为 true，单条与批量调用返回 1024 维向量）

> —9.4-3 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（2026-11-05）。

###### 9.5 体验/契约（必须，1 项）

- [ ] 【必须】前端 DEGRADED 提示联调（来源：批次八 8.1「前端提示搜索不可用」；证据：`SearchServiceImpl.java:792-798` 已返回 `source=DEGRADED`，仓库内无前端代码承接；前置：search V3 通过；验收：停 ES 时前端展示「搜索暂不可用」，BOOK/USER 空页不误报；降级固定第一页、total=当页条数口径经前端确认）

###### 9.6 测试补齐（可选，1 项）

- [ ] 【可选】search 关键路径自动化测试（来源：批次二~八均仅编译验证；前置：测试环境与 Testcontainers/嵌入式 ES 方案确定；验收：检索参数组合、兜底不缓存、降级开关、KNN 强制 PUBLIC、索引版本守卫至少各有 1 条集成用例通过）

###### 9.7 低成本优化（已完成，2 项）

> —已归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（2026-11-05）。

###### 9.8 并发竞态修复与下架墓碑（已完成 2 项 + P2 条件项 1 条）

> —P0-E / P0-D 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（2026-11-05）。

> —P2 条件项已迁出至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（2026-11-05）。
- [ ] 【待拍板】`updateTime` 精度（来源：版本守卫以秒级 `updateTime` 比较；风险：同一秒内两次编辑会被判为旧）；建议后续补毫秒或单调版本号，本轮不实施。

###### 9.9 search 索引同步与可观测性（本轮仅登记，未执行）

> 编号说明：9.8 之后无 9.9，本组编号沿用批次九体例为 9.9。
> 本轮仅完成分析与登记，未改代码、未改配置、未改 DDL；条目按「先做 / 待触发 / 已决定不做」分组。
> 执行更新（2026-11-05）：A/B/C/D/F 五项已实施（E/G/H 未动），详见各条 ✅ 摘要。

> —A~D 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（2026-11-05）。

**二、待触发（条件项）**

> —E 已迁出至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)；F 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（2026-11-05）。

**三、已决定不做**

> —G/H 已决定不做，登记迁至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（2026-11-05）。

------------

##### 批次十：用户个性化推荐流（可无限刷，2026-11-05 立项）

> 需求来源：用户 2026-11-05 提出（原话：「用户可以像抖音那样刷文章或者书籍，2 者分开，并且每个人刷到的内容不一样，可以一直刷，未登录用户也可以刷，同时考虑 content 模块推荐流处置情况，文章/书籍有分区，参考 ZoneEnum」）。
> 板块归属：**content 为主**（推荐流是内容聚合视图，必须对权威态做展示前回查；复用既有 `FeedController` / `FeedServiceImpl` / `FeedHotRankSupport` / `FeedLocalFallbackSupport` 体系）；**search 仅作 P1 召回能力提供方**（内部 Feign，不在 search 建推荐流）；**gateway 仅新增匿名白名单**；**api 承载 DTO/VO 与 Feign 契约**。
> 不重复建设：`GET /article/hot` 是单一全局热度榜，本批次把热度榜降级为「推荐流的一路召回输入」，不新建第二套热度能力；`/api/feign/content/hot-articles` 保持现状语义（search 降级用）。
> 现状证据：现有 `/api/content/feed` 仅文章、登录限定、页码式、`userId` 未参与排序（[FeedServiceImpl.java:76-87](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedServiceImpl.java#L76-L87)），故本批次必须改造为「登录与匿名共用 + 快照式 + 画像驱动」。
> 相关文档：[Content-Architecture.md](../Architecture/Content-Architeche.md)、[Search-Architecture.md](../Architecture/Search-Architecture.md)、热度榜写路径归档 [search-01-heat-write-path.md](../archive/search/search-01-heat-write-path.md)。

**一、需求条款（可验收）**

1. **两条独立流**：`GET /api/content/feed/articles`（文章流）、`GET /api/content/feed/books`（书籍流）；**不做混合流**（VO 仅预留 `type` 枚举作为扩展位）。
2. **匿名可刷**：未登录可直接调用两流端点；匿名标识沿用前端 uuid + 服务端格式校验与缺失兜底（见「二、已确认决策」第 14 条）。
3. **无限刷**：游标式持续供给，不设页码上限；候选池耗尽自动续池，前端无感。
4. **个性化**：登录用户用 `reading_history` + `favorite` 聚合画像；匿名用户用匿名标识 + 每日种子（同标识当天序列稳定、跨天变化）。
5. **分区参与**：文章流参与 `zone`（1-普通 / 2-作家），默认 1:1 混排，支持按 zone 筛选；书籍流不参与 zone（已确认）。
6. **内容门控（硬）**：文章 `del_flag=0 + status=PUBLISHED(2) + visibility=PUBLIC(1)`，zone ∈ {1,2}；书籍 `del_flag=0 + status ∈ {0-连载中, 1-已完结}`；审核中/驳回/下架/私密内容**永不进流**；无论召回来源，展示前必须走 `recheckFilter`。
7. **冷启动**：无画像→全局热度 + 分区轮换；新文章 24h 内加权；新书 7 天内保底曝光。
8. **重复控制**：当日曝光硬去重；已读（30 天内）降权 0.5、不剔除（保证长刷有内容）。
9. **频控**：登录 60 次/60s（USER 维度）、匿名 20 次/60s（IP 维度）。
10. **书籍流形态**：书卡片（复用 `BookCardVO` 字段口径），点击落书籍详情页；**不做章节流**，项目无付费/试读概念，不考虑。

**二、已确认决策（14 条，写实，执行时不得变更）**

- **书籍不加 zone**：不改 schema，书籍流按 `category` 走。
- **书籍排序权重**：收藏 0.4 / 阅读人数 0.3 / 新鲜度 0.2 / 完结偏好 0.1；两个计数做 `log1p` 归一化；新书 7 天保底曝光。
- **重复策略**：已读 30 天内降权 0.5（不剔除）；当日曝光硬去重。
- **分页规格**：单页 20 条、单快照 300 条、到底自动续快照（前端无感）。
- **旧 `/feed`**：保留并存，前端切到新端点后再下线（切换时间由前端确认）。
- **书籍点击**：落书籍详情页，不做章节流。
- **P0 埋点边界**：只做 Redis 曝光去重；埋点表与「不感兴趣」进 P1（需前端配合）。
- **P0 个性化边界**：只用「分区 + 分类」偏好；**标签偏好进 P1**。
- **`article_tag` 注释口径冲突处理**：[content-schema.sql:167](../../docker/init/mysql/content-schema.sql#L167) 现写「不参与推荐流排序权重，仅聚合列表页」——**P0 不改该注释也不读 `article_tag`**（口径自洽）；P1 启用标签偏好时，同一变更内先把该注释改为「P0 仅聚合列表页；自 P1 起参与推荐召回」，再落地标签召回。
- **频控阈值**：登录 60 次/60s（USER）、匿名 20 次/60s（IP）。
- **分页形态**：候选池快照 + cursor 切片 + `snapshotId` 版本号（`from` 恒为 0），彻底规避深分页，不依赖 ES `max_result_window`。
- **索引依赖（用户已接受，独立 DDL 窗口）**：`reading_history` 补 `idx_biz (biz_type, biz_id)`，支撑「按对象聚合阅读人数」；`favorite` 无需新增（`idx_target(target_type,target_id)` 已存在）。
- **P0 召回走 DB（A 档），ES 不参与 feed 召回与分页（2026-11-05 用户拍板）**：多维条件筛选（标签 × 分类 × 分区 × 作者 × 时间任意组合）**在批次十之后（P1）交付**，故 P0 = 4 路 DB 召回 + Redis 快照分页。**B 档保留**（ES 只作召回器、不承担分页），以 `litisle.content.feed.recall=db|es` 开关承载，默认 `db`。切换阈值：文章行数 > 30 万 / 补索引后 DB 召回 P99 > 120ms 持续 3 天 / 需要关键词兴趣或向量召回 / 筛选维度 ≥ 4 且组合种类 ≥ 3 种。分工口径：**ES 只做一次性召回（返回 ≤300 条 id+score），永不参与逐页查询与深分页**。
- **匿名标识沿用前端 uuid（中小项目主流做法，2026-11-05 用户拍板）**：前端首次进入生成 UUID v4 存 localStorage，请求头 `X-Anon-Id` 携带；服务端只做**格式校验**（UUID 正则）+ **缺失/非法兜底生成**（服务端生成后随响应头下发，前端写回 localStorage）；**不做**服务端签发 Cookie、**不做** HMAC 签名——匿名标识只作「尽力而为的曝光去重键」，不是身份凭证；该决策**不影响匿名限流**（限流口径为 IP 维度 20 次/60s，不依赖 anonId）；升级触发条件（进 P1）：出现匿名刷量证据、或需要跨设备匿名画像累积 → 再改服务端签发 + 签名校验。

**三、待确认项（0 条，暂无未决项；如有新问题在此登记并标注日期）**

**四、用户隔离与算分口径（已确认）**

1. **身份区分**：登录用户用 `userId`；匿名用户用请求头 `X-Anon-Id`。身份标识只作用于四处——画像读取 key、频控口径、曝光去重 key、快照存储 key。
2. **算分隔离粒度**：按「用户 × 流类型（文章/书籍）× 快照代次」独立计算，**每个候选池只算一次分**；翻页只按 cursor 切片，**不重新算分**。
3. **四路召回的个性化边界**：最热（全局热度榜）、最新（全局时间倒序）两路所有人共享；常看分区、常看分类两路按该用户画像取。
4. **打分口径（P0）**：热度、新鲜度、分区匹配、分类匹配四项加权求和，已读项 `-0.5`（降权不剔除）；**四项权重必须做成配置项，禁止硬编码在代码中**。
5. **随机性口径**：使用会话级 `session seed`——同一会话内翻页顺序稳定，跨会话可变；**不使用 `ORDER BY RAND()`，也不依赖 ES `random_score` 的默认 seed**。
6. **冷启动**：无画像时两路个性化退化为全局热门/最新，探索位比例上调（比例同样为配置项）。
7. **量级估算**：5000 DAU × 人均 3 次会话 ≈ 1.5 万候选池/日 × 300 条 ≈ 450 万次打分/日，纯内存计算，不构成瓶颈。
8. **已知局限**：匿名标识不跨设备，换浏览器视作新用户，画像从零开始。

###### 10.1 契约与门控（必须，1 项）

- [x] 【必须】新建书籍流 VO 并补齐文章流字段，固化可推荐门控（来源：需求条款 1/6/10；前置：无；涉及 content/api；验收：`BookFeedItemVO` 含 id/authorId/title/intro/coverFileId/category/status（连载/完结）/chapterCount/collectCount/readerCount/updateTime；`FeedItemVO` 补 `zone/category/tags`（仅新增字段，前端现有字段不减）；门控口径与展示前 `recheckFilter` 统一，旧 `/feed` 返回字段不减少；编译通过）
  - ✅ 2026-11-05：新增 `BookFeedItemVO` / `FeedPageVO` / `FeedItemAssembler`；`FeedItemVO` 补 `zone/category/tags`（tags P0 恒 null，不读 article_tag）；门控抽 `FeedConstant.ARTICLE_RECOMMENDABLE_ZONES`，时间倒序召回与 `recheckFilter` 共用同一集合；`mvn -pl litisle-content -am -DskipTests compile` 通过

###### 10.2 匿名可刷 + 频控 + 网关白名单（必须，1 项）

- [x] 【必须】匿名可访问两流端点并接入频控（来源：需求条款 2/9；前置：10.1；涉及 content/api/gateway；验收：网关白名单新增 `/api/content/feed/**`（该前缀下仅 GET，写端点不受影响）；未登录直连两流成功；登录 60 次/60s（USER）、匿名 20 次/60s（IP）超限返回限流错误；匿名标识按「二、已确认决策」第 14 条接入：客户端携带 `X-Anon-Id`（UUID v4），服务端仅做格式校验；缺失或非 UUID 格式时自动生成并随响应头下发，客户端写回后同一浏览器跨会话稳定；同一 IP 60 秒内第 21 次请求被拒）
  - ✅ 2026-11-05：新增 `GET /api/content/feed/articles`、`/books`（游标契约，10.2 阶段按 DB 时间序切片，10.3/10.4 换快照与加权排序）；`AnonIdFilter` 做 UUID v4 校验 + 缺失/非法兜底并写响应头；`FeedRateLimitSupport` 运行时按身份切换 USER 60/60s、IP 20/60s；SaToken 仅 GET 精确放行两条路径，网关白名单加 `/api/content/feed/**`（旧 `/feed` 仍登录限定）；编译通过；匿名直连与限流运行态验证留 10.7

###### 10.3 文章流多路召回 + 融合打分 + 快照分页（必须，1 项）

- [ ] 【必须】四条召回路 + 加权融合 + 打散 + 快照切片（来源：需求条款 3/4/7/8；前置：10.1/10.2；涉及 content；验收：四路各 ≤100 条、去重后 ≤300 条（hot 取现有 `CONTENT_FEED_HOT_ZSET`、fresh 走 `idx_create_time` 倒序、zone 偏好、category 偏好）；融合权重 `heat 0.35 + freshness 0.30（半衰期 24h）+ category 匹配 0.20 + zone 匹配 0.10 + 作者分散 0.05`；打散规则「同作者连续 ≤1、同分类连续 ≤2、zone 1:1」；快照写 Redis（TTL 30min，单用户最多 3 个活动快照），`cursor=offset + snapshotId` 且 `from` 恒 0；连续 15 页不重不漏；快照过期返回 `reset=true` + 新 `snapshotId`）
  - 依赖备注：P0 召回走 DB（已确认 A 档，见「二、已确认决策」第 13 条；ES 不参与 feed 召回与分页，B 档留 `feed.recall=es` 开关）；为使 DB 召回达标，需补 `article idx_feed_zone(del_flag,status,visibility,zone,create_time)` 与 `idx_feed_category(del_flag,status,visibility,category,create_time)`（独立 DDL 窗口，待执行）

###### 10.4 书籍流召回与排序（必须，1 项）

- [ ] 【必须】书籍流书卡片召回与排序（来源：需求条款 1/6/10 + 已确认决策「书籍不加 zone」；前置：10.1/10.2；涉及 content；验收：候选满足 `del_flag=0 + status ∈ {0,1}` 且按用户分类偏好筛选；排序 = `log1p` 归一化(收藏 0.4 + 阅读人数 0.3) + 新鲜度 0.2 + 完结/连载偏好 0.1；新书 7 天保底曝光；收藏数取 `favorite(target_type=2)`、阅读人数取 `reading_history(biz_type=1)` 聚合，与 SQL 聚合结果一致；下架/软删书籍 0 命中；书籍流不依赖 zone）
  - 依赖备注：`reading_history idx_biz(biz_type,biz_id)`（**已确认**，独立 DDL 窗口）+ `book idx_feed(del_flag,status,category,create_time)`（**待确认**）

###### 10.5 用户画像与冷启动（必须，1 项）

- [ ] 【必须】登录/匿名画像与冷启动兜底（来源：需求条款 4/7 + 已确认决策「P0 只用分区 + 分类偏好」；前置：10.3；涉及 content；验收：登录画像由 `reading_history` 近 60 天 + `favorite` 近 90 天聚合出 top 分类/zone/作者，落 `content:feed:profile:{uid}`（TTL 1h）；匿名同构 `:anon:{anonId}`；无画像走冷启动（全局热度 + zone 轮换）；登录后匿名画像归并（权重衰减 0.5）并删匿名键；不读取 `article_tag`（P0 口径与 content-schema.sql:167 注释保持一致）；无画像用户走冷启动口径：两路个性化退化全局，探索位比例上调；登录用户与匿名用户各自独立取画像，不串号）

###### 10.6 曝光去重与降级收口（必须，1 项）

- [ ] 【必须】曝光去重 + 三级降级（来源：需求条款 8 + 现有降级体系；前置：10.3/10.4；涉及 content；验收：`content:feed:exposed:{uid|anon}:{yyyyMMdd}`（TTL 2d）保证当日不重复；已读 30 天内降权 0.5；快照缺失→重建，重建失败→热度榜空降级时间倒序（复用现有 `loadByTimeDesc`），Redis 全宕→`FeedLocalFallbackSupport` last-good 快照；停 Redis 后仍可返回旧快照且日志留痕）

###### 10.7 验证 V4：文章流无限刷（必须，1 项）

- [ ] 【必须】文章流端到端验证（来源：10.3/10.5/10.6；前置：联调环境（content/Redis/MySQL 可用）；验收：连续 15 页不重不漏、同作者/同分类打散生效、私密与下架与未过审内容 0 命中、匿名与登录序列不同、快照过期续刷正常、频控按维度触发）

###### 10.8 验证 V5：书籍流与降级（必须，1 项）

- [ ] 【必须】书籍流端到端验证（来源：10.4/10.6；前置：10.7 通过；验收：书籍门控正确（下架/软删 0 命中）、收藏与阅读人数排序与 SQL 聚合一致、新书保底曝光生效、书籍流不依赖 zone、停 Redis 后仍可刷、匿名频控触发）

###### 10.9 P1 能力登记（可选，1 项）

- [ ] 【可选】P1 个性化增强仅登记触发条件，不实施（来源：需求条款 4 + 已确认决策「标签偏好进 P1」「埋点进 P1」；前置：10.7/10.8 通过；涉及 search/content/api；验收：登记完成即可，不含代码）：
  - 向量召回：触发条件=9.1 存量向量重建完成且 Embedding 接真实模型；落地形态=search 新增内部召回端点（只返回 id+score，不做分页），content 侧作为第 5 路召回并落快照
  - 协同过滤：触发条件=曝光/点击埋点落地且累计 ≥1 个月行为数据；落地形态=离线 ItemCF 产出相似表，在线只读
  - 负反馈与埋点表：触发条件=前端排期可投入；落地形态=`不感兴趣` 降权/剔除 + 曝光点击落库
  - 标签偏好：触发条件=启用标签召回前，先按已确认决策同步修改 `content-schema.sql:167` 注释口径
  - 重排模型：触发条件=日曝光 ≥10 万且有 ≥1 个月样本；落地形态=LR/GBDT 起步
  - P1 切换时新增的仅三件：search 内部召回接口（返回 ≤300 条 id+score）、content 侧 Feign 客户端、`litisle.content.feed.recall` 开关；其余全部复用

**五、P1 改造边界（换发动机，不新开链路）**

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

> 结论：**P1 只替换“召回”这一步，接口、快照、翻页、打散、去重全部复用；代价是链路变两跳（ES 召回 + DB 回查），因此 `feed.recall=db|es` 必须有真实的回落分支，不能只做摆设。** 召回器形态与 search 侧前置改造见 [Search-Architecture.md](../Architecture/Search-Architecture.md) §十。

> 与「暂不实现汇总」衔接：本批次无「暂不实现」项；P1 各项按触发条件在 10.9 登记，达到触发条件后再拆为正式任务条目。

-------------------------

## 暂不实现汇总

#### content模块

#### search模块

- ~~user_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user_idx），此项从「暂不实现汇总」移出，原触发条件已满足
