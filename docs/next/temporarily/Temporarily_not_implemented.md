# 待办与暂不实现清单（合并版）（索引）

> 本文件原为单文件清单（代码/验证/部署/暂不实现四部分全量），2026-10-28 按部分拆分至本目录四个正文文件，本页保留为索引；正文一律在正文文件中维护。
> 用途：收纳全项目待办事项，按「类别 → 模块 → 任务」三级组织。
> 合并来源：`docs/temp/temp.md`（原审核模块暂不实现清单）+ `docs/next/next_update.md` 全部待办，已去重整理。
> 整理日期：2026-10-17；已实现部分归档见 `docs/archive/review/review-implemention-records.md`（2026-10-28 拆分为五板块文件，本页为索引）、`docs/archive/20260929-implementation-records.md`。

## 部分索引

| 部分 | 内容 | 文件 |
| --- | --- | --- |
| 代码部分 | review 审核模块（人审/举报/申诉/处罚/词库/ReportController/3-5 回写矩阵消费端/3-7 限流与短锁/4-1~4-6 入口嵌入/5-4.4 恢复）· litisle-ai 模块（chat 端点）；含通用纪律六条 | [temporarily-01-code.md](temporarily-01-code.md) |
| 验证部分 | 网关/认证限流故障降级本地兜底验证 · Sentinel Dashboard 降级熔断与 Nacos 规则热生效验证 | [temporarily-02-verify.md](temporarily-02-verify.md) |
| 部署部分（生产上线前必做） | 通用：监控端口网络隔离、Sentinel 规则操作纪律 · file：replication≥010、Filer 元数据切 MySQL、instance-count 动态化 | [temporarily-03-deploy.md](temporarily-03-deploy.md) |
| 暂不实现（预留项，附触发条件） | review（云 API 适配器/多模态/公示/音视频）· content（temp.md 历批迁入项：Book 分类/悬空兜底/计数契约/热度榜调参/阅读历史扩展/收藏迁移/书籍配套缺口等）· file（CDN/冷热分层/存储 TTL）· 公共库 | [temporarily-04-deferred.md](temporarily-04-deferred.md) |
