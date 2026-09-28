## 暂不实现（注释中已声明的预留项）

- [ ] safe\_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置

## 待实现（按优先级排序）

### 上传完整性校验 · 方案A（存储侧校验 + 精确语义，2026-09-27 定稿）

背景：`SeaweedFsClient#put` 已携带 `ChecksumAlgorithm.SHA256`（SDK 计算并随请求下发）。
实测结论（2026-09-27）：SeaweedFS 网关确实校验，详见下方 P0。
已完成（2026-09-27，不再单列）：精确错误码 `FILE_CHECKSUM_MISMATCH(90006)`；
摘要来源唯一（hex→Base64 显式下发）；`put` 5 参重载（兼容缩略图）；校验失败 `warn` 日志。

- [x] **P0 网关校验能力验证**：实测结论（2026-09-27）——SeaweedFS S3 网关【会校验】
  `x-amz-checksum-sha256`：摘要不符返回 `400 BadDigest`（message 文案沿用 Content-Md5）
  且对象被拒绝写入；正确摘要 200 通过。→ 方案A 为终态，收工。
  - [x] **P0 实测后回填错误码**：`CHECKSUM_MISMATCH_CODES` 已定稿为 `Set.of("baddigest")`
    （移除未验证的 `XAmzContentSHA256Mismatch` 占位，遵循“不留无效配置”）
- [x] **~~P2 关键类型回读兜底（条件触发）~~**：P0 证明网关校验生效，**本项不触发，关闭**

> 取舍说明：不选“服务端回读兜底”为主方案——多一次完整读（≤10MB）且 ETag 为 MD5、
> 与秒传 SHA256 判重键不同源，需另算 MD5，成本高、口径乱；方案A 在网关支持前提下零额外往返。

### 文件安全与配额（2026-09-27 新增）

- [x] **P0 per-user 存储配额**：限流只限频率、不限总量（30 次/分钟 × 10MB = 300MB/分钟可灌爆存储）。
  需按用户/租户限制「总容量 + 文件数」，超限拒绝（新增 `FILE_QUOTA_EXCEEDED`）；
  `FileProperties.Upload` 现仅有 `maxImageSize`（单文件），需扩展 quota 配置
  - 已完成（2026-09-27）：错误码 `FILE_QUOTA_EXCEEDED(90007)`；`FileProperties.Upload` 扩展
    `maxTotalSize`/`maxFileCount`（yml 可调）；`FileMetaMapper#selectQuotaUsage` 聚合 `SUM(size)+COUNT(*)`；
    上传在「锁内·双检之后·写库之前」校验，秒传命中不占配额，统计口径含 `del_flag=0`（预占语义）。
  - 并发模型：方案 α（软限）——sha256 锁维度，同用户并发上传不同文件最坏超限约 1×单文件；
    如需精确到字节，升级为用户维度锁 `file:quota:{ownerId}`。
- [x] **P0 缩略图接口限流**：`POST /{fileId}/thumbnail` 无限流，而 ImageIO 缩放为 CPU 密集 +
  对象存储读写，未命中缓存每次重算 → CPU 打满风险；补 `FileLimitConstant` 阈值 + `@RateLimiter`
  - 已完成（2026-09-27）：`THUMBNAIL_WINDOW_SECONDS=60` / `THUMBNAIL_MAX_COUNT=120`，
    `FileController#thumbnail` 加 `@RateLimiter(keyType=USER)`。
- [x] **P1 presign / delete 限流**：presign 可枚举 fileId 刷凭证；delete 为写操作可被批量刷
  - 已完成（2026-09-27）：presign `60s/60`、delete `60s/30`，均 `@RateLimiter(keyType=USER)`。
- [x] **P1 文件内容魔数校验**：`validateUpload` 仅校验 Content-Type 字符串，可伪造
  （.exe 改名 .jpg + 伪造 header 可通过；`resolveImageSize` 失败仅返回 null 不拒绝）
  → 校验文件头 magic bytes 与声明类型一致
  - 已完成（2026-09-27）：新增 `util/FileMagicValidator`（只读 12 字节文件头，不整份读内存）；
    「声明 MIME → 可接受真实 MIME 集合」映射（docx 与 zip 同为 PK 压缩包魔数，互相接受）；
    WEBP 需 `RIFF` + offset8 `WEBP` 双条件；识别失败 fail-safe 拒绝；错误码复用 `FILE_TYPE_NOT_ALLOWED(90002)`。
