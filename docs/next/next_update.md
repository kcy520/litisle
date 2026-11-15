# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分
- 需环境实测的验证任务：见 [next\_verify.md](next_verify.md)
- 已知约束与风险（非待办）：见 [constraints-index.md](../constraints/constraints-index.md)

### 文档说明:

- 任务内容与落地摘要直接记录在本文档条目内，不引用 status.md 细案
- **代码与验证分文件**：纯代码改造、待决策、待外部条件留在本文档；需起服务/并发/真实流量/动库窗口/中间件就绪的验证任务统一放在 [next\_verify.md](next_verify.md)，同一任务只存一份（迁移是移动，不是复制）
- **状态标记约定**：任务完成只用 `- [x]`（随后按归档规则归档并留指针）；如无法使用，只允许正文中的子项 使用`✅` 。

### 文档模版

\*\* next\_update.md模版: \*\*

```
# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 任务内容与落地摘要直接记录在本文档条目内，不引用 status.md 细案
- 代码与验证分文件：验证项（V{n}）统一写入 next_verify.md，不在本文件挂载

### 文档模版

（本节：书写 next_update.md 的模版说明）

### 归档与暂不实现（板块完成后的文档迁移规则）

（沿用本文档既有迁移规则章节，新增文档原样保留）

--------------------------

## 待办部分

#### {模块名}模块

> 板块划分依据：{设计文档名，如 XX_Function_Develop.md / XX-Architecture.md}；推进顺序：板块一 → 二 → ...（有依赖的板块注明前置）

##### 板块{n}：{板块名称}

###### {n}.{m} {任务短标题}

- [ ] {任务内容一句话 + 关键口径/铁律 + 涉及的表/事件/常量}
  - ✅ {yyyy-MM-dd}：{落地摘要：核心类/方法 + 关键分支决策}；跨板块遗留：{遗留项（所属板块）}；编译验证已通过（{yyyy-MM-dd}）

------------

（每个板块之间用 ------------ 分隔线隔开）

##### V{n} {验证主题}（写入 next_verify.md，不在本文件挂载）

- [ ] {验证内容}（依赖{前置板块/模块}）

-------------------------

## 暂不实现汇总

#### {模块名}模块
```

### 归档与暂不实现（板块完成后的文档迁移规则）

**相关文档职责**：

| 文档                              | 职责                                                                                                                                                                       |
| ------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `docs/next/next_update.md`（本文档） | 仅收纳待办、代码改造、待决策、待外部条件任务，按 模块 → 板块/任务组织                                                                                                                                    |
| `docs/next/next_verify.md`      | 只收纳运行态验证/环境实测类任务（代码已改完待验证、必须实测才能定论），验完按下方规则归档                                                                                                                            |
| `docs/next/temporarily/`        | 收纳暂不实现/暂不做/挂起项（索引 Temporarily\_not\_implemented.md + temporarily-01\~04 正文文件，2026-10-28 拆分），每条必须附触发条件                                                                    |
| `docs/archive/`                 | 已完成板块的实现记录归档：按模块分子目录（如 `content/`、`review/`），每目录一个同名索引页 + 按板块拆分的板块文件（如 `content/content-09-temp-checklist.md`）；日期型归档（如 `20260929-implementation-records.md`）放 archive/ 根 |

**板块完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将该板块的实现步骤/完成记录（原散落在本文档条目内）整理移入 `docs/archive/` 对应归档文档（新模块新建归档子目录 + 同名索引页，遵循上方命名惯例；已有同模块归档则追加进对应板块文件，如临时清单批次追加进 content-09-temp-checklist.md）。
2. **本文档回填**：将已完成条目从本文档删除（或标注 ✅ 与日期后移除），保持本文档只留活跃待办。
3. **挂起项转移**：板块中因宿主模块未开发而无法落地的事项（如契约已备但入口未建），移入 `docs/next/temporarily/` 对应部分文件（代码类挂起进 temporarily-01-code.md，预留/暂不实现进 temporarily-04-deferred.md），并写明触发条件，供宿主模块开发时迁回本文档。
4. **预留项登记**：板块规划中明确「暂不实现/暂不做」的事项（含触发条件与决策原因），一并移入 temporarily-04-deferred.md，不在本文档保留。

> 纪律：迁移时条目内容原样搬运（含口径修正、注意事项），不压缩关键细节；归档文档与本文档/Temporarily 清单之间用相对路径互链，保证可回溯。

<br />

***

## 待办部分

#### content模块

> 板块划分依据：Content\_Function\_Develop.md / Content-Architecture.md；推进顺序：板块一 → 二 → 三 → 四/五/六（可并行）→ 七 → 八（依赖 review 板块三 3-5 消费端联调）

