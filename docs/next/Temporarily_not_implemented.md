
# 审核模块 · 暂不实现清单（Temporarily Not Implemented）

> 用途：收纳审核模块板块三/板块四中因宿主模块未开发而挂起的未完成项，以及审核模块预留项（暂不实现/暂不做）。

> 转移日期：2026-10-11（自 status.md / next_update.md 转移）；已实现部分见 `docs/archive/review-implemention-records.md`。

## 一、板块三挂起项（触发条件：litisle-content / litisle-social 正式开发时随实体层落地）

### 3-5 业务方回写矩阵消费端（content / social）（§4.5.2）

**落位**：litisle-content 与 litisle-social 的 mq consumer 包（各自订阅对应 result 队列）。

**步骤（content 侧）**：

1. 消费 moderation.text.result / moderation.image.result，按 bizType 分发：1→article.status、2→comment.status；CAS 条件更新重放无副作用。

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

### 3-7.2 发布/上传入口 @RateLimiter(USER)（§2.6）

挂起：发布入口在 content（空壳）、上传入口在 file（随 file 联动），触发条件同 3-5。

### 3-7.3 单内容在途 SETNX 短锁（入口侧预检）（§2.6）

消费端已随 3-1.9 落地 ModerationProperties.Dedup（dedup.lock-seconds=60s）；入口侧预检随 3-5 挂起，可复用同 key 口径 `dedup:{provider}:{contentHash}`。

## 二、板块四挂起项（契约层已全部落地 litisle-api；以下仅剩入口嵌入，触发条件同 3-5；细案步骤见 docs/archive/review-implemention-records.md 板块四实施细案）

### 4-1 发布前置校验嵌入（发布链路）
文章/评论发布接口嵌入 PublishTextPrecheck.check + SubmitTextAssembler.assemble/contentHash；空内容校验必须在 hash 计算与送审之前。

### 4-2.1 文章/评论/私信链路注入 DfaFacade（USERNAME 链路已随 A 组在 auth 落地）
HIGH 拒 70001 / MID 标记放行；scene 传原始值由 DfaScenes 归一。

### 4-2.2 私信 HIGH 同步拒发且不投递 MQ
仅 MID/LLM 走先发后审。

### 4-3.1 content 消费 result 写 last-hash
TTL=last-hash.ttl-hours(72)±10% 抖动，与 cache.ttl-hours 对齐（判定契约 LastHashStore.write 已实现）。

### 4-3.2 发布三分支判定嵌入
PASS 免审直落并置 review_task 终态 / REJECT 前置提示 / 键缺失照常发 MQ（判定契约 LastHashExemptChecker.decide 已实现，发布编排嵌入待 content）。

### 4-4 删除/下架/转私密三路径嵌入
三路径发 content.unpublished 事件并失效 last-hash + text/img-result 缓存键（事件与 ContentUnpublishedInvalidator 契约已备）。

### 4-5.1/4-5.2 POST /api/content/preview-check 端点实现
复用 DfaFacade 同 scene 同词库版本；返回仅 {hit,risk} 禁回吐命中词 + 本人草稿鉴权 + @RateLimiter(USER)。

### 4-6 私信撤回端点与发送链路嵌入（social 私信域）
MessageRecallGate.recall / StrangerQuotaGate.tryAcquire 契约已备；发送链路嵌入配额与 DFA 闸门。

## 三、审核模块预留项（暂不实现/暂不做，触发条件随附）

- [x] ~~review 侧 quarantine sweeper~~（✅ 2026-10-12 已随板块六 6-3 落地实现（QuarantineSweeper，sweeper 包），从预留项移除；落地明细见 docs/archive/review-implemention-records.md 板块六——设计口径：扫描超期 REJECT 图 → 按图反查 appeal（status=0 待裁决顺延）→ FileClient.deleteQuarantined 经 file 侧前缀校验后物理删除（review 无存储写凭证）。原触发条件（review 侧 sweeper 定时任务基建，板块六 6-1）已达成并关闭）

