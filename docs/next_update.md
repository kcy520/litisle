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

## 待实现（按优先级排序）

## 审核模块开发清单（板块化，2026-10-06 梳理自 Review-Architecture-Development-Plan.md）

> 现状基线（已完成，不再列入）：file 侧 safe\_flag 四态闸口/拒传黑名单/quarantine 契约/result·appeal.result 消费回写/getContent/占位图/对账跳隔离前缀；litisle-api 契约层（MqConstant/ModerationConstant/事件体/FileClient）；review 侧人审域（claim/decide CAS、申诉防重三层、双人复核、举报撤回/诬告反制、词库表）。
> 推进顺序：板块一 → 三 → 四/五；板块二独立可并行先行；板块六依赖三；板块七随三收尾。各条目括号内为计划文档章节号，可回溯细案。

注意**：**\
**业务代码中LLM审核部分目前留下注释预留占位即可，暂不实现**

### 板块一：机审 SPI 域（§4.3 moderation 包 / §4.5.1）

- [x] ContentModerationService 门面 + ModerationResult（record：verdict/category/reason/providerTraceId/costMs）
  ——复用既有 VerdictEnum 三分态 SSOT（不另建 ModerationVerdict），category 直接用 ModerationCategoryEnum；
  单属性 dto 按防过度工程裁剪，签名直接收 String/byte[]；视频/音频显式不支持，预留 moderateVideo 扩展点（§2.9）
- [x] LlmTextModerationAdapter 代码落地（2026-10-06，编译态完成，联调前置见「暂不实现」AI 端点条目）：
  防注入三件套（</content> 标签清洗 + system 声明数据非指令 + 强制 JSON）+ 白名单校验
  （verdict/category 越界→SUSPICIOUS 宁疑不放，通道异常/降级 fail-fast 同入口）+ temperature=0/
  few-shot-version 外置 ModerationProperties + reason 截断 500 + 日志剥离 \r\n；
  moderateImage 抛 UnsupportedOperationException 暴露 provider 错配（启动自检前兼底）（§2.3/§2.11）
- [x] OnnxImageModerationAdapter（2026-10-06）：Semaphore tryAcquire 快速失败（permits≈CPU 核数外置）+
  独立线程池 + future.get(timeout) 封顶（原生推理不响应中断，permits 上限保证最坏有界）+ 构造期加载模型
  fail-fast；预处理/输出下标按 nsfwjs MobileNetV2 口径（TODO 核对 input card，落位时改 preprocess/nsfwScore
  两方法并 +1 image.model-version）；故障语义与 LLM 不对称：解码/推理/超时一律抛异常走延迟重试（§2.7/§2.11）
- [x] provider 启动自检 fail-fast（ModerationProviderStartupChecker，SmartInitializingSingleton）：
  blank→失败 / 值不在注册表→失败 / 值合法但适配器 Bean 缺失→失败 / 显式 none→放行+强告警
  （唯一合法禁用通道，杜绝隐式漏审）（§4.3）
- [x] 配置外置首批：ModerationProperties 收口 text/image-provider（默认 llm/onnx）、
  llm.temperature/few-shot-version、image.model-version、onnx.model-path/timeout-ms/max-concurrency/
  reject-threshold/suspicious-threshold；yml 样例见对话，环境差异走 Nacos 覆盖（§4.7）
- [ ] （二期）AliyunImageModerationAdapter：runtime scope，切生产零代码改动（§1.3）

### 板块二：DFA 同步闸门共享化 + 词库运营（§4.1 / §4.4.4 / §2.5 / §2.12）

- [ ] DfaFacade + WordLibraryLoader 下沉 litisle-api；scene 归一映射（去 \_RE\_REVIEW 后缀，图片场景跳过不加载）；70001 仅 HIGH 同步拦截、MID 只标记 layer1\_hit=2
- [ ] 词库版本单一来源 sensitive\_word\_version（scene 级）：任何变更同事务 +1 + Redis Pub 广播各实例重建 DFA（§2.5）
- [ ] 启动加载兜底：表加载失败/为空时加载本地持久化快照 + 告警，绝不空词库 default-allow（§4.4.4）
- [ ] 灰度：新词默认落 MID 观察 + 独立误报上报入口 + 运营降级（HIGH→MID）/加白 + wordlib\_misreport\_total{scene} 埋点（§2.5）
- [ ] 导入导出安全：词长度上限 + 字符校验（DFA 构建前拦截）、CSV/Excel 导出对 =/+/-/@ 开头单元格前置 '、解析不启外部实体（防 XXE）；LIKE 对 %/\_/\ 转义（§2.12）
- [ ] 词库页权限收口仅 ADMIN（§4.6.4）

### 板块三：MQ 异步机审链路（§4.2.1/§4.2.2/§4.5.2/§4.2.4）

- [ ] ModerationTextConsumer：幂等（bizType+bizId+scene 定位 + CAS status=0 + 按 content\_hash 分流补发/丢弃）+ 缓存查重（Redis→idx\_hash 表兜底，provider/wordlib\_ver/TTL 窗口比对；命中缓存仍补留痕行）+ 超长分片（段落分片→字符窗口 overlap 硬切，禁静默截断）（§4.2.1 ④⑤⑥）
- [ ] ModerationImageConsumer：取图一律走 FileClient 内部字节流（严禁按用户 URL 抓取）+ 图片缓存 key 含 provider+modelVersion（§4.2.2/§2.1）
- [ ] 统一建任务 upsert：uk\_biz\_scene 复用同行 + 重置例外/目标态按场景分列 + 清机审证据列 + 多轮举报回写/反查一律 status<3 收敛（§4.2.2）
- [ ] result 事件发布：事务顺序「本地事务提交→publisher confirm→ACK」；事件体必带 scene/contentHash/wordlibVer/reviewTaskId；bizType↔target\_type 映射表禁恒等（5↔4、6↔5 错位）（§4.5.2）
- [ ] 业务方回写矩阵消费端（content/message）：文章双链路联合聚合（含配图须图片复审 PASS；无图仅文本 PASS；纯图/空边界）+ 评论 CAS 带 del\_flag=0 + 私信 1→2 CAS（§4.5.2）
- [ ] ModerationDlqConsumer：重试耗尽转人审兜底（review\_task.status=2 / safe\_flag=3 / mod\_verdict=3）（§4.2.4）
- [ ] 成本护栏：全站 QPS 上限 + 入口 @RateLimiter(USER) + 单内容 SETNX 在途去重短锁（dedup.lock-seconds）（§2.6）
- [ ] 故障降级：延迟插件指数退避重试 + Redis 不可用直调模型仍受全站 QPS 护栏与本地兜底计数约束，超限保持待审（§4.2.4）

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

## 文档设计方案修复清单