> **板块一\~八已全部完成并归档**（2026-10-20）：实现记录见 [content-implemention-records.md](../archive/content/content-implemention-records.md)（next\_update 条目摘要与实现细案，原样搬运；2026-10-28 拆分为九板块文件，本页为索引）
> 验证项 V1、V2 已迁入 [next\_verify.md](next_verify.md)（与 9.1 同名条目同源）；本文件不保留验证条目。

***

<br />

<br />

***

#### litisle-ai（AI）模块

> 板块划分依据：AI-Architecture.md（现状 + 设计态，D1–D25 决策映射见其 §十二）/ Design-Document.md §2.3.6；推进顺序：板块零（骨架）→ 一（基建）→ 二（Agent 底座 + 摘要/标签）→ 三（会话 + 润色/结构）→ 四（RAG 问答）；板块零/一内任务可并行
>
> 技术底座说明：Agent 化采用 LangChain4j AiServices（ChatAgent + ChatMemory + Tools / Function Calling），不引入新框架；摘要/标签等能力拆为 tools 由模型自主编排，区别于固定管道式 LLM 调用
>
> 口径说明：`AI-Business-Guide.md` 曾被清空，已于 2026-11-15 从 git 提交 0106d4a（871 行完整原版）恢复，并新增「§4.6 生成式流程（小悦 Agent）」「§5.3 计量幂等键修订」「§十七 Agent 化定档（P1–P10）」；D 决策口径恢复以业务指南为唯一来源
>
> 范围说明：本清单聚焦面向用户文章的小悦（Xiaoyue）生成式 Agent（简历导向优先）；图片判定端点、小审判定辅助、反馈采集、评测集不在本清单范围；需起服务/真实模型/中间件实测的任务按文档规则移入 next_verify.md，不在本文件挂载

##### 板块零：模块基础骨架接入（当前仅空壳，一切任务的前置）

###### 0.1 依赖与基础设施接入

- [x] litisle-ai 补齐基础依赖与配置：web / openfeign / mybatis-plus / mysql / redis / rabbitmq / nacos 注册与配置中心；确定服务端口；application.properties 迁移 yml 并接入 Nacos（数据源、Redis、MQ 连接均未配置）
  - 现状基线（AI-Architecture §2.1）：pom 仅 spring-boot-starter + test，controller/service/mapper/config 均不存在；本任务只立骨架不写业务
  - ✅ 2026-11-15：pom 补齐 web / openfeign(+loadbalancer) / mybatis-plus / mysql / data-redis(+pool2) / amqp / nacos discovery+config / lombok；端口定 8087（顺延 gateway 8080~search 8086）；application.properties 删除（经确认），迁移为 application.yml + application-local.yaml（profile local，Nacos 接入与 review 同构：config.import litisle-ai.yaml + litisle-shared.yaml，db=litisle_ai）；只立骨架未写业务——@EnableFeignClients 与 litisle-api 依赖随板块一 1.2 接入时补，MQ 消费侧配置随板块四落地；编译验证已通过（2026-11-15，mvn -pl litisle-ai clean compile）

##### 板块一：模型通道与计量配额基建（后续板块的共同前置）

###### 1.1 OpenAI 兼容模型接入通道（provider 层）

- [ ] 新建 provider/text 包实现统一文本模型通道：base-url / api-key / model / 超时 / 重试参数全部外置到配置（yml/Nacos），代码内不硬编码模型端点；prompt 模板与 prompt_version 常量集中管理（文本场景 prompt_version 由 model_version 承载）；provider 是唯一接触模型 SDK/HTTP 的层，controller/service 不直连模型
  - 业务逻辑：单一 chat 入口收 system+user 双段 prompt → 出站前按最小必要原则过滤（禁止附带用户身份信息）→ 调用失败 fail-fast 上抛，不静默吞错、不默认放行；日志只留标识/指纹/必要片段，不打印正文全文

###### 1.2 用途打标契约扩展（实施落 litisle-api）

- [ ] litisle-api `dto/ai` 包新增 AiPurposeEnum（MODERATION_TEXT / MODERATION_IMAGE / SUMMARY / TAGGING / QA / OTHER 六值）；AiChatRequestDTO 增 purpose（必传，缺省端点侧拒绝并告警，防「忘传」被默认归机审）、requestId（业务侧生成的幂等键，重试复用同一键）、scene（可空）
  - 计费与预算只认前五类；OTHER 仅兜底落明细、不计入预算与报表，出现即按「未归类调用」告警；用途由调用方显式传入，不靠 URL/模型名/表名推断

