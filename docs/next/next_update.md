## 暂不实现（注释中已声明的预留项）

- [x] safe\_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——2026-10-02 接入完成，见待办第三节
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置
- [ ] common-redis 库模块不引 Sentinel（保持轻量，仍用自研 LocalRateLimiter）——**业务模块已引 Sentinel**（Nacos 数据源规则已落地）；触发条件与落位见归档文档 archive/20260929-implementation-records.md §一·五（库模块当前不动）
- [ ] review 侧隔离保留期清理任务（quarantine sweeper）——设计口径已定稿并入审核文档
  （Review-Architecture-Development-Plan.md §4.2.2「quarantine 清理任务」段）：扫描超期 REJECT 图 →
  按图反查 appeal（status=0 待裁决顺延）→ FileClient.deleteQuarantined 经 file 侧前缀校验后物理删除
  （review 无存储写凭证）。触发条件：review 侧开始搭建 sweeper 定时任务基建（认领超时回池/申诉超时维持）
  时一并落地；此前违规图滞留隔离前缀不影响安全性（safe\_flag=0 闸口 default-deny 不放行），仅占存储空间。
- [ ] litisle-ai 侧 chat 端点（POST /api/feign/ai/chat，OpenAI 兼容转发）——契约层已就绪
  （AiClient + AiChatRequestDTO/AiChatResponseDTO + AiClientFallbackFactory fail-fast + 自动装配注册，
  2026-10-06）；本端点暂不实现（用户决策：LLM 审核留注释占位，AiClient Javadoc 已标 TODO）。
  base-url/api-key/model 走 Nacos 外置，禁提交密钥；超时对齐 feign.client.config.litisle-ai.read-timeout。
  触发条件：板块三 ModerationTextConsumer 联调前，或 AI 侧 RAG 建设启动时一并落地。
- [ ] 审核模块——板块三 3-5 业务方回写矩阵消费端（content / social）（§4.5.2）——2026-10-10 自板块三实施细案移入本节（触发条件：litisle-content / litisle-social 正式开发时随实体层落地；当前 content 为空壳、social 未开发；私信归属 social，无独立 message 模块）
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

  **2026-10-11 message→social 口径修正说明**（本节及板块三/四/五相关条目已统一改为 social 归属）：
  1. 已实现部分零代码改动：3-1 ModerationTextConsumer（[x]）依赖的 MessageClient 契约、3-4 result 事件发布（[x]）的 moderation.message.result 路由键与 MESSAGE 场景枚举均属私信业务域命名，保留原名，已落地代码与本清单仍一致。
  2. 未实现部分（3-5 消费端、板块四/五私信相关条目）尚未编码，实现时直接按 social 归属落地，无返工。
  3. 未来唯一代码触点：social 实现私信域 Provider 时，需对齐 MessageClient 的 Feign 服务名（服务名硬编码在 @FeignClient 注解中而非 FeignConstant，仅需改 MessageClient.java 一处）。

## 待实现（按优先级排序）

## 审核模块开发清单（板块化，2026-10-06 梳理自 Review-Architecture-Development-Plan.md）

> 现状基线（已完成，不再列入）：file 侧 safe\_flag 四态闸口/拒传黑名单/quarantine 契约/result·appeal.result 消费回写/getContent/占位图/对账跳隔离前缀；litisle-api 契约层（MqConstant/ModerationConstant/事件体/FileClient）；review 侧人审域（claim/decide CAS、申诉防重三层、双人复核、举报撤回/诬告反制、词库表）。
> 推进顺序：板块一 → 三 → 四/五；板块二独立可并行先行；板块六依赖三；板块七随三收尾。各条目括号内为计划文档章节号，可回溯细案。

注意**：**\
**业务代码中LLM审核部分目前留下注释预留占位即可，暂不实现**

### 板块四：发布侧文本链路接入（§4.2.1 ①①′② / §4.5.3）

