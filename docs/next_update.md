## 暂不实现（注释中已声明的预留项）

- [x] safe\_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——2026-10-02 接入完成，见待办第三节
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置
- [ ] common-redis 库模块不引 Sentinel（保持轻量，仍用自研 LocalRateLimiter）——**业务模块已引 Sentinel**（Nacos 数据源规则已落地）；触发条件与落位见归档文档 archive/20260929-implementation-records.md §一·五（库模块当前不动）

## 待实现（按优先级排序）











### 四、待办

#### 代码修改类

##### common 模块 · MQ 公共能力抽取（common-mq，A2 方案已定稿，2026-10-02）

> 背景：MQ 代码已自然分三层——契约层（litisle-api 的 MqConstant/事件 DTO，已抽对不动）、拓扑层
> （各服务 Queue/Binding/DLX 声明，服务私有**不抽**）、机制层（Converter/发送/消费模板，跨服务重复）。
> review 模块开发在即，机制层将出现第二份重复（Jackson2JsonMessageConverter、发送/ACK 模板逐字复制必漂移）；
> 且 MQ 链路 traceId 目前断链（发事件未注入 Header、消费未恢复 MDC），违背 commom-des.md §10
> 全链路追踪既定规范。

**方案边界（防过度设计）**：
- 只抽机制不抽拓扑：Exchange/Queue/Binding/DLX 声明、yml 连接配置、prefetch 留在各服务
- ACK 模板做轻量静态工具，不做注解/切面/基类继承
- common-mq 不依赖 litisle-api（业务契约由业务服务自引），依赖方向仅 common-mq → common-core
- 拒绝塞进 common-core（会强制 gateway 等无 MQ 需求模块传递 amqp 依赖，违背模块隔离原则）

- [x] **common-mq 模块骨架**（2026-10-02 完成）：litisle-common 聚合注册 + parent 改指
  litisle-common（修复 IDE 生成的 spring-boot-starter-parent 4.1.1 误指向）+ 依赖
  common-core（compile，用 HeaderConstant.X_TRACE_ID 与 Feign/HTTP 同口径）/
  spring-boot-starter-amqp（Boot 父 POM 托管版本）/ jackson-databind（compile，
  common-core 该依赖为 provided 不传递，必须显式引入，版本由根 POM jackson-bom 锁定 2.21.4）；
  删除库模块不需要的 spring-boot-maven-plugin
- [x] **CommonMqAutoConfiguration**（2026-10-02 完成）：注册 Jackson2JsonMessageConverter（事件体 JSON 统一口径，
  禁 Java 原生反序列化）+ RabbitTemplate 定制；经 META-INF/spring/...AutoConfiguration.imports
  注册（引依赖即生效，仿 common-redis/common-security 模式）
- [x] **MqSendUtil（发送封装，两档语义）**（2026-10-02 完成）：convertAndSend 统一收口——
  ① send：尽力而为，MDC traceId 注入消息 Header + 失败仅记 error 不抛
  （对齐 file 侧「发布失败不回滚上传、safe_flag 停留占位」纪律）；
  ② sendCritical：必达语义，静默重试 1 次后仍失败抛 BizException(MQ_SEND_FAILED/10015)，
  强制调用方回滚/补偿；首个调用方为 content.unpublished（向量库 chunk 必须移除，禁静默丢失）
- [x] **MqTraceSupport（消费侧 traceId 恢复）**（2026-10-02 完成）：消费前从消息 Header 读 traceId 写 MDC，
  finally 清理防线程池复用污染，补齐 §10.2 链路图的 MQ 缺口
- [x] **MqAckHelper（手动 ACK 模板）**（2026-10-02 完成）：固化 file 侧已定稿 ACK 纪律为 SSOT——成功 ack；
  业务性失败 warn 后 ack 丢弃（防毒丸无限重投）；非预期异常 nack(requeue=false) 入 DLQ。
  review 消费者直接复用，防复制漂移