###### 1.3 Token 计量与预算分账基建

- [ ] 落地 ai_token_usage / ai_usage_daily / ai_pricing 三表实体与 Mapper（表结构已建：`docker/init/mysql/schema/ai-schema.sql`，2026-11-09）；实现 AiUsageRecordService 写明细、AiBudgetService 预算桶判定、AiUsageDailyAggregateTask 定时聚合
  - 明细口径：幂等键 = requestId + 调用序号（callSeq）联合唯一——一次业务请求可触发多次模型调用（Agent 多轮、机审切片），每轮/每片各落一条明细；重试复用同一 callSeq 键不重复计费（修正架构文档 §8.3④「request_id 唯一」的口径：该设计只覆盖了切片场景，Agent 多轮会撞键漏计；落地时须回写架构文档）；失败调用也落一条 status=FAIL（避免「失败即无成本」）；归属日期按调用完成时间，跨天重试只计一次成功成本；成本 = token 数 × ai_pricing 单价（两位小数），单价配置变更只影响未来日期
  - 用户级配额（D5/V8）：按 用户×用途×日 校验次数/Token 上限，普通低配、按等级放开、VIP 高额度非不限量；超额拒绝并提示「今日额度已用完」；数值待定档（Q39），未定档前仅计量不拦截
  - 预算口径（D15 分账）：机审桶 = 文本+图片合并；SUMMARY/TAGGING/QA 各自独立桶；判定读 ai_usage_daily 当日合计、本地缓存 60 秒，不在每次调用读写预算键；返回 ALLOWED / WARN(≥80%) / EXCEEDED(100%)；预算值来自 AiBudgetProperties，≤0 表示未设预算只计量不限额；100% 只停该用途新请求（机审桶到 100% 不停调用、转全量人工+告警），生成被停返回明确错误码，不静默返回空结果
  - 聚合口径：每小时 + 启动延迟一次，对当日与前一日幂等覆盖写（保证预算判定当日数据可用）；明细与汇总差异 >1% 记日志告警

###### 1.4 内部 Feign 端点（解锁 review 文本机审闭环）

- [ ] 实现 POST /api/feign/ai/chat 提供方端点（契约 AiClient.chat 与 AiClientFallbackFactory fail-fast 已在 litisle-api 实现，AI 侧当前无 controller）；AI 只回结构化判定结论，防注入包裹与白名单三分态映射由 review 侧组织；接线后 review 文本机审从「提供方缺失→全量转人工」恢复为 LLM 判定
  - 跨模块牵连（归属 review 模块，需在 review 侧另行登记）：SPI ContentModerationService.moderateText 改收 TextModerationRequest；requestId = taskId:chunkIndex（分片确定性切分，延迟重试复用同一 requestId 满足「重试不重复计费」）；purpose 固定 MODERATION_TEXT；scene 取 review_task.scene

###### 1.5 AI 侧指标与日志字段统一

- [ ] 建立指标打点：ai_call_total{purpose,provider,result} / ai_call_latency{purpose}（Timer，P50/P95/P99）/ ai_tokens_total{purpose} / ai_quota_exceeded_total{purpose} / ai_generation_total{scene,result} / ai_guard_state{provider}；命名沿用 review 的 moderation_* 风格（代码侧点号、Prometheus 下划线）
  - 按用户用量不加指标标签（防高基数），统一由 ai_usage_daily 承担；日志字段统一 traceId / purpose / modelVersion / costMs / tokenIn / tokenOut / result，禁止打印正文全文、私信内容与用户身份信息

------------

##### 板块二：小悦 Agent 底座——AiServices 装配 + 工具注册 + 意图路由（Agent 化核心）

###### 2.1 LangChain4j Agent 底座装配

- [ ] 引入 LangChain4j，用 AiServices 装配小悦 ChatAgent：system prompt（角色定位 + 能力边界 + 建议态约束）+ ChatMemory 接口（实现见 3.2）+ Tools 注册；模型调用复用 1.1 provider 层；Agent 不直接写业务库，产出一律为建议态，落库决策在 service
  - 业务逻辑：用户消息 → Agent 推理（自主决定调哪个工具、调几次）→ 工具结果回喂 → 终答；Agent 循环设上限（初值 5 轮）防死循环与成本放大；工具失败作为观察结果回喂模型自行调整，不直接中断会话

###### 2.2 能力工具注册（Function Calling）

