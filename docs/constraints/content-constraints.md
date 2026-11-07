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
