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
> **板块五/六/七已全部完成并归档**（2026-11-16）：实现记录同见上述索引页，正文按板块拆分（板块五 [ai-05-similar-recommend.md](../archive/ai/ai-05-similar-recommend.md) / 板块六 [ai-06-usage-observability.md](../archive/ai/ai-06-usage-observability.md) / 板块七 [ai-07-docs-registration.md](../archive/ai/ai-07-docs-registration.md)）
> 运行态验证项已迁入 [next\_verify.md](next_verify.md)（AI-V1 RAG 端到端联调 / AI-V2 会话与计量 / AI-V3 相似推荐与用量出账）；挂起项与跨板块遗留已登记 [temporarily-01-code.md](temporarily/temporarily-01-code.md)「litisle-ai 模块」节（XIAOSHEN 预留/已保存区预留/计量明细清理；网关登记/架构文档登记/D15 修订三项已随板块七回收标注 ✅）；本文件不保留 AI 验证条目。

> **2026-11-16 增补板块八~十四**（[temp.md](../temp/temp.md) 两个 Agent 立项整体转正式，任务编号沿用原批次编号不重排）：推进顺序——P0 拍板项（维度注册表词表定稿 / 入口形态 / 打标 purpose 归桶，均在条目内标注待讨论）→ 板块八（条件提取管道）→ search 模块板块二/三（标签槽 + 条件版查询）→ 板块九（交互扩展地基+动作+读后，契约核对类子项可并行提前核）→ 板块十（多轮调条件）→ 板块十一（对比/收藏检索，依赖 search 板块三/四）→ 板块十二（创作辅助，依赖 content 创作侧入口，4.2/4.4 契约核对可提前核）→ 板块十三（能力发现，零 LLM，可与板块八并行）→ 板块十四（读前预览+组合编排，6.3~6.5 依赖契约核对，可并行提前核）；验证项 AI-V4~V8 已迁入 [next\_verify.md](next_verify.md)
> **板块八已全部完成并归档**（2026-11-16，见 [ai-08-conditional-similar.md](../archive/ai/ai-08-conditional-similar.md)）；search 配套（板块二整体 / 板块三）同批落地并归档（板块二见 [search-12-ai-labels.md](../archive/search/search-12-ai-labels.md)，板块三含 3.2 升级② reranker 见 [search-14-conditional-query.md](../archive/search/search-14-conditional-query.md)，升级①/③ 预留项迁 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)）；**板块九已全部完成并归档**（2026-11-16，见 [ai-09-agent-interaction.md](../archive/ai/ai-09-agent-interaction.md)，书单动作与金句落库契约缺失部分挂起 [temporarily-01-code.md](temporarily/temporarily-01-code.md) ai 节）；**板块十已完成并归档**（2026-11-16，见 [ai-10-multi-turn-condition.md](../archive/ai/ai-10-multi-turn-condition.md)）；**板块十一已完成并归档**（2026-11-16，见 [ai-11-compare-favorites.md](../archive/ai/ai-11-compare-favorites.md)，search 板块四 4.1 白名单 filter 同批配套落地，见本文档 search 模块板块四）；**板块十二已完成并归档**（2026-11-16，见 [ai-12-creator-assist.md](../archive/ai/ai-12-creator-assist.md)，4.6 配套补 SearchClient#getArticleAiLabels 契约，4.7 并入板块十五 15.1 承载）；**板块十五已完成并归档**（2026-11-16 落地，2026-11-17 归档，见 [ai-15-writing-assist.md](../archive/ai/ai-15-writing-assist.md)）；活跃余项：板块十三 5.1/5.2、板块十四 6.1~6.5（6.3~6.5 契约核对前置）

##### 板块五：F8 相似文章推荐（复用板块四 RAG 底座，纯检索形态零生成成本）

> **板块五 5.1/5.2 已全部完成并归档**（2026-11-15 落地，2026-11-16 归档）：条目与 ✅ 摘要原样搬运至 [ai-05-similar-recommend.md](../archive/ai/ai-05-similar-recommend.md)；运行态验证走 [next\_verify.md](next_verify.md) AI-V3
> 原「AI-Business-Guide.md 空文件」事项已消除：文档已确认完整（897 行），§5.3 计量口径已随板块七 7.2 修订为八类（含 SIMILAR）。

***

##### 板块六：用量可观测与模型自检（F9 收口 + §5.2 口径，无板块依赖可提前做）

