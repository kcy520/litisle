# common-redis 批次十七：分布式锁工具整改与迁移评估（归档）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md)「批次十七：分布式锁工具与消息中间件凭证收尾」的已完成条目 17.1、17.3，正文原样搬运（现状 / 为什么做 / 改动明细 / 验收标准 / 涉及文件 / 实测证据 / 迁移评估结论与硬约束），**编号不重排**。
> **归档范围**：17.1（`DistributedLock` 固定租期重载与前缀、解锁留痕统一，9 模块编译通过）、17.3（review 自造 SETNX 锁迁移评估，结论为暂不迁移）。
> **未归档（仍在主清单）**：17.2（代码已改、运行态未验）、17.4（配置已改、未起服务验证）、17.5（测试数据清理待确认）、17.6（锁释放原子性待决策）。
> **归属说明**：17.1 是 common-redis 锁工具本体；17.3 的评估对象是 litisle-review 的 sweeper 锁，但结论落在「common-redis 锁工具的迁移口径与硬约束」，且与 17.1/17.6 同属分布式锁主题，故与 17.1 合并归档到本目录，不拆入 review 实现记录（review 归档记录的是已落地实现，本项明确未迁移）。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；模块速查见 [common-index.md](../../index/common-index.md)；本目录索引见 [common-redis-implemention-records.md](common-redis-implemention-records.md)。

## 板块十七：分布式锁工具整改与迁移评估（2026-11-08）

### 17.1 common-redis 锁工具整改（已完成 ✅）

