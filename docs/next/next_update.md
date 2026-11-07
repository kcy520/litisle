# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分
- 已知约束与风险（非待办）：见 [constraints-index.md](../constraints/constraints-index.md)

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

### 已完成项索引（2026-11-07 建立）

> 本表只做导航，不重复条目正文。**编号一律不重排**：本文件按编号被引用（「按 11.1 口径」「见 10.3」等），归档后留下的编号空缺即指向下表文件，不要按新序号去理解旧引用。

| 编号段 | 主题 | 状态 | 归档位置 |
| --- | --- | --- | --- |
| 10.1~10.6、10.9 | 用户个性化推荐流（批次十） | 已归档（7 项） | [content-10-feed-personalization.md](../archive/content/content-10-feed-personalization.md) |
| 11.1~11.10、11.12~11.17 | 推荐互动数据与统计口径治理（批次十一） | 已归档（16 项） | [content-11-interaction-stats.md](../archive/content/content-11-interaction-stats.md) |
| 12.1~12.3 | 批次十二收口（屏蔽下代生效 / 对账 0 值可观测 / user schema 补列） | 已归档（3 项） | [content-12-batch12-closing.md](../archive/content/content-12-batch12-closing.md) |
| 板块一~九（content） | 模块基建、草稿、发布、可见性、书籍、标签、推荐流、审核回写、临时清单 | 已归档（前序） | [content-implemention-records.md](../archive/content/content-implemention-records.md)（索引页） |
| 批次八/九（search） | 搜索模块前序批次（索引同步、召回、降权等） | 已归档（前序） | [search-implemention-records.md](../archive/search/search-implemention-records.md)（索引页） |

> 注意：search 的 9.1（7 项）、9.4（2 项）、9.5、9.6、9.8 仍有未完成项在本文档，第 5 行「已归档」指的是前序批次，不含这些活跃项。

**本文件保留的编号空缺**：

- 批次十：仅 10.7、10.8 在本文档，10.1~10.6、10.9 已在归档（见上表第 1 行）。
- 批次十一：仅 11.11 在本文档，11.1~11.10、11.12~11.17 已在归档（见上表第 2 行）。
- 批次十二：仅「已知风险 R1~R3」在本文档，12.1~12.3 已在归档（见上表第 3 行）。

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

> —9.2 a/b 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)；c/d 已迁出至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（2026-11-05）。

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

> —P0-E / P0-D 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)；P2 条件项已迁出至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（2026-11-05）。
- [ ] 【待拍板】`updateTime` 精度（来源：版本守卫以秒级 `updateTime` 比较；风险：同一秒内两次编辑会被判为旧）；建议后续补毫秒或单调版本号，本轮不实施。

###### 9.9 search 索引同步与可观测性（本轮仅登记，未执行）

> 编号说明：9.8 之后无 9.9，本组编号沿用批次九体例为 9.9；本轮仅完成分析与登记，未改代码/配置/DDL。
> 原按「先做 / 待触发 / 已决定不做」分组，现已全部处置：A~D 与 F 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)；E 及 G/H（已决定不做）已迁出至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（2026-11-05）。

------------

##### 批次十：用户个性化推荐流（可无限刷，2026-11-05 立项）

> 10.1~10.6、10.9 共 7 项已完成并归档，见 [content-10-feed-personalization.md](../archive/content/content-10-feed-personalization.md)；批次内背景（含需求来源/板块归属/现状证据/相关文档）、需求条款 10 条、已确认决策 14 条、待确认项 0 条、用户隔离与算分口径 8 条、P1 改造边界表、与暂不实现汇总衔接均已整体移入该文件（**唯一副本**）。**编号不重排**，本文件仅保留 10.7、10.8 两条待环境验证项。

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

-------------------------

##### 批次十一：推荐互动数据与统计口径治理（2026-11-06 立项）

