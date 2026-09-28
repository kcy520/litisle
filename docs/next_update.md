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
  - 待办：Feign 调用侧 Sentinel 规则（限流/熔断）尚未落地。
- [x] **P2 系统级限流/熔断**：现有 `@RateLimiter` 均为单用户维度，缺全站 QPS 上限 / 存储后端保护
  - 已完成（2026-09-27，选型 a）：新增 `FileGlobalQpsInterceptor` 对 `/api/file/**` 做全站 QPS 上限
    （阈值 `FileLimitConstant.GLOBAL_QPS_MAX_COUNT`，1s 窗口），与各接口 `@RateLimiter(USER)` 并存、互不干扰；
    限流 Lua 抽取为可复用组件 `RedisRateLimiter`（common-redis），注解式与系统级共用同一脚本，保证单一事实来源。
  - 待办：Sentinel 熔断（存储后端保护）尚未落地。

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

