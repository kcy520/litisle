# content 模块已知约束与风险（2026-11-07 迁自 next_update.md）

> **主题文件**：本文件是 [constraints-index.md](constraints-index.md) 的主题文件之一，按模块拆分；后续新增条目在文末追加，编号接着该模块最大号。
> **来源**：`docs/next/next_update.md` 批次十二「已知风险」R1、R3（2026-11-06 登记，2026-11-07 迁入），原文搬运、未压缩。
> **性质**：两者均属**流程/环境约束**，不改代码；平时无需处理，触发条件成立时才按「处置方式」执行。

## R1 · 存量统计回填能力已移出初始化脚本（关键词：回填 / content-stat-backfill）

- **现象**：`zz-` 系列补丁脚本已于 2026-11-06 合并进 `schema/content-schema.sql` 后删除，统计表与源表的重算能力只剩 [tools/db-repair/content-stat-backfill.sql](../../tools/db-repair/content-stat-backfill.sql) 手工执行，**不再随建库自动完成**。全新库的统计基线由 `seed/content-seed.sql` 直接写入，同样不经过该脚本。
- **触发条件**：任何「只升级代码 / 只改源表、没有手工跑这个脚本」的环境。典型场景：① 老环境从 `zz-` 脚本时代升级，历史统计从未按新口径重算；② 手工改 `favorite` / `article` / `reading_history` / `book_chapter` 后未同步统计表；③ 用自建脚本或导数工具批量写入内容数据（绕过 `seed/content-seed.sql`）。
- **影响**：`article_stat`（收藏 / 点赞 / 评论 / 浏览）、`book_stat`（收藏 / 章节数 / 读者数）、`content_stat_window`（30 / 90 天窗口）、`book_read_quality`（完读率）落后于源表。**接口不报错、卡片不异常，只是收藏数 / 阅读数 / 章节数显示偏小或为 0**——属于静默错误，不做核对发现不了；连带推荐流排序权重失真（`content_stat_window` 缺失时窗口值退化到累计值），个性化召回质量下降。
- **处置方式**：
  - **何时必须跑**：换库 / 换环境首次启动前；升级到批次十一及以后代码且库内已有存量内容时；批量改源表（`favorite` 迁移、`reading_history` 裁剪、章节批量增删）之后；对账任务报出差异时。
  - **操作顺序**（与脚本头注释一致）：先跑「第一部分 执行前核对」记录 6 项 `diff_rows` → 再跑「第二部分 分批重算」（`batch_size` 默认 1000，按主键分批、可随时中断）→ 最后跑「第三部分 执行后复核」，`diff_rows` 应全部为 0（`book_reader_under` 因 90 天 / 单用户 1000 条裁剪可能仍留有旧值差，属预期）。
  - **靠什么发现**：[ContentStatReconcileJob](../../litisle-content/src/main/java/com/litisle/litislecontent/job/ContentStatReconcileJob.java) 每日一轮对账，写 6 类指标 `litisle_content_stat_reconcile_diff{type=article_collect|article_comment|article_like|article_view|book_collect|book_reader_under}`（Gauge 预注册，0 值可见），差异总数超阈值（默认 50）打 ERROR 日志。
  - **口径与边界**：收藏数排除作者自收藏；章节数只统计「未删 + 已发布 + 公开」；书籍阅读人数取 `GREATEST(现值, 源值)` 不缩小；脚本只做统计重算，不含任何结构性 DDL、不 `drop` / `truncate` 业务表、不参与自动初始化。
- **性质（2026-11-07 补记）**：属**流程约束**，不改任何代码；触发时只需执行脚本并复核，不涉及发布或代码变更。

## R3 · `00-init-databases.sql` 对 `MYSQL_USER` 的隐含依赖（关键词：MYSQL_USER / 授权）

