# search 板块二：维度注册表与 ai_labels 标签槽（含板块三 3.1 条件版查询配套）

> 归档日期：2026-11-16（板块二 2.1/2.2 全部完成并归档；板块三 3.1 同批落地但板块三整体未完，条目仍留在 next_update.md 活跃）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「search模块」板块二节，条目原样搬运不压缩（含 P0 拍板口径）
> ai 侧配套（打标端点 /ai/feign/ai/label、条件提取管道）见 [ai-08-conditional-similar.md](../ai/ai-08-conditional-similar.md)

## 方案总纲（原样搬运）

> 方案：维度注册表（配置驱动打标与提取两端，新维度零代码）+ ai_labels 动态标签槽（索引期 LLM 打标，查询期零 LLM）
> 原则：查询期零 LLM（标签全部索引期预算）；打标失败不阻断索引主链路；新维度=改配置+跑一次全量重建补标，零代码

## 2.1 维度注册表初版定稿（P0 拍板项，编码前置）

- [x] litisle.search.ai-labels.dimensions 初版定稿：filter 型维度与受控词表（style/mood 等取值集合）、range 型字段绑定（word_count、publish_time）、param 型（topK/sort/权重语义）、semantic 型（topic）；打标与提取两端 prompt 由注册表动态生成，**新增维度=改配置+跑一次全量重建补标，零代码**
  - 2026-11-15 实用性×成本筛选——**二期首批词表**（随打标管线顺带，无独立成本）：depth（入门/进阶/深度）、structure（叙事/论述/教程/清单）；stance/audience 词表留位观察不做
  - ✅ 拍板（2026-11-16）：初版词表定稿——style=轻松易懂/严谨专业/故事叙事/实战干货/观点评论/幽默趣味（六值受控词表）+ depth=入门/进阶/深度 + structure=叙事/论述/教程/清单 + topic（semantic 自由语义，非受控）；mood 词表留位观察不做（随 stance/audience 同口径）；range 型绑定 word_count/publish_time、param 型 topK/sort/权重语义维持清单既有口径
  - ~~待讨论：初版词表内容需产品/算法拍板~~（已拍板，词表闸门解除，随 AI 板块八 1.2 / 板块三 3.1 落地）
  - 暂不做清单（has_image / OR 逻辑组合 / 关注列表 filter / 已读去重 / 用户画像个性化）已登记 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md) ai 模块节，不在 next_update.md 保留
  - ✅ 2026-11-16：注册表落地为 litisle-api 契约类 AiLabelDimensionsProperties（前缀 litisle.search.ai-labels，打标/提取/条件查询三端共读同一配置命名空间保口径同源；litisle-ai 经 AiModelConfig @EnableConfigurationProperties 注册、litisle-search 经新增 AiLabelsConfig 注册——服务启动类不扫描 api 包）；P0 默认词表内置代码默认值（Nacos 可覆盖）：style 六值/depth 三值/structure 四值（filter，写 ai_labels.{维度名}）、topic（semantic）、category/tags（filter 绑既有字段）、word_count（range 绑 word_count）、publish_time（range 绑 updateTime）、topK/sort（param，sort 词表 related/newest/heat/similarity-first）；每维度带 prompt 描述供两端 prompt 动态拼装；search 侧 SearchProperties 增 aiLabels 组（labelingEnabled 打标开关 / labelContentMaxChars 正文截断）；新维度=改配置+存量补标，零代码；编译验证已通过（2026-11-16）

## 2.2 ES 字段与索引期打标

- [x] article_idx 新增 ai_labels object（dynamic keyword，新维度零 mapping 改动）+ word_count 数值字段（索引期按 body 纯计算字数，零 AI 成本）；ArticleIndexServiceImpl 建索引链路接入打标：调 AI /api/feign/ai/label → ai_labels 写入文档；改稿 article-updated → 重新打标整体覆盖
  - 铁律：打标只发生在内容变化时（发布/改稿/重建），查询期零 LLM；打标失败不阻断索引主链路（重试耗尽 → ai_labels 留空，该维度过滤退化为语义兜底）
  - 存量补标：一次全量重建（VectorRebuild admin 任务扩展）
  - 依赖：AI 板块八 1.3 打标端点就绪
  - ✅ 2026-11-16：SearchFieldConstant 增 FIELD_AI_LABELS/FIELD_WORD_COUNT；EsIndexInitializer articleMapping 增 ai_labels object（DynamicMapping.True）+ ai_labels.* dynamic_templates（字符串→keyword，新维度零 mapping 改动）+ word_count long；upgradeArticleMappingIfNeeded 幂等补齐存量索引（补 mapping 后新写入文档带动态模板，存量文档需存量补标回填）；ArticleIndexDoc/ArticleMetaDoc 增 aiLabels/wordCount 字段；ArticleIndexServiceImpl buildIndexDoc 写 wordCount（正文纯文本纯计算）+ labelArticle 调 AiClient#label（requestId=index-label-{articleId} 稳定键防重复计费、正文截断 labelContentMaxChars、失败告警留空不阻断主链路）；handleArticleUpdated 改稿重新打标整体覆盖 + 重算 word_count；存量补标 VectorRebuildService#relabelAiLabels（search_after 分页扫描公开文章+逐篇打标+bulk 局部更新只写 ai_labels/word_count，单篇失败跳过重跑可补齐）+ POST /api/search/admin/relabel-ai-labels（ADMIN/SUPER_ADMIN，成本提示：逐篇 LLM 调用走 LABELING 桶）；编译验证已通过（2026-11-16）

## 板块三 3.1 配套（同批落地，条目仍在 next_update.md 活跃——板块三 3.2 升级阶梯未完）

- ✅ 2026-11-16（板块三 3.1）：SearchClient 增 similarByCondition（SimilarConditionRequestDTO：queryText/excludeArticleId/authorId/excludeAuthorIds/category/tags/aiLabels/wordCount 区间/publishTime 区间/topK/sortHint）与 searchUsers（UserSearchVO：userId+penName 最小集）两契约 + SearchClientFallbackFactory fail-fast；SimilarService#similarByCondition 按注册表路由四类构造：filter 词表型 → term（aiLabels 各键，esField 声明写既有字段，注册表外 key 忽略防污染）/ range 数值型 → range（word_count、publish_time→updateTime，解析失败该条件忽略）/ param → topK 截断（knn.max-top-k 同口径）+ sortHint（related 默认融合 / newest updateTime 降序 / heat 热度降序 / similarity-first 融合权重置纯向量 [1,0,0]）/ semantic → queryText embed 后 knn 作参照向量（替代源文章向量）；queryText 空（或 embedding 未就绪）退化纯条件过滤检索按 sortHint ES 排序；visibility=PUBLIC + 排除自身铁律强制（filter + mustNot 双保险）；相似度阈值过滤仅向量路径生效；SimilarArticleVO 增 authorId/heat/updateTime 透传字段（similar 旧链路同步补 authorId）；无命中返回空列表不报错；编译验证已通过（2026-11-16）
