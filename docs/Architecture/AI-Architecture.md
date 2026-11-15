# litisle-ai AI 模块架构文档（现状 + 设计态）

> 版本：v0.1（2026-11-08，按代码事实核对：`litisle-ai` 仅有启动骨架；review 机审能力按当前实现引用）
>
> 关联文档：[AI-Business-Guide.md](../Study/AI-Business-Guide.md)（功能 F1–F22、流程、规则、§十六 决策台账）· [AI-Doc-Checklist.md](../docs_design/AI-Doc-Checklist.md)（Q 系列 / R1–R12 / V1–V12，临时工作目录；Q 明细已删，仅留索引）· [Design-Document.md](../overall_documen/Design-Document.md) §2.3.6 / §2.6 · [Api-Architecture.md](Api-Architecture.md) §4.5 · [Review-Architecture.md](Review-Architecture.md) · [Content-Architeche.md](Content-Architeche.md) · [File-Architecture.md](File-Architecture.md) · [Search-Architecture.md](Search-Architecture.md) · [Sql-Document.md](../Sql-Document.md) §五

## 文档说明

| 项 | 说明 |
| --- | --- |
| 读者对象 | 后端开发、算法接入、测试、运维与产品；用于开工前对齐职责、现状与设计边界 |
| 口径来源 | 业务口径唯一来源为 `AI-Business-Guide.md`，其中「十六、D1–D25 决策定档」是决策唯一台账；本文只做架构落点与现状标注，不另立决策 |
| 状态图例 | `✅ 已实现` = 仓库中已有可运行代码/配置；`🟡 设计态（待实现）` = 口径已定但代码未开始；`⬜ 未开始` = 无设计、未立项或明确排除 |
| 事实基线 | `litisle-ai/src/main/java/com/litisle/litisleai/LitisleAiApplication.java` 与 `application.properties`（仅 `spring.application.name=litisle-ai`）；`pom.xml` 仅引入 `spring-boot-starter` 与测试依赖 |
| 重要约束 | 本文绝不把设计态写成已实现；凡引自其他模块的能力均标注「由谁提供」，AI 侧不冒领 |

------

## 一、模块定位与边界

| 项目 | 说明 |
| --- | --- |
| 定位 | AI 能力供给层：判定型（文本/图片机审）与生成型（摘要/标签/写作辅助/站内问答）统一收口，统一模型接入、配额与留痕 |
| 一句话边界 | AI 只回答「这条内容像什么、风险多高、能给出什么建议」，**不决定内容命运**；裁决在 review，状态在 content，存储在 file，检索在 search |
| 当前形态 | 独立微服务骨架已存在，业务代码为零；对外契约 `AiClient` 已在 litisle-api 定义，提供方端点未实现 |
| 不负责 | 内容存储/编辑/上下架、处罚/封禁/申诉裁决、敏感词库治理、图片隔离与清理、知识库摄入与索引维护、通知渠道与模板 |

### 1.1 职责切分（谁定音）

| 维度 | 定音方 | AI 交接物 | 出处 |
| --- | --- | --- | --- |
| 裁决 | review（机审分流 + 人工终裁） | 三分态结论 + 类目 + 理由 + 版本标识 | 业务指南 §1.3、§六 |
| 状态 | content（文章状态机 CAS 回写）、file（`safe_flag`） | 带 `contentHash` 的结论事件，供业务方丢弃陈旧判定 | Review-Architecture §2.2；业务指南 F10 |
| 存储 | file（隔离前缀、软删、引用解绑） | 只给判定，不移动/删除对象、不设隔离期 | File-Architecture v1.4；业务指南 F11 |
| 检索 | search（索引同步 + `IndexVersionGuard` 版本守卫） | 无关；AI 不写 ES、不做索引同步 | Search-Architecture §1.2 |
| 通知 | notification（消费 review 结果事件触达作者） | 判定理由摘要，不直接发通知 | 业务指南 §15.6 第 3 条 |
| 身份 | auth / user | 用户名场景只留痕，不产生处罚 | 业务指南 §十 场景矩阵 |

### 1.2 与 review 机审的现状关系（AI 侧尚不产出）

review 模块的机审框架已落地，AI 模块只提供模型通道与生成能力；以下能力**属于 review 已实现**，不可计入 AI 模块实现：

| 能力 | 载体 | 事实 | 状态 |
| --- | --- | --- | --- |
| 文本机审适配器 | `LlmTextModerationAdapter` | `text-provider=llm`，内部经 `AiClient.chat` 调 LLM；防注入包裹与白名单三分态映射在 review 侧 | ✅ 已实现（review） |
| 图片机审适配器 | `OnnxImageModerationAdapter` | `image-provider=onnx/none`；dev/local 为 `none`，模型文件缺失，缺失时消费期抛异常走重试后转人工 | ✅ 已实现（review）/ 模型 ⬜ 未就绪 |
| HTTP 全站 QPS 护栏 | `ModerationGlobalQpsInterceptor` | Redis 键 `rate_limit:global:moderation:qps` | ✅ 已实现（review） |
| 模型直调 QPS 护栏 | `ModerationModelQpsGuard` | Redis 键 `rate_limit:global:moderation:model-qps` | ✅ 已实现（review） |
| MQ 拓扑与死信 | `ReviewRabbitConfig` | 交换机 `litisle.moderation.exchange` / 延迟交换机 `litisle.moderation.delay.exchange` / 死信 `litisle.moderation.dlx`；vhost `/litisle` | ✅ 已实现（review） |
| 失败重试 | `ModerationProperties.Retry` | 最多 5 次，基准 30s，指数退避 30s→60s→120s→240s→480s（约 15.5 分钟），耗尽入 DLQ 转人工 | ✅ 已实现（review） |

现状结论：文本通道的配置虽指向 LLM，但**提供方端点不存在**，调用失败按 `SUSPICIOUS` 转人工；图片在本机 `image-provider=none`，同样全量转人工。因此当前是「框架已开、能力未供给」，不存在静默放行，代价是审核人力被打穿（业务指南现状声明、R1/R7）。

------

## 二、现状声明

### 2.1 骨架程度

| 项 | 事实 | 状态 |
| --- | --- | --- |
| 启动类 | `LitisleAiApplication`（仅 `@SpringBootApplication` + `main`） | ✅ 已实现 |
| 配置 | `application.properties` 只有 `spring.application.name=litisle-ai`，无 yml/Nacos 业务配置 | ✅ 已实现（骨架级） |
| 依赖 | `pom.xml` 仅 `spring-boot-starter`、`spring-boot-starter-test`；未引入 web/feign/mysql/redis/mq/模型 SDK | ✅ 已实现（骨架级） |
| controller / service / mapper / config / mq | 均不存在 | ⬜ 未开始 |
| 端口、数据库、Redis、MQ 连接 | 均未配置（端口待确认） | ⬜ 未开始 |

### 2.2 已实现清单

- ✅ `LitisleAiApplication` 可启动（空上下文，无任何业务 Bean）。
- ✅ 模块在父工程中作为独立 Maven 模块存在，具备后续接入基础。
- ✅ 外部契约面已存在（在 litisle-api）：`AiClient.chat` 与 `AiClientFallbackFactory`，降级语义 fail-fast。
- 注意：以上均不构成「AI 能力已实现」。

### 2.3 设计态清单（待实现）

