# common-security 模块实现记录（归档索引）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md) 批次十六的已完成条目 16.1、16.1-V1（后者于 2026-11-08 补归档），条目内容原样搬运（含现象、根因、影响面实测、修法取舍、改动与实测证据、逐链路验证证据），编号不重排。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；模块速查见 [common-index.md](../../index/common-index.md)。

## 目录定位

本目录收录 `litisle-common/common-security`（服务间鉴权公共组件）的已完成实现记录。批次十六虽是跨服务基础设施缺陷（Feign 拦截器在 MQ 消费线程无 SaToken 上下文），代码归属是 common-security，因此按模块归属归档到本目录，不归入 content/search/review/file 任一业务模块。

## 批次索引

| 批次 | 主题 | 文件 |
| --- | --- | --- |
| 批次十六 | `FeignAuthInterceptor` 无 SaToken 上下文安全降级（索引同步消费重投 / 进 DLQ 修复）；16.1-V1 其余 MQ 消费者路径逐链路运行态验证 | [common-security-16-feign-interceptor.md](common-security-16-feign-interceptor.md) |
