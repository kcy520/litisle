> **拆分归档**：本文件拆分自同目录索引页 [review-implemention-records.md](review-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。

### 板块七：观测与告警收尾（§4.8/§4.7，2026-10-12 全量完成，自 next_update.md 转移）

- [x] 指标：moderation\_total{verdict} + moderation\_cost Timer + moderation\_backlog{status=pending|claimed|suspicious 三态} + moderation\_dlq\_backlog + moderation\_punish\_total{action} + moderation\_cache\_hit{layer} + wordlib\_misreport\_total{scene}（仅 scene 标签防基数爆炸）；均 ObjectProvider optional 模式（✅ 2026-10-12）
- [x] traceId 全链路贯穿：网关→发布→MQ 消息头→消费端→moderation\_record.trace\_id（✅ 2026-10-12）
- [x] Alertmanager 六规则：存疑积压/在办超时/DLQ 堆积/机审失败率/通过率骤变/缓存命中率跌落；阈值全部外置（§4.7）（✅ 2026-10-12）
- [x] §4.7 剩余配置项收尾：retry.*、cache.ttl-hours+jitter-ratio、reject-blacklist.ttl-days、last-hash.ttl-hours（落 litisle-shared.yaml）、qps.max-count、consumer.prefetch、appeal.window-days、report.abuse-threshold、claim.*、sweeper.\*、dedup.lock-seconds、llm.max-text-chars、image.model-version、few-shot-version（✅ 2026-10-12）

#### status.md 子项级进度勾选（2026-10-13 自 status.md 转移，去重后补录：父项与上方清单重复不迁，仅迁子项；板块七子项全部已实现无挂起）

- [x] 7-1.1 moderation_total{verdict} Counter（判定落库处）+ moderation_cost Timer（SPI 包裹）
- [x] 7-1.2 moderation_backlog{status} / moderation_dlq_backlog Gauge（6-1 任务内定时刷新，随板块六 6-1 落地）
- [x] 7-1.3 moderation_punish_total{action} / moderation_cache_hit{layer} Counter
- [x] 7-2.1 网关→发布→submit 事件→MQ 头→消费→record 落库贯穿；与 provider_trace_id 分列；落日志前剥离 \r\n（✅ 基础设施全链路已通：网关/服务 Filter、Feign 透传、MQ Header 注入/恢复、record 落库均既有；本次新增 common-core TraceIdSanitizer 收口 CRLF 净化，网关/服务两 Filter 生成口径收敛 SSOT、MqTraceSupport 接线净化；既有发布点已按 setTraceId 惯例写入事件体，content 模块发布端随模块建设落地）
- [x] 7-3.1 六规则阈值外置落 Nacos（存疑积压/在办超时/DLQ 堆积/机审失败率/通过率跌幅/缓存命中跌落）（✅ 2026-10-12：阈值指标化方案——新增 ModerationProperties.Alert 五阈值组 + AlertThresholdMetrics 暴露 moderation_alert_threshold{rule} 阈值 Gauge（含复用 claim-timeout-minutes 共 6 个标签值）；alert-rules.yml 新增 litisle-moderation 规则组，表达式用 on(application) 引用阈值指标零写死数字；在办超时经新增 moderation_claim_timeout_repool_total 回池计数器表达；阈值 yml 值随 7-4.2 落 Nacos）
- [x] 7-4.1 litisle-shared.yaml 共享项：last-hash.ttl-hours / cache.ttl-hours / ttl-jitter-ratio / reject-blacklist.ttl-days（✅ 2026-10-12：四项已落 docs/nacos-yaml.md 共享模板 litisle.moderation 段，含归共享原因注释；Nacos 实际导入按模板执行；review 侧 cache/reject-blacklist 本地值已标注「共享默认值+环境覆盖」双源口径；last-hash 写入点在 content 服务随模块建设消费）
- [x] 7-4.2 review 服务项：retry / qps / consumer.prefetch / appeal / report / claim / sweeper / dedup / llm / model-version / alert.*（既有不生效配置保留加注释）（✅ 2026-10-12：绑定收口——ModerationProperties 新增 RejectBlacklist/Claim/Report 三组完成全量绑定；appeal.timeout-days 与 claim.timeout-minutes 口径分别收敛至 sweeper 组（永不生效保留备查）；消费点接线归属：认领配额/诬告反制随板块五 claimTask/decideTask，黑名单 TTL 写入随图片拒绝链路）

### 板块七实施细案步骤（2026-10-13 自 next_update.md 转移）

#### 7-1 指标埋点（均 ObjectProvider optional 模式）

| 指标 | 类型/标签 | 埋点位置 |
|---|---|---|
| moderation_total{verdict=pass\|reject\|suspicious} | Counter | 3-1/3-2 判定落库处 |
| moderation_cost | Timer | SPI 调用包裹 |
| moderation_backlog{status=pending\|claimed\|suspicious} | Gauge | 6-1 第 3 步定时刷新 |
| moderation_dlq_backlog | Gauge | 6-1 同任务刷新（DLX 队列深度） |
| moderation_punish_total{action} | Counter | 5-1/5-4 punish_log 落库同源 |
| moderation_cache_hit{layer=result\|blacklist} | Counter | 3-1/3-2 缓存命中处 |
| wordlib_misreport_total{scene} | Counter | 已落地（板块二），灰度转正唯一数据源 |

**落地注记（2026-10-12，7-1 已完成）**：新建 review 侧统一埋点组件 `ModerationMetrics`（对齐 WordlibMetrics 的 ObjectProvider optional 模式，未接 actuator 静默跳过）；moderation_total 在文本 `persistAndPublish` 判定映射后/图片单图 `persistSingleImage` 前/复审聚合判定后收口计数（缓存命中与真实送审两路均覆盖，非逐图重复计数）；moderation_cost 在文本分片送审与图片逐张 SPI 真实送审后按 result.costMs 预量测记录（缓存命中不计入）；moderation_cache_hit{layer=result} 在文本/单图/复审三处缓存命中分支计数；moderation_punish_total{action} 在 `RejectPunishAdapterHost` punish_log 落行成功后同源收口（全链路处罚落库无分支重复埋点）；moderation_cache_hit{layer=blacklist} 在 file 侧 `FileUploadServiceImpl.checkRejectBlacklist` 命中分支内联注册（跨模块不可复用 review 组件，指标名/标签口径与 review 侧一致）；标签均为枚举名小写派生（verdict/layer/action），无基数爆炸风险；moderation_backlog/moderation_dlq_backlog 两个 Gauge 已随板块六 6-1 落地。

**注意事项**：标签只允许 verdict/status/action/layer/scene 等限定枚举集合，**禁按词/内容 ID/用户打标签**（基数爆炸拖垮 Prometheus）。

#### 7-2 traceId 全链路贯穿

网关过滤器生成/透传→发布接口写入 submit 事件 traceId 字段→MQ 消息头→消费端解析→moderation_record.trace_id 落库；与厂商凭证 provider_trace_id **分列**（前者排内部链路、后者对外出证）；落日志前剥离 \r\n。

**落地注记（2026-10-12，7-2 已完成）**：新建 common-core `TraceIdSanitizer`（sanitize 逐字符剥离 \r\n 及全部 C0 控制字符与 DEL(\u007F) + 64 字符硬截断；sanitizeOrGenerate 净化后空值兑底生成 32 位无连字符 UUID）。三个 traceId 写入点全部接线：网关 TraceIdFilter 与 common-security TraceIdFilter 统一改走 sanitizeOrGenerate（本地 UUID 生成口径收敛 SSOT），common-mq MqTraceSupport.restore 写 MDC 前过 sanitize（保持缺失静默跳过既有口径）。既有发布点（file 侧 ImageSubmitEvent、review 侧补偿重投/申诉改判）已按 `evt.setTraceId(MDC.get("traceId"))` 惯例写入事件体；content 模块发布端接线随模块建设落地。

#### 7-3 Alertmanager 六规则（阈值全部外置 §4.7 alert.*）

①存疑积压>suspicious-backlog-threshold(50)；②在办超时=claimed 超 claim.timeout-minutes 未裁决；③DLQ 堆积>dlq-backlog-threshold(100)；④机审失败率>moderation-fail-rate(0.2)；⑤通过率相对基线跌幅>pass-rate-drop-ratio(0.3)；⑥缓存命中率跌落>cache-hit-drop-ratio(0.5)。括号内为建议初值，均落 Nacos。

**落地注记（2026-10-12，7-3 已完成，阈值指标化方案）**：Prometheus 规则文件无法直读 Nacos，故采用社区标准做法——①`ModerationProperties` 新增 `Alert` 嵌套组（五阈值，在办超时复用既有 sweeper.claimTimeoutMinutes 不另立配置防口径漂移）；②新建 `AlertThresholdMetrics`（moderation/config 包）@PostConstruct 惰性注册阈值 Gauge `moderation_alert_threshold{rule=...}`（suspicious-backlog/claim-timeout-minutes/dlq-backlog/moderation-fail-rate/pass-rate-drop/cache-hit-drop 六个固定标签值防基数爆炸，Gauge 绑定 properties getter，Nacos 重绑后抓取即时生效）；③`alert-rules.yml` 新增 litisle-moderation 规则组六条，表达式 `on(application)` 引用阈值指标零写死数字，④⑤⑥分母为零时结果 NaN 不触发（天然防初启动误报）；④`ReviewTaskSweeper` 补回池 Counter `moderation_claim_timeout_repool_total`（批量按轮 increment(reclaimed)），规则②用 `increase(...[15m]) > 0` 表达持续出现认领超时——claim.timeout-minutes 变更在 Nacos 改即间接改变告警触发点；⑤alert.* 五项阈值 yml 值随 7-4.2 落 Nacos，alertmanager.yml dev-null 接收器维持现状（通知渠道后续接）。

#### 7-4 §4.7 剩余配置项收尾（落位分两类）

- **litisle-shared.yaml（跨服务共享）**：litisle.moderation.last-hash.ttl-hours=72（content 写/review 声明，须与 cache.ttl-hours 对齐防口径漂移）、cache.ttl-hours=72、cache.ttl-jitter-ratio=0.1、reject-blacklist.ttl-days=30。
- **review 服务 yml/Nacos**：retry.max-count=5 / retry.base-delay-ms=30000、qps.max-count、consumer.prefetch=10、appeal.window-days=7 / appeal.timeout-days=7、report.abuse-threshold=3、claim.timeout-minutes=30 / claim.max-active-per-reviewer=20、sweeper.interval-seconds=60 / sweeper.stuck-resubmit-minutes=30、dedup.lock-seconds=60、llm.max-text-chars=6000、image.model-version、few-shot-version、alert.* 五项。

**注意事项**：既有暂不生效的 yml 配置保留并加注释说明（用户规则，不删）。

**落地注记（2026-10-12，7-4 已完成）**：①7-4.1 四项已落 docs/nacos-yaml.md 的 litisle-shared.yaml 模板（litisle.moderation 段，含归共享原因注释：last-hash 双侧读同一 TTL 防漂移、cache 与 last-hash 严格对齐），Nacos 实际导入按模板执行；②7-4.2 绑定收口：盘点发现 retry/qps/prefetch/sweeper/dedup/llm/model-version/alert.* 八组早已绑定生效，真实缺口为 reject-blacklist/claim/report 无绑定类——本次在 ModerationProperties 新增 RejectBlacklist/Claim/Report 三组完成全量绑定（yml 默认值不变）；③口径收敛决策：appeal.timeout-days 与 claim.timeout-minutes 分别与 sweeper.appeal-timeout-days / sweeper.claim-timeout-minutes 重复，统一收敛读 sweeper 组（yml 两项标注永不生效保留备查，防双源漂移）；④消费点接线归属：claim.max-active-per-reviewer（认领配额校验）与 report.abuse-threshold（诬告反制）随板块五 claimTask/decideTask 实现接线，reject-blacklist.ttl-days 写入点（REJECT 落黑名单 TTL）随图片拒绝链路接线，last-hash.ttl-hours 写入在 content 服务随模块建设消费；⑤review application.yml 的 cache/reject-blacklist 本地值标注「共享默认值+环境特定覆盖」双源语义。


