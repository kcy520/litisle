> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。
> 通用纪律（适用板块一~八）：事件发送一律本地事务提交后 afterCommit；SQL 全走 #{}；所有 CAS 补 del_flag=0；CAS affected=0 一律静默跳过事件发送（不落库不发送，对齐 review publisher「落库失败不发」铁律，防免审直落并发双击/重复提交把重复事件打进 search/notification）。

## 板块六：标签系统

**落位**：`service/TagService`、`controller`（/tags 端点）；⚠️ 前置依赖：DfaFacade 注入随 content 开发回接（review 挂起项 4-2.1，随板块四一并落地）

**任务完成概览**（迁自 next_update.md，均 2026-10-17 落地）：

- [x] 6.1 标签挂载
  - ✅ 2026-10-17：TagServiceImpl#attachTags/detachTags 全量落地（Controller/实体/Mapper 既有，服务层原为 TODO 壳）：① attachTags：owner 校验（requireOwnedArticle 复用，软删按 ARTICLE_NOT_FOUND 不暴露存在性）→ 同名单请求内去重收敛 + 已有挂载数与新增合计 >5 抛 ARTICLE_TAG_EXCEEDED（新常量 TAG_MAX_PER_ARTICLE=5 收口）→ 仅池内命中可挂（in name + status=NORMAL + del_flag=0；封禁标签拒挂不给违规词引流）→ 已挂载过滤后逐条 insert（并发双挂 catch DuplicateKeyException 幂等跳过不报错不计数，清单口径与 addChapter 的冲突拒绝不同）→ 挂载成功才 use_count 原子 +1 @Transactional；② detachTags：owner 校验 → 先查实际存在的挂载行（幂等重复摘除不多扣）→ 删挂载行 → use_count 原子 -1（GREATEST 兑底不为负）；③ 方案B：requireOwnedArticle 不校验文章 status（status=2 允许增删标签，不影响 content_hash 不参与审核聚合）；池中无命中标签名的新建 DFA 闸门随 [6.2] 接入；细目见下方 [6.1]
- [x] 6.2 标签池与闸门
  - ✅ 2026-10-17：① searchByPrefix：空参空列表 → likeRight 前缀（%/_/\ 转义防通配符全池拖出）+ status=NORMAL + del_flag=0 + use_count 倒序 LIMIT 10（TAG_SEARCH_RESULT_LIMIT）；② attachTags 接 DFA 闸门：池内全量按名查询（不过滤状态）→ 正常态可挂/封禁已删静默跳过/池外名字同步 dfaFacade.check(DfaScenes.BIO, name)（BIO 短文本词库，标签/简介/代表作共用）→ 任一命中即抛 SENSITIVE_WORD_DETECTED(70001)（标签是聚合锚点宁严勿松，不分 HIGH/MID）→ 未命中落池（useCount=0，uk_name 并发冲突重查幂等）；③ 名称规范化：trim + 空串跳过 + >64 字拒（TAG_NAME_MAX_LENGTH 与词库词条同口径）；④ 契约缺口修复：DfaScenes.normalize 未映射 BIO（BIO 在 textScenes 全集但 check(BIO) 被 default 判非文本抛异常，直审场景不可达）→ 补 case BIO -> BIO 直映；⑤ 顺带修复 6.1 遗留编译问题：tagIdsOf 占位方法及三处调用全部内联替换
- [x] use_count 冗余计数异步刷新（与 view_cnt 同机制）；热门 TOP N 走 GET /tags/hot
  - ✅ 2026-10-17：① hotTags：Redis 短缓存（key=content:tag:hot:10，值=TagVO[] JSON，TTL 基准 300s ±10% 抖动防雪崩，缓存三防对齐 §七.6）→ miss 查库 status=NORMAL + del_flag=0 + use_count 倒序 LIMIT 10（TAG_HOT_TOP_N，走 idx_use_count）；② use_count 机制定位修正：挂载/摘除同事务原子增减（[6.1]，计数正确性来源，比异步回写更准），另建 TagCountReconcileJob 定时对账兜底（单条 UPDATE 自联仅修漂移行，默认 1h 间隔/5min 初延，litisle.content.tag.reconcile 可调）；CacheConstant 新增 CONTENT_TAG_HOT，ContentLimitConstant 新增 4 常量；细目见下方 [6.2]

**实现细案**（迁自 status.md）：

