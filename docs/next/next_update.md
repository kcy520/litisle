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

> 板块划分依据：AI-Architecture.md（现状 + 设计态，D1–D25 决策映射见其 §十二）/ Design-Document.md §2.3.6 / AI-Business-Guide.md F6/F8/F9/§5.2 / temp.md 两立项（2026-11-16 迁入板块八~十四）；推进顺序：板块零（骨架）→ 一（基建）→ 二（Agent 底座 + 摘要/标签）→ 三（会话 + 润色/结构）→ 四（RAG 问答）→ 五（F8 相似文章推荐）→ 六（用量可观测与模型自检）→ 八（条件化相似推荐）→ 九（Agent 交互扩展）→ 十/十一/十二/十三/十四（交互编排与创作辅助，见各板块）→ 十五（写作辅助扩展——多级改写与草稿，依赖板块二 Agent 底座，可与五/六并行）→ 七（文档收尾与登记，收口全部板块后执行）；板块零/一内任务可并行

> **板块零\~四已全部完成并归档**（2026-11-15）：实现记录见 [ai-implemention-records.md](../archive/ai/ai-implemention-records.md)（next\_update 条目摘要与实现细案原样搬运；本页为索引，正文按板块拆分五文件；P1–P10 Agent 化定档决策表随索引页归档）
> 运行态验证项已迁入 [next\_verify.md](next_verify.md)（AI-V1 RAG 端到端联调 / AI-V2 会话与计量 / AI-V3 相似推荐与用量出账）；挂起项与跨板块遗留已登记 [temporarily-01-code.md](temporarily/temporarily-01-code.md)「litisle-ai 模块」节（网关登记/架构文档登记/D15 修订/XIAOSHEN 预留/已保存区预留/计量明细清理）；本文件不保留 AI 验证条目。

> **2026-11-16 增补板块八~十四**（[temp.md](../temp/temp.md) 两个 Agent 立项整体转正式，任务编号沿用原批次编号不重排）：推进顺序——P0 拍板项（维度注册表词表定稿 / 入口形态 / 打标 purpose 归桶，均在条目内标注待讨论）→ 板块八（条件提取管道）→ search 模块板块二/三（标签槽 + 条件版查询）→ 板块九（交互扩展地基+动作+读后，契约核对类子项可并行提前核）→ 板块十（多轮调条件）→ 板块十一（对比/收藏检索，依赖 search 板块三/四）→ 板块十二（创作辅助，依赖 content 创作侧入口，4.2/4.4 契约核对可提前核）→ 板块十三（能力发现，零 LLM，可与板块八并行）→ 板块十四（读前预览+组合编排，6.3~6.5 依赖契约核对，可并行提前核）；验证项 AI-V4~V8 已迁入 [next\_verify.md](next_verify.md)

##### 板块五：F8 相似文章推荐（复用板块四 RAG 底座，纯检索形态零生成成本）

> **板块五 5.1/5.2 已于 2026-11-15 全部落地**（实现记录见本板块条目 ✅ 摘要；运行态验证走 [next\_verify.md](next_verify.md) AI-V3；板块归档迁移与架构文档登记随板块六/七批次一并执行）
> 注意：[AI-Business-Guide.md](../Study/AI-Business-Guide.md) 当前为空文件（2026-11-15 发现），§5.3 计量口径扩充核对（板块七 7.2）前需先恢复该文档内容。

###### 5.1 相似文章推荐端点

- [x] POST /ai/xiaoyue/similar（设计锚点，落地前在 Api-Architecture 定稿）：按文章向量在自管索引 article\_chunks\_ai 做 knn 近邻检索，返回相似公开文章卡片列表；**默认不调 LLM，零生成成本**
  - 业务逻辑：ContentClient 取稿 → 标题+摘要（或正文首块）向量化（本地 Ollama bge-m3）→ knnSearch TopK + min-score 阈值过滤 → 排除自身文章（articleId）→ 复用 verifyChunk 同口径逐片回查 → 返回卡片（articleId/标题/作者/相似度）
  - 可见性铁律（与 §四引用回查同双保险口径）：只返回当前已发布且公开的文章——检索命中不代表可推荐，回查失败/非已发布/非公开即移除；contentHash 与摄入时不一致标注「内容已更新」；下架即失效
  - 边界铁律（F8）：只做相似检索，不输出对作者的负面评价性结论；无命中 / rag.enabled=false → 返回空列表明确响应，不报错不降级抛错
  - 计量：purpose 新增 SIMILAR 值（扩 AiPurposeEnum，同步扩预算桶与 D15 修订挂起项核对）；纯检索形态 token=0 仍落一条明细保证可观测；embedding 为本地模型不产生外部费用
  - ✅ 2026-11-15：AiPurposeEnum 扩 SIMILAR（枚举注释同步分账桶口径）+ AiBudgetService/AiBudgetProperties/AiQuotaProperties/AiUserQuotaService 四处桶映射同步；AiRagProperties 增 Similar 配置组（topK/minScore）；RagSearchService.similarArticles（knn 3×topK 切片召回防同篇挤占 → minScore 过滤 + LinkedHashMap 按篇去重取最高分 + 排除自身 → verifyForSimilar 同口径回查改经 getArticleIndexDoc 一次取齐状态/可见性/contentHash/作者昵称，回查失败/非公开移除、hash 不一致标 outdated）；SimilarRecommendService 编排（ObjectProvider 容忍 rag.enabled=false Bean 缺席 → 空列表；取稿失败/检索异常降级空列表并落 FAIL 明细；查询文本=标题+正文首块 500 字截断）；XiaoyueController POST /ai/xiaoyue/similar 返回 Result\<List\<SimilarArticleCard>>；计量：纯检索 token=0 落一条明细（callSeq=1，modelVersion=ollamaModel）；跨板块遗留：网关 /api/ai/\*\* 前缀与端点登记（板块七 7.1）；AI-Business-Guide.md 当前为空文件，§计量口径「五类」扩充核对受阻（板块七 7.2 前需先恢复该文档）；编译验证已通过（2026-11-15）

