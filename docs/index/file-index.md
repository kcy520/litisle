# file-index · litisle-file 文件模块速查表

> 用途：写码/联调前查 file 模块坐标，**免去逐个 Read**。
> 标记：✅ 已落地 ｜ 🆕 待补（缺口）。
> 关联：`File-Architecture.md`（架构全貌）· `seaweedfs笔记.md`（存储细节）· `Api-Architecture.md §4.4`（FileClient 契约）。
> 纪律：契约层 Javadoc 锁口径；改字段名须同步消费方（user/content/review）。

## 一、模块坐标

| 项 | 值 |
|---|---|
| 服务名 / 端口 | `litisle-file` / `8083`（application.yml） |
| 定位 | 独立微服务，封装 SeaweedFS S3（SDK 走 S3 网关 :8333，公开直链走 Filer :8888 /buckets），只管**文件本体 + 元数据** |
| 库 | `litisle_file`（`file_meta` / `file_ref`） |
| 关键依赖 | spring-boot-starter-amqp、common-redis（限流/锁）、common-mq（ACK/幂等）、litisle-api（契约）、AWS SDK v2（S3） |
| 不负责 | 内容审核判定（DFA/机审在 review）、业务权限判定（调用方决定） |

## 二、包结构（`com.litisle.litislefile`）

```text
client/           SeaweedFsClient（唯一接触 AWS SDK；put/get/getStream/delete/presign/listKeys/exists/copy）
config/           FileProperties / SeaweedFsConfig / FileAsyncConfig / ModerationMqConfig / SaTokenWebConfig
constant/         FileLimitConstant（限流阈值）
controller/       FileUploadController（上传域）/ FileAccessController（访问/下载/预签名域）/ FileManageController（管理/删除域）——由 FileController 按业务域拆分，FileController 整体注释保留
controller/feign/ FileFeignProviderController（implements FileClient，/api/feign/file）
domain/entity/    FileMeta / FileRef
domain/vo/        FileUploadVO / FileMetaVO / PresignVO / ThumbnailVO
domain/dto/       FileDownloadResource(record) / FileQuotaUsage
enums/            StorageBizTypeEnum / AccessLevelEnum / SafeFlagEnum / UploadStatusEnum / AlignEnum
interceptor/      FileGlobalQpsInterceptor / ModerationResultListener
mapper/           FileMetaMapper / FileRefMapper
service/          FileUploadService / FileAccessService / FileManageService / FileModerationService——由 FileService 按业务域拆分，FileService/FileServiceImpl 整体注释保留
service/impl/     FileUploadServiceImpl / FileAccessServiceImpl / FileManageServiceImpl / FileModerationServiceImpl
service/support/  FileMetaSupport（跨域公共方法：getMetaOrThrow / buildPublicUrl）
task/             FileReconcileTask（每日 02:00 对账）
thumbnail/        ThumbnailGenerator
util/             FileMagicValidator（魔数校验）
```

## 三、枚举（`enums`，均 @EnumValue 入库 + @JsonValue 序列化 desc）

- `StorageBizTypeEnum`（implements DictEnum，三值兼容 name/code/desc）：`AVATAR("avatar")` / `COVER("cover")` / `ARTICLE_IMG("article")` / `LICENSE("license")`——决定 bucket；LICENSE 服务层强制 PRIVATE
- `AccessLevelEnum`：`PRIVATE(0)` 预签名 / `PUBLIC(1)` 直链
- `SafeFlagEnum`（default-deny 四态）：`VIOLATIVE(0)` / `NORMAL(1)` / `PENDING(2)` / `SUSPICIOUS(3)`
- `UploadStatusEnum`：`UPLOADING(0)` 锁内占位 / `COMPLETED(1)` put 成功
- `AlignEnum`：`LEFT(1)/LEFT_CENTER(2)/CENTER(3)/RIGHT_CENTER(4)/RIGHT(5)`（权威在 content article_image.align）

