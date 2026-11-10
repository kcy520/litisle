# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分
- 需环境实测的验证任务：见 [next\_verify.md](next_verify.md)
- 已知约束与风险（非待办）：见 [constraints-index.md](../constraints/constraints-index.md)

### 文档说明:

- 任务内容与落地摘要直接记录在本文档条目内，不引用 status.md 细案
- **代码与验证分文件**：纯代码改造、待决策、待外部条件留在本文档；需起服务/并发/真实流量/动库窗口/中间件就绪的验证任务统一放在 [next\_verify.md](next_verify.md)，同一任务只存一份（迁移是移动，不是复制）
- **状态标记约定**：任务完成只用 `- [x]`（随后按归档规则归档并留指针）；如无法使用，只允许正文中的子项 使用`✅` 。

### 文档模版

\*\* next\_update.md模版: \*\*

```
# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 任务内容与落地摘要直接记录在本文档条目内，不引用 status.md 细案
- 代码与验证分文件：验证项（V{n}）统一写入 next_verify.md，不在本文件挂载

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

##### V{n} {验证主题}（写入 next_verify.md，不在本文件挂载）

- [ ] {验证内容}（依赖{前置板块/模块}）

-------------------------

## 暂不实现汇总

#### {模块名}模块
```

### 归档与暂不实现（板块完成后的文档迁移规则）

**相关文档职责**：

| 文档                              | 职责                                                                                                                                                                       |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `docs/next/next_update.md`（本文档） | 仅收纳待办、代码改造、待决策、待外部条件任务，按 模块 → 板块/任务组织                                                                                                                                    |
| `docs/next/next_verify.md`      | 只收纳运行态验证/环境实测类任务（代码已改完待验证、必须实测才能定论），验完按下方规则归档                                                                                                                            |
| `docs/next/temporarily/`        | 收纳暂不实现/暂不做/挂起项（索引 Temporarily\_not\_implemented.md + temporarily-01\~04 正文文件，2026-10-28 拆分），每条必须附触发条件                                                                    |
| `docs/archive/`                 | 已完成板块的实现记录归档：按模块分子目录（如 `content/`、`review/`），每目录一个同名索引页 + 按板块拆分的板块文件（如 `content/content-09-temp-checklist.md`）；日期型归档（如 `20260929-implementation-records.md`）放 archive/ 根 |

**板块完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将该板块的实现步骤/完成记录（原散落在本文档条目内）整理移入 `docs/archive/` 对应归档文档（新模块新建归档子目录 + 同名索引页，遵循上方命名惯例；已有同模块归档则追加进对应板块文件，如临时清单批次追加进 content-09-temp-checklist.md）。
2. **本文档回填**：将已完成条目从本文档删除（或标注 ✅ 与日期后移除），保持本文档只留活跃待办。
3. **挂起项转移**：板块中因宿主模块未开发而无法落地的事项（如契约已备但入口未建），移入 `docs/next/temporarily/` 对应部分文件（代码类挂起进 temporarily-01-code.md，预留/暂不实现进 temporarily-04-deferred.md），并写明触发条件，供宿主模块开发时迁回本文档。
4. **预留项登记**：板块规划中明确「暂不实现/暂不做」的事项（含触发条件与决策原因），一并移入 temporarily-04-deferred.md，不在本文档保留。

> 纪律：迁移时条目内容原样搬运（含口径修正、注意事项），不压缩关键细节；归档文档与本文档/Temporarily 清单之间用相对路径互链，保证可回溯。

### 已完成项归档指引（2026-11-07 建立，2026-11-08 折叠为总指针）

> 已完成并归档的条目见 [docs/archive/](../archive/) 下各模块索引页，本清单不再逐条登记。
> **编号一律不重排**：本文件按编号被引用（「按 11.1 口径」「见 10.3」等），归档后留下的编号空缺不要按新序号去理解旧引用。

### 执行排期（2026-11-08 用户拍板）

> 状态口径：「已拍板待执行」保持 `- [ ]` / `- [~]`，**不改成** **`- [x]`**；`- [x]` 仅表示实际已完成。下表只反映执行顺序，不代表已经实施。
> 需环境实测的条目（9.8-P2、15.2-A、10.7、10.8、9.4、9.5、9.6-A、14.1、17.5 及各 V{n}）正文已迁至 [next\_verify.md](next_verify.md)，本表只保留执行顺序口径。