###### 5.2 推荐理由生成（可选增强，默认关闭）

- [x] 配置开关 similar.reason-enabled=false：开启时对 TopN 相似文章按需生成一句话推荐理由（结构化输出 + SuggestionValidator 自校验，purpose=SIMILAR 走计量与预算）；默认关闭，避免每次推荐都产生生成成本
  - ✅ 2026-11-15：AiRagProperties.Similar 增 reason-enabled=false/reason-top-n=3/reason-max-chars=60；AiPromptConstants.SIMILAR\_REASON\_PROMPT（主题相关性一句话，禁评价优劣/负面评价——F8 边界铁律入 prompt）；SuggestionValidator.validateReasons（index 范围 1..TopN + 字数上限校验，缺条目不判失败）+ ReasonItem 载体；SimilarRecommendService.fillReasons（预算 EXCEEDED 只跳过理由不报错、记 ai\_quota\_exceeded\_total；结构化回喂重试复用 structuredRetryBudget，耗尽静默返回无理由卡片；成功按 callSeq=2 落 SIMILAR 计量明细 + ai\_call\_total/ai\_call\_latency/ai\_generation\_total 打点）；yml 增 litisle.ai.rag.similar.\* 与 budget/quota similar 桶；编译验证已通过（2026-11-15）

***

##### 板块六：用量可观测与模型自检（F9 收口 + §5.2 口径，无板块依赖可提前做）

###### 6.1 用量查询与出账端点（F9 收口）

- [ ] 用户侧 GET /ai/usage/me（剩余配额、当日用量、花费统计）+ 运营侧按用途/按日汇总查询（F9 验收「后台可按场景出账，单日花费可对账」）：数据源 ai\_usage\_daily / ai\_token\_usage，只差端点与汇总组装
  - 口径：用户侧只能查自己的数据（跨用户按不存在处理，与会话归属校验同口径）；花费 = token × ai\_pricing 单价（与明细口径一致，两位小数）；匿名调用（user\_id 空）不进用户侧查询，只进运营汇总（scene 记 IP 哈希）；明细查询默认限当日或近 7 天，防深翻明细大查询；按用途/按日聚合复用 ai\_usage\_daily，不重复建汇总逻辑

###### 6.2 模型可用性自检与自动切回（§5.2 口径）

- [ ] 实现模型通道运行时自检：连续失败 N 次（阈值外置）判定「确定不可用」进入熔断期——熔断期内调用直接失败不再逐条请求模型（机审侧由 review 转人工语义兜底，生成侧抛 80005）；熔断到期后半开探测一次，成功自动恢复切回
  - 口径：自检状态收口在 provider 层（唯一接触模型的层），机审与 Agent 全通道共用；区分「单次失败」（照常 fail-fast 上抛，走既有重试语义）与「确定不可用」（熔断跳过逐条白等）；恢复无需人工干预
  - 业务依据（AI-Business-Guide §5.2）：避免「模型挂了每条还白等约 15 分钟并持续无效调用」；自检恢复后自动切回
  - 配套：补齐 ai\_guard\_state{provider} 护栏指标（板块一 1.5 简化时未建）

***

##### 板块七：文档收尾与登记（板块五/六落地后的配套，契约与文档类）

###### 7.1 新端点登记与网关前缀定稿

- [ ] Api-Architecture 定稿 /ai/xiaoyue/similar、/ai/usage/me 及运营侧用量端点的路径与出入参；网关 /api/ai/\*\* 前缀登记（原 temporarily-01「网关登记/架构文档登记」挂起项随板块五/六完成触发，落地后从挂起清单移除）

###### 7.2 purpose 枚举与预算桶扩展（D15 修订收口）

- [ ] AiPurposeEnum 扩 SIMILAR（六值 → 七值）+ AiBudgetProperties 扩桶 + AI-Business-Guide §5.3「该五类为计量打标的唯一定义」同步更新为六类；D15 修订挂起项随本任务关闭