- [ ] 发布前置校验：空内容（trim + 去 HTML 标签可见文本非空）、标题≤100、标题与正文拼接一并送审（防违规藏标题）
- [ ] 同步 DFA 闸门嵌入各发布链路（文章/评论/私信/用户名）；私信 HIGH 命中同步拒发 70001 且不投递，仅 MID/LLM 走先发后审（§4.2.1）
- [ ] 免审 last-hash：版本化 key（wordlibVer:bizType:scene:bizId）+ scene 必带 + 状态排除条件按 bizType 分列（评论驳回态=2）+ 含图文章连查图片复审键且聚合 hash 一致 + 免审直落置 review\_task 终态（§4.2.1 ②）
- [ ] content.unpublished（删除/下架/转私密）同步失效 last-hash 与该 content\_hash 结果缓存键；免审排除条件补 del\_flag=0（§2.1）
- [ ] POST /api/content/preview-check：仅返回 {hit, risk} 不回吐命中词 + @RateLimiter(USER) 限频 + 仅本人草稿/待发内容，不替代发布闸门（§4.5.3）
- [ ] 私信撤回 CAS（仅本人仅正常态）+ 陌生人配额原子更新（INCR 为主 + DB 条件更新兜底，REJECT 拦截仍占配额）（§4.2.1）
- [ ] USERNAME 同步拒绝：不建 review\_task、只落 record(biz\_id=0)、不可申诉（明示设计非遗漏）（§4.2.2）

### 板块五：申诉与处罚联动闭环（§4.2.3 / §4.5.2）

- [ ] 机审 REJECT 联动落 punish\_log(action=3 内容下架, operator\_id=0-系统) + violation\_score 累计；自动禁言/封禁阈值判定只重放 action∈{1,2} 生效行，机审 action=3 不计入（§4.2.3）
- [ ] moderation.punish.result 事件 + user/auth 消费：禁言态快照维护 + 封禁 Sa-Token kickout 即时下线 + 权威态拉取（Feign /api/review/punish/status 短 TTL 本地缓存；punishLogId 仅排障严禁作版本门控）（§4.2.3）
- [ ] punish.result 队列按 userId 串行消费（单消费者/一致性哈希路由）+ 失败走 DLQ + 双向对账 sweeper（正向补漏 kickout / 反向补漏解封）（§4.2.3）
- [ ] 申诉改判 PASS：发布 moderation.appeal.result（allow-list：3→2/4→2、comment 2→1、message 2→1、safe\_flag 0→1）+ 缓存层同步失效（text/img-result key + last-hash + 黑名单移除该 sha256）+ punish 撤销 + violation\_score 重算 + 按内容身份回写同内容全部活跃举报 status<3（§4.2.3/§2.1）
- [ ] 图片申诉对称恢复：safe\_flag 0→1 + 对象从 quarantine/ 移回原前缀（unquarantine 契约已备）

### 板块六：Sweeper 基建 + quarantine 清理任务（§4.3 / §4.2.2；即原「暂不实现」quarantine sweeper 条目，触发条件本板块达成）

- [ ] ReviewTaskSweeper：认领超时自动回池（claim.timeout-minutes）+ 申诉超时自动维持（appeal.timeout-days，宁严勿松）+ 积压 Gauge 定时刷新（§4.3/§4.2.3）
- [ ] status=0 待审超时补偿重发：阈值 > MQ 最大退避总时长 + 重发前先抢 dedup.lock + 沿用 retry\_count 不归零（§4.3）
- [ ] quarantine sweeper：扫 record(biz\_type=6) verdict=REJECT 超 retention-days → 反查 appeal(status=0) 待裁决则顺延 → FileClient.deleteQuarantined（file 侧前缀校验 + 删缩略图 + del\_flag=1 + 回收站兜底）→ fail-fast 幂等 + 批量限速 + 天级频率（§4.2.2）
- [ ] moderation\_record 归档任务占位：retention-months=6 配置先行，归档任务二期落地（§4.4.1）

### 板块七：观测与告警收尾（§4.8 / §4.7）

