## 暂不实现（注释中已声明的预留项）

- [ ] common-redis 库模块不引 Sentinel（保持轻量，仍用自研 LocalRateLimiter）——**业务模块已引 Sentinel**（Nacos 数据源规则已落地）；触发条件与落位见归档文档 archive/20260929-implementation-records.md §一·五（库模块当前不动）
- [ ] 审核模块——板块三 3-5 业务方回写矩阵消费端（content / social）（§4.5.2）（⏸ 已移入 `Temporarily_not_implemented.md`（2026-10-11），含完整步骤与 message→social 口径修正）

> 2026-10-11 转移注记：审核模块已实现任务（板块三/板块四契约层、auth 注册链路 A 组、auth 处罚消费端 B 组）及其实现步骤已归档至 `docs/archive/review-implemention-records.md`；板块三/四挂起项与预留项已移入 `docs/next/Temporarily_not_implemented.md`。

## 待实现（按优先级排序）

## 审核模块开发清单（板块化，2026-10-06 梳理自 Review-Architecture-Development-Plan.md）

> 现状基线（已完成，不再列入）：file 侧 safe\_flag 四态闸口/拒传黑名单/quarantine 契约/result·appeal.result 消费回写/getContent/占位图/对账跳隔离前缀；litisle-api 契约层（MqConstant/ModerationConstant/事件体/FileClient）；review 侧人审域（claim/decide CAS、申诉防重三层、双人复核、举报撤回/诬告反制、词库表）。
> 推进顺序：板块一 → 三 → 四/五；板块二独立可并行先行；板块六依赖三；板块七随三收尾。各条目括号内为计划文档章节号，可回溯细案。

注意**：**\
**业务代码中LLM审核部分目前留下注释预留占位即可，暂不实现**

### 板块四：发布侧文本链路接入（§4.2.1 ①①′② / §4.5.3）——已全部转移（2026-10-11）

> 板块四勾选清单与实施细案（4-1～4-7）已归档至 `docs/archive/review-implemention-records.md`；入口嵌入挂起项已移入 `docs/next/Temporarily_not_implemented.md`；进度勾选看 status.md 板块四。

### 板块五：申诉与处罚联动闭环（§4.2.3 / §4.5.2）——已全部归档（2026-10-13）

> 板块五勾选清单（5-1~5-5 全部 [x]，含落地注记）已归档至 `docs/archive/review-implemention-records.md`；进度勾选看 status.md 板块五。

### 板块六：Sweeper 基建 + quarantine 清理任务（§4.3 / §4.2.2）——已全部归档（2026-10-13）

> 板块六勾选清单（6-1~6-4 全部 [x]，含 2026-10-12 落地注记）已归档至 `docs/archive/review-implemention-records.md`；进度勾选看 status.md 板块六。

### 板块七：观测与告警收尾（§4.8 / §4.7）——已全部归档（2026-10-13）

> 板块七勾选清单（7-1~7-4 全部 [x]，含 2026-10-12 落地注记）已归档至 `docs/archive/review-implemention-records.md`；进度勾选看 status.md 板块七。

### 审核模块暂不做（⏸ 已移入 `Temporarily_not_implemented.md` 第三节，2026-10-11）

> 云 API 生产适配器 / 图文组合审核·OCR 多模态盲区 / 撤销改判公示 / 视频·音频审核 / quarantine sweeper 预留条目均已移入 `docs/next/Temporarily_not_implemented.md` 第三节（触发条件随附），本节不再重复维护。

### 四、待办

#### 代码修改类

**【生产上线前必做】**

**【暂不做（防过度工程，附触发条件）】**

- disk 冷热分层（`-disk=ssd/hdd` + `010` 规范）：生产 Volume 多机时一并设计，不单独排期。
- 存储 TTL：quarantine 保留期（需按 appeal 待裁决态顺延）与对账清理（需 file\_ref 活跃引用前置校验）
  均为业务条件删除，不适用存储 TTL；二期大文件分片的半途清理走 S3 桶生命周期规则（已定双保险方案）。
- [ ] Sentinel Dashboard 改规则只推内存、重启丢——操作纪律：规则增删改一律去 Nacos 控制台。
- [ ] 【生产部署】监控端口网络隔离（监控链路安全前提）：各服务 8xxx 端口的 /actuator/\*\*
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
  （可同步用 Prometheus 观察：redis\_guard\_degraded 0→1→0，Alertmanager 告警
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

## 审核模块剩余待办 · 实施细案（2026-10-08 定稿，可直接照此写码，无需回查 Review 计划文档）

> **前置阻塞**：litisle-ai chat 端点（POST /api/feign/ai/chat，OpenAI 兼容转发）未实现——板块三 3-1 联调前必须落地（base-url/api-key/model 走 Nacos 外置，禁提交密钥；超时对齐 feign.client.config.litisle-ai.read-timeout）。业务代码中 LLM 审核部分留注释预留占位即可（用户决策）。
> **通用纪律（所有条目默认适用，后文不再重复）**：
> ① SQL 全走 MyBatis #{} 绑定禁拼接；排序/筛选列名服务端白名单枚举映射；LIKE 对 %/_/\\ 转义。
> ② Redis key 各段服务端派生（枚举名/数字 ID/SHA256），禁拼用户原串；value 一律 JSON，禁 Java 原生反序列化。
> ③ UGC/traceId 落日志前剥离 \r\n（防 CRLF 伪造日志行）。
> ④ 指标埋点 ObjectProvider<MeterRegistry> optional 注入，未接入不影响核心功能。
> ⑤ review 不跨库直写业务表，业务态回写一律 result 事件 + 业务方 CAS；跨库回查走 litisle-api 契约，fallbackFactory fail-fast（回查失败不 ACK 进延迟重试，禁静默吞错）。
> ⑥ 缓存/黑名单/last-hash TTL 一律 ±10% 抖动（防同批内容集体过期雪崩）。


> 板块四 4-1～4-7 实施细案（含 4-3 免审 last-hash、4-5 preview-check）已归档至 `docs/archive/review-implemention-records.md`；入口嵌入挂起项见 `Temporarily_not_implemented.md` 第二节。板块五/六/七实施细案均已归档至 `docs/archive/review-implemention-records.md`。

### 板块五：申诉与处罚联动闭环（落位：litisle-review workflow 包 + litisle-user / litisle-auth 消费端）——细案已归档（2026-10-13）

> 板块五实施细案（5-1～5-5，含步骤、注意事项与落地注记）已归档至 `docs/archive/review-implemention-records.md`。



### 板块六：Sweeper 基建 + quarantine 清理（落位：litisle-review workflow 包 ReviewTaskSweeper + cleanup 包）——细案已归档（2026-10-13）

> 板块六实施细案（6-1～6-4，含步骤与注意事项）已归档至 `docs/archive/review-implemention-records.md`。

### 板块七：观测与告警收尾（§4.8/§4.7）

> 板块七实施细案（7-1～7-4，含步骤与 2026-10-12 落地注记）已归档至 `docs/archive/review-implemention-records.md`。



### 完成后动作

- 每条落地后回填本文档上方清单对应 [x] 与日期。
- 板块六 6-3 落地后：勾选「暂不实现」节 quarantine sweeper 条目。
- 板块三联调前置：先落 litisle-ai chat 端点（「暂不实现」节条目）。
- 推进顺序：3-1→3-2→3-3→3-4→3-5→3-6→3-7→3-8 → 板块四（与 3-4/3-5 联调）→ 板块五（复用 result 事件）→ 板块六 → 板块七随三收尾并行埋点。

## 文档设计方案修复清单


# 临时清单
