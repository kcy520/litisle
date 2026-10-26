# 待办与暂不实现清单（合并版）

> 用途：收纳全项目待办事项，按「类别 → 模块 → 任务」三级组织。

> 合并来源：`docs/temp/temp.md`（原审核模块暂不实现清单）+ `docs/next/next_update.md` 全部待办，已去重整理。

> 整理日期：2026-10-17；已实现部分归档见 `docs/archive/review-implemention-records.md`、`docs/archive/20260929-implementation-records.md`。

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

> 板块三/四挂起项说明：契约层已全部落地 litisle-api；以上仅剩入口嵌入，触发条件同 3-5；细案步骤见 `docs/archive/review-implemention-records.md` 板块四实施细案。

### litisle-ai 模块

#### chat 端点（POST /api/feign/ai/chat，OpenAI 兼容转发）

契约层已就绪（AiClient + AiChatRequestDTO/AiChatResponseDTO + AiClientFallbackFactory fail-fast + 自动装配注册，2026-10-06）；本端点暂不实现（用户决策：LLM 审核留注释占位，AiClient Javadoc 已标 TODO）。base-url/api-key/model 走 Nacos 外置，禁提交密钥；超时对齐 feign.client.config.litisle-ai.read-timeout。触发条件：板块三 3-1 联调前，或 AI 侧 RAG 建设启动时一并落地。

## 验证部分

### 网关 / 认证模块（限流故障降级本地兜底，已实现 2026-09-28）

#### 停 Redis 一次合并验证

受限接口应正常放行且出现「进入降级」日志，第二次起走本地计数；同时观察登录防爆破「本地兜底降级」日志；Redis 恢复后冷却期探测成功，日志出现「恢复」回归 Redis 计数。（可同步用 Prometheus 观察：redis_guard_degraded 0→1→0，Alertmanager 告警 inactive→pending→firing→resolved，一次演练同时验收告警链路。）

> 设计思路 / 实现细节 / 完成记录见 `docs/archive/20260929-implementation-records.md` §一。

### Sentinel（业务模块限流熔断）

#### Dashboard 手动触发降级 / 熔断日志观察 + Nacos 规则热生效确认

在 Dashboard 手动触发降级 / 观察熔断日志；确认 Dashboard 改 Nacos 规则热生效。

## 部署部分（生产上线前必做）

### 通用（全服务）

#### 监控端口网络隔离（监控链路安全前提）

