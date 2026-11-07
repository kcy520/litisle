> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，内容迁自 [next_update.md](../../next/next_update.md)「批次十二：11.11 屏蔽下代生效 + 11.16 对账 0 值可观测」的已完成条目（2026-11-07 归档）。条目原文与实测证据**原样搬运，未压缩**。
> **归档范围**：12.1 / 12.2 / 12.3 共 3 项。
> **仍在主清单**：批次十二「已知风险 R1~R3」为风险登记、不是待办任务，保留在 [next_update.md](../../next/next_update.md)；11.11 的剩余实施（作者自互动过滤、异常频次规则）见 [content-11-interaction-stats.md](content-11-interaction-stats.md) 的归档说明。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；上一批次归档见 [content-11-interaction-stats.md](content-11-interaction-stats.md)；本模块其他归档见 [content-implemention-records.md](content-implemention-records.md)。

## 一、背景（迁自 next_update.md，原文搬运）

> 来源：批次十一环境验证发现的两处缺陷 + 全新初始化验证连带发现。**代码修复已完成**，本批次收口正式验收与遗留风险处置。

## 二、已完成任务条目（迁自 next_update.md，原文搬运）

### 12.1 11.11 作者/分类屏蔽在下一代快照生效（已收口 ✅）

- 现象（批次十一清单第 8 项）：屏蔽作者后建池候选为空 → 走 last-good 兜底，兜底页不应用负反馈名单 → 新代仍返回被屏蔽作者内容。
- 根因：[FeedStreamServiceImpl](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedStreamServiceImpl.java) 6 处 last-good 兜底调用（Redis 不可用/建池失败/空池 × article/book）未传 userId，`lastGoodArticlePage`/`lastGoodBookPage` 不做负反馈过滤；建池路径本身有过滤，非「没读名单」。
- 修复：兜底页统一经新增 `filterArticleIdsByFeedback`/`filterBookIdsByFeedback`（内容 ID/作者 ID/分类名三维度）过滤后再切片；建池与切片口径不变。
- 已实测（2026-11-06，证据链均为「旧快照含目标 → 屏蔽 → 触发下一代 → 目标消失」）：article 作者屏蔽 gen1 含 5099→屏蔽 2003→gen2 剔除；article 分类屏蔽（合法枚举「散文」）gen4 含 5099→屏蔽→gen5 剔除；book 作者屏蔽 gen1 含 6199→屏蔽 2003→gen2 剔除；空池→last-good 兜底页不放出被屏蔽内容。
- [x] ✅ 2026-11-06 收口实测（批次十二执行）：三维度+撤销恢复全链路复测通过——①不感兴趣（type=1，targetId=5099）：池 gen1 含 5099 → 屏蔽 → 同快照重放**当页消失**（切片过滤后由 5003 补位）→ 触发 gen4 快照原文无 5099 → 撤销 → 重建池恢复；②屏蔽作者（type=2，2003）：gen5 含 5099 → 屏蔽 → 旧快照重放仍见（既有语义）→ gen6 剔除 → 撤销 → 恢复；③屏蔽分类（type=3，「散文」）：gen7 含 5099 → 屏蔽 → 旧快照仍见 → gen8 剔除 → 撤销 → 恢复；④书籍流作者（stream=book，2003）：gen1 含 6199 → 屏蔽 → 旧快照仍见 → gen2 剔除 → 撤销 → 恢复。测试数据已清场。

### 12.2 11.16 对账差异 0 值可观测（已收口 ✅）

- 现象：差异为 0 时 Counter 不创建，Prometheus 指标缺席，「无数据」与「一切正常」不可区分。
- 选型：**Gauge 预注册**。理由：对账差异是「当前状态量」（每轮覆盖为本轮差异数，0 本身是有效观测值），Counter 表达累计事件、0 值打点无意义且 meter 不创建。实现：[ContentMetrics](../../../litisle-content/src/main/java/com/litisle/litislecontent/metrics/ContentMetrics.java) `@PostConstruct` 一次性注册 6 类 type 的 `litisle_content_stat_reconcile_diff` gauge（AtomicLong 强持有防弱引用回收、AtomicBoolean 防重复注册、标签固定 6 值不动态创建）；`recordStatReconcileDiff` 改 `set()` 覆盖、0 也写入；[ContentStatReconcileJob](../../../litisle-content/src/main/java/com/litisle/litislecontent/job/ContentStatReconcileJob.java) 每轮 6 类都上报（含 0）。
- 已实测：重启后 6 条 gauge 全部可见 `0.0`（type=article_collect/article_comment/article_like/article_view/book_collect/book_reader_under，均带 application="litisle-content"）。
- [x] ✅ 2026-11-06 收口实测（批次十二执行）：扰动 `article.like_cnt`（+1，镜像无周期自愈任务）制造 1 行差异 → 以 JVM 系统属性运行时覆盖对账参数（initial-delay 15s/interval 60s/threshold 0，仅验证用、验完还原）重启 → 首轮对账 `ERROR [统计对账] 差异超阈值：total=1, articleLike=1`，gauge `type="article_like" 1.0`；还原数据后次轮 `INFO 完成：total=0, articleLike=0`，6 条 gauge 全部回 `0.0`。已按正常配置（每日一轮、threshold 50）重启还原运行态。注：`@Scheduled` 占位符不走 relaxed binding，环境变量方式覆盖无效，必须用 `-D` 系统属性（已验证）。

### 12.3 user schema 补列与全新初始化（已完成 ✅）

- 依据：[Sql-Document.md](../../Sql-Document.md) §二 sys_user 已含 phone_hash/email_hash/uk_phone_hash_del/uk_email_hash_del（email varchar(255)）；与 [User.java:65-71](../../../litisle-user/src/main/java/com/litisle/litisleuser/domain/entity/User.java#L65-L71) 实体一致，user-schema.sql 原缺。
- 变更：user-schema.sql 补 `phone_hash varchar(64) NOT NULL` + `email_hash varchar(64) NULL` + 唯一键 `uk_phone_hash_del(phone_hash,del_flag)`/`uk_email_hash_del(email_hash,del_flag)`（列注释注明依据来源）；user-seed.sql 9 行全部带 `SHA2(phone,256)`（phone_hash NOT NULL 必须带值，否则 INSERT IGNORE 静默跳过；2001~2003 密文手机号对密文取 SHA2）。
- 全新初始化实测（一次性 mysql:8.0 容器挂 `docker/init/mysql` 走真实 entrypoint + .env 完整环境）：00→01→02→03→10→20 全套无 ERROR；sys_user 结构与 User.java 全部 @TableField 对齐（无 Unknown column 风险）；种子 9 行 phone_hash 全非空、email_hash 全 NULL（预期）；中文分类 hex 正确无双重编码。临时容器与匿名卷已删。
- [x] ✅ 本条已完成，无需收口。

## 三、归档说明

- 本批次共归档 3 项（12.1~12.3）。
- **未归档**：「已知风险 R1~R3」是风险登记而非待办任务，按四要素（现象 / 触发条件 / 影响 / 处置方式）保留在主清单。
- 编号空缺说明：主清单批次十二仅保留风险登记，12.1~12.3 的空缺指向本文件；编号不重排。
- **2026-11-07 二次整理（用户授权）**：批次十二的背景/来源说明已随 12.1~12.3 整体收录于「一、背景」与「二、已完成任务条目」，主清单只留一行指针与「已知风险 R1~R3」（风险登记不属于归档范围，按四要素留在主清单）。