- [ ] litisle-ai 侧 chat 端点（POST /api/feign/ai/chat，OpenAI 兼容转发）——契约层已就绪（AiClient + AiChatRequestDTO/AiChatResponseDTO + AiClientFallbackFactory fail-fast + 自动装配注册，2026-10-06）；本端点暂不实现（用户决策：LLM 审核留注释占位，AiClient Javadoc 已标 TODO）。base-url/api-key/model 走 Nacos 外置，禁提交密钥；超时对齐 feign.client.config.litisle-ai.read-timeout。触发条件：板块三 3-1 联调前，或 AI 侧 RAG 建设启动时一并落地。

- [ ] 云 API 生产适配器：生产切换时（pom runtime scope + Nacos 配置 AK/SK 走环境变量）

- [ ] 图文组合审核 / OCR 多模态盲区（§2.9）：二期；此前由人审详情页「图文并排核对」兜底

- [ ] 撤销改判公示：二期，仅公示脱敏摘要，严禁回显 appeal.content/appellant_id/reporter_id（§4.2.3）

- [ ] 视频/音频审核：一期显式不支持，业务出现前须先补 SPI 方法与适配器

- [ ] CDN 接入——public-url-base 已分离，届时仅改配置（file 侧预留项，随审核图片外链需求一并评估）

## 四、板块五挂起项（触发条件：litisle-content / litisle-social 正式开发时回接；2026-10-13 自 status.md 板块五转移）

### 5-4.4 content/file/message allow-list CAS 恢复（article 3→2/4→2、comment 2→1、message 2→1、safe_flag 0→1）（⏳ 下游消费侧职责：review 侧仅发 appeal.result 触发信号已落地；file 侧 safe_flag 0→1+unquarantine 已随基线落地，content/message 随对应模块开发回接）

## 五、review 模块骨架待实现项（2026-10-13 自代码扫描补录：逐方法 TODO 占位，方法体仅抛 UnsupportedOperationException）

> 全部为 litisle-review 自身 service/controller 骨架（实现责任方均属 review 模块，不涉及 api/auth/user/gateway/file 模块代办）；各条目实现口径以代码内步骤注释为准，逐字保留。

### ReviewTaskServiceImpl（人审任务链路，4 方法）

- listQueue：REVIEWER/ADMIN 校验 + layer2_risk 降序 + 认领配额过滤
- getTask：REVIEWER/ADMIN 校验 + 证据图经 FileClient 批量取 URL
- claimTask：CAS where id=? and status=2 and assignee is null；affected=0 抛 70004
- decideTask：终态 CAS + REJECT 联动（下架事件/处罚落行/违规分累加/举报回写）+ 高风险 confirm 双签

### ReportServiceImpl（举报链路，3 方法）

- submitReport：Step1 防自举报 → Step2 活跃去重 → Step3 落 report+证据 → Step4 DFA 初审分流
- withdrawReport：CAS where reporter_id=? and status<3 置 4-已撤回；affected=0 抛错
- getInternalReport：内部契约查询（举报详情回查）

### AppealServiceImpl（申诉链路，1 方法）

- submitAppeal：归属校验 → 双层防重（关联列级+内容级）→ 落 appeal（bizType/bizId 解析固化）（⏳ 注：同类的 judgeAppeal 裁决侧已随板块五 5-4 落地，仅提交侧待实现）

### PunishServiceImpl（处罚链路，2 方法）

- listMyPunish：仅本人；status 全量展示，reason UGC 回显由前端 sanitize
- getActivePunishes：活跃处罚查询（内部契约）

### WordlibServiceImpl（词库链路，1 方法）

- deleteWordByContent：scene+word 精确匹配定位 → 不存在抛 WORD_NOT_FOUND → 物理删 + version+1 同事务 + Pub（广播）

### ReportController（1 端点）

- GET /report/my：我的举报列表（头注释标注 TODO 待实现）