- [ ] 指标：moderation\_total{verdict} + moderation\_cost Timer + moderation\_backlog{status=pending|claimed|suspicious 三态} + moderation\_dlq\_backlog + moderation\_punish\_total{action} + moderation\_cache\_hit{layer} + wordlib\_misreport\_total{scene}（仅 scene 标签防基数爆炸）；均 ObjectProvider optional 模式
- [ ] traceId 全链路贯穿：网关→发布→MQ 消息头→消费端→moderation\_record.trace\_id
- [ ] Alertmanager 六规则：存疑积压/在办超时/DLQ 堆积/机审失败率/通过率骤变/缓存命中率跌落；阈值全部外置（§4.7）
- [ ] §4.7 剩余配置项收尾：retry.*、cache.ttl-hours+jitter-ratio、reject-blacklist.ttl-days、last-hash.ttl-hours（落 litisle-shared.yaml）、qps.max-count、consumer.prefetch、appeal.window-days、report.abuse-threshold、claim.*、sweeper.\*、dedup.lock-seconds、llm.max-text-chars、image.model-version、few-shot-version

### 审核模块暂不做（触发条件随附）

- 云 API 生产适配器：生产切换时（pom runtime scope + Nacos 配置 AK/SK 走环境变量）
- 图文组合审核/OCR 多模态盲区（§2.9）：二期；此前由人审详情页「图文并排核对」兜底
- 撤销改判公示：二期，仅公示脱敏摘要，严禁回显 appeal.content/appellant\_id/reporter\_id（§4.2.3）
- 视频/音频审核：一期显式不支持，业务出现前须先补 SPI 方法与适配器
- quarantine sweeper 原条目已并入板块六，落地后回填「暂不实现」节勾选状态

### 四、待办

#### 代码修改类

**【生产上线前必做】**

**【暂不做（防过度工程，附触发条件）】**

- disk 冷热分层（`-disk=ssd/hdd` + `010` 规范）：生产 Volume 多机时一并设计，不单独排期。
- 存储 TTL：quarantine 保留期（需按 appeal 待裁决态顺延）与对账清理（需 file\_ref 活跃引用前置校验）
  均为业务条件删除，不适用存储 TTL；二期大文件分片的半途清理走 S3 桶生命周期规则（已定双保险方案）。
- [ ] Sentinel Dashboard 改规则只推内存、重启丢——操作纪律：规则增删改一律去 Nacos 控制台。
- [ ] 【生产部署】监控端口网络隔离（监控链路安全前提）：各服务 8xxx 端口的 /actuator/\*\*
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
  （可同步用 Prometheus 观察：redis\_guard\_degraded 0→1→0，Alertmanager 告警
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
- [ ] **file模块-replication ≥ 010 + Volume 多机**：用户上传原件不可再生，单副本（replication=001）不达
  生产标准；⚠️ 副本策略只影响新写入 volume，存量不自动补副本，须在首次生产写入前定稿。
- [ ] **file模块-Filer 元数据切 MySQL**：当前 leveldb 与数据同盘，盘坏 = 目录全丢（比丢文件更致命）；
  复用已有 MySQL 实例。开发期 leveldb 无所谓。

## 审核模块剩余待办 · 实施细案（2026-10-08 定稿，可直接照此写码，无需回查 Review 计划文档）

> **前置阻塞**：litisle-ai chat 端点（POST /api/feign/ai/chat，OpenAI 兼容转发）未实现——板块三 3-1 联调前必须落地（base-url/api-key/model 走 Nacos 外置，禁提交密钥；超时对齐 feign.client.config.litisle-ai.read-timeout）。业务代码中 LLM 审核部分留注释预留占位即可（用户决策）。
> **通用纪律（所有条目默认适用，后文不再重复）**：
> ① SQL 全走 MyBatis #{} 绑定禁拼接；排序/筛选列名服务端白名单枚举映射；LIKE 对 %/_/\\ 转义。
> ② Redis key 各段服务端派生（枚举名/数字 ID/SHA256），禁拼用户原串；value 一律 JSON，禁 Java 原生反序列化。
> ③ UGC/traceId 落日志前剥离 \r\n（防 CRLF 伪造日志行）。
> ④ 指标埋点 ObjectProvider<MeterRegistry> optional 注入，未接入不影响核心功能。
> ⑤ review 不跨库直写业务表，业务态回写一律 result 事件 + 业务方 CAS；跨库回查走 litisle-api 契约，fallbackFactory fail-fast（回查失败不 ACK 进延迟重试，禁静默吞错）。
> ⑥ 缓存/黑名单/last-hash TTL 一律 ±10% 抖动（防同批内容集体过期雪崩）。