> 已完成 16 项（11.1~11.10、11.12~11.17）并归档，见 [content-11-interaction-stats.md](../archive/content/content-11-interaction-stats.md)。批次内的背景、现状问题清单、方案 13 条、已确认决策、待确认项、执行注意项 15 条、环境验证清单与收尾记录均已整体移入该文件（**唯一副本**）。**编号不重排**，本文件仅保留 11.11。

- [~] 【P1｜前端配合：是】11.11 反作弊与负反馈登记（部分完成，来源：问题 11；前置：11.6 有曝光点击数据；涉及 content/api/前端）
  - **已完成**：负反馈三维度（不感兴趣 / 屏蔽作者 / 屏蔽分类）的提交、撤销、幂等，以及当页剔除与下一代快照生效（含 last-good 兜底过滤）。落地与收口实测见 [content-11-interaction-stats.md](../archive/content/content-11-interaction-stats.md) 的 11.11 条目与「八、附录：环境验证清单」第 8 项，验收转入批次十二 [12.1](../archive/content/content-12-batch12-closing.md)。
  - **未做**：作者自互动过滤、异常频次规则——原设计明确另拆实施项，不是遗漏。
  - **追溯**：环境验证清单原表已移入归档文件，实测依据以该处为准。

##### 批次十二：11.11 屏蔽下代生效 + 11.16 对账 0 值可观测（2026-11-06 立项）

> 12.1~12.3 共 3 项已完成并归档，见 [content-12-batch12-closing.md](../archive/content/content-12-batch12-closing.md)；批次来源/背景、现象、根因、选型、修复与实测证据原文均在该文件（**唯一副本**）。**编号不重排**，本文件仅保留下方「已知风险 R2」——风险登记，不是待办；R1、R3 已迁至 [docs/constraints/](../constraints/constraints-index.md)。

###### 已知风险（批次十二登记，按「现象 / 触发条件 / 影响 / 处置方式」四要素记录）

> 与上面的 12.1~12.3 任务条目区分：本节是**风险登记**，不是待办任务；R2 未改变代码与数据现状，**已知约束与风险统一登记在 [constraints-index.md](../constraints/constraints-index.md)**（R1、R3 已迁入该目录）。

> **指针**：R1（存量统计回填能力已移出初始化脚本）、R3（`00-init-databases.sql` 对 `MYSQL_USER` 的隐含依赖）已迁入 [docs/constraints/](../constraints/constraints-index.md)，原文、触发条件与处置方式完整保留；此处不再重复。

**R2 · 存量库 `article.category` 双重编码（mojibake）（关键词：category 编码 / 分类屏蔽）**

- **现象**：种子文章 5001~5005 的 `article.category` 在库内是 `hex=C3A5C2B0…` 这类双重编码值（latin1 客户端写入 utf8mb4 列），按 latin1→utf8mb4 解码后才可读（如「散文」）。书籍表 `book.category` 是 String 列，同样存在 mojibake。全新库因 `docker/init/mysql/01-schema.sql`、`03-seed.sql` 入口补了 `SET NAMES utf8mb4` 已正确，**仅存量行受影响**。
- **触发条件**：任何依赖分类名精确匹配的逻辑——分类召回、屏蔽分类（`feed_feedback` type=3 按 `target_key` 分类名匹配）、分类过滤（列表 / 搜索的 category 精确查询）、分类聚合展示。
- **影响**：匹配静默失效或漏配。文章侧 `ArticleCategoryEnum` 把非法值映射为 `null` → **分类屏蔽对存量行天然无效**、分类过滤取不到值；书籍侧 mojibake 值与用户屏蔽提交的正常中文对不上，同样无效。此前屏蔽分类的实测用的是**解码后**的值，代码不做解码故匹配不上（测试造数问题，非代码缺陷）。前端若直接展示 `category` 会出现乱码。
- **处置方式**：当前**未改数据**，入口脚本修复只对全新初始化生效。可选路径（需产品与数据确认后再动，**本轮不改**）：
  - a. **单次数据修复脚本**：`UPDATE` 把双重编码值转回正常中文；执行前必须备份、抽样比对、低峰窗口内分批执行；
  - b. **代码层兼容解码**：读取时按 latin1→utf8mb4 检测并转换；新数据无需解码，须用「可解码且含高位区段」判定，避免误伤正常值；
  - c. **只影响存量不管**：新增内容编码正常，存量行维持现状（分类屏蔽对存量行持续无效）。
  - 核对手段：`select distinct hex(category), category from article`，与解码后的预期值逐条比对。
