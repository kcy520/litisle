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
| 13.1~13.11 | 自研 RedisGuard 熔断降级切换 Resilience4j（批次十三，common-redis 基础设施） | 已归档（11 项） | [common-redis-13-resilience4j.md](../archive/common-redis/common-redis-13-resilience4j.md) |
| 9.6-B | search TestNG 编译期依赖清理（批次九「9.6 测试补齐」） | 已归档（1 项） | [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（9.6-B 节） |
| 15.1、15.2-B | content `support` 包收敛文档同步 + `unknown` 常量提取（批次十五） | 已归档（2 项） | [content-15-structure-governance.md](../archive/content/content-15-structure-governance.md) |
| 16.1 | Feign 拦截器无 SaToken 上下文安全降级（批次十六，common-security） | 已归档（1 项） | [common-security-16-feign-interceptor.md](../archive/common-security/common-security-16-feign-interceptor.md) |

> 注意：search 的 9.1（7 项）、9.4（2 项）、9.5、9.6-A、9.8-P1/P2 仍有未完成项在本文档，第 5 行「已归档」指的是前序批次，不含这些活跃项。

**本文件保留的编号空缺**：

- 批次十：仅 10.7、10.8 在本文档，10.1~10.6、10.9 已在归档（见上表第 1 行）。
- 批次十一：仅 11.11 在本文档，11.1~11.10、11.12~11.17 已在归档（见上表第 2 行）。
- 批次十二：仅「已知风险 R1~R3」在本文档，12.1~12.3 已在归档（见上表第 3 行）。
- 批次十三：13.1~13.11 全部归档（见上表第 6 行），本文件只保留指针，无剩余待办。
- 批次九 9.6：仅 9.6-A 在本文档，9.6-B 已归档（见上表 9.6-B 行）。
- 批次十五：仅 15.2-A 在本文档，15.1、15.2-B 已归档（见上表批次十五行）。
- 批次十六：仅 16.1-V1 在本文档，16.1 已归档（见上表 16.1 行）。

### 执行排期（2026-11-08 用户拍板）

> 状态口径：「已拍板待执行」保持 `- [ ]` / `- [~]`，**不改成 `- [x]`**；`- [x]` 仅表示实际已完成。下表只反映执行顺序，不代表已经实施。

| 批次 | 条目 | 一句话说明 |
| --- | --- | --- |
| 第一批（零风险，先做） | 15.1、15.2-B、9.6-B | 仅文档同步、常量引用替换、测试依赖作用域清理，不改变运行时行为 |
| 第二批（必修，需回归） | 9.8-P1、11.11-A（含 11.11-B）、15.2-A | 改行为；回归文章详情、章节阅读、匿名刷流三种身份 |
| 第三批（需 DDL） | 9.8-P2 | 四张表升 `datetime(3)`，攒到下次动库时一起执行 |
| 第四批（大工程，单独排期） | 9.6-A、14.1 | Testcontainers 集成测试；验证码/文件上传纳入 breaker 评估 |
| 延后 | 11.11-C1；10.7、10.8 | 影子统计等真实互动量；两个端到端验证并入下次环境验证 |
| 保持不做 | 9.8-P3 | 单调版本号，不进入实施 |

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

###### 9.6 测试补齐（Testcontainers 方案已定，2026-11-08）

- [ ] 【可选｜9.6-A】search 关键路径自动化测试（来源：批次二~八均仅编译验证）
  - **方案结论（2026-11-08）**：选 **Testcontainers**，不用嵌入式 ES。理由：ES 8.x 无官方进程内支持；社区 `embedded-elasticsearch` 停在 7.x，与 `elasticsearch-java 8.18.8` 不兼容；项目自定义 IK 镜像的分词与 KNN 行为无法被嵌入式方案代表。
  - **容器边界**：只让 ES 走真容器（项目镜像 `elasticsearch:8.18.8-ik`、单节点、关闭安全认证）；`ContentClient`、`UserClient`、Redis 全部用 Mockito 替身，不引入额外容器。
  - **前置**：先执行 `docker build -t elasticsearch:8.18.8-ik docker/init/elasticsearch`（[compose.yaml:265-269](../../compose.yaml#L265-L269) 已使用同一镜像名）；测试运行机需 Docker 可用。
  - **隔离要求**：测试使用本次运行唯一的索引名（如 `article_idx_it_<uuid>`），用例结束在 `finally` 中删除；不得读写本地固定的 `article_idx` / `book_idx` / `user_idx`，避免污染开发数据。
  - **动哪些文件**：`litisle-search/pom.xml`（新增 `org.testcontainers:elasticsearch` 等测试依赖，版本按 Spring Boot BOM 或显式锁定）、`litisle-search/src/test/` 下新增集成测试与容器/索引清理夹具；不改业务代码。
  - **代价 / DDL**：新增测试依赖与容器/索引清理夹具，CI 需具备 Docker 能力；无 DDL、不改业务代码。
  - **验收**：检索参数组合、兜底不缓存、降级开关、KNN 强制 PUBLIC、索引版本守卫（含同秒相等跳过）至少各有 1 条集成用例通过；无 Docker 时用例明确 skip，而不是失败。
  - **已拍板（2026-11-08｜第四批·大工程）**：单独排期，不与本轮小改动混做。

> —9.6-B 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md) 的 9.6-B 节（2026-11-08）；9.6-A 仍为本文档待办。

###### 9.7 低成本优化（已完成，2 项）

> —已归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)（2026-11-05）。