### 板块四：发布侧文本链路接入（落位：litisle-content / litisle-social（私信）发布链路 / auth 注册链路）

#### 4-1 发布前置校验（§4.2.1 ①′）

**步骤**：
1. 发布/评论/私信接口内、DFA 之前执行：空内容=标题 trim 非空 且 正文去 HTML 标签后可见文本非空（防 `<p></p>` 空壳绕过）；标题≤100 字符（对齐 varchar(100)）。
2. 送审文本=标题+正文拼接（DFA 与 LLM 同口径；标题只进 hash 不进模型=违规藏标题躲过 LLM 漏审）。

**注意事项**：空内容校验必须在 hash 计算与送审之前——空内容 content_hash 恒定，全站共享同一 hash，一条 PASS 缓存即污染全部空内容免审。

#### 4-2 同步 DFA 闸门嵌入（§4.2.1 ①）

**步骤**：
1. 文章/评论/私信/用户名四条发布链路注入 DfaFacade（板块二已下沉 litisle-api，本地执行免网络调用）。
2. check(scene, 文本)→DfaHitResult：HIGH 命中→拒 70001；MID→不拦截，layer1_hit=2 随任务送异步层。
3. **私信特殊**：HIGH 命中同步拒发 70001 且**不投递 MQ**（不进异步库）；仅 MID/LLM 走先发后审。
4. USERNAME：注册路径同步拒绝即终态（见 4-7）。

**注意事项**：scene 传原始值由 DfaScenes 归一（去 _RE_REVIEW 后缀等），勿自行映射；MID 只标记不拦截（新增词灰度观察位）。

#### 4-3 免审 last-hash（§4.2.1 ②）

**步骤**：
1. content 消费 result 事件时写 Redis `review:last-hash:{wordlibVer}:{bizType}:{scene}:{bizId}`，value={hash,verdict} JSON，TTL=last-hash.ttl-hours(72)±10% 抖动，与 cache.ttl-hours 对齐（防「消费端已过期重审、发布侧仍免审」口径漂移）。
2. 发布时判定顺序：算新 hash→查 key（wordlibVer 取当前 DfaFacade 该 scene 版本）→三分支：hash 一致且 verdict=PASS 且状态排除→**免审直落 status=2，不发 MQ，且 review_task 就地置 status=1 终态**；hash 一致且 verdict=REJECT→前置提示「内容未修改仍将驳回」，不落库不发 MQ；key 缺失/SUSPICIOUS→照常发 MQ（Redis 丢数据宁多审不漏审）。
3. **状态排除按 bizType 分列**：文章 status∉{3-驳回,4-下架}；评论 status≠2-驳回（评论驳回态=2，套文章口径会漏排除→被驳评论原样重发复活）；均补 del_flag=0。
4. **含配图文章连带校验** `review:last-hash:{wordlibVer}:1:ARTICLE_IMG_RE_REVIEW:{articleId}` 存在、其聚合 hash=当前配图 sha256 排序拼接后再 sha256、verdict=PASS，三者缺一不可。
5. 免审直落必须置 review_task 终态（status=1）。

**注意事项**：
- 缺「聚合 hash 一致」比对→仅换图不改文时旧 PASS 键命中，架空图片复审。
- 缺「置终态」→sweeper（6-2）把免审任务当卡死重投 MQ，可能机审 REJECT 触发已发布文章 2→4 下架。

#### 4-4 content.unpublished 同步失效（§2.1）

