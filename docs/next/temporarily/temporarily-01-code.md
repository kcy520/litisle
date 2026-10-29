> **拆分归档**：本文件拆分自同目录索引页 [Temporarily_not_implemented.md](Temporarily_not_implemented.md)，原单文件对应部分原样搬运（2026-10-28 拆分，内容零修改），各部分入口见索引页。

## 代码部分

> **通用纪律（所有条目默认适用，不再逐条重复）**：
> ① SQL 全走 MyBatis #{} 绑定禁拼接；排序/筛选列名服务端白名单枚举映射；LIKE 对 %/_/\ 转义。
> ② Redis key 各段服务端派生（枚举名/数字 ID/SHA256），禁拼用户原串；value 一律 JSON，禁 Java 原生反序列化。
> ③ UGC/traceId 落日志前剥离 \r\n（防 CRLF 伪造日志行）。
> ④ 指标埋点 ObjectProvider<MeterRegistry> optional 注入，未接入不影响核心功能。
> ⑤ review 不跨库直写业务表，业务态回写一律 result 事件 + 业务方 CAS；跨库回查走 litisle-api 契约，fallbackFactory fail-fast（回查失败不 ACK 进延迟重试，禁静默吞错）。
> ⑥ 缓存/黑名单/last-hash TTL 一律 ±10% 抖动（防同批内容集体过期雪崩）。

### review 审核模块

> 现状基线：file 侧 safe_flag 四态闸口/拒传黑名单/quarantine 契约/result·appeal.result 消费回写/getContent/占位图/对账跳隔离前缀；litisle-api 契约层（MqConstant/ModerationConstant/事件体/FileClient）；review 侧人审域（claim/decide CAS、申诉防重三层、双人复核、举报撤回/诬告反制、词库表）已全部完成。

> 推进顺序：3-1→3-2→3-3→3-4→3-5→3-6→3-7→3-8 → 板块四（与 3-4/3-5 联调）→ 板块五（复用 result 事件）→ 板块六 → 板块七随三收尾并行埋点。

> ✅ 已完成记录：review 侧 quarantine sweeper 已随板块六 6-3 落地（2026-10-12，QuarantineSweeper，sweeper 包），原预留项关闭。

#### ReviewTaskServiceImpl（人审任务链路，4 方法）

- listQueue：REVIEWER/ADMIN 校验 + layer2_risk 降序 + 认领配额过滤
- getTask：REVIEWER/ADMIN 校验 + 证据图经 FileClient 批量取 URL
- claimTask：CAS where id=? and status=2 and assignee is null；affected=0 抛 70004
- decideTask：终态 CAS + REJECT 联动（下架事件/处罚落行/违规分累加/举报回写）+ 高风险 confirm 双签

#### ReportServiceImpl（举报链路，3 方法）

- submitReport：Step1 防自举报 → Step2 活跃去重 → Step3 落 report+证据 → Step4 DFA 初审分流
- withdrawReport：CAS where reporter_id=? and status<3 置 4-已撤回；affected=0 抛错
- getInternalReport：内部契约查询（举报详情回查）

#### AppealServiceImpl（申诉链路，1 方法）

- submitAppeal：归属校验 → 双层防重（关联列级+内容级）→ 落 appeal（bizType/bizId 解析固化）（⏳ 注：同类的 judgeAppeal 裁决侧已随板块五 5-4 落地，仅提交侧待实现）

#### PunishServiceImpl（处罚链路，2 方法）

- listMyPunish：仅本人；status 全量展示，reason UGC 回显由前端 sanitize
- getActivePunishes：活跃处罚查询（内部契约）

#### WordlibServiceImpl（词库链路，1 方法）

- deleteWordByContent：scene+word 精确匹配定位 → 不存在抛 WORD_NOT_FOUND → 物理删 + version+1 同事务 + Pub（广播）

#### ReportController（1 端点）

- GET /report/my：我的举报列表（头注释标注 TODO 待实现）

#### 3-5 业务方回写矩阵消费端（content / social）（§4.5.2）

> ✅ content 侧已落地（2026-10-17，随 review 模块推进回收），social 侧继续挂起（评论区/私信随 social 模块开发接线）。