## 四、REST 接口（`FileController`，前缀 `/api/file`，返回 `Result<T>`）

| 方法 | 路径 | 权限 | 限流(60s) | 说明 |
|---|---|---|---|---|
| POST | `/upload` | 登录 | 30 | 单传（含秒传）；`file`+`bizType` |
| POST | `/upload/batch` | 登录 | 5 | 批量 ≤9 张，逐个独立、不回滚 |
| GET | `/{fileId}/url` | 登录(PUBLIC)/owner·ADMIN(PRIVATE) | — | 统一入口分流 |
| GET/HEAD | `/{fileId}/download` | 同上 | 120 | 流式代理，Range 200/206/416；**唯一非 Result<T>** |
| POST | `/{fileId}/presign` | owner/ADMIN | 60 | 强制预签名，`expireMinutes ≤ 配置上限` |
| GET | `/{fileId}/meta` | 登录 | — | 非 owner/ADMIN 脱敏 ownerId |
| POST | `/{fileId}/thumbnail` | 登录 | 120 | w/h 默认 300×300，不落库 |
| DELETE | `/{fileId}` | owner/ADMIN | 30 | 方案C 解绑，无活跃引用才软删 |
| GET | `/test` | ADMIN | 2（复用 DELETE 限流窗口） | 限流测试端点，非业务（FileManageController） |

REST 层已按业务域拆分：上表端点分别落在 FileUploadController / FileAccessController / FileManageController。

全站 QPS：`FileGlobalQpsInterceptor` 拦 `/api/file/**`（1s/200，外置可调）。

## 五、Feign 提供端（`FileFeignProviderController implements FileClient`，前缀 `/api/feign/file`）

网关不路由 `/api/feign/**`，仅服务间可达；不加 @SaCheckLogin。拆分后 Provider 委托 FileAccessService / FileManageService / FileModerationService 三域。

- ✅ `GET /{fileId}/info` → `Result<FileOwnerDTO>`（归属校验，fail-fast）
- ✅ `POST /ref/bind` · `/ref/unbind` → `Result<Void>`（引用登记，fail-fast）
- ✅ `POST /batch-urls` → `Result<Map<String,String>>`（展示读，静默空 Map）
- ✅ `POST /{fileId}/quarantine` · `/unquarantine` → `Result<Void>`（违规图隔离/恢复，fail-fast）
- ✅ `POST /batch-safe-flags` → `Result<Map<String,Integer>>`（读，静默空 Map，缺失=未知态）
- ✅ `GET /{fileId}/content` → `Result<FileContentDTO>`（机审取图唯一入口，不过 safe_flag 闸口，fail-fast）
- ✅ `POST /{fileId}/delete-quarantined` → `Result<Void>`（隔离图到期删除：review 侧 QuarantineSweeper 保留期收口委托；前缀校验防误删 + 删本体/缩略图 + 软删，幂等）

## 六、Service 能力（按业务域拆分，原 `FileService`/`FileServiceImpl` 整体注释保留）

- `FileUploadService`（FileUploadServiceImpl）：`upload` / `uploadBatch`
- `FileAccessService`（FileAccessServiceImpl）：`getUrl` / `batchGetUrls` / `download` / `presign` / `getMeta` / `getInternalMeta` / `getThumbnail`
- `FileManageService`（FileManageServiceImpl）：`bindRef` / `unbindRef` / `delete`
- `FileModerationService`（FileModerationServiceImpl）：`quarantine` / `unquarantine` / `deleteQuarantined` / `updateSafeFlag` / `batchGetSafeFlags` / `getInternalContent`
- 跨域公共方法收敛至 `FileMetaSupport`：`getMetaOrThrow` / `buildPublicUrl`

## 七、数据模型