- **当前状态（2026-11-07）**：一次性修复脚本已就绪 [article-book-category-mojibake-fix.sql](../../tools/db-repair/article-book-category-mojibake-fix.sql)（四段：只读统计 / 备份 / 修复 / 校验，幂等、可回滚），**等用户确认数据性质后再执行**；执行完成并复核通过后，本条整体迁入 [docs/constraints/](../constraints/constraints-index.md)。

-------------------------

##### 批次十三：自研 RedisGuard 熔断降级切换 Resilience4j（2026-11-07 立项）

> 来源：用户 2026-11-07 提出；**只读核查已于同日完成，结论直接沿用，不重新调研**。切换前必须先完成「四、待决策项」第 1 条，未拍板前本批次不进入实施。**2026-11-07 用户已拍板执行 13.1~13.9，13.10 保持现状不动。**
> 板块归属：**common-redis 为主**（组件本体与自动配置），联动 litisle-auth（唯一暴露 prometheus 端点的服务）与文档层；content / file / review 等模块只涉及注释与指标消费，不改逻辑。
> 范围边界：32 个直接持有 `RedisTemplate` / `StringRedisTemplate` / `RedissonClient` 的类**按设计绕开熔断**，不在本次范围（`ReactiveRedisTemplate` 全项目 0 处）。

**一、背景与现状（迁自只读核查，2026-11-07）**

- 组件本体：`litisle-common/common-redis` 的 `RedisGuard`，手写三态（`int` 常量 `CLOSED=0` / `OPEN=1` / `HALF_OPEN=2`），`AtomicInteger state` + `AtomicInteger consecutiveFailures` + `volatile long openAtMillis`，无锁 CAS 实现。
- 失败口径：**连续 3 次失败**跳闸（`FAILURE_THRESHOLD=3`）；半开只放行 1 个探测线程；冷却默认 5 秒（`litisle.rate-limit.fallback.cooldown-seconds`）。
- 指标：自建 Gauge `redis.guard.degraded`（1=降级，**无 label**）+ Counter `redis.guard.state.change`（tag `from`/`to`）；`docker/init/prometheus/alert-rules.yml` 的 `RedisGuardDegraded` 告警直接依赖 `redis_guard_degraded == 1`。
- 入口：状态机唯一承载点 `RedisRateLimiter`（`acquire()` 3 处、`onSuccess`/`onFailure` 共 10 处），向上被 6 个类消费，再往上 77 处 `@RateLimiter` 注解、37 个文件全走切面。
- 测试：组件**零单元测试**。
- 现状疑点：`LoginRetryLimiter` **全项目没有任何业务类注入**，等于这套熔断当前实际只服务「限流」一个场景。

**二、方案（4 条，含 1 条硬性要求）**

1. **等价映射必须显式配置（硬性要求，不是建议）**：`failureRateThreshold=100`、`slidingWindowSize=3`、`minimumNumberOfCalls=3`。原因：手写是「连续 3 次失败」，Resilience4j 默认是「窗口失败率 50% / 最小 100 次调用」；直接用默认值会出现 **Redis 连挂三次也不跳闸**，故障期继续吃连接超时。
2. **三态映射**：`CLOSED/OPEN/HALF_OPEN` 直接对应 `CircuitBreaker.State`；`acquire()` 改用 `CircuitBreaker#tryAcquirePermission()`，`Path.PROBE` 语义重写为「半开许可」，不再手写探测线程标记。
3. **指标策略**：优先**保留同名 Gauge 桥接**（`redis.guard.degraded` 由 `CircuitBreaker#getState` 派生），告警规则可不动；若确要改名，指标与规则必须同一提交内切换，不允许只改一边。
4. **本地兜底不动**：`Path.LOCAL` 仍走 `LocalRateLimiter`（含 `instanceCount` 分摊），不得简化成「OPEN 就直接返回」。