###### 7.3 架构文档状态同步

- [ ] AI-Architecture §2.3 / §3.2 / §4.1 / §5.5 / §7.1 登记板块五/六新增能力与类（RagSearchService.similarArticles、用量查询服务、provider 层熔断自检、ai\_guard\_state 指标、/ai/xiaoyue/similar 与用量端点），状态符号随实现推进由 🟡 改 ✅

***

##### 板块八：条件化相似推荐——条件提取管道（L0/L1 分层路由）（2026-11-16 迁自 temp.md 第一立项 AI 批次一）

> 方案：L0/L1 分层（无自由条件直通固定接口 0 次 LLM；带条件 1 次 LLM 结构化提取 → 条件版检索）+ 四类维度 match（filter 词表 / range 数值 / param 参数 / semantic 语义）+ ES ai_labels 动态标签槽（索引期 LLM 打标，查询期零 LLM）+ 维度注册表（配置驱动打标与提取两端，新维度零代码）
> 原则：成本红线——无自由输入绝不调 LLM；查询期基线 1 次 LLM，按「门控升级阶梯」按需递增（单请求 LLM 硬上限 3 次，钱只花在结果不好的时候）；提取失败降级 L0 不报错；打标失败不阻断索引主链路。**部分决策项待讨论（各条目内标注），定稿后补入口径再开工**
> search 侧配套（维度注册表 / ai_labels 标签槽 / 条件版查询）见 search 模块板块二/三

###### 1.1 入口扩展与静态路由

- [ ] POST /ai/xiaoyue/similar 请求体增 freeText 可空字段：空 → L0 直通转发 SearchClient#similar(articleId)（0 次 LLM，AI 不落计量明细）；非空 → L1 条件管道；提取失败/超时降级 L0 + 响应标注「未识别自定义条件」，不报错
  - 静态路由本身零成本（freeText 是否为空的 if 判断，不需要 LLM 判意图）
  - 待讨论：入口形态（复用 /similar 加 freeText 字段 vs 独立新端点，倾向前者）

###### 1.2 结构化条件提取（固定管道，不用 Agent 循环）

- [ ] 1 次 LLM 调用输出统一 JSON：queryText（主旨+风格合并语义文本，含否定语义如"不要标题党"）/ authorName / excludeAuthorName / category / tags / style / topK / wordCount(min,max) / timeRange（发布时间范围，映射既有 publishTime 字段）/ sortHint（related|newest|heat|similarity-first——similarity-first 表示融合权重置纯向量）；service 侧按维度注册表校验（体裁越枚举、wordCount min>max、timeRange 起止倒置、topK 超上限 → 具体错误回喂重试 1 次，复用板块二 2.4 自校验模式）；全字段空 = 无有效条件 → 降级 L0
  - 铁律：**不用 Agent 循环**——条件提取是单轮任务，5 轮循环是纯成本浪费；提取 prompt 由维度注册表动态拼装（新维度零代码）；输出 schema 与注册表 match 类型一一对应
  - 作者名→ID 解析走 SearchClient 查 user_idx（普通 Feign 查询，非 LLM）
  - 阅读时长换算（"5 分钟能读完的"）：提取层按 ~400 字/分钟换算为 wordCount range（约 2000 字），**不加独立字段**——2026-11-15 实用性×成本筛选结论
  - 多轮调条件（会话式"再轻松一点"）：**2026-11-15 已拉回**，随板块十落地（会话记忆 + 增量改条件），原 temporarily-04 挂起项随之关闭

###### 1.3 打标端点（供 search 索引链路调用）

- [ ] POST /api/feign/ai/label（内部 Feign，网关不路由）：收文章标题+正文 → 1 次 LLM 按注册表词表输出全部维度标签 JSON（低置信输出 null 不硬造）→ 返回 ai_labels；失败 fail-fast 上抛，重试语义由 search 消费侧承担
  - 计量：走 AI 计量明细落痕；purpose 归桶待定档（复用 TAGGING vs 新增管线类用途——**待讨论**）
  - 依赖：provider.text.enabled=true（LLM 通道就绪）

***

##### 板块九：Agent 交互扩展——会话结果集地基 + 结果动作 + 读后服务（2026-11-16 迁自 temp.md 第二立项 AI 批次一）

> 方案：一档四项——结果动作（0 次 LLM 工具路由）/ 读后摘要 / 读后追问（复用板块四 RAG 底座）/ 多轮调条件（会话记忆增量改条件）；二档两项——多文对比、收藏夹语义检索（订阅提醒不做）
> 2026-11-16 追加七项：板块九增 1.5 大纲导航 / 1.6 术语卡片 / 1.7 批判性阅读陪聊 / 1.8 金句摘录卡（契约依赖）；板块十二增 4.6 风格仿写辅助 / 4.7 标题/开头多版建议；板块十四增 6.5 继续阅读（契约依赖，与 6.4 同源）
> 原则：公共地基先行（会话结果集序号映射，所有"对结果下指令"类功能的前提）；动作类 0 生成成本、生成类走既有 purpose 计量与预算桶；契约核对前置（content 收藏/书单接口存在性，缺则登记 temporarily）；工具挂 XiaoyueAgent Function Calling 底座——固定管道（板块八）与工具编排两形态并存