**file_meta**：`id`(AUTO) / `file_id`(UK) / `owner_id` / `biz_type` / `bucket` / `path`({yyyy/MM/dd}/{fileId}.{ext}) / `mime` / `size` / `width` / `height` / `align` / `access` / `status` / `safe_flag` / `sha256`(idx_dedup: sha256+size+biz_type+del_flag) / `del_flag` / `create_time` / `update_time` / `delete_time`

**file_ref**（方案C）：`id` / `file_id`(idx_file) / `biz_type` / `biz_id` / `owner_id` / `create_time`；唯一索引 `uk_ref(file_id,biz_type,biz_id,owner_id)`

判重键：`sha256+size+biz_type+del_flag=0`；PUBLIC 全局去重，PRIVATE/LICENSE 追加 owner_id 隔离。

## 八、MQ 拓扑（file 侧：生产 `image.submit` + 消费结果）

- 声明（`ModerationMqConfig`）：Exchange `EXCHANGE_MODERATION`(Topic) + `EXCHANGE_MODERATION_DLX`(Direct)；队列 `QUEUE_FILE_MODERATION_RESULT(_DLQ)` / `QUEUE_FILE_MODERATION_APPEAL_RESULT(_DLQ)`
- **生产**（上传链路）：缓存未命中（safe_flag=2）→ `publishImageSubmitEvent` 发 `ROUTING_MODERATION_IMAGE_SUBMIT`(`moderation.image.submit`)，bizId=fileId、contentHash=sha256；发布失败仅记 error，safe_flag 停留 2（default-deny 不放行）
- **消费**（`ModerationResultListener`，手动 ACK + `MqAckHelper`）：`moderation.result` REJECT→quarantine / PASS→safe_flag=1 / SUSPICIOUS→safe_flag=3；`moderation.appeal.result` 改判PASS→unquarantine
- 仅消费 `bizType=6`（图片）；prefetch=10
- 上传前置闸门：① 拼 `review:img-blacklist:{sha256}`（`IMG_REJECT_BLACKLIST_KEY`，review 写 TTL 30d）命中→抛 `FILE_UPLOAD_REJECTED(90009)`；② 查 `review:img-result:{provider}:{modelVersion}:{sha256}`（`IMG_RESULT_KEY`，TTL 72h）PASS→safe_flag=1 免审直落 / REJECT→拒传 / 未命中→safe_flag=2 送审

## 九、配置（`FileProperties`，prefix `litisle.file`）

- `seaweedfs.{endpoint,publicUrlBase,accessKey,secretKey,presignExpireMinutes=30,collections}`
- `upload.{maxImageSize=10MB,maxTotalSize=200MB,maxFileCount=200}`
- `thumbnail.{enabled,width,height}` / `executor.{core=4,max=8,queue=64,keepAlive=60,asyncTimeout=600}`
- `limit.{globalQpsWindowSeconds=1,globalQpsMaxCount=200}`
- `moderation.{imageProvider,imageModelVersion,pendingPlaceholderUrl}`
- `reconcileTask.{cron=0 0 2 * * ?,compensateThresholdMinutes=30,physicalCleanDelayDays=7,quarantinePrefix=quarantine/,skipQuarantineCleanup=true}`（cron 由 `@Scheduled` 占位符直读，不走 Bean）

- 直链 = `publicUrlBase` + `/` + bucket + `/` + path（`public-url-base` 指 Filer :8888 的 `/buckets`，非 SDK endpoint :8333）
- Multipart：`max-file-size=10MB` / `max-request-size=100MB`

## 十、错误码（`ErrorCode`，common-core）