| 组件/资产 | 设计来源 | 说明 | 状态 |
| --- | --- | --- | --- |
| `TokenQuotaService` | Design-Document §2.3.6 | 生成式配额与 Token 统计服务；代码中不存在 | 🟡 设计态 |
| `AiTokenUsage` | Design-Document §2.3.6 / Sql-Document §五 | 明细实体与 `ai_token_usage` 表；表结构已按 §8.3 落地（`docker/init/mysql/schema/ai-schema.sql`，2026-11-09），实体/服务代码未开始 | 🟡 设计态（表已建，代码未开始） |
| `AiLimiter` | Design-Document §2.3.6 | Sentinel 线程隔离限流器；代码中不存在 | 🟡 设计态 |
| `XiaoyueAgent` / `XiaoshenAgent` | Design-Document §2.3.6 | 生成式 Agent / 无状态判定 Agent；代码中不存在 | 🟡 设计态 |
| `AiSession` / `AiMessage` | Design-Document §2.3.6 / Sql-Document §五 | 会话与消息实体；代码中不存在 | 🟡 设计态 |
| RAG 摄入/检索（`KnowledgeBaseIngestService` 等） | Design-Document §2.6 | 设计存在；F7 前置依赖 search 知识库 | 🟡 设计态（另见 §十三） |
| `AiClient` 提供方端点 | Api-Architecture §4.5 | `POST /api/feign/ai/chat` 契约已定，AI 侧未实现 | 🟡 设计态 |
| 配额计量与用途打标 | 业务指南 D15 / Checklist 3.1 | 现有仅全站 QPS，无 Token/成本计量 | 🟡 设计态 |
| 小悦 Agent 底座 | Design-Document §2.3.6 + next_update 板块二（2026-11-15 定档） | LangChain4j AiServices 装配：ChatAgent + ChatMemory + Tools（Function Calling）；模型通道 DeepSeek API（OpenAI 兼容）；Agent 循环上限 5 轮，工具失败回喂不中断 | 🟡 设计态 |
| 意图路由与降级直连 | next_update 板块二 2.3 | 单一入口 `/ai/xiaoyue/chat` 由 Agent 判定意图分流；固定端点保留为降级直连通道；purpose 由功能入口预传入，Agent 循环继承 | 🟡 设计态 |
| 结构化自校验 | next_update 板块二 2.4 | JSON 解析失败/越池/超长时错误回喂重试 1 次（最小反思闭环）；校验在 service 侧执行 | 🟡 设计态 |
| ChatMemoryStore（Redis） | Design-Document §2.3.6 | LangChain4j ChatMemoryStore Redis 实现；DB 全量存 + Memory 滑窗（windowSize 最近 N 条），两层口径不冲突 | 🟡 设计态 |
| Embedding 通道 | next_update P3 定档（2026-11-15） | 本地 Ollama + bge-m3（1024 维）优先，云 API（智谱 embedding-3）兜底 | 🟡 设计态 |

### 2.4 未开始清单

| 项 | 原因 | 状态 |
| --- | --- | --- |
| F16 搜索/推荐语义供给 | D9 已定：维持 search 自建，AI 不承接 | ⬜ 未开始（不立项） |
| F17 文档/音视频多模态 | D10/D11 已定：本期排除，仅中文 | ⬜ 未开始（排除） |
| 本地图片模型与推理算力 | 模型文件缺失、算力未保障（R1） | ⬜ 未开始（外部前置） |
| 固定评测集与标注 | 无数据集与标注人力（R4） | ⬜ 未开始（外部前置） |
| 通知模板与触达 | 归 notification，AI 不实现 | ⬜ 未开始（他模块） |

------

## 三、包结构与分层

### 3.1 现有真实结构

```text
litisle-ai
├── pom.xml                                   ✅ 已实现（仅 starter + test）
└── src/main/
    ├── java/com/litisle/litisleai/
    │   └── LitisleAiApplication.java         ✅ 已实现（唯一源码文件）
    └── resources/
        └── application.properties            ✅ 已实现（仅应用名）
```

### 3.2 规划结构（设计态，目录均为待建）

```text
litisle-ai
├── controller/                    🟡 待建：REST 接入层，只做协议与校验
│   ├── XiaoyueController          🟡 摘要/标签/润色/分析/问答入口（路径见 §7.1）
│   ├── XiaoshenController         🟡 判定辅助入口
│   └── SessionController          🟡 会话列表与删除
├── service/                       🟡 待建：业务编排，事务与幂等收口
│   ├── impl/                      🟡 接口实现
│   ├── support/                   🟡 闸门、裁剪、建议态组装等支撑
│   └── quota/                     🟡 配额校验/扣减/退还（对应 TokenQuotaService）
├── agent/                         🟡 待建：小悦 Agent 装配（LangChain4j AiServices：ChatAgent + ChatMemory + 意图路由 + 循环上限控制）
├── tool/                          🟡 待建：Function Calling 工具（取稿件/查标签池/敏感词预检/知识库检索；只读不写业务库）
├── provider/                      🟡 待建：模型接入单点
│   ├── text/                      🟡 文本模型通道（OpenAI 兼容）
│   ├── image/                     🟡 图片模型通道（本地优先）
│   └── embedding/                 🟡 向量通道（本地 Ollama bge-m3 优先，云 API 兜底；P3 定档 2026-11-15）
├── mapper/                        🟡 待建：仅操作 litisle_ai 自有表
├── domain/
│   ├── entity/                    🟡 会话/消息/用量/反馈
│   ├── dto/                       🟡 出入参
│   └── vo/                        🟡 建议态与引用卡片
├── enums/                         🟡 用途、Agent 类型、结论枚举
├── constant/                      🟡 上限、用途枚举、日志字段名
├── mq/                            🟡 待建：如后续需要生成事件/消费内容事件
├── guard/                         🟡 待建：限流、熔断、并发舱壁
├── metrics/                       🟡 待建：指标统一打点
└── config/                        🟡 待建：模型、配额、超时参数外置
```

说明：目录形态参考 Design-Document §2.3.6 的组件划分，但本文按「接入 / 编排 / 供给 / 护栏 / 观测」重新归位；具体类名落地时以技术设计定稿为准，**未在代码中出现前不得引用为既有实现**。

### 3.3 分层约束

| 约束 | 内容 |
| --- | --- |
| controller | 只做协议转换、参数校验、登录态/角色校验；不直连模型、不写库 |
| service | 编排调用顺序、配额与留痕、建议态组装；事务边界在此收口 |
| provider | 唯一接触模型 SDK/HTTP 的层；所有 prompt 版本、超时、重试参数从此处取 |
| mapper | 只操作 `litisle_ai` 自有表；禁止跨库访问 review/content/file 表 |
| guard | QPS、并发、熔断统一收口；禁止散落在 controller/service |
| metrics | 指标名与标签集中定义，避免同一语义多个指标名 |

------

## 四、核心组件与职责

### 4.1 组件总表