###### 1.1 会话结果集映射（公共地基）

- [ ] 会话上下文维护「最近一次推荐/检索结果集」的 articleId→序号 映射（复用板块三会话存储，TTL 外置过期失效）；跨会话不可引用，过期后引用序号 → 明确提示重新发起检索，不猜不编造

###### 1.2 收藏/书单动作工具（0 次 LLM 生成成本）

- [ ] XiaoyueAgent 新增动作工具：解析"收藏第 2、3 篇/第 1 篇加入书单"→ 经 1.1 序号映射定位 articleId → 调 content 收藏/书单契约；动作失败明确响应不静默吞掉
  - **0 次生成成本**（意图路由后纯契约调用，不落 AI 计量明细）
  - 前置核对：content 收藏接口 / 书单接口是否已存在（核对结果决定本任务是否转 temporarily 挂起，触发条件写明缺哪个契约）

###### 1.3 读后摘要工具

- [ ] "第 1 篇讲了什么/帮我提炼要点"：按序号取正文 → 复用板块二摘要链路（purpose=SUMMARY 计量与预算照常）；长文按既有截断口径；文章不可见/已下架 → 明确响应

###### 1.4 读后追问工具（单文 RAG）

- [ ] "文中说的 X 是什么意思/这篇的论据有哪些"：复用 article_chunks_ai 按 articleId 过滤检索该篇切片 → 生成回答带引用（verifyChunk 引用回查铁律照搬：非已发布/非公开/contentHash 不一致即剔除）；问题超出单文范围 → 引导走全文问答入口，不混用全库检索

###### 1.5 文章大纲导航（2026-11-16 追加）

- [ ] "这篇的结构是什么/给我列个提纲"：复用 article_chunks_ai 按 articleId 取切片 → 拼装文章大纲（0~1 次 LLM，切片标题信息完整时零生成）；文章不可见/已下架 → 明确响应

###### 1.6 术语卡片（2026-11-16 追加）

- [ ] "这篇里的 X 是什么意思/这篇有哪些术语"：1.4 单文 RAG 轻量变体——抽提该文术语清单，逐个按切片上下文解释（1 次 LLM）；术语不在该文范围 → 明确说明不编造

###### 1.7 批判性阅读陪聊（2026-11-16 追加）

- [ ] "我觉得这篇观点有问题，帮我辩一辩"：1.4 读后追问的对话化延伸——检索该篇切片 + 平衡呈现双方论点（不预设立场、不迎合用户）；每轮 1 次 LLM，purpose 计量照常（桶位随 1.3/1.4 计量定档对齐）

###### 1.8 金句摘录卡（2026-11-16 追加，契约依赖）

- [ ] "把这篇的精华句子都摘出来"：单文切片检索 → 0~1 次 LLM 抽取原文金句（只摘原文不改写，不编造）；保存/落库依赖 content 笔记/摘录契约
  - 前置核对：content 笔记/摘录契约是否可经 Feign 复用（缺则转 temporarily 挂起，触发条件写明缺哪个契约；纯会话内展示不落库可先行）

***

##### 板块十：多轮调条件（原 temporarily-04 挂起项拉回）

###### 2.1 会话记忆增量改条件

- [ ] "再轻松一点/时间改成最近半年"：会话内保存上一轮提取 JSON → 本轮 LLM 输出增量修改 → 合并后走注册表校验与条件检索（复用板块八管道）；每轮仍 1 次 LLM；首轮无上下文时按普通提取处理；合并冲突（本轮否定上轮条件）以上轮为准由本轮覆盖

***

##### 板块十一：多文对比与收藏夹检索（二档）

###### 3.1 多文对比工具

- [ ] "对比这 3 篇的观点差异"：序号定位 ≤3 篇 → 各取摘要级内容 → 一次对比生成（purpose 走既有桶，token 随篇数涨由预算桶管控）；超 3 篇 → 明确告知上限，不静默截断

###### 3.2 收藏夹语义检索

- [ ] "我收藏过的讲 X 的文章"：拉取用户收藏列表 → SearchClient 条件版查询（新增 includeArticleIds 白名单 filter，见 search 板块四）+ queryText 语义 knn → 只返回已收藏且当前公开的文章；收藏为空/检索无命中 → 空列表明确响应
  - 依赖：search 条件版查询契约（search 板块三）+ 白名单扩展（search 板块四）

***

##### 板块十二：检索编排与创作辅助扩展（2026-11-15 追加，复用板块九~十一底座，边际成本≈0）

###### 4.1 找系列/前作（0 次 LLM）

- [ ] "这篇有前作吗/同系列还有哪些"：条件版检索管道组合查询——同作者 filter + 标题/语义相似 knn；结果按「同作者系列」标注分组；无命中 → 空列表明确响应

