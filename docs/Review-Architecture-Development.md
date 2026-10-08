# litisle-review 审核模块架构文档（简化版）

> 版本：v1.0（2026-10-08，按当前实现对齐）
>
> 关联文档：[Review-Architecture-Development-detailed.md](Review-Architecture-Development-detailed.md)（详细版，防绕过推演/竞态分析看它）· [Sql-Document.md] litisle_review 章节（表结构 SSOT）· [Api-Architecture.md](Api-Architecture.md) · [commom-des.md](commom-des.md)
>
> 阅读约定：本文只保留**结论与机制**；每节末尾的「详见」指向详细版章节

------

## 一、模块定位与边界

| 项目     | 说明                                                         |
| -------- | ------------------------------------------------------------ |
| 定位     | 独立微服务，内容安全统一收口方：**同步关键词闸门 + 异步机审（三分态）+ 人审工作流** |
| 技术选型 | sensitive-word 0.27.2（DFA）· LLM 文本审核（经 litisle-ai 通道）· ONNX（开发期图片）/ 云 API（生产期）· RabbitMQ 延迟插件 · Redis 结果缓存 |
| 依赖方   | content / file / message 经 litisle-api 契约（Feign + MQ 事件）接入；notification 消费结果事件做通知 |
| 硬边界   | review **不跨库直写业务表**（回写一律 result 事件 + 业务方 CAS 回写）；业务方**不跨库直查** review 库（唯一特例：DFA 冷启动只读词库表兜底） |

### 1.1 三类能力

| 能力 | 载体 | 说明 |
| --- | --- | --- |
| 同步闸门 | DfaFacade（下沉 litisle-api 共享） | 发布/注册/私信链路内**本地执行**（免网络、不依赖 review 可用性）；HIGH 黑名单词直接拒绝 70001，MID 只标记不拦截 |
| 异步机审 | moderation 包 consumer + SPI 适配器 | MQ 消费驱动 LLM/图片模型，输出 PASS / REJECT / SUSPICIOUS 三分态 |
| 人审工作流 | ReviewService（workflow 域） | SUSPICIOUS 终审、举报处理、申诉裁决、违规分与处罚 |

### 1.2 代码结构（★=已落地，☆=板块三待建）

```text
litisle-review
├── moderation/                    # 机审域
│   ├── spi/          ★ ContentModerationService 门面 + ModerationResult + dto/
│   ├── adapter/      ★ LlmTextModerationAdapter（防注入三件套）/ OnnxImageModerationAdapter
│   ├── config/       ★ ModerationProperties + ModerationProviderStartupChecker（启动自检 fail-fast）
│   ├── consumer/     ☆ ModerationTextConsumer / ModerationImageConsumer / ModerationDlqConsumer
│   ├── cache/        ☆ ModerationResultCache（Redis → moderation_record 表双层查重）
│   └── service/      ☆ 统一建任务 upsert / 三分态落库 / result 事件发布
├── dfa/              ★ WordlibVersionService（版本推进+快照+Pub 广播）/ WordlibChangeSubscriber
├── service/          ★ ReviewService（认领 CAS / decide 终态 CAS / 举报 / 申诉）
└── enums / domain    ★ 三分态枚举、review_task 状态机、实体与 Mapper
```

------

## 二、审核漏斗与状态机

### 2.1 总体流程