- [ ] 将小悦的能力拆成 tools 注册给 Agent：①取当前稿件标题+正文（经 ContentClient 回查）②查标签池候选（经 ContentClient）③敏感词预检（复用既有 DFA 闸门能力；若无内部端点则先占位，闸门兜底仍由调用方执行）④知识库检索（板块四落地前注册占位实现，未就绪返回「工具暂不可用」，模型改走无检索回答）
  - 铁律：工具只读、不写业务表；工具出入参日志不带正文全文；purpose 按意图打标（SUMMARY/TAGGING/QA），计入 1.3 计量预算
  - 出站红线（Q63 已定档，2026-11-15）：选「边界修订」路线——未公开稿件经**脱敏过滤器**（手机号/邮箱/身份证/外部链接）后，外发给签约合规的国内模型供应商（API 协议含数据处理条款），并在用户协议中告知同意；脱敏在 AI 供给层（provider 出站前）统一执行；不搞私有化部署；稿件外发保留配置开关，可一键关闭

###### 2.3 意图路由与降级直连

- [ ] 单一入口 POST /ai/xiaoyue/chat：Agent 首轮判断意图（摘要/标签/润色/结构分析/站内问答/闲聊）自主路由；原设计锚点端点（/ai/xiaoyue/summary、/tag 等）保留为固定管道直连通道，Agent 循环异常时降级走直连
  - 降级铁律：Agent 循环超限/模型不可用 → 降级为固定管道或「暂时无法生成」提示（可手动填写，不落库）；purpose 打标与预算判定在路由后即执行，降级链路同样计量

###### 2.4 结构化输出与自校验循环

- [ ] 标签候选、摘要等结构化产出要求模型输出 JSON：解析失败或校验不过（标签越池、摘要超 500 字）时，把具体错误信息回喂模型重试 1 次（最小反思闭环）；仍失败降级为失败提示，不落库
  - 重试预算固定 1 次，防止反思循环成本放大；校验规则（池内白名单、长度上限）在 service 侧执行，不依赖模型自觉

###### 2.5 摘要与标签建议（Agent 编排实现）

- [ ] 在 Agent 底座上实现摘要（≤500 字）与标签建议：由模型经 2.2 工具自主取稿、查池、预检后产出建议
  - 建议态铁律（D4）：不自动写入，作者编辑/确认后由 content 落库；未确认则丢弃不落库
  - 展示铁律（D3/D13）：响应带「AI 生成/AI 建议」标识；展示前须过 DFA 闸门（闸门由调用方 content/review 执行，机制已有）；摘要不进正文指纹、不触发正文重审
  - 失败语义：失败重试 1 次 → 终态 → 退还预扣配额，同一 requestId 只扣一次
  - 标签铁律：AI 不新造标签，prompt + 越池校验双重约束；新标签只能作者走正常创建流程

------------

##### 板块三：小悦有状态 Agent——会话记忆与润色/结构分析

###### 3.1 会话与消息实体 + 会话治理

- [ ] ai_session / ai_message 实体与 Mapper（DDL 以 Sql-Document §五为准）；GET /ai/sessions 会话列表
  - 治理铁律（D25）：留存 90 天；90 天内 200 会话上限、单会话 500 条上限，超限按最久未活跃（last_active_time）LRU 淘汰（is_lru 标记）
  - 删除铁律：支持用户删除——删除请求由 AI 侧受理（会话数据持有方），异步任务 + 完成回执 + 审计记录；懒删之外补定时兜底清扫，防用户停用后超期留存（R9 缺口）

###### 3.2 Redis 会话记忆（ChatMemoryStore 实现）

- [ ] 实现 LangChain4j ChatMemoryStore 的 Redis 版（衔接 2.1 底座）：会话上下文暂存（Design-Document §2.3.6 口径：LRU 暂存 10/30 + 已保存 100/200）；Memory 不可用降级为无记忆单轮回答并明确标注，不静默丢上下文

###### 3.3 润色与结构分析端点

- [ ] POST /ai/xiaoyue/polish、POST /ai/xiaoyue/analyze-structure：润色建议与结构分析建议，带 session_id 走 3.2 Memory 支持多轮；同时作为 2.3 意图路由的分流目标之一；仅作者可见（建议态），同 2.5 的闸门/标识/计量/幂等口径

------------

##### 板块四：RAG 知识库与站内问答（引用可溯源）

###### 4.1 知识库摄入链路

- [ ] mq/RagIngestConsumer 监听 content.published / content.unpublished（AI 侧当前不生产不消费 MQ，新增事件须在 AI-Architecture §7.3 与 Api-Architecture 同步登记）→ 质量门控 → 仅摄入 visibility=PUBLIC 公开文章（未公开内容不外发，与 D2/V3 出站红线同口径）→ ContentChunker 正文清洗 + 切片（500 字/段、重叠 50 字）→ EmbeddingClient → 写 ES 索引 article_chunks（dense_vector 向量 + 元数据）
  - 摄入异步解耦不阻塞发布；下架/删文同步移除 chunks；文章改稿只更新元数据不重建切片（search 批次A A2 定案），改稿一致性由检索侧版本比对兜底（见 4.2）