- [x] **P2 上传/下载线程池隔离**：同步阻塞占 Tomcat 工作线程，规范上应隔离防打满主线程池
  - 已完成（2026-09-27，方案 A）：新增 `FileAsyncConfig`（`fileIoExecutor` 线程池 Bean + `configureAsyncSupport`），
    `FileController#download` 改为返回 `StreamingResponseBody`，阻塞的 S3 读 + 客户端写由 `fileIoExecutor` 执行，
    慢客户端不再占用 Tomcat 工作线程；线程池参数外置 `litisle.file.executor`（可 Nacos 覆盖）。
  - 上传保持同步（multipart + 事务语义，异步化风险高）：`FileServiceImpl#upload` 已加瓶颈说明注释。
- [ ] **P2 Feign 侧防护**：确认 `file_ref` 四元组唯一索引（保证 bindRef 并发幂等）；
  Feign 无频率限制，异常调用可能打爆 file 服务，可加 Sentinel 规则
  - 已完成（2026-09-27，唯一索引部分）：`file-schema.sql` 补 `UNIQUE KEY uk_ref(file_id, biz_type, biz_id, owner_id)`，
    `FileServiceImpl#bindRef` 捕获 `DuplicateKeyException` 维持并发幂等（见 Sql-Document §八）。
  - 待办：Feign 调用侧 Sentinel 规则（限流/熔断）尚未落地（选型已定稿：Nacos 数据源，见「待办决策定稿」）。
- [x] **P2 系统级限流/熔断**：现有 `@RateLimiter` 均为单用户维度，缺全站 QPS 上限 / 存储后端保护
  - 已完成（2026-09-27，选型 a）：新增 `FileGlobalQpsInterceptor` 对 `/api/file/**` 做全站 QPS 上限
    （阈值 `FileLimitConstant.GLOBAL_QPS_MAX_COUNT`，1s 窗口），与各接口 `@RateLimiter(USER)` 并存、互不干扰；
    限流 Lua 抽取为可复用组件 `RedisRateLimiter`（common-redis），注解式与系统级共用同一脚本，保证单一事实来源。
  - 待办：Sentinel 熔断（存储后端保护）尚未落地（选型已定稿：Nacos 数据源，见「待办决策定稿」）。

***

## 2026-09-27 本次会话改动记录（P2：线程池隔离 + 系统级限流）

### 一、限流注解相关改动（重点）

本次围绕「`@RateLimiter` 注解式限流」做了三处重构，核心目标是
**把内联 Lua 脚本抽取为可复用组件，实现单一事实来源（SSOT）**，
使「注解式限流」与「系统级全站限流」共用同一套原子计数逻辑。

#### 1. 新增 `RedisRateLimiter`（common-redis）

- 路径：`litisle-common/common-redis/.../ratelimit/RedisRateLimiter.java`
- 抽出原内联于 `RateLimiterAspect` 的固定窗口 Lua 脚本（`INCR` + 首次 `EXPIRE`），
  封装为 `increment(key, windowSeconds)`。
- 关键约束：`windowSeconds` 必须传**数字类型**，不能转 String——JSON 序列化器会给 String
  再包一层引号（`"60"`），导致 Redis `EXPIRE` 收到非整数报错。
- 空返回值兜底为 `0`（放行），避免限流组件故障阻断主流程。
- 由 `CommonRedisAutoConfiguration` 以 `@Bean @ConditionalOnMissingBean` 注册。

#### 2. 重构 `RateLimiterAspect`

- 移除内联 `DefaultRedisScript`，改为构造注入 `RedisRateLimiter`。
- 执行链路：`resolveUserId → keyBuilder.build(keyType, ...) → redisRateLimiter.increment
  → 超限抛 BizException(TOO_MANY_REQUESTS)`。
- 语义不变：仍拦截 `@annotation(rateLimiter)`，USER/IP/DEFAULT 三种 keyType 行为一致。

#### 3. `CommonRedisAutoConfiguration` 调整