**三、任务项（10 项：P0 9 项 + 待决策 1 项）**

- [x] 【P0｜13.1】`RedisGuard` 退化为薄适配层：`acquire()` / `onSuccess` / `onFailure` / `resetFailure` 全部改为委托 `CircuitBreaker#tryAcquirePermission` / `onSuccess` / `onError` / `getState`；保留 `Path` 枚举（REDIS / PROBE / LOCAL）与「业务异常不算故障」的判定口径
  - 关键：`resetFailure` 不得再借 `onSuccess` 把 OPEN 直接打回 CLOSED（现状会绕过半开探测），改为「仅 CLOSED 下清零计数」或显式 `transitionToClosedState`
  - 验收：三处调用点（`RedisRateLimiter#increment`、`LoginRetryLimiter` 的探测与复位）签名不变、行为等价
  - ✅ 2026-11-07：`RedisGuard` 改为持有 `CircuitBreaker` + `RateLimitFallbackProperties`；`acquire()` 按「enabled 开关 → OPEN 直接 LOCAL → tryAcquirePermission 拒绝则 LOCAL → 申请瞬间 HALF_OPEN 为 PROBE」判定；`onSuccess(isProbe)` 探测成功上报并记恢复日志、非探测成功仅在 CLOSED 下上报（OPEN/HALF_OPEN 一律忽略，即 `resetFailure` 复位路径不再绕过半开探测）；`onFailure` 委托 `onError` 并只在真实状态切换点打日志。注：原 `RedisGuard` 并无 `resetFailure` 方法（复位在 `LoginRetryLimiter#resetFailure` 内以 `onSuccess(false)` 表达），本轮按「不在适配层新增死方法」处理
- [x] 【P0｜13.2】`RedisRateLimiter`：注入类型 `RedisGuard` → `CircuitBreaker`；`Path.PROBE` 改为半开许可语义；`localIncrement` 兜底路径原样保留（`instanceCount` 分摊逻辑不动）｜前置 13.1
  - ✅ 2026-11-07：字段/构造参数改为 `CircuitBreaker`；`increment` 直接 `getState` + `tryAcquirePermission` + `onSuccess/onError`（半开探测由「申请瞬间 state==HALF_OPEN」判定）；`enabled=false` 走 `tryRedis(..., reportToBreaker=false)` 不申请许可也不上报；`localIncrement` 与 `instanceCount` 分摊零改动
- [x] 【P0｜13.3】`CommonRedisAutoConfiguration`：`redisGuard` Bean 换成 `CircuitBreakerRegistry` + `CircuitBreakerConfig`，显式写入 `failureRateThreshold=100`、`slidingWindowSize=3`、`minimumNumberOfCalls=3`、`waitDurationInOpenState=cooldownSeconds`、`permittedNumberOfCallsInHalfOpenState=1`、`automaticTransitionFromOpenToHalfOpenEnabled=true`
  - 关键：**`ObjectProvider<MeterRegistry>` 必须保留**，不得改为构造注入强依赖（未引 actuator 的服务会启动失败）
  - 验收：未接入 actuator 的 content / file / review 启动正常
  - ✅ 2026-11-07：新增 `CircuitBreakerConfig`（静态工厂 `createCircuitBreakerConfig`，供测试直接断言）、`CircuitBreakerRegistry`（实例名 `redisGuard`）、`CircuitBreaker` 三个 Bean；`MeterRegistry` 仍由 `ObjectProvider` 可选传入，null 时指标静默跳过；`RedisGuard`/`RedisRateLimiter` 注入同一 `CircuitBreaker` 单例。未引 actuator 服务的启动验证属运行态项（随 13.8 一起，见下）
