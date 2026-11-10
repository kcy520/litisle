# auth 模块实现记录（归档索引）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md) 批次十七的已完成条目 17.4，条目内容原样搬运（含现状、改法、验收标准、涉及文件与运行态验证证据），编号不重排。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；模块速查见 [auth-index.md](../../index/auth-index.md)。

## 目录定位

本目录收录 `litisle-auth`（认证与处罚执行服务）的已完成实现记录。批次十七整体横跨 common-redis 分布式锁与 auth 消息凭证两个主题，17.4 代码与配置归属 auth，因此按模块归属归档到本目录，不归入 common-redis。

## 批次索引

| 批次 | 主题 | 文件 |
| --- | --- | --- |
| 批次十七（17.4） | auth `spring.rabbitmq` 补齐 `username`/`password`，与 content/review/file/user/search 统一为 `litisle` / `litisle_pass` + vhost `/litisle`（运行态验证 PASS） | [auth-17-rabbitmq-credential.md](auth-17-rabbitmq-credential.md) |
