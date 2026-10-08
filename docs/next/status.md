
# 审核模块 · 进度看板（status）

> 用途：一眼看进度。**本文件只放勾选状态，不写步骤细节**。

> 详细步骤见同目录 `spec-*.md`；细案原件 `next_update.md`（只读，勿改）。

> 维护纪律：每条落地后回填 `[x]` 与日期。

## 当前执行锚点

- **进行中**：板块三 **3-1 ModerationTextConsumer**（细案见 `spec-3-1.md`）

- **前置阻塞**：litisle-ai chat 端点（`POST /api/feign/ai/chat`）未实现——3-1 联调前必须落地

- **推进顺序**：3-1→3-2→3-3→3-4→3-5→3-6→3-7→3-8 → 板块四 → 板块五 → 板块六 → 板块七（随三并行埋点）

## 现状基线（已完成，不再列入）

file 侧 safe_flag 四态闸口 / 拒传黑名单 / quarantine 契约 / result·appeal.result 消费回写 / getContent / 占位图 / 对账跳隔离前缀；litisle-api 契约层（MqConstant / ModerationConstant / 事件体 / FileClient）；review 侧人审域（claim·decide CAS、申诉防重三层、双人复核、举报撤回·诬告反制、词库表）。

## 板块一：机审 SPI 域（§4.3 / §4.5.1）

- [x] ContentModerationService 门面 + ModerationResult

- [x] LlmTextModerationAdapter（2026-10-06，编译态完成；联调前置 = AI chat 端点）

- [x] OnnxImageModerationAdapter（2026-10-06）

- [x] provider 启动自检 fail-fast（ModerationProviderStartupChecker）

- [x] 配置外置首批（ModerationProperties）

- [ ] （二期）AliyunImageModerationAdapter

## 板块二：DFA 同步闸门共享化 + 词库运营

- [x] DfaFacade + WordLibraryLoader 下沉 litisle-api（2026-10-06）

- [x] review 侧 TableWordLibraryLoader + DfaFacade Bean 装配（2026-10-07）

- [x] 词库版本单一来源 sensitive_word_version（2026-10-07）

- [x] 灰度：新增词默认 MID + 误报上报入口 + HIGH→MID 降级（2026-10-07）

- [x] 导入导出安全（2026-10-07）

- [x] 词库页权限收口仅 ADMIN（2026-10-07）

- [ ] 禁用默认词库，并从默认词库导入部分敏感词

## 板块三：MQ 异步机审链路（§4.2.1/§4.2.2/§4.5.2/§4.2.4）

- [ ] 3-1 ModerationTextConsumer

- [ ] 3-2 ModerationImageConsumer

- [ ] 3-3 统一建任务 upsert（ReviewTaskUpsertService）

- [ ] 3-4 result 事件发布

- [ ] 3-5 业务方回写矩阵消费端（content / message）

- [ ] 3-6 ModerationDlqConsumer

- [ ] 3-7 成本护栏

- [ ] 3-8 故障降级

## 板块四：发布侧文本链路接入（§4.2.1 / §4.5.3）

- [ ] 4-1 发布前置校验

- [ ] 4-2 同步 DFA 闸门嵌入

- [ ] 4-3 免审 last-hash

- [ ] 4-4 content.unpublished 同步失效

- [ ] 4-5 POST /api/content/preview-check

- [ ] 4-6 私信撤回 CAS + 陌生人配额

- [ ] 4-7 USERNAME 同步拒绝

## 板块五：申诉与处罚联动闭环（§4.2.3 / §4.5.2）

- [ ] 5-1 机审 REJECT 联动处罚

- [ ] 5-2 moderation.punish.result 事件 + user/auth 消费

- [ ] 5-3 punish.result 串行消费 + 双向对账 sweeper

- [ ] 5-4 申诉改判 PASS

- [ ] 5-5 图片申诉对称恢复

## 板块六：Sweeper 基建 + quarantine 清理（§4.3 / §4.2.2）

- [ ] 6-1 ReviewTaskSweeper

- [ ] 6-2 status=0 待审超时补偿重发

- [ ] 6-3 quarantine sweeper

- [ ] 6-4 moderation_record 归档占位

## 板块七：观测与告警收尾（§4.8 / §4.7）

- [ ] 7-1 指标埋点

- [ ] 7-2 traceId 全链路贯穿

- [ ] 7-3 Alertmanager 六规则

- [ ] 7-4 §4.7 剩余配置项收尾

## 暂不实现（预留项）

- [x] safe_flag 审核链路（2026-10-02 接入完成）

- [ ] CDN 接入——public-url-base 已分离，届时仅改配置

- [ ] common-redis 库模块不引 Sentinel（库模块当前不动）

- [ ] review 侧 quarantine sweeper——触发条件 = 板块六达成

- [ ] litisle-ai chat 端点——触发条件 = 板块三 3-1 联调前

## 审核模块暂不做（触发条件随附）

- 云 API 生产适配器（生产切换时）

- 图文组合审核 / OCR 多模态盲区（二期，人审详情页图文并排兜底）

- 撤销改判公示（二期，仅脱敏摘要）

- 视频 / 音频审核（一期显式不支持）
