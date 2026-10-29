> **拆分归档**：本文件拆分自同目录索引页 [review-implemention-records.md](review-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。

### 板块六：Sweeper 基建 + quarantine 清理（§4.3 / §4.2.2，2026-10-12 全量完成，自 next_update.md 转移；即原「暂不实现」quarantine sweeper 条目，触发条件本板块达成）

> 2026-10-12 落地注记：板块六四项全量完成——ReviewTaskSweeper 四职责合并（6-1 认领超时回池/申诉超时维持/积压 Gauge 三态+DLQ 深度 + 6-2 status=0 超时补偿重发，同任务内分频执行）；QuarantineSweeper（6-3 扫描源=moderation_record+最新态过滤+申诉豁免+file 侧 deleteQuarantined 前缀校验契约+限速天级+双标签 Gauge）；ModerationRecordArchiveSweeper（6-4 原计划占位、实际全量实现：moderation_record_archive 归档表 DDL + 原子迁档事务 + 引用保护 + backlog Gauge，archive-enabled 默认 false 待显式启用）；明细见 status.md 板块六。

- [x] ReviewTaskSweeper：认领超时自动回池（claim.timeout-minutes）+ 申诉超时自动维持（appeal.timeout-days，宁严勿松）+ 积压 Gauge 定时刷新（§4.3/§4.2.3）（✅ 2026-10-12）
- [x] status=0 待审超时补偿重发：阈值 > MQ 最大退避总时长 + 重发前先抢 dedup.lock + 沿用 retry\_count 不归零（§4.3）（✅ 2026-10-12）
- [x] quarantine sweeper：扫 record(biz\_type=6) verdict=REJECT 超 retention-days → 反查 appeal(status=0) 待裁决则顺延 → FileClient.deleteQuarantined（file 侧前缀校验 + 删缩略图 + del\_flag=1 + 回收站兑底）→ fail-fast 幂等 + 批量限速 + 天级频率（§4.2.2）（✅ 2026-10-12）
- [x] moderation\_record 归档任务：retention-months=6 配置先行；归档 sweeper 已按契约全量实现（原计划二期，提前落地），archive-enabled=false 默认关闭（§4.4.1）（✅ 2026-10-12）

#### status.md 子项级进度勾选（2026-10-13 自 status.md 转移，去重后补录：父项与上方清单重复不迁，仅迁子项；板块六子项全部已实现无挂起）

- [x] 6-1.1 认领超时回池：status=2 超 claim.timeout-minutes→CAS 置回 0 清 assignee
- [x] 6-1.2 申诉超时自动维持：7 天未裁决置 1 + record(mod_type=6) + 通知作者
- [x] 6-1.3 积压 Gauge 三态全量刷新（pending/claimed/suspicious）+ DLQ 深度
- [x] 6-2.1 扫 status=0 超时任务（阈值必须 > MQ 退避总时长防系统性双投）
- [x] 6-2.2 重投前抢 dedup SETNX 短锁 + retryCount 保持原值不归零
- [x] 6-3.1 扫描源=moderation_record（biz_type=6 且 REJECT 超 retention-days）+ 待裁决申诉豁免顺延
- [x] 6-3.2 FileClient.deleteQuarantined：path 前缀校验→删本体+缩略图→del_flag=1（回收站兑底 7 天）
- [x] 6-3.3 限速/天级分频/单条失败下轮兑住 + quarantine 积压 Gauge；落地后勾「暂不实现」节条目
- [x] 6-4.1 落配置 retention-months=6 + 归档任务占位（二期：按 id 区间幂等迁移，删前跳过被申诉/处罚引用记录）（✅ 含归档表 DDL/实体/Mapper/原子迁档事务全量实现）

### 板块六实施细案步骤（2026-10-13 自 next_update.md 转移）

#### 6-1 ReviewTaskSweeper（§4.3/§4.2.3）

**步骤**（@Scheduled，间隔 sweeper.interval-seconds=60 外置，三职责合并）：
1. **认领超时回池**：扫 status=2 且 claim_time 早于 now-claim.timeout-minutes(30)→CAS 置回 status=0 清 assignee（decide 的 CAS 条件天然拦截回池后原审核员裁决）。
2. **申诉超时自动维持**：扫 appeal.status=0 且 create_time 早于 now-appeal.timeout-days(7)→置 status=1 + record(mod_type=6，裁决=超时维持) + 通知作者（宁严勿松，防内容/处罚态悬空）。
3. **积压 Gauge 定时刷新**：moderation_backlog{status=pending|claimed|suspicious} **三态全量**——只统计 status=2 会漏「已认领未裁决」与「status=0 卡死」两态。

#### 6-2 status=0 待审超时补偿重发（§4.3）

**步骤**：
1. 同任务内分频执行：扫 status=0 且 create_time（或最近重置时间）早于 sweeper.stuck-resubmit-minutes(30) 的任务——**阈值必须 > MQ 最大退避总时长**（retry.max-count 次指数退避累加，按实际参数计算），否则对仍在延迟重试中的消息系统性双投。
2. 重发前抢 dedup:{provider}:{contentHash} SETNX 锁，失败→跳过本轮（原消息在途）。
3. 重投 submit 事件消息头 retryCount=review_task.retry_count 原值（**不归零**，防已耗尽重试的任务被重发又拉起整轮重试）。

**注意事项**：双投由 3-1 步骤 4 的 CAS `where status=0` 去重（affected=0 直接 ACK），代价仅多一次模型调用、result 事件幂等；只扫 status=0，与 DLQ 转人审（置 2）天然互斥。

#### 6-3 quarantine sweeper（§4.2.2）

**步骤**：
1. **扫描源=moderation_record（非 review_task）**：biz_type=6 且 verdict=2-REJECT 且 create_time 早于 now-quarantine.retention-days(10)→fileId 集合——record 留痕表口径比 review_task 状态机更稳（task 会被复审/申诉改写），以 record 落库时间为基准只会顺延删除、宁晚勿早。
2. 删除前反查 appeal：该图（biz_type=6+biz_id=fileId）存在 status=0 待裁决申诉→本轮跳过顺延（retention 10>申诉窗口 7 仅是缓冲，兜不住「窗口末期申诉、缓冲期满未裁决」，须按申诉态动态豁免）。
3. 执行：FileClient.deleteQuarantined(fileId)（**review 无存储写凭证**，s3.json 三身份拆分纪律）→file 侧校验 path 确在 quarantine/ 前缀内→删本体+_thumb_* 缩略图→del_flag=1→SeaweedFS 回收站兑底 7 天。
4. 纪律：fail-fast 单条失败留 error 日志由下轮兜住（文件已删则前缀校验/不存在跳过，天然幂等）；批量限速（每秒 N 条可配）；天级频率（任务内部分频，60s 粒度无意义）。
5. 观测：quarantine 积压 Gauge(biz_type 标签)+清理 Counter；**落地后回填「暂不实现」节 quarantine sweeper 条目勾选**。（✅ 2026-10-12 落地：QuarantineSweeper 落位 sweeper 包，原文步骤 5 的「暂不实现」条目已回填勾选）

**注意事项**：file 侧 FileReconcileTask 已对 quarantine/ 前缀跳过自身 7 天清理（已落地），本任务是 quarantine/ 前缀唯一删除责任方，不得重复造删除入口。

#### 6-4 moderation_record 归档占位（§4.4.1）

**步骤**：仅落配置 litisle.moderation.record.retention-months=6（《网络安全法》≥6 月合规下限），归档任务二期：按 create_time 迁只读历史存储后删热表行；删前跳过被 appeal.status=0 或 punish_log.status=1 引用的记录；按 id 区间幂等可重跑；应用账号仅授 INSERT/SELECT。（✅ 2026-10-12 落地：原计划二期，实际全量提前落地，archive-enabled=false 默认关闭，明细见上方清单落地注记）