###### 4.2 发布前查重（创作者向）

- [ ] 创作侧发布前调用相似检索（复用板块八条件版管道换调用时机），相似度超阈值（配置外置）提示与库内已有文章的重复风险；0~1 次 LLM（仅用户追问"哪里相似"时生成说明）
  - 衔接点：content 创作页触发入口（发布前检查按钮/流程节点，入口建设归 content 侧）；review 模块可参考其结果但不强制依赖（独立功能，非机审环节）

###### 4.3 读者预设问题（创作者向）

- [ ] "读者读我这篇可能会问什么"：复用 1.4 读后追问的单文 RAG 底座（同检索、换 prompt 与输出形态）→ 生成 N 个预设 Q&A 供作者发布前自检；purpose 计量照常

###### 4.4 选题灵感（创作者向）

- [ ] "我这个领域最近缺什么角度/什么话题热"：热榜/分区热度数据（search 既有能力）作为上下文 + 一次生成分析；响应固定标注「仅参考」；purpose 计量照常
  - 前置核对：search 热榜查询契约是否可经 Feign 复用（缺则本项转 temporarily 挂起，触发条件写明缺哪个契约）

###### 4.5 跨文观点对比（并入 3.1，不单独立项）

- [ ] "这两篇的说法矛盾吗"：作为 3.1 多文对比的子场景处理（同底座同流程），随 AI-V5 ⑥ 验收补一条对比矛盾性用例

###### 4.6 风格仿写辅助（2026-11-16 追加）

- [ ] "用这篇的笔法帮我写个开头"：按序号取参考文章 → ai_labels.style 取风格标签作生成约束 + 1 次生成——把已有标签从"筛选用"变"生成用"；参考文章需当前公开可见，生成结果标注「仅参考」；purpose 计量照常

###### 4.7 标题/开头多版建议（2026-11-16 追加）

- [ ] "给我 3 个替代标题/换个开头"：与 4.3 预设问题同批的创作自检——取本人文章内容 → 1 次生成 N 版建议（N 外置配置）；文章归属校验，非本人所有明确拒绝（对齐 AI-V6 ⑤ 口径）；purpose 计量照常

***

##### 板块十三：能力发现与引导（2026-11-16 追加，零 LLM 成本）

> 解决"用户不知道 Agent 能做什么/不能做什么"——主流四板斧取三（结果即时提示 suggestions 字段暂不做，触发条件：实测冷启动引导不足）

###### 5.1 冷启动快捷指令（suggestion chips）

- [ ] 空会话/助手入口展示预置问法清单（配置化，随功能上线同步维护）：「找轻松风格的用户增长文章」「收藏第 2 篇」「这篇有前作吗」「对比前 3 篇」等高频能力示例；零 LLM 成本

###### 5.2 能力清单自动生成（单一口径）

- [ ] 工具注册表双端消费：XiaoyueTools 的 description 本为 Function Calling 路由必填，同一注册表渲染前端「能力面板」（新增 GET /ai/xiaoyue/capabilities 返回能力清单）——**新增工具自动出现在面板，双端不重复维护**；与维度注册表同设计哲学（一处声明，两端消费）

###### 5.3 边界响应模板（做不到时怎么说）

- [ ] system prompt 固化拒绝话术模板：「这个我还做不到，但我可以：<相近能力清单>」——把每次能力边界撞墙变成一次能力广告；支撑既有"明确响应不编造"铁律；模板配置化可调

***

##### 板块十四：读前预览与组合编排（2026-11-16 追加）

###### 6.1 读前事实性预览（0 次 LLM）

- [ ] "这篇讲了什么？多长？什么风格？"：标题+摘要+ai_labels+word_count 拼装事实卡片（讲什么/篇幅/风格/体裁/发布时间），**只报事实不给评价**——"值不值得读"类评价性问法按 F8 边界铁律引导为事实预览（不说好不好，只说是什么）；文章不可见/已下架 → 明确响应

###### 6.2 组合指令（多工具编排能力验证场景）

- [ ] "找 5 篇轻松的增长文章，摘要后把前 2 篇收藏"：Function Calling 多步链式调用（检索→摘要→收藏），各步各自按 purpose 计量；**定位为工具底座编排能力验证，非独立新功能**——跑通即证明未来新工具入注册表自动获得组合能力
  - 护栏：步数上限外置（防成本放大）；中途失败 → 已完成步骤保留并如实说明，不回滚不重复执行

###### 6.3 关注作者动态（二档，契约依赖）

- [ ] "我关注的作者最近发了什么"：关注列表契约解析为 authorId 白名单 filter + publishTime 排序 → 返回动态列表
  - 前置核对：关注列表契约（user/social 侧）是否可经 Feign 复用（缺则转 temporarily 挂起，触发条件写明缺哪个契约）

###### 6.4 排除已读（二档，契约依赖）