| 批次            | 条目                               | 一句话说明                                     |
| ------------- | -------------------------------- | ----------------------------------------- |
| 第一批（零风险，先做）   | 15.1、15.2-B、9.6-B                | 仅文档同步、常量引用替换、测试依赖作用域清理，不改变运行时行为           |
| 第二批（必修，需回归）   | 9.8-P1、11.11-A（含 11.11-B）、15.2-A | 改行为；回归文章详情、章节阅读、匿名刷流三种身份                  |
| 第三批（需 DDL）    | 9.8-P2                           | 四张表升 `datetime(3)`，攒到下次动库时一起执行            |
| 第四批（大工程，单独排期） | 9.6-A、14.1                       | Testcontainers 集成测试；验证码/文件上传纳入 breaker 评估 |
| 延后            | ~~11.11-C1~~（2026-11-09 已归档）；10.7、10.8 | 影子统计等真实互动量；两个端到端验证并入下次环境验证                |
| 暂不实现（预留项）     | feed 快照熔断（2026-11-09 登记）        | Redis 黑洞型故障熔断，复用 common-redis 已有 Resilience4j 熔断器；触发条件见 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)，未触发不排期 |
| 保持不做          | 9.8-P3                           | 单调版本号，不进入实施                               |

***

## 待办部分

#### content模块

> 板块划分依据：Content\_Function\_Develop.md / Content-Architecture.md；推进顺序：板块一 → 二 → 三 → 四/五/六（可并行）→ 七 → 八（依赖 review 板块三 3-5 消费端联调）

> **板块一\~八已全部完成并归档**（2026-10-20）：实现记录见 [content-implemention-records.md](../archive/content/content-implemention-records.md)（next\_update 条目摘要与实现细案，原样搬运；2026-10-28 拆分为九板块文件，本页为索引）
> 验证项 V1、V2 已迁入 [next\_verify.md](next_verify.md)（与 9.1 同名条目同源）；本文件不保留验证条目。

***

#### search模块

> 板块划分依据：[Search-Architecture.md](../Architecture/Search-Architecture.md) §九 + [Search-Business\_Guide.md](../Study/Search-Business_Guide.md) §四缺口清单（G1\~G8）+ temp.md search模块 批次A\~D + 2026-11-03 设计审查（发现 6 项重大缺陷 A1\~A6）；推进顺序：批次一 → 二 → 三 → 四 → 五 → 六 → 七 → 八。
> **搜索归属核查结论（2026-11-03，精准扫描 content 控制器）**：
>
> - 不迁移（保留 content）：`GET /article/my`、`GET /book/my` 的 keyword 过滤（作者管理侧列表，author\_id 硬收口，DB 直查合理）；`GET /tags?keyword=` 前缀搜索（tag 池为 content 域元数据，SearchTypeEnum 无 TAG，标签搜索≠全文检索）；`GET /article/list`、`/article/hot`（结构化筛选列表，无关键词）。
> - 归属 search（既定设计，`BookController` `/book/list` 注释已声明「一期不做 keyword 搜索（search 模块书籍索引为外部依赖）」）：公开文章/书籍/用户的全文与多维度检索 + 相似推荐。
> - **~~发现的遗漏~~**~~：book\_idx / user\_idx 无索引同步事件来源（书籍创建/变更/完结/下架无事件、UserRegisteredEvent 为空类未发布）~~：**2026-11-04 已随批次五收口**（书籍事件 B4 + 用户事件 B5 + book\_idx/user\_idx 消费）。
>   **优先级口径（2026-11-03 定）**：优先 content 文章与书籍相关链路（批次一\~五），联想/相似/降级随后；user\_idx 同步链路已于 2026-11-04 随批次五 5.3 落地（见批次五）。
>   *批次A（缺陷修正定版 A1\~A7）/ 批次B 契约 B1/B2/B3/B7（SearchClient、social 事件、SEARCH\_* 错误码、article-updated/view-count-flushed 事件）/ 批次C 决策（C1\~C4）已于 2026-11-03 完成\*；批次B 余项 B6（回查字段核对）已于 2026-11-04 随批次二 2.1 收口，B4（书籍事件）/B5（用户事件）随批次五 收口。
>   批次八（8.1\~8.2）已完成归档（[search-09-degrade-observability.md](../archive/search/search-09-degrade-observability.md)）；V1\~V3 与 9.1 同名验证项已迁入 [next\_verify.md](next_verify.md)。

##### 批次九：收尾、验证与遗漏补齐（依赖批次一\~八；2026-11-05 复核）

