# content 模块实现记录（归档索引）

> 本文件原为单文件归档（板块一~九全量正文），2026-10-28 按板块拆分至本目录九个板块文件，本页保留为索引；正文一律在板块文件中维护，后续归档批次追加至对应板块文件（板块九已追加批次4 阶段15~17、批次5 后续另立项，2026-10-30 又追加 content 模块缺口修复批次 Q1~Q5）。
> **归档日期**：2026-10-20（板块一~八）/ 2026-10-23（板块九）/ 2026-10-28（板块九 批次3：阶段10~14；单文件拆分为九板块文件）/ 2026-10-30（板块九 content 模块缺口修复批次 Q1~Q5）
> **来源**：迁移自 docs/next/next_update.md（板块条目摘要）与 docs/next/status.md（实现细案），条目原样搬运不压缩，含口径修正与注意事项；板块九迁自 docs/temp/temp.md（临时任务清单，阶段1~14 全量）
> **互链**：活跃待办/验证清单见 [next_update.md](../../next/next_update.md)；验证部分实现步骤见 [status.md](../../next/status.md)；挂起项见 [Temporarily_not_implemented.md](../../next/temporarily/Temporarily_not_implemented.md)

> 通用纪律（适用板块一~八）：事件发送一律本地事务提交后 afterCommit；SQL 全走 #{}；所有 CAS 补 del_flag=0；CAS affected=0 一律静默跳过事件发送（不落库不发送，对齐 review publisher「落库失败不发」铁律，防免审直落并发双击/重复提交把重复事件打进 search/notification）。

## 板块索引

| 板块 | 主题 | 文件 |
| --- | --- | --- |
| 板块一 | 模块基建与文章 CRUD | [content-01-article-crud.md](content-01-article-crud.md) |
| 板块二 | 草稿系统 | [content-02-draft.md](content-02-draft.md) |
| 板块三 | 发布与送审链路 | [content-03-publish-review.md](content-03-publish-review.md) |
| 板块四 | 可见性控制与安全 | [content-04-visibility-security.md](content-04-visibility-security.md) |
| 板块五 | 书籍合集与阅读进度 | [content-05-book-collection-reading.md](content-05-book-collection-reading.md) |
| 板块六 | 标签系统 | [content-06-tag.md](content-06-tag.md) |
| 板块七 | 推荐流 | [content-07-feed.md](content-07-feed.md) |
| 板块八 | 审核回写与联动消费 | [content-08-review-writeback.md](content-08-review-writeback.md) |
| 板块九 | 临时清单（docs/temp/temp.md）——分类枚举化与 content 模块补链 | [content-09-temp-checklist.md](content-09-temp-checklist.md) |

> 待用户手动执行项汇总位于板块九文件（当前含阶段5/6/9/13~17 未勾选验证项与手动操作项）。