###### 4.2 检索转正与问答分流链路

- [ ] 知识库检索工具（2.2 工具④）由占位转正：Embedding → TopK 检索 + 相似度阈值过滤 → 引用可见性回查 → 组 prompt → LLM → 回答 + 引用卡片；问答作为 2.3 意图路由的分流目标之一，复用单一入口 /ai/xiaoyue/chat，**不另设独立端点**（消除与本清单 2.3 的路径双重定义）
  - 引用铁律：引用必须可溯源；引用返回带文章 updateTime/版本号，与切片构建时版本比对，不一致则移除引用或标注「内容已更新」（Q75 口径）；引用内容下架即失效
  - 降级铁律（Q54）：RAG 不可用降级为无引用回答并显著标注「未引用站内内容」；涉及事实性结论只答「暂无相关资料」，不得混入模型自身知识冒充站内回答
  - 匿名问答配额：Redis 日计数键 `ai:quota:anon:{ip哈希}`（定档见 P5，2026-11-15）；purpose=QA 走计量预算链路；模型原始输出不直接展示，只出结构化回答

##### 待决策与外部前置（2026-11-15 全部定档；模型 api-key 为唯一剩余外部条件）

| 编号 | 事项 | 定档口径（2026-11-15） | 影响范围 |
| --- | --- | --- | --- |
| P1 | 出站红线（Q63） | 选「边界修订」：未公开稿件经脱敏（手机号/邮箱/身份证/外部链接）后外发合规国内供应商（API 协议含数据处理条款）+ 用户协议告知；脱敏在 provider 出站前统一执行；不私有化；外发保留配置开关 | 板块二/三 |
| P2 | 模型选型 | DeepSeek API（OpenAI 兼容、国内直连）+ LangChain4j 官方 BOM + spring-boot-starter 自动装配；换供应商只改 yml（1.1 配置外置保证） | 0.1、1.1、板块二 |
| P3 | Embedding | 本地 Ollama + bge-m3（1024 维，零外发零成本），云 API（智谱 embedding-3）兜底；DeepSeek 无 embedding，必须独立选型 | 4.1 |
| P4 | 会话上限口径 | 分层定档：D25（90 天/200 会话/单会话 500 条）为**持久化上限**；Design-Document 的 10/30 为**进 prompt 的 ChatMemory 滑窗**（windowSize 取最近 N 条）——DB 全量存 + Memory 滑窗，两者不同层不冲突 | 3.1/3.2 |
| P5 | 匿名问答配额 | Redis 日计数键 `ai:quota:anon:{ip哈希}`；计量明细 user_id 空 + scene 记 IP 哈希（脱敏）；数值先写常量、后外置配置 | 4.2、1.3 |
| P6 | 路由打标口径 | 不做独立路由 LLM 调用：purpose 由功能入口（调用方）预传入，Agent 循环继承该值；路由靠入口信息而非模型分类；OTHER 兜底 | 2.3、1.3 |
| P7 | 建议态存活期 | Redis 缓存建议结果，key=requestId，TTL 30 分钟；刷新/多端可回查，过期即「未确认丢弃」 | 2.5/3.3 |
| P8 | content.published 事件核实 | ✅ 已闭环（2026-11-15 代码核实）：事件已存在——MqConstant 定义 `content.published`/`content.unpublished` 路由键、ContentPublishedEvent 已在 litisle-api、content 发布且 search 已有消费先例；AI 直接消费，正文经 ContentClient 回查，无需新增契约 | 4.1 |
| P9 | ES 实例与索引归属 | 共用 ES 实例，AI 自建自管 `article_chunks` 索引（索引名隔离）；与 D9 不冲突——RAG 知识库不是站内检索，与 search 的 article_idx 互不干涉 | 4.1 |
| P10 | 一次性生成与会话额度 | 直连端点（/summary、/tag 等）**不建会话**：不写 ai_session/ai_message，Memory 仅服务 /chat 多轮入口；生成留痕由计量明细承担 | 2.5/3.1 |

> 剩余外部条件：DeepSeek / Ollama 服务可用与 api-key 配置（联调前置，不阻塞编码）

***

## 暂不实现汇总

#### content模块

#### search模块

- ~~user\_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user\_idx），此项从「暂不实现汇总」移出，原触发条件已满足