| 组件 | 职责 | 依赖 | 对外产出 | 状态 |
| --- | --- | --- | --- | --- |
| 文本判定 | 对标题+正文/评论/私信/用户名给出三分态、类目、理由 | `AiClient` 通道或直连模型；调用方分片与回查 | 结构化判定（供 review 落库与回写） | 🟡 设计态（review 侧适配器 ✅，AI 供给端点未实现） |
| 图片判定 | 对配图/封面/头像给出三分态与类目，仅本地/私有化模型 | file 侧按 `fileId` 取字节（禁用户 URL）；本地 ONNX | 单图判定；整篇聚合在 review | 🟡 设计态（review 适配器 ✅，模型 ⬜ 未就绪） |
| 判定辅助（小审） | 结合机审证据给审核员风险等级、理由、免审建议 | review 任务详情数据；不使用用户私密上下文 | 建议态证据，不自动裁决 | 🟡 设计态 |
| 小悦 Agent 底座 | Agent 化编排核心：LangChain4j AiServices 装配（system prompt 角色与边界 + Tools 注册 + ChatMemory）；Agent 自主决定调用哪个工具、调几次，循环上限 5 轮 | 1.1 模型通道；tool/ 工具；会话记忆 | 结构化建议产出；Agent 不直接写业务库，落库决策在 service | 🟡 设计态 |
| 能力工具（Function Calling） | 取稿件标题+正文（ContentClient）、查标签池候选（ContentClient）、敏感词预检（复用 DFA 闸门）、知识库检索（板块四前占位） | 调用方契约；闸门机制 | 工具观察结果回喂 Agent；工具只读 | 🟡 设计态 |
| 出站脱敏过滤器 | 未公开稿件外发前脱敏（手机号/邮箱/身份证/外部链接）；Q63 边界修订路线（2026-11-15 定档） | provider 出站口 | 脱敏后内容；外发保留配置开关 | 🟡 设计态 |
| 摘要生成 | Agent 经工具自主取稿后生成建议摘要（≤500 字），不自动写入 | content 稿件内容；DFA 闸门由调用方执行 | 建议文本，作者确认后由 content 落库 | 🟡 设计态 |
| 标签建议 | 从现有标签池推荐候选及理由，不新造标签 | content 标签池；闸门由调用方执行 | 候选标签列表（建议态） | 🟡 设计态 |
| 写作辅助 | 润色、结构建议 | 选中段落/全文 | 建议文本，仅作者可见 | 🟡 设计态 |
| 站内问答 | 检索 search 提供的公开过审片段，组织回答并附引用 | search 知识库/检索；引用可见性回查 | 回答 + 引用卡片；下架即失效 | 🟡 设计态 |
| 模型接入通道 | 统一 base-url/api-key/model、超时、重试、结果解析 | Nacos/yml 配置；模型服务 | 统一 `chat`/判定结果 | 🟡 设计态 |
| 配额计量 | 按用户+用途计数、限额、预算告警 | Redis/MySQL；用户等级与 VIP | 放行/拒绝 + 用量记录 | 🟡 设计态 |
| 结论回写 | 不直写业务表，只提供带指纹与版本的结论 | review 结果事件 + content/file CAS | 结论事件（由 review 发布） | 🟡 设计态（回写机制由 review/content 已有实现） |
| 反馈采集 | 记录 AI 建议与人工结论的采纳/否决/改判 | 审核工作台与任务数据 | 追加型反馈记录 + 聚合指标 | 🟡 设计态 |
| 能力开关与预算 | 场景级开关、灰度比例、预算预警/熔断 | 运营配置 | 开关生效、超额拒绝/告警 | 🟡 设计态 |
| 会话与留存治理 | 90 天 + 200 会话 + 单会话 500 条，LRU 淘汰，支持用户删除 | MySQL/Redis；D6/D25 | 会话列表、删除结果 | 🟡 设计态 |

### 4.2 依赖方向

```
controller → service → provider → 模型服务
service → quota → Redis/MySQL（litisle_ai）
service → 调用方契约（结论以 review 事件为准，AI 不反向直写）
mq（如需）→ service（生成任务/反馈聚合）
guard/metrics 作为横切能力被 service/provider 调用
```

------

## 五、关键链路时序

说明：`✅` 为已实现环节（主要在 content/review/file），`🟡` 为 AI 侧设计环节，`⬜` 为外部前置未就绪。

### 5.1 文本审核（文章主链）

```
content 发布入口 → DFA 本地同步闸门（毫秒级，命中高危直接拒绝）  ✅
→ content 置 status=1 并发 content.submitted（事件不含正文）        ✅
→ review 消费 → ContentClient 回查正文 → 按段落切片                 ✅
→ ModerationModelQpsGuard 申请模型调用配额（Redis 双键之一）         ✅
→ TextModerationAdapterProvider → LlmTextModerationAdapter          ✅
→ AiClient.chat（POST /api/feign/ai/chat）                          🟡 提供方未实现
→ AI 服务文本判定（三分态 + 类目 + 理由 + 版本标识）                  🟡 设计态
→ review 解析白名单并写 moderation_record（append-only）             ✅
→ review 发布 moderation.result → content CAS 1→2 / 1→3             ✅
→ notification 触达作者（文案口径 D20）                              ⬜ 由 notification 实现
```

- 任一片段 REJECT → 整篇 REJECT；SUSPICIOUS → `review_task.status=2` 进人审。
- AI 侧不可用：调用失败返回 `SUSPICIOUS`/异常 → review 延迟重试 → 耗尽 DLQ → 转人工；**任何环节不得默认放行**。
- 结论迟到且 `contentHash` 已变化时，业务方丢弃旧结论（指纹防线在 content）。

### 5.2 图片上传即预审

```
客户端 → file 上传（大小/MIME/魔数校验）                            ✅
→ 查拒传黑名单 review:img-blacklist:{sha256}（命中拒传）             ✅
→ file_meta 落 safe_flag=2（待审）并发 image.submit                 ✅
→ review ModerationImageConsumer → FileClient.getContent（字节流）  ✅
→ OnnxImageModerationAdapter（image-provider=onnx/none）            ✅ 适配器；模型 ⬜
→ AI 图片判定（仅本地/私有化，不外发 UGC 图片，D2）                  🟡 设计态
→ review 落结论 → file CAS safe_flag 1/0/3                          ✅
→ 违规图执行隔离（quarantine/ 前缀 + safe_flag=0，del_flag 保持）    ✅
→ 发布 moderation.result                                            ✅
```

- 未出结论前图片不得公开引用；存疑保持待审，人审前不展示。
- 隔离不是"改标记位"，而是移入隔离前缀；到期由 QuarantineSweeper 委派 file 软删 + 解绑引用（可恢复期内可恢复）。

### 5.3 配图聚合复审

```
content 发布/免审直落 → 即使文本免审也必须配图复审（免文不免图）     ✅
→ review 建一条任务 scene=ARTICLE_IMG_RE_REVIEW                     ✅
→ content_hash = 全部配图 sha256 排序拼接后再哈希（聚合指纹）        ✅
→ ContentClient.listArticleImages 反查清单 → 逐张 FileClient 取图    ✅
→ AI 对每张图给单图判定（AI 不做整篇聚合）                           🟡 设计态
→ review 聚合裁决（任一张 REJECT → 整篇 REJECT）                     ✅
→ moderation.result → content 聚合槽与 file safe_flag               ✅
```

- 聚合槽丢失或重复消费会导致长期"审核中"，由 F21 对账清扫兜底（设计态）。

### 5.4 申诉改判与解隔离

```
作者申诉 → review AppealService 建申诉（窗口/防重/active_key）       ✅
→ ADMIN 裁决（高风险撤销强制回避原裁决人 + 双签）                    ✅
→ 回查作者处罚权威态：封禁中不放行；查询异常/非 0/空同样不放行        ✅
→ review 发布 moderation.appeal.result                              ✅
→ content 仅接受申诉来源的 3→2 / 4→2（机审/人审不得反向恢复）        ✅
→ file unquarantine 解隔离 + 移出拒传黑名单                          ✅
→ search 重新索引 / 知识库引用恢复                                   ✅
```