###### 9.8 并发竞态修复与下架墓碑（已完成 2 项）+ `updateTime` 精度（P1/P2/P3）

> —P0-E / P0-D 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)；P2 条件项已迁出至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（2026-11-05）。
> **核查结论（2026-11-08，替换原「需重跑」写法）**：`article`、`book`、`user`（以及 `sys_user_profile`）的 `update_time` 均为 `datetime` 无小数位（秒精度）；search 没有水位线批量拉取，是**纯事件驱动 + `IndexVersionGuard` 文档级比较**（现有 `updateTime` ≥ 新快照即跳过）。因此：同秒内改两次会漏（第二次被判定为不前进）；先改后删不会漏（删除走门控删索引，不经版本比较）；重复投递天然幂等（相等即跳过）。以下 P1/P2/P3 按此结论拆分，不再要求重跑核查；此处的 P1/P2/P3 指精度方案优先级，与上方已迁出的「P2 条件项」无关。

- [x] 【P1｜必修｜9.8-P1】`user_idx` 版本源补上 `sys_user_profile.update_time`
  - **问题**：`getUserIndexDoc` 只回填 `user.getUpdateTime()`（[UserFeignProviderController.java:292](../../litisle-user/src/main/java/com/litisle/litisleuser/controller/feign/UserFeignProviderController.java#L292)），而 `user_profile.pen_name` 只改笔名时不刷新 `sys_user.update_time`；`IndexVersionGuard` 判定版本未前进 → 索引永不更新，属**必然漏**（不是同秒竞态）。
  - **改法**：同一方法已加载 `profile`（[同文件:281-283](../../litisle-user/src/main/java/com/litisle/litisleuser/controller/feign/UserFeignProviderController.java#L281-L283)），把回填改为 `max(user.getUpdateTime(), profile == null ? null : profile.getUpdateTime())`；`UserProfile#getUpdateTime` 已存在（[UserProfile.java:58-60](../../litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/UserProfile.java#L58-L60)）。
  - **动哪些文件 / 代价**：1 处代码（1 个文件）；无 DDL、无新配置。
  - **验收**：只修改笔名（不动 user 主表）→ 触发 `UserProfileUpdatedEvent` → `user_idx` 的 `penName` 更新且 `updateTime` 前进；只改主表简介同样正常。
  - **已拍板（2026-11-08｜第二批·必修，需回归）**：按 `max(user.updateTime, profile.updateTime)` 实施；回归文章详情、章节阅读、匿名刷流三种身份。
  - ✅ 2026-11-08（版本源 + 索引写入均已实测通过）：`getUserIndexDoc` 已取 `user` / `profile` 的 `updateTime` 最大值，复用同方法已加载的 `profile`、未新增查询。
    - **已验通过（版本源）**：启动 user + search（直连 8081/8086），`PUT /api/user/profile` 只改笔名后，`sys_user.update_time` 全程保持 `2026-11-08 03:26:45`（乱码写入、正确 UTF-8 写入、复原三次均未变），`sys_user_profile.update_time` 前进到 `11:40:54`；`GET /api/feign/user/index-doc?userId=2002` 返回 `updateTime=2026-11-08 11:40:54`，证明 `max(user.updateTime, profile.updateTime)` 生效。
    - **未通过（索引写入）**：search 消费 `user.profile-updated` 报 `SaTokenContext 上下文尚未初始化`（[FeignAuthInterceptor.java:68](../../litisle-common/common-security/src/main/java/com/litisle/commonsecurity/feign/FeignAuthInterceptor.java#L68) 的 `StpUtil.isLogin()` 在 MQ 消费线程无上下文），消息重投后进 DLQ，ES `user_idx` count=0、doc 2002 返回 404。故「索引 `updateTime` 前进且笔名已更新」无实测证据，**不标 `[x]`**。该缺陷为本轮之前即存在的基础设施问题，非 9.8-P1 改动引入。
    - **复原**：笔名已改回 `林间有风`（`HEX(pen_name)=E69E97E997B4E69C89E9A38E`，`profile.update_time=11:41:42`）；因 `user_idx` 自始至终未写入，「改回后索引是否同步回去」无同步对象可验。
    - **后续闭环（2026-11-08 12:29:25）**：批次十六 16.1 修复 `FeignAuthInterceptor` 无上下文异常后重跑，`user_idx` 文档成功写入：`penName=林间有风`（HEX `E69E97E997B4E69C89E9A38E`）、`updateTime=2026-11-08T12:29:25`（与 `sys_user_profile.update_time` 一致），`sys_user.update_time` 未因笔名变化，本次验收完整通过。
- [ ] 【P2｜该修｜9.8-P2】四张表的时间列升级为 `datetime(3)`
  - **范围**：`article.update_time`（[content-schema.sql:43](../../docker/init/mysql/schema/content-schema.sql#L43)）、`book.update_time`（[content-schema.sql:110](../../docker/init/mysql/schema/content-schema.sql#L110)）、`sys_user.update_time`（[user-schema.sql:60](../../docker/init/mysql/schema/user-schema.sql#L60)）、`sys_user_profile.update_time`（[user-schema.sql:134](../../docker/init/mysql/schema/user-schema.sql#L134)）。
  - **改法**：`ALTER TABLE ... MODIFY COLUMN update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)`；同步修改初始化 schema，保持全新库一致。**代码零改动**：守卫比较逻辑不用变、ES mapping 已支持毫秒、DTO 已是 `LocalDateTime`。
  - **代价 / DDL**：需要一次 DDL 窗口（四张表），存量行的毫秒位补 `.000`，无需回填；有 DDL、无代码改动。
  - **验收**：`SHOW COLUMNS` 四列均为 `datetime(3)`；同一秒内连续两次编辑，第二次索引不被守卫跳过；跨秒/乱序仍按现有规则跳过旧快照。
  - **已拍板（2026-11-08｜第三批·需 DDL）**：攒到下次动库时一起执行，本轮不改库。
- 【保持不做｜9.8-P3】不引入单调版本号
  - **理由**：单调版本号要横跨 content 与 user 的全部写路径（文章/书籍/章节/用户/资料等），每条写入都必须递增并透传到 DTO/事件/索引；漏掉任意一处就会让该实体版本永久不前进（比同秒漏更严重且不可自愈）；同时要改契约与存量回填，收益仅为消除同秒窗口。故不做，改由 P2 的毫秒精度覆盖。
  - **代价 / DDL**：0（不实施）；无 DDL、无代码改动。**验收**：无需验收，仅需确认该方案不进入实施。
  - **已拍板（2026-11-08｜保持不做）**：维持不做，不进入任何批次；避免与「已完成」混淆，本项不再使用 `[x]`。

###### 9.9 search 索引同步与可观测性（本轮仅登记，未执行）

> 编号说明：9.8 之后无 9.9，本组编号沿用批次九体例为 9.9；本轮仅完成分析与登记，未改代码/配置/DDL。
> 原按「先做 / 待触发 / 已决定不做」分组，现已全部处置：A~D 与 F 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)；E 及 G/H（已决定不做）已迁出至 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（2026-11-05）。

------------

##### 批次十：用户个性化推荐流（可无限刷，2026-11-05 立项）

> 10.1~10.6、10.9 共 7 项已完成并归档，见 [content-10-feed-personalization.md](../archive/content/content-10-feed-personalization.md)；批次内背景（含需求来源/板块归属/现状证据/相关文档）、需求条款 10 条、已确认决策 14 条、待确认项 0 条、用户隔离与算分口径 8 条、P1 改造边界表、与暂不实现汇总衔接均已整体移入该文件（**唯一副本**）。**编号不重排**，本文件仅保留 10.7、10.8 两条待环境验证项。

###### 10.7 验证 V4：文章流无限刷（必须，1 项）

- [ ] 【必须】文章流端到端验证（来源：10.3/10.5/10.6；前置：联调环境（content/Redis/MySQL 可用）；验收：连续 15 页不重不漏、同作者/同分类打散生效、私密与下架与未过审内容 0 命中、匿名与登录序列不同、快照过期续刷正常、频控按维度触发）
  - **待环境验证（2026-11-06，情况 B，未实测、不勾选）**：本机探测 MySQL84 服务 Stopped、Redis 6379/6380 未监听、content 8085 与 Nacos 8848 未启动。恢复环境后按下列命令逐条执行：
    - **执行状态（2026-11-08 补记）**：上次「推荐流端到端验证 10.7/10.8」会话被取消、无结论留存，需重跑；当前仍未实测。
    - **已拍板（2026-11-08｜延后）**：与下次环境验证合并执行，不单独排期；上次会话 canceled、无结论，仍需重跑，不能视为已验证。
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
    - **执行状态（2026-11-08 补记）**：上次「推荐流端到端验证 10.7/10.8」会话被取消、无结论留存，需重跑；当前仍未实测。
    - **已拍板（2026-11-08｜延后）**：与下次环境验证合并执行，不单独排期；上次会话 canceled、无结论，仍需重跑，不能视为已验证。
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
  - 已完成部分（负反馈提交/撤销/幂等、当页剔除与下一代快照生效）见 [content-11-interaction-stats.md](../archive/content/content-11-interaction-stats.md) 的 11.11 条目与「八、附录：环境验证清单」第 8 项，验收转入批次十二 [12.1](../archive/content/content-12-batch12-closing.md)；此处不再复述。
  - **11.11-A（必须）作者自浏览排除**：给 `ViewCounterSupport#recordView` 增加带 `authorId` 的重载，内部判断「浏览者 == 文章作者」直接 `return`；两个调用点（[ArticleController.java:170](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/ArticleController.java#L170)、[BookReadSupport.java:50](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/BookReadSupport.java#L50)）改走重载。依据：文章详情无条件调 `recordView`，章节阅读注释明确写「含匿名/作者本人」；`article_stat.view_count` 是文章流排序四个统计量之一（权重 0.03），是当前唯一仍会污染推荐权重的自互动。成本约 30 行、3 个文件，无 DDL、无新配置；历史数据不回滚。验收：作者本人浏览不再增加 `view_count`，他人与匿名计数不变。
  - **已拍板（2026-11-08｜第二批·必修，需回归）**：作者自浏览排除进入实施；回归文章详情、章节阅读、匿名刷流三种身份。
  - ✅ 2026-11-08（运行态实测通过｜文章侧 + 章节侧双路径）：`ViewCounterSupport` 带 `authorId` 的重载内部短路；`ArticleController` 的 `recordView` 在 `detail != null` 分支传 `detail.getAuthorId()`；`BookReadSupport` 改传 `bookAuthorId`。启动 auth/user/content 直连实测（服务需带 `--sa-token.token-name=Authorization` 才能识别 auth 签发的 `Authorization: Bearer <token>`，否则 viewerId=null 被当匿名）：
    - 文章侧（articleId=5001，authorId=2002）：清空 `content:article:view:pending:5001` 后，作者 2002 访问详情 → pending 仍为空（0）；换读者 2001（非作者）访问 → pending=1。
    - 章节侧（book 6101 / 章节 610101 → article 5001）：作者 2002 阅读章节 → pending 仍为空；新读者 2003 阅读 → pending=1。
    - DB `article_stat.view_count`：122→127（此前匿名冒烟 5 次）→128（读者 2001 文章侧 1 次）→129（读者 2003 章节侧 1 次，11:40:20 刷回）。作者两次访问均未贡献计数。
  - **11.11-B（可选）推荐流作者自点击排除**：推荐流 `recordClick` 前判断作者自点击并跳过（调用点在 [ArticleController.java:177](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/ArticleController.java#L177)、[BookController.java:192](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/BookController.java#L192)）。只影响 CTR 报表口径，不影响排序；与 11.11-A 独立，可后做。
  - **已拍板（2026-11-08｜第二批·随 11.11-A 顺手做）**：不单独立项，随 11.11-A 一并实施；只影响 CTR 报表口径。
  - ✅ 2026-11-08（运行态实测通过｜查库确认）：`FeedExposureSupport#recordClick` 增加 `authorId` 入参与「作者 == 浏览者」短路；`ArticleController`、`BookController` 调用点已同步。实测 `feed_exposure_daily`（target 5004，`from=feed&snapshotId=1`）：
    - 作者 2002 拉自己的推荐流并点击 5004 → 该行 `exposure_count` 保持 1、`click_count` 保持 0（未 +1）。
    - 读者 2003 拉流后点击同一条 → 该行 `exposure_count=2`、`click_count=1`（非作者正常 +1）。
  - **11.11-C1（Phase 1｜影子统计，不拦截）异常频次三维度**：实现 R2 单用户持续高频（600s / 80 次）、R3 单内容突增（10min / 25 赞、15 藏，且为 24h 基线的 3 倍）、R4 匿名指纹泛滥（10min / 50 篇）的计数与命中标记；约 150~200 行，无 DDL。Phase 1 只记录「本应命中」与原因，不拒绝、不停计数，先验证阈值误伤。
  - **已拍板（2026-11-08｜延后）**：等有真实互动量、能看出稳定 24h 基线后再做，避免在无样本时拍阈值。**重启条件**：11.6 的曝光/点击数据已能支撑三维度统计，且单用户/单内容/匿名的真实频次足以形成稳定基线（具体阈值由实施前数据复核确认）；C2、C3 随 C1 顺延。本条保留在清单，不视为遗漏。
  - **11.11-C2（Phase 2｜阈值验证后执行处置）**：按 Phase 1 数据复核阈值后启用处置——R2 超限拒绝、R3 仅打标不拦截、R4 只停计数不断访问；复用 Phase 1 统计口径，不新增表。
  - **11.11-C3（Phase 3｜复盘与可选加固）**：阈值调优、指标/告警接入与误伤复盘。本阶段明确**不做**多账号/IP 关联、设备指纹、独立风控服务（不在本批次范围）。
  - **追溯**：环境验证清单原表已移入归档文件，实测依据以该处为准；四条未验证风险已登记 [constraints R4~R7](../constraints/constraints-index.md)，不进待办区。

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

##### 批次十三：自研 RedisGuard 熔断降级切换 Resilience4j（已归档）

> 13.1~13.11 共 11 项已于 2026-11-08 完成并归档，见 [common-redis-13-resilience4j.md](../archive/common-redis/common-redis-13-resilience4j.md)（唯一副本：含核查结论、实施细节、验证证据，以及 4 条待真实审核流量复核的 Moderation 告警说明）。**编号不重排**，本文件不再保留批次十三正文。
> 归属说明：本批次是 common-redis 基础设施改造（含 Prometheus 抓取/告警联动），不归属 content/search/review/file，归档落点 `docs/archive/common-redis/`。

-------------------------

#### 基础设施与安全加固（批次十四：批次十三配套遗留）

> 编号说明：批次十三已归档收口，本项作为新批次十四第 1 项登记，避免回写已归档批次；与 11.x 反作弊、13.x 限流降级均不混排。

##### 14.1 验证码与文件上传纳入熔断覆盖评估（待评估）

> **为什么单独立项**：批次十三的 breaker 只包住 `RedisRateLimiter` 的限流调用；验证码与文件上传是「在线必经 + 无本地兜底」，不属于限流降级，混进其他小节会掩盖真实风险面。本项只做评估，不在本轮实施。
> **判断依据**：全仓 38 个直连 Redis 的业务类中，[CaptchaServiceImpl.java](../../litisle-auth/src/main/java/com/litisle/litisleauth/service/impl/CaptchaServiceImpl.java)（验证码）与 [FileUploadServiceImpl.java](../../litisle-file/src/main/java/com/litisle/litislefile/service/impl/FileUploadServiceImpl.java)（文件上传）直接依赖 Redis 完成在线必经步骤，且没有本地兜底；Redis 故障时不是「降级限流」，而是功能不可用。

- [ ] 【评估｜P1】14.1 验证码与文件上传纳入 breaker：按资源名拆分熔断实例（`captcha` / `file-upload`），覆盖真正的 Redis 调用点，而不是继续只包限流
  - 代价：约 1 天；需要按资源名建多个 breaker 实例（避免一个资源拖垮另一个），并补「打开后的业务返回」与「恢复探测」用例；不改 DDL、不新增中间件。
  - 验收：Redis 故障时两条链路进入 OPEN 并按既定策略返回可见错误（或本地兜底），Redis 恢复后自动半开探测回 CLOSED；限流 breaker 与业务 breaker 指标可区分。
  - 边界：file 模块另有 Sentinel `@SentinelResource`（加载情况见 constraints R8），本项不替代 Sentinel，只评估是否需要用统一的 Resilience4j 口径覆盖。
  - **已拍板（2026-11-08｜第四批·大工程）**：单独立项、单独排期，不与本轮小改动混做。

-------------------------

#### content 结构与口径治理（批次十五：2026-11-08 立项）

> 编号说明：批次十三（common-redis）、批次十四（基础设施）之后，本轮内容侧两项独立登记为批次十五，使用 `15.1` / `15.2`，不与已归档的 11.1~11.17 混排。两项均为结构调整/口径统一，不改业务语义。

##### 15.1 content `support` 包收敛（代码已收敛，文档已同步）

> 已完成并归档，见 [content-15-structure-governance.md](../archive/content/content-15-structure-governance.md) 的 15.1 节（2026-11-08）；现状核对、改动文件清单、验收与风险说明均在归档文件。

##### 15.2 客户端 IP 解析统一 + `unknown` 字面量提取

- [~] 【P1｜15.2-A】统一 content 内两份 IP 解析口径
  - **现状（口径不同）**：
    - [ClientIpSupport.java:21-28](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/ClientIpSupport.java#L21-L28)：`X-Forwarded-For` 首段 → `remoteAddr`，**不读 `X-Real-IP`**，取不到可能返回空串；调用点 [ArticleController.java:170](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/ArticleController.java#L170)（文章浏览去重）、[BookController.java:229](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/BookController.java#L229)（章节浏览去重）。
    - [FeedRateLimitSupport.java:80-95](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedRateLimitSupport.java#L80-L95)：`X-Forwarded-For` 首段 → `X-Real-IP` → `remoteAddr` → `"unknown"`；用于匿名推荐流限流键（[同文件:70](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedRateLimitSupport.java#L70)）。
  - **统一口径（必须一致）**：`X-Forwarded-For` 首段 → `X-Real-IP` → `remoteAddr` → `unknown`。这是 `FeedRateLimitSupport`、common-redis `RateLimiterKeyBuilder`、auth `IpUtil`、`IdempotentAspect` 已有的口径；统一到它等于把 `ClientIpSupport` 补齐，而不是悄悄改匿名限流键的算法。
  - **改法**：`ClientIpSupport` 改为完整四级回退（并复用 `HeaderConstant.X_FORWARDED_FOR` / `X_REAL_IP` 代替字面头名）；`FeedRateLimitSupport` 的私有方法与 `ClientIpSupport` 合并为一份，二者调用同一组件；`FeedRateLimitSupport` 不再自带解析副本。
  - **行为影响**：`ClientIpSupport` 的调用点从「不读 X-Real-IP、可能空串」变为「读 X-Real-IP、兜底 unknown」，匿名浏览去重键会更稳定；匿名推荐流限流键口径不变。
  - **动哪些文件 / 代价**：2 个类（`ClientIpSupport`、`FeedRateLimitSupport`）+ 调用点无需改签名；无 DDL、无配置。
  - **验收**：同一请求分别经浏览与限流两条路径解析出的 IP 相同；构造仅有 `X-Real-IP`、仅有 `remoteAddr`、四者全缺三种请求，分别断言取到 `X-Real-IP`、`remoteAddr`、`unknown`。
  - **已拍板（2026-11-08｜第二批·必修，需回归）**：统一口径为 **XFF → X-Real-IP → remoteAddr → unknown**，这是硬性要求；不得改成其他顺序或省略 `X-Real-IP`，否则等于静默改变匿名限流行为。回归文章详情、章节阅读、匿名刷流三种身份。
  - ⏳ 2026-11-08（代码已改 + 限流/去重冒烟通过；`unknown` 用例不可构造）：`ClientIpSupport` 已统一为四级回退；`FeedRateLimitSupport` 删除自带解析、改注入 `ClientIpSupport`；编译通过。实测（content 8085 + Redis 6380）：①XFF=10.8.8.8 连发 21 次匿名推荐流，前 20 次 `code=0`、第 21 次 `code=10005`，限流键 `rate_limit:feed:ip:10.8.8.8:stream`=21；②仅带 `X-Real-IP=10.7.7.7` 的请求，限流键落在 10.7.7.7（统一组件生效）；③文章详情分别仅带 `X-Real-IP=10.5.5.5` 与四头全不带（`remoteAddr=127.0.0.1`），均生成对应 `content:article:view:dedup:5001:a<hash>` 键。未验证：四头全缺的 `UNKNOWN_IP` 分支在真实 HTTP 下无法构造（`remoteAddr` 恒有值），仅代码评审确认，故不标 `[x]`。

> —15.2-B 已完成并归档，见 [content-15-structure-governance.md](../archive/content/content-15-structure-governance.md) 的 15.2-B 节（2026-11-08，含 `unknown` 使用点清单与常量落点决策）。

-------------------------

#### 服务间调用与 MQ 基础设施治理（批次十六）

> 编号说明：本条为跨服务基础设施缺陷（common-security 的 Feign 拦截器 + MQ 消费线程），不属于内容结构调整，独立登记为批次十六，使用 `16.1`，不与 15.x 混排。

##### 16.1 Feign 拦截器在无 SaToken 上下文线程抛异常，导致索引同步消费重投 / 进 DLQ

> 16.1 已完成并归档，见 [common-security-16-feign-interceptor.md](../archive/common-security/common-security-16-feign-interceptor.md)（2026-11-08）；现象、根因、影响面实测、修法取舍、改动与实测证据均在归档文件。

- [~] 【P1｜16.1-V1】验证其余 MQ 消费者路径的 Feign 降级
  - **待验路径**：auth `PunishResultConsumer` → `ReviewClient`；review 文本/图片审核消费者 → `ContentClient` / `SocialClient` / `FileClient`；content `UserBannedConsumer`、`ModerationResultConsumer`、`ModerationAppealResultConsumer` → `ReviewClient` / `FileClient`；review 定时任务 `QuarantineSweeper`、`PunishReconcileSweeper`。
  - **未验原因**：本轮只触发 search 三条索引链路；构造审核 / 处罚事件需要额外测试数据与审核服务联动，且这些路径复用同一个 `FeignAuthInterceptor`（代码层面同一修复点）。
  - **验收**：上述每条链路各触发一次，日志无 `SaTokenContextException`、无重投，下游回查返回 200。

-------------------------

#### 分布式锁工具与消息中间件凭证收尾（批次十七：2026-11-08 立项）

> 编号说明：批次十三已完成 RedisGuard→Resilience4j 改造，本批次承接其配套的分布式锁收尾（lock 工具语义、调用方租期、自造 SETNX 锁迁移评估）与 auth 消息凭证对齐，编号 17.1~17.5，不重排已有 16.x / 15.x。17.1 本次已完成并附证据，17.2~17.5 待办。

##### 17.1 common-redis 锁工具整改（已完成 ✅）

- [x] 【P0｜17.1】`DistributedLock` 补固定租期重载 + 前缀约定与解锁留痕统一
  - **现状**：`DistributedLock` / `RedissonLock` 只有三参 `tryLock(key, waitTime, unit)`，底层走 `tryLock(waitTime, -1, unit)` 即 Redisson 看门狗（租期自动续期，直到进程退出或显式 unlock）；工具内部再拼 `CacheConstant.LOCK_PREFIX`，而调用方 key 已自带 `lock:`，content 8 处实际落地键变成 `lock:lock:xxx`；`unlock` 对未持有锁与异常全部静默吞掉；两个接口注释把 `tryLock` 说成「立即返回」、把 `waitTime` 说成租期。
  - **为什么做**：临界区为「查库 + 占位写库」这类毫秒级短任务时，看门狗会在调用线程卡死（死锁/长时间 GC/挂起）期间无限续租，锁一直不释放；双前缀让同一语义的键分裂，运维按 `lock:` 排查会漏；解锁静默使「未持有锁 / 重复解锁」无从观测。
  - **改动明细**：
    - 新增固定租期重载 `tryLock(key, waitTime, leaseTime, unit)`：`leaseTime <= 0` 抛 `IllegalArgumentException`，走固定租期、**不启用看门狗**；旧三参重载语义不变（仍为看门狗）。
    - key 前缀约定改为「工具不加前缀、调用方给全名且含 `lock:`」；`CacheConstant.LOCK_PREFIX` 保留供调用方拼接；file 两处补前缀，content 8 处 key 未改代码，但落地键由 `lock:lock:xxx` 变为 `lock:xxx`。
    - `unlock` 不再静默：未持有锁 / `IllegalMonitorStateException` / 其他异常分别留痕（级别区分），签名保持 `void`，不改变调用方契约。
    - 修正 `DistributedLock`、`RedissonLock` 注释：`tryLock` 是「最多等待 waitTime」，`waitTime` 不是租期；固定租期语义指向新重载。
  - **验收标准**：固定租期重载在 `leaseTime <= 0` 时抛 `IllegalArgumentException`；持锁线程不主动 unlock 时锁在 leaseTime 到期后自动释放（无续期）；旧三参重载行为不变；解锁异常有日志可检索；content 实际锁键为 `lock:content:...` 单前缀。
  - **涉及文件**：[DistributedLock.java](../../litisle-common/common-redis/src/main/java/com/litisle/commonredis/lock/DistributedLock.java) · [RedissonLock.java](../../litisle-common/common-redis/src/main/java/com/litisle/commonredis/lock/RedissonLock.java) · [CacheConstant.java:162](../../litisle-common/common-redis/src/main/java/com/litisle/commonredis/constant/CacheConstant.java#L162) · [FileReconcileTask.java:51](../../litisle-file/src/main/java/com/litisle/litislefile/task/FileReconcileTask.java#L51) · [FileUploadServiceImpl.java:99](../../litisle-file/src/main/java/com/litisle/litislefile/service/impl/FileUploadServiceImpl.java#L99) · content 8 处 key（未改代码，仅落地键变化）：[ContentBizConstant.java:127-130](../../litisle-content/src/main/java/com/litisle/litislecontent/constant/ContentBizConstant.java#L127-L130)、[ArticleScheduledPublishJob.java:43](../../litisle-content/src/main/java/com/litisle/litislecontent/job/ArticleScheduledPublishJob.java#L43)、[ContentStatWindowJob.java:46](../../litisle-content/src/main/java/com/litisle/litislecontent/job/ContentStatWindowJob.java#L46)、[EventOutboxResendJob.java:54](../../litisle-content/src/main/java/com/litisle/litislecontent/job/EventOutboxResendJob.java#L54)、[BookServiceImpl.java:433](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/BookServiceImpl.java#L433)、[FeedHotRankSupport.java:88](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java#L88)、[FeedServiceImpl.java:124](../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedServiceImpl.java#L124)、[FeedHotRankRefillJob.java:66](../../litisle-content/src/main/java/com/litisle/litislecontent/job/FeedHotRankRefillJob.java#L66)
  - ✅ 2026-11-08：9 个模块 `mvn compile` 全部 SUCCESS（锁工具改动为纯新增重载 + 前缀口径与日志调整，旧调用方行为不变）。

-------------------------

##### 17.2 `FileUploadServiceImpl` 秒传判重改固定租期

- [ ] 【P1｜17.2】秒传判重锁由看门狗改固定租期
  - **现状**：`FileUploadServiceImpl` 秒传判重当前为 `tryLock(DEDUP_LOCK_KEY_PREFIX + sha256, 5, TimeUnit.SECONDS)`（waitTime=5s、租期 -1 → 看门狗续期）。
  - **为什么做**：临界区只有「查库 + 占位写库」，是毫秒级短任务；用看门狗意味着持锁线程卡死时锁被持续续期，同 sha256 的上传会被阻塞到进程退出。
  - **改法**：改为 `tryLock(key, 5, 10, TimeUnit.SECONDS)`（等锁 5s、固定租期 10s），不改 key 与业务逻辑。
  - **验收标准**：上传并发下同一文件不会重复落库；模拟持锁线程异常/卡死时锁在 10s 内自动释放，后续请求恢复。
  - **涉及文件**：[FileUploadServiceImpl.java:184](../../litisle-file/src/main/java/com/litisle/litislefile/service/impl/FileUploadServiceImpl.java#L184)（调用点）与同文件 [L99](../../litisle-file/src/main/java/com/litisle/litislefile/service/impl/FileUploadServiceImpl.java#L99)（`DEDUP_LOCK_KEY_PREFIX`）。

-------------------------

##### 17.3 review 自造 SETNX 锁的迁移评估

- [ ] 【评估｜17.3】review 四处 sweeper 的 SETNX 锁能否迁到固定租期重载
  - **现状**：`QuarantineSweeper`、`PunishReconcileSweeper`、`ReviewTaskSweeper`、`WordlibMisreportCleanupSweeper` 各自复制一套 `SETNX + token + GET 比对后 DEL`（释放非原子）；`ModerationTextService#tryLockInFlight` 只 SETNX、靠 TTL 自愈。
  - **为什么做**：四套手写锁分散维护，释放非原子在并发下可能误删他人的锁；统一到 common-redis 工具可减少重复实现，但**语义不等价**（sweeper 依赖 TTL 自愈、且拿不到锁就跳过本轮，没有「等待」诉求），不能直接把看门狗重载照搬过去。
  - **改法**：逐个评估——可迁的改用固定租期重载（租期取原 TTL，`waitTime=0` 保持「拿不到即跳过本轮」）；不可迁的明确保持 TTL 语义并注明原因。
  - **验收标准**：给出迁移方案与风险结论，逐项明确哪些可迁、哪些保持 TTL 语义及理由；不改变 sweeper 的「无锁跳过」行为与定时频率。
  - **涉及文件**：[QuarantineSweeper.java](../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/QuarantineSweeper.java) · [PunishReconcileSweeper.java](../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/PunishReconcileSweeper.java) · [ReviewTaskSweeper.java](../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/ReviewTaskSweeper.java) · [WordlibMisreportCleanupSweeper.java](../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/WordlibMisreportCleanupSweeper.java) · [ModerationTextService.java](../../litisle-review/src/main/java/com/litisle/litislereview/moderation/service/ModerationTextService.java)

-------------------------

##### 17.4 auth 的 RabbitMQ 凭证与其余服务对齐

- [ ] 【P1｜17.4】auth 补齐 RabbitMQ `username`/`password`
  - **现状**：`litisle-auth` 的 `spring.rabbitmq` 只配了 `virtual-host: /litisle`，未配 `username`/`password`，走 Spring Boot 默认 `guest@localhost`；content/review/file/user/search 均显式使用 `litisle` / `litisle_pass`。本机 guest 被授予 `/litisle` 权限所以能跑，生产环境 guest 默认禁止远程登录/无该 vhost 权限，auth 队列消费会失败。
  - **为什么做**：凭证口径必须全服务一致，避免「本机可跑、上线即挂」。
  - **改法**：auth yml 补 `username: ${litisle.rabbitmq.username:litisle}` 与 `password: ${litisle.rabbitmq.password:litisle_pass}`，与其余服务同一套默认值与环境变量。
  - **验收标准**：auth 与其余服务使用同一套凭证；`/litisle` vhost 下 auth 队列正常消费（处罚结果等消息可收）。
  - **涉及文件**：[litisle-auth application.yml:64-67](../../litisle-auth/src/main/resources/application.yml#L64-L67)；对齐基准：[content application.yml:73-75](../../litisle-content/src/main/resources/application.yml#L73-L75) · [review application.yml:59-61](../../litisle-review/src/main/resources/application.yml#L59-L61) · [file application.yml:86-88](../../litisle-file/src/main/resources/application.yml#L86-L88) · [user application.yml:56-58](../../litisle-user/src/main/resources/application.yml#L56-L58)。

-------------------------

##### 17.5 测试数据清理（待执行，需用户确认）

- [ ] 【数据清理｜17.5】清理本轮验证残留数据（**只登记 SQL，未执行**）
  - **现状**：本轮验证在库中仍留有测试数据——`litisle_review.punish_log` id=2/3/4、`litisle_review.review_task` id=2/3、`litisle_review.moderation_record` id=2、`litisle_content.article_image` id=1、`litisle_file.file_ref`（`file_id='25fd4ac95b6248ec995dd5dd4205d326'`、`biz_type='article'`、`biz_id=5008`）。
  - **为什么做**：避免验证数据污染审核列表、处罚记录与文件引用对账口径。
  - **待确认点**：①执行窗口与是否需要先备份；②`litisle_file.file_meta` 对应记录的 `safe_flag` 原值需确认（作为回滚参照）；③`litisle_content.article` id=5008 已回到原值 4，**无需 UPDATE**。
  - **SQL（登记待确认，禁止直接执行）**：
    ```sql
    -- litisle_review
    DELETE FROM punish_log WHERE id IN (2, 3, 4);
    DELETE FROM review_task WHERE id IN (2, 3);
    DELETE FROM moderation_record WHERE id = 2;

    -- litisle_content
    DELETE FROM article_image WHERE id = 1;

    -- litisle_file
    DELETE FROM file_ref
     WHERE file_id = '25fd4ac95b6248ec995dd5dd4205d326'
       AND biz_type = 'article'
       AND biz_id = 5008;
    ```
  - **验收标准**：上述行均不存在；`article` id=5008 与 `file_meta` 的 `safe_flag` 为预期原值；无其他业务数据被误删。
  - **涉及文件**：数据库 `litisle_review` / `litisle_content` / `litisle_file`（无代码改动）。

-------------------------

## 暂不实现汇总

#### content模块

#### search模块

- ~~user_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user_idx），此项从「暂不实现汇总」移出，原触发条件已满足
