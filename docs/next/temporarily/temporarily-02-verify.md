> **拆分归档**：本文件拆分自同目录索引页 [Temporarily_not_implemented.md](Temporarily_not_implemented.md)，原单文件对应部分原样搬运（2026-10-28 拆分，内容零修改），各部分入口见索引页。

## 验证部分

### 网关 / 认证模块（限流故障降级本地兜底，已实现 2026-09-28）

#### 停 Redis 一次合并验证

受限接口应正常放行且出现「进入降级」日志，第二次起走本地计数；同时观察登录防爆破「本地兜底降级」日志；Redis 恢复后冷却期探测成功，日志出现「恢复」回归 Redis 计数。（可同步用 Prometheus 观察：redis_guard_degraded 0→1→0，Alertmanager 告警 inactive→pending→firing→resolved，一次演练同时验收告警链路。）

> 设计思路 / 实现细节 / 完成记录见 `docs/archive/20260929-implementation-records.md` §一。

### Sentinel（业务模块限流熔断）

#### Dashboard 手动触发降级 / 熔断日志观察 + Nacos 规则热生效确认

在 Dashboard 手动触发降级 / 观察熔断日志；确认 Dashboard 改 Nacos 规则热生效。