- [x] **file 模块迁移**（2026-10-02 完成）：ModerationMqConfig 删 jacksonMessageConverter() Bean 改用公共
  Converter；FileServiceImpl 的 rabbitTemplate.convertAndSend 换 MqSendUtil；
  ModerationResultListener 的 ack/nack 换 MqAckHelper。
  ⚠️ 遗留：包名 interceptor→listener 未做（涉及文件移动，待确认后手动/IDE 拖动）
- [x] **【现在就做】MqAckHelper Javadoc 补毒丸防护说明**（2026-10-03 完成）：类头新增
  【毒丸防护·隐式依赖路径】章节，列出三项禁止改动（catch 改宽 ack 丢数据 / requeue=true
  毒丸重投 / lambda 内吞异常绕过 DLQ）
- [ ] **【现在就做】common-mq 单元测试**（代码已写好，⚠️ 遗留：3 个测试类误放
  src/main/java/com/litisle/commonmq/test/，须移至 src/test/java/com/litisle/commonmq/ 并改回原包名，
  否则 main 编译因 test 依赖不可见而失败；另 CommonMqApplicationTests 删留待定）：
  MqAckHelper（mock Channel 三分支：成功 ack /
  BizException ack 丢弃 / 其他异常 nack requeue=false）；MqTraceSupport（Header 恢复 MDC /
  缺失静默跳过 / clear 清理）；MqSendUtil（mock RabbitTemplate：send 失败吞掉不抛 /
  sendCritical 重试 1 次后抛 MQ_SEND_FAILED / traceId Header 注入断言）
- [x] **【工具类预先就位，按需使用】MqIdempotentHelper（幂等消费辅助）**（2026-10-03 完成）：
  SETNX 去重键 key=`litisle:mq:idempotent:{queue}:{eventId}`（服务端派生，禁拼用户原串），
  TTL 外置 `litisle.mq.idempotent.dedup-ttl-seconds` 默认 86400；pom 补 redis starter（provided），
  自动配置 @ConditionalOnClass 守卫 + ObjectProvider optional 注入；降级两档由调用方选——
  tryAcquire 降级放行（业务幂等兜底）/ tryAcquireStrict 降级拦截（宁漏勿重，留 error 人工补偿）；
  触发条件：review 侧非幂等副作用消费者；file 侧天然幂等暂无调用方
- [x] **【工具类预先就位，按需使用】MqMetrics（收发指标埋点）**（2026-10-03 完成）：新增
  metrics/MqMetrics——mq_send_total{exchange,result} / mq_consume_total{queue,result} Counter，
  消费结果枚举与 MqAckHelper 三分支对齐（success/biz_drop/unexpected）；照搬 RedisGuard 模式
  （pom 补 micrometer-core provided + @ConditionalOnClass 守卫 + ObjectProvider optional 注入，
  MeterRegistry 缺失时静默空转）；tag 基数纪律：exchange/queue 禁动态串；
  调用点接入触发条件：review 开发时接告警面板（file 侧暂不埋）
- [ ] **【暂不做（防过度设计，附触发条件）】**：Publisher Confirm（触发：broker 丢消息造成
  真实业务事故）；本地消息表/事务性发件箱（支付级强一致已由 Seata AT 覆盖）；
  批量消费（触发：高吞吐日志/埋点类消费者）；MQ 常量/枚举抽取进 common-mq
  （已定论：契约常量 SSOT 在 litisle-api，机制常量已在位，强抽会制造第二契约源）
- [ ] **review 模块接入约束**：review 开发时发布 moderation.result 等事件一律走 MqSendUtil、
  消费 image.submit 走 MqAckHelper + MqTraceSupport，禁止再自建 Converter