- [x] 【P0｜13.4】`RateLimitFallbackProperties`：`cooldownSeconds` 映射到 `waitDurationInOpenState`；`enabled`、`instanceCount` 保留（本地兜底仍需要）；前缀 `litisle.rate-limit.fallback` 不变（全项目无 yml 显式配置，走默认值）｜前置 13.3
  - ✅ 2026-11-07：字段与默认值不变（`cooldownSeconds=5L` 由自动配置映射为 `waitDurationInOpenState`），仅补齐映射关系与用途注释；前缀零改动
- [x] 【P0｜13.5】`docker/init/prometheus/alert-rules.yml`：指标名联动；首选保留 `redis.guard.degraded` 同名桥接、规则零改动；若改名则 `expr` 与 `for` 同提交调整｜前置 13.3
  - ✅ 2026-11-07：按首选方案执行——`RedisGuardMetrics` 用同名 Gauge `redis.guard.degraded`（由 `CircuitBreaker#getState` 派生）与同名 Counter `redis.guard.state.change`（tag `from`/`to`）桥接；`alert-rules.yml` 的 `RedisGuardDegraded`（`expr: redis_guard_degraded == 1`、`for: 5m`）**零改动**
- [x] 【P0｜13.6】上游 6 个消费者与 2 处类注释同步：`RateLimiterAspect`、`FileGlobalQpsInterceptor`、`LikeRateLimitSupport`、`FeedRateLimitSupport`、`ModerationModelQpsGuard`、`ModerationGlobalQpsInterceptor` 注释里的 "RedisGuard" 字样；`RedisRateLimiter`、`LocalRateLimiter` 类注释同步｜前置 13.1/13.2
  - ✅ 2026-11-07：全仓 java 精准检索确认 6 个消费者**均无 "RedisGuard"/"三态"/"熔断" 字样**（其注释只写「故障降级/本地兜底」，语义仍准确），故无需改动；已同步 `RedisRateLimiter`（改为「Resilience4j 熔断 + 编程式 API，禁止注解」并保留原降级口径说明）与 `LocalRateLimiter`（补「与熔断器实现的关联：只做单机计数，不参与状态判定」）两处类注释
- [x] 【P0｜13.7】新增 3 条测试：①降级（连续 3 次失败 → OPEN）②半开（冷却到期放行且仅 1 个 permit）③恢复（探测成功回 CLOSED / 探测失败重新 OPEN）
  - 验收：3 条用例通过；断言覆盖 `permittedNumberOfCallsInHalfOpenState=0` 这种配置错误不会上线
  - ✅ 2026-11-07：新增 `RedisGuardCircuitBreakerTest`（纯内存、真实 CircuitBreaker），含 5 条用例：①连续 3 次失败打开 ②1s 冷却到期自动半开且仅 1 个 PROBE ③探测成功回 CLOSED / 再探测失败回 OPEN ④无许可复位在 OPEN/HALF_OPEN 下不打回 CLOSED、不吃探测许可 ⑤生产配置值断言（100/3/3、半开许可 1、自动转半开 true，防 `permittedNumberOfCallsInHalfOpenState=0` 上线）。`mvn -pl litisle-common/common-redis -am -Dtest=RedisGuardCircuitBreakerTest test`：**Tests run: 5, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**
- [ ] 【P0｜13.8】灰度验证：只有 `litisle-auth` 暴露 `health,prometheus` 端点，验证只能在这一处做（停 Redis → 观察状态切换、`redis_guard_degraded` 取值、告警触发与恢复）｜前置 13.3/13.5
  - ⏳ 2026-11-07：**未执行（环境未就绪）**。本机无 Docker/Redis/auth 运行态，无法停 Redis 观测；代码与配置侧已就绪（同名指标 + 规则零改动），恢复环境后按本条验收