- 新增 `redisRateLimiter(RedisTemplate)` Bean。
- `rateLimiterAspect(...)` 依赖由 `RedisTemplate` 改为 `RedisRateLimiter`
  （保留 `RateLimiterKeyBuilder`、`ObjectProvider<UserIdResolver>`）。

#### 4. `FileLimitConstant` 新增全站 QPS 常量

- `GLOBAL_QPS_WINDOW_SECONDS = 1L`（1 秒窗口）。
- `GLOBAL_QPS_MAX_COUNT = 200L`（`/api/file/**` 全站合计每秒上限）。

#### 5. 新增 `FileGlobalQpsInterceptor`（选型 a）

- 拦截 `/api/file/**`，key = `rate_limit:global:file:qps`，复用 `RedisRateLimiter` 计数。
- 与各接口 `@RateLimiter(keyType=USER)` **并存、互不干扰**：前者防单用户刷，后者防全站洪峰。
- 选拦截器而非注解的原因：`@RateLimiter` 不可重复，同一方法无法同时叠加「用户级 + 全局级」。
- 超限抛 `BizException(TOO_MANY_REQUESTS)`，错误码与注解式限流一致。

> 限流语义分层小结：
>
> | 维度    | 载体                         | 作用     | key 形态                             |
> | ----- | -------------------------- | ------ | ---------------------------------- |
> | 单用户   | `@RateLimiter(USER)`       | 防单用户高频 | `rate_limit:user:{id}:{类:方法}`      |
> | 单接口全站 | `@RateLimiter(DEFAULT)`    | 防单接口总量 | `rate_limit:default:global:{类:方法}` |
> | 多接口全站 | `FileGlobalQpsInterceptor` | 防全站洪峰  | `rate_limit:global:file:qps`       |

### 二、线程池隔离（方案 A）

- 新增 `FileAsyncConfig`：`fileIoExecutor` 线程池 Bean（`CallerRunsPolicy` 回压）
  - `configureAsyncSupport`（注册执行器 + 放宽异步超时，默认 600s）。
- `FileController#download` 返回 `StreamingResponseBody`，阻塞的 S3 读 + 客户端写交由
  `fileIoExecutor`，慢客户端不再占用 Tomcat 工作线程。
- 参数外置 `litisle.file.executor`（`FileProperties.Executor`，可 Nacos 覆盖）。
- 上传保持同步，`FileServiceImpl#upload` 补瓶颈说明注释。

### 三、Feign 侧防护（唯一索引部分）

- `file-schema.sql` 补 `UNIQUE KEY uk_ref(file_id, biz_type, biz_id, owner_id)`。
- `FileServiceImpl#bindRef` 捕获 `DuplicateKeyException` 维持并发幂等。

### 四、待办

- [ ] 运行库 `file_ref` 表补 `uk_ref` 前需先查重（`GROUP BY ... HAVING COUNT(*) > 1`）。
- [ ] Feign 调用侧 Sentinel 规则（限流/熔断）未落地。
- [ ] 系统级 Sentinel 熔断（存储后端保护）未落地。

***

## 限流故障降级 · 本地兑底限流（2026-09-28 定稿并完成，选型：自研）

背景：`RedisRateLimiter.increment` 已统一 fail-open（2026-09-28），但 fail-open 意味着
「Redis 挂 → 限流失效」，对防爆破类接口（登录/验证码）是防护缺口。定稿为「轻量企业版本地兑底」，
不引 Sentinel（common-redis 库模块轻量约束）；将来商城/规模化阶段在业务模块按需引 Sentinel，
两者形成「常态流控 + 故障兑底」双保险终态。

### 一、实现思路（三态熔断 + 配额分摊）

1. 新增 `LocalRateLimiter`：单机固定窗口计数，`ConcurrentHashMap<String, AtomicLong>`，
   槽位 key = `限流key#窗口序号`（对齐墙钟窗口，与 Redis 路径语义一致）；
   约每 1024 次操作 purge 一次清理过期槽位，内存有界。
