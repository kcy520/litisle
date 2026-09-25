## 暂不实现（注释中已声明的预留项）

- [ ] safe_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
- [ ] FileFeignApi（batchGetUrls 跨服务暴露）——当前先编译期依赖，未来再抽
- [ ] 大文件流式下载——当前 ≤10MB 场景 getObjectAsByteArray 足够
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置

## 待实现（按优先级排序）

### P0：file_ref 引用登记链路补齐（跨 litisle-file / litisle-api / litisle-user 三模块）

**背景**：秒传为全局去重（AVATAR/COVER/ARTICLE_IMG 跨账号共享同一条 file_meta），
而 `bindRef/unbindRef` 目前全项目零调用方，file_ref 恒为空表。后果：用户删除文件时
查不到活跃引用 → 直接 `del_flag=1` → 7 天后对账任务物理删除，将悬空其他秒传复用方的引用
（典型场景：A/B 秒传共用同一张图作头像，A 删除文件 → B 的头像 7 天后 404）。

- [ ] litisle-file：新增内部 REST 端点 `POST /api/file/ref/bind`、`POST /api/file/ref/unbind`
      （委托 `fileService.bindRef/unbindRef`；网关匿名白名单/路由需拉黑外部直访，仅服务间可用）
- [ ] litisle-api：新增 `FileClient`（Feign）+ `FileClientFallbackFactory`
      （bind/unbind 属核心写操作，fallback 必须 fail-fast；按规范注册进
      `FeignFallbackAutoConfiguration`，DataId 走 litisle-shared.yaml 的 feign.sentinel.enabled）
- [ ] litisle-user：实现「设置头像」接口（当前 updateCurrentUserProfile 未处理 avatarFileId，
      全项目无任何代码写入 sys_user.avatar_file_id——头像链路只完成了一半）：
      ① 归属校验：file_meta.owner_id == 当前登录人（防挂他人文件）；
      ② 旧头像 unbindRef（bizType=AVATAR，bizId=sys_user.id）；
      ③ 新头像 bindRef；④ 更新 sys_user.avatar_file_id。
      ①~④ 与业务表同事务（跨服务 Seata AT，litisle_file 库 undo_log 已建）
- [ ] 验收：A/B 共图头像 → A 换头像（unbind）→ A delete 后 B 头像不受影响；
      双方均解绑且删除 → del_flag=1 → 7 天后物理清理 → file_ref 同清

### P1：对账任务健壮性

- [ ] FileReconcileTask 两处循环 `catch (BizException)` 放宽为 `catch (Exception)`：
      防 NPE 等非受检异常中断整轮对账，落实"单条失败不影响其余，下一轮自愈"的注释承诺

### P2：已知取舍 / 低风险优化（暂不阻塞）

- [ ] resolveExt 对点开头文件名（如 `.gitignore`）会误判扩展名为 `gitignore`
      （仅影响存储路径美观，无功能/安全风险）
- [ ] 秒传命中在途记录（status=0）时访问 URL 可能短暂 404——已知取舍（put 秒级完成 +
      调用方重试自愈，见 seaweedfs笔记.md §3.4.6），线上观察后再决定是否加状态校验

## 本次已修复（2026-09-25，litisle-file 排查会话）

- [x] 上传报错 `Field 'sha256' doesn't have a default value`：upload() 锁内占位写库漏设 sha256，补 `setSha256(sha256)`
- [x] 缩略图接口绕过 PRIVATE 鉴权：getThumbnail 对 PRIVATE（LICENSE）直接拒绝，防公开直链泄露私有文件
- [x] 公开直链不可访问：public-url-base 由 S3 签名端口 8333 改为 Filer 匿名端口 8888，
      并追加 `/buckets` 前缀（Filer 侧 S3 桶挂在 /buckets/ 目录下）
- [x] 分布式锁注释与实现不一致纠偏（leaseTime=5s 自动释放，非看门狗续期）