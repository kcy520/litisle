## 暂不实现（注释中已声明的预留项）

- [x] safe\_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——2026-10-02 接入完成，见待办第三节
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置
- [ ] common-redis 库模块不引 Sentinel（保持轻量，仍用自研 LocalRateLimiter）——**业务模块已引 Sentinel**（Nacos 数据源规则已落地）；触发条件与落位见归档文档 archive/20260929-implementation-records.md §一·五（库模块当前不动）
- [ ] review 侧隔离保留期清理任务（quarantine sweeper）——设计口径已定稿并入审核文档
  （Review-Architecture-Development-Plan.md §4.2.2「quarantine 清理任务」段）：扫描超期 REJECT 图 →
  按图反查 appeal（status=0 待裁决顺延）→ FileClient.deleteQuarantined 经 file 侧前缀校验后物理删除
  （review 无存储写凭证）。触发条件：review 侧开始搭建 sweeper 定时任务基建（认领超时回池/申诉超时维持）
  时一并落地；此前违规图滞留隔离前缀不影响安全性（safe_flag=0 闸口 default-deny 不放行），仅占存储空间。

## 待实现（按优先级排序）











### 四、待办

#### 代码修改类



**【生产上线前必做】**



**【暂不做（防过度工程，附触发条件）】**

- disk 冷热分层（`-disk=ssd/hdd` + `010` 规范）：生产 Volume 多机时一并设计，不单独排期。
- 存储 TTL：quarantine 保留期（需按 appeal 待裁决态顺延）与对账清理（需 file_ref 活跃引用前置校验）
  均为业务条件删除，不适用存储 TTL；二期大文件分片的半途清理走 S3 桶生命周期规则（已定双保险方案）。



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

