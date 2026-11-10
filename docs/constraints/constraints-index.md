# 已知约束与风险（索引）

> **目录定位**：本目录**不是待办清单**，收纳「已知、但暂时不必处理，只有触发条件成立时才需要动手」的约束与风险。与四类文档分工互补：`docs/next/next_update.md`（活跃待办）、`docs/next/temporarily/`（暂不实现/挂起项）、`docs/archive/`（已完成工作归档）、本目录（已知约束/风险登记）。
> **新建日期**：2026-11-07（R1、R3 自 `docs/next/next_update.md` 批次十二「已知风险」迁入）
> **来源**：原 `docs/next/next_update.md` 批次十二「已知风险 R1~R3」；条目原文搬运，含触发条件与处置方式，未压缩
> **互链**：活跃待办/验证见 [next_update.md](../next/next_update.md)；已完成归档见 [content-implemention-records.md](../archive/content/content-implemention-records.md)；挂起项见 [Temporarily_not_implemented.md](../next/temporarily/Temporarily_not_implemented.md)

## 记录规则

- 每条保留**原编号**（如 R1、R3），不重排、不合并；新增条目在原模块文件末尾追加，编号接着该模块已有的最大号。
- 每条按「现象 / 触发条件 / 影响 / 处置方式」四要素写，**不写成摘要**；触发条件必须能直接判断「现在要不要处理」。
- 本目录条目**不改代码**：触发时才按处置方式执行（跑脚本、核对数据、换部署参数等），做完再决定是删除条目还是转成待办。

## 主题索引

| 编号 | 主题 | 文件 |
| --- | --- | --- |
| R1 | 存量统计回填能力已移出初始化脚本（关键词：回填 / content-stat-backfill） | [content-constraints.md](content-constraints.md) |
| R2 | 存量库 `article.category` / `book.category` 双重编码（mojibake），2026-11-09 已修复复核通过（关键词：category 编码 / 分类屏蔽 / cp1252） | [content-constraints.md](content-constraints.md) |
| R3 | `00-init-databases.sql` 对 `MYSQL_USER` 的隐含依赖（关键词：MYSQL_USER / 授权） | [content-constraints.md](content-constraints.md) |
| R4 | 作者自评论是否计入 `comment_count` 未验证（关键词：自评论 / comment_count / social） | [content-constraints.md](content-constraints.md) |
| R5 | `view-count-flushed` 事件进入 search 后对热度的实际影响未逐行核验（关键词：view-count-flushed / heat） | [content-constraints.md](content-constraints.md) |
| R6 | 推荐流召回不排除本人作品是否为设计意图未确认（关键词：召回 / 作者本人 / author 过滤） | [content-constraints.md](content-constraints.md) |
| R7 | 曝光去重实现仅按注释确认、未逐行核验（关键词：曝光去重 / 快照 / CTR） | [content-constraints.md](content-constraints.md) |
| R8 | file 模块 Sentinel 降级规则是否真正加载未验证（关键词：Sentinel / SeaweedFS / degrade-rules） | [file-constraints.md](file-constraints.md) |

## 尚未归入

- （无）R1\~R8 已全部归入上方主题索引；R2 于 2026-11-09 执行修复并复核通过后自 next_update.md 批次十二迁入 [content-constraints.md](content-constraints.md)。