| 码 | 常量 | 触发场景 |
|---|---|---|
| 90001 | `FILE_SIZE_EXCEEDED` | 单文件 > max-image-size（10MB） |
| 90002 | `FILE_TYPE_NOT_ALLOWED` | MIME 白名单 / 魔数校验失败 |
| 90003 | `FILE_HASH_ERROR` | SHA256 计算 IO 异常 |
| 90004 | `FILE_NOT_FOUND` | fileId 不存在或已软删 |
| 90005 | `FILE_STORAGE_UNAVAILABLE` | S3 调用异常 / 熔断打开 |
| 90006 | `FILE_CHECKSUM_MISMATCH` | 存储侧 SHA256 比对失败 |
| 90007 | `FILE_QUOTA_EXCEEDED` | per-user 总容量/文件数超限 |
| 90008 | `FILE_VIOLATIVE` | safe_flag=0 违规，禁止访问（闸口收口处抛） |
| 90009 | `FILE_UPLOAD_REJECTED` | 命中审核黑名单 / 缓存 REJECT，拒传 |
| 90010 | `FILE_NOT_READY` | PUBLIC 面 2/3 待审态 download 阻断 |
| — | `NO_PERMISSION` | 非 owner 且非 ADMIN/REVIEWER（PRIVATE 读写） |
| — | `TOO_MANY_REQUESTS` | @RateLimiter / 全站 QPS / Sentinel 触发 |

## 十一、安全闸口（safe_flag 分层，按 `access` 收口）

> access 由 bizType 决定（`resolveAccess`）：仅 LICENSE→PRIVATE，其余（AVATAR/COVER/ARTICLE_IMG）→PUBLIC。

| safe_flag | PUBLIC（AVATAR/COVER/ARTICLE_IMG） | PRIVATE（LICENSE） |
|---|---|---|
| 0 违规 | getUrl/batchGetUrls/download/presign 均拒 `FILE_VIOLATIVE(90008)` | download/presign 拒 90008（getUrl/batchGetUrls 当前未收口） |
| 1 正常 | 直链 | 预签名（owner/REVIEWER/ADMIN） |
| 2/3 待审·存疑 | getUrl/batchGetUrls→占位图 URL；download→拒 `FILE_NOT_READY(90010)` | 不占位，按角色放行 |

读类（getUrl/batchGetUrls/download/presign）权限扩为 owner/REVIEWER/ADMIN；`getContent` 不过闸口（申诉复核需重取隔离图）；`getUrl`/`batchGetUrls` 的占位图取自 `litisle.file.moderation.pending-placeholder-url`。

## 十二、对账任务（`FileReconcileTask`，每日 02:00，Redisson 锁互斥）

- Step1 补偿：`status=0` 超 30min → head 存在置 1 / 不存在软删
- Step2 清理：`del_flag=1` 超 7 天 → 跳过 `quarantine/` 前缀 → 校验 file_ref 无活跃引用 → 删对象+缩略图+元数据

## 十三、开发导航（按需求找落点）

| 我要…… | 落点 |
|---|---|
| 加/改文件业务类型 | `StorageBizTypeEnum` + `FileProperties.collections` + `resolveAccess` |
| 调上传大小/配额 | `FileProperties.Upload`（yml `litisle.file.upload`） |
| 改限流阈值 | `FileLimitConstant`（per-user）+ `FileProperties.Limit`（全站 QPS） |
| 改 safe_flag 闸口 | `FileModerationServiceImpl`（safe_flag 回写）+ `FileAccessServiceImpl`（占位图分流）+ `SafeFlagEnum` |
| 改审核 MQ 拓扑 | `ModerationMqConfig` + `ModerationResultListener` |
| 改对账/清理策略 | `FileReconcileTask` + `FileProperties.ReconcileTask` |
| 改违规图隔离保留期/到期删除 | review 侧 `QuarantineSweeper`（§6-3）+ file 侧 `FileModerationServiceImpl.deleteQuarantined`；`quarantine/` 前缀 SSOT 同 `FileProperties.ReconcileTask.quarantinePrefix` |
| 跨服务调用契约 | `litisle-api` 的 `FileClient`（Provider: `FileFeignProviderController`） |
| 改跨域元数据公共方法 | `service/support/FileMetaSupport` |
| 换存储后端 | `SeaweedFsClient`（唯一接触 AWS SDK 的门面） |