- [ ] "没读过的""挑 3 篇今天值得看的"：拉取用户阅读历史 ID → excludeArticleIds（must_not filter，与 includeArticleIds 对称，见 search 板块四）+ 条件检索组合
  - 前置核对：阅读历史契约（content 侧，批次E bizType 枚举化已归档）是否可经 Feign 复用（缺则转 temporarily 挂起）

###### 6.5 继续阅读（2026-11-16 追加，契约依赖）

- [ ] "接着读我昨天读的那篇/我上次读到哪了"：拉取用户阅读历史 → 最近一条定位 articleId → 读前预览事实卡片（复用 6.1）+ 引导跳转；0 次 LLM
  - 前置核对：与 6.4 同源——阅读历史契约（content 侧）是否可经 Feign 复用（缺则随 6.4 一并转 temporarily 挂起）

------------

##### 板块十五：写作辅助扩展——多级改写建议与文章草稿（F6 延伸，建议态，依赖板块二 Agent 底座）

###### 15.1 多级改写建议端点（标题 / 段落 / 句子 / 词语）

- [ ] POST /ai/xiaoyue/rewrite（设计锚点，落地前 Api-Architecture 定稿）+ /chat 意图路由新增 REWRITE 意图：按目标粒度生成优化版本候选与优化建议，供作者参考选用
  - 重合归并：TITLE 粒度与板块十二 4.7「标题/开头多版建议」重合——落地时以本端点统一承载（4.7 并入 TITLE/PARAGRAPH 粒度实现，不重复建设）
  - 入参：target_type（TITLE/PARAGRAPH/SENTENCE/WORD）+ 原文 + 可选风格偏好（正式/口语/简洁）+ 候选数（1–3，配置上限）
  - 出参：候选版本数组（每版附一句改动理由）+ 优化建议清单（冗余/歧义/语气/结构诊断，只诊断不贬损——F6/F8 边界）
  - 业务逻辑：走 Agent 底座（purpose=POLISH、scene=target_type，路由后即判配额预算）→ 结构化产出 → SuggestionValidator 扩展校验（按 target_type 约束：标题字数上限、词语不得偏离原意等，越限错误回喂重试 1 次）→ 建议态缓存（P7 同口径，requestId 幂等）→ 失败降级直连固定管道（三级降级链复用）
  - 铁律：建议态不自动写入（D4）；恒带 AI 标识（D3/D13）；展示前过 DFA 闸门（调用方执行）；**改写保持作者原意**（prompt 硬约束），词语级不做词典式近义词堆砌、给上下文适配建议；仅登录用户可用（写作辅助不开匿名）
  - 计量：purpose=POLISH 复用写作辅助桶（同属 F6 科目，不再扩枚举），scene 区分粒度，Agent 多轮按 requestId+callSeq 落明细

###### 15.2 文章草稿生成端点

- [ ] POST /ai/xiaoyue/draft + /chat 意图路由新增 DRAFT 意图：按主题（+可选大纲/要点/字数范围）生成建议态草稿与段落结构建议
  - 业务逻辑：入参校验（主题必传）→ 配额/预算判定（purpose=POLISH）→ Agent 产出草稿正文 + 段落规划 → 结构化校验（单次字数上限 draft-max-chars 外置，默认 2000，防成本放大）→ 建议态缓存 → 返回
  - **采用路径（关键边界）**：AI 不落库——作者编辑后由前端带入 content **正常创建流程**（走完整 DFA 同步闸门 + 机审主链路，AI 草稿不绕过送审）；采用后 AI 生成标识随正文持续展示（D3 / Q64 生命期口径），AI 会话侧副本按 90 天清理
  - 铁律：草稿仅参考、作者可关闭；出站脱敏由装饰链自动覆盖；prompt 版本外置（prompt_version 承载，复用键与评测回归可追溯）

***

#### search模块

> 板块划分依据：Search-Architecture.md；本模块既有批次（批次A~批次十七）均已归档或迁验证清单，以下为新增专项板块
> 推进顺序：板块一内 1.2（重建链路）先行 → 1.3（写入链路）后切，防新旧口径混跑
> **2026-11-16 增补板块二~四**（迁自 [temp.md](../temp/temp.md) 两 Agent 立项整体转正式，任务编号沿用原批次编号不重排）：推进顺序——板块二（维度注册表 + ai_labels 标签槽，2.2 依赖 AI 板块八 1.3 打标端点）→ 板块三（条件版相似查询，AI 板块九/十一/十二多任务的前置）→ 板块四（白名单 filter 扩展，依赖板块三 3.1）；与池化专项板块一非硬依赖，但 queryText 口径对齐项联动（板块三 3.1 注）；运行态验证见 [next\_verify.md](next_verify.md) AI-V4

##### 板块一：文章向量切片池化重建（相似推荐全文覆盖改造）

> 背景与动机：当前 vec_dense 输入 = 标题+摘要+正文前 2000 字截断（SearchTextUtils.buildEmbeddingInput），长文后半段主题丢失，相似推荐只看文章开头；主流解法为**切片向量池化**（mean pooling / 层级向量父向量），且 search 自管 article_chunks 切片索引（批次C 建，与 article_idx 同一 bge-m3 单模型，批次C C1）已存全量切片向量——池化零新增 Embedding 调用，查询侧零变化。

