## 暂不实现（注释中已声明的预留项）

- [ ] safe\_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置
- [ ] common-redis 库模块不引 Sentinel（保持轻量，仍用自研 LocalRateLimiter）——**业务模块已引 Sentinel**（Nacos 数据源规则已落地）；触发条件与落位见归档文档 archive/20260929-implementation-records.md §一·五（库模块当前不动）

## 待实现（按优先级排序）











### 四、待办

#### 代码修改类



#### 测试类



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


 
## 文档设计方案修复清单

> 审核模块设计审查进度盘点（截至 2026-09-30）。状态：✅已扫描并给出修复 / ⚠️已触及但未系统排查 / ❌尚未扫描。
> 说明：仅登记「设计文档层面」的修复项，代码落地另行跟踪。

### 一、✅ 已扫描并已给出修复（含待接受 diff）

| 方面 | 具体点 | 状态 |
|---|---|---|
| 状态机 | `safe_flag 0-3` 与 `review_task.status` 映射、default-deny 不变 | 已核 |
| 幂等建任务 | `uk_biz_scene` upsert 语义、重置清单（补清 mod_verdict/layer1_hit/hit_words/layer2_risk） | 已修 |
| 免审直落 | 文本 hash 未变 + 配图聚合 hash 一致 + 非驳回/下架态 | 已修 |
| 图片举报闭环 | 回填 `report.review_task_id` 至既有 IMAGE 任务，防卡 status=2 | 已修 |
| 隔离图清理 | 清理前校验 `appeal.status=0` 待裁决，防证据提前删除 | 已修 |
| 缓存策略 | key 含 `wordlibVer/provider`；兜底显式排除 SUSPICIOUS | 已修 |
| 结果事件体 | 补 `contentHash/wordlibVer/reviewTaskId` | 已修 |
| 机审处罚规则 | 只重放 `action∈{1,2}` 的 status=1 行、区分展示分/自动阈值作用域 | 已修 |
| 申诉改判事件体 | 必带 `reviewTaskId`；作者通知幂等键纳入 contentHash | 已修 |
| punish.result 乱序 | 拉取模型边界 + 按 userId 串行 + sweeper 双向对账 + 字段更名 punishLogId | 已修 |
| DLQ 转人审 | 补置 `mod_verdict=3` 与人审列表口径一致 | 已修 |
| violation_score 并发 | 乐观锁 → SQL 原子累加（score = score + Δ） | 已修 |
| punish_log | 补 `idx_biz` 索引 + `score` 列 | 已修 |
| 申诉裁决幂等 | `judge` 端点 CAS `where id=? and status=0`，防跨审核员并发改判致重复发事件/重算/留痕 | 已修 |
| 申诉维持留痕 | 维持分支补 `moderation_record(mod_type=6)` + 作者通知，与改判对称 | 已修 |
| 申诉回写举报 | 改判 PASS 回写 `report.result='申诉改判PASS'`，口径与最终裁决一致 | 已修 |
| 申诉超时 SLA | 新增 `appeal.timeout-days`，sweeper 超时自动维持（宁严勿松） | 已修 |
| 申诉窗口基准 | `appeal.window-days` 以 `review_task.decide_time` 为基准，防改判致窗口漂移 | 已修 |
| 撤销定位兜底 | 机审驳回无 `punish_id` 时按 `review_task_id` 反查 `punish_log` 生效行 | 已修 |
| 越权与鉴权 | judge/词库仅 ADMIN、原裁决人回避、内部契约端点不外露、申诉归属校验 | 已修 |
| 图片取图 SSRF | 一律按 fileId 走内部契约，禁用户 URL；外链需 allowlist+私网拦截 | 已修 |
| 存储型 XSS | 转义范围扩至全 UGC 回显字段 + 「审核≠净化」防线归属声明 | 已修 |
| 注入防护 | SQL 参数化/排序白名单、词库导入导出防公式注入与 DoS、日志 CRLF、缓存键、禁 Java 反序列化 | 已修 |
| Prompt 注入 | 补间接注入（OCR/外链）与输出侧白名单校验 | 已修 |
| 留存合规 | `record.retention-months=6` 归档迁移+跳过争议引用行+防篡改（链式摘要），append-only 声明 | 已修 |
| 举报撤回 | 新增 withdraw 端点、`status=4-已撤回`、active_key 释放允许再举报 | 已修 |
| 举报防滥用 | 禁举报自己内容（reporter_id ≠ 目标 owner） | 已修 |
| 诬告反制/信誉 | `abuse_flag` 列 + `report.abuse-threshold=3` → 反向 punish_log(action=4)+降信誉 | 已修 |
| 举报↔申诉联动 | 改判 PASS 回写 `report.result='申诉改判PASS'` 并通知举报人 | 已修 |
| 人审认领并发 | claim CAS `status=2 and assignee is null` + `claim_time` 列，affected=0→70004 | 已修 |
| 认领超时释放 | 新增 `claim_time` 列，sweeper 按此判定回池（勿用 update_time） | 已修 |
| 裁决幂等 | decide CAS 同时置 `status=3/4 + decided_by + opinion + decide_time`，终态后重复裁决被拦截 | 已修 |
| 申诉窗口基准列 | 新增 `review_task.decide_time` 列（此前文档引用但表缺列） | 已修 |
| 双人复核（轻量） | 高风险撤销 = ADMIN-only + 回避原裁决人 + 二次确认 + 双签留痕（不引入新状态/端点） | 已修 |
| 审核员配额 | 新增 `claim.max-active-per-reviewer` 同时在手上限 | 已修 |
| 敏感词库版本 | 新增 `sensitive_word_version` scene 级版本表；任何变更同事务 +1 并广播，行级 version 降为快照 | 已修 |
| 词库灰度/误报 | 新词默认 MID 灰度、误报率达标转 HIGH、独立误报回流入口（作者/运营） | 已修 |
| 词库启动兜底 | 加载失败/为空时加载本地持久化快照，绝不空词库 default-allow | 已修 |
| 模型/SPI 版本 | 缓存 key 与 `moderation_record` 绑定 `modelVersion`/`few-shot-version`，防同 provider 模型/prompt 升级复用旧结论 | 已修 |
| SPI 超时/熔断 | ONNX `onnx.timeout-ms` + 云 API `api.timeout-ms` + 云 API 按异常比例熔断（grade=1） | 已修 |
| provider 自检 | 配置值与适配器 Bean 不匹配时启动 fail-fast，防无 Bean 静默漏审 | 已修 |
| 成本护栏 | 单内容在途去重（`dedup.lock-seconds` SETNX 短锁）+ 单用户 `@RateLimiter(USER)` | 已修 |
| 多模态边界 | 视频/音频一期显式声明不支持，禁「无方法即默认通过」隐式放行 | 已修 |
| RAG 向量移除 | `content.unpublished` 事件登记 + 复审驳回/封禁连带下架路径必须发事件，防违规 chunk 残留 | 已修 |
| RAG 检索过滤 | KNN 强制 filter `visibility=PUBLIC` + 引用展示前回查文章当前态 | 已修 |
| RAG Prompt 注入 | 检索 chunk 按不可信数据包裹、不拼入 system 段 | 已修 |
| RAG 乱序/兜底 | ingest 前回查文章态防乱序写回；Embedding 重试耗尽 DLQ+告警 | 已修 |
| 监控与告警 | 积压 Gauge 三态全量、DLQ 堆积、处罚趋势、词库误报率指标 + 6 条告警规则 | 已修 |
| 性能容量 | 缓存 TTL 抖动防雪崩、Redis 降级放大保护、ONNX 并发闸、MQ prefetch 上限 | 已修 |
| 社交场景（评论） | 补 `comment` 回写 CAS 显式口径 0→1/0→2（禁套文章 1→2/1→3），防初态 0 永不匹配卡待审 | 已修 |
| 社交场景（私信） | 撤回 CAS（仅本人/仅正常态）、配额原子条件更新防超发、REJECT 仍占配额、HIGH 同步拒发不投递 | 已修 |
| 社交场景（群聊） | 一期显式不支持（无表无链路），预留扩展点，禁隐式放行 | 已修 |

