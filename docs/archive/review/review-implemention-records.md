# review 模块实现记录（归档索引）

> 本文件原为单文件归档（板块三~七全量正文），2026-10-28 按板块拆分至本目录五个板块文件，本页保留为索引；正文一律在板块文件中维护（批次6 于 2026-10-31 新增板块八文件，迁自 docs/temp/temp.md）。本归档自板块三起收录（板块一~二未在本单文件内，早期公共基建记录见 [20260929-implementation-records.md](../20260929-implementation-records.md)）。
> **来源**：迁移自 docs/next/next_update.md（板块条目摘要）与 docs/next/status.md（实施细案），含 2026-10-13 自 status.md 转移的子项级进度勾选补录，条目原样搬运不压缩。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；挂起项见 [Temporarily_not_implemented.md](../../next/temporarily/Temporarily_not_implemented.md)；架构口径见 [Review-Architecture.md](../../Architecture/Review-Architecture.md)。

## 板块索引

| 板块 | 主题 | 文件 |
| --- | --- | --- |
| 板块三 | MQ 异步机审链路（§4.2.1/§4.2.2/§4.5.2/§4.2.4） | [review-03-mq-moderation.md](review-03-mq-moderation.md) |
| 板块四 | 发布侧文本链路接入 + auth 注册审核嵌入/punish 消费端（§4.2.1/§4.2.2） | [review-04-publish-auth.md](review-04-publish-auth.md) |
| 板块五 | 申诉与处罚联动闭环（§4.2.3/§4.5.2） | [review-05-appeal-punish.md](review-05-appeal-punish.md) |
| 板块六 | Sweeper 基建 + quarantine 清理（§4.3/§4.2.2） | [review-06-sweeper-quarantine.md](review-06-sweeper-quarantine.md) |
| 板块七 | 观测与告警收尾（§4.8/§4.7） | [review-07-observability-alert.md](review-07-observability-alert.md) |
| 板块八 | 词库治理与 DFA 同步闸门优化（wordlib_misreport/词库建议清单/AC 自动机替换批次1~4；迁自 temp.md 批次6） | [review-08-wordlib-dfa.md](review-08-wordlib-dfa.md) |

## 后续修改