**验收口径**：file 迁移后全链路日志（发事件→消费）traceId 贯通；DLQ 行为与迁移前一致；
本地 RabbitMQ 队列参数零变化（拓扑未动，不触发 PRECONDITION_FAILED）；
common-mq 单测全绿；MqIdempotentHelper/MqMetrics 编译期零强制依赖（optional/provided），
未接 Redis/Micrometer 的消费方引 common-mq 不受影响。

##### file 模块 · 审核隔离条件接入（对齐 Review-Plan §4.2.2 / §907 定稿口径）

- [x] **【最高优先级】FileReconcileTask Step2 跳过 `quarantine/` 前缀**（2026-10-02 完成）：物理清理扫 del_flag=1 且超 7 天时，
  对 path 以 `quarantine/` 开头的记录直接跳过——违规图保留期由 moderation 侧按
  `litisle.moderation.quarantine.retention-days`（当前=10 天）控制，防 file 侧 7 天清理提前删除违规图证据、
  致申诉无法复核（两套保留期打架）。
  落地：`litisle.file.reconcile-task.quarantine-prefix`（默认 quarantine/）与 `skip-quarantine-cleanup`（默认 true）
  外置到 FileProperties.ReconcileTask，Step2 循环内、活跃引用校验前跳过并留 info 日志。
- [x] **FileClient 新增 `quarantine(fileId)` / `unquarantine(fileId)`**（litisle-api）（2026-10-02 完成）：
  REJECT 时将对象从原前缀移入 `quarantine/`（safe_flag=0，del_flag 保持 0 不破坏 file_ref 不变式）；
  申诉改判 PASS 时从 `quarantine/` 移回原前缀（safe_flag 回 1）；file 模块补对应 `/api/feign/file/**` 端点。
  落地：SeaweedFsClient 补同桶 copyObject（copy+删源=移动）；隔离 key = `quarantine/` + 原 key 可逆推导，
  无需新增列；缩略图 `_thumb_*` 派生 key 一并搬移；前缀 SSOT 复用 reconcile-task.quarantine-prefix；
  幂等闸口 + 降级 fail-fast。⚠️ 调用方接入（moderation 侧 REJECT/改判时发起调用）待 review 模块实现。
- [x] **file 模块消费审核结果回写 safe_flag**（2026-10-02 完成）：
  ① 契约层：MqConstant 补审核交换机/独立 DLX/2 队列/2 DLQ/路由键常量；litisle-api 新增
  `ModerationResultEvent` / `ModerationAppealResultEvent` 事件 DTO（JSON 序列化，含 eventId/eventTime/traceId/version 公共字段）。
  ② 消费层：ModerationMqConfig 声明拓扑——**独立 Direct 死信交换机** `litisle.moderation.dlx`
  （不用业务 Topic 交换机复用方案：Direct 精确匹配无通配语义，杜绝业务侧 `moderation.#` 通配绑定误吸死信）；
  ModerationResultListener（interceptor 包）双 @RabbitListener：moderation.result 按 verdict 分流
  （REJECT→quarantine() 物理隔离 / PASS→1 / SUSPICIOUS→3），moderation.appeal.result 改判 PASS→unquarantine()；
  手动 ACK + prefetch=10 + nack(requeue=false) 入 DLQ。
  ③ 闸口层：FileServiceImpl 三处收口（getUrl/batchGetUrls/download）safe_flag=0 抛
  `ErrorCode.FILE_VIOLATIVE(90008)`；新增 `updateSafeFlag` 幂等回写（目标态短路）。
  ④ 基础设施：SafeFlagEnum 补 SUSPICIOUS(3) 四态；pom 补 spring-boot-starter-amqp；yml 补 RabbitMQ
  连接（litisle/litisle_pass@/litisle，与 definitions.json 一致）。
  ⚠️ 注意：若本地 RabbitMQ 已按旧参数（DLX=业务交换机）声明过队列，改 DLX 后启动报
  PRECONDITION_FAILED，需先删 4 个 file 侧队列再重启。
  ⚠️ 上游发布侧（review 侧发事件 + moderation 侧 REJECT/改判时调 Feign）待 review 模块实现。