> **板块六 6.1/6.2 已全部完成并归档**（2026-11-16 落地并归档）：条目与 ✅ 摘要原样搬运至 [ai-06-usage-observability.md](../archive/ai/ai-06-usage-observability.md)；运行态验证走 [next\_verify.md](next_verify.md) AI-V3

***

##### 板块七：文档收尾与登记（板块五/六落地后的配套，契约与文档类）

> **板块七 7.1/7.2/7.3 已全部完成并归档**（2026-11-16 落地并归档）：条目与 ✅ 摘要原样搬运至 [ai-07-docs-registration.md](../archive/ai/ai-07-docs-registration.md)；落地摘要——7.1 AI-Architecture §7.1 端点路径与出入参定稿 + 网关 /api/ai/\*\* 前缀登记（Gateway-Architecture §2.1 已有路由、Api-Architecture §4.5 补口径）；7.2 AI-Business-Guide §5.3 计量口径修订为八类（含 SIMILAR，D15/§16.3.4 引用同步）；7.3 AI-Architecture §2.3/§3/§4.1/§5.5/§5.6/§7.1/§7.3/§7.4/§9/§11.1 状态同步与新增能力登记；temporarily-01-code.md 三项挂起标注 ✅ 已回收；板块五/六/七均为纯文档/文档收口改造无编译面（板块五/六代码侧编译验证已分别于 2026-11-15/16 通过）

***

##### 板块八：条件化相似推荐——条件提取管道（L0/L1 分层路由）（2026-11-16 迁自 temp.md 第一立项 AI 批次一）

> **板块八 1.1/1.2/1.3 已全部完成并归档**（2026-11-16 落地并归档）：条目与 ✅ 摘要原样搬运至 [ai-08-conditional-similar.md](../archive/ai/ai-08-conditional-similar.md)（P0 三项拍板口径随条目归档）；search 侧配套（维度注册表 / ai_labels 标签槽）见 [search-12-ai-labels.md](../archive/search/search-12-ai-labels.md)、条件版查询见 search 模块板块三 3.1；运行态验证走 [next\_verify.md](next_verify.md) AI-V4

***

##### 板块九：Agent 交互扩展——会话结果集地基 + 结果动作 + 读后服务（2026-11-16 迁自 temp.md 第二立项 AI 批次一）

> **板块九已全部完成并归档**（2026-11-16 落地并归档）：条目与 ✅ 摘要原样搬运至 [ai-09-agent-interaction.md](../archive/ai/ai-09-agent-interaction.md)（含计量与桶位定档：1.2 收藏动作 0 次生成成本不落 AI 计量明细、1.3 purpose=SUMMARY、1.4/1.6/1.7/1.8 purpose=QA、1.5 purpose=STRUCTURE，均复用既有桶不扩枚举）；前置核对结论——content 收藏接口已存在（随本批补 Feign 契约落地）、书单/笔记摘录契约不存在，1.2 书单动作与 1.8 金句落库转 [temporarily-01-code.md](temporarily/temporarily-01-code.md)「litisle-ai 模块」节挂起（触发条件随条目）；运行态验证走 [next\_verify.md](next_verify.md) AI-V5

***

##### 板块十：多轮调条件（原 temporarily-04 挂起项拉回）

> **板块十已全部完成并归档**（2026-11-16 落地并归档）：条目与 ✅ 摘要原样搬运至 [ai-10-multi-turn-condition.md](../archive/ai/ai-10-multi-turn-condition.md)；运行态验证走 [next\_verify.md](next_verify.md) AI-V5 ⑤

***

##### 板块十一：多文对比与收藏夹检索（二档）

> **板块十一已全部完成并归档**（2026-11-16 落地并归档）：条目与 ✅ 摘要原样搬运至 [ai-11-compare-favorites.md](../archive/ai/ai-11-compare-favorites.md)；前置依赖 search 板块四 4.1 白名单 filter 同批配套落地（见本文档 search 模块板块四）；计量定档——3.1/3.2 均为工具素材组装 0 次额外生成成本，对比结论/检索整理在 Agent 意图路由轮内一次生成（purpose 随入口，QA 会话即 QA 桶）；运行态验证走 [next\_verify.md](next_verify.md) AI-V5 ⑥⑦

***

##### 板块十二：检索编排与创作辅助扩展（2026-11-15 追加，复用板块九~十一底座，边际成本≈0）

