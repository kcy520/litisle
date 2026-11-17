# ai 板块十一：多文对比与收藏夹检索（二档）

> 归档日期：2026-11-16（板块十一 3.1/3.2 全部落地；前置依赖 search 板块四 4.1 白名单 filter 同批配套落地，实现细节见本文「依赖链（search 板块四 4.1）」一节；运行态验证走 [next_verify.md](../../next/next_verify.md) AI-V5 ⑥⑦）
> 来源：迁移自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」板块十一节，条目原样搬运不压缩
> 依赖：板块九 1.1 会话结果集地基（3.1 序号定位）+ search 板块三 3.1 条件版查询 + search 板块四 4.1 白名单 filter + content 收藏域（3.2 列表数据源）

## 计量定档（2026-11-16 随落地定档）

- 3.1 多文对比 / 3.2 收藏夹检索：均为**工具素材组装 0 次额外生成成本**——对比结论与检索整理在 Agent 意图路由轮内一次生成（purpose 随入口，QA 会话即 QA 桶）；对比 token 随篇数线性涨由篇数上限（3 篇）管控，收藏夹检索整理由白名单上限（100 篇）+ topK 截断管控

## 3.1 多文对比工具

- [x] "对比这 3 篇的观点差异"：序号定位 ≤3 篇 → 各取摘要级内容 → 一次对比生成（purpose 走既有桶，token 随篇数涨由预算桶管控）；超 3 篇 → 明确告知上限，不静默截断
  - ✅ 2026-11-16：XiaoyueTools 新增工具⑨ compareResultArticles（序号清单解析复用 parseSeqNumbers 辅助（与工具⑥收藏动作共用，兼容「2、3」「2,3」「2 3」表达，含非数字 token 返回明确解析失败提示）；超 3 篇（常量 COMPARE_MAX_ARTICLES=3）明确告知上限不静默截断；逐个序号经 SessionResultRegistryService 解析（过期/越界逐条明确列出，失败条目不影响其余篇目继续组装）；取稿回查可见性双保险（下架/不可见逐条明确，结果集记录时可见不代表现在可见）；单篇正文截断 2000 字符（常量 COMPARE_ARTICLE_MAX_CHARS）摘要级素材（截断处标注「……（已截断）」防模型误当全文）；对比结论由模型在意图路由轮内一次生成）；system prompt 补【对比与收藏夹规则（板块十一）】（上限如实告知/结论只基于素材/不编造未提供的细节）；一次性请求（无会话）明确提示不可用；编译验证已通过（2026-11-16）

## 3.2 收藏夹语义检索

- [x] "我收藏过的讲 X 的文章"：拉取用户收藏列表 → SearchClient 条件版查询（新增 includeArticleIds 白名单 filter，见 search 板块四）+ queryText 语义 knn → 只返回已收藏且当前公开的文章；收藏为空/检索无命中 → 空列表明确响应
  - 依赖：search 条件版查询契约（search 板块三）+ 白名单扩展（search 板块四）
  - ✅ 拍板（2026-11-16 前置核对结论）：search 条件版查询已存在（板块三 3.1），白名单 filter 缺失——随本批配套落地 search 板块四 4.1；content 收藏列表查询已存在（FavoriteService.pageMyFavorites 多态 join 分页），缺 ID 列表轻量口径与 Feign 面——随本批补 listFavoriteTargetIds + ContentClient#listFavoriteArticleIds 契约落地
  - ✅ 2026-11-16：content 侧 FavoriteService#listFavoriteTargetIds（targetType 白名单校验复用 requireValidType + Page 对象承接取前 N 条按收藏时间倒序（不拼 LIMIT），只查 target_id 单列不做多态 join 装配）+ ContentInternalService#listFavoriteArticleIds 委托（固定文章类型 FavoriteTargetTypeEnum.ARTICLE——AI 收藏夹检索只覆盖文章收藏）+ ContentFeignProviderController 提供方端点 POST /api/feign/content/favorite/article-ids（fail-fast 降级——静默返空会让收藏夹检索误报「没有收藏」，异常穿透由 AI 工具层组装明确失败响应）；AI 侧 XiaoyueTools 新增工具⑩ searchMyFavorites（登录态前置明确拒绝（不静默）；listFavoriteArticleIds 拉 100 条白名单（常量 FAVORITE_FETCH_LIMIT 防 terms filter 过大）→ SearchClient.similarByCondition（includeArticleIds must filter 收窄到收藏集合 + queryText 语义 knn；visibility=PUBLIC 铁律由 search 侧强制——已下架文章不入索引，白名单命中即公开）→ 命中卡片观察文本（标题/作者/articleId）；收藏为空/检索无命中/依赖不可用均明确响应不编造）；system prompt 同批补规则；编译验证已通过（2026-11-16）

## 依赖链（search 板块四 4.1，随本批配套落地）

- SearchClient 条件版相似查询增 includeArticleIds（must terms filter，与 excludeAuthorIds 对称）及 excludeArticleIds（must_not filter，AI 板块十四 6.4 排除已读用）；ES bool filter 组装按四类 match 构造器复用
  - ✅ 2026-11-16：SimilarConditionRequestDTO 增 includeArticleIds / excludeArticleIds 两字段；SimilarServiceImpl#buildConditionFilters 白名单走 idTermsFilter 共用构造器（Long→字符串 FieldValue，与 tags termsFilter 同风格）；knnSearchByVector / filterSearch 黑名单入 mustNot（与 excludeArticleId 单值排除对称）；fuseBySortHint 黑名单集合化双保险二次排除（防脏数据漏排）；SearchClient 契约签名不变（DTO 内扩展，降级工厂无需改动）；queryText 为空时白名单路径退化为纯过滤检索（按 sortHint 排序），仍只出白名单内公开文章
- 后续使用方：AI 板块十四 6.4 排除已读（excludeArticleIds）登记在 next_update.md 板块十四，落地时直接复用本契约字段