**落位**：litisle-content 与 litisle-social 的 mq consumer 包（各自订阅对应 result 队列）。触发条件：content / social 正式开发时随实体层落地。

**步骤（content 侧）**：

1. 消费 moderation.text.result / moderation.image.result，按 bizType 分发：1→article.status、2→comment.status；CAS 条件更新重放无副作用。

   > ⚠️ 口径批注（2026-10-17，详见文末「content 侧落地记录」第 2 条）：实际 content 侧仅订阅 moderation.text.result 单队列——配图复审 result（scene=ARTICLE_IMG_RE_REVIEW）bizType 仍=1 复用文章任务走文本路由键，同队列到达按 scene 分流聚合；moderation.image.result（bizType=6 单图）归 file 侧消费，content 不订阅。

2. **文章双链路联合聚合**：放行(1→2)条件=文本 PASS 且（无配图 或 图片复审 result=PASS）；任一 REJECT→整文驳回。实现：两路 result 到达时先落本地聚合态（Redis 短暂暂存或回查 review_task 两行现态），聚合判定后再 CAS article。

3. REJECT 分流：首发驳回 1→3；已发布被复审下架 2→4（配套 punish_log action=3 由 review 侧 5-1 落）。

4. **评论 CAS**：PASS 0→1 / REJECT 0→2，条件 `where id=? and status=0 and del_flag=0`；**禁 2→1**（改判只认申诉来源，见 5-4）。

5. **空/纯图边界**：正文去 HTML 标签后可见文本为空且含配图→文本链路 trivially PASS（仅看图片复审）；正文与配图同时为空→拒。

6. 所有 CAS 补 del_flag=0（用户复审期间自删内容防误改）；CAS affected=0 静默丢弃 ACK 不重试（并发/已删是正常终局）。

**步骤（social 侧）**：

7. 消费 moderation.message.result（私信业务域路由键，消费端为 social）：REJECT/SUSPICIOUS→CAS `update message set status=2 where id=? and status=1`（风控拦截不可见）；人审 PASS→1 恢复（含 appeal.result 2→1，5-4）。

**注意事项**：评论驳回态=2 非 3，禁套文章口径；文章**禁 3→2**（驳回态不得被任何机审/人审 PASS 覆盖）；文本/图片两路 result 并发到达，驳回先落则 PASS 不得覆盖（CAS 白名单天然拦截）。

**message→social 口径修正说明（2026-10-11）**：

1. 已实现部分零代码改动：3-1 ModerationTextConsumer 依赖的 MessageClient 契约、3-4 result 事件发布的 moderation.message.result 路由键与 MESSAGE 场景枚举均属私信业务域命名，保留原名。

2. 未实现部分实现时直接按 social 归属落地，无返工。

3. 未来唯一代码触点：social 实现私信域 Provider 时，需对齐 MessageClient 的 Feign 服务名（硬编码在 @FeignClient 注解，仅需改 MessageClient.java 一处）。

**content 侧落地记录（2026-10-17）**：

1. 落位文件：ContentRabbitConfig（队列拓扑：2 主队列+2 DLQ+业务/延迟/死信三向绑定，声明幂等参数与 review 侧对齐）；ModerationResultConsumer / ModerationAppealResultConsumer（消费编排：traceId 恢复+MqAckHelper ACK 纪律+延迟重投，耗尽入 DLQ）；ArticleReviewWriteBackServiceImpl（业务：聚合判定/CAS 白名单/last-hash 写删）。

2. 消费拓扑口径澄清：清单原步骤 1 写「消费 moderation.text.result / moderation.image.result」，实际实现为 content 侧仅消费 moderation.text.result 单队列——文章配图复审 result（scene=ARTICLE_IMG_RE_REVIEW）bizType 仍=1 复用文章任务，发送侧走 ROUTING_MODERATION_TEXT_RESULT 同队列到达，按 scene 分流聚合；moderation.image.result（bizType=6 单图）归 file 侧消费，content 不订阅。

3. 双链路聚合实现选型：Redis Hash 槽暂存（key=content:article:review-agg:{articleId}，field text/img → verdict，TTL 2h），REJECT 到达即驳回不等另一路；有无配图以 article_image 表现状为准（防事件与落库竞态误判无图直放）；空/纯图 trivially PASS 边界由发布侧 A2 段保证（正文可见文本为空且含配图时不发文本送审，仅走配图复审），消费端无需重判。