2. `RedisRateLimiter` 内置轻量三态熔断：
   - CLOSED：正常走 Redis；【连接/超时类】失败达阈值 → OPEN（业务类异常如脚本返回 null 不算故障，不触发熔断）；
   - OPEN：冷却期内直接走本地，不碰 Redis（避免每请求都吃连接超时）；
   - HALF_OPEN：冷却到期后放少量探测请求试 Redis，成功→CLOSED，失败→回 OPEN
     （防冷却到期全量请求同时冲回 Redis 的惊群，也防一次侥幸成功就全量恢复）。
3. 配额分摊：本地阈值 = 集群阈值 ÷ 实例数（配置 `instance-count`，可从 Nacos 读），
   保证降级期「更严或等严」，绝不更松——否则 N 实例各跑全量阈值，故障期实际放行 阈值×N，
   Redis 故障变成攻击窗口。
4. 配置外置 `litisle.rate-limit.fallback.*`：`enabled`（默认 true）/ `cooldown-seconds`（默认 5）/
   `instance-count`；关闭则回退为纯 fail-open。
5. 改动清单：新增 `ratelimit/LocalRateLimiter.java`、`config/RateLimitFallbackProperties.java`；
   修改 `RedisRateLimiter`（熔断+兑底）、`CommonRedisAutoConfiguration`（注册新 Bean）。
   调用方（`RateLimiterAspect` / `FileGlobalQpsInterceptor`）increment 签名不变，零改动。

### 一·补、完成记录（2026-09-28，全量落地）

- 新增 `ratelimit/LocalRateLimiter.java`：单机固定窗口计数（`ConcurrentHashMap<String, AtomicLong>`）。
  - 槽位 key = `限流key#窗口结束时间戳`：与定稿「窗口序号」语义等价，改用绝对过期时间戳是因
    清理时拿不到每个 key 的窗口大小（不同 key 窗口可不同，1s/60s 并存），存绝对过期时间
    可让 `removeIf` 无需额外记录窗口尺寸。
  - 墙钟对齐窗口（窗口边界取 `windowSeconds` 整数倍墙钟时刻，非「首次访问+N」），与 Redis
    路径固定窗口切分点一致，降级/恢复切换时计数口径连续。
  - 约 1024 次操作 purge 一次（`& 0x3FF` 位运算取模），内存有界（≈ 活跃 key 数 × 2 槽位）。
- 新增 `config/RateLimitFallbackProperties.java`：`litisle.rate-limit.fallback.*`，
  `enabled` 默认 true / `cooldown-seconds` 默认 5 / `instance-count` 默认 1（Nacos 可覆盖）。
- 修改 `RedisRateLimiter`（三态熔断 + 兑底，签名不变）：
  - 状态机 `AtomicInteger`（CLOSED=0/OPEN=1/HALF_OPEN=2）+ `openAtMillis`(volatile) 冷却基准。
  - 故障口径：仅【连接/超时类】异常计入 `consecutiveFailures`（连续 3 次 → 熔断）；
    脚本返回 null 属业务类，返回 0 放行不算故障。取 3 而非 1：单次超时可能是抖动，误熔断
    会白白降级到精度更低的本地计数；取小值是为了快速切入降级少吃超时。
  - OPEN 冷却期内不碰 Redis（防每请求吃连接超时拖垮接口延迟）；冷却到期仅一个线程
    CAS(OPEN→HALF_OPEN) 发探测，防惊群；探测失败回 OPEN 重计冷却，成功恢复 CLOSED。
  - 日志仅在状态切换点打（探测失败/熔断打开 warn、恢复 info），天然节流防刷屏。
  - `Math.max(1, instanceCount)` 防误配 0 导致乘积恒 0、限流失效。
- 修改 `CommonRedisAutoConfiguration`：注册 `LocalRateLimiter`、`RateLimitFallbackProperties`，
  `redisRateLimiter` Bean 构造参数同步扩展。
- 配额分摊实现（与定稿「本地阈值 = 集群阈值 ÷ 实例数」等价）：
  `increment` 返回前做 本地原始计数 × instance-count = 集群等效计数，调用方仍拿返回值与
  集群阈值比较——数学上等价于本地阈值向下取整，只会更严不会更松；因 `increment` 拿不到
  集群阈值（阈值在切面/拦截器侧才比较），乘法变换是调用方零改动的唯一可行位。
