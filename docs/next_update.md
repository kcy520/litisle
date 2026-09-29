## 暂不实现（注释中已声明的预留项）

- [ ] safe\_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置
- [ ] 引 Sentinel——触发条件与落位见归档文档 archive/20260929-implementation-records.md §一·五（当前不动）

## 待实现（按优先级排序）











### 四、待办

#### 代码修改类



#### 测试类



- [ ] Sentinel Dashboard 改规则只推内存、重启丢——操作纪律：规则增删改一律去 Nacos 控制台。
- [ ] 网关封禁 /actuator/**（安全项，2026-09-29 引入 actuator 后新增）：actuator 端点已免登录放行，
  若网关把 /actuator/** 路由到公网，等于把健康/指标端点免登录裸奔到外网——
  需确认网关路由表无 /actuator 匹配，或显式拉黑该前缀。

***

## 限流故障降级 · 本地兑底限流（已定稿并完成，2026-09-28）

> 设计思路 / 实现细节 / 完成记录已压缩归档至
> [archive/20260929-implementation-records.md](archive/20260929-implementation-records.md) §一。

- [ ] 【待验证】停 Redis 一次合并验证：受限接口应正常放行且出现「进入降级」日志，
  第二次起走本地计数；同时观察登录防爆破「本地兑底降级」日志；Redis 恢复后冷却期
  探测成功，日志出现「恢复」回归 Redis 计数。

***

## 待办决策定稿（2026-09-28，已定稿并完成）

> 全量决策记录已压缩归档至
> [archive/20260929-implementation-records.md](archive/20260929-implementation-records.md) §二。

- [ ] 【运维侧验证】在 Dashboard 手动触发降级 / 观察熔断日志；确认 Dashboard 改 Nacos 规则热生效。
- [ ] 【待验证】停 Redis 一次：同时观察限流降级日志与登录防爆破「本地兑底降级」日志；
  恢复 Redis 后冷却期探测成功，日志出现「恢复」。
- [ ] `instance-count` 动态化时机：接 Nacos 服务发现自动计算实例数（扩缩容免人工同步）
  ——触发条件：file 服务多实例部署上线时。
  （Micrometer 指标埋点与暴露已于 2026-09-29 落地并验证通过，详见 commom-des.md §3.5，
  剩余 Prometheus/Alertmanager 为部署侧工作，零代码改动。）