> 已接受 diff：上一轮 6 处 + 本轮 3 处（共 9 处）；申诉闭环 6 处、安全面 4 处（2026-09-30）已接受；敏感词库 4 处已接受；注入专项 2 处、举报链路闭环 5 处、人审工作台 6 处（2026-09-30）待接受；模型/SPI 层 9 处（设计 7 + SQL 2，2026-09-30）待接受；监控与告警 3 处、性能容量 7 处（2026-09-30）待接受；社交场景 4 处（2026-09-30）待接受；边界情况 5 处（2026-09-30）待接受；状态映射矩阵 3 处（2026-09-30）待接受；§二 未系统排查项 4 处（2026-09-30）待接受（含 `appeal.decide_time` 补列）。

### 二、✅ 已系统排查并给出修复（原「已触及但未系统排查」，2026-09-30 收口）

- [x] `ReviewTaskSweeper` 的 `status=0` 补偿重发 与 MQ 正常重投 幂等边界（补偿阈值 > MQ 最大退避总时长 + 重发前抢 dedup.lock + 消费端 CAS `where status=0` 去重）
- [x] `moderation_record` 留存执行：归档迁移（非硬删）+ 跳过未终态申诉/生效处罚引用行 + 防篡改（链式摘要 prev_hash + 撤 UPDATE/DELETE 授权）
- [x] `report ↔ review_task` 多轮关系：decide 回写按 `status<3` 活跃态过滤、反查举报排除历史轮、禁止裸用 review_task_id UPDATE（任务行对多轮 report 属时间维 1:N）
- [x] 申诉剩余子项：三层防重（关联列级 + 内容级轮次新鲜度 `appeal.decide_time` + 并发 active_key 兜底）、跨轮 N 由关联实体轮次自然约束、改判公示仅脱敏摘要（二期预留，一期站内通知）