**步骤**：
1. 删除(del_flag=1)/下架(status=4)/转私密 三处发 content.unpublished 事件的同一事务/紧随其后：删 `review:last-hash:{...}:{bizId}` + 删该 content_hash 的 text-result/img-result 缓存键。
2. 免审直落排除条件补 del_flag=0（同 id 复活场景）。

**注意事项**：防「PASS→人工/法务下架→删除→重发同内容（新 bizId 同 hash）」命中旧 PASS 缓存绕过下架决定——历史已定性的攻击面，必须闭环。

#### 4-5 POST /api/content/preview-check（§4.5.3）

**步骤**：
1. content 侧新增端点：入参=草稿/待发内容标识（或标题+正文），复用 DfaFacade 同 scene、同词库版本同步校验。
2. 返回仅 {hit: boolean, risk: HIGH|MID|none}，**禁回吐命中词**。
3. 鉴权：登录用户+仅本人草稿/待发内容；@RateLimiter(USER) 限频。
4. Javadoc 明示：预览仅为体验优化，**发布侧同步闸门必须独立执行，不替代发布校验**。

**注意事项**：回吐命中词=词库 oracle，攻击者可逐词探测黑名单后以同音/插字变体绕过；与发布同 scene 同词库版本保证口径一致。

#### 4-6 私信撤回 CAS + 陌生人配额（§4.2.1）

**步骤**：
1. 撤回：`update message set status=0 where id=? and sender_id=? and status=1`——仅本人、仅正常态；不物理删正文（留存审计），对方仅见「消息已撤回」。
2. 陌生人配额：Redis INCR 为主；Redis 失败→DB 原子条件更新 `update conversation set a_quota_used=a_quota_used+1 where id=? and a_quota_used<3`（禁读-改-写两段式）；REJECT 拦截消息仍占配额（投递行为已发生）。

**注意事项**：撤回与审核 1→2 CAS 并发互斥；撤回成功后审核 result 的 2→1 因 status=0 天然 no-op。

#### 4-7 USERNAME 同步拒绝（§4.2.2）

**步骤**：
1. 注册路径 DFA HIGH 命中→同步拒绝，**不建 review_task**，只落 moderation_record(biz_type=3, biz_id=0, hit_words 自包含取证)。
2. 不可申诉（appeal 三关联列至少其一非空才可建）——用户名被拒换名重试即可，明示设计非遗漏；无改名路径防绕过审核。

**注意事项**：同音/跳词变体漏网靠注册后举报+人审兜底（板块二误报入口可辅助运营观察）。

### 板块五：申诉与处罚联动闭环（落位：litisle-review workflow 包 + litisle-user / litisle-auth 消费端）

#### 5-1 机审 REJECT 联动处罚（§4.2.3）

**步骤**：
1. 3-1/3-2 消费端判 REJECT 的**同一本地事务内**：落 punish_log(action=3-内容下架, operator_id=0-系统, score=本次记入分值快照, status=1) + violation_score 累计。
2. **自动禁言/封禁阈值判定只重放 action∈{1,2} 的 status=1 生效行**——机审 action=3 不计入。

**注意事项**：
- action=3 仅落流水不改 violation_score.last_punish_action（不影响登录态）；若误计入→机审误判在申诉裁决前就触发真实封禁，误伤放大。
- 若机审驳回不处罚→违规者零分累计可低成本重复违规，违背「机审挡量、人审兑疑」漏斗设计。

#### 5-2 moderation.punish.result 事件 + user/auth 消费（§4.2.3/§4.5.2）

**步骤**：
1. 处罚落库同一本地事务内发事件，体={userId, action, op(1-生效/2-撤销), punishLogId, frozenUntil, traceId}。
2. user 消费：按 userId 经 Feign 调 review 内部端点 GET /api/review/punish/status 拉权威态（禁言/封禁快照）**覆盖本地**——事件仅作触发信号，幂等与到达顺序无关；端点短 TTL 本地缓存；**punishLogId 仅排障，严禁作版本门控**（撤销就地改同一行、id 不变不单调，按其丢弃会吞 op=2 撤销事件致误禁持续）。
3. 封禁(action=2)：auth/user 消费后额外执行 Sa-Token kickout 强制下线全部会话（封禁即时生效）。
4. 执行点在发布链路：content/social（私信）发布前经 user 契约校验禁言态拦截发言，不能只落 punish_log。
5. 网关**不配置** /api/review/punish/status 路由（内部契约端点防公网直查处罚态）。