```text
发布/上传请求
  ├─ 同步层（毫秒级）：DFA 关键词闸门 → 命中 HIGH 直接拒绝（70001）
  ├─ 前置校验：空内容/标题超长拒绝；标题随正文一并送审；content_hash=sha256(标题+正文)
  ├─ 落库待审（article.status=1 / file_meta.safe_flag=2）→ 发 MQ（延迟插件交换机）
  ▼ 异步消费者（litisle-review）
  ├─ 幂等：bizType+bizId+scene 定位任务，status≠0 按 content_hash 分流；CAS 条件更新
  ├─ 缓存查重：终局结论（PASS/REJECT）直接复用，SUSPICIOUS 挂既有任务
  ├─ SPI 调用：超长文本按段落分片（字符窗口 overlap 硬切），任一片 REJECT 即整文 REJECT
  ▼ 三分态
  ├─ PASS       → 业务态放行（article 2 / safe_flag 1）
  ├─ REJECT     → 驳回/下架 + punish_log(action=3) + violation_score 累计 → 通知作者（可申诉）
  └─ SUSPICIOUS → review_task.status=2 进人工复审队列
  └─ 故障 → 保持待审 + 延迟重试；耗尽进 DLX → 转人审兜底（绝不默认放行）
  └─ 全程追加 moderation_record（append-only 留痕）
```

### 2.2 三分态状态映射（不新增状态值）

| 三分态 | review_task.status | article.status | comment.status | file_meta.safe_flag |
| --- | --- | --- | --- | --- |
| PASS | 1-自动通过 | 1→2 | 0→1 | 2→1 |
| REJECT | 4-驳回 | 1→3 / 2→4（下架） | 0→2 | 2→0 |
| SUSPICIOUS | 2-人工待审 | 保持 1 | 保持 0 | 2→3 |

- 文本不写 safe_flag（一列不承载两种语义）；私信无待审态，REJECT/SUSPICIOUS 由 message 服务 CAS 置 status=2 风控拦截
- 回写 CAS 禁止驳回态被 result 来源覆盖（3→2 / comment 2→1 仅申诉来源放行）；CAS 统一带 `del_flag=0`

### 2.3 图片触发时机（按 biz_type 区分）

- **AVATAR/COVER**：上传即审——先查 REJECT 拒传黑名单（命中拒传不落库），未命中落库 safe_flag=2 + 发 image.submit；违规图移入 `quarantine/` 隔离前缀，del_flag 保持 0（不破坏 file_ref 不变式）
- **ARTICLE_IMG**：上传预审 + 发布复审；复审以文章为单位建一条任务（scene=ARTICLE_IMG_RE_REVIEW），content_hash 取全部配图 sha256 排序拼接后再哈希的聚合指纹
- 机审取图一律按 fileId 走 FileClient 内部契约（字节流），严禁按用户 URL 抓取（SSRF 防线）

### 2.4 幂等与统一建任务

- 任务定位键 = **bizType + bizId + scene**（复审与文本任务同 biz 同 id 并存，scene 必带）
- 唯一键 `uk_biz_scene`，建任务一律 **upsert**：content_hash 变化或旧任务已终态 → 追加 superseded 留痕后重置（清 assignee/decided_by/机审证据列，retry_count 归零）；hash 未变且在审（status∈{0,2}）→ 只复用回填关联列，**不打断已认领任务**
- 重置目标态：待机审场景置 status=0；举报复审场景（*_RE_REVIEW，除图片复审外）置 status=2 直进人审

### 2.5 结果缓存与免审（key 全版本化）

| Key | 结构 | 要点 |
| --- | --- | --- |
| 文本结果缓存 | `review:text-result:{wordlibVer}:{fewShotVer}:{scene}:{hash}` | 仅复用终局结论；命中仍追加 moderation_record（留痕不省） |
| 图片结果缓存 | `review:img-result:{provider}:{modelVersion}:{sha256}` | 版本/模型换版不复用旧结论 |
| REJECT 拒传黑名单 | `review:img-blacklist:{sha256}` | TTL 30 天，不随版本失效；申诉平反时移除 |
| 发布侧免审 | `review:last-hash:{wordlibVer}:{bizType}:{scene}:{bizId}` | hash 未变 + PASS + 非驳回/下架态才免审；含配图须连带校验图片复审键 |

- 所有 TTL 加 ±10% 随机抖动（防同批内容集体过期雪崩）；删除/下架/转私密必须失效免审与缓存键
- 详见 detailed §2.1 / §4.2.1 ②

### 2.6 故障降级矩阵（宁严勿松）