- [x] [6.1] 挂载：POST /article/{id}/tags——owner 校验 → ≤tag.max-per-article=5（超限抛 ARTICLE_TAG_EXCEEDED）→ uk(article_id,tag_id) 冲突幂等跳过。——已落地（2026-10-17）：TagServiceImpl#attachTags/detachTags 全量实现（服务层原为 TODO 壳）：① attachTags 链路：入参空集直接返回 → requireOwnedArticle 复用校验（软删按 ARTICLE_NOT_FOUND、非本人 NO_PERMISSION）→ 同名单请求内 HashSet 去重收敛 → 已有挂载 selectCount + 新增去重数 >5（新常量 TAG_MAX_PER_ARTICLE=5）抛 ARTICLE_TAG_EXCEEDED(30014) → 池内命中查询（in name + status=NORMAL + del_flag=0；封禁标签拒挂）→ 已挂载 tag_id 过滤 → 剩余逐条 insert + use_count 原子 +1（setSql "use_count = use_count + 1"，挂载成功才计数）@Transactional(rollbackFor)；② uk 冲突双层：预过滤已挂载（体验）+ 并发双挂 catch DuplicateKeyException 幂等跳过（不报错不重复计数，对齐清单「冲突幂等跳过」口径，区别于 addChapter 的冲突拒绝）；③ detachTags：先 selectList 实际存在的挂载行（幂等重复摘除不多扣）→ 删挂载行 → use_count 原子 -1（setSql "GREATEST(use_count - 1, 0)" 防对账空窗期透支）@Transactional；④ use_count 说明：本次为挂载/摘除时原子增减（计数正确性来源），「异步刷新」指 [6.2] 的定时对账校正机制，两机制互补不冲突。
- [x] [6.1] 方案B 关系：status=2 允许增删标签（不参与审核聚合、不影响 content_hash），新增仍过 DFA。——已落地（2026-10-17）：① requireOwnedArticle 仅校验归属与软删，不校验文章 status（status=2 已发布允许增删标签，status=0/3 同理开放，标签属元数据不在方案B 禁改清单）；② 不影响 content_hash：标签变更不触碰 article.content_hash，免审直落命中口径不变；不参与审核聚合：挂载不发任何 MQ 事件；③ 新增仍过 DFA 随 [6.2] 新建闸门接入（池中无命中的标签名当前静默跳过，仅挂池内已有标签，留 TODO 注记）。
- [x] [6.2] 标签池：GET /tags?keyword= 前缀搜索已有标签池（防碎片化）；确无命中才允许新建——新建文本过 DFA 闸门（同步，命中拒挂）；存量挂载零审核成本。——已落地（2026-10-17）：① searchByPrefix：空参空列表；likeRight 前缀搜索（escapeLike 转义 %/_/\\ 防通配符全池拖出）+ status=NORMAL + del_flag=0 + use_count 倒序 LIMIT TAG_SEARCH_RESULT_LIMIT(10)；② 新建闸门：attachTags 池内按名全量查询（不过滤状态，区分封禁/池外）：正常态未删→挂载；封禁/已删→静默跳过（不给违规词引流）；池外名字→dfaFacade.check(DfaScenes.BIO, name) 同步判定→任一命中抛 SENSITIVE_WORD_DETECTED(70001)（标签是聚合锚点，不分 HIGH/MID 宁严勿松）→未命中新建落池（useCount=0/status=NORMAL，uk_name 并发冲突重查幂等）后挂载；③ 名称规范化：trim 空串跳过、>TAG_NAME_MAX_LENGTH(64) 拋 ARTICLE_PARAM_INVALID（与词库词条长度同口径）；④ 契约缺口：DfaScenes.normalize 未映射 BIO（textScenes 含 BIO 但 check(BIO) 被 default 判非文本抛 IllegalArgumentException，直审场景不可达）→ 补 case BIO -> BIO（litisle-api 变更，需 review 侧同步确认无影响：normalize 仅影响 BIO 场景，review 现有调用不传 BIO）；⑤ 顺带修复 6.1 混合态遗留：tagIdsOf 占位方法及三处调用内联替换（原文件无法编译）。
- [x] [6.2] use_count 冗余计数异步刷新（与 view_cnt 同机制）；热门 TOP N 走 GET /tags/hot。——已落地（2026-10-17）：① hotTags：Redis 短缓存（CacheConstant.CONTENT_TAG_HOT + TOP N；值=TagVO[] JSON 序列化，避免依赖 RedisTemplate 泛型反序列化；TTL 基准 TAG_HOT_CACHE_TTL_SECONDS(300s) ±10% 抖动防雪崩）→ miss 查库 status=NORMAL + del_flag=0 + use_count 倒序 LIMIT TAG_HOT_TOP_N(10)（走 idx_use_count）；挂载/摘除/新建均不主动失效热门缓存（TTL 短缓存容忍毫秒级偏差，与 view_cnt 同为最终一致）；② use_count 机制定位：同事务原子增减为主（[6.1]，正确性来源）+ TagCountReconcileJob 定时对账兑底（新 job/ 包，@EnableScheduling；单条 UPDATE 自联仅修 use_count≠实际挂载数的漂移行；间隔默认 1h/初延 5min，litisle.content.tag.reconcile.{enabled,interval-ms,initial-delay-ms} 外置 yml 可调）。
- [x] [6.1] 方案B 关系：status=2 允许增删标签（不参与审核聚合、不影响 content_hash），新增仍过 DFA。——已落地（2026-10-17，详见上方 [6.1] 挂载条目②③）。

**验收**：DFA 命中标签拒挂；超限拒绝；封禁标签不再聚合展示。

------------