- [x] **`quarantine/` 前缀常量与「file 侧跳过清理」开关外置 yml**（2026-10-02 随第一项一并完成）：保留期仍归 moderation 侧控制，
  避免双头配置打架。
- [x] **建立「存储类型 → 审核触发时机」映射（运行时，非新增枚举/bucket）**（2026-10-02 完成）：
  契约库新增 `litisleapi.enums.ImageAuditTriggerEnum`（UPLOAD_IMMEDIATE/UPLOAD_PRECHECK/EXEMPT），
  静态映射 `fromStorageBizTypeName(FileOwnerDTO.bizType)`；case 值以 StorageBizTypeEnum 枚举名对齐
  （依赖方向 file→api 不可反向引用，SSOT 注释标注改名须同步）；未知/空回退 UPLOAD_PRECHECK
  （宁严勿松：两触发点全覆盖）。review 侧消费 image.submit 后经 getFileInfo 读 biz_type 调用本映射，
  决定是否登记发布复审唤醒；EXEMPT 理论不出现（file 侧 PRIVATE 豁免不发 image.submit），
  仅作防御性分支。`StorageBizTypeEnum`/`ModerationBizTypeEnum`/本枚举三维度正交，禁合并。

##### file 模块 · 审核文档补充项（Review-Plan 全文扫描，2026-10-02）

**【上传链路】**

- [x] **拒传黑名单 + 审核缓存前置检查**（Review-Plan §4.2.2 ①/§2.1）（2026-10-02 完成）
  ：`FileServiceImpl#checkRejectBlacklist/checkAuditCache`，key 口径收口 `ModerationConstant`
  （`review:img-blacklist:{sha256}` / `review:img-result:{provider}:{modelVersion}:{sha256}`，
  TTL/抖动由 review 写入侧控制）；命中黑名单/REJECT 抛 `FILE_UPLOAD_REJECTED(90009)`，
  不落库不进 MQ；provider/模型版本外置 `litisle.file.moderation.*`，须与 review 侧一致防 key 漂移。
- [x] **落 safe_flag=2 占位 + 发 image.submit MQ**（§4.2.2 ②，即现有「步骤8 埋点」）（2026-10-02 完成）
  ：PUBLIC 面缓存未命中落库 `safe_flag=2`（default-deny），`put` 完成置 status=1 后由
  `publishImageSubmitEvent` 发 `moderation.image.submit`（事件体含 eventId/traceId/modType/contentHash）；
  发布失败仅记 error 不回滚上传，safe_flag 停留 2 闸口不放行；PASS 缓存命中免审直落 safe_flag=1；
  上传 VO/Meta VO 已回显 safeFlag 供前端轮询。
- [x] **判重排除隔离图**（§2.1，隐蔽坑）（2026-10-02 完成）：`selectByDedupKey` 追加
  `safe_flag != 0`（VIOLATIVE）排除，黑名单/缓存过期后同图重传不再秒传命中隔离图。

**【FileClient 契约】**

- [x] **safe_flag 批量读取端点**（§4.1 边界表/§4.2.2 ④）（2026-10-02 完成）：`FileClient`
  新增 `POST /api/feign/file/batch-safe-flags` → `Result<Map<String, Integer>>`（value 为
  SafeFlagEnum code，契约库不反向依赖 file 模块枚举）；提供方仅查活跃记录（del_flag=0）
  select 两列降 IO；降级**静默空 Map**（读操作），调用方须将缺失视为未知态保守处理
  （不唤醒复审/保持占位，严禁把「读不到」当「safe_flag=1 放行」）。