> **板块十二已全部完成并归档**（2026-11-16 落地并归档）：4.1~4.6 全部落地；4.5 并入 3.1（随板块十一归档）；4.7 依板块十五 15.1 重合归并口径并入统一承载（本板块不重复建设）；4.6 配套补 SearchClient#getArticleAiLabels 契约；条目与 ✅ 摘要原样搬运至 [ai-12-creator-assist.md](../archive/ai/ai-12-creator-assist.md)；运行态验证走 [next\_verify.md](next_verify.md) AI-V5 ⑥ / AI-V6 ⑤

***

##### 板块十三：能力发现与引导（2026-11-16 追加，零 LLM 成本）

> 解决"用户不知道 Agent 能做什么/不能做什么"——主流四板斧取三（结果即时提示 suggestions 字段暂不做，触发条件：实测冷启动引导不足）
> **板块十三 5.3 已完成**（2026-11-16 落地）；**5.1/5.2 已于 2026-11-16 从暂不开发清单恢复**，条目迁回本板块待开发（原暂缓登记已自 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md) 移除）；板块归档待 5.1/5.2 落地或关闭后执行

###### 5.3 边界响应模板（做不到时怎么说）

- [x] system prompt 固化拒绝话术模板：「这个我还做不到，但我可以：<相近能力清单>」——把每次能力边界撞墙变成一次能力广告；支撑既有"明确响应不编造"铁律；模板配置化可调
  - ✅ 2026-11-16：AiPromptConstants 增 XIAOYUE_BOUNDARY_RESPONSE_RULES 规则段（拒绝话术模板占位 + {capabilities} 填写规则——从真实注册工具能力中挑与用户诉求最相近 2~4 项如实填写、禁编造、拒绝时附一句可执行替代问法，为「明确响应不编造」铁律的固定话术承载）+ renderXiaoyueSystemPrompt 渲染方法；AiAgentProperties 增 boundaryRefusalTemplate（yml 外置 litisle.ai.agent.boundary-refusal-template，默认「这个我还做不到，但我可以：{capabilities}」）；AiAgentConfig 装配点接线渲染（仅 Agent 意图路由轮注入；固定管道结构化输出场景不混入本段防污染 JSON 输出）；零 LLM 成本（只改话术不加调用）；编译验证已通过（2026-11-16）

###### 5.1 冷启动快捷指令（suggestion chips）

- [ ] 空会话/助手入口预置问法清单：「找轻松风格的用户增长文章」「收藏第 2 篇」「这篇有前作吗」「对比前 3 篇」等高频能力示例，配置化维护（问法清单 yml 外置，零 LLM 成本）；2026-11-16 曾拍板暂不开发，同日恢复保留在本板块待开发
  - 触发观察：实测冷启动引导不足时优先落地（对应板块注 suggestions 字段触发条件同源）

###### 5.2 能力清单自动生成（单一口径）

- [ ] XiaoyueTools 工具注册表双端消费：新增 GET /ai/xiaoyue/capabilities 渲染前端「能力面板」，新增工具自动出现在面板（单一口径：工具注册表即能力清单唯一来源，前端不自维护重复清单）；2026-11-16 曾拍板暂不开发，同日恢复保留在本板块待开发
  - 触发观察：前端提出能力面板需求，或新增工具需要前端同步展示能力时优先落地

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

> **板块十五已全部完成并归档**（2026-11-16 落地，2026-11-17 归档）：条目与 ✅ 摘要原样搬运至 [ai-15-writing-assist.md](../archive/ai/ai-15-writing-assist.md)；板块十二 4.7 重合归并随之回收——TITLE 粒度已由 15.1 统一承载（归属校验随 articleId 入口落地）；运行态验证走 [next\_verify.md](next_verify.md) AI-V6

***

#### search模块

