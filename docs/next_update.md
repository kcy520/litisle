## 暂不实现（注释中已声明的预留项）

- [ ] safe\_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
- [ ] FileFeignApi（batchGetUrls 跨服务暴露）——当前先编译期依赖，未来再抽
- [ ] 大文件流式下载——当前 ≤10MB 场景 getObjectAsByteArray 足够
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置

## 待实现（按优先级排序）

### P0：file\_ref 引用登记链路补齐（跨 litisle-file / litisle-api / litisle-user 三模块）

**背景**：秒传为全局去重（AVATAR/COVER/ARTICLE\_IMG 跨账号共享同一条 file\_meta），
而 `bindRef/unbindRef` 目前全项目零调用方，file\_ref 恒为空表。后果：用户删除文件时
查不到活跃引用 → 直接 `del_flag=1` → 7 天后对账任务物理删除，将悬空其他秒传复用方的引用
（典型场景：A/B 秒传共用同一张图作头像，A 删除文件 → B 的头像 7 天后 404）。

- [x] litisle-file：内部端点已实现——【实施偏差】按 Feign 规范落在 `/api/feign/file/ref/bind|unbind`
  与 `/api/feign/file/{fileId}/info`（FileFeignProviderController implements FileClient），
  而非清单原拟 `/api/file/ref/*`：规范前缀网关未注册路由，外部请求天然不可达，免网关黑名单开发
  （原路径任意登录用户可直访并伪造 ownerId）；委托 `fileService.bindRef/unbindRef` 不变
- [x] litisle-api：新增 `FileClient`（Feign，getFileInfo/bindRef/unbindRef）+ `FileClientFallbackFactory`
  （bind/unbind 属核心写操作，fallback 必须 fail-fast；按规范注册进
  `FeignFallbackAutoConfiguration`，DataId 走 litisle-shared.yaml 的 feign.sentinel.enabled）
- [x] litisle-user：实现「设置头像」接口（POST /api/user/profile/avatar + updateAvatar；
  原 updateCurrentUserProfile 未处理 avatarFileId，
  全项目无任何代码写入 sys\_user.avatar\_file\_id——头像链路只完成了一半）：
  ① 归属校验：file\_meta.owner\_id == 当前登录人（防挂他人文件）；
  ② 旧头像 unbindRef（bizType=AVATAR，bizId=sys\_user.id）；
  ③ 新头像 bindRef；④ 更新 sys\_user.avatar\_file\_id。
  ①\~④ 与业务表同事务（跨服务 Seata AT，litisle\_file 库 undo\_log 已建）
- [ ] 验收：A/B 共图头像 → A 换头像（unbind）→ A delete 后 B 头像不受影响（待启动两服务实测）；
  双方均解绑且删除 → del\_flag=1 → 7 天后物理清理 → file\_ref 同清

### P0.5：Seata AT 应用级接入（本次实施时发现，清单原假定已具备）

【发现】全项目零 seata 依赖、零 @GlobalTransactional——仅 DB 层 undo\_log 已建、
Seata Server 已在 compose.yaml 部署。updateAvatar 跨服务一致性当前由
@Transactional（本地）+ bind fail-fast + unbind 尽力而为兑底，③④ 之间失败会残留解绑。

- [ ] litisle-user / litisle-file pom 加 spring-cloud-starter-alibaba-seata
- [ ] yml（Nacos 共享模板）：seata.tx-service-group + vgroup-mapping + registry 指向 Nacos
- [ ] updateAvatar 的 @Transactional 升级 @GlobalTransactional，验收 ③ 失败整体回滚

### P1：对账任务健壮性

- [ ] FileReconcileTask 两处循环 `catch (BizException)` 放宽为 `catch (Exception)`：
  防 NPE 等非受检异常中断整轮对账，落实"单条失败不影响其余，下一轮自愈"的注释承诺

### P2：已知取舍 / 低风险优化（暂不阻塞）

- [x] resolveExt 对点开头文件名（如 `.gitignore`）会误判扩展名为 `gitignore`
  （仅影响存储路径美观，无功能/安全风险）
- [ ] 秒传命中在途记录（status=0）时访问 URL 可能短暂 404——已知取舍（put 秒级完成 +
  调用方重试自愈，见 seaweedfs笔记.md §3.4.6），线上观察后再决定是否加状态校验
- [x] updateAvatar 旧头像解绑为尽力而为：unbindRef 前置 getMetaOrThrow，旧文件已被物理清理
  时抛 FILE\_NOT\_FOUND，引用行无法解绑——残留行无实害（对账物理清理有"仍有引用则跳过"兑底），
  调用侧降级为 warn 日志不阻断换头像
- [ ] FileController 类注释"引用登记无 REST 入口：业务方经编译期依赖 FileService"已过时
  （现另有 /api/feign/file 端点），需同步更新注释口径
- [ ] UserAdminService 接口上的类级 @Transactional 下沉到 UserAdminServiceImpl 的写方法：
  Spring Boot 默认 CGLIB 代理下接口注解不随实现传递，当前属碰巧生效（踩在 proxy-target-class
  默认值上）；且类级粒度会让读方法（page/detail）也白开事务占连接。参照
  UserProfileServiceImpl 的实现类方法级写法统一

## 本次已修复（2026-09-25，litisle-file 排查会话）

- [x] 上传报错 `Field 'sha256' doesn't have a default value`：upload() 锁内占位写库漏设 sha256，补 `setSha256(sha256)`
- [x] 缩略图接口绕过 PRIVATE 鉴权：getThumbnail 对 PRIVATE（LICENSE）直接拒绝，防公开直链泄露私有文件
- [x] 公开直链不可访问：public-url-base 由 S3 签名端口 8333 改为 Filer 匿名端口 8888，
  并追加 `/buckets` 前缀（Filer 侧 S3 桶挂在 /buckets/ 目录下）
- [x] 分布式锁注释与实现不一致纠偏（leaseTime=5s 自动释放，非看门狗续期）

## 本次已实现（2026-09-25，P0 file\_ref 引用登记链路会话）

- [x] litisle-api：FileRefDTO / FileOwnerDTO / FileClient / FileClientFallbackFactory（全 fail-fast），
  并注册进 FeignFallbackAutoConfiguration
- [x] litisle-file：FileFeignProviderController（getFileInfo 不脱敏 + bind/unbind 幂等委托；
  bizType 走 DictEnum 三值兼容解析）+ FileService.getInternalMeta（区别于对外 meta 的脱敏口径，
  不调 SaRoleHelper——Feign 服务端线程无登录会话）
- [x] litisle-user：AvatarUpdateDTO + POST /api/user/profile/avatar + updateAvatar
  （①归属校验→②旧头像解绑尽力而为→③新头像 bindRef fail-fast→④回写 sys\_user.avatar\_file\_id），
  Seata 升级点已注释标注（见 P0.5）

