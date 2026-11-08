# LitIsle AI 文档清单与状态索引

> **目录说明**：本文件位于 `docs/docs_design/`。该目录是**文档设计临时工作目录**，用于排查记录与问题跟踪，**不纳入 `docs/index/` 的正式索引**；将来若要长期保留，再单独决定归档落点。
>
> 定位：本清单承担三件事——**①文档资产台账**（有哪些文档、写到什么程度）；**②实施前置与外部依赖跟踪**（还卡在哪个外部条件）；**③文档设计问题跟踪**（Q 编号，按阻断/影响/文字分档，见第六节）。只记状态，不复制设计正文或决策明细。**决策记录不在这里**，见 `AI-Business-Guide.md` 第十六章。
>
> 最后核对日期：2026-11-08
>
> 最后修订：2026-11-08 · 修复批次 1–12（范围：Q1–Q77 可修项、R1–R11 文档侧、附录词条、§七 验收补 F10–F22、§八 待定值补 V5–V12；遗留 Q63 待裁决）
>
> 统计（2026-11-08）：文档资产 9 项；实施前置 6 项；设计问题 77 条（阻断 15 / 影响 44 / 文字 18，其中已修 76、待确认 1，即 Q63 待用户裁决；待用户定值 12 项，见第八节）；风险登记 11 条（文档侧已缓解 3 / 等待外部条件 8）。
>
> 状态标记：
>
> - `[x]` 已写清 / 已定 / 已修
> - `[~]` 已写但范围未定、仍有前置依赖，或问题处理中
> - `[ ]` 未开始 / 未产出 / 待确认
>
> 维护要求见文末「维护规则」。改动 AI 相关文档时，请在同一次改动内同步本清单。

## 一、文档资产清单