> **复核口径**：按 批次1-2 / 3-4 / 5-6 / 7-8 四组逐条对照代码核查；已勾选条目未发现「勾选但代码缺失」，以下汇总未勾选验证项、只做一半的实现、过期标注与部署/体验缺口。
> 复核结论计数：批次1-2 发现 2 项、批次3-4 发现 2 项、批次5-6 发现 1 项、批次7-8 发现 6 项、content 跨模块 2 项，去重合并后见各分类清单。
> 类型说明：【必须】= 上线/联调前必须完成；【可选】= 优化项或已挂起项，按触发条件择机推进。
> **批次九其余条目**：9.1\~9.5、9.6-A、9.7、9.9 不构成本文件待办——9.1/9.4/9.5/9.6-A 已迁入 [next\_verify.md](next_verify.md)；9.2/9.3/9.7/9.9 已归档（[search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md)）或迁入挂起项（[temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)）。

###### 9.6 测试补齐（批次九）

> —9.6-A（Testcontainers 集成测试）已迁入 [next\_verify.md](next_verify.md)（2026-11-09，测试执行需 Docker/ES 容器就绪，属环境实测类）。
> —9.6-B 已完成并归档，见 [search-10-batch09-closing.md](../archive/search/search-10-batch09-closing.md) 的 9.6-B 节（2026-11-08）。

***

##### 批次十一：推荐互动数据与统计口径治理（2026-11-06 立项）

> 已完成 16 项（11.1\~11.10、11.12\~11.17）并归档；11.11 的三个部分（负反馈原文、A/B 已完成项、C1\~C3 延后项）已于 2026-11-09 全部归档至 [content-11-interaction-stats.md](../archive/content/content-11-interaction-stats.md)（**唯一副本**，C1\~C3 归档块含主流方案评审结论：重启信号量化、C2 的 R2 处置降档，用户已拍板采纳）。**批次十一全部条目均已归档，编号不重排**，本文件不再保留批次十一正文。

- \[\~] 【P1｜前端配合：是】11.11 反作弊与负反馈登记——**已于 2026-11-09 全部迁出主清单**：负反馈完成部分、11.11-A/B（作者自浏览/自点击排除，已实测通过）归档见 [content-11-interaction-stats.md](../archive/content/content-11-interaction-stats.md)「六」末尾；11.11-C1\~C3（异常频次三维度，延后）已登记挂起项 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md) content 节（**触发条件**：连续 7 天单内容日互动 ≥100 或单用户日互动 ≥20），三阶段设计与前端配合职责见 [Content-Architeche.md](../Architecture/Content-Architeche.md) §五；此处不再复述。

##### 批次十二：11.11 屏蔽下代生效 + 11.16 对账 0 值可观测（2026-11-06 立项）

> 12.1\~12.3 共 3 项已完成并归档，见 [content-12-batch12-closing.md](../archive/content/content-12-batch12-closing.md)；批次来源/背景、现象、根因、选型、修复与实测证据原文均在该文件（**唯一副本**）。**编号不重排**，本文件的「已知风险」节仅留迁出指针——R1、R2、R3 均已迁至 [docs/constraints/](../constraints/constraints-index.md)。

###### 已知风险（批次十二登记，按「现象 / 触发条件 / 影响 / 处置方式」四要素记录）

> 与上面的 12.1\~12.3 任务条目区分：本节是**风险登记**，不是待办任务；R2 原挂在主清单待办区，2026-11-09 执行修复并复核通过后已整体迁入 [constraints R2](../constraints/content-constraints.md)。**已知约束与风险统一登记在** **[constraints-index.md](../constraints/constraints-index.md)**。

> **指针**：R1（存量统计回填能力已移出初始化脚本）、R3（`00-init-databases.sql` 对 `MYSQL_USER` 的隐含依赖）已迁入 [docs/constraints/](../constraints/constraints-index.md)，原文、触发条件与处置方式完整保留；此处不再重复。

> **R2 已于 2026-11-09 收口迁出**：用户拍板执行路径 a（单次数据修复），脚本 [article-book-category-mojibake-fix.sql](../../tools/db-repair/article-book-category-mojibake-fix.sql) 修正口径（cp1252 双重编码实测 + HEX 白名单）后在运行库执行完毕并复核通过（article 31 行 / book 5 行，备份表可回滚，update_time 未污染），原文、执行记录与处置方式已整体迁入 [constraints R2](../constraints/content-constraints.md)；此处不再保留正文。