#### 5-3 punish.result 串行消费 + 双向对账 sweeper（§4.2.3）

**步骤**：
1. 队列固定单消费者（低频足够）；扩容时按 userId 一致性哈希路由——并发消费下「拉取→写入」可乱序，「不依赖到达顺序」仅在串行下成立。
2. 消费失败不 ACK→DLQ；配对账 sweeper（挂 6-1 调度基建）：正向=比对 punish_log status=1 封禁行 vs auth 活跃会话**补漏 kickout**；反向=比对 status=2 已撤销行 vs user/auth 仍封禁快照**补漏解封**。

**注意事项**：漏封是安全风险、漏解封是误伤，两向都要收敛；永久封禁 expire_time=null 无到期自愈，撤销事件丢失只能靠反向对账恢复。

#### 5-4 申诉改判 PASS（§4.2.3/§2.1）

**步骤**：
1. judge 端点改判分支：CAS `update appeal set status=2, judge_by=?, judge_opinion=?, decide_time=now() where id=? and status=0`，affected=0→70004（@Idempotent 防不住跨人并发裁决）。
2. 同一本地事务：punish 撤销定位（appeal.punish_id 非空直用；否则按 review_task_id 反查 punish_log 生效行）→status=2 撤销 + violation_score 重算（重放 status=1 生效行 append-only 流水：展示/预警分含 action=3；自动处罚阈值仅 action∈{1,2}；action=1 撤销清 frozen_until、action=2 撤销发恢复事件）+ record(mod_type=6)。
3. 事务提交后发 moderation.appeal.result，体={bizType, bizId, scene, contentHash, reviewTaskId, action=1, traceId}——**必带 reviewTaskId**（notification 幂等键=A-{reviewTaskId}-{contentHash}-{verdict}）。
4. content/file/social 消费按 allow-list CAS（与机审 result 分列，防机审 PASS 绕过驳回）：article 3→2/4→2、comment 2→1、message（私信表）2→1、safe_flag 0→1。
5. **缓存层同步失效**：删 review:text-result / review:img-result 对应 key + 删 last-hash key + 从 review:img-blacklist 移除该 sha256——否则平反内容 TTL 窗口内仍判 REJECT，作者无法重发，申诉闭环断裂。
6. **按内容身份回写举报**：按 biz_type→target_type 映射（禁恒等，5↔4、6↔5）`update report set status=3, result='申诉改判PASS' where target_type=? and target_id=? and status<3`——收敛同内容全部活跃举报，非仅 appeal.report_id 那一份（防同一内容多路径举报交叉申诉绕过防重）。

**注意事项**：改判前业务态维持驳回/下架（受理仅登记不改业务态，防「未决先复活」）；原驳回裁决人（decided_by）不得裁决该内容申诉（含 ADMIN）；封禁/违规分清零类改判走双人复核四硬约束（ADMIN+回避+confirm=true+mod_type=6 双签留痕）。

#### 5-5 图片申诉对称恢复（§4.2.3）

**步骤**：
1. appeal.result 消费（bizType=6）：safe_flag 0→1（file 侧既有 appeal.result 消费链路已备）+ 调 FileClient.unquarantine(fileId) 把对象从 quarantine/ 移回原前缀；del_flag 全程 0 无需回滚。
2. 同步删 review:img-result key + 黑名单移除该 sha256（同 5-4 第 5 步）。