| 文档（标题锚定） | 路径 | 定位 | 当前状态 | 最后更新 |
| --- | --- | --- | --- | --- |
| [AI-Business-Guide.md · AI 模块功能指南（业务与功能向）](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md) | `docs/Study/AI-Business-Guide.md` | 业务口径（功能主文档） | `[x]` 已完成：F1–F22 功能项、闭环审计、业务流程、业务规则、验收口径、D1–D25 决策；25 项决策全部定档（2026-11-08），D15 实施前置为补计量 | 2026-11-08 |
| [Function-Document.md · litisle-ai（AI 服务）](file:///e:/NewJavaPro/LitIsle/docs/overall_documen/Function-Document.md) | `docs/overall_documen/Function-Document.md` | 业务口径（系统级功能概要） | `[x]` 已有；仅职责概要，不覆盖模块细节 | 2026-11-07 |
| [Design-Document.md · litisle-ai（AI 服务）](file:///e:/NewJavaPro/LitIsle/docs/overall_documen/Design-Document.md) | `docs/overall_documen/Design-Document.md` | 技术/架构口径（目标蓝图） | `[~]` 已有设计目标（小月/小审、会话与配额）；未落到实现 | 2026-11-08 |
| [Design-Document.md · RAG 知识库设计](file:///e:/NewJavaPro/LitIsle/docs/overall_documen/Design-Document.md) | `docs/overall_documen/Design-Document.md` | 技术/架构口径（RAG） | `[~]` 有设计目标；依赖 search 侧知识库摄入与 Embedding 就绪 | 2026-11-08 |
| [Api-Architecture.md · AiClient（AI 服务契约）](file:///e:/NewJavaPro/LitIsle/docs/Architecture/Api-Architecture.md) | `docs/Architecture/Api-Architecture.md` | 契约口径 | `[~]` 契约已定、端点未实现（占位） | 2026-11-03 |
| [Sql-Document.md · litisle-ai 模块](file:///e:/NewJavaPro/LitIsle/docs/Sql-Document.md) | `docs/Sql-Document.md` | 数据口径 | `[~]` 有表设计，尚未落地（占位） | 2026-11-07 |
| [Review-Architecture.md · 审核架构](file:///e:/NewJavaPro/LitIsle/docs/Architecture/Review-Architecture.md) | `docs/Architecture/Review-Architecture.md` | 技术口径（机审侧现状与降级） | `[x]` 已有；AI 在其中仅作为上游调用方 | 2026-11-02 |
| [Review-Business-Guide.md · 审核业务指南](file:///e:/NewJavaPro/LitIsle/docs/Study/Review-Business-Guide.md) | `docs/Study/Review-Business-Guide.md` | 业务口径（机审上游流程） | `[x]` 已有 | 2026-11-02 |
| [status.md · 现状与阻塞项](file:///e:/NewJavaPro/LitIsle/docs/next/status.md) | `docs/next/status.md` | 状态跟踪 | `[~]` 已有；记录「AI 对话端点未实现」为前置阻塞 | 2026-10-28 |

> 说明：`docs/Architecture/` 下暂无 AI 专篇架构文档；`docs/index/` 下暂无 `ai-index`。这两项属于「未产出的文档」，见第四节。

## 二、功能项覆盖状态（F1–F22）

| 功能项 | 落点章节（标题锚定） | 覆盖状态 | 关联决策 |
| --- | --- | --- | --- |
| F1 文本机审 | 功能清单与优先级；业务流程与状态 | `[x]` 已写清 | D1、D7 |
| F2 图片机审 | 功能清单与优先级；业务流程与状态 | `[x]` 已写清 | D2 |
| F3 判定辅助（小审） | 功能清单与优先级；业务流程与状态 | `[x]` 已写清 | D1、D12 |
| F4 摘要生成 | 功能清单与优先级；业务流程与状态 | `[x]` 已写清 | D4、D13 |
| F5 标签建议 | 功能清单与优先级 | `[x]` 已写清 | D4、D13 |
| F6 写作辅助 | 功能清单与优先级 | `[x]` 已写清 | D4 |
| F7 站内问答与引用 | 功能清单与优先级；业务流程与状态 | `[x]` 已写清 | D8、D13 |
| F8 深度分析与相似推荐 | 功能清单与优先级 | `[x]` 已写清 | D9 |
| F9 会话与用量管理 | 功能清单与优先级 | `[x]` 已写清 | D5、D6、D14 |
| F10 判定结论处置衔接与申诉恢复 | 补充功能项（F10–F17） | `[x]` 已写清（「回查失败不放行」已入规则） | D12 |
| F11 图片复审与隔离治理协同 | 补充功能项（F10–F17） | `[x]` 已写清 | D2 |
| F12 结论复用供给（原名：结论复用与免审供给） | 补充功能项（F10–F17） | `[x]` 已写清 | D1、D2 |
| F13 审核反馈闭环与效果评估 | 补充功能项（F10–F17）· F13.1–F13.5 | `[x]` 已补实：采集点、存储、消费方、回流动作 | D12、D19、D22、D23、D24 |
| F14 AI 能力运营配置与预算管控 | 补充功能项（F10–F17） | `[x]` 已写清 | D5 |
| F15 生成内容安全闸门与标识 | 补充功能项（F10–F17） | `[x]` 已写清 | D3、D4、D13 |
| F16 搜索与推荐语义能力供给 | 补充功能项（F10–F17） | `[x]` 已定（2026-11-08）：维持 search 自建，AI 不承担语义检索 | D9 |
| F17 非图片多模态检测 | 补充功能项（F10–F17） | `[x]` 已定：本期排除文档/音视频与多语言；下期从 OCR 文本复用文本判定 | D10、D11 |
| F18 发布入口全覆盖与送审守卫 | 闭环审计与补充 · 补充功能项（F18–F22） | `[x]` 已定：全部发布入口逐篇送审 | D17 |
| F19 存量结论重判与版本迁移 | 闭环审计与补充 · 补充功能项（F18–F22） | `[x]` 已定：抽样重判 + 过期标记 | D16 |
| F20 审核结论通知与用户触达协同 | 闭环审计与补充 · 补充功能项（F18–F22） | `[x]` 已定：通知写「自动审核 + 人工复核」 | D20 |
| F21 审核链路对账与卡死任务清扫 | 闭环审计与补充 · 补充功能项（F18–F22） | `[x]` 已写清（P0 稳定性兜底） | 无 |
| F22 生成质量评测与人工抽检 | 闭环审计与补充 · 补充功能项（F18–F22） | `[x]` 已定：固定评测集每版本 ≥300 条 | D21 |

> 覆盖统计：22/22 项有落点；22 项结论均已定档（2026-11-08），实施待落地。闭环判定（闭环/半闭环/不适用）见 AI-Business-Guide.md · 十五、闭环审计与补充。

## 三、决策记录与实施前置

> 决策记录见 [AI-Business-Guide.md · 十六、D1–D25 决策定档](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md)，共 25 项，全部定档于 2026-11-08。翻案时改正文该章，不改本清单。
>
> D25（会话条数上限）已定档：90 天内最多 200 个会话、单会话最多 500 条消息，与 D6 的天数约束为「与」关系，先触发者先淘汰；明细见正文第十六章。

### 3.1 实施前置与外部依赖

| 前置事项 | 阻塞对象 | 现状 | 责任方建议 | 完成判定 |
| --- | --- | --- | --- | --- |
| AI 计量按用途打标（机审/摘要/标签/问答） | D15 分账、F14 预算、D5 配额 | 无 Token/成本计量；只有全站 QPS（`rate_limit:global:moderation:qps`、`rate_limit:global:moderation:model-qps`），不区分用途 | 后端 / AI 服务 | 调用入口携带用途，可按用途分别统计与限额 |
| 结论版本标识落库 | D16 过期标记、F19 | 结论未强制记录模型/提示词/词库版本 | 后端 | 每条结论可查三类版本 |
| 反馈记录实体 + 工作台强校验 | F13、D22、D23 | 无 `moderation_feedback_record`；工作台未做强校验 | 后端 / 前端 | 追加记录可写，原因标签必填可校验 |
| 固定评测集与标注 | D16、D21、D24、F22 | 无固定评测集 | 算法 / 运营 | 每版本 ≥300 条，覆盖主要场景与违规类型 |
| 财务预算科目 | D15、F14 | 未开「机审 / 生成」两个科目 | 财务 / 产品 | 两个科目与上限可查，机审优先可执行 |
| 本地图片模型与算力 | D2、F2、F11 | 本地模型文件缺失，推理算力未保障 | 基础设施 / 算法 | 本地可推理；否则按既定降级全转人工 |

> 本节是清单现阶段的主体：决策已全部定档，剩下的是外部条件与工程投入，不需要再拍板。

## 四、未产出的文档

| 文档 | 拟落点 | 依赖（必须先定） | 状态 |
| --- | --- | --- | --- |
| AI 技术设计文档（架构、接口契约、模型接入、部署配置） | 路径待定（原议 `docs/ai/` 下新建） | 决策前置已满足（见第三节指针）；实施前置见 3.1：计量、版本标识、反馈实体、评测集、预算科目 | `[ ]` 可开工 |
| AI 模块索引（`docs/index/ai-index.md`） | `docs/index/` | 无（可在技术设计启动后随代码结构补写） | `[ ]` 未产出 |
| AI 运维与告警手册（指标、告警阈值、停用与回滚） | 路径待定 | D7、D14、F14 | `[ ]` 未产出 |
| 首期模型效果评估报告（上线前） | 路径待定 | D1、D2（已定，可按结论设计评估口径） | `[ ]` 未产出 |

> 决策前置已满足：D1–D25 全部定档（2026-11-08），无需再拍板。开工后仍需先满足第三节 3.1 的六项实施前置；其中 D15 分账依赖 AI 计量，D16/D21 依赖评测集与版本标识，D22/D23 依赖反馈记录实体。

## 五、维护规则

1. **同改同更**：任何 AI 相关文档的功能项、文档状态、实施前置或设计问题状态发生变化时，在**同一次改动**内更新本清单，不允许事后补记；决策变化只更新正文第十六章并核对本清单指针，不重复登记。
2. **只记状态，不抄正文**：本清单只写「在哪份文档、写到什么程度、还缺什么」；细节以链接文档为准，避免双份正文漂移。
3. **决策不在本清单登记**：决策的定档、前提与翻案条件一律改 `AI-Business-Guide.md` 第十六章；本清单只在必要时核对指针与实施前置。
4. **章节引用用标题锚定**：不写「§3.1」这类硬编码章节号，统一写章节标题；其他会话调整章节顺序时，只需核对链接文字，不改本清单语义。
5. **范围待定的标注**：功能项若「已写但范围未定」，用 `[~]` 而不是 `[x]`，防止把「写了」误读为「定了」。
6. **核对节奏**：每个迭代结束核对一次；文首「最后核对日期」超过 30 天未更新时，使用本清单的人应先复核再引用。
7. **不记过程**：临时讨论、未成型想法不进本清单，只登记可交付、可引用的状态。
8. **问题闭环留痕**：设计问题修复时把状态改为 `[x]`，并在条目内补「改前 → 改后」；不删除条目，便于回溯。
9. **修复选型偏中小型项目主流**：同一问题能用配置、常量收敛、口径统一解决就不动结构；新增依赖前先说明现有依赖能否覆盖；「主流」须给依据（同类项目常规做法、框架官方推荐、社区通行约定），给不出依据的标「存疑」，不当结论；与 D1–D25 冲突时仍以决策为准。

## 六、文档设计问题

> 来源：2026-11-08 对 `AI-Business-Guide.md` 的只读一致性审计（未用代码验证，AI 模块仅有启动骨架），以及同日八个排查批次（记录见 [AI-design-audit.md](AI-design-audit.md)）。按严重度分三档：阻断 15 条、影响 44 条、文字 18 条。Q1–Q29 为初次审计，Q30–Q77 为排查批次新增（来源批号见各条状态列）。已修 76 条、待确认 1 条（Q63，需产品 + 法务裁决）；需要新数值才能收口的一律不自行填写，集中在第八节「待用户定值」。同一处问题只登记一次，跨条目影响用「关联 Q」标注。
>
> 判定口径：**阻断** = 照此实现会做出与设计意图相反的东西（矛盾、断点、职责不清）；**影响** = 不影响开工，但会埋下返工或争议；**文字** = 术语不统一、表述含糊、引用失效。
>
> 实现层面的待确认事项（IMP1–IMP3）不属文档能定的问题，保留在 [AI-design-audit.md](AI-design-audit.md) 各批次末尾，不在本清单重复登记。

### 6.1 阻断（8 条）

| 编号 | 位置（章节 · 行号） | 原文摘录 | 问题说明 | 建议改法与牵连范围 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q1 | [§5.3 成本与配额 L264](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L264) | 改前「已保存会话上限更高（约 100 条/200 天量级）；具体值待拍板」→ 改后「留存期与生成内容同为 90 天；90 天内最多保留 200 个会话，单会话最多 500 条消息，超出按最久未活跃淘汰」 | D6 已定 90 天，原文写 200 天且标「待拍板」，实现会照 200 天做；条数上限此前无 D 项覆盖 | 已修（2026-11-08）：新增 D25 定档并同步本格；牵连 §5.4、F9、§十六 D25 | `[x]` |
| Q2 | [§5.4 数据留存与合规 L271](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L271) | 「不少于 6 个月（合规留存期），超期归档」 | 对象写「机审结论与证据」，未限定「证据」范围；D14 已定不存模型原始返回全文，实现可能存全文 | 明确定义证据 = 原因码 + 命中规则 + 置信度 + 内容指纹 + 版本号，不含原始返回；牵连 F10 L393、F13.2 L506、本清单 3.1；关联 Q12 | `[x]` 已修（2026-11-08）：§5.4 明确定义证据 = 原因码 + 命中规则 + 置信度 + 指纹 + 三版本 + 结论时间，不含模型原始返回全文（D14）。改前：证据范围未定；改后：与 D14 对齐。 |
| Q3 | [§3.2 L132](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L132)；[§5.5 L288](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L288)；[F15 L578](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L578) | 「标签不得自动创建新标签」；「标签建议不得自动创建新标签」；「新建标签必须过闸门」 | 前两处禁止、第三处条件允许，行为相反；D13 只管标识，不覆盖此冲突 | 统一为「AI 不自动创建；作者手动新建走正常标签创建流程并过闸门」 | `[x]` 已修（2026-11-08）：§3.2 改为「AI 不自动建标签；作者手动新建走正常流程并过闸门」，F15 同步。改前：两处禁止、一处条件允许；改后：三处一致。牵连 F5、F15、§七 F5 验收 |
| Q4 | [F14 L558](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L558) | 「上限线停生成式或降低机审调用频率」 | D15 与 §15.6 第 1 条均为「生成打满只停生成、机审预算优先」，此处允许降低机审频率，方向相反 | 改为「上限线停生成式；机审预算打满转全量人工并告警，不降低机审判定频率」 | `[x]` 已修（2026-11-08）：F14 改为「上限线停生成式；机审预算打满转全量人工并告警，不降机审判定频率」。改前：允许降低机审频率；改后：与 D15 / §15.6 一致。牵连 D15、§15.6、§5.3 |
| Q5 | [§4.1 L169](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L169) | 「已发布内容被编辑后再次送审，若结论迟到且内容指纹已变化，旧结论直接丢弃」 | §12.1 自认该说法不准确（status=2 禁改正文/标题/配图），但原文未改；照 §4.1 实现会造出不存在的「已发布重审」分支 | 直接改为「待审期间内容变化、结论迟到」，或加「见 §十二.1」标注 | `[x]` 已修（2026-11-08）：§4.1 改为「待审期间内容变化」，并注明 status=2 禁改、无已发布反复送审路径（见 §十二 第 1 条）。改前：造出不存在的重审分支；改后：与 §12.1 一致。牵连 §10 矩阵、F12、F19 |
| Q6 | [§15.3 L722](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L722)；[L723](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L723)；[F21 L781](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L781) | 「没有对账清扫」；「文件解隔离失败时无补偿」；「补偿解隔离」 | 同一文档一处说缺口存在、一处说已补，读者无法判断 F21 是否生效 | 15.3 加注「已由 F21 覆盖，剩余缺口为阈值未定」，或改写缺口描述；牵连 15.1、F21 判定、本清单 F21 状态；关联 Q7 | `[x]` 已修（2026-11-08，随 Q33）：§15.3 #1/#2 与 F10 行均改为「F21 已补对账清扫 / 解隔离失败由 F21 补偿」。改前：一处说缺口、一处说已补；改后：统一为已由 F21 覆盖。 |
| Q7 | [F10 L396](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L396)；[F11 L415](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L415)；[F21 L781](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L781) | 「不跨库直写业务表」；「AI 不移动或删除存储对象」；「补偿解隔离」 | F21 的补偿由谁执行、能否自动恢复可见、与 review/file 的权限边界均未写，且与「不直写业务表」冲突 | 补执行方矩阵：裁决 = review，状态 = content，存储 = file，F21 只发指令/工单并留痕；牵连 §六 边界表「申诉改判」行、§15.6 第 3 条；关联 Q6、Q8 | `[x]` 已修（2026-11-08）：§六 增「裁决 → 执行矩阵」（7 类动作的裁决方/执行方），并说明 F21 只发指令、不直写业务表。改前：权限边界未写、与不直写冲突；改后：单一裁决方 + 执行方明确。 |
| Q8 | [§4.5 L220](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L220)；[§4.4 L209](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L209)；[§4.2 L190](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L190) | 「本次不生成，提示稍后再试」；「定时回收进池」；「申诉中的图片不清理」 | 三处流程断点：生成失败无重试/终态/配额退还（F9 L708 自认缺）；只覆盖「已认领超时」，未认领无人推进；申诉期间无状态定义，仅提图片不清理 | 分别补：失败重试 1 次→终态→退还配额；未认领 T 小时升级告警；申诉期内容保持原状态、图片不清理、裁决沿用 7 天；牵连 F9、F21、F10、§5.1/5.2；关联 Q7 | `[x]` 已修（2026-11-08，T 待定）：§4.4 增「进池 T 小时无人认领→升级告警派单」与「申诉期内容保持原状、图片不清理、裁决 7 天」，§4.5 生成失败改「重试 1 次→终态→退还配额」。改前：三处断点；改后：均有推进方与终态。T 见第八节 V4。 |

### 6.2 影响（12 条）

| 编号 | 位置（章节 · 行号） | 原文摘录 | 问题说明 | 建议改法与牵连范围 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q9 | [§九 L345](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L345)；[L351–L356](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L351) | 「## 九、待拍板事项」；「强制标识（合规与信任成本最低）」；「90 天，支持用户主动删除」 | 标题仍「待拍板」，D3/D6/D7/D8 四行仍是建议体、未标已定，与 §十六冲突，读者会以为未定 | 标题改「已定事项（D1–D8）」，各行补「已定（2026-11-08）」；关联 Q10–Q16 | `[x]` 已修（2026-11-08）：§九 标题改「已定事项（D1–D8，2026-11-08）」，D3/D6/D7/D8 行补已定标记。改前：标题仍「待拍板」；改后：全部标注已定。 |
| Q10 | [§5.1 L241](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L241)；[L249](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L249) | 「目标时效（建议基线，待拍板）」；「与 D7 的建议取向一致」 | D7 已定「只显示状态 + 预计范围，不承诺具体时长」，表头与备注口径过期 | 改为「内部目标基线（D7 已定：仅对外显示状态 + 预计范围）」；关联 Q9 | `[x]` 已修（2026-11-08）：§5.1 表头已改为内部目标基线并注明 D7。 |
| Q11 | [§七 L307](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L307) | 「以下阈值为建议基线，需与运营/审核负责人共同拍板后固化」 | 决策已定档，措辞仍要求拍板，与 §十六冲突 | 改为「阈值已随 D1/D2 定档，此处为验收基线」；关联 Q9 | `[x]` 已修（2026-11-08）：§七 引言改为「已随 D1/D2 定档，不再另行拍板，调整走翻案」。 |
| Q12 | [§十三 L626](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L626)；[D14 L635](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L635) | 「## 十三、补充待拍板事项（D9–D14）」；「判定理由随审核留痕 6 个月」 | 标题仍「待拍板」；D14 行仍是旧口径，未反映「原因码 + 命中规则 + 置信度、不存全文」 | 标题去「待拍板」；D14 行补精简存储口径；牵连 §5.4、F13.2、D23；关联 Q2、Q9 | `[x]` 已修（2026-11-08）：§十三 标题改「补充决策事项（D9–D14，全部已定）」；D14 行改为「只留原因码 + 命中规则 + 置信度 + 内容指纹 + 三版本号，不存模型原始返回全文」。改前：标题仍「待拍板」、D14 仍是旧口径；改后：与 D14 定档一致。 |
| Q13 | [§十四 L639](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L639)；[L679](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L679) | 「其余项可在实现阶段再定」 | D1–D25 已全部定档，该表述与 §十六相反 | 改为「D1–D25 已全部定档，见 §十六」；关联 Q9 | `[x]` 已修（2026-11-08）：§十四 标题改「决策汇总表（D1–D25 一次过）」，引言改「D1–D25 已于 2026-11-08 全部拍板」。改前：仍写「其余项可在实现阶段再定」；改后：与 §十六 一致。 |
| Q14 | [§14.1 L666](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L666)；[§15.2 L706](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L706) | 「D9 未定时 F16 不排期」；「匿名口径 D8 未定」 | D9、D8 均已定档，仍写「未定」 | 改为「D9 已定：F16 不立项」「D8 已定：公开内容匿名 + IP/设备配额」；关联 Q9、Q15 | `[x]` 已修（2026-11-08）：§14.1 D9→F16 改「D9 已定（维持 search 自建）：F16 不立项、不排期」；§15.2 F7 行改「匿名口径 D8 已定（公开内容匿名可问，历史与个性化需登录）」。改前：仍写「D9 未定」「D8 未定」；改后：与定档一致。 |
| Q15 | [F16 L584](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L584)；[L588](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L588)；[F17 L601](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L601)；[L605](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L605) | 「### F16 搜索与推荐语义能力供给（待拍板）」；「待拍板，不排期」；「### F17 非图片多模态检测（待拍板）」；「待拍板，当前明确排除」 | 与 D9/D10/D11、15.1「明确不纳入」、16.1「不适用」冲突 | 标题去「（待拍板）」；状态改「已定：不立项（归 search）」「已定：本期排除」；关联 Q13、Q14 | `[x]` 已修（2026-11-08）：F16 标题改「（已定：不立项）」、F17 标题改「（已定：本期排除）」，两节状态行分别标注 D9 与 D10、D11。改前：标题带「待拍板」；改后：与 §15.1、§16.1 一致。 |
| Q16 | [F13 L472](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L472)；[L493](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L493)；[L505](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L505) | 「是否强制由 D22 定」；「最终由 D22 拍板」；「由 D23 拍板」 | D22、D23 已定，仍写「由…拍板」 | 改为「已定：强制 + 其他 + 备注必填（D22）」「已定：12 个月（D23）」；关联 Q12、Q9 | `[x]` 已修（2026-11-08）：F13.1 改判原因标签标「已定（D22）：强制」，不填处理改「已定，D22」；F13.2 保留期改「已定（D23，2026-11-08）：12 个月」。改前：三处仍写「由 D22/D23 拍板」；改后：均为已定表述。 |
| Q17 | [§15 L683](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L683)；[§15.1 L687](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L687) | 「对 F1–F17 逐项审计」 | 审计范围声明与汇总只覆盖 F1–F17，F18–F22 新增后未纳入闭环判定，读者无法判断其状态 | 补「F18–F22 未纳入首轮审计」说明，或补齐判定行 | `[x]` 已修（2026-11-08）：§十五 引言补「F18–F22 为后补功能项，不纳入本节逐项与计数，判定见 15.4 与 16.1」。改前：审计范围与合计只覆盖 F1–F17 且无说明；改后：口径边界写明，读者不会误判状态。 |
| Q18 | [§16.2 L870–L876](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L870) | 「以下事项已不需要拍板，但需要外部条件或工程投入才能落地：」 | 正文只列 5 条前置，缺「本地图片模型与算力」（本清单 3.1 有 6 条，§9.1 也写明 D2 硬冲突） | 补第 6 条「本地图片模型与算力」；牵连 §9.1 D2 硬冲突、本清单 3.1 | `[x]` 已修（2026-11-08）：§16.2 补第 6 条「本地图片模型与算力（D2、F2、F11 的前置）」，注明不可用时全量转人工、不回退云 API。改前：正文只列 5 条、与本清单 3.1 的 6 条不一致；改后：两处一致。 |
| Q19 | [§七 F3 L313](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L313) | 「审核员单条裁决平均耗时下降；审核员反馈「证据可用」占比 ≥80%」 | 无基线、无对照样本、数据来源仅为问卷，「下降」不可验收 | 补基线与统计口径（如「较阶段 0 基线下降 ≥20%，N ≥ 200 条」）；关联 Q20 | `[x]` 已修（2026-11-08，数值待定）：§七 F3 改为「较阶段 0 基线下降，基线与最小样本量见 V11；基线未建前只记录、不判达标」。改前：无基线、无样本量；改后：口径明确、数值收口 V11。 |
| Q20 | [§七 F1 L311](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L311) | 「存疑比例与人工日产能匹配（不出现队列持续积压）」 | 「持续」「匹配」无时长与阈值定义，无法判定达标 | 定义为「连续 3 天队列积压 > X 条或平均等待 > Y 小时」；关联 Q19 | `[x]` 已修（2026-11-08，X/Y 待定）：§七 F1 改为「连续 3 天队列积压 > X 条或平均等待 > Y 小时即不达标」，X/Y 登记 V11。改前：「持续积压」「匹配」无定义；改后：可判定。 |

### 6.3 文字（9 条）

| 编号 | 位置（章节 · 行号） | 原文摘录 | 问题说明 | 建议改法与牵连范围 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q21 | [文件头现状声明 L16](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L16)；[§十 矩阵 L377](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L377)；[§七 F1 L311](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L311)；[F12 L444](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L444)；[§4.2 L183](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L183) | 「待人工」；「safe_flag=2 待审」；「自动通过率」；「免审」；「软删」 | 三组术语混用：存疑/待审/待人工；自动通过/免审/放行；隔离/下架/软删。「自动通过」（机审判通过即放行）与「免审」（结论复用放行）未区分 | 附录补定义并在正文统一用词；关联 Q22 | `[x]` 已修（2026-11-08）：附录补「正文用词约定」：结论统一「存疑」、「待审」专指 `safe_flag=2`、通过动作统一「放行」、复用旧结论写「免审」、图片统一「隔离」、到期统一「软删」、文章退出可见写「下架」。改前：三组术语混用无约定；改后：有定义与用词规则。 |
| Q22 | [附录 L880–L888](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L880) | 「## 附录：术语」 | 词条只有 8 个，缺原因码、置信度、过期标记、反馈记录、待审、自动通过、下架、软删、拒传名单 | 补齐上述词条；关联 Q21 | `[x]` 已修（2026-11-08）：附录由 8 条扩到 18 条，补齐存疑、待审、自动通过、放行、下架、软删、拒传名单、原因码、置信度、过期标记、反馈记录，并区分「自动通过 vs 免审」「隔离 vs 下架 vs 软删」。改前：缺 10 个高频词；改后：词条覆盖正文用词。 |
| Q23 | [§1.3 L70](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L70)；[F20 L770](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L770) | 「通知渠道与模板由 notification 负责」 | F20 与 §15.6 第 3 条引入 notification，但 §1.3 模块切分表只有 content/file/review/search/auth，读者找不到归属 | 在 §1.3 补 notification 一行；牵连 §六 边界表 | `[x]` 已修（2026-11-08）：§1.3 模块切分表补 notification 行（职责：按 review 结论事件触达作者；与 AI 无直接交接）。改前：notification 只在 F20/§15.6 出现、无归属说明；改后：§1.3 可查。 |
| Q24 | [§3.1 L118](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L118) | 「F10–F17 为对照最新模块文档补充的功能项（2026-11-08），字段口径与上表一致，见 §十一；本节总表不重复罗列。」 | 只提及 F10–F17，未提 F18–F22 在 §十五，读者按 §3.1 找不全功能项 | 补「F18–F22 见 §十五」 | `[x]` 已修（2026-11-08）：§3.1 表下注补「F18–F22 为闭环审计补充项，见 §十五」。改前：只指 F10–F17；改后：22 项都能从 §3.1 找到落点。 |
| Q25 | [§15.3 #3 L724](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L724) | 「需在文档中写明这一边界」 | §12.4 与 §15.6 第 5 条已写明摘要/标签不进指纹，该备注过期 | 删去「需写明」，改为「已在 §12.4、§15.6 写明」 | `[x]` 已修（2026-11-08）：§15.3 第 3 行备注改为「该边界已在 §12.4 与 §15.6 第 5 条写明」。改前：写着「需在文档中写明」，但已写明；改后：不再残留已完成的待办语。 |
| Q26 | [§16.1 L855–L866](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L855) | 「### 16.1 因决策发生状态跳变的条目」 | 表内缺 F14（D15 改变了其预算规则），状态跳变清单不完整 | 补 F14 行：预算规则由「单预算」→「分账 + 机审优先」，依据 D15 | `[x]` 已修（2026-11-08）：§16.1 补 F14 行（预算规则由单预算变分账 + 机审优先，依据 D15），置于 F17 与 F18 之间保持编号顺序。改前：D15 改变了 F14 规则却不在跳变表；改后：清单完整。 |
| Q27 | [F13.3 L513](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L513)；[L515](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L515)；[L518](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L518) | 「每场景建议 ≥500 条再谈调阈值」；「每版本建议 ≥300 条固定样本」；「最终由 D21 的评测基线统一」 | 500 与 300 的关系未写清（一个是调阈值门槛、一个是评测集总量），「统一」易被读成互相替代 | 注明两者用途不同：评测集 300 条/版本（D21），调阈值需该场景 ≥500 条反馈记录 | `[x]` 已修（2026-11-08）：F13.3 表下注改为「300 条/版本是评测集总量，500 条/场景是调阈值门槛，两者不可替代；『统一』仅指按 D21 基线校准」。改前：500 与 300 关系不明；改后：用途分清。 |
| Q28 | [D15 L844](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L844)；[§5.3 L265](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L265) | 「按用途（机审/摘要/标签/问答）打标」；「按场景（机审文本/机审图片/摘要/标签/问答）」 | 用途枚举粒度不一，计量打标时无法确定是否要拆「机审文本/机审图片」 | 统一用途枚举，建议按 §5.3 的五类 | `[x]` 已修（2026-11-08）：§5.3「成本可见」列为计量打标的唯一定义（机审文本/机审图片/摘要/标签/问答），并注明 D15 的「机审」为前两类合称；D15 单元格同步指向该枚举。改前：D15 四类、§5.3 五类并存；改后：单一枚举。 |
| Q29 | [§十六 L830](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L830) | 改前「D3–D24 见下表」→ 改后「D3、D6–D25 见下表」（初改为 D3、D6–D24，D25 落档后同步扩到 D25） | 表内实际无 D4/D5（在 §9.1），原范围指代错误；属机械修复，已于 2026-11-08 修完 | 已修，无需再动 | `[x]` |

### 6.4 排查批次新增（2026-11-08，Q30–Q77）

> 来源：八个排查批次的逐批记录见 [AI-design-audit.md](AI-design-audit.md)。本小节只登记条目与状态，证据不重复转抄。严重度：阻断 7 条、影响 32 条、文字 9 条。

#### 6.4.1 阻断（7 条）

| 编号 | 位置（章节 · 行号） | 原文摘录 | 问题说明 | 建议改法与牵连范围 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q30 | [§十 场景矩阵 L377](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L377)；[F11 L412](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L412) | 「file：safe_flag=2 待审 → 1 通过 / 0 违规隔离 / 3 存疑」 | 全文使用 content 状态码与 `safe_flag` 却无定义与迁移表；`safe_flag=3` 与 `2` 都可能「不可见等人审」，谁推进终局、写回方是谁未写，易出孤儿态或双写覆盖 | 补「content.status / file.safe_flag 取值与允许迁移」表，分列两套字段并写明推进方与触发事件；`safe_flag=3` 明确定义为存疑子态 | `[x]` 已修（2026-11-08）：§十 新增「状态码与字段定义」「允许迁移与推进方」两表，分列 content.status 与 file.safe_flag，明确 3 为存疑子态、每次迁移的裁决方与执行方，并要求 CAS + 结论序号。改前：全文只用码值、无定义与迁移表；改后：有定义、有推进方。来源：批次一；关联 Q7、Q21、Q8 |
| Q31 | [F10 L402](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L402)；[§12 第 5 条 L624](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L624) | 「进入延迟重试，不得把『查不到』当成『没处罚』」 | 只有「延迟重试」，无次数上限、无终态、无用户侧状态；处罚服务长期异常时内容永久停在中间态 | 补「最多 N 次 / 超 T 转人工复核工单并告警，期间展示『复核中』」；重试耗尽默认维持驳回待复核。N/T 需定值 | `[x]` 已修（2026-11-08，数值待定）：F10 补「最多 N 次 / 最长 T 小时，耗尽转人工复核工单 + 告警 + 作者侧『复核中』」，§12 第 5 条同步指针。改前：只写「延迟重试」；改后：有上限、终态与用户侧状态。N/T 见第八节待定值。来源：批次一；关联 Q8 |
| Q35 | [F12 L442–L443](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L442)；[§5.3 L262](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L262) | 「文本结论按「词库版本 + 提示词版本 + 场景 + 内容指纹」区分」 | 文本复用键缺模型版本、图片键缺词库/提示词维度，违反同段「任一版本变化即失效」与 D16 三版本要求 | 在一处定义统一「结论复用键」，§5.3 与 F12 均引用该处，不再各写一套 | `[x]` 已修（2026-11-08）：复用键在 F12 唯一定义（文本=模型+提示词+词库+场景+指纹；图片=模型+指纹，叠加辅助再并入），§5.3 改为引用。改前：两处各写一套且文本缺模型版本；改后：单一出处。来源：批次二；关联 Q74、Q56 |
| Q43 | [§1.3 L72](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L72)；[§1.3 L74](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L74)；[§六 L297](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L297) | content 行「AI 交回：判定结论、摘要/标签建议」；review 行「AI 交回：判定结论与证据」 | 同一「判定结论」同时交回 content 与 review，未写主接收方与顺序；按 content 行实现会绕过 review 编排与人审 | 明确链式交接：AI → review（唯一接收方）→ 事件 → content / file；content 行改为「经 review 转发的结论事件」 | `[x]` 已修（2026-11-08）：§1.3 content/file 交接物改为「经 review 转发的结论事件」，并加「结论交接是链式，不是广播」注。改前：content 与 review 同时作为结论接收方；改后：唯一接收方为 review。来源：批次三；关联 Q7、Q30 |
| Q57 | [F10 L404](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L404)；[§4.4 L209](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L209) | 「结论事件必须携带内容指纹，供业务方丢弃「内容已改、结论迟到」的陈旧判定」 | 指纹只能识别内容变化、识别不了事件先后；申诉期间内容未改，迟到的机审违规结论会再次下架已恢复内容 | 定义跨事件先后守卫：结论带裁决时间 + 单调序号，业务方只接受序号不小于当前最终裁决的事件 | `[x]` 已修（2026-11-08）：F10 增「先后守卫」（裁决时间 + 单调序号，只接受不小于当前最终裁决的事件），§4.4 加同步注。改前：只用内容指纹防陈旧；改后：增加跨事件先后守卫。来源：批次五；关联 Q31、Q34 |
| Q63 | [文件头 L13–L18](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L13)；[§5.4 L278](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L278)；[F1 L114](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L114) | 「review 的文本机审配置已指向外部 LLM 通道」；「未公开内容、私信…默认不允许发送给外部模型」；F1 覆盖「文章正文、评论、私信、用户名四类场景」 | 主链自相矛盾：文本机审判文章首发/私信时它们都属「未公开」，按 §5.4 无法机审，按现状则每天违反出站红线；属二选一未裁决 | 需产品 + 法务裁决：①私信/未公开改走本地模型；或②改 §5.4 边界并补合规评估。文档不得默认其一 | `[ ]` 待用户裁决（等待外部条件）：需在「私信/未公开改本地模型」与「修改 §5.4 出站边界」之间二选一，文档未擅自定。来源：批次六；关联 Q65、R8 |
| Q74 | [F12 L432–L443](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L432)；[§5.3 L262](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L262)；[Review §2.5 L108](file:///e:/NewJavaPro/LitIsle/docs/Architecture/Review-Architecture.md#L108) | AI：「换版不复用」无时限；review：「`review:last-hash:{wordlibVer}:{bizType}:{scene}:{bizId}`…TTL 72h」 | 同一免审存在两套口径：AI 要求三版本任一变化即失效且无时限；review 已实现键只含词库版本、靠 72h TTL 兜底；谁为准未写 | 以 review 已实现为基线：免审有效期 72 小时；AI 侧复用结论时再做模型/提示词二次校验；F12 与 §5.3 统一引用 review §2.5 键定义 | `[x]` 已修（2026-11-08）：F12 明确免审由 review last-hash 判定、TTL 72h、AI 不另立键，AI 复用结论时二次校验模型/提示词版本；§5.3 同步。改前：两套口径（AI 无时限、review 72h）；改后：以 review 已实现为基线。来源：批次八；关联 Q35、D16、F19 |

#### 6.4.2 影响（32 条）

| 编号 | 位置（章节 · 行号） | 原文摘录 | 问题说明 | 建议改法与牵连范围 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q32 | [F18 L739](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L739) | 「缺送审的入口被拦截并告警」；「作者无感」 | 未写拦截后内容停在哪个状态、作者看到什么、如何补送审；「作者无感」与拦截可能矛盾 | 明确拦截后停在「未提交」并返回可读原因与补送审入口 | `[x]` 已修（2026-11-08）：F18 输出改「停在未提交态」，可见结果改「能看到未提交成功 + 原因」，并补「拦截后如何补送审」段。改前：只写拦截 + 作者无感；改后：有状态、有提示、有补送审路径。来源：批次一；关联 Q8 |
| Q33 | [§15.3 #1 L722](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L722)；[F21 L780](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L780) | 「没有对账清扫」；「补发结论」 | 未写补发用哪一路已有结论还是重新判定，也未写补发与正常消费如何互斥 | 补「补发口径」：以已到达终局为准、缺失一路按存疑转人工；补发带幂等键由 CAS 拒绝重复回写 | `[x]` 已修（2026-11-08）：§15.3 #1 改为「F21 已补对账清扫」，F21 输出补「以已到达终局为准、缺失一路按存疑转人工」，并加「补发与互斥规则」段（幂等键 + CAS + 序号）。改前：只说没有对账清扫、补发口径不明；改后：对账归属与补发口径明确。来源：批次一；关联 Q6、Q7、Q8 |
| Q34 | [F19 L753](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L753)；[F12 L443](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L443)；[D16 L845](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L845) | 「过期标记」；「任一版本变化即旧结论失效」 | 「过期标记」与「失效」两套状态语义关系未写：是否阻断免审、由谁清除、未清除能否举证 | 明确「过期 = 不参与免审复用、仍作历史证据；重判产出新结论后旧结论仅归档」 | `[x]` 已修（2026-11-08）：F19 输出把过期标记定义为「不参与免审复用、仍作历史证据」，增「过期 ≠ 作废」段；F12 同步改为「不再参与复用」。改前：过期标记与失效关系不明；改后：两者语义与归属写清。来源：批次一；关联 Q12 |
| Q36 | [F22 L795](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L795)；[D21 L852](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L852) | F22 输出「质量合格线」；D21「每版本 ≥300 条」 | 只定样本量、未定合格线；D4 未来开启自动写入要求「先达标」，达标线缺失无法判定 | 补可计算合格线，或明确「合格线由 D21 单独定档，当前不作判据」 | `[x]` 已修（2026-11-08，数值待定）：F22 输出改为「质量指标 + 错误分类 + 合格线待 V10，未定前只呈现指标、不作合格判定」。改前：输出写了「质量合格线」但无线；改后：显式待定、不误用。来源：批次二；关联 Q46 |
| Q37 | [D16 L847](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L847)；[F19 L752](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L752) | 「抽样可按风险分层（建议 5%–10%，初值）」；「按周期抽样」 | 抽样比例只在前提里且为初值；F19 无比例、无周期定义 | 把「抽样比例 + 周期」列为待定值参数，并标明初值与责任人 | `[x]` 已修（2026-11-08）：F19 补「抽样参数」：按风险分层、高风险全量、D16 的 5%–10% 仅作初值、正式比例与周期登记 V7、校准责任方指向 AI-Arch §11.3。改前：F19 无比例与周期；改后：参数有归属、初值有出处。来源：批次二；关联 Q20、Q59 |
| Q38 | [F13.3 L520](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L520) | 「下降超过 20%…高于基线 1.5 倍…超过 5%…数值为初值」 | 三阈值无基线、无校准责任人、无校准时点；「基线」本身依赖尚未建的反馈数据 | 标注待定值，列基线采集指标与周期，与 Q20/Q72 合并为告警阈值表 | `[x]` 已修（2026-11-08，随 Q72）：AI-Arch §11.3 成为阈值唯一出处，并补「校准责任方（算法 / 审核运营）」「首次校准在阶段 0 结束」「基线未建立时沿用 review 的 50/100 条」；业务指南 F13.3 改为业务含义 + 链接。改前：三阈值无基线、无责任人、无时点；改后：三项齐全。来源：批次二；关联 Q20、Q72 |
| Q39 | [§5.3 L263](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L263)；[F14 L561](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L561)；[D5 L846](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L846) | 「普通用户先按日低配额，按用户等级逐级放开」 | 只定机制，所有数值缺失（日次数、等级系数、VIP 上限、日/月预算） | 集中列「生成式配额与预算参数表」，每项标待用户定值 | `[x]` 已修（2026-11-08）：§5.3 增「生成式配额与预算参数」表（5 个参数，全部标「待用户定值 V8」，未预设暂定值）；F14 机制保持。改前：只有机制、无数值清单；改后：参数有清单、取值统一收口。来源：批次二；关联 Q71、R2 |
| Q40 | [§七 F2 L312](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L312)；[§5.1 L247](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L247) | 「申诉成功后 30 分钟内恢复可见」；「申诉 7 天内裁决」 | 「30 分钟」只在验收表，正文无恢复时效要求，易与 7 天混淆 | 正文补「裁决落库后 30 分钟内完成解隔离与恢复」，注明不含裁决等待 | `[x]` 已修（2026-11-08）：§5.1 表下补「裁决落库后 30 分钟内完成解隔离与恢复可见（不含 7 天等待期）」，与 §七 F2 验收对齐。改前：30 分钟只在验收表；改后：正文有同口径要求。来源：批次二；关联 Q48 |
| Q41 | [§5.1 L244](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L244)；[§5.2 L253](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L253) | 「95% 在 60 秒内出结论」；「最多 5 次（约 15 分钟量级）」 | 「60 秒」未定义计时起点、是否含重试；含重试时 95%/60 秒必然不达标 | 定义计时口径（入队→首次结论或进入重试），另设含重试 P95 | `[x]` 已修（2026-11-08）：§5.1 表下补 60 秒计时口径（入队→首次结论，含首次重试等待），端到端另设 P95 指标。改前：未定义起点与是否含重试；改后：口径可计算。来源：批次二；关联 Q70、Q19 |
| Q45 | [F13.4 L526](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L526)；[D19 L850](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L850) | 「提示词变更由 D19 指定的责任人确认」 | D19 只写角色未指定岗位，指向空指针 | 落实为「算法值班评估、审核运营负责人审批」或等效可执行角色 | `[x]` 已修（2026-11-08，角色待定）：F13.4 第 3 条改为「具体岗位待用户定值 V9，建议算法值班评估 + 审核运营负责人审批，文档不预设责任人」。改前：指向 D19 空指针；改后：缺口显式、候选角色写明。来源：批次三；关联 Q46、R4 |
| Q46 | [D4 L848](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L848)；[F22 L798](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L798) | 「将来开启自动写入必须…满足发布级闸门」；「是否开启仍由 D4 的回退条件决定」 | 只写前置条件，未写谁有权拍板开启，最终无人负责 | 补「产品负责人提议、审核与法务会签」，并附 D21 评测报告 | `[x]` 已修（2026-11-08，决策组待定）：F22 补「开启自动写入的决策组」段（待 V9，建议产品提议 + 审核与法务会签 + D21 报告；落定前维持默认不自动写入）。改前：无人负责开启；改后：决策组有候选、默认态不变。来源：批次三；关联 Q36 |
| Q47 | [§5.4 L273](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L273)；[§15.2 L708](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L708)；[F11 L415](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L415) | 「生成式输入输出…支持用户删除」；F11「AI 不移动或删除存储对象」 | 未写删除由谁执行、入口在哪、谁验证、是否留审计；责任悬空且易被读成 AI 一律不删 | 明确 AI 受理删除、按对象类型分派（file/content），异步任务 + 回执 + 审计 | `[x]` 已修（2026-11-08）：§5.4 生成式行补删除执行方（AI 受理并分派，会话自清、正文/配图发指令给 content/file，异步 + 回执 + 审计，AI 不直接删存储）。改前：只写「支持用户删除」；改后：责任与路径明确。来源：批次三；关联 Q8 |
| Q48 | [F11 L425](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L425)；[§4.2 L186](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L186) | 「短期拒传名单在申诉平反时必须移除」 | 未写名单归属、由谁在什么事件移除；F21 补偿清单未含此项 | 指定名单归属（建议 file 维护、review 在改判事件下发移除），并入 F21 补偿动作 | `[x]` 已修（2026-11-08）：F11 明确拒传名单由 file 维护、review 在改判事件下发移除、file 执行回执，F21 对账纳入校验。改前：只写「必须移除」；改后：归属与触发写清。来源：批次三；关联 Q7、Q33 |
| Q50 | [§4.5 L217](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L217)；[§15.6 L822](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L822) | 「AI 限流 / 配额用尽 → 推迟调用」；「机审预算打满降级为全量人工」 | 同一「配额打满」两种处置，未区分瞬时限流与当天预算打满 | 拆两种情况：限流/瞬时→延迟重试；预算打满→立即转人工 + 告警且不自动重试 | `[x]` 已修（2026-11-08）：§4.5 把「限流 / 配额用尽」拆成「瞬时配额不足→延迟重试」与「当天预算打满→立即转人工 + 告警、机审优先」。改前：同一行两种处置；改后：按两种成因分开。来源：批次四；关联 Q4 |
| Q51 | [§4.5 L219](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L219)；[§5.2 L253](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L253) | 「模型未配置 / 模型文件缺失 → 全量转人工」 | 已判定「确定不可用」仍走单条 5 次重试，每条白等约 15 分钟并持续无效调用 | 补短路规则：自检判不可用直接转人工并带标记，自检恢复后切回 | `[x]` 已修（2026-11-08）：§4.5 模型缺失行补「自检判不可用直接转人工、不走单条 5 次重试、恢复自动切回」，§5.2 同步。改前：已判不可用仍逐条重试；改后：短路规则明确。来源：批次四；关联 Q8、R1 |
| Q52 | [AI-Arch §九 L415](file:///e:/NewJavaPro/LitIsle/docs/Architecture/AI-Architecture.md#L415) | 「熔断/隔离：设计存在两种口径…选型待确认」 | 只有按条重试，无服务级熔断行为；整体故障时无效调用放大 5 倍 | 补「全局熔断」：连续失败 N 次打开，期间新任务直接转人工、在途停止重试；半开恢复 | `[x]` 已修（2026-11-08）：AI-Arch §九「熔断/隔离」改为按用途计数的服务级熔断（打开/半开/恢复行为齐全），并明确复用现有 Redis 计数、不新增 Sentinel/Resilience4j；业务指南 §4.5「服务整体不可用」行同步。改前：只有两种选型待确认、无行为；改后：行为确定、选型收敛、N 待定值（见 V5）。来源：批次四；关联 Q51、IMP2 |
| Q53 | [§4.5 L213](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L213)；[AI-Arch §九 L410](file:///e:/NewJavaPro/LitIsle/docs/Architecture/AI-Architecture.md#L410) | §4.5 全表无「恢复」行 | 降级解除后的动作缺失：在途任务继续还是转人工、降级期结论是否打标 | 补恢复动作与「降级期结论」标记，供 F13/F19 剔除或单列 | `[x]` 已修（2026-11-08）：§4.5 增「降级恢复」行与「降级期结论」注（degraded=true 不计入评估/重判基线，在途任务继续）。改前：无恢复动作；改后：恢复与标记规则明确。来源：批次四；关联 Q51、Q52 |
| Q54 | [§4.5 L221](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L221)；[§5.5 L289](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L289) | 「知识库不可用 → 降级为不引用回答，并明确标注」 | 无引用回答可能混入模型自身知识却仍以站内助手身份呈现，与「引用必须可溯源」红线关系未写 | 明确无引用时显著标注「未引用站内内容」，事实性问题只答「暂无相关资料」或停用 | `[x]` 已修（2026-11-08）：§4.5 知识库降级行改为「显著标注『未引用站内内容』，事实性问题只答『暂无相关资料』」。改前：只说「明确标注」；改后：给出可观察口径。来源：批次四；关联 R7 |
| Q56 | [F12 L442](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L442)；[D16 L847](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L847) | 「文本结论按「词库版本 + 提示词版本 + 场景 + 内容指纹」区分」 | 未规定版本号如何生成；秒级时间戳在同秒多次变更时无法区分 | 明确版本为按类型各自发号的单调递增序列，禁止用秒级时间戳作复用键 | `[x]` 已修（2026-11-08，随 Q35 一并完成）：F12 已写明「按各自发布单调递增发号，不得用秒级时间戳充当版本」。改前：版本号生成方式未定；改后：发号规则明确。来源：批次五；关联 Q35 |
| Q58 | [F11 L419](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L419)；[AI-Arch §5.2 L222](file:///e:/NewJavaPro/LitIsle/docs/Architecture/AI-Architecture.md#L222) | 「上传后先落「待审」占位」；「file CAS safe_flag 1/0/3」 | 上传预审与配图聚合复审两路并发写同一 `safe_flag`，未写 CAS 期望值/结论序号与冲突优先级 | `safe_flag` 写回带结论序号与期望旧值；聚合复审优先；同 sha256 是否共享结论需明确 | `[x]` 已修（2026-11-08）：F11 补「两路并发写回」：CAS 带期望旧值 + 结论序号、聚合复审优先、同 sha256 命中 F12 复用键才共享结论。改前：两路并发写同一字段无冲突规则；改后：有 CAS、优先级与共享条件。来源：批次五；关联 Q30、Q33 |
| Q59 | [F19 L753](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L753)；[D16 L847](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L847) | 「触发时机：版本变更发布时；按周期抽样」 | 两个触发源可能命中同一批内容，无去重键，重复重判与结果互覆盖 | 定义重判任务键 = 内容指纹 + 目标版本；已完成跳过；周期抽样只补未重判 | `[x]` 已修（2026-11-08）：F19 补「两个触发源去重」：重判任务键 = 内容指纹 + 目标版本，同键只建一次、已完成跳过，周期抽样只补未重判内容。改前：两个触发源可重复重判且结果互覆盖；改后：有去重键与跳过规则。来源：批次五；关联 Q37、Q34 |
| Q60 | [F13.1 L460](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L460)；[F13.5 L534](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L534) | 「任务超时未处理由 F21 写一条「过期」记录」 | 对账反复扫描会重复写过期记录，放大积压与时效统计 | 过期记录以「任务标识 + record_type=过期」为唯一键，重复扫描幂等跳过 | `[x]` 已修（2026-11-08）：F13.1 补「过期记录唯一键 = 任务标识 + 记录类型=过期，F21 反复扫描幂等跳过」。改前：反复扫描会重复写过记录；改后：幂等。来源：批次五；关联 Q33、Q61 |
| Q62 | [F14 L559](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L559)；[F9 L319](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L319) | 「配额按用户与场景分别计量」；15.2「配额扣减原子性…未写」 | 并发双击/多端可同时读余额再各自扣减；未定扣减顺序与失败退还方式 | 明确先原子预扣（失败退还）或先占位后结算；同 `requestId` 只扣一次；并发超额后到拒绝 | `[x]` 已修（2026-11-08）：F14 补「原子预扣、失败退还、同 requestId 只扣一次、并发超额后到者拒绝」。改前：只说分别计量；改后：扣减语义明确。来源：批次五；关联 Q8、Q39 |
| Q64 | [§5.5 L287](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L287)；[D3 L836](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L836) | 「生成式内容必须标注…作者确认后才进入正文」；「生成式输入输出 90 天」 | AI 标识生命期未写：采用并写入正文后标识是否保留、适用哪套留存、删除权归谁 | 明确「采用即转正式内容，标识按 D3/D13 继续展示；留存与删除按 content 规则」 | `[x]` 已修（2026-11-08）：§5.5 增「AI 标识的生命期」：采用后随正文持续展示至删除，正文按 content 规则留存/删除，会话副本仍 90 天清理。改前：未写采用后标识生命期；改后：标识与留存归属写清。来源：批次六；关联 Q47 |
| Q65 | [§5.4 L280](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L280) | 「优先片段/摘要而非全文；能脱敏则脱敏；禁止附带用户身份信息」 | 只有原则无可执行规则：脱敏字段、执行方、校验点、审计抽样均空白 | 补「外发允许/必须脱敏/禁止字段」清单，指定 AI 供给层统一脱敏并写审计抽样 | `[x]` 已修（2026-11-08）：§5.4 增「脱敏的可执行规则」表（执行方、必须脱敏字段、脱敏也不发的字段、永不外发字段、双校验留痕、审计抽样）；抽样比例未自填，登记 V6。改前：只有四句原则；改后：可执行到字段与拦截点。来源：批次六；关联 Q63、R8 |
| Q66 | [D24 L855](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L855)；[F13.2 L505](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L505) | 「训练需单独授权并去标识」 | 授权链路缺失：入口、范围、期限、撤回、撤回后数据处理均未写 | 补授权入口与记录字段、撤回处理；牵连 R5、D21 | `[x]` 已修（2026-11-08，法务细则待定）：F13.2 增「训练用途的授权链路」表（入口默认关闭、记录字段、期限与撤回待 V12、未授权数据用途限制）。改前：只有一句「需单独授权并去标识」；改后：链路可落地、法务参数显式待定。来源：批次六；关联 R5 |
| Q67 | [D14 L845](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L845) | 「举证靠固定评测集重跑」 | 未写重跑不可行（版本下线、换版、评测集污染）时如何举证 | 补「证据包」定义与重跑失败时的举证优先级 | `[x]` 已修（2026-11-08）：§5.4 增「结论举证（D14 补充）」：常规靠结论记录，复现靠评测集重跑；版本下线或评测集变更时用「结论记录 + 发布评测报告 + 人工复核记录」证据包，重跑非唯一方式。改前：只写重跑；改后：不可重跑也有举证路径。来源：批次六；关联 Q2、R3、R4 |
| Q69 | [§七 L308](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L308)；[§八 L327](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L327) | §七 表体只有 F1–F9 | F10–F22 无验收口径，F21 作为 P0 兜底也无达标线 | §七 增补 F10–F22 验收行，§八 纳入 F21 演练验收 | `[x]` 已修（2026-11-08）：§七 补 F10–F22 共 13 行（F16/F17 标不适用），验收点均为可观测结果、未引入新阈值；§八 阶段 0 验收补「F21 对账演练通过」。改前：表体只有 F1–F9；改后：22 项均有验收口径。来源：批次七；关联 Q6、Q20 |
| Q70 | [AI-Arch §11.1 L457](file:///e:/NewJavaPro/LitIsle/docs/Architecture/AI-Architecture.md#L457)；[§5.1 L244](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L244) | `ai_call_latency{purpose}`；「95% 在 60 秒内出结论」 | 指标只测 provider 调用，不含排队/重试/落库，无法证明端到端验收；P95 统计窗口未定义 | 补端到端指标 `moderation_e2e_latency{scene}`，验收与告警统一引用，并定义窗口 | `[x]` 已修（2026-11-08）：AI-Arch §11.1 增 `moderation_e2e_latency{scene}`（入队→落库），并写窗口口径（5 分钟滚动 + 日聚合），§5.1 与 §11.3 统一引用。改前：只有 provider 调用时延；改后：端到端可测。来源：批次七；关联 Q41 |
| Q71 | [§5.3 L267](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L267)；[§七 F9 L320](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L320) | 「按场景…与按用户可查用量与花费」；`ai_token_usage{purpose}` | 指标不带 user 标签，未给按用户聚合的数据来源，F9 验收无法计算 | 明确按用户用量落点（如 `ai_usage_daily` 汇总表）与日/月切规则 | `[x]` 已修（2026-11-08）：AI-Arch §11.1 增「按用户用量」：明细按日汇总到 `ai_usage_daily(user_id, purpose, date, token_in, token_out, cost)`，F9 与报表读该表，指标不做用户标签。改前：无按用户数据来源；改后：落点明确。来源：批次七；关联 Q39 |
| Q72 | [F13.3 L520](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L520)；[AI-Arch §11.3 L480](file:///e:/NewJavaPro/LitIsle/docs/Architecture/AI-Architecture.md#L480) | 「过期任务占比超过 5%」；「积压超 50 条 / DLQ 超 100 条」 | 同类告警两份文档各写一套，无单一出处，改一处会与另一处漂移 | 指定 AI-Architecture §11.3 为唯一出处，业务指南只留业务含义并链接；补基线/校准人 | `[x]` 已修（2026-11-08）：AI-Arch §11.3 加「唯一出处约定」与校准责任方（算法/审核运营，岗位待 Q45 落定）、基线未建立时沿用 50/100 条；业务指南 F13.3 删去重复数值、改为业务含义 + 链接 + 未校准前的沿用值。改前：两处各一套阈值；改后：单一出处，数值不丢（全部保留在 §11.3）。来源：批次七；关联 Q38、Q52 |
| Q75 | [§4.6 L233](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L233)；[Search L92](file:///e:/NewJavaPro/LitIsle/docs/Architecture/Search-Architecture.md#L92) | 「回答展示前校验引用文章当前仍可见；下架则去掉该引用」；search「article-updated 只更新元数据、不动 chunks」 | search 改稿不重建切片，AI 只覆盖「下架失效」，未覆盖「改稿后引用与正文不一致」 | 补「改稿一致性」：引用返回带文章版本，与切片构建版本比对；不一致移除或标注 | `[x]` 已修（2026-11-08）：§4.6 流程图与说明补两条失效路径（仅元数据更新→标注「内容已更新」；正文已改而切片未重建→移除引用），并要求引用返回带「文章版本 + 切片构建版本」做比对。改前：只防下架、不防改稿；改后：下架与改稿两条防线独立。来源：批次八；关联 Q64 |

#### 6.4.3 文字（9 条）

| 编号 | 位置（章节 · 行号） | 原文摘录 | 问题说明 | 建议改法与牵连范围 | 状态 |
| --- | --- | --- | --- | --- | --- |
| Q42 | [§5.4 L272](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L272)；[F11 L424](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L424) | 「软删后仍有恢复期，期满物理删除」；「生成式输入输出 建议 90 天，可配置」 | ①恢复期全文无天数；②生成侧仍写「建议 90 天，可配置」，与 D6 已定 90 天冲突 | ①补恢复期 = 7 天（file 文档权威值）；②改「已定 90 天（D6）」，「可配置」只指清理执行时间 | `[x]` 已修（2026-11-08）：§5.4 补软删恢复期 7 天（对齐 File 文档）；生成式留存改「已定 90 天（D6）」，「可配置」限定为清理执行时间。改前：恢复期无天数、留存写「建议/可配置」；改后：两个值都定档。来源：批次二；关联 D6、D14 |
| Q44 | [§六 L294](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L294)；[§1.3 L76](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L76) | §六 表头只有 5 个模块列；§1.3 有 auth / user | §六 缺 auth/user 列，读者找不到用户名判定与封禁下线归属 | §六 增 auth/user 列并补两行；notification 缺列见 Q23 | `[x]` 已修（2026-11-08）：§六 增「auth / user」列，并补「用户名判定」与「处罚与封禁→执行封禁/解封」「申诉改判→解除封禁」归属。改前：用户名判定与封禁下线无列可落；改后：职责完整。来源：批次三；关联 Q23 |
| Q49 | [AI-Arch §1.1 L32](file:///e:/NewJavaPro/LitIsle/docs/Architecture/AI-Architecture.md#L32) | 「裁决 ｜ review…｜ 业务指南 §1.3、§六；Checklist Q7」 | 架构文档把仍未决的 Q7 当作已决结论的出处 | 出处列删去 Q7，只留正文锚点；Q7 关闭后再回填 | `[x]` 已修（2026-11-08）：AI-Arch §1.1 出处列删去「Checklist Q7」，只留正文锚点；Q7 关闭后再回填。改前：把未决问题当已决出处；改后：不引用未关闭项。来源：批次三；关联 Q7 |
| Q55 | [AI-Arch §九 L414](file:///e:/NewJavaPro/LitIsle/docs/Architecture/AI-Architecture.md#L414)；[AI-Arch §1.2 L50](file:///e:/NewJavaPro/LitIsle/docs/Architecture/AI-Architecture.md#L50) | 「超时参数待定稿」；「最多 5 次，基准 30s，指数退避…」 | 未区分 provider 单次调用超时与送审重试参数，读者误以为重试也未定 | 注明重试沿用 review 既有 5 次/30s 基准，待定稿的仅是 provider 单次超时 | `[x]` 已修（2026-11-08）：AI-Arch §九注明「仅 provider 单次调用超时待定稿，送审重试沿用 review 既有 5 次 / 30s 基准」。改前：读者误以为重试也未定；改后：两类参数分开。来源：批次四；关联 Q41 |
| Q61 | [F13.1 L464](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L464) | 「由任务 + 操作序号生成」 | 未说操作序号由谁生成；客户端生成会导致冲突或重复计数 | 改为「服务端按任务 + 操作类型 + 客户端幂等键生成」，重试复用同键 | `[x]` 已修（2026-11-08）：F13.1 记录标识改为「服务端按任务 + 操作类型 + 客户端幂等键生成，重试复用同键」。改前：未说谁生成；改后：生成方与幂等语义明确。来源：批次五；关联 Q60 |
| Q68 | [F13.2 L506](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L506) | 「内容只留标识、指纹与必要片段」 | 「必要片段」无长度范围，可能与 D14 精简留存冲突 | 加边界（如 ≤200 字、仅命中段落、不含个人信息）或改为只留指纹 + 偏移 | `[x]` 已修（2026-11-08）：F13.2 给「必要片段」加边界（仅命中段落、≤200 字、不含身份信息，能只留指纹 + 偏移时优先不存）。改前：无长度范围；改后：与 D14 精简留存不冲突。来源：批次六；关联 Q2、Q12 |
| Q73 | [§七 L323](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L323) | 「运维能在 5 分钟内从告警定位」 | 无演练方式与可判定完成标准 | 改为可演练判据：停图像模型→1 分钟内告警→值班 5 分钟内确认，附 Runbook | `[x]` 已修（2026-11-08）：§七 改为可演练判据（停用图片模型→1 分钟内告警→值班 5 分钟内按 Runbook 确认并处置，无 Runbook 视为未通过）。改前：无法验收；改后：可演练。来源：批次七；关联 Q41 |
| Q76 | [F12 L428](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L428)；[§六 L297](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L297) | 「F12 结论复用与免审供给」 | 「免审供给」易读成 AI 决定免审，与「AI 不决定内容命运」冲突 | 标题改「结论复用供给」，正文注明免审由 review last-hash 判定、content 直落 | `[x]` 已修（2026-11-08）：F12 标题改「结论复用供给」，标题下保留旧名注（便于检索）并写明免审由 review last-hash 判定；清单 §二 F12 行同步。改前：标题易读成 AI 决定免审；改后：职责不越界。来源：批次八；关联 Q21、Q43 |
| Q77 | [§4.2 L176](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L176)；[§15.2 L377](file:///e:/NewJavaPro/LitIsle/docs/Study/AI-Business-Guide.md#L377) | §4.2 流程「图片上传 → 归属校验 → 送审」 | 流程图漏缓存/黑名单分支，与 §15.2「缓存未命中才送审」及 file 文档「缓存 PASS 免审直落」不一致 | §4.2 补分支「缓存/黑名单命中→直落或拒传；未命中→送审」，并链接 F12 复用键 | `[x]` 已修（2026-11-08）：§4.2 流程图补「命中黑名单→拒传；命中有效 PASS 结论→直落通过不送审；未命中→送审」，要点补「命中同样留痕」。改前：漏缓存/黑名单分支；改后：与 §15.2 及 file 文档一致。来源：批次八；关联 Q35 |

## 七、风险登记

> 与第六节区分：Q 是「文档写错/写漏」，R 是「文档没写错，但外部条件不满足时会咬人」。状态：`[ ]` 无缓解 / 等待外部条件；`[~]` 文档侧已缓解、仍有外部前置；`[x]` 已缓解。共 11 条，影响面按「用户可见 / 审核人力 / 成本 / 合规」标注。

| 编号 | 风险描述 | 触发条件 | 影响面 | 当前是否已有缓解 | 需要谁决定或执行 | 状态 |
| --- | --- | --- | --- | --- | --- | --- |
| R1 | 本地图片模型文件缺失、推理算力未保障，图片机审无法真正上线 | 阶段 2 图片机审上线时模型或算力仍未就绪 | 审核人力（全部图片转人工）；用户可见（上传后等待变长） | 有降级：模型不可用一律转人工，绝不默认放行；无算力保障。见 3.1「本地图片模型与算力」 | 基础设施 + 算法；产品确认上线门槛 | `[ ]` 等待外部条件：模型文件与算力属基础设施排期 |
| R2 | 计量未按用途区分、生成预算打满时可能连带停掉机审 | 生成调用量上涨触发预算上限；计量仍只有全站 QPS | 成本；用户可见（审核变慢）；合规（漏审风险） | D15 已定「机审预算优先、生成打满只停生成」；计量改造是实施前置，尚未落地。见 3.1、Q4 | 后端 + 财务；产品定预算科目 | `[ ]` 等待外部条件：计量改造与预算科目属工程/财务投入 |
| R3 | 结论无版本标识，模型/提示词/词库升级后新旧口径长期共存 | 任一版本升级后，旧结论未标注过期 | 审核人力（无法判断旧结论是否仍可信）；合规（举证口径不一致）；用户可见（误放/误判） | D14/D16 要求带版本，但代码未把版本随结论落库。见 3.1、Q2 | 后端 + 算法 | `[ ]` 等待外部条件：结论版本标识落库属实现前置，文档侧口径已一致（D14、F12、F19） |
| R4 | 固定评测集未建立，无法判断模型能否放开自动写入或调整阈值 | 想提高自动通过率、调阈值、换模型时 | 合规（无效果证据）；审核人力（误判回流）；用户可见 | D16/D21/D24 定了方法（≥300 条/版本），数据集与标注人力未落实。见 3.1、Q19/Q20 | 算法 / 运营；产品批标注预算 | `[ ]` 等待外部条件：评测集与标注人力未落实 |
| R5 | 反馈与申诉数据用于训练的合规边界未落地（用户授权、去标识） | 想把反馈数据用于训练/微调，或跨版本复用 | 合规（个人信息处理、授权缺失）；用户信任 | D24 已定「仅评估 + 脱敏评测集，训练需单独授权并去标识」；授权链路已写入 F13.2（入口/记录/范围/撤回），但期限与撤回后处理待法务（V12） | 法务 + 产品 + 算法 | `[~]` 文档侧已补授权链路，法务细则待定 |
| R6 | 审核人力是单点：所有「存疑」最终落人工，人力不足时整条链路积压 | 存疑比例偏高、大促、批量导入集中发生 | 审核人力；用户可见（长期停在「审核中」）；合规（积压掩盖漏审） | 有队列回收、超时升级、入口限流（F21、§5.1），但无人力兜底与排班承诺。见 Q8 | 运营排班 + 产品定容载与 SLA | `[ ]` 等待外部条件：审核人力与排班承诺 |
| R7 | 外部模型服务不可用时的降级路径未定稿 | 外部 LLM 超时、限流、欠费或区域不可达 | 用户可见（生成不可用、审核转人工变慢）；成本 | 已有「不可用→转人工 / 提示稍后再试」，但重试次数、终态与配额退还未写。见 Q8 | 后端 + 产品 | `[~]` 文档侧已补（重试 5 次、失败退还、服务级熔断），N 待 V5 |
| R8 | 敏感信息进入模型上下文（用户原文、私信/草稿内容）的边界未写 | 问答拼接用户原文、草稿或私信内容送入外部模型 | 合规（个人信息出境/第三方处理）；用户信任 | D2 控制图片外发；文本上下文边界未定义。见 Q2、Q7 | 法务 + 安全 + 后端 | `[~]` 脱敏规则与出站过滤器已写入 §5.4，出站范围二选一待 V3 |
| R9 | 懒删式留存造成过期数据滞后：用户停止使用后不再触发清理 | 用户长期不产生新写入，超期会话/内容无人回收 | 合规（超期留存）；成本（存储无界增长） | 阅读历史已有「随写入懒删」先例（只在插入新行时触发）；AI 侧尚未实现，是否加定时清理未定。见 D6、D25 | 后端；产品确认是否加定时清理任务 | `[ ]` 等待外部条件：是否加定时清理由产品决定 |
| R10 | 违规图片的到期清理依赖归档任务，而该任务默认关闭 | AI 上线时未同步启用归档/清理任务 | 合规（违规图片长期留存）；成本 | §5.4 已写明「现有归档任务默认关闭，需随本模块启用」，但目前无人负责开启与验证。见 3.1 | 运维 + 后端 | `[ ]` 等待外部条件：归档任务启用属运维排期 |
| R11 | Redis 同键共用：`ReviewTaskSweeper` 的去重前置锁与 `ModerationTextService#tryLockInFlight` 共用同一个键 | 任一侧单独迁移键名或改变值类型（如改成 Hash/List） | 另一侧读写触发 `WRONGTYPE`，去重与在途锁同时失效，可能重复消费或任务永久卡死 | 目前无代码层约束，只靠口头约定 | review 后端；两侧必须同一批迁移、同批复核，禁止单边改键 | `[ ]` 等待外部条件：约束已登记，待实现侧落成代码级约定（如同一常量/同一迁移脚本） |

## 八、待用户定值

> 依修复规则：需要新定值才能收口的参数不自行填写，集中在此等待用户决定。定值后回填正文，并同步对应 Q 条状态。

| 编号 | 参数 / 选择 | 影响章节 | 建议值区间 | 不给值的后果 |
| --- | --- | --- | --- | --- |
| V1 | 申诉恢复前「处罚态回查」重试次数 N（见 Q31） | F10 业务规则、§12 第 5 条 | 3–5 次（与机审重试 5 次同量级） | 只能写「有限次重试」，实现可能无限重试或过早转人工，中间态依然不可判 |
| V2 | 申诉恢复前「处罚态回查」总时长 T（见 Q31） | 同上 | 1–6 小时 | 无法界定「多久算耗尽」，告警与复核工单的触发时点无法判定 |
| V3 | 文本机审出站边界二选一（见 Q63）：①私信/未公开内容改走本地模型；②修改 §5.4 允许外发并补合规评估 | §5.4、F1、文件头现状声明 | 二选一；合规优先建议 ① | 现状与红线冲突持续：实现要么违反「不外发」红线，要么私信/未公开内容无法机审 |
| V4 | 人工队列「进池后无人认领」升级时限 T（见 Q8） | §4.4、F21 | 2–8 小时（与人工复核 24 小时目标同量级） | 未认领任务可能长期无人推进，只剩「已认领超时回收」一条兜底 |
| V5 | 服务级熔断的连续失败次数 N（见 Q52） | AI-Arch §九、业务指南 §4.5 | 5–10 次；与 review 单条 5 次重试同量级，建议取 5 次（窗口 5 分钟） | 熔断行为已写死但打开条件缺失，实现只能自定或永不打开，整体故障时无效调用仍会放大 |
| V6 | 外发审计的人工抽样比例与周期（见 Q65） | §5.4 脱敏规则、R8 | 每月固定抽样 50–100 条，或 1%–5% 比例；周期按月 | 脱敏是否真的执行无独立验证，出站审计只剩自查 |
| V7 | 存量结论重判的抽样比例与周期（见 Q37） | F19、D16 前提 | 低风险层 5%–10%，高风险层全量；周期按月或按版本发布触发 | 抽样口径不定，「按周期抽样」无法执行，重判覆盖面不可控 |
| V8 | 生成式配额与预算参数组（见 Q39）：普通用户日次数、等级系数、VIP 上限、生成日/月预算、机审预算 | §5.3、F9、F14、D5 | 普通用户 5–20 次/日；等级系数 1.5–3 倍；VIP 为普通 5–10 倍；预算按调用量估算后定 | 机制已定但无一个可执行数值，F9 与 F14 无法验收，预算熔断无法触发 |
| V9 | 责任岗位与决策组（见 Q45、Q46）：提示词变更评估/审批人；自动写入开启的拍板组 | F13.4、F22、D4、D19 | 提示词变更＝算法值班评估 + 审核运营负责人审批；自动写入＝产品提议 + 审核与法务会签 | 两处指向空指针：提示词变更无人签，自动写入永远没人敢开或一线私自开 |
| V10 | 质量合格线的指标构成与阈值（见 Q36） | F22、D21、D4 | 建议：人工可用性评分 ≥4/5 占比 ≥80%、关键事实错误率 ≤1%、场景准确率按基线定；三项全部纳入 | F22 只能出报告不能判合格，D4「达标才可自动写入」永远不成立 |
| V11 | 验收基线组（见 Q19、Q20）：F3 基线样本量与降幅；F1 积压 X 条 / 平均等待 Y 小时 | §七 F1、F3 | F3：阶段 0 基线、样本量 ≥200 条、降幅 ≥20%；F1：X 与 review 已配置的 50 条存疑积压对齐，Y 取 6–12 小时 | 两条验收仍写「下降/匹配」，无法判定达标，首期上线验收没有结论 |
| V12 | 训练数据授权的期限与撤回后处理（见 Q66） | F13.2 授权链路、D24、R5 | 期限建议 2 年，到期需重新授权；撤回后是否追溯已训练模型由法务确认 | 授权链路有入口无规则，撤回后如何处理无依据，合规举证不完整 |
