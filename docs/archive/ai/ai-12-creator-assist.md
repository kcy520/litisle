# ai 板块十二：检索编排与创作辅助扩展

> 归档日期：2026-11-16（板块十二 4.1~4.6 全部落地；4.5 并入板块十一 3.1；4.7 依 next_update.md 板块十五 15.1 重合归并口径并入统一承载，本板块不重复建设；4.6 配套补 SearchClient#getArticleAiLabels 契约；运行态验证走 [next_verify.md](../../next/next_verify.md) AI-V5 ⑥ / AI-V6 ⑤）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」板块十二节，条目原样搬运不压缩
> 依赖：板块九~十一底座（会话结果集 / 条件版检索 / 收藏白名单）+ 板块八条件版管道（4.2 换调用时机）

## 计量与桶位定档（2026-11-16 随落地定档）

- 4.1 找系列/前作：**0 次 LLM 纯检索**（工具⑪纯契约调用，会话内引用经意图路由轮）
- 4.2 发布前查重：检索 token=0 落明细（SIMILAR 桶 scene=duplicate-check callSeq=1）；explain=true 且命中时说明生成 1 次（scene=duplicate-explain callSeq=2）；预算 EXCEEDED 拒绝不降级放行
- 4.3 读者预设问题 / 4.4 选题灵感 / 4.6 风格仿写：purpose=QA（chat 入口 scene 路由 / 工具素材组装 0 次额外生成成本，生成在意图路由轮内一次完成）
- 4.7 标题/开头多版建议：并入板块十五 15.1 统一承载（重合归并口径），计量随板块十五定档

## 4.1 找系列/前作（0 次 LLM）

- [x] "这篇有前作吗/同系列还有哪些"：条件版检索管道组合查询——同作者 filter + 标题/语义相似 knn；结果按「同作者系列」标注分组；无命中 → 空列表明确响应
  - ✅ 2026-11-16：XiaoyueTools 新增工具⑪ findSeriesArticles（序号/articleId 双入口定位目标文章 → getContent 回查作者与标题（可见性双保险，下架/不可见明确响应）→ SearchClient.similarByCondition（authorId filter + 标题语义 knn + excludeArticleId 排除自身）→ 同作者系列候选观察文本；无命中明确空响应「该文可能为该作者的唯一作品」不编造系列关系）；一次性请求无序号上下文时明确要求提供 articleId；编译验证已通过（2026-11-16）

## 4.2 发布前查重（创作者向）