###### 1.1 池化聚合器与配置外置

- [ ] 新增纯函数聚合器 ArticleVectorPooler：vec_dense = (w_t·v标题 + w_s·v摘要 + w_c·mean(v全部切片向量)) / Σw，L2 归一化后写 article_idx.vec_dense；写入与重建共用同一聚合器，防口径漂移（对齐 buildEmbeddingInput 单点纪律）
  - 口径：w_t=2 / w_s=2（摘要为空则权重并入 w_t）/ w_c=1（mean 聚合）；池化策略配置开关 litisle.search.embedding.pool.strategy=mean|max-pool（默认 mean；max-pool 逐维取各切片最大值，对多主题长文更尖锐但可能放大噪声，备选不默认）
  - 无切片兜底：正文 < chunkSize 的短文退化为现口径直算（EMBEDDING_INPUT_MAX_CHARS=2000 降级为兜底路径上限，主链路不再依赖）；纯图/无正文回退标题向量（防 null 向量）；article_idx 的 _meta 模型登记不变（池化不换模型，批次C C1 单模型纪律天然满足）

###### 1.2 重建链路改造 + 全量重建（先行）

- [ ] VectorRebuildService.rebuildArticleVectors 改池化口径：存量文章先补齐 article_chunks 切片向量、再池化聚合写 vec_dense（一个任务两步）
  - 实施顺序铁律：先跑 1.2 全量重建收敛存量 → 再切 1.3 写入链路；中间态新发布文章走旧口径（前 2000 字）可接受，重建任务最终收敛

###### 1.3 发布写入链路切换

- [ ] ArticleIndexServiceImpl 发布事件建文档时向量改为池化口径：保证该篇切片向量在位（既有切片链路）→ ArticleVectorPooler 聚合 → 写 vec_dense（替换 embedArticle 标题+摘要+前 2000 字直算）
  - 改稿联动：article-updated → 切片更新 → vec_dense 同步刷新，全链路无人工介入；换模型仍走既有「_meta 防漂移 + 全量重建」纪律

###### 1.4 相似检索联动核对（查询侧零改动 + AI 查询文本口径对齐）

- [ ] 相似推荐查询链路确认零改动并留核对记录：SimilarController / SimilarServiceImpl / SearchClient#similar 契约 / EsIndexInitializer mapping（1024 维 cosine 不变）均不动——池化只改 vec_dense 生产端，查询侧读同一字段
  - 联动改造一处（litisle-ai 侧）：板块五 SimilarRecommendService.buildQueryText 查询文本由「标题+正文前 500 字」对齐为「标题+摘要+正文截断」（经 getArticleIndexDoc 取 summary——ContentDTO 无摘要字段），保证查询向量与池化后文章向量输入口径可比（两边都是"标题+摘要+长正文"，knn 分才有可比性）；对齐后 AI 侧 buildQueryText 与 search 侧 buildEmbeddingInput 的拼接规则保持一致
  - 阈值回归（验证项，见 next_verify.md S-P1 ⑤）：池化改变文章向量分布，knn.similarityThreshold=0.4 与空结果兜底的实时 Embedding 查询需抽样回归，必要时校准配置

##### V-P1 文章向量池化运行态验证（写入 next_verify.md，不在本文件挂载）

- [ ] 长文池化效果运行态验证（依赖板块一 1.1~1.3 全部落地）

***

##### 板块二：维度注册表与 ai_labels 标签槽（2026-11-16 迁自 temp.md 第一立项公共配置 + search 批次一）

###### 2.1 维度注册表初版定稿（P0 拍板项，编码前置）

- [ ] litisle.search.ai-labels.dimensions 初版定稿：filter 型维度与受控词表（style/mood 等取值集合）、range 型字段绑定（word_count、publish_time）、param 型（topK/sort/权重语义）、semantic 型（topic）；打标与提取两端 prompt 由注册表动态生成，**新增维度=改配置+跑一次全量重建补标，零代码**
  - 2026-11-15 实用性×成本筛选——**二期首批词表**（随打标管线顺带，无独立成本）：depth（入门/进阶/深度）、structure（叙事/论述/教程/清单）；stance/audience 词表留位观察不做
  - **待讨论：初版词表内容需产品/算法拍板**（本条目为占位，词表定稿后随 AI 板块八 1.2 / 板块三 3.1 落地；词表定稿是整条主链的闸门）
  - 暂不做清单（has_image / OR 逻辑组合 / 关注列表 filter / 已读去重 / 用户画像个性化）已登记 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md) ai 模块节，不在本文档保留

###### 2.2 ES 字段与索引期打标