- AI 不参与改判、不解封、不撤销处罚；申诉时只提供原判定证据（原因码/命中规则/置信度，D14）。
- 文件解隔离失败无补偿属已知缺口，由 F21 补偿，AI 不越权处理（见清单索引 Q7）。

### 5.5 小悦 Agent 生成链路（摘要/标签/润色/问答统一编排）

```
作者/读者发起请求（功能入口预传 purpose，缺省拒绝）                  🟡 设计态
→ 单一入口 /ai/xiaoyue/chat：Agent 判定意图并路由                    🟡 设计态
→ Agent 循环（上限 5 轮）：自主调工具（取稿/查标签池/预检/检索）      🟡 设计态
→ 结构化产出 + 自校验（解析失败/越池/超长回喂重试 1 次）              🟡 设计态
→ 建议态返回（Redis 缓存 30 分钟，按 requestId 可回查）               🟡 设计态
→ 生成文本过同步 DFA 闸门（由调用方 content/review 执行）             ✅ 机制已有
→ 作者编辑/确认 → content 写入（摘要 ≤500 字；标签只从现有池选择）    ✅（落库能力）
→ 展示"AI 生成/AI 建议"标识（D3/D13）                                🟡 设计态
→ 未确认则丢弃，不落库
```

- Agent 循环异常/模型不可用 → 降级走固定管道直连端点（/summary、/tag 等保留）；降级链路同样计量。
- 直连端点为一次性动作：不建会话、不写 ai_session/ai_message；Memory 仅服务多轮 /chat 入口（2026-11-15 定档）。
- 出站脱敏：未公开稿件正文经脱敏过滤器后外发（Q63 边界修订路线，2026-11-15 定档），provider 出站前统一执行。
- 摘要/标签不进正文指纹，不触发正文重审；但摘要展示前须过闸门。
- 作者自浏览不计入内容浏览统计（content 11.11-A），AI 采纳率埋点必须独立，不复用浏览量口径。
- 标签不得由 AI 自动创建；新标签只能由作者走正常创建流程并过闸门。

------

## 六、数据模型与实体关系

### 6.1 结论记录（SSOT 在 review，AI 不落结论表）

| 项 | 说明 | 状态 |
| --- | --- | --- |
| 表 | `moderation_record`（litisle_review） | ✅ 已实现 |
| 关键列（实际） | `id`、`mod_type`、`biz_type`、`biz_id`、`review_task_id`、`scene`、`content_hash`、`provider`、`wordlib_ver`、`model_version`、`verdict`、`category`、`hit_words`、`reason`、`provider_trace_id`、`trace_id`、`operator_id`、`cost_ms`、`create_time` | ✅ 已实现 |
| 缺口 | 无独立 `reason_code`、`confidence` 列（D14 已定证据构成，字段待随 §6.2 落地）；`prompt_version` 不单独落列：文本场景由 `model_version` 承载（该列在 review 存的就是提示词版本），图片场景无提示词、不另加列 | 🟡 设计态（字段待落） |
| AI 侧 | 不新增结论表；AI 返回结论经 review 落库 | 🟡 设计态 |

### 6.2 版本标识与过期标记

| 项 | 用途 | 状态 |
| --- | --- | --- |
| `model_version` | 模型/接口版本；换版不复用旧结论 | ✅ 已落列（review） |
| `wordlib_ver` | 词库版本快照；换库不复用 | ✅ 已落列（review） |
| `prompt_version` | 提示词版本，供 D16 抽样重判与效果对比 | ✅ 文本场景由 `model_version` 承载（该列在 review 存的就是提示词版本）；图片场景无提示词，不另加列 |
| 过期标记 | 版本变更后标旧结论、抽样重判（D16） | 🟡 设计态 |

### 6.3 反馈记录（设计态）

| 项 | 内容 |
| --- | --- |
| 暂名 | `moderation_feedback_record`（业务指南 F13.2 暂名，归属 review 还是 ai 待确认） |
| 用途 | 一次人工操作一条，只增不改；计算采纳率、否决率、误判率、漏判率 |
| 关键列 | `id`、任务标识、`biz_type`、`biz_id`、`scene`、AI 建议结论、`confidence`、人工结论、是否一致、改判原因标签、`model_version`（文本即提示词版本）、`rule_version`、操作类型、处理耗时、审核员标识（脱敏）、`created_at`、上一条记录标识、是否最终生效 |
| 状态 | 🟡 设计态（D22 强制标签、D23 保留 12 个月） |

### 6.4 配额与会话记录（设计态）

| 表 | 用途 | 关键列 | 状态 |
| --- | --- | --- | --- |
| `ai_session` | 会话与 LRU 淘汰 | `id`、`user_id`、`agent_type`、`title`、`mode`、`is_lru`、`last_active_time`、`del_flag` | 🟡 设计态（Sql-Document §五） |
| `ai_message` | 会话消息与逐条 token | `id`、`session_id`、`role`、`content`、`token_cnt`、`prompt_tokens`、`completion_tokens`、`create_time` | 🟡 设计态（Sql-Document §五） |
| `ai_usage_daily` | 用户日用量汇总（按用户 × 用途 × 日） | `id`、`user_id`、`purpose`、`stat_date`、`calls`、`tokens_in`、`tokens_out`、`cost`、`fail_calls`、`degraded_calls`；`uk(stat_date, user_id, purpose)` | ✅ 表结构已落地（`ai-schema.sql`，2026-11-09）；聚合与查询代码未实现 |

### 6.5 关系与留存

```
review_task 1 ── n moderation_record        （一次送审可多次记录/复核轮次）
review_task 1 ── n moderation_feedback_record（一次任务多次人工操作，只增不改）
ai_session  1 ── n ai_message
user        1 ── n ai_session / 1 ── n ai_usage_daily(按用户 × 用途 × 日)
```

- DDL 统一指向 `docs/Sql-Document.md` §五，本文不写 DDL。
- 留存：审核理由 6 个月；生成输入输出 90 天且可用户删除（D6/D14）；反馈记录 12 个月（D23）；会话 90 天 + 200 个 + 单会话 500 条（D25）。

------

## 七、接口与事件契约

### 7.1 REST 分组（全部 🟡 设计态）

下表路径部分取自 Design-Document §2.3.6 的 API 行，仅作为设计锚点；2026-11-15 起新增统一入口与降级直连口径（见 docs/next/next_update.md litisle-ai 板块）；网关前缀按业务指南为 `/api/ai/**`，落地前须在 Api-Architecture 定稿，**不得据此认为端点已存在**。

| 分组 | 端点（设计） | 说明 | 状态 |
| --- | --- | --- | --- |
| 统一入口 | `POST /ai/xiaoyue/chat` | 小悦 Agent 单一入口：意图路由（摘要/标签/润色/结构分析/问答/闲聊），purpose 由入口预传入；问答分流带 RAG 引用 | 🟡 设计态 |
| 降级直连 | `POST /ai/xiaoyue/summary` / `tag` / `polish` / `analyze-structure` | 固定管道直连通道（Agent 循环异常时降级）；一次性动作，不建会话 | 🟡 设计态 |
| 作者端 | `GET /ai/sessions` | 会话列表 | 🟡 设计态 |
| 审核员端 | `POST /ai/xiaoshen/judge` | 判定辅助（不自动裁决） | 🟡 设计态 |
| 内部 | `POST /api/feign/ai/chat` | 见 §7.2 | 🟡 设计态 |
| 管理端 | 待确认 | 场景开关、预算、用量报表无既定路径 | ⬜ 未开始 |