- [x] 【P0｜17.1】`DistributedLock` 补固定租期重载 + 前缀约定与解锁留痕统一
  - **现状**：`DistributedLock` / `RedissonLock` 只有三参 `tryLock(key, waitTime, unit)`，底层走 `tryLock(waitTime, -1, unit)` 即 Redisson 看门狗（租期自动续期，直到进程退出或显式 unlock）；工具内部再拼 `CacheConstant.LOCK_PREFIX`，而调用方 key 已自带 `lock:`，content 8 处实际落地键变成 `lock:lock:xxx`；`unlock` 对未持有锁与异常全部静默吞掉；两个接口注释把 `tryLock` 说成「立即返回」、把 `waitTime` 说成租期。
  - **为什么做**：临界区为「查库 + 占位写库」这类毫秒级短任务时，看门狗会在调用线程卡死（死锁/长时间 GC/挂起）期间无限续租，锁一直不释放；双前缀让同一语义的键分裂，运维按 `lock:` 排查会漏；解锁静默使「未持有锁 / 重复解锁」无从观测。
  - **改动明细**：
    - 新增固定租期重载 `tryLock(key, waitTime, leaseTime, unit)`：`leaseTime <= 0` 抛 `IllegalArgumentException`，走固定租期、**不启用看门狗**；旧三参重载语义不变（仍为看门狗）。
    - key 前缀约定改为「工具不加前缀、调用方给全名且含 `lock:`」；`CacheConstant.LOCK_PREFIX` 保留供调用方拼接；file 两处补前缀，content 8 处 key 未改代码，但落地键由 `lock:lock:xxx` 变为 `lock:xxx`。
    - `unlock` 不再静默：未持有锁 / `IllegalMonitorStateException` / 其他异常分别留痕（级别区分），签名保持 `void`，不改变调用方契约。
    - 修正 `DistributedLock`、`RedissonLock` 注释：`tryLock` 是「最多等待 waitTime」，`waitTime` 不是租期；固定租期语义指向新重载。
  - **验收标准**：固定租期重载在 `leaseTime <= 0` 时抛 `IllegalArgumentException`；持锁线程不主动 unlock 时锁在 leaseTime 到期后自动释放（无续期）；旧三参重载行为不变；解锁异常有日志可检索；content 实际锁键为 `lock:content:...` 单前缀。
  - **涉及文件**：[DistributedLock.java](../../../litisle-common/common-redis/src/main/java/com/litisle/commonredis/lock/DistributedLock.java) · [RedissonLock.java](../../../litisle-common/common-redis/src/main/java/com/litisle/commonredis/lock/RedissonLock.java) · [CacheConstant.java:162](../../../litisle-common/common-redis/src/main/java/com/litisle/commonredis/constant/CacheConstant.java#L162) · [FileReconcileTask.java:51](../../../litisle-file/src/main/java/com/litisle/litislefile/task/FileReconcileTask.java#L51) · [FileUploadServiceImpl.java:99](../../../litisle-file/src/main/java/com/litisle/litislefile/service/impl/FileUploadServiceImpl.java#L99) · content 8 处 key（未改代码，仅落地键变化）：[ContentBizConstant.java:127-130](../../../litisle-content/src/main/java/com/litisle/litislecontent/constant/ContentBizConstant.java#L127-L130)、[ArticleScheduledPublishJob.java:43](../../../litisle-content/src/main/java/com/litisle/litislecontent/job/ArticleScheduledPublishJob.java#L43)、[ContentStatWindowJob.java:46](../../../litisle-content/src/main/java/com/litisle/litislecontent/job/ContentStatWindowJob.java#L46)、[EventOutboxResendJob.java:54](../../../litisle-content/src/main/java/com/litisle/litislecontent/job/EventOutboxResendJob.java#L54)、[BookServiceImpl.java:433](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/BookServiceImpl.java#L433)、[FeedHotRankSupport.java:88](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedHotRankSupport.java#L88)、[FeedServiceImpl.java:124](../../../litisle-content/src/main/java/com/litisle/litislecontent/service/impl/FeedServiceImpl.java#L124)、[FeedHotRankRefillJob.java:66](../../../litisle-content/src/main/java/com/litisle/litislecontent/job/FeedHotRankRefillJob.java#L66)
  - ✅ 2026-11-08：9 个模块 `mvn compile` 全部 SUCCESS（锁工具改动为纯新增重载 + 前缀口径与日志调整，旧调用方行为不变）。

### 17.3 review 自造 SETNX 锁的迁移评估（已完成 ✅，结论：暂不迁移）

- [x] 【评估｜17.3】review 四处 sweeper 的 SETNX 锁能否迁到固定租期重载
  - **现状**：`QuarantineSweeper`、`PunishReconcileSweeper`、`ReviewTaskSweeper`、`WordlibMisreportCleanupSweeper` 各自复制一套 `SETNX + token + GET 比对后 DEL`（释放非原子）；`ModerationTextService#tryLockInFlight` 只 SETNX、靠 TTL 自愈。
  - **为什么做**：四套手写锁分散维护，释放非原子在并发下可能误删他人的锁；统一到 common-redis 工具可减少重复实现，但**语义不等价**（sweeper 依赖 TTL 自愈、且拿不到锁就跳过本轮，没有「等待」诉求），不能直接把看门狗重载照搬过去。
  - **改法**：逐个评估——可迁的改用固定租期重载（租期取原 TTL，`waitTime=0` 保持「拿不到即跳过本轮」）；不可迁的明确保持 TTL 语义并注明原因。
  - **验收标准**：给出迁移方案与风险结论，逐项明确哪些可迁、哪些保持 TTL 语义及理由；不改变 sweeper 的「无锁跳过」行为与定时频率。
  - **涉及文件**：[QuarantineSweeper.java](../../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/QuarantineSweeper.java) · [PunishReconcileSweeper.java](../../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/PunishReconcileSweeper.java) · [ReviewTaskSweeper.java](../../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/ReviewTaskSweeper.java) · [WordlibMisreportCleanupSweeper.java](../../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/WordlibMisreportCleanupSweeper.java) · [ModerationTextService.java](../../../litisle-review/src/main/java/com/litisle/litislereview/moderation/service/ModerationTextService.java)
  - ✅ 2026-11-08（迁移评估完成，本轮未改代码）：
    - **4 个 sweeper 主锁（Quarantine / PunishReconcile / ReviewTask / WordlibMisreport）：可迁**。等价映射为 `tryLock(key, 0, 原 lockSeconds, SECONDS)` + `finally unlock(key)`：`waitTime=0` 保持「拿不到即跳过本轮」，原 TTL 作固定租期保持「到期自动释放」，`unlock` 由 Redisson 原子校验持有线程，消除现有 `GET` 比对后 `DEL` 的竞态。代价是键名要从 `review:sweep:*:lock` 改为 `lock:review:sweep:*`（工具约定要求 `lock:` 前缀），且 Redis 中锁值变为 Redisson hash（人工 `GET` 巡检方式要同步），多实例下抢锁失败会打 warn（当前是静默 `return`）。
    - **`ReviewTaskSweeper` 的 dedup 前置锁（L407-411）与 `ModerationTextService#tryLockInFlight`：不可单侧迁移**。二者共用同一 `review:dedup:{provider}:{hash}` 键；若一侧改 Redisson（hash）、另一侧仍 SETNX（string），一旦 string 侧 TTL 过期、由另一实例抢到 hash 锁，过期持有者执行 `GET` 比对就会触发 `WRONGTYPE`。必须两侧同时迁移，否则保持 SETNX+TTL。
    - **结论与建议**：本轮全部保持 SETNX+TTL，「拿不到即跳过 + TTL 自愈」最贴合 sweeper 语义，且不引入键名、锁表示与日志噪音变化；后续若统一，按「4 个主锁 + dedup 双端」打包一次迁移，并同步运维巡检口径。未改变定时频率与无锁跳过行为。
  - 🔒 **硬约束（2026-11-08 用户确认，迁移前必读，不是建议）**：`ReviewTaskSweeper` 的 dedup 前置锁（[L407-430](../../../litisle-review/src/main/java/com/litisle/litislereview/sweeper/ReviewTaskSweeper.java#L407-L430)）与 `ModerationTextService#tryLockInFlight`（[L226-236](../../../litisle-review/src/main/java/com/litisle/litislereview/moderation/service/ModerationTextService.java#L226-L236)）共用同一个 Redis 键 `review:dedup:{provider}:{hash}`。**任何迁移都必须两侧同时改**：只要一侧换成 Redisson（键值为 hash 结构）、另一侧仍是 SETNX（键值为 string 结构），在 string 侧 TTL 过期、该键被另一实例以 hash 结构抢占后，过期持有者执行 `GET` 比对就会触发 `WRONGTYPE`，释放逻辑报错且锁状态失控。**禁止单侧迁移，也禁止「先迁一侧、另一侧下一批再迁」的拆分实施**；两侧必须同一提交内完成，或整体保持 SETNX+TTL。

## 归档说明

- 本文件共归档 2 项（17.1、17.3）。
- 17.1 的验收中「content 实际锁键为单前缀」由代码与 key 构造方式确认；持锁线程卡死时自动释放属运行态行为，未单独实测（固定租期已是 Redisson 标准语义）。
- 17.3 为评估类条目，无代码/配置改动；结论「暂不迁移」与 🔒 硬约束是后续任何迁移实施的前置条件，17.6 若选 A（Lua 原子删除）须与 17.3 的 5 个释放点打包一次做。
- 编号空缺：主清单批次十七的 17.1、17.3 空缺指向本文件；编号不重排。
