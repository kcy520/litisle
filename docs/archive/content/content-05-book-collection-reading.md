> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。
> 通用纪律（适用板块一~八）：事件发送一律本地事务提交后 afterCommit；SQL 全走 #{}；所有 CAS 补 del_flag=0；CAS affected=0 一律静默跳过事件发送（不落库不发送，对齐 review publisher「落库失败不发」铁律，防免审直落并发双击/重复提交把重复事件打进 search/notification）。

## 板块五：书籍合集与阅读进度

**落位**：`service/BookService`、`controller/BookController`

**任务完成概览**（迁自 next_update.md，均 2026-10-17 落地）：

- [x] 5.1 书籍/章节管理
  - ✅ 2026-10-17：BookServiceImpl 五方法全量落地（Controller/实体/DTO/VO 既有，服务层原为 TODO 壳）：① create 默认 status=0 连载中 + category 空落默认「小说」；② update owner 校验 + 非 null 字段更新；③ delete 软删 CAS（where del_flag=0，重复删幂等）+ 物理清挂载行（挂载关系无独立业务价值，文章本体不动）@Transactional；④ addChapter 四重校验：书籍归属 requireOwnedBook 复用 → 文章归属本人（挂他人文章 NO_PERMISSION，非已发布 ARTICLE_PARAM_INVALID，软删按不存在）→ uk 冲突预检 → 落挂载行（并发双挂 DB uk 兑底转 30015）；⑤ removeChapter 书籍归属 + 章节归属（chapter_id 不属 book_id 拒 BOOK_CHAPTER_MISMATCH，防伪造跨书章节）仅删挂载行不删文章；新增 ErrorCode BOOK_CHAPTER_NO_CONFLICT(30015)；细目见下方 [5.1]
- [x] 5.2 阅读进度
  - ✅ 2026-10-17：ReadingProgressServiceImpl 两方法全量落地（Controller/实体/Mapper/VO 既有，服务层原为 TODO 壳）：① report：chapter_id 归属 book_id 反查（章节行缺失或 bookId 不匹配拒 BOOK_CHAPTER_MISMATCH，伪造跨书章节被拒；书籍软删时挂载行随删，无需额外 book 查询）→ UPSERT：selectOne uk_user_book 无则 insert（并发首报 catch DuplicateKeyException 重读转 update，uk 兑底）→ 仅前进不回退：progress 无 chapter_no 列，经 book_chapter 反查双方序号比对，新序号 ≤ 当前序号静默忽略（多端乱序不回退），当前章节行被退章移除时容错放行；② getLastChapter：无进度返回 null（前端落第一章），进度指向章节已被退章移除时按 null 返回前端按最新章容错；③ 节流：Controller @RateLimiter(USER)=2/60s 既有（[4.3] 已核对），配合前端 30s 节流；细目见下方 [5.2]

**实现细案**（迁自 status.md）：

- [x] [5.1] 书籍/章节：book CRUD（status 0-连载/1-完结/2-下架）；章节挂载校验文章归属本人；chapter_no 写入依赖 uk(book_id,chapter_no) 冲突拒绝，序号即排序键。——已落地（2026-10-17）：BookServiceImpl 五方法全量实现（Controller/实体/DTO/VO/Mapper 既有，服务层原为 TODO 壳）：① create：authorId=登录人、status=SERIALING(0)、delFlag=0、category 空落默认「小说」，返回自增 ID；② update：requireOwnedBook 复用（非本人 NO_PERMISSION、软删按 BOOK_NOT_FOUND 不暴露存在性）+ 非 null 字段更新（BookSaveDTO 全字段可空）；③ delete：软删 CAS（LambdaUpdateWrapper where del_flag=0，并发重复删幂等跳过）+ 物理清 book_chapter 挂载行（挂载关系无独立业务价值，文章本体不动）@Transactional 包裹两写；④ addChapter 四重校验链：书籍归属 → 文章归属本人（挂他人文章 NO_PERMISSION；status≠2 已发布抛 ARTICLE_PARAM_INVALID；软删按 ARTICLE_NOT_FOUND）→ uk 冲突预检（selectCount book_id+chapter_no >0 抛 BOOK_CHAPTER_NO_CONFLICT 新码 30015）→ insert（并发双挂同序号 catch DuplicateKeyException 转 30015，DB uk 兑底）；⑤ removeChapter：书籍归属 + 章节归属（chapter_id 不属 book_id 拒 BOOK_CHAPTER_MISMATCH 30012，防伪造跨书章节）仅删挂载行不删文章。新增 ErrorCode BOOK_CHAPTER_NO_CONFLICT(30015,「章节序号已被占用」）。注：挂载仅校验文章已发布，书籍 status（连载/完结）不影响挂载（完结书补章合法，下架书查询侧不可见）；书籍详情/章节列表 GET 端点未在 §3.1 矩阵（阅读侧接口随 5.2/前端需求另议）。
- [x] [5.2] 阅读进度：PUT 上报——chapter_id 归属 book_id 反查校验（拒绝伪造跨书章节，抛 BOOK_CHAPTER_MISMATCH）→ UPSERT（uk_user_book 冲突转 update）→ 仅前进不回退（reading_progress 无 chapter_no 列，update 前经 book_chapter 反查当前/新 chapter_id 的 chapter_no 比对，仅前进才 update，防多端乱序回退）；@RateLimiter(USER)=2 配合前端 ≥30s 节流。——已落地（2026-10-17）：ReadingProgressServiceImpl#report 全量实现：① 归属反查：selectById(chapterId) 行缺失或 bookId 不匹配抛 BOOK_CHAPTER_MISMATCH(30012)（书籍软删时挂载行已随删，章节行缺失自然被拒，无需额外 book 查询）；② UPSERT：selectOne(user_id+book_id) 无则 insert，并发双端首报 catch DuplicateKeyException 重读转 update（uk_user_book 兑底），极端并发（冲突行恰被删）本次跳过下次兑底；③ 仅前进：经 book_chapter 反查当前/新章节序号比对，新序号 ≤ 当前序号静默忽略（含同章节重复上报幂等）；当前章节行被退章移除（selectById=null）无法比对时容错放行更新；④ 节流：Controller @RateLimiter(USER)=2/60s 既有（[4.3] 已核对）。
- [x] [5.2] 续读：GET 返回最后阅读章节。——已落地（2026-10-17）：ReadingProgressServiceImpl#getLastChapter：selectOne(uk_user_book) 无进度返回 null（前端落第一章）；进度指向章节已被退章移除时 selectById=null 按 null 返回，前端按最新章容错（对齐 BookChapterMapper 注释口径「章节缺失按最新章处理」）；Controller GET /reading-progress/{bookId} 既有。

**验收**：跨书伪造章节被拒；多端乱序上报不回退。

------------