- [x] 创作侧发布前调用相似检索（复用板块八条件版管道换调用时机），相似度超阈值（配置外置）提示与库内已有文章的重复风险；0~1 次 LLM（仅用户追问"哪里相似"时生成说明）
  - 衔接点：content 创作页触发入口（发布前检查按钮/流程节点，入口建设归 content 侧）；review 模块可参考其结果但不强制依赖（独立功能，非机审环节）
  - ✅ 2026-11-16：新增端点 POST /ai/xiaoyue/duplicate-check（[DuplicateCheckRequest](../../..//litisle-ai/src/main/java/com/litisle/litisleai/dto/DuplicateCheckRequest.java)：requestId/title/body 必传 + explain 可空；查重对象为待发布草稿——标题正文由创作页显式传入，不走取稿工具链路）+ [DuplicateCheckService](../../../litisle-ai/src/main/java/com/litisle/litisleai/service/DuplicateCheckService.java)（预算判定 → 标题+截断正文 queryText 经 SearchClient.similarByCondition 语义检索 top10 → 相似度 ≥ 外置阈值 litisle.ai.rag.similar.duplicate-score-threshold=0.85 判 risk → 命中卡片返回）；explain=true 且命中时 1 次 LLM 生成「哪里相似」说明（DUPLICATE_EXPLAIN_PROMPT，TextModelProvider 直连固定管道不走 Agent，失败降级 explanation 置空不中断主结果——主结果 risk/articles 已就绪）；编译验证已通过（2026-11-16）

## 4.3 读者预设问题（创作者向）

- [x] "读者读我这篇可能会问什么"：复用 1.4 读后追问的单文 RAG 底座（同检索、换 prompt 与输出形态）→ 生成 N 个预设 Q&A 供作者发布前自检；purpose 计量照常
  - ✅ 2026-11-16：chat 入口 scene 路由（SCENE_READER_QUESTIONS=reader-questions，purpose=QA）→ INTENT_READER_QUESTIONS（必须携带 articleId，缺失抛 PARAM_ERROR；取稿工具获取正文——发布前草稿未建切片索引以取稿工具为准；生成 N 个「问题+简答」，N 外置 reader-question-count=5；正文未覆盖的问题如实说明「正文未涉及」）；编译验证已通过（2026-11-16）

## 4.4 选题灵感（创作者向）

- [x] "我这个领域最近缺什么角度/什么话题热"：热榜/分区热度数据（search 既有能力）作为上下文 + 一次生成分析；响应固定标注「仅参考」；purpose 计量照常
  - 前置核对：search 热榜查询契约是否可经 Feign 复用（缺则本项转 temporarily 挂起，触发条件写明缺哪个契约）
  - ✅ 拍板（2026-11-16 前置核对结论）：search 无热榜查询契约（HotQueryCacheService 为检索结果缓存非热榜），但 **ContentClient#getHotArticles 热榜契约已存在**（批次八 8.1 落地的 /api/feign/content/hot-articles）——数据源可直接复用，无缺契约，不转挂起
  - ✅ 2026-11-16：XiaoyueTools 新增工具⑫ getHotTopicContext（content 热榜列表 0 次 LLM：标题+摘要截断 100 字符作分析上下文，无榜/失败明确响应防编造）；chat 入口 scene 路由（SCENE_TOPIC_INSPIRATION=topic-inspiration，purpose=QA）→ INTENT_TOPIC_INSPIRATION（热榜主题方向归纳 + 差异化选题建议，开头固定标注「仅参考，灵感仅供选题思考」）；N 外置 topic-inspiration-hot-limit=10；编译验证已通过（2026-11-16）

## 4.5 跨文观点对比（并入 3.1，不单独立项）

- [x] "这两篇的说法矛盾吗"：作为 3.1 多文对比的子场景处理（同底座同流程），随 AI-V5 ⑥ 验收补一条对比矛盾性用例
  - ✅ 2026-11-16：确认并入板块十一 3.1 多文对比承载（同底座同流程）；AI-V5 ⑥ 验收口径已含「含跨文矛盾性对比用例」（next_verify.md AI-V5 ⑥，无需改动）

## 4.6 风格仿写辅助（2026-11-16 追加）

- [x] "用这篇的笔法帮我写个开头"：按序号取参考文章 → ai_labels.style 取风格标签作生成约束 + 1 次生成——把已有标签从"筛选用"变"生成用"；参考文章需当前公开可见，生成结果标注「仅参考」；purpose 计量照常
  - 前置核对：ai_labels 风格标签无读取契约（板块八打标只写 ES 不回读）——随本批补 SearchClient#getArticleAiLabels 契约落地（同板块九 1.2「能力存在缺 Feign 面」配套口径）
  - ✅ 2026-11-16：litisle-api SearchClient 增 getArticleAiLabels（GET /api/feign/search/article-labels，读 article_idx 单篇 ai_labels 词表型映射，未入索引/未打标返回空 map 不报错）+ fail-fast 降级 + search 提供方端点（SimilarService#getArticleAiLabels，ES get 实现，ES 异常按 SEARCH_ES_UNAVAILABLE fail-fast）；AI 侧 XiaoyueTools 新增工具⑬ getStyleImitationMaterial（序号/articleId 定位 → 公开可见性双保险 → ai_labels.style 回查（未打标降级为仅正文笔法并如实说明）→ 风格标签 + 截断 2000 字符正文笔法素材观察文本）；生成在意图路由轮内 1 次完成（purpose 随入口 QA），system prompt 补【创作辅助规则（板块十二）】；编译验证已通过（2026-11-16）

## 4.7 标题/开头多版建议（2026-11-16 追加）

- [x] "给我 3 个替代标题/换个开头"：与 4.3 预设问题同批的创作自检——取本人文章内容 → 1 次生成 N 版建议（N 外置配置）；文章归属校验，非本人所有明确拒绝（对齐 AI-V6 ⑤ 口径）；purpose 计量照常
  - ✅ 归并拍板（2026-11-16）：按 next_update.md 板块十五 15.1 重合归并口径（「TITLE 粒度与板块十二 4.7 重合——落地时以本端点统一承载，4.7 并入 TITLE/PARAGRAPH 粒度实现，不重复建设」），本项**并入板块十五 15.1 统一承载**，本板块不重复落地；文章归属校验与 N 外置要求随板块十五 15.1 落地时一并实现（对齐 AI-V6 ⑤ 口径）
