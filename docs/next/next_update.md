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
- **`article_tag` 注释口径冲突处理**：[content-schema.sql](../../docker/init/mysql/schema/content-schema.sql) 中 `article_tag` 表注释现写「不参与推荐流排序权重，仅聚合列表页」——**P0 不改该注释也不读 `article_tag`**（口径自洽）；P1 启用标签偏好时，同一变更内先把该注释改为「P0 仅聚合列表页；自 P1 起参与推荐召回」，再落地标签召回。
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

- [x] 【必须】四条召回路 + 加权融合 + 打散 + 快照切片（来源：需求条款 3/4/7/8；前置：10.1/10.2；涉及 content；验收：四路各 ≤100 条、去重后 ≤300 条（hot 取现有 `CONTENT_FEED_HOT_ZSET`、fresh 走 `idx_create_time` 倒序、zone 偏好、category 偏好）；融合权重 `heat 0.35 + freshness 0.30（半衰期 24h）+ category 匹配 0.20 + zone 匹配 0.10 + 作者分散 0.05`；打散规则「同作者连续 ≤1、同分类连续 ≤2、zone 1:1」；快照写 Redis（TTL 30min，单用户最多 3 个活动快照），`cursor=offset + snapshotId` 且 `from` 恒 0；连续 15 页不重不漏；快照过期返回 `reset=true` + 新 `snapshotId`）
  - 依赖备注：P0 召回走 DB（已确认 A 档，见「二、已确认决策」第 13 条；ES 不参与 feed 召回与分页，B 档留 `feed.recall=es` 开关）；为使 DB 召回达标，需补 `article idx_feed_zone(del_flag,status,visibility,zone,create_time)` 与 `idx_feed_category(del_flag,status,visibility,category,create_time)`（独立 DDL 窗口，待执行）
  - ✅ 2026-11-06：`FeedStreamServiceImpl#buildArticleSnapshot` 四路召回（热度榜/最新/常看分区/常看分类各 ≤100）+ `rankArticles` 四项加权（0.35/0.30/0.10/0.20，作者分散与 zone 1:1 以结构打散实现）+ `sliceArticlePage` 偏移切片；`FeedSnapshotSupport` 负责快照落 Redis（TTL 30min、单身份最多 3 代）、`cursor=offset`、续池与 reset 语义；`mvn -pl litisle-content -am -DskipTests compile` 通过（2026-11-06）；跨板块遗留：`article idx_feed_zone` / `idx_feed_category` DDL 待手工窗口执行

###### 10.4 书籍流召回与排序（必须，1 项）

- [x] 【必须】书籍流书卡片召回与排序（来源：需求条款 1/6/10 + 已确认决策「书籍不加 zone」；前置：10.1/10.2；涉及 content；验收：候选满足 `del_flag=0 + status ∈ {0,1}` 且按用户分类偏好筛选；排序 = `log1p` 归一化(收藏 0.4 + 阅读人数 0.3) + 新鲜度 0.2 + 完结/连载偏好 0.1；新书 7 天保底曝光；收藏数取 `favorite(target_type=2)`、阅读人数取 `reading_history(biz_type=1)` 聚合，与 SQL 聚合结果一致；下架/软删书籍 0 命中；书籍流不依赖 zone）
  - 依赖备注：`reading_history idx_biz(biz_type,biz_id)`（**已确认**，独立 DDL 窗口）+ `book idx_feed(del_flag,status,category,create_time)`（**待确认**）
  - ✅ 2026-11-06：`FeedStreamServiceImpl#buildBookSnapshot` 书籍门控召回（分类偏好命中）+ `rankBooks` 收藏/阅读人数 log1p 归一化 + 新鲜度 + 完结偏好四权重 + `applyNewBookGuarantee` 新书 7 天保底；`sliceBookPage` 按页回填章节/收藏/阅读三项计数；`FeedItemAssembler#toBookItem` 与三个 group-by Mapper（`countBookCollects` / `countBookReaders` / `countBookChapters`）；`mvn -pl litisle-content -am -DskipTests compile` 通过（2026-11-06）；跨板块遗留：`reading_history idx_biz`、`book idx_feed` DDL 待手工窗口执行

###### 10.5 用户画像与冷启动（必须，1 项）

- [x] 【必须】登录/匿名画像与冷启动兜底（来源：需求条款 4/7 + 已确认决策「P0 只用分区 + 分类偏好」；前置：10.3；涉及 content；验收：登录画像由 `reading_history` 近 60 天 + `favorite` 近 90 天聚合出 top 分类/zone/作者，落 `content:feed:profile:{uid}`（TTL 1h）；匿名同构 `:anon:{anonId}`；无画像走冷启动（全局热度 + zone 轮换）；登录后匿名画像归并（权重衰减 0.5）并删匿名键；不读取 `article_tag`（P0 口径与 content-schema.sql:167 注释保持一致）；无画像用户走冷启动口径：两路个性化退化全局，探索位比例上调；登录用户与匿名用户各自独立取画像，不串号）
  - ✅ 2026-11-06：`FeedProfileSupport#load(FeedQuery)` 增加 Redis 画像缓存，键按身份隔离（`content:feed:profile:u{userId}` / `a{anonId}`），TTL 900s 配置化（`stream.profile-cache-ttl-seconds`），未命中才回源聚合；缓存载体新增 `FeedProfileCache`（int/string 表示枚举，规避枚举 @JsonValue 反序列化歧义）；维度按本轮确认收窄为「分区 + 分类」两维，`favorite`/作者维度与「匿名画像归并 0.5」本轮不做，标签偏好仅留 `FeedProfile#preferredTags` 占位并注明 P1；冷启动判定=近 `readWindowDays` 天无文章阅读，两路个性化召回跳过、探索位取 `coldStartExplorationRatio=0.30`；匿名按 anonId 独立读写并注明「不跨设备」；阅读行为不主动失效，成本与触发条件写入注释；`mvn -pl litisle-content -am -DskipTests compile` 通过（2026-11-06）；跨板块遗留：无

###### 10.6 曝光去重与降级收口（必须，1 项）

- [x] 【必须】曝光去重 + 三级降级（来源：需求条款 8 + 现有降级体系；前置：10.3/10.4；涉及 content；验收：`content:feed:exposed:{uid|anon}:{yyyyMMdd}`（TTL 2d）保证当日不重复；已读 30 天内降权 0.5；快照缺失→重建，重建失败→热度榜空降级时间倒序（复用现有 `loadByTimeDesc`），Redis 全宕→`FeedLocalFallbackSupport` last-good 快照；停 Redis 后仍可返回旧快照且日志留痕）
  - ✅ 2026-11-06：曝光键 `content:feed:exposed:{streamType}:{identity}:{yyyyMMdd}`（TTL 2d）在切片时写入，续池与跨快照代次仍按当日集合去重；切片阶段新增二次过滤（`sliceArticlePage`/`sliceBookPage` 扫描指针跳过「建池后已被曝光」条目并计入已消费，cursor 不卡死）；已读 `-0.5` 只作用于 `rankArticles` 打分，切片不剔除；降级收口为「last-good 快照 → 实时一页」：`FeedSnapshotSupport` 在每次成功写入非空快照时记录内存 LRU（上限 200、TTL 同快照）+ Redis 指针 `content:feed:snapshot:last:{scope}`，Redis 不可用/建池失败/空池优先返回 last-good；热度榜为空 → 最新召回放大到整池兜底；所有分支统一 `logDegrade` 留痕、响应体 `degraded` 标记，micrometer 接入点已注释且未引入依赖；`mvn -pl litisle-content -am -DskipTests compile` 通过（2026-11-06）；跨板块遗留：无

###### 10.7 验证 V4：文章流无限刷（必须，1 项）

- [ ] 【必须】文章流端到端验证（来源：10.3/10.5/10.6；前置：联调环境（content/Redis/MySQL 可用）；验收：连续 15 页不重不漏、同作者/同分类打散生效、私密与下架与未过审内容 0 命中、匿名与登录序列不同、快照过期续刷正常、频控按维度触发）
  - **待环境验证（2026-11-06，情况 B，未实测、不勾选）**：本机探测 MySQL84 服务 Stopped、Redis 6379/6380 未监听、content 8085 与 Nacos 8848 未启动。恢复环境后按下列命令逐条执行：
    - 前置：`Start-Service MySQL84,Redis`；启动 content（IDE 或 `mvn -pl litisle-content -am spring-boot:run`）；`Test-NetConnection 127.0.0.1 -Port 8085` 应为 True
    - 连续 15 页不重不漏：`$h=@{"X-Anon-Id"=[guid]::NewGuid().ToString()};$c=$null;$s=$null;$ids=@(); 1..15|%{$u="http://127.0.0.1:8085/api/content/feed/articles?pageSize=20"+$(if($c){"&cursor=$c"})+$(if($s){"&snapshotId=$s"});$r=Invoke-RestMethod $u -Headers $h;$ids+=$r.data.items.articleId;$c=$r.data.cursor;$s=$r.data.snapshotId}`；判定：`$ids` 无重复、每页 20 条、cursor 单调递增；池 300 耗尽（第 16 页）后 snapshotId 递增且 reset=false
    - 打散：首屏相邻 authorId 连续 ≤1、category 连续 ≤2；两个 zone 近似 1:1 交替
    - 门控 0 命中：取库内已有 PRIVATE/下架/未过审样本 ID（无样本则该条记「环境样本缺失」），断言不出现在 items
    - 匿名与登录序列不同：不同 `X-Anon-Id` 与登录 token（请求头 `satoken`）各取首屏，ID 集合不同
    - 快照过期续刷：`redis-cli -p 6380 -a 123 --no-auth-warning DEL "content:feed:snapshot:<scope>:<snapshotId>"` 后携旧参数请求，断言 reset=true 且返回新 snapshotId（无 redis-cli 时等待 30 分钟自然过期）
    - 频控：同一 IP 60s 内连发 21 次文章流请求（匿名阈值 20），第 21 次 body.code=429（或 HTTP 429）

###### 10.8 验证 V5：书籍流与降级（必须，1 项）

- [ ] 【必须】书籍流端到端验证（来源：10.4/10.6；前置：10.7 通过；验收：书籍门控正确（下架/软删 0 命中）、收藏与阅读人数排序与 SQL 聚合一致、新书保底曝光生效、书籍流不依赖 zone、停 Redis 后仍可刷、匿名频控触发）
  - **待环境验证（2026-11-06，情况 B，未实测、不勾选）**：环境同上不可用。恢复后执行：
    - 连续翻页：同 10.7 循环，端点改 `/api/content/feed/books`，断言 `items.id` 无重复、每页 20 条、cursor 递增
    - 门控 0 命中：断言 items 中无 `status=2`（下架）/`del_flag=1` 书籍；抽查 `SELECT id,status,del_flag FROM book WHERE id IN (<首页ID>)`
    - 计数与 SQL 一致：`SELECT book_id,COUNT(*) FROM favorite WHERE target_type=2 AND book_id IN (<首页ID>) GROUP BY book_id` 对照返回收藏数；`SELECT biz_id,COUNT(DISTINCT user_id) FROM reading_history WHERE biz_type=1 AND biz_id IN (<首页ID>) GROUP BY biz_id` 对照阅读人数
    - 新书保底：7 天内新建书籍在首屏出现条数 ≥ `new-book-quota`(6)；池内新书不足 6 条时按实际条数核
    - 不依赖 zone：`/feed/books` 无 zone 语义，带 `zoneCode=1/2` 传参结果应一致
    - 停 Redis 仍可刷：`Stop-Service Redis` 后请求，断言 items 非空、hasMore=false、degraded ∈ {last-good, redis-down}，日志出现「推荐流降级」WARN；随后 `Start-Service Redis` 恢复
    - 匿名频控：20/60s 阈值，第 21 次 body.code=429

###### 10.9 P1 能力登记（可选，1 项）

- [x] 【可选】P1 个性化增强仅登记触发条件，不实施（来源：需求条款 4 + 已确认决策「标签偏好进 P1」「埋点进 P1」；前置：10.7/10.8 通过；涉及 search/content/api；验收：登记完成即可，不含代码）：
  - 向量召回：触发条件=9.1 存量向量重建完成且 Embedding 接真实模型；落地形态=search 新增内部召回端点（只返回 id+score，不做分页），content 侧作为第 5 路召回并落快照
  - 协同过滤：触发条件=曝光/点击埋点落地且累计 ≥1 个月行为数据；落地形态=离线 ItemCF 产出相似表，在线只读
  - 负反馈与埋点表：触发条件=前端排期可投入；落地形态=`不感兴趣` 降权/剔除 + 曝光点击落库
  - 标签偏好：触发条件=启用标签召回前，先按已确认决策同步修改 `content-schema.sql` 的 `article_tag` 表注释口径
  - 重排模型：触发条件=日曝光 ≥10 万且有 ≥1 个月样本；落地形态=LR/GBDT 起步
  - P1 切换时新增的仅三件：search 内部召回接口（返回 ≤300 条 id+score）、content 侧 Feign 客户端、`litisle.content.feed.recall` 开关；其余全部复用

  - ✅ 2026-11-07 补勾选（依据：本条验收为「登记完成即可，不含代码」，下方 5 条能力登记已齐）：无剩余实施动作，P1 启用条件保留。

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

