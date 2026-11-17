# ai 板块十五：写作辅助扩展——多级改写建议与文章草稿（F6 延伸，建议态）

> 归档自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块 · 板块十五：写作辅助扩展——多级改写建议与文章草稿」（15.1/15.2 于 2026-11-16 落地，2026-11-17 归档），条目原样搬运不压缩（含口径修正、注意事项与重合归并标注）。
> 关联回收：板块十二 4.7「标题/开头多版建议」按重合归并口径并入 15.1 TITLE 粒度统一承载（归属校验随 articleId 入口落地），不重复建设。
> 运行态验证：AI-V6 ⑤/⑦（归属校验与候选数上限口径）见 [next_verify.md](../../next/next_verify.md)。

## 15.1 多级改写建议端点（标题 / 段落 / 句子 / 词语）

- [x] POST /ai/xiaoyue/rewrite（设计锚点，落地前 Api-Architecture 定稿）+ /chat 意图路由新增 REWRITE 意图：按目标粒度生成优化版本候选与优化建议，供作者参考选用
  - 重合归并：TITLE 粒度与板块十二 4.7「标题/开头多版建议」重合——落地时以本端点统一承载（4.7 并入 TITLE/PARAGRAPH 粒度实现，不重复建设）
  - 入参：target_type（TITLE/PARAGRAPH/SENTENCE/WORD）+ 原文 + 可选风格偏好（正式/口语/简洁）+ 候选数（1–3，配置上限）
  - 出参：候选版本数组（每版附一句改动理由）+ 优化建议清单（冗余/歧义/语气/结构诊断，只诊断不贬损——F6/F8 边界）
  - 业务逻辑：走 Agent 底座（purpose=POLISH、scene=target_type，路由后即判配额预算）→ 结构化产出 → SuggestionValidator 扩展校验（按 target_type 约束：标题字数上限、词语不得偏离原意等，越限错误回喂重试 1 次）→ 建议态缓存（P7 同口径，requestId 幂等）→ 失败降级直连固定管道（三级降级链复用）
  - 铁律：建议态不自动写入（D4）；恒带 AI 标识（D3/D13）；展示前过 DFA 闸门（调用方执行）；**改写保持作者原意**（prompt 硬约束），词语级不做词典式近义词堆砌、给上下文适配建议；仅登录用户可用（写作辅助不开匿名）
  - 计量：purpose=POLISH 复用写作辅助桶（同属 F6 科目，不再扩枚举），scene 区分粒度，Agent 多轮按 requestId+callSeq 落明细
  - ✅ 2026-11-16：新增端点 POST /ai/xiaoyue/rewrite + WritingAssistService 编排（Agent 优先 → 结构化校验回喂重试 1 次 → 降级直连三级降级链复用）；入参 RewriteRequest——targetType 白名单必传（AiContentConstant.TARGET_TYPES，注册表外拒绝）、原文来源二选一：articleId（ContentClient 取稿 + ownerId 归属校验，非本人所有明确拒绝——AI-V6 ⑤/⑦ 口径；TITLE 粒度原文取标题，承载 4.7 标题/开头多版建议）优先于 originalText（无 articleId 必传）；stylePreference/candidateCount 可空（候选数缺省取外置上限 rewrite-candidate-limit=3，越界 clamp 1~上限）；Agent 意图指令 INTENT_REWRITE（REWRITE 意图以编排服务 scene 路由承载，purpose 不扩枚举，/chat 原入口不变）；SuggestionValidator#validateRewrite 按粒度校验——TITLE ≤ rewrite-title-max-chars=100（对齐 content 侧标题约束）、WORD 候选 ≤ 原文 2 倍（「词语不得偏离原意」机械代理兜底约束，防整句改写/近义词堆砌）、改动理由 ≤ rewrite-reason-max-chars=50，越限回喂重试 1 次；失败降级直连 REWRITE_DIRECT_PROMPT 固定管道（runStructuredPipeline 扩展自定义校验器重载，明细 degraded=1），降级也失败返回失败提示不抛异常；建议态缓存 P7 requestId 幂等；purpose=POLISH 复用写作辅助桶（F6 科目不扩枚举），scene=rewrite-{粒度} 区分粒度，Agent 多轮按 requestId+callSeq 落明细；响应体 XiaoyueSuggestionResponse 扩展 targetType/rewriteCandidates/rewriteSuggestions 字段；铁律落地——D4 建议态不自动写入、D3/D13 恒带 AI 标识、DFA 闸门调用方执行、仅登录用户可用（userId 为空 PARAM_ERROR 明确拒绝）；编译验证已通过（2026-11-16）

## 15.2 文章草稿生成端点

- [x] POST /ai/xiaoyue/draft + /chat 意图路由新增 DRAFT 意图：按主题（+可选大纲/要点/字数范围）生成建议态草稿与段落结构建议
  - 业务逻辑：入参校验（主题必传）→ 配额/预算判定（purpose=POLISH）→ Agent 产出草稿正文 + 段落规划 → 结构化校验（单次字数上限 draft-max-chars 外置，默认 2000，防成本放大）→ 建议态缓存 → 返回
  - **采用路径（关键边界）**：AI 不落库——作者编辑后由前端带入 content **正常创建流程**（走完整 DFA 同步闸门 + 机审主链路，AI 草稿不绕过送审）；采用后 AI 生成标识随正文持续展示（D3 / Q64 生命期口径），AI 会话侧副本按 90 天清理
  - 铁律：草稿仅参考、作者可关闭；出站脱敏由装饰链自动覆盖；prompt 版本外置（prompt_version 承载，复用键与评测回归可追溯）
  - ✅ 2026-11-16：新增端点 POST /ai/xiaoyue/draft（DraftRequest：theme 必传；outline 大纲要点与 wordCountMin/Max 字数范围可空，随 prompt 组装字数范围文本）→ WritingAssistService 复用 15.1 同款编排骨架（登录前置 → POLISH 桶配额/预算判定 → P7 缓存回查 → INTENT_DRAFT 意图 Agent 优先 → validateDraft 字数上限校验（draft-max-chars=2000 外置防成本放大，超出回喂压缩）→ 回喂重试 1 次 → 降级直连 DRAFT_DIRECT_PROMPT 固定管道）；purpose=POLISH scene=draft 计量照常；AI 不落库——草稿仅参考，作者采用后由前端带入 content 正常创建流程（走完整 DFA 同步闸门 + 机审主链路，AI 草稿不绕过送审）；出站脱敏由 DesensitizingChatModel 装饰链自动覆盖；prompt 版本由 model_version 承载（AiPromptConstants 既有口径，模板集中管理）；仅登录用户可用；编译验证已通过（2026-11-16）