各服务 8xxx 端口的 /actuator/** 免登录放行的是「路径」而非「来源 IP」，生产环境必须通过防火墙/安全组确保业务端口仅对内网开放（Prometheus 所在网段可达即可，公网不可达）；或升级为 management.server.port 独立管理端口 + 网络层仅放行 Prometheus IP 段（与网关 8081 方案同一套纪律）。开发环境单机直连现状合规，无需处理。

#### Sentinel 规则操作纪律

Sentinel Dashboard 改规则只推内存、重启丢——操作纪律：规则增删改一律去 Nacos 控制台。

### file 文件模块

#### replication ≥ 010 + Volume 多机

用户上传原件不可再生，单副本（replication=001）不达生产标准；⚠️ 副本策略只影响新写入 volume，存量不自动补副本，须在首次生产写入前定稿。

#### Filer 元数据切 MySQL

当前 leveldb 与数据同盘，盘坏 = 目录全丢（比丢文件更致命）；复用已有 MySQL 实例。开发期 leveldb 无所谓。

#### instance-count 动态化（触发条件：file 服务多实例部署上线时）

接 Nacos 服务发现自动计算实例数（扩缩容免人工同步）。Micrometer 指标埋点与暴露已于 2026-09-29 落地并验证通过（详见 commom-des.md §3.5），剩余 Prometheus/Alertmanager 为部署侧工作，零代码改动。

## 暂不实现（预留项，附触发条件）

### review 审核模块

#### 云 API 生产适配器

生产切换时落地（pom runtime scope + Nacos 配置 AK/SK 走环境变量）。

#### 图文组合审核 / OCR 多模态盲区（§2.9）

二期；此前由人审详情页「图文并排核对」兜底。

#### 撤销改判公示（§4.2.3）

二期，仅公示脱敏摘要，严禁回显 appeal.content/appellant_id/reporter_id。

#### 视频 / 音频审核

一期显式不支持，业务出现前须先补 SPI 方法与适配器。

### content 内容模块

#### 文章分类：Book 链路枚举改造待确认（迁自 docs/temp/temp.md，2026-10-23 归档）

Book 分类集合是否与文章一致（DB default '小说'）？一致 → 复用 ArticleCategoryEnum；不一致 → 新建 BookCategoryEnum 后再改 BookSaveDTO/Book/BookDetailVO/BookServiceImpl。阻塞 Book 链路，不阻塞文章链路（已枚举化落地，见归档板块九）。触发条件：Book 链路改造时。

#### 方案B 软删重发互动数据悬空兑底（迁自 temp.md 阶段7）

已发布文章改正文=软删旧文+重发新文（新 articleId），点赞/评论挂旧 articleId → 重发后旧互动悬空。默认口径「接受丢失」（正文已变，旧互动语义失效）；若产品要求元数据级编辑保留互动，再启用 social 侧迁移（旧→新 articleId 批量改绑）。触发条件：方案B 重发事件（如 content.replaced）落地 / social 立项时。

#### social 点赞/评论随文隐藏与悬空兑底（迁自 temp.md 阶段7，设计意图固化）

①social 订阅 content.unpublished（reason=1），文章维度点赞/评论树置「随文隐藏」（软删标记不物理删，法务取证对齐），列表按隐藏状态过滤；②点赞列表/评论入口反查 content 详情，ARTICLE_NOT_FOUND 时静默过滤（双保险：即使事件丢失/乱序也不感知悬空）；③ContentUnpublishedEvent.bizType=2 评论扩展位复用同一事件口径，评论软删时其下点赞连带隐藏。触发条件：social 模块立项随设计文档固化。

#### social 回写计数对已删文章吞 ARTICLE_NOT_FOUND（迁自 temp.md 阶段8④）

updateCounters 带 del_flag=0 条件 rows=0 抛错；约定 social 回写失败时对 ErrorCode=ARTICLE_NOT_FOUND 记 info 后视为成功（文章已软删计数无意义），其余错误走重试/DLQ。触发条件：social 回写落地时。

#### FeedItemVO.commentCnt 填充（迁自 temp.md 阶段8③）

字段已补（对齐 Card/Detail 三计数口径），填充值未接。触发条件：social 点赞/评论落地后 feed 聚合链路补充。

#### Book.coverFileId 封面引用管理同病（迁自 temp.md 阶段8②）

文章封面已并入阶段6方案C 引用管理（COVER bizType，发布/编辑/软删/草稿清理全链路 bind/unbind），Book.coverFileId 未接入。触发条件：随「Book 分类确认」一并处理。



#### visibility=3 取关失效联动（方向四 P1-B）

social 取关后 content 侧关注回查短 TTL 缓存（≤60s）内仍判关注者放行正文，属可接受弱一致性（status.md 4.1 已定口径）；follow.changed 失效事件联动留二期。触发条件：social 模块开发时评估事件联动收益与成本。

#### 生产端事件发送失败重投载体（方向六 P2-B）

afterCommit 发事件失败时通用纪律仅定"失败重投"口径，实现载体未定（本地消息表 vs 定时补偿扫描卡 status=1 超时文章重发 submitted）——不定则 MQ 抖动窗口内文章永久卡待审无人消费（review sweeper 只管 status=0 任务，不管 article 已落 1 但事件丢失的场景）。触发条件：板块三 3.5 事件收口实现时定夺。

#### search 建索引数据源契约（ES 检索联动缺口）

search 消费 content.published 建 article_idx/book_idx（Sql-Document §九：标题^4/标签^3/摘要^2/正文/dense_vector）需回查标题/摘要/正文/标签/作者聚合数据——published 事件体字段未定义、ContentClient 现有三端点（getContent/listArticleImages/updateCounters）分别服务 review/social，search 侧复用 getContent 还是新增带标签聚合的 detail 回查端点未定；content 文档按职责边界只保证"有契约可回查"，索引字段映射属 search 模块设计决策，不提前替其定死。触发条件：litisle-search 模块开发时定夺（与 search 索引 mapping 设计同步收口）。

### file 文件模块

#### CDN 接入

public-url-base 已分离，届时仅改配置；随审核图片外链需求一并评估。

#### disk 冷热分层（`-disk=ssd/hdd` + `010` 规范）

生产 Volume 多机时一并设计，不单独排期。

#### 存储 TTL

quarantine 保留期（需按 appeal 待裁决态顺延）与对账清理（需 file_ref 活跃引用前置校验）均为业务条件删除，不适用存储 TTL；二期大文件分片的半途清理走 S3 桶生命周期规则（已定双保险方案）。

### 公共库模块

#### common-redis 不引 Sentinel（保持轻量，仍用自研 LocalRateLimiter）

业务模块已引 Sentinel（Nacos 数据源规则已落地）；触发条件与落位见 `docs/archive/20260929-implementation-records.md` §一·五（库模块当前不动）。