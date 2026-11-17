# 板块六：用量可观测与模型自检（实现记录归档）

> 归档日期：2026-11-16（板块六随板块七收口一并归档）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」板块六，条目原样搬运不压缩
> 互链：运行态验证见 [next_verify.md](../../next/next_verify.md) AI-V3（相似推荐与用量出账）；架构登记见 [AI-Architecture.md](../../Architecture/AI-Architecture.md) §2.3/§4.1/§5.5/§7.1/§9/§11.1

##### 板块六：用量可观测与模型自检（F9 收口 + §5.2 口径，无板块依赖可提前做）

###### 6.1 用量查询与出账端点（F9 收口）

- [x] 用户侧 GET /ai/usage/me（剩余配额、当日用量、花费统计）+ 运营侧按用途/按日汇总查询（F9 验收「后台可按场景出账，单日花费可对账」）：数据源 ai\_usage\_daily / ai\_token\_usage，只差端点与汇总组装
  - 口径：用户侧只能查自己的数据（跨用户按不存在处理，与会话归属校验同口径）；花费 = token × ai\_pricing 单价（与明细口径一致，两位小数）；匿名调用（user\_id 空）不进用户侧查询，只进运营汇总（scene 记 IP 哈希）；明细查询默认限当日或近 7 天，防深翻明细大查询；按用途/按日聚合复用 ai\_usage\_daily，不重复建汇总逻辑
  - ✅ 2026-11-16：AiUsageQueryService 编排（用户侧 usageMe=当日各用途用量+剩余配额 AiUserQuotaService#resolveLimit 复用+近 7 日花费统计按日补零；usageDetails 明细分页 Page 插件、窗口 1~7 天收口、页大小默认 20 上限 100；运营侧 summary=按用途/按日双维度跨用户求和含系统桶 user\_id=0，区间默认近 7 天上限 31 天收口）；AiUsageDailyMapper 增 sumByPurposeRange/sumByDateRange/listByUserRange 三条 GROUP BY 注解查询 + AiUsageSummaryRow 行载体；AiUsageController 三端点（GET /ai/usage/me、GET /ai/usage/me/details、GET /ai/usage/summary）——用户侧 StpUtil 登录态取 userId 禁客户端传入，运营侧 StpUtil.checkRole(ADMIN)；AiUsageMeResponse/AiUsageSummaryResponse/AiUsageDetailItem 三 DTO（剩余配额未定档以 -1 表示不限制）；跨板块遗留：网关 /api/ai/\*\* 前缀与 /ai/usage/\*\* 端点登记（板块七 7.1，已随 2026-11-16 板块七回收）；编译验证已通过（2026-11-16）

###### 6.2 模型可用性自检与自动切回（§5.2 口径）

- [x] 实现模型通道运行时自检：连续失败 N 次（阈值外置）判定「确定不可用」进入熔断期——熔断期内调用直接失败不再逐条请求模型（机审侧由 review 转人工语义兜底，生成侧抛 80005）；熔断到期后半开探测一次，成功自动恢复切回
  - 口径：自检状态收口在 provider 层（唯一接触模型的层），机审与 Agent 全通道共用；区分「单次失败」（照常 fail-fast 上抛，走既有重试语义）与「确定不可用」（熔断跳过逐条白等）；恢复无需人工干预
  - 业务依据（AI-Business-Guide §5.2）：避免「模型挂了每条还白等约 15 分钟并持续无效调用」；自检恢复后自动切回
  - 配套：补齐 ai\_guard\_state{provider} 护栏指标（板块一 1.5 简化时未建）
  - ✅ 2026-11-16：CircuitBreakerChatModel 装饰器（provider/text，CLOSED→OPEN→HALF_OPEN 状态机：连续失败达阈值熔断、失败计数窗口 5 分钟防偶发失败跨窗累积、熔断期内直接抛 80005 不逐条白等、到期半开放行一个探测请求、探测成功自动切回 CLOSED、探测失败重新熔断计时；阈值 ≤0 直通停用；状态迁移 synchronized 收口、模型调用在锁外）；AiModelConfig 装饰链改为 DesensitizingChatModel → CircuitBreakerChatModel → OpenAiChatModel（机审 TextModelProvider 与 Agent AiServices 两通道统一经过）；AiProviderProperties 增 breaker-failure-threshold=5/breaker-window-ms=300000/breaker-open-ms=60000 三配置键（yml 同步）；ai\_guard\_state{provider} Gauge 改由熔断装饰器持有（1=正常/半开探测中 0=熔断期），OpenAiCompatTextModelProvider 原 AtomicBoolean 健康标记移除（Gauge 责任移交，语义由「单次成败」改为「熔断状态」）；跨板块遗留：AI-Architecture §九/§11.1 状态符号随板块七 7.3 一并同步（已于 2026-11-16 完成）；编译验证已通过（2026-11-16）
