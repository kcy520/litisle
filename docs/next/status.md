
# 审核模块 · 进度看板（status）

> 本页已收敛为**纯指针页**（2026-10-13）：勾选明细与落地注记不再在此维护，仅保留内容去向与执行锚点。

## 内容去向

- **已实现明细**（板块三/四/五/六/七 全部父项+子项级勾选、auth A/B 组落地记录、各板块实施细案步骤）→ `docs/archive/review-implemention-records.md`
- **挂起/暂不实现项**（3-5 / 3-7.2 / 3-7.3 / 4-2.1 / 4-2.2 / 4-3.1 / 4-3.2 / 4-5.1 / 4-5.2 / 5-4.4 及各入口嵌入项，触发条件=content/social/message 正式开发时回接）→ `docs/next/Temporarily_not_implemented.md`
- **细案原件** → `docs/next/next_update.md`（只读，勿改）与同目录 `spec-*.md`
- **file 侧基线**（safe_flag 四态闸口 / 拒传黑名单 / quarantine 契约 / result·appeal.result 消费回写 / getContent / 占位图 / 对账跳隔离前缀）与 **litisle-api 契约层**（MqConstant / ModerationConstant / 事件体 / FileClient）→ 已随基线完成，明细见归档文档

## 当前执行锚点

- **前置阻塞**：litisle-ai chat 端点（`POST /api/feign/ai/chat`）未实现——3-1 联调前必须落地（见 Temporarily_not_implemented.md 预留项）
- **推进状态**：板块五/六/七已全量落地并归档；板块三/四挂起项随 content/social 开发回接

> 维护纪律：后续新落地项直接写入归档文档对应板块，本页仅维护指针与锚点。