- **现象**：[00-init-databases.sql:56-60](../../docker/init/mysql/00-init-databases.sql#L56-L60) 直接 `GRANT ALL PRIVILEGES ... TO 'test_k.1'@'%'`，**账号并非 SQL 创建**，而是依赖 Docker 官方 entrypoint 从 `MYSQL_USER` / `MYSQL_PASSWORD` 环境变量预建。
- **触发条件**：换部署方式——Compose 改为 K8s、改用已有 MySQL 实例、只执行 SQL 不走官方 entrypoint、或 env 未注入 / 注入值与脚本硬编码账号不一致。
- **影响**：变量缺失时账号不存在，授权语句报 `ERROR 1410` 中断（实测位置即 00 脚本 GRANT 行），初始化在第一步就失败；若注入的 `MYSQL_USER` 与脚本写死的 `'test_k.1'` 不一致，则**授权给错误账号**，应用连不上库。报错发生在建库阶段，排查容易误判为网络或 SQL 语法问题。
- **处置方式**：当前依赖 `MYSQL_USER=test_k.1`、`MYSQL_PASSWORD=123`（compose 由 `.env` 注入，须与 00 脚本硬编码账号一致）。换环境时必须：① 保证 entrypoint 能拿到这两个 env（compose 正常路径不受影响）；② 或改为不依赖 entrypoint 的初始化方式，自行执行 `CREATE USER IF NOT EXISTS ...` + `GRANT ...`，并把账号名抽成模板变量；③ 走 K8s 时先把 00 脚本账号与 Secret 中账号对齐再执行。
- **触发条件补充（2026-11-07 补记）**：仅在**换部署方式**（Compose → K8s、改用已有 MySQL 实例、不走官方 entrypoint）、**改数据库用户名**（`MYSQL_USER` 与 00 脚本硬编码的 `'test_k.1'` 不一致）、或**把这套初始化脚本复用到别的环境**时才需要处理；现有 compose 正常路径不受影响。

## R4 · 作者自评论是否计入 `comment_count` 未验证（关键词：自评论 / comment_count / social）

- **现象**：11.11 反作弊核查已确认作者自收藏、自点赞、自读（阅读人数）已排除或硬拦截，但「作者自评论是否计入 `article_stat.comment_count`」未核验；social 模块评论链路尚未落地，仓库内没有可逐行核对的评论文档写入实现。
- **触发条件**：social 评论功能上线并写入 `article_stat.comment_count`；或对账任务开始核对评论数；或文章详情开始展示评论数。
- **影响**：若作者自评论被计数，`comment_count` 会虚高；评论数若参与推荐排序或运营报表，偏差会静默进入口径，接口不报错、页面不异常。
- **处置方式**：social 落地时在评论写入处排除 `commenter == article.author`，或在统计侧排除；上线前用一条作者自评论 + 一条他人评论做对照，确认 `comment_count` 只增加 1。未核验前不要把 `comment_count` 当作可信排序特征。
- **当前状态（2026-11-08 登记）**：未验证，不做代码改动。

## R5 · `view-count-flushed` 事件进入 search 后对热度的实际影响未逐行核验（关键词：view-count-flushed / heat / 热度）

- **现象**：content 侧发布 `view-count-flushed` 事件、search 侧消费后用于热度累加；但事件进入 search 后对热度的实际影响（消费幂等、窗口去重、每次 bulk 写入次数、排序权重）仅按设计与注释确认，未逐行核验。
- **触发条件**：热度排序、热点榜或热度调权上线；事件重复消费/乱序；同一文章在同一窗口被多次浏览。
- **影响**：热度可能重复累加或漏加，热点排序与运营口径失真；属于静默偏差，不报错。
- **处置方式**：按 search V1 的「heat 累加-定时 bulk 链路生效（pending 键 GETDEL 清理 + 每篇每窗口至多 1 次 ES 写 + 重复消费不重复累加）」逐条核对；用一条文章多次浏览事件验证 pending 键与 ES 写入次数；未核验前不据此调整热度权重。
- **当前状态（2026-11-08 登记）**：未验证，不做代码改动。

## R6 · 推荐流召回不排除本人作品是否为设计意图未确认（关键词：召回 / 作者本人 / author 过滤）

- **现象**：推荐流召回/排序链路未见显式排除 `author == viewer` 的代码；该行为是否为产品设计意图未确认。11.11-A 只修复作者自浏览对 `view_count` 的污染，不改变召回结果。
- **触发条件**：作者刷自己的推荐流；产品确认「推荐流是否应包含本人作品」；CTR/曝光数据用于训练或调权。
- **影响**：作者会看到自己的作品，可能影响使用体验与 CTR 口径；若产品要求排除而代码未排除，则属于功能缺口。
- **处置方式**：先由产品拍板；若需排除，在召回或过滤层加 `author` 过滤并保留快照兜底；若不排除，登记为已知行为，不在代码里做隐式排除。未确认前不要按「已排除本人」解释 CTR 数据。
- **当前状态（2026-11-08 登记）**：未验证，不做代码改动。

## R7 · 曝光去重实现仅按注释确认、未逐行核验（关键词：曝光去重 / 快照 / CTR）

- **现象**：曝光去重（同一快照内不重复曝光）仅按代码注释确认，未逐行核验 key 维度、TTL、快照边界与并发下的原子性。
- **触发条件**：曝光数据用于 CTR 报表或推荐训练；并发首屏请求；快照切换或续刷。
- **影响**：重复曝光会拉低 CTR、造成训练样本重复；属于静默偏差，接口与页面均不报错。
- **处置方式**：用同一 anon/user 连续请求同一快照，检查曝光记录键与 TTL；并发压测确认去重写入的原子性（setnx/incr 或等价操作）；核对快照切换后的新 key；未核验前不将曝光数据用于调权。
- **当前状态（2026-11-08 登记）**：未验证，不做代码改动。