### 7.2 内部 Feign

| 项 | 内容 | 状态 |
| --- | --- | --- |
| 契约 | `AiClient.chat(AiChatRequestDTO) → Result<AiChatResponseDTO>` | ✅ 已实现（litisle-api） |
| 降级 | `AiClientFallbackFactory` fail-fast；review 捕获后转 `SUSPICIOUS` | ✅ 已实现 |
| 提供方 | AI 服务需实现该端点；当前无 controller | 🟡 设计态 |
| 语义 | OpenAI 兼容通道，base-url/api-key/model 外置；system+user 双段 prompt；防注入/JSON 校验由调用方组织 | 🟡 设计态 |

### 7.3 MQ 事件与路由键

| 项 | 值 | 提供方 | 状态 |
| --- | --- | --- | --- |
| 业务交换机 | `litisle.moderation.exchange`（Topic） | review | ✅ 已实现 |
| 延迟交换机 | `litisle.moderation.delay.exchange`（x-delayed，direct 语义） | review | ✅ 已实现 |
| 文本送审 | `moderation.text.submit` → `litisle.moderation.text.submit.queue` | review | ✅ 已实现 |
| 图片送审 | `moderation.image.submit` → `litisle.moderation.image.submit.queue` | review | ✅ 已实现 |
| 结果 | `moderation.result`（file）、`moderation.text.result`（content 文本/配图复审）、`moderation.image.result`、`moderation.message.result` | review | ✅ 已实现 |
| 申诉结果 | `moderation.appeal.result` | review | ✅ 已实现 |
| 死信 | `litisle.moderation.dlx` + 各 `.dlq` 队列 | review | ✅ 已实现 |
| vhost | `/litisle` | 各服务 yml | ✅ 已实现 |
| AI 侧新增事件 | 判定结论不新建事件，统一走 `moderation.result`；生成式事件（如需）路由键待确认 | AI | ⬜ 未开始 |

- 送审事件体只带定位键（`modType`、`bizType`、`bizId`、`scene`、`contentHash`、`retryCount`、`traceId`），正文/图片由审核侧回查，避免敏感内容在 MQ 中扩散。
- AI 侧当前既不生产也不消费 MQ；若后续需要异步生成（长文本摘要），必须新设计事件并在本文与 Api-Architecture 同步登记。

### 7.4 幂等与重试语义

| 项 | 语义 | 状态 |
| --- | --- | --- |
| 送审幂等键 | `bizType + bizId + scene`，同 `contentHash` 在审则复用任务；变化则 supersede 重置 | ✅ 已实现（review） |
| 结论复用 | 仅终局（PASS/REJECT）；文本按词库+提示词+场景+指纹，图片按 provider+模型版本+sha256 | ✅ 已实现（review 缓存） |
| 重试 | 最多 5 次，指数退避，约 15.5 分钟；耗尽入 DLQ 转人审 | ✅ 已实现（review） |
| AI 端点幂等 | 生成按 `requestId + callSeq` 联合键去重（2026-11-15 定档，见 §8.3④）；机审沿用送审幂等键 | 🟡 设计态 |
| 生成式重试 | 失败重试 1 次 → 终态 → 失败退还已预扣配额，同一 `requestId` 只扣一次（业务指南 §5.3 配额规则；Q8 已闭合） | 🟡 设计态 |

------

## 八、配额与成本计量设计

> 整节状态：🟡 设计态（待实现）。现状只有全站 QPS 计数，**无 Token/成本计量、无用途区分**。

### 8.1 现状

| 能力 | 事实 | 状态 |
| --- | --- | --- |
| 全站 HTTP QPS | `rate_limit:global:moderation:qps`（review 拦截器） | ✅ 已实现（review） |
| 模型直调 QPS | `rate_limit:global:moderation:model-qps`（review 消费链路） | ✅ 已实现（review） |
| 按用途计数 | 无；机审与生成共用同一阈值口径，无法分账 | ⬜ 未开始 |
| Token 计量 | 无代码（三张计量表已按 §8.3 建表：`ai-schema.sql`；计量链路未实现，现有仅全站 QPS） | 🟡 设计态（表已建） |
| 用户配额 | 无（`TokenQuotaService` 仅设计） | 🟡 设计态 |

### 8.2 设计要点（D15 / D5 / F14）

| 项 | 设计口径 | 状态 |
| --- | --- | --- |
| 分账 | 机审预算与生成预算独立（机审桶 = 文本 + 图片合并计量）；机审优先；生成预算打满只停生成 | 🟡 设计态 |
| 用途打标 | 机审文本 / 机审图片 / 摘要 / 标签 / 问答五类（已定，与业务指南 §5.3「成本可见」的枚举一致；见清单索引 Q28） | 🟡 设计态 |
| 用户配额 | 普通用户按日低配额，按等级放开；VIP 高额度但不限量为否；与生成预算科目挂钩（机审预算独立，见 D15） | 🟡 设计态 |
| 扣减与退还 | 调用前原子预扣 + 成功后确认；失败退还；按请求键幂等（Q8 已闭合） | 🟡 设计态 |
| 机审预算耗尽 | 降级为全量人工 + 告警，**不得降低机审判定频率、不得静默放行** | 🟡 设计态 |
| 计量落点 | AI 调用入口统一打标，写入 `ai_token_usage` 或等价计数 | 🟡 设计态 |

### 8.3 按用途计量与预算分账（R2 实施设计）

> 一次改造同时支撑 D15 分账、F14 预算、V8 生成配额、V5 熔断计数；四件事共用同一套埋点与同一套用途枚举，不各写一套。

**① 调用入口统一带用途**

- 单一入口接收 `purpose` 枚举：`MODERATION_TEXT` / `MODERATION_IMAGE` / `SUMMARY` / `TAGGING` / `QA` / `OTHER`。**计费与预算只认前五类**（与业务指南 §5.3「五类为计量打标的唯一定义」一致）；`OTHER` 仅作兜底落明细，**不计入预算与报表**，出现即告警并按「未归类调用」单独观察。
- 用途由调用方显式传入，**不靠 URL、模型名或表名推断**；缺省时拒绝调用并记告警，避免「忘传」被默认归到机审。
- 同一次业务请求可能触发多次模型调用（机审切片、Agent 多轮、重试），用业务侧生成的 `request_id` 串联；每次模型调用带调用序号 `callSeq`（切片为 chunkIndex、Agent 为轮次序号），重试复用同一 `request_id + callSeq`（2026-11-15 修订）。

**② 明细按日汇入汇总表，不做实时扣减**

- 明细表 `ai_token_usage`（只追加，不更新）：`request_id`、`user_id`（机审无发起用户时为空）、`purpose`、`scene`、`model_version`、`prompt_version`（文本与 `model_version` 同值，图片留空）、`token_in`、`token_out`、`cost`、`status`、`degraded`、`occurred_at`；失败的调用也留一条，避免「失败即无成本」。
- 汇总表 `ai_usage_daily`：每日定时任务按 `(stat_date, user_id, purpose)` 聚合，字段 `calls`、`tokens_in`、`tokens_out`、`cost`、`fail_calls`、`degraded_calls`；唯一键 `(stat_date, user_id, purpose)`。**保留用户粒度**的依据是 F9（会话与用量管理）与个人配额都要按用户查；运营看板与预算所需的「日 × 用途」全局量由该表按 `(stat_date, purpose)` 求和得出，不另建第二张同名表。
- 成本口径：单价配置在 `ai_pricing(model_version, currency, unit_price_in, unit_price_out, effective_from)`；金额 = token 数 × 单价，两位小数；配置变更只影响未来日期，不回改历史汇总。
- 预算判定读 `ai_usage_daily` 当日**按预算桶**的合计（机审桶 = 文本 + 图片合并；生成类按用途各自计数；对用户维度求和），本地缓存 60 秒，**不在每次调用时读写预算键**，避免每次调用多一次写入；预算按自然日 00:00 重置。

