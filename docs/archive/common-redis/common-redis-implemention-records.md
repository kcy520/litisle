# common-redis 模块实现记录（归档索引）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md) 批次十三的已完成条目，条目内容原样搬运（含核查依据、实施细节、验证证据与遗留说明），编号不重排。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；模块速查见 [common-index.md](../../index/common-index.md)。

## 目录定位

本目录收录 `litisle-common/common-redis`（基础设施公共组件）的已完成实现记录。批次十三跨 common-redis 组件、自动配置、Prometheus 抓取/告警与文档联动，但代码归属是 common-redis，因此按模块归属归档到本目录，不归入 content/search/review/file 任一业务模块。

## 批次索引

| 批次 | 主题 | 文件 |
| --- | --- | --- |
| 批次十三 | 自研 `RedisGuard` 熔断降级切换 Resilience4j（含 `LoginRetryLimiter`/`RedisGuard` 删除、测试重写、Prometheus 8084 抓取补齐） | [common-redis-13-resilience4j.md](common-redis-13-resilience4j.md) |