- [x] **机审取图契约端点**（§4.2.2 取图纪律，SSRF 防护唯一入口）（2026-10-02 完成，定字节流形态）：
  `FileClient` 新增 `GET /api/feign/file/{fileId}/content` → `Result<FileContentDTO>`
  （fileId/mime/byte[]，byte[] 经 Jackson 自动 Base64）；**不签发内网 presign URL**，
  杜绝 review 侧按 URL 请求引入的 SSRF/内网探测面（与「云API优先传字节流」纪律一致）；
  **不过 safe_flag 闸口**（申诉复核需重取隔离图 safe_flag=0）；仅服务间可用不占公开面配额；
  降级 **fail-fast**（取不到字节流机审无法进行，快速失败由 review 侧重试/熔断转人审）；
  调用方接入待 review 模块实现。

**【权限与元数据】**

- [x] **证据图 REVIEWER/ADMIN 直读**（§4.4.5）（2026-10-02 完成）：私有面读类权限（getUrl/download/
  presign）扩为 owner/REVIEWER/ADMIN——新增 `FileServiceImpl#checkPrivateReadAccess`
  + `SaRoleHelper#isReviewer`；删除等敏感写操作仍收口 `checkOwnerOrAdmin`（REVIEWER 无写权限）；
  同时闸口语义细化（曝光面分层）：=0 拒绝不变；PUBLIC 高曝光面仅 =1 放行，2/3 返回占位图 URL
  （`litisle.file.moderation.pending-placeholder-url` 外置，默认 /img/file-pending.svg）；
  download 流无占位语义抛 `FILE_NOT_READY(90010)`；PRIVATE 低曝光面不占位（审核员看证据图不被挡）。
- [x] **safe_flag 列注释/建表语句同步**（§4.4.3）（2026-10-02 完成）：FileMeta 字段注释与
  file-schema.sql 列注释/表级注释已删「【预留｜已定接入，暂未开发】」标记，改四态定义
  （0/1/2/3，SSOT 为 SafeFlagEnum）+ 已接入触发/回写链路 + 曝光面分层闸口说明；
  FileServiceImpl 类头 Javadoc 同步（【预留｜访问闸口】→【访问闸口（已接入）】）。

> 归位备注：绑定闸口（换头像/封面绑定时 safe_flag=0 拒绝绑定 70001，2/3 可绑定）属 **user 模块**
> 改动，记录在 user 侧待办，不混入本清单。

##### file 模块 · SeaweedFS 存储治理加固（2026-10-02 评估定稿）

> 维度结论：collection ✅ 已达标；replication 开发期够用、生产前必调；disk / 存储 TTL 暂不做
> （删除语义均为业务条件删除，存储 TTL 盲删不适用）；filer.conf 侧有真实缺口，最值得补。

**【现在就可做（零/低成本）】**
- [x] **Filer 启用 JWT 签名（最严重缺口）**（2026-10-02 完成）：新建 `docker/init/seaweedfs/security.toml`
  （SeaweedFS 的 JWT 配置统一在 security.toml 而非 filer.toml），启用 `[jwt.filer_signing]` 与
  `[jwt.signing]` 同值密钥；compose 挂载到 `/etc/seaweedfs/security.toml`（master/volume/filer/s3
  自动搜索加载，无需命令行参数）。写操作强制验签，拦截 8888 匿名 PUT/DELETE；刻意不配
  `[jwt.filer_signing.read]`，公开直链匿名 GET 不受影响。⚠️ 密钥为开发期写死值，生产必须轮换。
- [x] **Filer 开回收站**（2026-10-02 完成）：compose 启动参数 `-filer.recycleBinMaxHours=168`（7 天），
  与应用层 7 天软删缓冲双保险。⚠️ 生效后对账物理删除、quarantine 移除实际先进回收站，
  真正物理删除延后 7 天（预期行为）。
