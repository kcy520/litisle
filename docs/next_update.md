## 暂不实现（注释中已声明的预留项）

- [ ] safe_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
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
- [x] ~~**P2 关键类型回读兜底（条件触发）**~~：P0 证明网关校验生效，**本项不触发，关闭**

> 取舍说明：不选“服务端回读兜底”为主方案——多一次完整读（≤10MB）且 ETag 为 MD5、
> 与秒传 SHA256 判重键不同源，需另算 MD5，成本高、口径乱；方案A 在网关支持前提下零额外往返。

### 文件安全与配额（2026-09-27 新增）

- [ ] **P0 per-user 存储配额**：限流只限频率、不限总量（30 次/分钟 × 10MB = 300MB/分钟可灌爆存储）。
  需按用户/租户限制「总容量 + 文件数」，超限拒绝（新增 `FILE_QUOTA_EXCEEDED`）；
  `FileProperties.Upload` 现仅有 `maxImageSize`（单文件），需扩展 quota 配置
- [ ] **P0 缩略图接口限流**：`POST /{fileId}/thumbnail` 无限流，而 ImageIO 缩放为 CPU 密集 +
  对象存储读写，未命中缓存每次重算 → CPU 打满风险；补 `FileLimitConstant` 阈值 + `@RateLimiter`
- [ ] **P1 presign / delete 限流**：presign 可枚举 fileId 刷凭证；delete 为写操作可被批量刷
- [ ] **P1 文件内容魔数校验**：`validateUpload` 仅校验 Content-Type 字符串，可伪造
  （.exe 改名 .jpg + 伪造 header 可通过；`resolveImageSize` 失败仅返回 null 不拒绝）
  → 校验文件头 magic bytes 与声明类型一致
- [ ] **P2 上传/下载线程池隔离**：同步阻塞占 Tomcat 工作线程，规范上应隔离防打满主线程池
- [ ] **P2 Feign 侧防护**：确认 `file_ref` 四元组唯一索引（保证 bindRef 并发幂等）；
  Feign 无频率限制，异常调用可能打爆 file 服务，可加 Sentinel 规则
- [ ] **P2 系统级限流/熔断**：现有 `@RateLimiter` 均为单用户维度，缺全站 QPS 上限 / 存储后端保护