| 故障点 | 策略 |
| --- | --- |
| LLM/ONNX/云 API 异常 | 不 ACK → 延迟插件退避重试，内容保持待审 |
| 重试耗尽 | DLX 消费者转 status=2 人审兜底，绝不自动通过 |
| Redis 不可用 | 跳过缓存直调模型（多花钱不漏审），受全站 QPS 护栏 + 本地兑底计数约束，超限保持待审 |
| 调用级超时 | llm/onnx/api 各自 timeout-ms 外置；ONNX 加信号量并发闸（permits≈CPU 核数）；云 API 按异常比例熔断 |

------

## 三、接口设计

### 3.1 SPI 门面（moderation.spi）

```java
public interface ContentModerationService {
    ModerationResult moderateText(TextModerationRequest request);   // 文本异步消费端调用
    ModerationResult moderateImage(ImageModerationRequest request); // 图片上传即审/复审共用
}
// ModerationResult: verdict(PASS/REJECT/SUSPICIOUS) + category + reason + providerTraceId + costMs
// 视频音频一期显式不支持，预留 moderateVideo 扩展点，禁「无方法即默认放行」
```

适配器经 `litisle.moderation.text-provider / image-provider` 配置激活（@ConditionalOnProperty），配置与 Bean 不匹配时启动 fail-fast。

### 3.2 MQ 契约（常量落 litisle-api MqConstant）

| 项 | 值 | 说明 |
| --- | --- | --- |
| 交换机 | moderation.exchange（x-delayed-message topic） | 重试走延迟插件指数退避 |
| 路由键 | moderation.text.submit / image.submit / text.result / image.result / message.result | 文本/图片分队列；MESSAGE 单发 message.result |
| 死信 | moderation.dlx / moderation.dead | 重试耗尽转人审 |
| 申诉改判 | moderation.appeal.result | 业务方按 allow-list 对称恢复（3→2、4→2、comment 2→1、safe_flag 0→1） |
| 处罚 | moderation.punish.result | user 消费维护禁言态；封禁追加 Sa-Token kickout；**按 userId 串行消费**，消费端拉取权威态，不依赖到达顺序 |
| prefetch | litisle.moderation.consumer.prefetch（默认 10） | 防一次拉取过多消息 OOM |

- **submit 事件体**：`{ modType, bizType, bizId, scene, contentHash, retryCount, traceId }`——不含正文/图片地址，消费端按任务定位后回查
- **result 事件体**：`{ bizType, bizId, scene, contentHash, wordlibVer, reviewTaskId, verdict, category, reason, traceId }`
- **顺序纪律**：本地事务提交 → publisher confirm 发 result → ACK，任何一步失败抛异常不 ACK
- **bizType ↔ report.target_type 映射非恒等**：1/2/3 同值，5↔4、6↔5 错位，两方向都必须走映射表，禁 `==` 恒等实现