**③ 预警与停止**

- 80%：按**预算桶**告警（站内信 + 群机器人），只提醒、不停用。
- 100%：**只停该用途的新建请求，不牵连其他用途**。预算按 D15 分账：生成类用途（`SUMMARY` / `TAGGING` / `QA`）各自到 100% 只停自己；`MODERATION_TEXT` 与 `MODERATION_IMAGE` 合并计入「机审桶」，机审桶到 100% 时**不停用调用**，转全量人工并告警——机审额度不因生成用途超支而被扣减，也不会被其他用途的 100% 连带停掉。
- 生成被停时返回明确错误码，前端显示「今日额度已用完」，不得静默返回空结果。

**④ 幂等与重复计量防护**

- 计费以 `request_id + callSeq` 联合唯一键（数据库唯一索引兜底），重复写入按 upsert 跳过；重试不重复计费。（2026-11-15 修正：原 `request_id` 单键口径只覆盖机审切片场景，Agent 多轮调用会在同一 request_id 下产生多条明细，单键会撞键漏计）
- 归属日期按「调用完成时间」，不按发起时间；跨天重试只计一次成功成本。

**⑤ 实施落点与组件归属（2026-11-09 定案，设计态待实施）**

- 表结构已落地：`ai_token_usage` / `ai_usage_daily` / `ai_pricing` 三张表（`docker/init/mysql/schema/ai-schema.sql` + Sql-Document「litisle-ai 模块」）。
- 契约打标（实施落 litisle-api）：`dto/ai` 包新增 `AiPurposeEnum`（`MODERATION_TEXT` / `MODERATION_IMAGE` / `SUMMARY` / `TAGGING` / `QA` / `OTHER` 六值）；`AiChatRequestDTO` 增 `purpose`（必传，缺省端点侧拒绝）、`requestId`（业务侧生成的幂等键）、`scene`（可空）三个字段，契约口径见 Api-Architecture「AiClient」与共享 DTO 表。
- review 侧传参（实施归属 review）：SPI `ContentModerationService.moderateText` 改收 `TextModerationRequest`（含 requestId/scene）；文本链路 purpose 固定 `MODERATION_TEXT`，requestId 由 `ModerationTextService#moderateWithChunking` 按任务上下文逐片生成 `taskId:chunkIndex`（分片确定性切分保证延迟重试复用同一 requestId，满足「重试不重复计费」），scene 取 `review_task.scene`；图片走本地 ONNX 不经 AI 服务，`MODERATION_IMAGE` 打标待 AI 图片判定端点实现时接入。
- litisle-ai 计量基建（实施归属 ai）：三表实体与 Mapper；`AiUsageRecordService` 写明细（request_id 幂等，撞唯一键跳过；失败调用也落一条 status=FAIL）；`AiBudgetService` 预算桶判定（机审桶 = 文本 + 图片合并、SUMMARY/TAGGING/QA 各自桶；读 `ai_usage_daily` 当日合计、本地缓存 60 秒；返回 ALLOWED / WARN(≥80%) / EXCEEDED(100%)；预算值来自 `AiBudgetProperties`，≤0 表示未设预算、只计量不限额）；`AiUsageDailyAggregateTask` 定时聚合（每小时 + 启动延迟一次，对当日与前一日幂等覆盖写，保证预算判定当日数据可用；明细与汇总差异 >1% 记日志告警）。
- 以上组件均为设计态，实施时与「AI 对话端点」批次同批接线；预算具体数值待财务科目定档后补配置。

------

## 九、可靠性

| 故障/场景 | 处理 | 用户可见 | 是否可能漏审 | 状态 |
| --- | --- | --- | --- | --- |
| 模型超时 | provider 侧超时控制 + 调用方重试；内容保持待审 | 审核中延迟 | 否 | 🟡 设计态（仅 provider 单次调用超时待定稿；送审重试沿用 review 既有 5 次 / 30s 基准） |
| 限流/配额超限 | 机审：保持待审并延迟重试；生成：拒绝并提示稍后 | 生成不可用；审核延迟 | 否 | 🟡 设计态（机审侧 review 已有 QPS 行为） |
| 模型不可用/未配置 | 显式告警 + 全量转人工；不默认放行 | 长期审核中 | 否 | ✅ review 已实现降级（AI 供给缺失导致全转人工） |
| 重试耗尽 | 入 DLQ → 死信消费转人审 | 审核中延长 | 否 | ✅ 已实现（review） |
| 幂等 | 送审按定位键 + 指纹；生成按请求键去重（待确认） | 无感 | 否 | ✅ review / 🟡 生成侧 |
| 熔断/隔离 | **按用途分开计数、服务级熔断（见清单索引 Q52）**：同一用途连续失败达 5 次（5 分钟窗口，V5 定档 2026-11-08）即打开；打开期间新任务直接转人工、在途任务停止重试；半开探测（自检）成功后自动恢复，恢复前后的结论按 §4.5 规则打 `degraded` 标记。实现优先复用项目已有的 Redis 计数与限流惯例（review 的 QPS guard 同款），不新增 Sentinel/Resilience4j 组件；若后续确需线程隔离再评估引入 | 生成可降级；机审转人工 | 否 | 🟡 设计态（计数键待定稿） |
| RAG 不可用 | 降级为不引用回答并明确标注 | 回答无引用卡片 | 不适用 | 🟡 设计态 |
| 生成失败 | 提示"暂时无法生成"，可手动填写；不落库 | 功能不可用 | 不适用 | 🟡 设计态 |
| 告警定位 | "哪类能力不可用"须能在 5 分钟内定位 | 运维侧 | 不适用 | 🟡 设计态 |

原则：可靠性设计的第一目标是**不默认放行**；生成式的可用性可以降级，机审的完整性不能降级。

------

## 十、安全与合规