- [x] 【P0｜13.9】文档联动：`docs/Architecture/commom-des.md`（组件表、故障降级章节、目录树、变更记录）、`docs/Architecture/File-Architecture.md`（限流体系与配置表）、`docs/overall_documen/Design-Document.md`（`guard/` 目录说明与 Redis 工具表述）｜前置 13.1~13.3
  - ✅ 2026-11-07：`commom-des.md` 组件表两行、故障降级章节三条、Micrometer 章节标题与说明、guard/ 目录树（新增 `RedisGuardMetrics.java`）、版本号 v2.1→v2.2 与变更记录；`File-Architecture.md` 配置表与「Redis 故障降级」条目；`Design-Document.md` `guard/` 目录说明与 Redis 工具表述
- [ ] 【待决策｜13.10】`LoginRetryLimiter` 处置：核查发现全项目无业务类注入 → 三选一（跟着换 / 接线后再换 / 直接删除），见「四、待决策项」第 2 条

**四、待决策项（2 条，单列，不替用户拍板）**

1. **是否真的切换**：收益 = 免维护自研状态机 + 标准熔断指标语义；成本 = 3~4 人天；行为上无提升（三态与连续失败口径已能满足现有需求）。
2. **`LoginRetryLimiter` 怎么处理**：①跟着换（保留共用状态机，继续无调用方）②接线后再换（先确认登录防爆破确实要接入）③直接删除（**需用户明确同意删文件**，且要确认没有外部依赖）。

**五、验收标准**

- 连续 3 次连接/超时失败后进入 OPEN；冷却到期后首个请求进入半开且**仅放行 1 个**；探测成功回 CLOSED，探测失败重新 OPEN。
- 降级期间 `RedisRateLimiter` 走本地兜底，阈值与现状一致（`instanceCount` 分摊不丢失，不得实质放宽 N 倍）。
- `redis.guard.degraded` 与 `redis.guard.state.change` 在 `litisle-auth` 的 `/actuator/prometheus` 可查；`RedisGuardDegraded` 告警触发与恢复各验证一次。
- 3 条测试通过；全服务（含未引 actuator 的模块）启动无报错。

**六、执行注意项（6 条，每条都写清后果）**

1. **只能用编程式 API**：`RedisRateLimiter#increment` 内部 `this.tryRedis(...)` 属自调用，不走 AOP；挂 `@CircuitBreaker` 会**静默失效且不报错**。
2. **告警会静默消失**：指标名变更后 `expr: redis_guard_degraded == 1` 返回 **no data 而不是报错**；必须同名 Gauge 桥接，或指标与规则同时切换。
3. **半开不自动转**：`automaticTransitionFromOpenToHalfOpenEnabled` 默认 `false`；`permittedNumberOfCallsInHalfOpenState=0` 会导致降级后**永不恢复**。
4. **兜底口径漂移**：不能把 `Path.LOCAL` 简化成「OPEN 就直接返回」，否则丢掉 `LocalRateLimiter` 的 `instanceCount` 分摊，故障期阈值实质放宽 N 倍。
5. **`ObjectProvider<MeterRegistry>` 必须保留**：未引 actuator 的服务改用构造注入强依赖会启动失败。
6. **`resetFailure` 触发 `onSuccess`** 会让 OPEN 直接回 CLOSED，绕过半开探测——迁移时必须单独处理这条路径。

**七、工期**

3~4 人天，落在「入口统一但需重做指标、告警、补测试」这一档。依据：入口已收敛（3 处 `acquire()` + 10 处回调），**不需要先做收口**；否则属「多入口分散改造」档，工期 15~25 人天。

-------------------------

## 暂不实现汇总

#### content模块

#### search模块

- ~~user_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user_idx），此项从「暂不实现汇总」移出，原触发条件已满足