### 三、❌ 尚未扫描（审核模块剩余面）

- [x] ~~敏感词库管理~~（已修：scene 级版本表、版本自增纪律、MID 灰度转正、误报回流、启动快照兜底）
- [x] ~~模型/SPI 层~~（已修：口径版本绑定缓存 key/留痕、ONNX/云API 超时+熔断、provider 启动自检、成本护栏在途去重+单用户限流、视频/音频显式不支持）
- [x] ~~RAG 准入与向量库~~（已修：content.unpublished 事件登记+下架路径移除向量、检索强制可见性 filter、RAG 内容 prompt 注入防护、事件乱序防御、Embedding 重试耗尽 DLQ 告警）
- [x] ~~监控与告警~~（已修：积压 Gauge 三态全量、DLQ 堆积、处罚趋势、词库误报率指标、6 条告警规则 + 4 个阈值外置）
- [x] ~~安全~~（已修：越权角色矩阵+回避原则、内部契约端点不暴露、取图 SSRF 纪律、XSS 转义范围与「审核≠净化」；注入防护 SQL/词库/日志/缓存键/反序列化 + Prompt 间接注入与输出侧约束——注入专项 diff 待接受）
- [x] ~~性能容量~~（已修：缓存 TTL 抖动防雪崩、Redis 降级直调模型放大保护、ONNX 并发信号量、MQ prefetch 上限；积压→扩容联动列为运维预案）
- [x] ~~社交场景~~（已修：评论回写 CAS 显式口径、私信撤回 CAS + 配额原子性、HIGH 同步拒发、群聊一期显式不支持；用户名注册同步闸门已覆盖，无改名路径故无绕过）
- [x] ~~状态映射矩阵~~（已修：bizType↔target_type 显式映射 SSOT 表（禁恒等假设，5↔4/6↔5 错位、biz_type=4 不可举报）、各 bizType 业务态回写矩阵补全（3-用户/4-作家申请/6-图片 缺行补齐，明示 biz_type=3 无 result 业务消费者））
- [x] ~~边界情况~~（已修：空内容/超长前置校验（含空 hash 缓存污染）、标题随文送审+长度上限、超长单片字符硬切防尾部漏审、纯图/图文聚合边界、删除/下架/转私密失效 last-hash+缓存键防重发绕过下架、作者自查端点+防词库 oracle）

### 建议下一步（按 安全 > 闭环 > 体验 排序）

1. ~~申诉链路完整闭环~~（已处理：幂等守卫、维持留痕、举报回写、超时 SLA、窗口基准、撤销定位）
2. ~~安全面~~（越权/SSRF/XSS/注入 均已完成）
3. ~~举报链路完整闭环~~（已处理：举报撤回、禁自举报、诬告反制/信誉、举报↔申诉联动回写）
4. ~~人审工作台~~（已处理：认领 CAS+claim_time、超时回池、裁决幂等+decide_time、双人复核、配额；误判追责已由 decided_by+record+AuditLog 覆盖）
5. ~~敏感词库管理~~（已处理：scene 级版本表、灰度转正、误报回流、启动快照兜底）
6. ~~模型/SPI 层~~（已处理：口径版本绑定、超时/熔断、provider 自检、成本护栏、多模态边界）
7. ~~RAG 准入与向量库~~（已处理：向量移除闭环、检索可见性过滤、prompt 注入防护、乱序防御、DLQ 告警）
8. ~~监控与告警~~（已处理：积压三态 Gauge、DLQ 堆积、处罚趋势、误报率指标、6 条告警规则 + 阈值外置）
9. ~~性能容量~~（已处理：TTL 抖动防雪崩、Redis 降级放大保护、ONNX 并发闸、MQ prefetch；扩容联动列运维预案）
10. ~~社交场景~~（已处理：评论/私信回写与撤回口径、配额原子性、群聊显式不支持）
11. ~~边界情况~~（已处理：空内容/超长前置校验、单片硬切、纯图/图文聚合边界、删除后缓存失效重审、作者自查端点+防 oracle）
12. ~~状态映射矩阵~~（已处理：映射 SSOT + 回写矩阵完整性）
13. ~~§二 未系统排查项~~（已处理：sweeper 重发幂等、record 留存执行、多轮举报关系、申诉剩余子项；补 `appeal.decide_time` 列支撑内容级轮次新鲜度判定）