> 板块划分依据：Search-Architecture.md；本模块既有批次（批次A~批次十七）均已归档或迁验证清单，以下为新增专项板块
> 推进顺序：板块一内 1.2（重建链路）先行 → 1.3（写入链路）后切，防新旧口径混跑
> **2026-11-16 增补板块二~四**（迁自 [temp.md](../temp/temp.md) 两 Agent 立项整体转正式，任务编号沿用原批次编号不重排）：推进顺序——板块二（维度注册表 + ai_labels 标签槽，2.2 依赖 AI 板块八 1.3 打标端点）→ 板块三（条件版相似查询，AI 板块九/十一/十二多任务的前置）→ 板块四（白名单 filter 扩展，依赖板块三 3.1）；与池化专项板块一非硬依赖，但 queryText 口径对齐项联动（板块三 3.1 注）；运行态验证见 [next\_verify.md](next_verify.md) AI-V4
> **板块一/二/三/四已全部完成并归档**（板块二 2026-11-16 见 [search-12-ai-labels.md](../archive/search/search-12-ai-labels.md)；板块一 2026-11-17 见 [search-13-vector-pooling.md](../archive/search/search-13-vector-pooling.md)；板块三 2026-11-17 见 [search-14-conditional-query.md](../archive/search/search-14-conditional-query.md)（升级①/③ 预留项迁 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)）；板块四 2026-11-16 随 AI 板块十一归档）；本模块活跃待办清零，验证项 S-P1 见 [next\_verify.md](next_verify.md)

##### 板块一：文章向量切片池化重建（相似推荐全文覆盖改造）

> **板块一已全部完成并归档**（2026-11-17 落地并归档）：条目与 ✅ 摘要原样搬运至 [search-13-vector-pooling.md](../archive/search/search-13-vector-pooling.md)（写入/重建/改稿全链路池化 + AI 查询文本口径对齐，查询侧零改动核对留档）；运行态验证项见 [next\_verify.md](next_verify.md) S-P1（V-P1 文章向量池化运行态验证，迁移是移动不在本文件保留）

***

##### 板块二：维度注册表与 ai_labels 标签槽（2026-11-16 迁自 temp.md 第一立项公共配置 + search 批次一）

> **板块二 2.1/2.2 已全部完成并归档**（2026-11-16 落地并归档）：条目与 ✅ 摘要原样搬运至 [search-12-ai-labels.md](../archive/search/search-12-ai-labels.md)（含同批落地的板块三 3.1 条件版查询配套摘要）；打标端点与条件提取管道见 ai 模块 [ai-08-conditional-similar.md](../archive/ai/ai-08-conditional-similar.md)；运行态验证随 AI-V4 走 [next\_verify.md](next_verify.md)

***

##### 板块三：条件版相似查询（SearchClient 契约扩展）

> **板块三已全部完成并归档**（3.1 于 2026-11-16 落地、3.2 升级②本地 reranker 精排于 2026-11-17 落地，2026-11-17 归档）：条目与 ✅ 摘要原样搬运至 [search-14-conditional-query.md](../archive/search/search-14-conditional-query.md)；升级①（二期）/升级③（默认关）预留项已迁 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)「search 模块」节（触发条件随条目）；运行态验证随 AI-V4 走 [next\_verify.md](next_verify.md)

***

##### 板块四：includeArticleIds / excludeArticleIds 白名单 filter（2026-11-16 迁自 temp.md 第二立项 search 批次）

> **板块四已全部完成**（2026-11-16 随 AI 板块十一同批配套落地并归档）：不再单独立归档文件，实现细节见 [ai-11-compare-favorites.md](../archive/ai/ai-11-compare-favorites.md) 依赖链一节；本节留指针不保留条目

#### review模块

> 板块划分依据：Review-Architecture.md / AI-Business-Guide.md §四（机审流程）；主线批次（板块一\~九）与板块十均已归档

> **板块十已完成并归档**（2026-11-15）：文本机审判定器切换云 API（阿里云内容安全 text-provider=aliyun 主通道，LLM 适配器保留不激活）——实现记录见 [review-10-aliyun-text-moderation.md](../archive/review/review-10-aliyun-text-moderation.md)（next\_update 条目摘要与决策口径原样搬运；主线板块一\~九记录见 [review-implemention-records.md](../archive/review/review-implemention-records.md) 索引页）
> 预留项「云 SUSPICIOUS → LLM 二次精判」生产混合方案已登记 [temporarily-04-deferred.md](temporarily/temporarily-04-deferred.md)（review 模块节）；运行态验证 RV-V1 已迁入 [next\_verify.md](next_verify.md)（review 模块节）；本文件不保留 review 活跃待办。

***

## 暂不实现汇总

#### content模块

#### search模块

- ~~user\_idx 索引同步链路（原批次B B5 + 批次D D2 用户部分）~~：**已于 2026-11-04 随批次五 5.3 落地**（用户注册/资料更新事件契约 + user 侧发布点 + search 消费同步 user\_idx），此项从「暂不实现汇总」移出，原触发条件已满足