| 主题 | 要求 | 落点 | 状态 |
| --- | --- | --- | --- |
| AI 生成标识 | 摘要/问答强制标识；标签标"AI 建议"（D3/D13） | 生成结果落库前与展示前的 UI/字段契约 | 🟡 设计态 |
| 结论审计 | 结论 append-only 留痕，人工改判全保留 | `moderation_record`（review） | ✅ 已实现 |
| 版本可追溯 | 结论带模型/词库/提示词版本，支持过期标记与抽样重判（D14/D16） | `model_version`/`wordlib_ver` 已落列；文本提示词版本由 `model_version` 承载，不另加 `prompt_version` 列（图片无提示词） | 🟡 部分设计态（仅剩过期标记） |
| 精简留存 | 只存原因码 + 命中规则 + 置信度，不存模型原始返回全文，6 个月（D14） | D14 已定证据构成；`moderation_record` 当前只有透传 `reason`（≤500 字），缺 `reason_code`/`confidence` 列，与证据构成不等价——剩余缺口是字段落地，不是口径未定（Q2 已闭合） | 🟡 设计态（字段待落） |
| 生成内容留存 | 90 天，可用户删除（D6） | 会话/消息清理 | 🟡 设计态 |
| 会话上限 | 90 天内 200 会话、单会话 500 条，超限按最久未活跃淘汰（D25） | 会话治理 | 🟡 设计态 |
| 反馈留存 | 12 个月，前 6 在线、后 6 归档（D23） | 反馈记录 | 🟡 设计态 |
| 内容出站 | UGC 图片首期不外发（D2）；私信只做机器判定、未公开内容按公开内容处理（V3 定档 2026-11-08）；用户已删除内容仍不外发 | provider 出站闸门 + 出站过滤器 | 🟡 设计态（闸门与脱敏未实现） |
| 敏感上下文 | 按 V3 执行：脱敏（手机号/邮箱/身份证/地址/外部链接等）+ 出站审计 + 用户告知同意；法务书面确认未到前按待确认处理（R12） | 出站过滤器 + 条款文案 | 🟡 设计态 |
| 不可信数据 | 站内内容与用户输入不得当指令执行；prompt 注入防护由调用方组织 | provider/调用方 | 🟡 设计态 |
| 原始输出 | 模型原始输出不直接展示给终端用户，只展示结构化、可解释结论 | 响应组装 | 🟡 设计态 |

------

## 十一、可观测性

### 11.1 指标

| 指标 | 标签/维度 | 归属 | 状态 |
| --- | --- | --- | --- |
| `moderation_total{verdict}` | pass/reject/suspicious | review | ✅ 已实现 |
| `moderation_cost` | 无 | review（SPI 调用耗时） | ✅ 已实现 |
| `moderation_cache_hit{layer}` | result/blacklist | review | ✅ 已实现 |
| `moderation_punish_total{action}` | 处罚动作 | review | ✅ 已实现 |
| `ai_call_total{purpose,provider,result}` | 五类用途、provider、成功/失败 | AI | 🟡 待建 |
| `ai_call_latency{purpose}`（Timer） | P50/P95/P99 | AI | 🟡 待建 |
| `moderation_e2e_latency{scene}`（Timer） | 入队→结论落库，P50/P95 | review + AI | 🟡 待建 |
| `ai_tokens_total{purpose}` | 输入/输出/总 token | AI | 🟡 待建 |
| `ai_quota_exceeded_total{purpose}` | 配额/预算超限 | AI | 🟡 待建 |
| `ai_generation_total{scene,result}` | 摘要/标签/问答 | AI | 🟡 待建 |
| `ai_guard_state{provider}` | 熔断/降级状态 | AI | 🟡 待建 |

命名沿用 review 的 `moderation_*` 风格：代码侧用点号（Micrometer），Prometheus 渲染为下划线。

**端到端时延口径**：`moderation_e2e_latency` 覆盖排队 + 调用 + 重试 + 落库，P95 用 5 分钟滚动窗口、告警看日聚合；业务指南 §5.1 验收与 §11.3「调用时延」统一引用该指标，provider 侧 `ai_call_latency` 只用于定位慢在哪一段。

**按用户用量**：不用指标标签（避免高基数），由调用明细按日汇总到 `ai_usage_daily(user_id, purpose, stat_date, calls, tokens_in, tokens_out, cost)`；F9 与个人配额读该表，运营报表按 `(stat_date, purpose)` 对同一张表求和（不在指标里加用户维度），指标只承担全局告警。

### 11.2 日志字段

| 字段 | 用途 |
| --- | --- |
| `traceId` | 网关→MQ→消费端全链排障（review 已有 `trace_id`） |
| `bizType` / `bizId` / `scene` | 定位内容与场景 |
| `verdict` / `category` | 判定结论 |
| `modelVersion` / `wordlibVer` / `promptVersion` | 版本追溯 |
| `provider` / `providerTraceId` | 供给方与合规凭证 |
| `purpose` | 计量打标（机审文本/机审图片/摘要/标签/问答） |
| `costMs` / `tokenIn` / `tokenOut` | 性能与成本 |
| `result` | success/fail/timeout/quota_exceeded |

要求：日志不得打印正文全文、私信内容或用户身份信息；只留标识、指纹与必要片段。

### 11.3 告警阈值建议（初值，待校准）

| 场景 | 建议阈值 | 状态 |
| --- | --- | --- |
| 机审存疑积压 | 持续超 50 条（review `Alert.suspiciousBacklogThreshold`） | ✅ review 已配置 |
| DLQ 积压 | 持续超 100 条（review `Alert.dlqBacklogThreshold`） | ✅ review 已配置 |
| AI 服务不可用/未配置 | 启动即告警；连续 3 次调用失败告警 | 🟡 待建 |
| 生成预算 | 80% 预警、100% 停生成并告警 | 🟡 待建 |
| 调用时延 | P95 > 60s 持续 10 分钟告警 | 🟡 待建（验收基线为 95% 在 60s 内） |
| 配额超限突增 | 单场景小时环比 > 3 倍告警（防刷） | 🟡 待建 |
| 采纳率/误判率 | 周环比降 20% 或误判率连续 3 天超基线 1.5 倍告警 | 🟡 待建（F13） |

> **唯一出处约定（见清单索引 Q72）**：本表是告警阈值与统计窗口的唯一出处，业务指南「F13.3 告警建议」只保留业务含义并链接到此处，不再重复数值。
>
> **校准责任方**：模型效果类基线（采纳率、误判率）由算法负责，人工时效类基线（积压、过期任务占比）由审核运营负责；首次校准在阶段 0 结束时完成，之后每个模型/提示词版本复校一次。责任岗位已按 V9 定档（提示词变更：算法值班评估 + 审核运营负责人审批；自动写入：产品负责人提议 + 运营/法务会签），剩余为岗位名映射到实际花名册，属排期而非待决策（Q45 已闭合）。
>
> **基线未建立时**：沿用 review 已配置值（存疑积压 50 条、DLQ 100 条），其余阈值仅作预警参考，不作为考核判据。

------

## 十二、与决策的映射

| 决策 | 要点 | 本文落点 |
| --- | --- | --- |
| D1 | 文本机审保守（高召回），逐步放宽 | §4.1 文本判定、§5.1、§11.3 |
| D2 | UGC 图片首期仅本地/私有化，不外发 | §1.2、§5.2、§10 内容出站、§十三 |
| D3 | 强制标注 AI 生成/建议 | §10 |
| D4 | 摘要/标签默认建议态，不自动写入 | §4.1、§5.5 |
| D5 | 按日低配额 + 等级 + 生成预算分账（以 D15 为准，机审优先），VIP 高额度非不限量 | §8 |
| D6 | 生成内容留存 90 天，可删除 | §6.5、§10 |
| D7 | 只显示状态 + 预计范围，不承诺具体时长 | §5.1、§10 |
| D8 | 公开内容匿名可问 + IP/设备配额 | §7.1、§十三 |
| D9 | 搜索/推荐语义归 search，AI 不承接 | §十三 |
| D10 | 本期排除文档/音视频多模态 | §十三 |
| D11 | 仅中文 | §十三 |
| D12 | 误判反馈复用申诉入口 | §4.1 反馈采集、§5.4 |
| D13 | 摘要/问答强制标识，标签标"AI 建议" | §10 |
| D14 | 只存原因码+命中规则+置信度，6 个月 | §6.1、§6.2、§10 |
| D15 | 分账、机审优先；前置为补计量 | §8（整节设计态） |
| D16 | 抽样重判 + 过期标记 | §6.2、§9、§十三 |
| D17 | 全部发布入口逐篇送审 | §5.1、§十三 |
| D18 | social 启用前必须接入评论/私信消费方 | §5.1、§十三 |
| D19 | 算法评估 + 运营审批，不自动改阈值 | §4.1 反馈采集、§十三 |
| D20 | 通知写"自动审核 + 人工复核"，不暴露模型细节 | §5.1、§十三 |
| D21 | 固定评测集每版本 ≥300 条 | §十三 |
| D22 | 改判原因标签强制 + 其他 + 备注 | §6.3 |
| D23 | 反馈记录保留 12 个月 | §6.3、§10 |
| D24 | 反馈仅评估/脱敏评测集，训练需单独授权 | §10、§十三 |
| D25 | 90 天 200 会话、单会话 500 条，LRU 淘汰 | §6.4、§6.5、§10 |