##### 批次十一：推荐互动数据与统计口径治理（2026-11-06 立项）

> 需求来源：用户 2026-11-06 基于「书籍推流互动数据现状分析」提出（原话要点）：①补齐三个不易察觉的失真与性能隐患；②参考主流方案给出修改方案；③优化历史记录方案；④收藏、点赞必须登录且校验权限；⑤文章推流同步向主流方案靠拢。
> 板块归属：**content 为主**（统计表、收藏/阅读/点赞写入、推荐打分、曝光归因）；**social 为最终归属**（点赞/评论计数最终由 social 承载，social 未落地前由 content 统计表提供真实计数，避免 `like_cnt/comment_cnt` 继续恒 0）；**api 承载契约**；**前端负责**曝光点击归因参数与点赞交互。
> 不重复建设：复用批次十两条流端点、快照、游标、曝光去重与三级降级；本批次只改「输入信号质量、统计口径、权限边界、归因闭环」，不新开推荐链路，也不回改 10.x 已勾条目。
> 与批次十边界：**11.1~11.5、11.7、11.10 是对 10.3/10.4/10.6 的补强**（10.4 的 `log1p(池内 max)`、10.6 的曝光 Set、10.5 的画像缓存均保留原实现，本批次替换其数据来源/算法）；**11.6、11.8、11.9、11.11~11.13 为新增能力**（11.9 属收藏域新增，不在 10.x 范围）。**10.7/10.8 仍按批次十旧口径验收**；本批次落地后另立 V6/V7 验证，避免同一验收对象漂移。
> 现状证据（2026-11-06 已逐文件核对）：[Book.java](../../litisle-content/src/main/java/com/litisle/litislecontent/domain/entity/Book.java#L28-L104)、[content-schema.sql](../../docker/init/mysql/schema/content-schema.sql#L35-L181)、[FeedStreamServiceImpl.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L580-L754)、[FeedProfileSupport.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedProfileSupport.java#L125-L133)、[FavoriteServiceImpl.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L30-L99)、[ReadingHistoryServiceImpl.java](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L383-L432)。
> 相关文档：[Content-Architeche.md](../Architecture/Content-Architeche.md) §3.3、[Search-Architecture.md](../Architecture/Search-Architecture.md) §10.8、[Content-Business-Guide.md](../Study/Content-Business-Guide.md) §六。

**一、现状问题清单（12 条）**

| # | 问题 | 证据（文件与行号） | 影响 | 优先级 |
| --- | --- | --- | --- | --- |
| 1 | 书籍无点赞、无浏览量字段；文章 `like_cnt/comment_cnt` 因 social 未落地恒 0 | [Book.java:28-104](../../litisle-content/src/main/java/com/litisle/litislecontent/domain/entity/Book.java#L28-L104)；[content-schema.sql:96-111](../../docker/init/mysql/schema/content-schema.sql#L96-L111)；[content-schema.sql:35-37](../../docker/init/mysql/schema/content-schema.sql#L35-L37)；[FeedHotRankSupport.java:38-45](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java#L38-L45) | 无法做「高赞召回」和点赞率权重；文章热度榜退化为纯浏览量排序 | P1 |
| 2 | **失真①**：readerCount 取 `reading_history` 去重人数，而每用户每类型只保留最近 500 条、超出懒删最旧 | [ReadingHistoryMapper.java:113-123](../../litisle-content/src/main/java/com/litisle/litislecontent/mapper/ReadingHistoryMapper.java#L113-L123)；[ReadingHistoryServiceImpl.java:383-384](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L383-L384)、[420-432](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L420-L432) | 用户继续阅读会把老书从历史中裁掉，老书 readerCount **反向下降**；推荐长期偏向新书 | P0 |
| 3 | 收藏是物理删除、无取消历史、无时间窗；且明确允许作者收藏自己作品，批次十 10.4 起 `collectCount` 把这部分计入排序 | [content-schema.sql:170-181](../../docker/init/mysql/schema/content-schema.sql#L170-L181)；[FavoriteServiceImpl.java:37-39](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L37-L39)、[90-99](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L90-L99) | 取消即丢历史；作者自收藏污染权重；无法做 30/90 天收藏率 | P0（自收藏）；取消历史已拍板不做（2026-11-06） |
| 4 | **失真②**：归一化分母取「当前池内最大收藏/最大阅读」，是池内相对归一化 | [FeedStreamServiceImpl.java:641-684](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L641-L684) | 同一本书跨池分数最多可差约 6 倍（1 收藏在 max=100 池约 0.150，在 max=1 池为 1.0）；冷启动小池会放大弱信号 | P0 |
| 5 | **失真③**：新书判定按 `create_time`，新鲜度按 `update_time`；编辑简介/挂章会刷新 `update_time` | [FeedStreamServiceImpl.java:1101-1103](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L1101-L1103)、[1112-1118](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L1112-L1118) | 老书改简介就「变新」；新书边界与新鲜度口径不一致 | P0 |
| 6 | 曝光只写当日去重 Redis Set，无曝光日志、无点击归因 | [FeedConstant.java:103](../../litisle-content/src/main/java/com/litisle/litislecontent/constant/FeedConstant.java#L103)；[FeedStreamServiceImpl.java:738-740](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L738-L740) | 算不出 CTR/CVR，推荐效果无法评估，也无法用反馈迭代 | P0 |
| 7 | `reading_history` 缺 `(biz_type,biz_id,...)` 前导索引；每翻一页重算章节/收藏/阅读三次聚合 | [content-schema.sql:131-141](../../docker/init/mysql/schema/content-schema.sql#L131-L141)；[FeedStreamServiceImpl.java:721-727](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java#L721-L727) | 翻页 DB 抖动；书量与历史行数上来后 P99 明显恶化 | P0 |
| 8 | 书籍详情页不记浏览；章节 PV 记在文章维度，书籍「被打开次数」不存在 | [BookController.java:167-170](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/BookController.java#L167-L170)、[199-205](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/BookController.java#L199-L205)；[BookReadSupport.java:46-54](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/BookReadSupport.java#L46-L54) | 曝光→阅读无法归因；书籍缺少独立浏览量口径 | P1 |
| 9 | 章节数统计含草稿/私密/下架/仅关注者可见章节 | [BookChapterMapper.java:294-311](../../litisle-content/src/main/java/com/litisle/litislecontent/mapper/BookChapterMapper.java#L294-L311)；[content-schema.sql:113-122](../../docker/init/mysql/schema/content-schema.sql#L113-L122) | 卡片章节数与详情可见章节数不一致 | P2 |
| 10 | 无完读率、阅读时长、章节进度、追读率 | [content-schema.sql:131-141](../../docker/init/mysql/schema/content-schema.sql#L131-L141) | 「打开即算阅读」，无法区分点开就走与追到最新章，内容质量不可度量 | P1 |
| 11 | 无负反馈、无刷量识别、无设备/IP 行为风控 | [FavoriteServiceImpl.java:37-39](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L37-L39) | 自互动/异常流量可直接污染计数与排序 | P1 |
| 12 | 匿名有曝光但无行为历史，登录/匿名 CTR 口径不能直接混用 | [FeedProfileSupport.java:125-133](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedProfileSupport.java#L125-L133) | CTR 分母含匿名、点击只来自登录时，转化率被系统性低估 | P1 |

> 问题→任务映射（保证每条问题都有可执行落点）：1→11.8/11.10；2→11.1/11.2/11.4；3→11.1（自收藏不计入统计）+11.9（权限复核）；4→11.3；5→11.7；6→11.6；7→11.5；8→11.1（`book_stat.view_count`）；9→11.1（`chapter_count` 口径）；10→11.13；11→11.11；12→11.12。

**二、方案（13 条：P0 8 条 / P1 5 条；文末附已确认决策 5 条）**

> 每条按「改什么 / 动哪张表、哪个类 / 要不要前端 / 风险」写实；所有新增计数字段与阈值走配置或常量，禁止散落硬编码。

**方案 1（P0，对应 11.1）计数与历史解耦**
- 改什么：新增 `article_stat` / `book_stat` 两张互动统计表，字段至少 `target_id(PK)、collect_count、reader_count、like_count、comment_count、view_count、chapter_count、stat_time、update_time`；收藏、首次阅读、点赞、浏览落库时**原子增量更新**（`count = count + 1`），取消收藏/取消点赞原子递减但 `reader_count` **只增不减**。`view_count` 口径：书籍详情/章节详情浏览记 `book_stat.view_count + 1`（沿用现有浏览去重，章节 PV 归书籍维度，不再只记文章维度）；`chapter_count` 只统计「章节对应文章未删 + 已发布 + 公开可见」的章节，由挂章/删章/文章状态与可见性变更时增量维护，回填按同一口径重算（卡片总数与详情页逐用户可见章节数存在差异，详情页保持现状逐用户判定）。写入模型（2026-11-06 已确认决策）：收藏/首次阅读/点赞/浏览在写入点**同步增量更新**，另配**每日对账任务**（源表聚合 vs 统计表，差异告警并按源表纠正）；**不引入 MQ/outbox**，避免消费延迟与重复消费补偿。
- 动哪张表/哪个类：新表 + `FavoriteServiceImpl`、`ReadingHistoryServiceImpl`（仅「首次插入」时 +1）、新 `LikeService`、浏览量刷回任务；推荐侧只读 `article_stat/book_stat`，不再从 `reading_history` 现算。
- 要不要前端：否（接口不变）。
- 风险：双写不一致 → 以源表定时对账/全量重建兜底；首次阅读判定依赖 `uk_user_biz`，并发重复插入会被唯一键挡住，增量需放在插入成功之后；回填脚本只能手工窗口执行。

**方案 2（P0，对应 11.2）推荐打分只读统计表**
- 改什么：`rankBooks` / `rankArticles` 的收藏、阅读、点赞输入全部改为读统计表；`sliceBookPage` / `sliceArticlePage` 的卡片计数也直接取统计表字段，不再每页 group by。
- 动哪张表/哪个类：`FeedStreamServiceImpl`（ranking + slice）、`FeedItemAssembler`；`FavoriteMapper#countBookCollects`、`ReadingHistoryMapper#countBookReaders` 退出推荐链路（保留给对账/管理端）。
- 要不要前端：否。
- 风险：统计表缺行时返回 0，需要「缺行按 0 处理 + 首次写入补行」；回填完成前不能切读。

**方案 3（P0，对应 11.3）计数平滑与时间窗**
- 改什么：把 `log1p(x)/log1p(池内 max)` 替换为稳定口径：率类（收藏率/阅读率/CTR）用 Wilson 下界；纯计数用贝叶斯收缩 `adj=(count+m*prior)/(1+m)`（`prior`=全站同计数中位数或均值，`m` 默认 20，配置化），再除以固定全局分位（如 P95，按日更新），**分母不再取池内最大值**。收藏/阅读另算 30/90 天窗口作为时效信号，全量作兜底；窗口查询走源表新索引。
- 动哪张表/哪个类：`FeedStreamServiceImpl#rankBooks/rankArticles`、`ContentProperties`（新增 `smoothing.m/prior-percentile/window-days`）；`FavoriteMapper`、`ReadingHistoryMapper` 增加时间窗聚合（仅构建统计/对账时用）。
- 要不要前端：否。
- 风险：先验与 `m` 取值影响排序，需要配置调参；窗口聚合会多一次查询，建议定时刷新进统计表而非每请求现算。

**方案 4（P0，对应 11.4）历史记录裁剪优化**
- 改什么：把「每用户每类型最多 500 条、超出懒删最旧」改为「**90 天时间窗保留 + 单用户上限 1000 条**」；「在读」代理口径=最近 7 天有更新且带 `chapter_id` 的书籍历史最多 10 条不裁（项目无完读标记，不能承诺真『未完成不裁』，此处用最后阅读时间代理并写注释）。
- 动哪张表/哪个类：`ReadingHistoryServiceImpl#trimOverflowHistory` 与常量；`reading_history` 不新增字段，无需迁移，旧行在下一次写入时按新规则收敛。
- 要不要前端：否。
- 风险：存储上限从 500 升到 1000（按用户×类型），需评估容量；**readerCount 不再依赖历史保留**后，裁剪只影响「我的历史」展示，不影响统计。

**方案 5（P0，对应 11.5）索引与翻页性能**
- 改什么：补 `reading_history idx_biz(biz_type,biz_id,update_time)`；统计表主键 `target_id` + 常用排序/时间列索引；翻页阶段禁止任何 group by，卡片计数一次批量 `IN` 查询统计表。
- 动哪张表/哪个类：`content-schema.sql`（手工 DDL 窗口）、`BookChapterMapper`/`FavoriteMapper`/`ReadingHistoryMapper` 的批量计数方法、`FeedStreamServiceImpl` 切片。
- 要不要前端：否。
- 风险：DDL 期间锁表/耗时（MySQL 8 online DDL 仍需窗口）；索引增加写放大，需观察 favorite/reading_history 写入 RT。

**方案 6（P0，对应 11.6）曝光计数与点击归因最小闭环**
- 改什么：在现有切片 `markExposed` 处同步 `INCR` 每日曝光计数（`content:feed:expo:{stream}:{yyyyMMdd}:{targetId}`），定时任务每 5 分钟落 `feed_exposure_daily(stat_date,stream,content_id,exposure_count,click_count)`（保留 30 天）；文章/书籍详情接口新增可选 `from=feed`、`snapshotId`，命中时 `INCR` 点击计数；CTR=`click_count/exposure_count`，按日/内容/流维度可查。
- 动哪张表/哪个类：`FeedStreamServiceImpl`（曝光计数）、`FeedExposureFlushJob`（新增）、`ArticleController`/`BookController`（点击归因）、新表 `feed_exposure_daily`。
- 要不要前端：**是**（跳详情带 `from=feed` 与 `snapshotId`）。
- 风险：曝光写 Redis 增加一次 pipeline 写，需批量/采样；点击依赖前端参数，漏传时只统计曝光不统计点击（CTR 偏低，需在报表标注）。

**方案 7（P0，对应 11.7）新鲜度口径统一**
- 改什么：P0 先零 DDL 统一为「内容首次可被推荐的时间」：书籍用 `create_time`，文章用 `create_time`（不再用 `update_time`）；新书判定与新鲜度共用同一基准字段，编辑不刷新。P1 再新增 `publish_time` 列并在首次发布/首次过审时写入，存量已发布行按 `create_time` 回填，彻底区分「草稿创建」与「发布」。
- 动哪张表/哪个类：`FeedStreamServiceImpl#freshScore/isNewBook`；P1 涉及 `article`/`book` 表与发布/过审写入点。
- 要不要前端：否。
- 风险：P0 用 `create_time` 时，长期存草稿后发布的老文章会被判「不新鲜」；P1 加列需要 DDL 与回填，需独立窗口。

**方案 8（P0，对应 11.8）点赞体系与权限**
- 改什么：新增 `user_like(user_id,target_type,target_id,create_time)`，唯一键 `uk_user_target(user_id,target_type,target_id)`；新增 `POST/DELETE /like`，**强制登录**（未登录返回统一未授权码，不混用 403），写入前走与收藏相同的目标可见性校验（私密/下架/不存在统一 NOT_FOUND）；点赞幂等，`article_stat/book_stat.like_count` 原子增减；作者自赞**硬禁止**（2026-11-06 已确认决策）：点赞人 = 内容作者时直接拒绝并返回**明确业务码**，不复用 NOT_FOUND（避免与「不可见」混淆）；原「允许自赞但仅不计入统计」的口径已被取代。
- 动哪张表/哪个类：新表 + 新 `LikeService`/`LikeController` + 统计表；`litisle-api` 加契约；social 后续接评论时复用同一统计表。
- 要不要前端：**是**（点赞按钮与状态）。
- 风险：点赞是高频写，需按 target 聚合后再落统计表防热点行；与 social 未来点赞表存在归属冲突，需在 api 层先定契约避免二次迁移。

**方案 9（P1，对应 11.9）收藏权限复核与作者自收藏排除**
- 改什么：收藏现有「登录 + 目标可见性」已满足（控制器 `@SaCheckLogin` 见 [FavoriteController.java:50-71](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/FavoriteController.java#L50-L71)，Service 目标校验见 [FavoriteServiceImpl.java:63-72](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FavoriteServiceImpl.java#L63-L72)），本项只做两件事：①**复核**收藏/取消收藏接口「必须登录 + 目标可见性」不回退（现状已满足，补测即可）；②统计写入**排除作者自收藏**（比较目标作者 `author_id`，不等于拒绝收藏）。取消收藏保持**物理删除**、**不记取消历史**、**不建 `favorite_event` 审计表**（2026-11-06 已确认决策；原「逻辑删除 / 审计表」选项已被取代）。
- 动哪张表/哪个类：`FavoriteServiceImpl`、统计表；不新增 `favorite_event`。
- 要不要前端：否。
- 风险：统计排除需要拿到目标作者 ID——现有 `checkTargetReadable` 只做校验不返回作者，落地时改为返回 `authorId` 或复用已查出的 `Article`/`Book`，否则统计写入无法判断自收藏；**原「逻辑删除与 `uk_user_target` 冲突 / 审计表写量」风险已被 2026-11-06 决策取代，不再成立**。

**方案 10（P1，对应 11.10）文章流对齐主流**
- 改什么：文章流复用同一套统计表、平滑、时间窗、曝光点击闭环；`FeedHotRankSupport` 的 `like_cnt/comment_cnt` 改读统计表（social 落地前由 content 统计表占位，不再恒 0）；文章 `stat_update_time` 继续作为热度增量灌榜时间列，不与 `update_time` 混用。
- 动哪张表/哪个类：`FeedHotRankSupport`、`FeedStreamServiceImpl#rankArticles`、`article_stat`、`feed_exposure_daily`；`article` 现有 `like_cnt/comment_cnt` 列保留但改为统计表回写（或只读统计表）。
- 要不要前端：**是**（仅点击归因与点赞按钮，曝光无感）。
- 风险：两套计数（列 vs 统计表）并存期需明确唯一真源，建议统计表为真源、列仅兼容/回写。

**方案 11（P1，对应 11.11）反作弊与负反馈**
- 改什么：先做规则层：作者自互动过滤、同设备/IP 短时间异常点赞/收藏阈值、曝光不计数异常流量；再做「不感兴趣」：新增 `dislike(user_id,target_type,target_id,reason)` 或 Redis 负反馈键，推荐侧降权/剔除。P1 登记，达到触发条件再实施。
- 动哪张表/哪个类：新 `user_dislike` 表或 Redis 键、`FeedStreamServiceImpl` 过滤/降权、风控规则类。
- 要不要前端：**是**（不感兴趣入口）。
- 风险：误杀正常用户；规则阈值需要数据基线，先影子统计再启用。

**方案 12（P1，对应 11.12）匿名统计口径**
- 改什么：明确匿名曝光计入曝光分母、匿名点击若不带身份则按设备/IP 计入单独标签；CTR 报表区分 `identity=login/anon`，不把匿名曝光与登录点击混算。匿名仍不落 `reading_history`（沿用现口径）。
- 动哪张表/哪个类：`FeedExposureFlushJob` 的维度、`feed_exposure_daily` 增 `identity_type` 列；推荐打分 P1 不直接使用匿名 CTR。
- 要不要前端：**是**（匿名点击归因沿用 `X-Anon-Id`）。
- 风险：匿名去重能力弱，CTR 仅作参考；不能据此对匿名用户做强个性化。

**方案 13（P1，对应 11.13）阅读质量信号**
- 改什么：先用零前端口径补「粗完读率」：以 `reading_history.chapter_id` 反查 `book_chapter.chapter_no`，配合 `book_stat.chapter_count` 得出「读到第几章 / 总章数」；追读率=当日读过该书、次日仍读同一书的登录用户占比。阅读时长需前端心跳上报，作为 P1 可选子项，不在 P0 引入。所有信号先只落统计与报表，**不进入 P0 排序权重**，观察分布后再定。
- 动哪张表/哪个类：`reading_history`（只读）、`book_chapter`（章节序号）、`book_stat` 扩展 `finish_user_count/avg_progress` 或新 `book_read_quality` 表、阅读上报接口（时长子项需新增字段）。
- 要不要前端：**是**（仅时长心跳需要；粗完读率与追读率可先零前端推算）。
- 风险：无时长/无完读标记时，「读到最新章」只是代理口径，不能等同真实读完；心跳上报会增加写量，需采样与批量落库。

**已确认决策（5 条，2026-11-06 拍板）**

1. **统计表写入：同步增量 + 每日对账**（2026-11-06 拍板）。收藏、首次阅读、点赞、浏览在各自写入点同步增量更新 `article_stat/book_stat`；另配每日对账任务（源表聚合 vs 统计表，差异告警并按源表纠正）；**不引入 MQ/outbox**，避免消费延迟与重复消费补偿。落地见 11.1。
2. **取消收藏：保持物理删除**（2026-11-06 拍板）。不记取消历史、不建 `favorite_event` 审计表；11.9 收敛为「复核登录 + 目标可见性（现状已满足，补测即可）」与「统计排除作者自收藏」两件事，保持 **P1，不虚升 P0**。
3. **作者自赞：硬禁止**（2026-11-06 拍板）。11.8 验收要求三件事同时满足：①必须登录；②目标必须可见；③点赞人 ≠ 作者本人（返回明确业务码），不是「自赞允许但不计入统计」。
4. **CTR 采用中小项目方案**（2026-11-06 拍板）。原问题「是否接受前端改造做点击归因」现决定：接受，但按中小项目口径——**不做埋点 SDK、不做 Kafka、不做离线数仓**；采用「跳转 URL 带来源参数 `from=feed&snapshotId`（可选 `position`）+ 服务端按日聚合 `feed_exposure_daily`」的最小闭环，按天看趋势。落地见 11.6。
5. **匿名曝光/点击进入 CTR 分母，但单独打 `identity_type=anon`**（2026-11-06 拍板）。登录 CTR 只看登录口径，不混算。**前置约束**：点击侧必须稳定携带同一个 `X-Anon-Id`，否则匿名点击漏记会让比率系统性偏低；该约束作为 11.6/11.12 的验收前置。落地见 11.12。

**三、待确认项（0 条，暂无未决项；如有新问题在此登记并标注日期）**

> 原 2 条（点击归因、匿名 CTR 分母）已于 2026-11-06 转为已确认决策 4/5，原文要点见上，本节不再保留未决条目。

**四、任务项（17 项：P0 11 项 / P1 6 项；已完成 8 项（11.1/11.2/11.3/11.4/11.5/11.7/11.8/11.15），11.14 代码完成待端到端，11.6/11.12 代码+SQL 验证完成待端到端，其余未勾选）**

- [x] 【P0｜前端配合：否】11.1 新建互动统计表并接入增量写（来源：问题 1/2/3；前置：无；涉及 content；验收：`article_stat`/`book_stat` 建成，收藏/首次阅读/点赞各写入点增量正确，取消收藏递减、读者数只增不减，回填后与源表 SQL 聚合一致；写入模型为同步增量 + 每日对账，不引入 MQ/outbox；DDL 与回填均在手工窗口执行；回填脚本/基线/分批窗口见 11.15，对账口径与指标见 11.16，本项不得省略这两步；2026-11-06 已在 litisle-mysql/litisle_content 建表，收藏同事务增减、首次阅读 +1、点赞入口、浏览量同步均已接入）
- [x] 【P0｜前端配合：否】11.2 推荐打分与卡片计数改读统计表（来源：问题 2/7；前置：11.1；涉及 content；验收：`rankBooks/rankArticles` 与 `sliceBookPage/sliceArticlePage` 不再调用 `countBookCollects/countBookReaders`，缺行按 0，翻页无 group by；`countBookCollects/countBookReaders/countBookChapters` 三个方法**保留并标注 `@Deprecated`**（禁止删除，留给对账/管理端复用））
  - ✅ 2026-11-06：`FeedStreamServiceImpl` 的 `rankArticles/rankBooks` 与 `sliceArticlePage/sliceBookPage/realtime*/lastGood*` 计数全部改读 `article_stat/book_stat`；新增 `loadArticleStats/loadBookStats`（按 ID 集合 `selectBatchIds` 一次批量查询，缺行按 0）；翻页路径已无任何 `countBook*` 调用与 group by；三个旧方法已加 `@Deprecated` + 保留说明；挂章/移除章节同步 `book_stat.chapter_count`；编译通过（应用未启动，单页聚合=0 以静态调用链核验）
- [x] 【P0｜前端配合：否】11.3 计数平滑与时间窗（来源：问题 4；前置：11.1/11.2；涉及 content；验收：同一本书在不同候选池的分数波动 < 5%，新书不再因 max=1 池被放大到满分，收藏/阅读 30/90 天窗口可配置，权重与 `m/prior` 全配置化）
  - ✅ 2026-11-06：新增 `content_stat_window`（DDL 已并入 `schema/content-schema.sql` 尾部「批次十一补充表」段；原 `zz-content-stat-window.sql` 已于 2026-11-06 合并后删除），按「目标类型+目标ID+窗口天数」唯一存 30/90 天收藏数与阅读人数；`ContentStatWindowJob` 每日全量重算（`fixedDelay` 24h、开关/窗口天数/周期全在 `litisle.content.stat.window`），翻页只按 ID 集合一次批量读，查询期零聚合。
  - ✅ 2026-11-06：书籍排序的 `log1p(x)/log1p(池内最大值)` 改为贝叶斯平滑 `(x+m*prior)/(x+m)`（`m` 默认 5、`prior` 默认 0.1，配置项 `litisle.content.feed.stream.smoothing.*`）；文章热度极值归一化改为池无关饱和 `heat/(heat+midpoint)`（中点默认 1.0）。实测同一本书 6102 在两池中得分均为 0.275（波动 0%），旧口径 0.5417→0.8000（波动 47.7%）；小池 max=1 时旧口径收藏/阅读均被放大到 1.0，新口径为 0.25。
  - ✅ 2026-11-06：窗口值回退链为「短窗口(30d) → 长窗口(90d) → 统计表累计值」；窗口任务关闭时直接用累计值。窗口阅读人数受阅读历史 90 天裁剪约束，超过 retain-days 的窗口会偏小（yml 注释已写明约束）。
- [x] 【P0｜前端配合：否】11.4 历史裁剪策略优化（来源：问题 2；前置：11.1；涉及 content；验收：裁剪改为 90 天 + 1000 条上限 + 在读代理保留 10 条，超限懒删；readerCount 与裁剪完全解耦，模拟删历史后统计不变；裁剪 SQL 由 offset 式改为「90 天条件 + 1000 上限 + 在读 10 条豁免」，`HISTORY_MAX_PER_USER` 改 1000 并同步常量注释与三处行内注释（保留常量，不删除）；90 天/1000/10 三个阈值全部配置化；超 90 天记录不再出现在 `pageMyHistory` 结果，落地前需确认产品预期）
  - ✅ 2026-11-06：`ReadingHistoryServiceImpl.trimOverflowHistory` 改为读 `litisle.content.history.*`（`trim-enabled`/`retain-days=90`/`max-per-user=1000`/`keep-in-progress=10`/`trim-batch-size=100`）；`ReadingHistoryMapper.deleteOverflowOldest` 改为「90 天时间窗 ∨ 超出 1000 条」为删除候选、最近 10 条在读书籍历史豁免，单批 100 行、最旧优先；`HISTORY_MAX_PER_USER` 改 1000 并保留常量作为配置默认值。
  - ✅ 2026-11-06：在读判定仅用现有字段：取书籍最大可见章节号（`book_chapter` join `article`，未删+已发布+公开），历史最后章节号小于该值即未读完；`chapter_id` 空/章节已删/无可见章节时保守保留。局限：读者读完最后一章后书又新增章节会重新变为在读；不加埋点。
  - ✅ 2026-11-06：事务内实测裁剪 SQL（用户 2003，时间窗置为未来强制过期）：书籍删 1 行、在读 630102 被保留；文章删 2 行；回滚后历史仍 5 行。`pageMyHistory` 的排序（update_time desc, id desc）与去重（`uk_user_biz`）不变，条数会因 90 天窗口/1000 上限减少——产品侧需确认「超 90 天历史不再展示」的预期。模拟删除 `biz_type=1` 全部历史后 `book_stat.reader_count` 保持 `6101=2,6102=1` 不变（已解耦）。
- [x] 【P0｜前端配合：否】11.5 索引补齐与翻页去聚合（来源：问题 7；前置：11.1；涉及 content/MySQL；验收：`reading_history idx_biz(biz_type,biz_id,update_time)` 生效（EXPLAIN 命中），翻页只做统计表批量查询，单页聚合次数为 0；DDL 手工窗口执行；与批次十遗留 4 个索引（`article idx_feed_zone`/`idx_feed_category`、`book idx_feed`、`reading_history idx_biz`）合并同一窗口，`idx_biz` 直接建三列版、不重复建两列版；执行顺序见「五、执行注意项」第 2 条）
  - ✅ 2026-11-06：四个索引（`article idx_feed_zone`/`idx_feed_category`、`book idx_feed`、`reading_history idx_biz` 三列版）已直接写入 `schema/content-schema.sql` 的建表语句；原 `zz-content-feed-index.sql`（存储过程 + `information_schema` 幂等外壳）已于 2026-11-06 合并后删除。整理前已在 `litisle-mysql`/`litisle_content` 执行，二次执行四个索引全部 skip；EXPLAIN 结果见当次回报（`idx_biz`/`idx_feed` 命中，文章索引数据量太小被优化器选为 `idx_visibility`，`force index` 可命中且分类索引走反向扫描）
- [x] 【P0｜前端配合：是】11.6 曝光计数与点击归因最小闭环（来源：问题 6；前置：11.1；涉及 content/api/前端；验收：按中小项目口径（跳转 URL 参数 + 日聚合表 `feed_exposure_daily`，不做埋点 SDK/Kafka/离线数仓，不做 trace/session 归因）——曝光按日/内容/流计数（保留 30 天）；详情带 `from=feed&snapshotId`（可选 `position`）记点击；未带归因参数时只计曝光、不计点击；可按天算出 `click_count/exposure_count`；`feed_exposure_daily` 需唯一键 `(stat_date,stream,content_id,identity_type)` 与 30 天过期清理任务；匿名曝光可被无限刷，CTR 只作趋势参考，不得直接进 P0 排序）
  - ⏳ 2026-11-06（代码与 SQL/Redis 已验证，HTTP 端到端待环境，暂不勾选）：新建 `feed_exposure_daily`（DDL 已并入 `schema/content-schema.sql` 尾部「批次十一补充表」段；原 `zz-feed-exposure.sql` 已于 2026-11-06 合并后删除），唯一键 `uk_feed_exposure_daily(stat_date,stream,target_id,identity_type)`；曝光在切片 `markExposed` 之后按页一条多行 upsert（`on duplicate key update exposure_count=exposure_count+1`），同身份同日同条只计 1 次仍由既有 Redis 曝光 Set 保证，未另建去重键。
  - ⏳ 2026-11-06：点击归因接入 `GET /api/content/article/{id}` 与 `GET /api/content/book/{id}`：`from=feed` + `snapshotId` 必须同时携带才计点击，`position` 可选（服务端计数不需要）；登录按 `userId`，匿名按 `X-Anon-Id`（匿名详情不经过 `AnonIdFilter`，Support 内直接校验 UUID v4，缺失即不计，避免临时生成新身份灌大 CTR）；同日同条用新增 Redis Set `content:feed:clicked:{stream}:{identity}:{yyyyMMdd}` 去重，且点击前校验当日曝光 Set 成员，保证 `click_count<=exposure_count`。
  - ⏳ 2026-11-06：`snapshotId` 不落库——计数维度是「日期+流+内容+身份口径」，不含快照代次，且同身份同日同条点击已去重，落库只会每行多存一段无用长字符串；仅作归因「必带」校验。
  - ⏳ 2026-11-06：清理选择「定时分批物理删除」而非表分区（日表数据量小、分区需持续维护 DDL 且唯一键必须含分区列，收益不成比例）；每日按 `litisle.content.feed.exposure.retain-days=30` 执行 `delete ... where stat_date < cutoff limit 1000` 循环。曝光/点击写失败只记 WARN + Counter（`litisle_content_feed_exposure_write_fail` / `litisle_content_feed_click_write_fail`），不阻断翻页与详情返回。
  - ⏳ 2026-11-06：实测（Redis 6380 AUTH 123）：曝光 SADD 首次 `:1`、重复 `:0`；点击 SADD 首次 `:1`、重复 `:0`（测试键已 DEL）。SQL 实测（种子文章 5001）：曝光 1→2（两个登录身份）、点击 1、重复点击被 Set 拦截后仍 1、匿名行 1/1；登录 CTR=1/2=0.5000、全站 CTR=2/3=0.6667、分母 0 返回 NULL；40 天前测试行被清理 SQL 删除，测试行已清理为 0。应用未启动，HTTP 端到端与指标暴露待环境。
  - ✅ 2026-11-07 状态回填（依据：「六、环境验证清单」第 7 项（11.6/11.12 曝光点击，✅ 实测）；应用启动见第 5 项）：曝光按日/流/内容增长、登录与匿名分行、带 `from=feed`+`snapshotId` 才 +1 点击、重复点击不增、CTR 两口径均实测通过。条目内「应用未启动，端到端待环境」为回填前记录，保留不动。
- [x] 【P0｜前端配合：否】11.7 新鲜度与新书口径统一（来源：问题 5；前置：无；涉及 content；验收：推荐新鲜度与新书判定共用同一时间字段，编辑简介/挂章不再改变排序；P0 使用 `create_time`，P1 再评估 `publish_time` 列）
  - ✅ 2026-11-06：`rankBooks` 新鲜度改用 `create_time`（移除 `update_time` 优先逻辑），与 `isNewBook` 共用同一字段；`freshScore` 入参更名 `createTime` 并注释说明不用 `update_time` 的原因（编辑简介/挂章会刷新，不代表内容新鲜度）；回滚造数验证：`create_time` 为两天前时分值 0.25，改 `update_time` 后仍 0.25（按旧口径会跳到 1），验证后已回滚
- [x] 【P0｜前端配合：是】11.8 点赞体系与权限校验（来源：问题 1/11 + 用户要求「点赞必须登录且有权限」；前置：11.1；涉及 content/api/前端；验收：三件事同时满足——①必须登录（未登录返回统一未授权码）；②目标必须可见（私密/下架/不存在返回 NOT_FOUND）；③点赞人 ≠ 作者本人（作者自赞返回明确业务码）；点赞/取消幂等且统计表计数正确；点赞接口沿用 `@RateLimiter` 60s 频控（新增 `LIKE_MAX_COUNT`，对齐收藏 20/60s）；计数用原子自增、递减用 `GREATEST(cnt-1,0)` 或 `where cnt>0`，并发唯一键冲突按幂等成功处理；点赞记录与计数同一事务；作者自赞新增独立业务码（不复用 NOT_FOUND）；点赞/取消点赞均需同步 search 增量（见 11.14））
  - ✅ 2026-11-06：新建 `user_like`（DDL 已并入 `schema/content-schema.sql`；原 `zz-user-like.sql` 已于 2026-11-06 合并后删除），唯一键 `uk_user_target(user_id,target_type,target_id)`；取消点赞物理删除，`delete` 影响行数 > 0 才递减。
  - ✅ 2026-11-06：接口 `POST/DELETE /api/content/like`，`@SaCheckLogin` 统一未授权码；新增业务码 `LIKE_TARGET_NOT_VISIBLE(40004)`（文章/书籍不存在、未发布、私密、下架统一返回，不混用 403）与 `LIKE_SELF_FORBIDDEN(40005)`（作者自赞硬禁止）；频控为 `LikeRateLimitSupport`（单用户维度、yml `litisle.content.like.rate-limit-*` 可调，复用 Redis 固定窗口脚本；注解阈值无法配置化故未用注解）。
  - ✅ 2026-11-06：幂等实测——首次点赞 `user_like` 1 行、`article_stat.like_count` 15→16；重复插入命中 `uk_user_target` 报 1062（应用捕获视为成功且不重复加）；取消后删 1 行、计数回 15；重复取消删 0 行不递减；`GREATEST` 下限实测 0-1 仍为 0；书籍点赞 6101 实测 `book_stat.like_count` 0→1→0。
  - ✅ 2026-11-06：文章点赞同事务同步 `article.like_cnt` 镜像（详情页 VO、`getArticleIndexDoc` 建索引、11.16 对账仍读该列），三处计数同步推进；编译通过（应用未启动，HTTP 接口端到端待环境）。
- [x] 【P1｜前端配合：否】11.9 收藏权限复核与作者自收藏排除（来源：问题 3 + 用户要求「收藏必须登录且有权限」；前置：11.1；涉及 content；验收：**验证项（现状已满足，补测即可）**——①收藏接口 `@SaCheckLogin` + 目标可见性校验不回退；取消收藏接口 `@SaCheckLogin` + 仅能删除本人收藏（按 `user_id` 条件删除）；②统计写入排除作者自收藏；取消收藏保持物理删除、不记取消历史（2026-11-06 决策）；保持 P1，不虚升 P0）
  - ✅ 2026-11-06 权限复核（逐点看过，无退化）：`POST /favorite` 与 `DELETE /favorite` 都保留 `@SaCheckLogin` + `@RateLimiter(count=FAVORITE_MAX_COUNT=20, 60s, USER)`；`favorite()` 先 `checkTargetReadable` 再 insert（文章判定链=未删 → 本人放行 → 非本人须已发布 → PUBLIC 放行/FOLLOWERS 回查关注/PRIVATE 拒；书籍判定链=未删 → 下架仅作者可收藏）；`unfavorite()` 删除条件为 `eq(userId, 当前登录用户)`，不存在越权删除他人的分支。取消历史仍为物理删除（沿用 2026-11-06 决策）。
  - ✅ 2026-11-06 排除作者自收藏（实质改动）：`checkTargetReadable` 改为返回目标作者 ID——**文章取 `article.author_id`、书籍取 `book.author_id`**（两处来源不同，均在 `FavoriteServiceImpl` 内取，不跨模块拼装）；收藏成功后仅当 `authorId != userId` 才 `increaseCollect`；取消前用 `resolveTargetAuthor` 按同一来源取作者，`deleted > 0` 且非作者才 `decreaseCollect`，**保证「收藏没加、取消也不减」对称**；目标行被物理删除时 `resolveTargetAuthor` 返回 null，按「非本人」照常递减（保留旧行为，偏差交给对账暴露，不静默吞掉一次递减）。
  - ✅ 2026-11-06 回填与窗口口径（原 `zz-content-stat.sql` 已于 2026-11-06 合并后删除；存量回填不再单列脚本，新库改由 `seed/content-seed.sql` 写入自洽统计基线）：原回填脚本步骤 1/3 的收藏聚合加 `f.user_id <> coalesce(a.author_id, 0)` / `<> coalesce(b.author_id, 0)`（left join 兼容目标行已不存在的历史收藏，仍按非本人计数）；`ContentStatWindowMapper` 的 30/90 天窗口聚合同样排除作者自收藏——否则窗口值会计入、累计值不计入，推荐排序（优先读窗口）与卡片展示（读累计）会打架。
  - ✅ 2026-11-06 对账同步（11.16）：`ContentStatReconcileMapper` 4 条收藏查询（文章/书籍 × 源表有 + 统计残留）的源值全部改为同口径过滤，**改后差异应为 0**；「只有作者自收藏」的目标源值视为无记录、stat 应为 0，若写入侧漏判会由 statOnly 查询（`collect_count <> 0`）报出来。对账任务仅在源表聚合侧过滤，不修改历史数据。
  - ⏳ 2026-11-06 实测（**未执行**：接续时 Docker Desktop 未运行，启动后引擎随即退出、3307 短暂可连又断开，`docker.exe ps` 报 npipe 不存在；未改动任何容器与数据）：待环境恢复后按「六、环境验证清单」第 4 条跑——作者自收藏 → `article_stat.collect_count` 不动；普通用户收藏 → +1；两次取消 → 0 / 回到原值；书籍同验。SQL 口径已静态核对（`left join` 作者比较、`GREATEST` 下限），未取得数字前不勾选。
  - ✅ 2026-11-07 状态回填（依据：「六、环境验证清单」第 4 项（11.9 收藏，✅ 实测））：作者自收藏 `favorite`+1 但 stat 不动、普通用户收藏 0→1、作者取消不扣、普通取消 1→0；书籍 6101 作者自收藏后 `book_stat.collect_count` 仍为 2；30/90 天窗口复算与对账差异 0。条目内「未执行」为回填前记录，保留不动。
- [x] 【P1｜前端配合：是】11.10 文章流对齐统计与闭环（来源：用户要求「文章推流也参考主流」；前置：11.1~11.8；涉及 content/api/前端；验收：文章流与书籍流共用统计表/平滑/曝光点击模型，`FeedHotRankSupport` 的点赞/评论取真实统计值，不再恒 0；`stat_update_time` 口径不变）
  - 2026-11-06 落地（静态+SQL+jshell 实测，应用未启动故端到端待环境 ⏳）：排序统计量统一改读 `article_stat`（收藏取 30/90 天窗口、缺失回退累计），`article.like_cnt` 明确为详情展示镜像列、仅保留索引用途；文章/书籍共用 `FeedScoreSupport` 的 `(x+m*prior)/(x+m)`（m=5/prior=0.1）与同一套 yml 参数；文章权重拆为 collect/like/comment/view/fresh/zone/category（默认 0.12/0.12/0.08/0.03/0.30/0.10/0.20，拆分理由见 `ContentProperties.ArticleWeights` 注释）；`FeedHotRankSupport` 与 `FeedStreamServiceImpl` 均调用 `FeedScoreSupport#computeArticleScore`，仅候选范围/画像命中不同；时间衰减、已读降权 -0.5、探索位、打散规则未改。实测：5004 点赞 30→0 分数 0.245196→0.152624、位次 2→4；5001 点赞 15→0 分数 0.302586→0.221586、位次 1→2；单篇池与全场池 5003 均为 0.089000（diff=0）；热门榜与推荐流冷启动对 5001~5005 五篇分数 diff 全为 0。
- [~] 【P1｜前端配合：是】11.11 反作弊与负反馈登记（部分完成）（来源：问题 11；前置：11.6 有曝光点击数据；涉及 content/api/前端；验收：登记作者自互动过滤、异常频次规则、「不感兴趣」入口与降权口径；达到触发条件后另拆实施项）
  - ⏳ 2026-11-06（负反馈代码与 SQL 已验证，HTTP 端到端待环境，暂不勾选）：新建 `feed_feedback`（DDL 已并入 [content-schema.sql](../../docker/init/mysql/schema/content-schema.sql)；原 `zz-feed-feedback.sql` 已于 2026-11-06 合并后删除）。三类维度：1-不感兴趣（单条内容，`target_id`=article.id）/2-屏蔽作者（`target_id`=authorId）/3-屏蔽分类（`target_key`=分类名、`target_id`=0）；`stream` 列把文章流与书籍流的名单隔开（默认 `article`），避免同名 ID 串用。唯一键 `uk_user_type_stream_target(user_id, feedback_type, stream, target_id, target_key)` 防重复提交，提交走 `insert ignore`、重复提交按幂等成功返回。
  - ⏳ 2026-11-06：接口 `POST /api/content/feed/feedback`（提交）与 `DELETE /api/content/feed/feedback`（撤销），同一 DTO；两者 `@SaCheckLogin`，**必须登录**——匿名只有 `X-Anon-Id`，浏览器清缓存即换身份，做不了长期屏蔽，故匿名提交直接拒绝（统一未授权码）；频控沿用既有风格 `@RateLimiter(count=FEEDBACK_MAX_COUNT=20, timeUnit=SECONDS=60, keyType=USER)`，与收藏/点赞同档。**提供撤销**：误点概率高、按五元组 DELETE 成本极低，且撤销幂等（首次删 1 行、重复删 0 行），不额外记录撤销历史。
  - ⏳ 2026-11-06：召回过滤落点——屏蔽作者/分类在**建池阶段**（`buildArticleSnapshot`/`buildBookSnapshot`）生效；单条「不感兴趣」在建池 + **切片阶段**（`sliceArticlePage`/`sliceBookPage`）双重过滤，当前快照内立即剔除。名单缓存为按用户单键 `content:feed:feedback:{stream}:u{userId}`（TTL `cache-ttl-seconds=300`、查询窗口 `window-days=30`），变更时只失效该用户该流键，**不做全量 evict**。
  - ⏳ 2026-11-06：缓存一致性实际表现——①单条内容屏蔽：切片时二次过滤，用户**当页起**就不再看到该条；②作者/分类屏蔽：只在建池生效，旧快照已经建好的剩余页里可能仍出现被屏蔽内容，用户下一次刷（翻到底生成下一代快照，或快照 TTL 过期后重建）即完全消失；③不做主动全量失效，避免一次屏蔽清空所有用户快照的缓存雪崩。④Redis 不可用时的 `last-good` 降级路径未追加屏蔽过滤（降级只保证可用性，不在 Redis 故障时再打一次 DB）。
  - ⏳ 2026-11-06：阅读时长**只做预留不实现**——`reading_history.duration_ms` 已并入 [content-schema.sql](../../docker/init/mysql/schema/content-schema.sql) 的 `reading_history` 建表语句（原 `zz-reading-duration-reserve.sql` 的 `information_schema` + `prepare` 幂等外壳仅用于存量库，已于 2026-11-06 合并后删除），上报接口 `PUT /api/content/reading-history` 新增可选 `durationMs` 入参并显式忽略；有效心跳口径（间隔/有效判定/聚合方式）留到前端方案确定后再定，不凭空定义。
  - ⏳ 2026-11-06：本轮只做负反馈登记；「作者自互动过滤」「异常频次规则」等反作弊项**未做**，仍按原验收要求另拆实施项。
  - ⏳ 2026-11-06：实测（测试用户 9999）：4 次 insert（含 1 次重复的「内容 5003」）落 3 行，唯一键生效；内容 5003 屏蔽后候选剩 5001/5002/5004/5005；作者 2002 屏蔽后候选为空（种子文章作者均为 2002）；分类「小说」屏蔽后剩 5002/5003/5004；DELETE 首次 1 行、重复 0 行。测试行已清理（`feed_feedback` 计数 0）。附注：种子 5001~5005 的 `article.category` 在库内为双重编码（`hex=C3A5C2B0…`，按 latin1→utf8mb4 解码后才是「小说/散文/现代诗/随笔」），分类过滤实测用解码后的值比较；该问题属存量种子数据，未修改数据。
  - 🔶 2026-11-07 状态回填为**部分完成**（依据：12.1 收口实测；范围见本条最后一个 ⏳ 子项「本轮只做负反馈登记」）：已完成负反馈三维度提交/撤销/幂等、当页剔除与下一代快照剔除（含 last-good 兜底过滤）；未做「作者自互动过滤」「异常频次规则」——属原设计明确不展开、另拆实施项，不是遗漏。
- [x] 【P1｜前端配合：是】11.12 匿名统计口径（来源：问题 12；前置：11.6；涉及 content/前端；验收：执行 2026-11-06 决策第 5 条——`feed_exposure_daily` 增 `identity_type(login|anon)`，匿名与登录分开统计，登录 CTR 不含匿名；前置约束：点击侧必须稳定带同一个 `X-Anon-Id`，否则匿名点击漏记会导致比率偏低；匿名仍不落阅读历史）
  - ⏳ 2026-11-06（代码与 SQL 已验证，HTTP/报表端到端待环境，暂不勾选）：`identity_type` 落库为 `login|anon`；登录 CTR = 仅 `identity_type='login'` 的 `click/exposure`，全站 CTR 去掉该条件（含匿名）。两条 SQL 收口在 `FeedExposureDailyMapper.selectCtrByTarget/selectDailyCtr`，由 `identityType` 传 `login` 或 `null` 区分口径，报表按日期区间直查。
  - ⏳ 2026-11-06：不做在线实时聚合、也不建第二张汇总结果表——`feed_exposure_daily` 本身就是日聚合结果，报表直查即可；再加汇总表只会多一份重复数据与一个同步点。分母为 0 时 CTR 返回 `NULL`（表示没有分母），不是 `0`（表示有曝光但无人点击）。
  - ⏳ 2026-11-06：指标只新增「曝光写失败」「点击写失败」两个 Counter（标签 `stream`），不为 CTR 本身建 Gauge。
  - ✅ 2026-11-07 状态回填（依据：「六、环境验证清单」第 7 项）：`identity_type=login|anon` 分行落库，登录 CTR 不含匿名（anon 0.1667 / login 0.2000），分母 0 返回 NULL，均已实测。
- [x] 【P1｜前端配合：是】11.13 阅读质量信号（来源：问题 10；前置：11.1/11.4；涉及 content/前端；验收：用 `reading_history.chapter_id` + `book_chapter.chapter_no` 可算出粗完读率与追读率并落统计，时长心跳作为 P1 可选子项；信号先只做统计展示，不改 P0 排序权重，缺上报时按「读到最新章」代理并标注口径）
  - ⏳ 2026-11-06（代码与 SQL 已验证，应用未启动，定时任务实跑待环境，暂不勾选）：新建只读统计表 `book_read_quality`（DDL 已并入 [content-schema.sql](../../docker/init/mysql/schema/content-schema.sql)；原 `zz-book-read-quality.sql` 已于 2026-11-06 合并后删除），`book_id` 为主键，**同表存分子 `finish_reader_count` 与分母 `eligible_reader_count`**。分母不用 `book_stat.reader_count`（累计只增不减，与 `reading_history` 的 90 天裁剪不同源，两者相除会让比率长期偏低），而用「最后一次阅读章节能解析到该书公开可见章节的读者数」，与分子同源、比率可直接计算。
  - ⏳ 2026-11-06：判定口径——`reading_history` 实际只有 `chapter_id`（**没有 `chapter_no` 列**），章节号经 `chapter_id join book_chapter` 取得；某用户对某书最后阅读章节的 `chapter_no` ≥ 该书**最大可见章节号**即近似判为「追上进度」。可见章节只算 `article.visibility=1（公开）` + `status=2（已发布）` + `del_flag=0`。
  - ⏳ 2026-11-06：三个已知误差写在 [BookReadQuality](../../litisle-content/src/main/java/com/litisle/litislecontent/domain/entity/BookReadQuality.java) 类注释，不藏在心里：①**打开即算阅读**——点开最后一章就离开会被判完读（假阳性）；②**书更新后回退**——新章节出现时，原本完读的用户变回未完成（假阴性）；③**只读最后一章**——只翻最后一章的用户历史上会被判完读。另补两条边界：追平后回看中间章会重判未完成（历史只存最后章节）、`visibility=3（仅关注者）`章节需社交关系，纯 SQL 不做个性化判定，统一按公开章节算。
  - ⏳ 2026-11-06：更新方式为**按日全量重算**（[BookReadQualityJob](../../litisle-content/src/main/java/com/litisle/litislecontent/job/BookReadQualityJob.java)，`litisle.content.stat.read-quality.interval-ms=86400000`、`initial-delay-ms=600000`、`enabled` 可关；分布式锁 + 单条 `insert … on duplicate key update` 一次落表），**不选增量**：新章发布会把已完读用户改判未完成、历史裁剪会缩小分母，增量感知不到这类反向变化；`reading_history` 有 90 天上限、单次扫描量可控。
  - ⏳ 2026-11-06：**建议暂不进排序，先做只读统计**（与「避免一次改两个变量」的判断一致）：本轮若同时把新指标塞进书籍四项权重，分数漂移将无法区分是权重改动还是指标引入导致，归因不了。启用时机：积累一段时间后先看 `finish_reader_count/eligible_reader_count` 的分布与稳定性（确认代理口径与实际完读大体相符），再单独提权重校准变更，届时给出改动前后分数对照。
  - ⏳ 2026-11-06：实测（`upsert` SQL 已执行）：6101 `finish_reader_count=1`、`eligible_reader_count=1`、比率 `1.0000`（用户 2001 最后读第 2 章＝该书最大公开章；第 3 章 `visibility=2` 私有，不计入分母，用户 2003 恰好停在私有第 3 章故不进 6101 分母）；6102 `finish_reader_count=0`、`eligible_reader_count=1`、比率 `0.0000`（用户 2003 读第 2 章，该书 3 章全公开）；6103~6105 无有效历史，`0/0`、比率为 `NULL`（无分母，不是 0）。
  - ✅ 2026-11-07 状态回填（依据：「六、环境验证清单」第 9 项）：`BookReadQualityJob` 实跑 rows=10，6101=1/1、6102=0/1、6103~6105=0/0(NULL)，重复执行不放大。
- [x] 【P0｜前端配合：否】11.14 互动计数跨模块同步（来源：审查缺口 1：点赞上线后搜索结果页与详情页数字不一致；前置：11.1/11.8；涉及 content/search/api；验收：点赞/取消点赞后 `article_idx.like_cnt` 与详情页、推荐流一致；search 现只有 [QUEUE_SOCIAL_LIKED](../../litisle-search/src/main/java/com/litisle/litislesearch/mq/HeatEventConsumer.java#L67-L90) 的 +1 消费、无取消点赞队列，必须新增负增量或绝对值事件；[getArticleIndexDoc](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ContentInternalServiceImpl.java#L320-L322) 与 [FeedHotRankSupport](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java#L308) 的计数来源统一：以 `article_stat` 为真源，或保留 `article.like_cnt/comment_cnt` 作镜像并同事务刷新 `stat_update_time`；`book_idx` 无计数字段，不同步（见注意项第 9 条））
  - ⏳ 2026-11-06（代码与静态链路完成，端到端待环境，暂不勾选）：content 侧 `LikeEventSupport` 在事务提交后（`TransactionSynchronization.afterCommit`）经 outbox 发布 `social.liked`（delta=+1）/ `social.unliked`（delta=-1）；契约层新增 `ROUTING_SOCIAL_UNLIKED=social.unliked`、`QUEUE_SOCIAL_UNLIKED=litisle.social.unliked.queue` 与 `SocialUnlikedEvent`；`SocialLikedEvent` 增加 `delta` 字段（旧消息缺失按 +1 兜底）。
  - ⏳ 2026-11-06：search 侧选择「新增取消点赞监听」而非在同一队列按 delta 区分——正负事件分队列便于分别观测/重投，且复用既有 `MqIdempotentHelper`（SETNX+eventId）、`MqAckHelper`（延迟重投）、`IndexDlqConsumer`（死信）链路，未另建基础设施；`HeatPendingService.applyLikeDelta(articleId, delta)` 统一正负累加；`IndexHeatFlushJob` 写回 `article_idx.likeCnt` 时 `Math.max(0, 现有值 + delta)` 做下限保护。
  - ⏳ 2026-11-06：书籍点赞不改索引——`BookIndexDoc` 确无 `likeCnt` 字段，书籍点赞只写 `book_stat.like_count` 并在类注释写明理由；文章点赞同时同事务刷新 `article.like_cnt` 镜像，保证详情页/首次建索引/对账与 `article_stat` 一致。三模块编译通过；端到端未验证（应用未启动，ES 中尚无 `article_idx` 索引），`user_like` 与 `article_stat` 已用种子数据实测一致。
  - ✅ 2026-11-07 状态回填（依据：「六、环境验证清单」第 6 项）：u2003 点赞后 stat 15→16、`article.like_cnt` 同步、search 消费 `social.liked` 后 ES `article_idx.like_cnt`=16；取消后 16→15、ES=15 不为负；重复取消无副作用。
- [x] 【P0｜前端配合：否】11.15 统计表存量回填（来源：审查缺口 2：建表后存量从哪来；前置：11.1；涉及 content/MySQL；验收：回填脚本按 `favorite`→`collect_count`、`reading_history`→`reader_count`、`article.view_cnt`→`view_count`、`book_chapter`→`chapter_count`、`like/comment` 回填 0（social 未落地无真值）；基线=回填开始时刻，回填期间写入走同步增量、完成后才切读；分批提交（单批 ≤1000 行）避免长事务；进度日志可查；注明 `reading_history` 已按 500 条裁剪，回填的 `reader_count` 是「现存历史基线」，此后只增不减，老书数值会低于真实历史累计，报表需标注；原回填脚本 `docker/init/mysql/zz-content-stat.sql`（幂等 `ON DUPLICATE KEY UPDATE + GREATEST`，1000 条/批，2026-11-06 已执行且重复执行行数不放大）已于 2026-11-06 合并进 schema 后删除；新库的统计基线由 `seed/content-seed.sql` 提供）
- [x] 【P0｜前端配合：否】11.16 统计对账任务与指标（来源：审查缺口 2/8：对账口径未细化；前置：11.1/11.15；涉及 content；验收：每日对账按源表聚合 vs 统计表；差异阈值写清（建议绝对差 ≥10 或相对差 ≥5% 告警），`reader_count` 只增不减故「源表小于统计表」不纠正；差异告警/自动纠正/差异日志落点明确；对账与回填进度复用 [ContentMetrics](../../litisle-content/src/main/java/com/litisle/litislecontent/metrics/ContentMetrics.java#L78-L116) 新增指标；对账失败不得阻断业务写入；2026-11-06 已完成代码与配置（`ContentStatReconcileJob` + 6 类差异查询 + `ContentMetrics` 指标 + `diff-rows-threshold` 配置），对账 SQL 已手工实测差异 0，定时任务实跑待应用启动确认）
  - ✅ 2026-11-07 状态回填（依据：12.2 收口实测）：首轮 total=0 且 6 类 gauge 均 0.0；扰动 `article.like_cnt` 后 total=1 触发 ERROR，恢复后回 0，0 值可观测与阈值告警均有效。
- [x] 【P1｜前端配合：否】11.17 文档与验收联动（来源：审查缺口 9；前置：11.14~11.16；涉及 docs；验收：[Content-Business-Guide.md](../Study/Content-Business-Guide.md#L552-L582) 补点赞接口/登录与可见性权限/错误码（现 §六 仍写 「social 未落地 like 恒 0」）；[content-index.md](../index/content-index.md#L15) 表清单由 10 表增补 `article_stat`/`book_stat`/`user_like`/`feed_exposure_daily`，并补 REST 接口、MQ 拓扑、错误码；把 V6/V7 验收草案补入本文档（草案见「五、执行注意项」第 14 条））
  - ✅ 2026-11-06 [Content-Business-Guide](../Study/Content-Business-Guide.md)：§四 收藏补「作者可收藏自己作品但统计不计」口径（文章/书籍作者来源不同）；§六 末尾新增「互动统计与推荐信号」整节——统计真源（stat 表真源、实体列镜像）、点赞接口与幂等、曝光点击 CTR 分身份、负反馈三维度与生效位置、粗完读口径与三条已知误差、三条「待确认」（90 天以上历史展示、分类双重编码、chapter_count 作者视角差异）。已知偏差（last-good 不带屏蔽过滤、曝光 Set 并发双计）按原样写入，未抹平。
  - ✅ 2026-11-06 [Content-Architeche](../Architecture/Content-Architeche.md)：§四 数据模型由 10 表扩到 17 表，补 `article_stat`/`book_stat`/`content_stat_window`/`user_like`/`feed_exposure_daily`/`feed_feedback`/`book_read_quality` 的键与设计原因，并新增「统计真源口径 + 写入点一览」（收藏排除作者自收藏、窗口任务、完读任务、曝光点击、对账同口径）；§三 接口设计补 `/like`、`/feed/feedback` 与 `/article/{id}` 的点击归因副作用；§3.1 限流矩阵补 `LIKE_MAX_COUNT`、`FEEDBACK_MAX_COUNT`。
  - ✅ 2026-11-06 [content-index](../index/content-index.md)：§四 REST 补 `/like`、`/feed/feedback` 与详情点击归因；§六 Service 补 `LikeService`/`FeedFeedbackService`/`FeedFeedbackSupport`，`FavoriteService` 标注 11.9 改动；§七 数据模型 10→17 表；§八 MQ 补 `social.liked`/`social.unliked` 与其余 5 个发送方法；§九 配置补 like/feed.feedback/stat.window/stat.reconcile/stat.read-quality/history；§十 限流补两条；§十二 Redis 键补负反馈名单键、点赞频控键、完读锁键；§十三 定时任务 5→9；§十四 导航补统计真源/负反馈/点赞/完读四条；§十五 指标补对账差异、曝光/点击写失败。
  - ✅ 2026-11-06 [Search-Architecture](../Architecture/Search-Architecture.md) §十一：前端清单第 7 条由「P1 未启动」改为「后端已就绪，待前端」（三维度入口/撤销自存五元组/后端不提供名单查询接口标注为待确认），新增第 8 条「阅读时长心跳：仅预留接口」；处理约定补「单条当页生效、作者/分类下代生效」「负反馈不可匿名」「完读无需前端埋点」。
  - ✅ 2026-11-06 本文档（next_update.md）：11.9/11.17 标注落地项与 ⏳ 项；批次十一末尾新增「六、环境验证清单」，按依赖顺序 11 步写「跑什么 → 预期看到什么」。
  - ⏳ 2026-11-06：文档为静态更新，无需运行验证；其中引用的接口数字与错误码待应用启动后按环境验证清单逐条复核，若与实现不符再回改。

  - ✅ 2026-11-07 状态回填（依据：本条四个文档子项均已 ✅；末项明确「文档为静态更新，无需运行验证」）：Content-Business-Guide、Content-Architeche、content-index、Search-Architecture 与本文档均已更新，无剩余文档动作。

**五、执行注意项（审查补充，2026-11-06；不属于单条任务验收，执行时必须遵守）**

> 本节只写「跨任务、易踩、且不写进某一条验收就会漏」的约束；新增任务见 11.14~11.17。

1. **DDL 风格对齐既有表**：`create table if not exists`、`datetime`（不用 `datetime(3)`）、`bigint unsigned` / `tinyint`、`engine=InnoDB`、`default charset = utf8mb4`、索引 `idx_*`、唯一键 `uk_*`（依据 [content-schema.sql](../../docker/init/mysql/schema/content-schema.sql) 的 article/book 建表段与 reading_history 段）。
2. **执行顺序（一个窗口内）**：①建 `article_stat`/`book_stat`/`user_like`/`feed_exposure_daily` → ②分批回填（11.15）→ ③切换读统计表（11.2）→ ④统一补索引（批次十遗留 4 个 + 本批 `idx_biz(biz_type,biz_id,update_time)`）。索引最后建，避免回填期写放大；`idx_biz` 只建三列版，不重复建批次十的两列版；回填 SQL 分批提交，禁长事务锁表。
3. **计数原子性与下限**：一律 `update ... set cnt = cnt + 1`（禁止读改写）；递减用 `set cnt = greatest(cnt-1,0)` 或带 `where cnt > 0`；以影响行数判断成功，取消收藏/取消点赞重复调用按幂等成功（删 0 行不递减）。
4. **首次阅读判定**：只在 insert 成功后 `reader_count + 1`；`DuplicateKeyException` 转更新分支时不得加计数（依据 [ReadingHistoryServiceImpl.java:374-392](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L374-L392)）。
5. **唯一键冲突**：收藏 `uk_user_target`、点赞 `uk_user_target` 冲突一律按幂等成功处理，不抛 500。
6. **事务边界**：收藏/点赞记录行与统计计数同一事务，任一步失败一起回滚；search 同步事件在事务提交后发送，避免回滚后事件已发出。
7. **真源口径**：`article_stat`/`book_stat` 为真源；`article.like_cnt/comment_cnt` 仅作既有读接口的镜像（[FeedItemAssembler.java:38](../../litisle-content/src/main/java/com/litisle/litislecontent/support/FeedItemAssembler.java#L38)、[ArticleQuerySupport.java:214](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/ArticleQuerySupport.java#L214)、[ContentFeignProviderController.java:74](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/feign/ContentFeignProviderController.java#L74) 均读列），镜像与 `stat_update_time` 必须同事务刷新，否则热度榜增量轮捞不到变更（[FeedHotRankSupport.java:46-50](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java#L46-L50)）。
8. **搜索同步**：[IndexHeatFlushJob](../../litisle-search/src/main/java/com/litisle/litislesearch/job/IndexHeatFlushJob.java#L248-L318) 只认 Redis pending 增量；pending 由 `HeatEventConsumer` 消费 `QUEUE_SOCIAL_LIKED` 写入。content 当前没有点赞事件生产者，也没有取消点赞队列 → 点赞与取消点赞都必须发事件，否则 `article_idx` 只增不减、heat 虚高。
9. **书籍索引不同步**：[BookIndexDoc](../../litisle-search/src/main/java/com/litisle/litislesearch/domain/entity/BookIndexDoc.java#L25-L49) 只有 7 个基础字段、无计数，`book_idx` 不需要计数同步；若后续搜索页要展示收藏/阅读数，另立任务。
10. **历史裁剪的产品影响**：90 天裁剪会让超 90 天记录从 [pageMyHistory](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java#L181-L205) 结果消失；现有懒删 SQL 只有 offset/limit（[ReadingHistoryMapper.java:92-102](../../litisle-content/src/main/java/com/litisle/litislecontent/mapper/ReadingHistoryMapper.java#L92-L102)），需换新 SQL；`HISTORY_MAX_PER_USER=500`（[ContentLimitConstant.java:70-72](../../litisle-content/src/main/java/com/litisle/litislecontent/constant/ContentLimitConstant.java#L70-L72)）改 1000 并同步 383/420-421/430-432 三处注释（保留常量，不删除）。
11. **频控与防刷**：收藏已有 20/60s（[FavoriteController.java:48-49](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/FavoriteController.java#L48-L49)、[62-63](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/FavoriteController.java#L62-L63)），点赞必须新增 `LIKE_MAX_COUNT` 同款注解；匿名曝光可无限刷（当日仅按 `X-Anon-Id` 去重），CTR 只作趋势、不进 P0 排序；`snapshotId` 不可信，不得因伪造点击参数抬高计数。
12. **配置化清单**：平滑 `m`/先验分位、30/90 天窗口、90 天/1000 条/在读 10 条、曝光保留 30 天，全部挂 `ContentProperties`（[ContentProperties.java:64-124](../../litisle-content/src/main/java/com/litisle/litislecontent/config/ContentProperties.java#L64-L124) 的 `Feed`/`Stream` 或新增嵌套）并提供 yml 默认值；禁止散落业务常量。
13. **可观测**：`ContentMetrics` 现仅 4 个入口（[ContentMetrics.java:78/92/105/116](../../litisle-content/src/main/java/com/litisle/litislecontent/metrics/ContentMetrics.java#L78-L116)），需新增对账差异、回填进度、计数异常指标；取不到 `MeterRegistry` 时按现有 `ObjectProvider` 静默空转，不阻断主流程。
14. **V6/V7 验收草案**：V6=统计一致性与权限（对账差异在阈值内、连点取消计数不为负、未登录点赞/收藏返回 10002、自赞返回独立业务码、不可见目标返回 NOT_FOUND）；V7=曝光点击闭环（曝光/点击/取消点赞均能按日聚合、未带 `from=feed&snapshotId` 只计曝光、登录与匿名分开统计）；实施完成后正式补入本文档。
15. **判定无需处理（避免重复提问）**：①`book_idx` 计数——无字段（[BookIndexDoc.java:25-49](../../litisle-search/src/main/java/com/litisle/litislesearch/domain/entity/BookIndexDoc.java#L25-L49)）；②content 既有单测——仅 `LitisleContentApplicationTests` 冒烟，改 500 条逻辑不会打破旧断言，但需新增裁剪单测；③浏览量→search 链路——[ViewCountFlushJob.java:124](../../litisle-content/src/main/java/com/litisle/litislecontent/job/ViewCountFlushJob.java#L124) 已发 `ContentViewCountFlushedEvent`，无需重建；④收藏频控——[ContentLimitConstant.java:33-34](../../litisle-content/src/main/java/com/litisle/litislecontent/constant/ContentLimitConstant.java#L33-L34) 已存在，11.9 无需新增；⑤匿名曝光去重键——已有当日 Set，本批只改 CTR 口径，不改去重键。

> 与「暂不实现汇总」衔接：本批次无「暂不实现」项；P1 项按触发条件推进，不满足条件时保留登记不实施。
> 验证口径：10.7/10.8 仍按批次十旧口径通过；本批次落地后新增 V6（统计一致性与权限）与 V7（曝光点击闭环）验证，草案见「五、执行注意项」第 14 条，实施完成后正式补入本文档。

**六、环境验证清单（批次十一收口用，2026-11-06）**

> 背景：本轮全部代码与 DDL 均为「静态可验证」范围内完成，应用未启动；2026-11-06 接续时 Docker Desktop 未运行（启动后引擎随即退出），3307 不可用，故 11.9 的造数实测未做。下列 11 步按依赖顺序排列，**前一步不通过不要跳到下一步**；每一步都写清「跑什么 → 预期看到什么」。
> 标记：✅ = 已手动验证；⏳ = 待环境。

| # | 要跑什么 | 预期看到什么 | 状态 |
| --- | --- | --- | --- |
| 1 | `docker ps`：确认 mysql(3307)/redis(6379)/rabbitmq(5672)/elasticsearch(9200) 四个容器都在运行；`mysql -h127.0.0.1 -P3307 -uroot -pverysecret -e "select 1"` 通 | 四容器均 healthy，`select 1` 返回 1 | ✅ 2026-11-06 19:4x 实测：12 个容器 Up（mysql/elasticsearch healthy），`select 1` 返回 1 |
| 2 | 按序手动执行幂等脚本：`zz-user-like.sql` → `zz-content-stat.sql`（含回填）→ `zz-content-stat-window.sql` → `zz-feed-exposure.sql` → `zz-feed-feedback.sql` → `zz-book-read-quality.sql` → `zz-reading-duration-reserve.sql`；执行完再整套重跑一次 | 首次全部 `Query OK`；第二次仍 `Query OK` 且表行数不变（幂等）；`reading_history` 有 `duration_ms` 列 | ✅ 两轮共 18 次执行全部 exit=0；行数稳定（article_stat 9、book_stat 5、content_stat_window 26→52 即 30/90 两窗口各 26、book_read_quality 5）；**附注（2026-11-06 整理）**：上述 7 个 `zz-` 脚本的表结构内容已合并进 `schema/content-schema.sql` 并删除，本行为整理前实测记录，新库初始化不再需要手动执行补丁 |
| 3 | 回填复核（11.15）：跑完后抽查 `article_stat.collect_count` / `book_stat.collect_count` 与「排除作者自收藏」的聚合值 | 两者一致；若先前已有作者自收藏，回填值**低于** `favorite` 总行数（差值等于作者自收藏条数） | ✅ 实测：文章 5003 仅作者 2002 自收藏时 `article_stat.collect_count=0`；书籍 6101 有作者自收藏（作者 2002）时 `book_stat.collect_count=2`（favorite 聚合 3 行，差值=1 条作者行） |
| 4 | **11.9 实测**：用作者账号收藏自己的文章/书籍，再用普通账号收藏同一目标；各取消一次 | 作者自收藏：`favorite` 表 +1 行、`article_stat.collect_count` / `book_stat.collect_count` **不动**；普通收藏：计数 **+1**；作者取消：计数**不动**；普通取消：计数**回退 1**（不会出现「收藏没加、取消扣 1」） | ✅ 实测（article 5003，作者 2002）：作者自收藏 → favorite+1(id17)、stat 仍 **0**；普通用户 2003 收藏 → stat **0→1**；作者取消 → stat 仍 **1**；普通取消 → stat **1→0**。书籍 6101：作者自收藏 → `book_stat.collect_count` 仍 **2**。`content_stat_window` 30/90 天按任务同 SQL 复算：6101 collect=2（排除作者行）、5003=0（仅作者自收藏），对账差异 0 |
| 5 | 启动 content 应用，看启动日志 | 无 Mapper/Bean 装配异常；`FeedHotRankRefillJob`/`ContentStatWindowJob`/`ContentStatReconcileJob`/`FeedExposureCleanupJob`/`BookReadQualityJob` 等 9 个任务注册成功；`/actuator/health` UP | ✅ 19:35 重启后 `/actuator/health`=UP；`job/` 下 9 个 `@Scheduled` 类全部装载，`ContentStatReconcileJob`/`ContentStatWindowJob`/`BookReadQualityJob`/`FeedExposureCleanupJob` 有实跑日志，其余 4 个（热度灌榜、浏览刷回、发件箱重发、定时发布）空载时不打日志、无法单独取证 |
| 6 | 11.8/11.14 点赞闭环：点赞 → 取消 → 再点一次；看 MQ 与 ES | `article_stat.like_count` +1/−1/+1；`article.like_cnt` 镜像同步；`social.liked`/`social.unliked` 各一条消息；ES `article_idx.likeCnt` 与详情页一致（负数不会出现） | ✅ 实测（article 5001，作者 2002，基线 like_count=15 / like_cnt=15）：u2003 点赞 → user_like 1 行、stat **15→16**、`article.like_cnt` **15→16**、Redis `search:heat:pending:5001` 出现 `like_delta=1`（证明 MQ 已发出并被 search 消费）→ 19:32 刷新后 ES `article_idx/_doc/5001` 的 `like_cnt`=**16**、pending 键被 GETDEL；取消 → user_like 0 行、stat **16→15**、镜像 **15**、pending `like_delta=-1`→ 19:47 刷新后 ES **15**（version 3，未变负）；重复取消无副作用 |
| 7 | 11.6/11.12 曝光点击：连续刷两次文章流；从流内打开一篇文章（带 `from=feed&snapshotId&position`），再从搜索入口打开同一篇 | `feed_exposure_daily` 出现当日行且 `exposure_count` 增长；带参数打开 `click_count` +1，不带参数只计曝光；登录与匿名各占一行（`identity_type` 分开） | ✅ 实测：匿名（合法 v4 UUID）刷流 → `feed_exposure_daily` 匿名行（stream=article、identity_type=anon）；登录 u2003 → 独立 login 行；点击必须带 `from=feed` + `snapshotId` + 当日已曝光才 +1，`5001` 曝光 2/点击 1、重复点击不再增；不带归因参数的 `5004` 点击 0；CTR 两口径 anon 6 曝光/1 点击=**0.1667**、login 5 行/1 点击=**0.2000**（登录口径不含匿名） |
| 8 | 11.11 负反馈：分别提交「不感兴趣(5003)」「屏蔽作者」「屏蔽分类」，重复提交一次；刷流观察；再 DELETE 撤销 | 重复提交不报错且行数不增；单条内容**当页**消失；作者/分类屏蔽**下一代快照**消失（旧快照剩余页仍可能看到，属已知表现）；撤销后重新建池可见；未登录提交返回 10002 | ❌ 未通过：未登录 `10002` ✅、单条内容当页消失 ✅、旧快照仍可刷到 ✅、撤销恢复 ✅、频控第 21 次 `10005` ✅；但**作者屏蔽的「下代生效」失败**——建池后候选池为空触发 last-good 兜底，而 last-good 路径不应用负反馈名单，新代仍返回被屏蔽作者内容（`FeedStreamServiceImpl` 空池兜底分支）；分类维度因种子 `category` 双重编码、feed VO `category` 为 null，**无法验证**。**2026-11-06 晚修复复测（验收转入批次十二 12.1）**：兜底路径已补负反馈过滤——article 作者屏蔽 gen1 含 5099→屏蔽 2003→gen2 剔除；article 分类屏蔽（合法枚举「散文」）gen4 含 5099→屏蔽→gen5 剔除；book 作者屏蔽 gen1 含 6199→屏蔽 2003→gen2 剔除；空池 last-good 兜底页同样不放出被屏蔽内容。原「分类无法验证」的根因是造数用了非枚举分类值（测试问题非代码缺陷） |
| 9 | 11.13 完读：等 `BookReadQualityJob` 首次执行（默认启动 10 分钟后）或临时把 `initial-delay-ms` 调小；查 `book_read_quality` | 种子书 6101 = 1/1（比率 1.0000）、6102 = 0/1（0.0000）；6103~6105 = 0/0（比率 NULL）；日志有耗时；重复执行不放大 | ✅ 19:45:04 实跑：日志 `[粗完读率] 本轮完成：rows=10`（5 本书 upsert，MySQL 计 2/行）；`book_read_quality` 落表 6101=**1/1**（1.0000）、6102=**0/1**（0.0000）、6103~6105=**0/0**（NULL），`stat_update_time` 统一刷新为 11:45:04(UTC)；upsert 语义保证重复执行不新增行 |
| 10 | 11.16 对账：等对账任务跑一轮，看日志与指标 | 收藏/浏览/点赞/评论差异均为 0（收藏源值已排除作者自收藏）；超 `diff-rows-threshold` 才 ERROR；`litisle_content_stat_reconcile_diff_total` 按类型可见 | ⚠️ 无法验证（指标可见性）：对账任务实跑 19:21、19:40 两轮均 `total=0, threshold=50`，6 类差异 articleCollect/bookCollect/articleView/articleLike/articleComment/bookReaderUnder 全为 0，阈值未触发故日志级别正确（仅 INFO）；`litisle_content_stat_reconcile_diff_total` 因 `diffRows<=0` 按设计不打点，本轮**看不到**该指标，未伪造差异。**2026-11-06 晚修复复测（验收转入批次十二 12.2）**：已改 Gauge 预注册（选型理由与实现见 12.2），重启后 6 条 `litisle_content_stat_reconcile_diff` gauge 全部可见 `0.0`，0 值不再缺席 |
| 11 | 前端契约复核：按 [Search-Architecture §十一](../Architecture/Search-Architecture.md) 第 5~8 条逐条过 | 第 5 条点击归因、第 6 条点赞提示、第 7 条负反馈三维度与撤销、第 8 条时长心跳仅预留（传 `durationMs` 不影响历史写入） | ✅ 静态复核通过：第 5 条详情接口已接受 `from`/`snapshotId`/`position` 且实测只在该组合下计点击；第 6 条 `POST/DELETE /like` 的三种拒绝码与计数刷新实测一致；第 8 条 `PUT /reading-history` 的 `durationMs` 仍被忽略。第 7 条契约与实现基本一致，唯「下代快照消失」受 11.11 的 last-good 兜底问题影响（见第 8 项） |

> **批次十一收尾（2026-11-06）**：上表 1~7、9、11 项实测通过按现状勾收；**11.11 作者屏蔽下代生效、11.16 对账 0 值可观测两项转入批次十二**（代码修复已完成并实测，正式验收随批次十二收口）。本批连带落地：① `docker/init/mysql/01-schema.sql`/`03-seed.sql` 入口补 `SET NAMES utf8mb4`（修复容器客户端 latin1 导致的中文双重编码，对全新初始化生效）；② `tools/db-repair/content-stat-backfill.sql`（zz 脚本删除后的存量统计回填替代，不参与自动初始化）；③ user schema 补 phone_hash/email_hash 并实测全新初始化（见 12.3）；④ `10-nacos-schema.sql`（0 字节）实测在 entrypoint 中执行无错、`nacos_config` 库 0 表由 Nacos 3.x 启动迁移器自建（20-nacos-user.sql 已授予该账号 DDL），占位文件无害，是否删除待用户定。

##### 批次十二：11.11 屏蔽下代生效 + 11.16 对账 0 值可观测（2026-11-06 立项）

> 来源：批次十一环境验证发现的两处缺陷 + 全新初始化验证连带发现。**代码修复已完成**，本批次收口正式验收与遗留风险处置。

###### 12.1 11.11 作者/分类屏蔽在下一代快照生效（已收口 ✅）
- 现象（批次十一清单第 8 项）：屏蔽作者后建池候选为空 → 走 last-good 兜底，兜底页不应用负反馈名单 → 新代仍返回被屏蔽作者内容。
- 根因：[FeedStreamServiceImpl](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java) 6 处 last-good 兜底调用（Redis 不可用/建池失败/空池 × article/book）未传 userId，`lastGoodArticlePage`/`lastGoodBookPage` 不做负反馈过滤；建池路径本身有过滤，非「没读名单」。
- 修复：兜底页统一经新增 `filterArticleIdsByFeedback`/`filterBookIdsByFeedback`（内容 ID/作者 ID/分类名三维度）过滤后再切片；建池与切片口径不变。
- 已实测（2026-11-06，证据链均为「旧快照含目标 → 屏蔽 → 触发下一代 → 目标消失」）：article 作者屏蔽 gen1 含 5099→屏蔽 2003→gen2 剔除；article 分类屏蔽（合法枚举「散文」）gen4 含 5099→屏蔽→gen5 剔除；book 作者屏蔽 gen1 含 6199→屏蔽 2003→gen2 剔除；空池→last-good 兜底页不放出被屏蔽内容。
- [x] ✅ 2026-11-06 收口实测（批次十二执行）：三维度+撤销恢复全链路复测通过——①不感兴趣（type=1，targetId=5099）：池 gen1 含 5099 → 屏蔽 → 同快照重放**当页消失**（切片过滤后由 5003 补位）→ 触发 gen4 快照原文无 5099 → 撤销 → 重建池恢复；②屏蔽作者（type=2，2003）：gen5 含 5099 → 屏蔽 → 旧快照重放仍见（既有语义）→ gen6 剔除 → 撤销 → 恢复；③屏蔽分类（type=3，「散文」）：gen7 含 5099 → 屏蔽 → 旧快照仍见 → gen8 剔除 → 撤销 → 恢复；④书籍流作者（stream=book，2003）：gen1 含 6199 → 屏蔽 → 旧快照仍见 → gen2 剔除 → 撤销 → 恢复。测试数据已清场。

###### 12.2 11.16 对账差异 0 值可观测（已收口 ✅）
- 现象：差异为 0 时 Counter 不创建，Prometheus 指标缺席，「无数据」与「一切正常」不可区分。
- 选型：**Gauge 预注册**。理由：对账差异是「当前状态量」（每轮覆盖为本轮差异数，0 本身是有效观测值），Counter 表达累计事件、0 值打点无意义且 meter 不创建。实现：[ContentMetrics](../../litisle-content/src/main/java/com/litisle/litislecontent/metrics/ContentMetrics.java) `@PostConstruct` 一次性注册 6 类 type 的 `litisle_content_stat_reconcile_diff` gauge（AtomicLong 强持有防弱引用回收、AtomicBoolean 防重复注册、标签固定 6 值不动态创建）；`recordStatReconcileDiff` 改 `set()` 覆盖、0 也写入；[ContentStatReconcileJob](../../litisle-content/src/main/java/com/litisle/litislecontent/job/ContentStatReconcileJob.java) 每轮 6 类都上报（含 0）。
- 已实测：重启后 6 条 gauge 全部可见 `0.0`（type=article_collect/article_comment/article_like/article_view/book_collect/book_reader_under，均带 application="litisle-content"）。
- [x] ✅ 2026-11-06 收口实测（批次十二执行）：扰动 `article.like_cnt`（+1，镜像无周期自愈任务）制造 1 行差异 → 以 JVM 系统属性运行时覆盖对账参数（initial-delay 15s/interval 60s/threshold 0，仅验证用、验完还原）重启 → 首轮对账 `ERROR [统计对账] 差异超阈值：total=1, articleLike=1`，gauge `type="article_like" 1.0`；还原数据后次轮 `INFO 完成：total=0, articleLike=0`，6 条 gauge 全部回 `0.0`。已按正常配置（每日一轮、threshold 50）重启还原运行态。注：`@Scheduled` 占位符不走 relaxed binding，环境变量方式覆盖无效，必须用 `-D` 系统属性（已验证）。

###### 12.3 user schema 补列与全新初始化（已完成 ✅）
- 依据：[Sql-Document.md](../Sql-Document.md) §二 sys_user 已含 phone_hash/email_hash/uk_phone_hash_del/uk_email_hash_del（email varchar(255)）；与 [User.java:65-71](../../litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/User.java#L65-L71) 实体一致，user-schema.sql 原缺。
- 变更：user-schema.sql 补 `phone_hash varchar(64) NOT NULL` + `email_hash varchar(64) NULL` + 唯一键 `uk_phone_hash_del(phone_hash,del_flag)`/`uk_email_hash_del(email_hash,del_flag)`（列注释注明依据来源）；user-seed.sql 9 行全部带 `SHA2(phone,256)`（phone_hash NOT NULL 必须带值，否则 INSERT IGNORE 静默跳过；2001~2003 密文手机号对密文取 SHA2）。
- 全新初始化实测（一次性 mysql:8.0 容器挂 `docker/init/mysql` 走真实 entrypoint + .env 完整环境）：00→01→02→03→10→20 全套无 ERROR；sys_user 结构与 User.java 全部 @TableField 对齐（无 Unknown column 风险）；种子 9 行 phone_hash 全非空、email_hash 全 NULL（预期）；中文分类 hex 正确无双重编码。临时容器与匿名卷已删。
- [x] ✅ 本条已完成，无需收口。

###### 已知风险（批次十二登记，按「现象 / 触发条件 / 影响 / 处置方式」四要素记录）

> 与上面的 12.1~12.3 任务条目区分：本节是**风险登记**，不是待办任务；三条均未改变代码与数据现状。

**R1 · 存量统计回填能力已移出初始化脚本（关键词：回填 / content-stat-backfill）**

- **现象**：`zz-` 系列补丁脚本已于 2026-11-06 合并进 `schema/content-schema.sql` 后删除，统计表与源表的重算能力只剩 [tools/db-repair/content-stat-backfill.sql](../../tools/db-repair/content-stat-backfill.sql) 手工执行，**不再随建库自动完成**。全新库的统计基线由 `seed/content-seed.sql` 直接写入，同样不经过该脚本。
- **触发条件**：任何「只升级代码 / 只改源表、没有手工跑这个脚本」的环境。典型场景：① 老环境从 `zz-` 脚本时代升级，历史统计从未按新口径重算；② 手工改 `favorite` / `article` / `reading_history` / `book_chapter` 后未同步统计表；③ 用自建脚本或导数工具批量写入内容数据（绕过 `seed/content-seed.sql`）。
- **影响**：`article_stat`（收藏 / 点赞 / 评论 / 浏览）、`book_stat`（收藏 / 章节数 / 读者数）、`content_stat_window`（30 / 90 天窗口）、`book_read_quality`（完读率）落后于源表。**接口不报错、卡片不异常，只是收藏数 / 阅读数 / 章节数显示偏小或为 0**——属于静默错误，不做核对发现不了；连带推荐流排序权重失真（`content_stat_window` 缺失时窗口值退化到累计值），个性化召回质量下降。
- **处置方式**：
  - **何时必须跑**：换库 / 换环境首次启动前；升级到批次十一及以后代码且库内已有存量内容时；批量改源表（`favorite` 迁移、`reading_history` 裁剪、章节批量增删）之后；对账任务报出差异时。
  - **操作顺序**（与脚本头注释一致）：先跑「第一部分 执行前核对」记录 6 项 `diff_rows` → 再跑「第二部分 分批重算」（`batch_size` 默认 1000，按主键分批、可随时中断）→ 最后跑「第三部分 执行后复核」，`diff_rows` 应全部为 0（`book_reader_under` 因 90 天 / 单用户 1000 条裁剪可能仍留有旧值差，属预期）。
  - **靠什么发现**：[ContentStatReconcileJob](../../litisle-content/src/main/java/com/litisle/litislecontent/job/ContentStatReconcileJob.java) 每日一轮对账，写 6 类指标 `litisle_content_stat_reconcile_diff{type=article_collect|article_comment|article_like|article_view|book_collect|book_reader_under}`（Gauge 预注册，0 值可见），差异总数超阈值（默认 50）打 ERROR 日志。
  - **口径与边界**：收藏数排除作者自收藏；章节数只统计「未删 + 已发布 + 公开」；书籍阅读人数取 `GREATEST(现值, 源值)` 不缩小；脚本只做统计重算，不含任何结构性 DDL、不 `drop` / `truncate` 业务表、不参与自动初始化。

**R2 · 存量库 `article.category` 双重编码（mojibake）（关键词：category 编码 / 分类屏蔽）**

- **现象**：种子文章 5001~5005 的 `article.category` 在库内是 `hex=C3A5C2B0…` 这类双重编码值（latin1 客户端写入 utf8mb4 列），按 latin1→utf8mb4 解码后才可读（如「散文」）。书籍表 `book.category` 是 String 列，同样存在 mojibake。全新库因 `docker/init/mysql/01-schema.sql`、`03-seed.sql` 入口补了 `SET NAMES utf8mb4` 已正确，**仅存量行受影响**。
- **触发条件**：任何依赖分类名精确匹配的逻辑——分类召回、屏蔽分类（`feed_feedback` type=3 按 `target_key` 分类名匹配）、分类过滤（列表 / 搜索的 category 精确查询）、分类聚合展示。
- **影响**：匹配静默失效或漏配。文章侧 `ArticleCategoryEnum` 把非法值映射为 `null` → **分类屏蔽对存量行天然无效**、分类过滤取不到值；书籍侧 mojibake 值与用户屏蔽提交的正常中文对不上，同样无效。此前屏蔽分类的实测用的是**解码后**的值，代码不做解码故匹配不上（测试造数问题，非代码缺陷）。前端若直接展示 `category` 会出现乱码。
- **处置方式**：当前**未改数据**，入口脚本修复只对全新初始化生效。可选路径（需产品与数据确认后再动，**本轮不改**）：
  - a. **单次数据修复脚本**：`UPDATE` 把双重编码值转回正常中文；执行前必须备份、抽样比对、低峰窗口内分批执行；
  - b. **代码层兼容解码**：读取时按 latin1→utf8mb4 检测并转换；新数据无需解码，须用「可解码且含高位区段」判定，避免误伤正常值；
  - c. **只影响存量不管**：新增内容编码正常，存量行维持现状（分类屏蔽对存量行持续无效）。
  - 核对手段：`select distinct hex(category), category from article`，与解码后的预期值逐条比对。

**R3 · `00-init-databases.sql` 对 `MYSQL_USER` 的隐含依赖（关键词：MYSQL_USER / 授权）**

- **现象**：[00-init-databases.sql:56-60](../../docker/init/mysql/00-init-databases.sql#L56-L60) 直接 `GRANT ALL PRIVILEGES ... TO 'test_k.1'@'%'`，**账号并非 SQL 创建**，而是依赖 Docker 官方 entrypoint 从 `MYSQL_USER` / `MYSQL_PASSWORD` 环境变量预建。
- **触发条件**：换部署方式——Compose 改为 K8s、改用已有 MySQL 实例、只执行 SQL 不走官方 entrypoint、或 env 未注入 / 注入值与脚本硬编码账号不一致。
- **影响**：变量缺失时账号不存在，授权语句报 `ERROR 1410` 中断（实测位置即 00 脚本 GRANT 行），初始化在第一步就失败；若注入的 `MYSQL_USER` 与脚本写死的 `'test_k.1'` 不一致，则**授权给错误账号**，应用连不上库。报错发生在建库阶段，排查容易误判为网络或 SQL 语法问题。
- **处置方式**：当前依赖 `MYSQL_USER=test_k.1`、`MYSQL_PASSWORD=123`（compose 由 `.env` 注入，须与 00 脚本硬编码账号一致）。换环境时必须：① 保证 entrypoint 能拿到这两个 env（compose 正常路径不受影响）；② 或改为不依赖 entrypoint 的初始化方式，自行执行 `CREATE USER IF NOT EXISTS ...` + `GRANT ...`，并把账号名抽成模板变量；③ 走 K8s 时先把 00 脚本账号与 Secret 中账号对齐再执行。

-------------------------

## 暂不实现汇总

#### content模块

#### search模块

- ~~user_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user_idx），此项从「暂不实现汇总」移出，原触发条件已满足
