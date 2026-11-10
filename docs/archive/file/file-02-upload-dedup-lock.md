# file 批次十七：秒传判重锁改固定租期（归档）

> **归档日期**：2026-11-08
> **来源**：迁移自 [next_update.md](../../next/next_update.md)「分布式锁工具与消息中间件凭证收尾（批次十七）」的已完成条目 17.2，正文原样搬运（现状 / 为什么做 / 改法 / 验收标准 / 涉及文件 / 运行态验证证据 / 环境缺口说明），**编号不重排**。
> **归档范围**：17.2（`FileUploadServiceImpl` 秒传判重锁由看门狗改固定租期；运行态验证 PASS）。
> **归属说明**：改动落在 `litisle-file` 的上传判重链路，与 `docs/archive/file/` 已有板块文件（删除语义）主题不同，故新建板块文件；不自建 `docs/archive/file/` 之外的目录，也不并入 common-redis 的锁工具归档（那里记的是锁工具本体与 review 侧使用）。
> **互链**：活跃待办见 [next_update.md](../../next/next_update.md)；本目录索引见 [file-implemention-records.md](file-implemention-records.md)。

## 板块二：上传判重锁与租期（2026-11-08）

### 17.2 `FileUploadServiceImpl` 秒传判重改固定租期

- [x] 【P1｜17.2】秒传判重锁由看门狗改固定租期
  - **现状**：`FileUploadServiceImpl` 秒传判重当前为 `tryLock(DEDUP_LOCK_KEY_PREFIX + sha256, 5, TimeUnit.SECONDS)`（waitTime=5s、租期 -1 → 看门狗续期）。
  - **为什么做**：临界区只有「查库 + 占位写库」，是毫秒级短任务；用看门狗意味着持锁线程卡死时锁被持续续期，同 sha256 的上传会被阻塞到进程退出。
  - **改法**：改为 `tryLock(key, 5, 10, TimeUnit.SECONDS)`（等锁 5s、固定租期 10s），不改 key 与业务逻辑。
  - **验收标准**：上传并发下同一文件不会重复落库；模拟持锁线程异常/卡死时锁在 10s 内自动释放，后续请求恢复。
  - **涉及文件**：[FileUploadServiceImpl.java:184](../../../litisle-file/src/main/java/com/litisle/litislefile/service/impl/FileUploadServiceImpl.java#L184)（调用点）与同文件 [L99](../../../litisle-file/src/main/java/com/litisle/litislefile/service/impl/FileUploadServiceImpl.java#L99)（`DEDUP_LOCK_KEY_PREFIX`）。
  - ✅ 2026-11-08（运行态验证 PASS）：
    - **并发**：6 个并发上传同一新文件（`bizType=COVER`、sha256=`2098a6dc…`、235B）→ 1 个 `deduplicated=false` 真实落盘，5 个 `deduplicated=true` 命中秒传；`file_meta` 该摘要仅 1 行（status=1）。
    - **固定租期**：Redis MONITOR 显示抢锁命令为 `HSET lock:file:sha:<sha> <uuid:thread> 1` + `PEXPIRE … 10000`，无看门狗续期；正常处理 29~50ms 后由 Redisson 解锁脚本删除锁。
    - **租期到点自动释放**：手工写入同结构锁键（`HSET … stuck-holder:1 1` + `PEXPIRE 10000`，模拟持锁者不释放），首次上传等待 `5.35s` 后返回 `{"code":10000,"message":"系统繁忙，请稍后重试"}`（waitTime=5s 生效）；该键在 10s 后自然过期（t≈6.5s 时 `PTTL=3499`），第二次上传在 t≈11.4s 成功抢锁并返回 `deduplicated=false`。
    - **环境缺口（非代码）**：首次用 `LICENSE` 上传返回 `90005 文件存储服务不可用`，原因是 SeaweedFS 仅有 `avatar`/`cover` 桶、`license` 桶未创建；改用 `cover` 后通过。
    - **新增测试数据（仅登记不清理）**：`file_meta` sha256=`2098a6dc…` 的 `license` 行 6 条（status=0，失败占位）、`cover` 行 1 条（status=1）；sha256=`59197412…` 的 `cover` 行 1 条（status=1）；SeaweedFS `cover/2026/11/08/b71490f244534862ab650f1aa32817a6.png`。

## 归档说明

- 本文件归档 1 项（17.2）。
- 验收中「持锁线程卡死时 10s 内自动释放」用手工写入同结构锁键模拟，未真实制造线程卡死；`PEXPIRE 10000` 且无续期由 MONITOR 直接证实，固定租期语义成立。
- 编号空缺：主清单批次十七的 17.2 空缺指向本文件；编号不重排。