------

## 十三、未纳入范围与技术债

| 项 | 结论/原因 | 依赖或相关风险 | 状态 |
| --- | --- | --- | --- |
| F16 搜索语义供给 | D9：维持 search 自建（ES + 实时 Embedding 兜底），AI 不接管检索与排序 | search 索引能力、D9 翻案条件 | ⬜ 不立项 |
| F17 文档/音视频多模态 | D10/D11：本期排除；下期从 OCR 文本提取复用文本判定 | 存储、耗时、隐私 | ⬜ 排除 |
| 本地图片模型与算力 | 模型文件缺失；无算力时按既定降级全量转人工 | R1；D2 硬冲突 | ⬜ 未就绪 |
| 固定评测集与标注 | D16/D21/D24 的执行前置；无数据集无法放开阈值或评估自动写入；可执行设计见第十四章 | R4 | 🟡 设计态（方案已写，数据集未建） |
| 计量改造（用途打标 + Token） | D15 分账的执行前置；现有仅全站 QPS；可执行设计见 8.3 | R2；用途粒度已统一（见清单索引 Q28） | 🟡 设计态 |
| 结论版本标识 | `model_version` / `wordlib_ver` 已落列；文本场景 `model_version` 即提示词版本 | R3（已关闭的一半） | ✅ 已实现 |
| 存量结论过期标记与抽样重判 | 旧结论无法标过期，重判无可执行任务与报告（D16 定方法） | R3（保留的一半）；F19 | 🟡 设计态 |
| 反馈记录实体与工作台强校验 | D22/D23 前置；无表与强校验无法聚类 | R5（文档侧已闭合）；反馈流程见清单索引 Q12 | 🟡 设计态 |
| 审核结论通知模板 | 归 notification；AI 只给理由摘要 | D20 | ⬜ 他模块 |
| 会话/内容懒删的定时兜底 | 用户停止使用后懒删不触发，可能超期留存 | R9；D6/D25 | 🟡 设计态 |
| 敏感上下文出站边界 | 已定（V3，2026-11-08）：私信只做机器判定、未公开内容按公开内容处理；脱敏 + 审计 + 告知同意；法务书面确认未到前按待确认处理 | R12；R8 已闭合 | 🟡 设计态（法务确认未到） |
| 归档任务启用与验证 | R10 已关闭（当前无违规内容可清理）；事实仍保留：归档任务默认关闭，首次出现违规内容或上线前合规检查时重开 | R10（已关闭，保留触发条件）；D14 | ⬜ 暂不处理 |
| 评论/私信结论消费方 | social 未启用，结论目前无在线消费方 | D18；R6 | ⬜ 未开始 |
| 审核人力与排班 | 所有存疑最终落人工，人力是单点 | R6 | ⬜ 非技术项 |
| 外部模型降级细则 | 重试次数/终态/配额退还已部分明确，AI 侧未实现 | R7（文档侧已闭合）；配额退还已定（见清单索引 Q8） | 🟡 设计态 |
| 内容指纹与摘要/标签边界 | 摘要/标签不进正文指纹，不触发正文重审；已写明 | — | ✅ 口径已明确 |

## 十四、固定评测集实施设计（R4）

> 目标：让 V10 三项指标可重复测量，让 D21 报告有固定样本。评测集是「测量工具」，不是训练数据；不允许为了跑分好看而改样本。

**① 首版规模与构成**

- 三套样本量分层，不得混用：**300 条 = 版本总量下限**（D21，模型灰度与质量报告的固定总量）；**50 条 = 单场景配额**（机审文本、机审图片、判定辅助、摘要、标签、问答引用各 50 条）；**≥100 条 = 评估切分格门槛**（业务指南 F13.3，切分格 = 场景 × 模型版本 × 提示词版本）。
- 按实际开工范围取前 6 类，未开工类别留空位、不凑数；**开工场景不足 6 类时按 50 条/场景实取，总量随之下调，报告须标注「未达 D21 300 条总量」，不得判合格**。
- 每场景内：违规样本 ≥60%、正常样本 ≥30%、边界或对抗样本 ≥10%；违规类型覆盖现有词库主要类目。
- 样本来源：历史人工裁决记录（脱敏）+ 人工构造；**不得直接使用未脱敏的真实用户原文**。

**② 标注流程**

- 双人独立标注；标注前先对齐标注手册（每类一句判定标准 + 3 个示例）。
- 交叉抽检 10%：第三人复核双标一致的样本；不一致率 >10% 时该场景退回重标。
- 冲突仲裁：两人不一致 → 第三人裁决；仍不一致 → 记为「争议样本」，排除出合格线计算，只做定性观察。
- 标注人不得是本次被评估的模型或提示词的直接开发者，避免自评。

**③ 版本化与不可变**

- 评测集按版本管理（`eval_set_v1`、`v2`…）；任何增删改都发新版本，旧版本冻结可查。
- 每份报告必须写明评测集版本 + 模型版本 + 提示词版本；三者缺一不算有效报告。
- 只有「新增场景」或「发现标注错误」才升版本；为提升分数改样本视为违规。

**④ 报告模板（对齐 V10）**

- 三项必填：可用性 ≥4/5 占比、关键事实错误率、场景准确率（对比阶段 0 基线）。
- 每项给出分子/分母与样本量；样本不足 300 条时报告标注「不足以判定合格」。
- 附混淆矩阵与误判 Top5 例子（脱敏），供 F13 回流与提示词调整。

**⑤ 谁能用、多久跑一次、存哪**

- 使用人：算法（评估）、审核运营（抽检）、产品（决策依据）；原始评测集只读，不开放业务方随意复制。
- 频率：每次模型/提示词/词库版本变更前必跑；无变更时每月一次回归。
- 存放：样本与标注存 `eval_set` 表（版本、样本、标注、仲裁、冻结标记）；报告按「版本号 + 日期」命名存文档系统。
- 访问控制：样本含脱敏文本，按「只读 + 水印 + 下载审计」管理。

------

## 文档维护

| 项 | 规则 |
| --- | --- |
| 状态更新时机 | AI 模块新增 controller/service/provider 或接入真实模型时，同步更新第二、三、四章的状态符号 |
| 决策变更 | 只改 `AI-Business-Guide.md` §十六，再核对本文第十二章映射，不在本文另立决策 |
| 数据表变更 | 以 `docs/Sql-Document.md` §五 为准，本文只维护用途与关键列 |
| 禁止 | 不把设计态组件描述为"已实现"；不新增未经代码或文档确认的类名、表名、路径、配置键 |