***

> 推荐流 Redis 黑洞型故障熔断（2026-11-09 登记，暂不实现）：降级链路残余风险（黑洞型故障无熔断、降级接口延迟被 command timeout 拉长）与候选方案已登记于 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md) content 节，推荐方案为「复用 common-redis 已有 Resilience4j 熔断器，feed 快照探测点（`FeedSnapshotSupport#isRedisAvailable`）接入同一 CircuitBreaker」。**触发条件**：黑洞型故障真实发生（或演练复现）且降级接口 P99 被拉长到 command timeout 量级、监控可观测时迁回本清单立项实施；未发生前仅登记不排期。此处只留指针，不复述正文。

***

> 批次十三：13.1\~13.11 已全部归档（[common-redis-13-resilience4j.md](../archive/common-redis/common-redis-13-resilience4j.md)，唯一副本）；本文件不再保留正文。

***

#### 基础设施与安全加固（批次十四：批次十三配套遗留）

> 编号说明：批次十三已归档收口，本项作为新批次十四第 1 项登记，避免回写已归档批次；与 11.x 反作弊、13.x 限流降级均不混排。

> —14.1 已迁入 [next\_verify.md](next_verify.md)（2026-11-09，评估结论与验收需 Redis 故障演练，属环境实测类）；编号不重排，本文件不再保留正文。

***

> 批次十五：15.1、15.2-B 已归档（[content-15-structure-governance.md](../archive/content/content-15-structure-governance.md)），15.2-A 已迁入 [next\_verify.md](next_verify.md)；本文件不再保留批次十五正文。

***

#### 服务间调用与 MQ 基础设施治理（批次十六）

> 16.1 / 16.1-V1 已完成并归档（2026-11-08），见 [common-security-16-feign-interceptor.md](../archive/common-security/common-security-16-feign-interceptor.md)。
> —16.1-V1 的两条旁路问题已于 2026-11-09 复核收口（用户拍板执行）：①文本 result 路由——`ModerationResultPublisher` 已按 bizType 解析路由键（1,2,4→`moderation.text.result`，Q87），content 队列已双向绑定主交换机与延迟交换机同名键；②`persistAndPublish` 事务同步注册已移入事务模板 lambda 内（Q88），两处修复均随 commit `053c808`（review 模块重构）落地；图片链路（image service 直发 `moderation.result` ↔ file 绑定同名键）自洽，发布器无 bizType=6 调用方；review/content 编译验证通过（2026-11-09）。编号不重排，本文件不再保留批次十六正文。

***

#### 分布式锁工具与消息中间件凭证收尾（批次十七：2026-11-08 立项）

> 编号说明：批次十三已完成 RedisGuard→Resilience4j 改造，本批次承接其配套的分布式锁收尾（lock 工具语义、调用方租期、自造 SETNX 锁迁移评估）与 auth 消息凭证对齐，编号 17.1\~17.7，不重排已有 16.x / 15.x。已归档：17.1/17.3/17.6/17.7 见 [common-redis-17-distributed-lock.md](../archive/common-redis/common-redis-17-distributed-lock.md)，17.2 见 [file-02-upload-dedup-lock.md](../archive/file/file-02-upload-dedup-lock.md)，17.4 见 [auth-17-rabbitmq-credential.md](../archive/auth/auth-17-rabbitmq-credential.md)；17.5 已迁入 [next\_verify.md](next_verify.md)（2026-11-09，动库窗口类，待用户确认后执行）。

##### 17.5 测试数据清理（批次十七）

> —17.5 已迁入 [next\_verify.md](next_verify.md)（2026-11-09，验证残留数据清理需动库窗口与用户确认，属环境实测类）。

***

#### AI 会话与生成内容清理（批次十八：2026-11-08 立项）

> 批次十八：18.1 经复核（2026-11-09）确认前置未满足——`litisle-ai` 模块无表、无实体、无 Mapper，无可执行的 AI 模块任务，用户拍板删除该批次；条目正文按挂起项转移规则原样迁入 [temporarily-01-code.md](temporarily/temporarily-01-code.md) 的「litisle-ai 模块」节（含触发条件：AI 表与实体/Mapper 落地时迁回）。编号不重排，本文件不再保留批次十八正文。

***

## 暂不实现汇总

#### content模块

#### search模块

- ~~user\_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user\_idx），此项从「暂不实现汇总」移出，原触发条件已满足