- [x] **s3.json 拆最小权限身份**（2026-10-02 完成）：拆为 file-service（桶级 Read+Write 四桶，
  覆盖上传/隔离移动/预签名读）、reconcile-task（对账清理）、litisle（仅 Admin，应急/建桶）。
  ⚠️ SeaweedFS S3 action 无 Delete-only 类别（DELETE 归 Write 类），且 quarantine 是同桶前缀非独立桶，
  故对账任务与 file-service 的 action 形式相同——拆分收益 = 凭证隔离轮换 + Admin 收敛；
  application.yml 默认凭证已同步 file_ak/file_sk。未来接 CDN 时另加匿名只读身份限 `Read:article` 等。

**【生产上线前必做】**



**【暂不做（防过度工程，附触发条件）】**

- disk 冷热分层（`-disk=ssd/hdd` + `010` 规范）：生产 Volume 多机时一并设计，不单独排期。
- 存储 TTL：quarantine 保留期（需按 appeal 待裁决态顺延）与对账清理（需 file_ref 活跃引用前置校验）
  均为业务条件删除，不适用存储 TTL；二期大文件分片的半途清理走 S3 桶生命周期规则（已定双保险方案）。

#### 测试类



- [ ] Sentinel Dashboard 改规则只推内存、重启丢——操作纪律：规则增删改一律去 Nacos 控制台。

- [ ] 【生产部署】监控端口网络隔离（监控链路安全前提）：各服务 8xxx 端口的 /actuator/**
  免登录放行的是「路径」而非「来源 IP」，生产环境必须通过防火墙/安全组确保业务端口
  仅对内网开放（Prometheus 所在网段可达即可，公网不可达）；或升级为
  management.server.port 独立管理端口 + 网络层仅放行 Prometheus IP 段（与网关
  8081 方案同一套纪律）。开发环境单机直连现状合规，无需处理。


***

## 限流故障降级 · 本地兜底限流（已定稿并完成，2026-09-28）

> 设计思路 / 实现细节 / 完成记录已压缩归档至
> [archive/20260929-implementation-records.md](archive/20260929-implementation-records.md) §一。

- [ ] 【待验证】停 Redis 一次合并验证：受限接口应正常放行且出现「进入降级」日志，
  第二次起走本地计数；同时观察登录防爆破「本地兜底降级」日志；Redis 恢复后冷却期
  探测成功，日志出现「恢复」回归 Redis 计数。
  （可同步用 Prometheus 观察：redis_guard_degraded 0→1→0，Alertmanager 告警
  inactive→pending→firing→resolved，一次演练同时验收告警链路。）

***

## 待办决策定稿（2026-09-28，已定稿并完成）

> 全量决策记录已压缩归档至
> [archive/20260929-implementation-records.md](archive/20260929-implementation-records.md) §二。

- [ ] 【运维侧验证】在 Dashboard 手动触发降级 / 观察熔断日志；确认 Dashboard 改 Nacos 规则热生效。
- [ ] 【待验证】停 Redis 一次：同时观察限流降级日志与登录防爆破「本地兜底降级」日志；
  恢复 Redis 后冷却期探测成功，日志出现「恢复」。
- [ ] `instance-count` 动态化时机：接 Nacos 服务发现自动计算实例数（扩缩容免人工同步）
  ——触发条件：file 服务多实例部署上线时。
  （Micrometer 指标埋点与暴露已于 2026-09-29 落地并验证通过，详见 commom-des.md §3.5，
  剩余 Prometheus/Alertmanager 为部署侧工作，零代码改动。）


- [ ] **file模块-replication ≥ 010 + Volume 多机**：用户上传原件不可再生，单副本（replication=001）不达
  生产标准；⚠️ 副本策略只影响新写入 volume，存量不自动补副本，须在首次生产写入前定稿。
- [ ] **file模块-Filer 元数据切 MySQL**：当前 leveldb 与数据同盘，盘坏 = 目录全丢（比丢文件更致命）；
  复用已有 MySQL 实例。开发期 leveldb 无所谓。

 
## 文档设计方案修复清单