### 3.3 REST API（管理端 REVIEWER/ADMIN，申诉端点为作者本人）

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| /api/review/moderation/tasks | GET | 人审队列分页（筛 scene/biz_type/时间） |
| /api/review/moderation/task/{id} | GET | 详情：命中词 + 机审证据 + 内容预览 |
| /api/review/moderation/task/{id}/claim | POST | 认领 CAS：`where id=? and status=2 and assignee is null`，affected=0 返回 70004 |
| /api/review/moderation/task/{id}/decide | POST | 终审终态 CAS：`where id=? and status=2`（终态特性天然防重复裁决） |
| /api/review/appeal | POST | 作者申诉（窗口以 review_task.decide_time 为基准）；双层防重 + active_key 唯一索引兑底 |
| /api/review/appeal/{id}/judge | POST | 申诉裁决 CAS：`where id=? and status=0`；高风险撤销类强制 ADMIN + 回避原裁决人 + confirm=true 双签 |
| /api/review/report/** | GET/POST | 举报受理/复审；图片举报复用 IMAGE 场景任务不新建；多举报同内容只回填不重置 |
| /api/review/report/{id}/withdraw | POST | 举报人本人撤回（仅 status<3，撤后置 4-已撤回） |

- 预留错误码：70002-申诉窗口外、70003-重复申诉、70004-任务认领冲突
- 内部契约端点（/api/review/punish/status 等）**网关不配路由**，不对公网暴露
- 作者自查：content 侧 POST /api/content/preview-check，只返回 `{hit, risk}` 不回吐命中词（防词库 oracle），限频且不替代发布闸门

------

## 四、数据模型（要点，DDL SSOT 在 Sql-Document.md）

| 表 | 动作 | 要点 |
| --- | --- | --- |
| moderation_record | 新增，append-only | 机审/人审/申诉全留痕（mod_type 1~6）；idx_hash(content_hash, scene, id) 支撑缓存表兑底；trace_id 与 provider_trace_id 分列；留存 ≥6 个月，应用账号仅授 INSERT/SELECT |
| review_task | 存量补列 | mod_verdict（机审三分态冗余）/ retry_count / content_hash / scene varchar(32)（存量迁移：加可空列→回填→not null） |
| file_meta.safe_flag | 三态扩四态 | 0-违规/1-正常/2-待检测/3-存疑人审；default-deny 不变：公开面仅 1 放行，2/3 展示占位图 |
| sensitive_word_library / _version | 存量 | 表为词库 SSOT；版本单一来源 = sensitive_word_version（scene 级），任何变更同事务 +1 再广播；启动加载失败用本地快照兑底，绝不空词库放行 |
| report / appeal / punish_log / violation_score | 复用 | report/appeal/punish_log 补关联列；punish_log 补 score 快照列（重放口径确定可算） |

------

## 五、配置项外置清单（yml/Nacos，运营可调）

| 配置键 | 默认 | 说明 |
| --- | --- | --- |
| litisle.moderation.text-provider / image-provider | llm / onnx | 激活适配器（生产切 aliyun 零代码） |
| litisle.moderation.retry.max-count / base-delay-ms | 5 / 30000 | 重试次数与退避基数 |
| litisle.moderation.cache.ttl-hours / ttl-jitter-ratio | 72 / 0.1 | 结果缓存有效期与抖动比例 |
| litisle.moderation.reject-blacklist.ttl-days | 30 | REJECT 拒传黑名单有效期 |
| litisle.moderation.last-hash.ttl-hours | 72 | 发布侧免审 key 有效期（与 cache.ttl 对齐，落 litisle-shared.yaml） |
| litisle.moderation.qps.max-count | — | 语义层全站 QPS 护栏 |
| litisle.moderation.consumer.prefetch | 10 | MQ 消费者预取上限 |
| litisle.moderation.appeal.window-days / timeout-days | 7 / 7 | 申诉窗口 / 超时自动维持 |
| litisle.moderation.report.abuse-threshold | 3 | 诬告累计阈值（反向落警告+降信誉） |
| litisle.moderation.claim.timeout-minutes / max-active-per-reviewer | 30 / 20 | 认领超时回池 / 单审核员在手上限 |
| litisle.moderation.quarantine.retention-days | 10 | 违规图隔离保留期（review 侧专属清理，file 侧跳过该前缀） |
| litisle.moderation.sweeper.interval-seconds / stuck-resubmit-minutes | 60 / 30 | sweeper 周期 / status=0 卡死补偿重发阈值 |
| litisle.moderation.llm.temperature / few-shot-version / timeout-ms / max-text-chars | 0 / v1 / 15000 / 6000 | LLM 判定一致性与超长分片阈值 |
| litisle.moderation.onnx.timeout-ms / max-concurrency | 10000 / CPU 核数 | ONNX 超时与并发信号量 |
| litisle.moderation.api.timeout-ms / image.model-version | 8000 / v1 | 云 API 超时与图片判定口径版本 |
| litisle.moderation.dedup.lock-seconds | 60 | 在途去重短锁 TTL（防并发穿透烧费用） |
| litisle.moderation.alert.* | — | 告警阈值（积压/DLQ/失败率/通过率跌幅/缓存命中率跌幅） |

------

## 六、异常与错误码

| 场景 | 错误码 |
| --- | --- |
| HIGH 敏感词命中 / 违规图拒传 | SENSITIVE_WORD_DETECTED(70001) 同族 |
| 申诉窗口外 / 重复申诉 | 70002 / 70003 |
| 任务认领冲突（CAS 失败） | 70004 |
| 申诉被替他人发起 | 校验 appellant_id 与内容 owner 一致，否则拒绝 |

------

## 七、安全与合规

1. **防注入**：SQL 全 #{} 绑定 + 排序/筛选列白名单；LLM 防注入三件套（</content> 清洗 + system 数据声明 + JSON 白名单解析，解析失败宁疑不放）；UGC 落日志剥 \r\n（防 CRLF）；Redis key 各段服务端派生；MQ 事件体一律 JSON（禁 Java 原生反序列化）
2. **词库运营**：导入限长 + 字符校验（DFA 构建前拦截）；导出防 CSV 公式注入；新增词默认 MID 灰度，误报率达标升 HIGH；HIGH 同步拒绝内容不走 appeal，走独立误报上报入口
3. **权限矩阵**：队列/详情/认领/decide → REVIEWER/ADMIN；申诉 judge → 仅 ADMIN；词库读写 → 仅 ADMIN；原驳回裁决人回避该内容申诉；高风险撤销类双人复核（ADMIN + 回避 + confirm 双签留痕 mod_type=6）
4. **审核 ≠ 净化**：本模块只做判定；展示侧独立 HTML sanitize + 输出编码，凡 UGC 回显字段一律转义（审核后台是存储型 XSS 攻击面）
5. **留痕合规**：moderation_record / punish_log 属《网络安全法》硬凭证，不随账号注销删除（用户侧仅匿名化）；留存 ≥6 个月到期归档

------

## 八、观测与告警

| 指标 | 类型 | 说明 |
| --- | --- | --- |
| moderation_total{verdict} + moderation_cost | Counter/Timer | 三分态计数与耗时（ObjectProvider optional 埋点） |
| moderation_backlog{status=pending\|claimed\|suspicious} | Gauge | 积压**三态全量**，只统计单态会漏已认领未裁决 |
| moderation_dlq_backlog | Gauge | 死信兑底链路观测 |
| moderation_punish_total{action} | Counter | 处罚趋势 |
| moderation_cache_hit{layer=result\|blacklist} | Counter | 缓存命中验证省钱效果 |
| wordlib_misreport_total{scene} | Counter | 灰度转正唯一数据源（仅 scene 标签，禁按 word 打标签防基数爆炸） |

告警规则（Alertmanager，阈值全部外置 §五）：存疑积压超阈 / 在办超时 / DLQ 堆积 / 机审失败率 /
通过率骤变 / 缓存命中率跌落。traceId 全链路贯穿（网关→MQ→消费端→moderation_record.trace_id）。

------

## 九、实施进度（对齐当前代码）

| 板块 | 内容 | 状态 |
| --- | --- | --- |
| 板块一 | 机审 SPI 域：门面/适配器/配置自检 | ★ 已落地 |
| 板块二 | DFA 共享化 + 词库运营（版本推进/快照/Pub 热更/误报上报） | ★ 已落地 |
| 板块三 | MQ 异步机审链路：consumer / cache / 统一建任务 / result 事件 / DLQ | ☆ 实施中（细案见 next_update.md） |
| 板块四 | 发布侧文本链路接入（免审/DFA 埋点/preview-check） | 待办 |
| 板块五 | 申诉与处罚联动（appeal.result / punish.result / 双向对账） | 待办 |
| 板块六 | sweeper 基础设施（认领超时/申诉超时/隔离清理） | 待办 |
| 板块七 | 观测指标埋点与 Alertmanager 规则 | 待办 |