- 调用方核实结论：`RateLimiterAspect` / `FileGlobalQpsInterceptor` increment 签名不变零改动；
  `@RateLimiter` 注解 5 属性、KeyType 枚举、切面拦截方式均未动，file/user/auth 三模块
  14 处注解使用方式零变更。

### 二、注意事项

- 单机口径 ≠ 集群精确：分摊依赖 `instance-count` 配准，实例扩缩容后需同步（后续可接 Nacos 服务发现动态算）。
- 日志只在熔断状态切换时打（进/出降级），天然节流防刷屏；将来可接 Micrometer 指标 + 告警
  （「限流降级超过 X 分钟」需报警——降级是静默的防护削弱，必须可见）。
- 安全关键型（登录/验证码）降级期防护减弱但仍存在（比 fail-open 强）；`LoginRetryLimiter`
  已定稿纳入本地兑底（2026-09-28，见下方「待办决策定稿」）。
- 验证方式：停 Redis 后请求受限接口，应正常放行且出现「进入降级」日志，第二次起走本地计数；
  Redis 恢复后冷却期探测成功，日志出现「恢复」，回归 Redis 计数。**【待验证，未执行】**

### 三、预留：将来引 Sentinel（触发条件满足任意一条再启动，当前不动）

1. 积分商城出现秒杀/抢购类接口，需按商品 ID 热点参数限流；
2. 促销需控制台动态调阈值（而非改 Nacos 配置）；
3. 接入服务 ≥ 6 个，需统一流控视图。

引入位置：业务模块层（sentinel 依赖已在 auth/file/user），不动 common-redis。
引 Sentinel 后自研 `LocalRateLimiter` 不作废：Sentinel 集群流控（Token Server）故障时的
官方降级同样是「退回单机本地限流」，本组件即终态双保险架构的兑底层。

***

## 待办决策定稿（2026-09-28）

### 一、Sentinel 规则落地方式：Nacos 数据源（定稿）

- 选型：`sentinel-datasource-nacos`——规则持久化 Nacos、热更新、重启不丢。
  企业规范：限流/熔断规则属「运营参数」，必须外置 + 可热更 + 重启不丢，
  与既有惯例（运营可调参数外置 yml/Nacos）同源。
- 不加代码层兜底规则（`loadRules` + `@PostConstruct`）：系统级防线已有
  `FileGlobalQpsInterceptor` + 本地兜底限流，Nacos 短暂不可用无裸奔风险。
- 前置动作：`feign.sentinel.enabled: true` + dashboard transport 地址落进
  Nacos 共享配置 `litisle-shared.yaml`（注意 .yaml 后缀）；当前仍注释在 auth 本地 yml。
- 覆盖范围：FileClient/UserClient/AuthClient 异常比例/慢调用比例熔断
  （FallbackFactory 已就位，规则补齐后避免降级风暴）；SeaweedFsClient put/get 熔断
  （存储后端保护，防连接超时打满工作线程）。

### 二、LoginRetryLimiter 纳入本地兜底（定稿，方式：抽取公共组件）

- 语义依据：安全关键组件降级「宁严勿松」（纯 fail-open 会给防爆破留攻击窗口），
  但不可 fail-closed（Redis 挂 = 全站无法登录）→ 与既定本地兜底方案同构，纳入。
- 实现路径：把 `RedisRateLimiter` 内嵌的三态熔断状态机抽取为独立组件
  `RedisGuard`（CLOSED/OPEN/HALF_OPEN + 冷却 + 单线程探测 + 连续故障计数），
  `RedisRateLimiter` 与 `LoginRetryLimiter` 共用，状态机单一事实来源，不复制。
- 预留：状态切换点是 Micrometer 指标天然埋点位置，在 `RedisGuard` 层埋一次，
  两个使用方均受益（对应「限流降级超过 X 分钟需告警」诉求）。

### 三、其他待决事项

- [ ] `idx_file` 冗余索引是否移除：uk_ref 最左前缀已含 file_id，file-schema.sql 注释
  标注「暂保留，如需精简可后续移除」——需定去留。
- [ ] Micrometer 指标 + 告警是随 `RedisGuard` 抽取一起落地，还是延后到多实例上线。
- [ ] `instance-count` 动态化时机：接 Nacos 服务发现自动计算实例数（扩缩容免人工同步）
  ——触发条件：file 服务多实例部署上线时。