### 板块六：Sweeper 基建 + quarantine 清理（落位：litisle-review workflow 包 ReviewTaskSweeper + cleanup 包）

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
3. 执行：FileClient.deleteQuarantined(fileId)（**review 无存储写凭证**，s3.json 三身份拆分纪律）→file 侧校验 path 确在 quarantine/ 前缀内→删本体+_thumb_* 缩略图→del_flag=1→SeaweedFS 回收站兜底 7 天。
4. 纪律：fail-fast 单条失败留 error 日志由下轮兜住（文件已删则前缀校验/不存在跳过，天然幂等）；批量限速（每秒 N 条可配）；天级频率（任务内部分频，60s 粒度无意义）。
5. 观测：quarantine 积压 Gauge(biz_type 标签)+清理 Counter；**落地后回填「暂不实现」节 quarantine sweeper 条目勾选**。

**注意事项**：file 侧 FileReconcileTask 已对 quarantine/ 前缀跳过自身 7 天清理（已落地），本任务是 quarantine/ 前缀唯一删除责任方，不得重复造删除入口。

#### 6-4 moderation_record 归档占位（§4.4.1）

**步骤**：仅落配置 litisle.moderation.record.retention-months=6（《网络安全法》≥6 月合规下限），归档任务二期：按 create_time 迁只读历史存储后删热表行；删前跳过被 appeal.status=0 或 punish_log.status=1 引用的记录；按 id 区间幂等可重跑；应用账号仅授 INSERT/SELECT。

### 板块七：观测与告警收尾（§4.8/§4.7）

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

**注意事项**：标签只允许 verdict/status/action/layer/scene 等限定枚举集合，**禁按词/内容 ID/用户打标签**（基数爆炸拖垮 Prometheus）。

#### 7-2 traceId 全链路贯穿

网关过滤器生成/透传→发布接口写入 submit 事件 traceId 字段→MQ 消息头→消费端解析→moderation_record.trace_id 落库；与厂商凭证 provider_trace_id **分列**（前者排内部链路、后者对外出证）；落日志前剥离 \r\n。

#### 7-3 Alertmanager 六规则（阈值全部外置 §4.7 alert.*）

①存疑积压>suspicious-backlog-threshold(50)；②在办超时=claimed 超 claim.timeout-minutes 未裁决；③DLQ 堆积>dlq-backlog-threshold(100)；④机审失败率>moderation-fail-rate(0.2)；⑤通过率相对基线跌幅>pass-rate-drop-ratio(0.3)；⑥缓存命中率跌落>cache-hit-drop-ratio(0.5)。括号内为建议初值，均落 Nacos。

#### 7-4 §4.7 剩余配置项收尾（落位分两类）

- **litisle-shared.yaml（跨服务共享）**：litisle.moderation.last-hash.ttl-hours=72（content 写/review 声明，须与 cache.ttl-hours 对齐防口径漂移）、cache.ttl-hours=72、cache.ttl-jitter-ratio=0.1、reject-blacklist.ttl-days=30。
- **review 服务 yml/Nacos**：retry.max-count=5 / retry.base-delay-ms=30000、qps.max-count、consumer.prefetch=10、appeal.window-days=7 / appeal.timeout-days=7、report.abuse-threshold=3、claim.timeout-minutes=30 / claim.max-active-per-reviewer=20、sweeper.interval-seconds=60 / sweeper.stuck-resubmit-minutes=30、dedup.lock-seconds=60、llm.max-text-chars=6000、image.model-version、few-shot-version、alert.* 五项。

**注意事项**：既有暂不生效的 yml 配置保留并加注释说明（用户规则，不删）。

### 完成后动作

- 每条落地后回填本文档上方清单对应 [x] 与日期。
- 板块六 6-3 落地后：勾选「暂不实现」节 quarantine sweeper 条目。
- 板块三联调前置：先落 litisle-ai chat 端点（「暂不实现」节条目）。
- 推进顺序：3-1→3-2→3-3→3-4→3-5→3-6→3-7→3-8 → 板块四（与 3-4/3-5 联调）→ 板块五（复用 result 事件）→ 板块六 → 板块七随三收尾并行埋点。

## 文档设计方案修复清单