4. last-hash 写入时机口径：跟随 result 事件到达写入（文本主键指纹=落库指纹已过陈旧性比对；图片连带键指纹=事件体聚合指纹），非聚合判定后写入——避免图片路触发聚合时拿不到文本指纹的数据依赖。

5. 评论 bizType=2 / 私信 bizType=5：消费端 bizType 分流已预置（留痕丢弃防毒丸），评论/私信实体落地后替换为对应 CAS 回写即可。

6. 遗留关联：板块八 [8.2] 封禁连带消费待 user.banned 事件契约就绪后落地。

#### 3-7.2 发布/上传入口 @RateLimiter(USER)（§2.6）

挂起：发布入口在 content（空壳）、上传入口在 file（随 file 联动），触发条件同 3-5。

#### 3-7.3 单内容在途 SETNX 短锁（入口侧预检）（§2.6）

消费端已随 3-1.9 落地 ModerationProperties.Dedup（dedup.lock-seconds=60s）；入口侧预检随 3-5 挂起，可复用同 key 口径 `dedup:{provider}:{contentHash}`。

#### 4-1 发布前置校验嵌入（发布链路）

文章/评论发布接口嵌入 PublishTextPrecheck.check + SubmitTextAssembler.assemble/contentHash；空内容校验必须在 hash 计算与送审之前。

#### 4-2.1 文章/评论/私信链路注入 DfaFacade（USERNAME 链路已随 A 组在 auth 落地）

HIGH 拒 70001 / MID 标记放行；scene 传原始值由 DfaScenes 归一。

#### 4-2.2 私信 HIGH 同步拒发且不投递 MQ

仅 MID/LLM 走先发后审。

#### 4-3.1 content 消费 result 写 last-hash（✅ 已回收：随方向四修复登记进 status.md 板块八 8.1）

TTL=last-hash.ttl-hours(72)±10% 抖动，与 cache.ttl-hours 对齐（判定契约 LastHashStore.write 已实现）。

#### 4-3.2 发布三分支判定嵌入

PASS 免审直落并置 review_task 终态 / REJECT 前置提示 / 键缺失照常发 MQ（判定契约 LastHashExemptChecker.decide 已实现，发布编排嵌入待 content）。

#### 4-4 删除/下架/转私密三路径嵌入

三路径发 content.unpublished 事件并失效 last-hash + text/img-result 缓存键（事件与 ContentUnpublishedInvalidator 契约已备）。

#### 4-5.1/4-5.2 POST /api/content/preview-check 端点实现

复用 DfaFacade 同 scene 同词库版本；返回仅 {hit,risk} 禁回吐命中词 + 本人草稿鉴权 + @RateLimiter(USER)。

#### 4-6 私信撤回端点与发送链路嵌入（social 私信域）

MessageRecallGate.recall / StrangerQuotaGate.tryAcquire 契约已备；发送链路嵌入配额与 DFA 闸门。

#### 5-4.4 content/file/message allow-list CAS 恢复（article 3→2/4→2、comment 2→1、message 2→1、safe_flag 0→1）

⏳ 下游消费侧职责：review 侧仅发 appeal.result 触发信号已落地；file 侧 safe_flag 0→1+unquarantine 已随基线落地，content/message 随对应模块开发回接。触发条件：content / social 正式开发时。

> 板块三/四挂起项说明：契约层已全部落地 litisle-api；以上仅剩入口嵌入，触发条件同 3-5；细案步骤见 `docs/archive/review/review-04-publish-auth.md` 板块四实施细案。

### litisle-ai 模块

#### chat 端点（POST /api/feign/ai/chat，OpenAI 兼容转发）

契约层已就绪（AiClient + AiChatRequestDTO/AiChatResponseDTO + AiClientFallbackFactory fail-fast + 自动装配注册，2026-10-06）；本端点暂不实现（用户决策：LLM 审核留注释占位，AiClient Javadoc 已标 TODO）。base-url/api-key/model 走 Nacos 外置，禁提交密钥；超时对齐 feign.client.config.litisle-ai.read-timeout。触发条件：板块三 3-1 联调前，或 AI 侧 RAG 建设启动时一并落地。
