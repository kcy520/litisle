> **拆分归档**：本文件拆分自同目录索引页 [Temporarily_not_implemented.md](Temporarily_not_implemented.md)，原单文件对应部分原样搬运（2026-10-28 拆分，内容零修改），各部分入口见索引页。

## 验证部分

### 网关 / 认证模块（限流故障降级本地兜底，已实现 2026-09-28）

#### 停 Redis 一次合并验证

受限接口应正常放行且出现「进入降级」日志，第二次起走本地计数；Redis 恢复后冷却期探测成功，日志出现「恢复」回归 Redis 计数。（可同步用 Prometheus 观察：redis_guard_degraded 0→1→0，Alertmanager 告警 inactive→pending→firing→resolved，一次演练同时验收告警链路。）

> 设计思路 / 实现细节 / 完成记录见 `docs/archive/20260929-implementation-records.md` §一。

### Sentinel（业务模块限流熔断）

#### Dashboard 手动触发降级 / 熔断日志观察 + Nacos 规则热生效确认

在 Dashboard 手动触发降级 / 观察熔断日志；确认 Dashboard 改 Nacos 规则热生效。

### review 模块（DFA/词库批次运行时复验，迁自 docs/temp/temp.md，2026-11-02）

#### 四宿主（content/file/social/review）启动复验 + 词库治理演练

- DFA 引擎切换 hankcs ACDAT（批次7，2026-11-01 完成，录 [review-09](../../archive/review/review-09-dfa-acdat-switch.md)）：四宿主启动复验 DFA 冷启动装载 + 词库热更（Pub 广播重建/原子切换）链路正常；2026-11-01 三次探测 Nacos 8848/Redis 6379/MySQL 3306 均不可达，代码层验证已由 13/13 测试 + 根聚合编译覆盖
- 词库治理演练（review-08 批次1 遗留，同批执行，录 [review-08](../../archive/review/review-08-wordlib-dfa.md)）：词库单条变更→审计落库 + 备份 gz 落盘、恢复到 v-N 演练

触发条件：本机中间件可用后执行（详录见 review-09 批次2/3 与 review-08 批次1 验收 ⚠️ 标注）。

### search 模块（联想链路优化验证，迁自 docs/temp/temp.md，2026-11-15）

#### V1 联想链路优化验证（批次一 L0/L1/L5）

- [ ] 验证批次一 L0/L1/L5 生效：同前缀重复请求命中 Redis 缓存（命中率日志）、空结果短 TTL 防穿透生效、超限 IP 请求被限流、TTL 抖动生效（依赖批次一，实现记录见 [search-11-temp-checklist.md](../../archive/search/search-11-temp-checklist.md)）
  - 2026-11-04：代码侧已就绪（编译通过），运行态验证需启动 search/content/Redis 后联调执行，本次未做，保持未勾选

触发条件：本机启动 search/content/Redis 中间件后联调执行。
