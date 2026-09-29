## 暂不实现（注释中已声明的预留项）

- [ ] safe\_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置
- [ ] 引 Sentinel——触发条件与落位见归档文档 archive/20260929-implementation-records.md §一·五（当前不动）

## 待实现（按优先级排序）











### 四、待办

#### 代码修改类



#### 测试类



- [ ] Sentinel Dashboard 改规则只推内存、重启丢——操作纪律：规则增删改一律去 Nacos 控制台。
- [ ] 【开发环境】Prometheus Docker 部署（监控链路第4层拉取端）：
  ① 新建 docker/init/prometheus/prometheus.yml（job=litisle-services，
    metrics_path=/actuator/prometheus，targets=host.docker.internal:8082/8081/8083
    对应 auth/user/file，容器内必须用 host.docker.internal 不能写 localhost）；
  ② docker run -d --name prometheus -p 9090:9090 -v 挂载上述配置 + prometheus-data 卷
    （--storage.tsdb.retention.time=15d）；
  ③ 验证：localhost:9090/targets 三 target 全 UP；查询 redis_guard_degraded
    能读出 application=litisle-auth/user/file 三条序列值为 0.0；
  ④ Alertmanager 后置：待指标数据积累、明确告警阈值后再加容器与 rule_files
    （如 redis_guard_degraded == 1 for 5m），零代码改动。
- [ ] 【生产部署】监控端口网络隔离（监控链路安全前提）：各服务 8xxx 端口的 /actuator/**
  免登录放行的是「路径」而非「来源 IP」，生产环境必须通过防火墙/安全组确保业务端口
  仅对内网开放（Prometheus 所在网段可达即可，公网不可达）；或升级为
  management.server.port 独立管理端口 + 网络层仅放行 Prometheus IP 段（与网关
  8081 方案同一套纪律）。开发环境单机直连现状合规，无需处理。
- [x] 网关封禁 /actuator/**（2026-09-29 完成）：已确认路由表全部为 /api/** 前缀无匹配、
  网关自身未引 actuator 依赖；并删除匿名白名单中的 /actuator/** 放行项（见 gateway
  application.yml 白名单注释），双保险封禁。

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

