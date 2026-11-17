# AI 预算闸门改造：Redis 实时累加 + MySQL 账本兜底 + 对账

## Context

当前 `AiBudgetService.check()` 每次判定读 `ai_usage_daily` 当日跨用户求和（60 秒本地缓存），存在三个问题：① 判定滞后最多 60 秒 + 聚合任务周期，高耗时段可能已超支一大截才亮红灯；② 缓存过期即触发一次聚合扫描，用户量上来后 DB 压力线性增长；③ 多实例各自缓存，口径漂移。

用户选定主流方案：**Redis 实时计数（调用后 INCRBY 实扣）+ Lua 原子判定 + MySQL（ai_usage_daily）作为账本权威源兜底 + 定时对账修正**。范围仅 `AiBudgetService`（用户级配额 `AiUserQuotaService` 维持现状，限额未定档无实际拦截行为）。

已确认：`AiUsageRecordService` 注入 `AiBudgetService` 无循环依赖（后者是叶子节点）；`check()` 签名与 `AiBudgetDecision` 枚举不变，8 个调用方零改动。

## 改动文件（3 个，均为修改既有文件）

### 1. AiBudgetService.java（核心）
`litisle-ai/src/main/java/com/litisle/litisleai/service/AiBudgetService.java`

- 新增依赖 `StringRedisTemplate`（沿用 `AiUserQuotaService.tryAcquireAnonymous` 的降级惯例）。
- 新增常量：`COST_KEY_PREFIX = "ai:budget:cost:"`、TTL 48h（覆盖跨天临界）、成本以**整数分**存储（cost 两位小数 ×100，避免浮点漂移）。
- 三个 `DefaultRedisScript<Long>` 内嵌 Lua：
  - **脚本 A 判定**（KEYS[1]=成本键，ARGV=warnFen/limitFen）：键缺失返回 3；`cur>=limit` 返回 2(EXCEEDED)；`cur>=warn` 返回 1(WARN)；否则 0(ALLOWED)。
  - **脚本 B 记账**：`INCRBY` 增量分 + `EXPIRE` 刷新 TTL。
  - **脚本 C 对账**：当前值 < DB 值才 SET（带 TTL），**只增不减**——防止抹掉其他节点刚 INCR 的在途增量。
- `check(purpose)` 签名/枚举不变，方法体改为：① OTHER/预算≤0 放行（原逻辑）→ ② try 执行脚本 A 直接映射返回 → ③ 返回 3（键缺失）：调 `todayBucketCost(bucket)`（复用现有 60s 缓存查库逻辑）×100 SET 预热后本地比较返回 → ④ Redis 异常：log.warn 并降级走账本口径（抽出原比较逻辑为私有 `decideByLedger`），`todayBucketCost` 保留不动。
- 新增 `public void recordCost(AiPurposeEnum purpose, BigDecimal cost, LocalDate statDate)`：cost≤0 直接返回；try 执行脚本 B；catch 仅 log.warn（绝不影响计量链路）。带 `statDate` 是因为明细落库以 `occurred_at` 为归属权威源，跨天落库必须记到归属日键。
- 新增 `public void reconcileBuckets(LocalDate statDate)`：查 `ai_usage_daily` 当日 metered 行，按 `bucketPurposes` 归桶求和，对预算>0 的桶执行脚本 C。
- 类级 javadoc 更新【职责边界】：Redis 实时判定 + 记账 + 对账；MySQL 为账本权威源，Redis 不可用/键缺失时降级读账本。

### 2. AiUsageRecordService.java（记账接线）
`litisle-ai/src/main/java/com/litisle/litisleai/service/AiUsageRecordService.java`

- 注入 `AiBudgetService`；`saveDetail()` 中 `tokenUsageMapper.insert(usage)` **成功后**（DuplicateKeyException catch 之外）追加：
  `status==SUCCESS && cost>0` 时调用 `budgetService.recordCost(purpose, cost, occurredAt.toLocalDate())`。
- 满足约束：重复写入跳过不扣、失败明细成本 0 跳过、Redis 异常在 recordCost 内吞掉。

### 3. AiUsageDailyAggregateTask.java（对账钩子）
`litisle-ai/src/main/java/com/litisle/litisleai/task/AiUsageDailyAggregateTask.java`

- 注入 `AiBudgetService`；`aggregate()` 中当日 `upsertForDay(today)` 之后追加 `budgetService.reconcileBuckets(today)`（仅当日；昨日 Redis 键不参与判定无需回补）。放在 try 内，聚合失败自然跳过对账。

## 注释规范
新增 3 个公开方法按三层结构：javadoc（含降级语义）+ `/* 业务逻辑步骤：1..n */` 块 + 关键分支行内注释（如"只增不减：防对账抹掉在途增量"）。不删除任何既有注释。

## 验证
1. `mvn -o -pl litisle-ai compile` 编译通过（8 处 check() 调用方零改动即证签名稳定）。
2. Lua 逻辑：本地 Redis `redis-cli EVAL` 验证三态返回（键缺失=3、`cur==limitFen` 边界=2）与对账"只增不减"。
3. 降级行为：停 Redis 确认判定降级走 DB 口径、log.warn 可见、计量落库不受影响。