- [ ] article_idx 新增 ai_labels object（dynamic keyword，新维度零 mapping 改动）+ word_count 数值字段（索引期按 body 纯计算字数，零 AI 成本）；ArticleIndexServiceImpl 建索引链路接入打标：调 AI /api/feign/ai/label → ai_labels 写入文档；改稿 article-updated → 重新打标整体覆盖
  - 铁律：打标只发生在内容变化时（发布/改稿/重建），查询期零 LLM；打标失败不阻断索引主链路（重试耗尽 → ai_labels 留空，该维度过滤退化为语义兜底）
  - 存量补标：一次全量重建（VectorRebuild admin 任务扩展）
  - 依赖：AI 板块八 1.3 打标端点就绪

***

##### 板块三：条件版相似查询（SearchClient 契约扩展）

###### 3.1 条件查询契约与四类 match 构造

- [ ] SearchClient#similar 扩展条件版请求（queryText / authorId / excludeAuthorIds / category / tags / style 词表值 / wordCount 区间 / timeRange（publishTime range）/ topK / sort）；SimilarService 增条件版实现，按注册表 match 类型路由：filter 词表型 → term filter（含 ai_labels.style）/ range 数值型 → range filter（word_count、publish_time）/ param 参数型 → 直接映射请求参数（topK 上限截断、sort 与融合权重调整——similarity-first 时 vectorWeight=1 其余归零）/ semantic → queryText embed 后 knn（替代源文章向量作参照）；批次七三路融合评分沿用
  - 铁律：visibility=PUBLIC + 排除自身铁律不变；queryText 非空时查询文本口径与池化专项板块一 1.4 对齐（标题+摘要+正文截断）；无命中返回空列表不报错
  - 2026-11-15 实用性×成本筛选：一期纳入 timeRange（字段已有）与 similarity-first 权重语义（权重外置可调）；has_image / stance / audience / OR 组合 / 关注列表 / 已读去重 / 用户画像**均不做**（详见 temporarily-04-deferred.md ai 模块节暂不做清单）

###### 3.2 按需升级阶梯（门控多次调用，2026-11-15 修订：1 次是基线不是上限）

- [ ] 升级①查询改写重检（+1 次 LLM，触发式）：有效命中 < 3 条 或 最高分 < 阈值时，LLM 改写 queryText 再检索一轮（每请求至多 1 次改写；改写仍无命中 → 返回空列表不报错）——CRAG 式按检索质量补救，钱花在结果不好的时候
- [ ] 升级②本地 reranker 精排（0 次 LLM，推荐中间档）：bge-reranker / cross-encoder 对 (queryText, 候选摘要) 打分重排，跑在与 Ollama 同款的本地推理上，零 API 费；排序效果显著优于三路融合加权、接近 LLM judge——主流实现优先本地 reranker 而非 LLM 评断，LLM 只留给 reranker 判不了的复杂要求
- [ ] 升级③LLM judge 精排（+N 次 LLM，配置开关默认关）：对 TopN≤5 逐篇评断「符合用户要求吗」（跨维度主观权衡），每查询成本随候选线性涨，仅作最准档
  - 护栏铁律：每次升级必须有明确触发条件 + 配置开关 + 降级路径（升级失败退回上一级结果，结果不劣化），无任何「无条件多调」；注册表外长尾表达仍由 3.1 semantic 路径语义兜底
  - 待讨论：①改写重检与②reranker 的一期边界（倾向：②随一期落地——本地零成本；①二期，触发条件：基线命中率实测不达标；③维持默认关）

***

##### 板块四：includeArticleIds / excludeArticleIds 白名单 filter（2026-11-16 迁自 temp.md 第二立项 search 批次）

###### 4.1 条件版契约扩展

- [ ] SearchClient 条件版相似查询增 includeArticleIds（must terms filter，与 excludeAuthorIds 对称，支撑 AI 板块十一 3.2 收藏夹检索）**及 excludeArticleIds（must_not filter，AI 板块十四 6.4 排除已读用）**；ES bool filter 组装按四类 match 构造器复用
  - 依赖：板块三 3.1（条件版查询框架先行）

#### review模块

> 板块划分依据：Review-Architecture.md / AI-Business-Guide.md §四（机审流程）；主线批次（板块一\~九）与板块十均已归档

> **板块十已完成并归档**（2026-11-15）：文本机审判定器切换云 API（阿里云内容安全 text-provider=aliyun 主通道，LLM 适配器保留不激活）——实现记录见 [review-10-aliyun-text-moderation.md](../archive/review/review-10-aliyun-text-moderation.md)（next\_update 条目摘要与决策口径原样搬运；主线板块一\~九记录见 [review-implemention-records.md](../archive/review/review-implemention-records.md) 索引页）
> 预留项「云 SUSPICIOUS → LLM 二次精判」生产混合方案已登记 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（review 模块节）；运行态验证 RV-V1 已迁入 [next\_verify.md](next_verify.md)（review 模块节）；本文件不保留 review 活跃待办。

***

## 暂不实现汇总

#### content模块

#### search模块

- ~~user\_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user\_idx），此项从「暂不实现汇总」移出，原触发条件已满足
