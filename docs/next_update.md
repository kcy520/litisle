e:\NewJavaPro\LitIsle\docs\next_update.md
# litisle-file 开发清单（依据代码注释生成）

> 生成日期：2026-09-22 ｜ 依据：litisle-file/src 各类注释中的实现规划
> 核对日期：2026-09-22 ｜ 已对照 litisle-file/src/main 现有代码逐项核查并更新勾选状态
> 设计文档：Sql-Document.md §八 · File-Architecture.md · seaweedfs笔记.md §3.1-§3.6

## 阶段〇：前置准备（依赖与配置）

- [x] 启动类启用 @EnableScheduling（已启用；⚠️ FileReconcileTask 类尚未创建，见阶段六）

## 阶段一：DAO 层（MyBatis-Plus）

- [x] FileMetaMapper：@Mapper + extends BaseMapper<FileMeta>（注释已列四类关键查询，
      全走 MP 便捷方法 + LambdaQueryWrapper，零 XML）
- [x] FileMeta 实体确认含 deleteTime 字段（方案C 新增列）

## 阶段二：存储客户端（SeaweedFsClient，已全部实现）

- [x] put(bucket, key, bytes, contentType)：PutObjectRequest + contentType，
      SdkException 统一转 BizException(FILE_STORAGE_UNAVAILABLE)，fail-fast 不重试
- [x] get(bucket, key)：getObjectAsByteArray（小文件；大文件流式留后续扩展）
- [x] delete(bucket, key)：deleteObject（含缩略图派生 key 场景由调用方处理）
- [x] presign(bucket, key, expire)：GetObjectRequest + S3Presigner，纯本地签名不联网；
      业务校验（bizType/权限/配额）必须由 Service 层前置
- [x] 【超额实现】exists(bucket, key)：head 探测（缩略图/对账补偿用）+
      listKeys(bucket, prefix)：分页前缀查询（物理清理缩略图派生 key 用）

## 阶段三：Service 实现（FileServiceImpl，最复杂链路）

- [x] FileService 接口方法签名已补全（upload/uploadBatch/getUrl/batchGetUrls/
      getMeta/getThumbnail/bindRef/unbindRef/delete 均已声明，实现待补）
- [x] 上传（含秒传）已按 9 步落地（upload/uploadBatch 实现完毕；步骤⑧审核埋点按预留不动；
      8 个私有辅助方法 validateUpload/sha256Hex/selectByDedupKey/resolveBucket/resolveExt/
      resolveAccess/resolveImageSize/buildUploadVO 已于 2026-09-22 补齐，原 __APPEND_METHODS__ 占位消除）：
      ① 校验（size ≤ maxImageSize + MIME 白名单按 bizType 区分，LICENSE 放开 pdf 等）
      ② DigestInputStream 流式算 SHA256 + 记 size
      ③ 无锁快查判重（sha256+size+biz_type+del_flag=0，PRIVATE 追加 owner_id）
      ④ Redisson 分布式锁 biz:file:sha:{hash}，leaseTime=10s + 看门狗，锁内双检
      ⑤ 锁内临界区：fileId=UUID(无连字符)，path={yyyy/MM/dd}/{fileId}.{ext}，
         insert status=0 占位；LICENSE 服务层强制 access=PRIVATE
      ⑥ 释放锁
      ⑦ 锁外 put（幂等）→ ImageIO 解析宽高 → update status=1
      ⑧ 【预留】status=1 后异步审核消息埋点（safe_flag 链路，暂不实现）
      ⑨ 返回 fileId + deduplicated
- [x] getUrl(fileId)：access 分流——PUBLIC→publicUrlBase+bucket+path 拼直链；
      PRIVATE→owner/ADMIN 校验 + presign（默认 30min）
- [x] batchGetUrls(fileIds)：一次 in 批查避免 N+1；PRIVATE 直接预签（仅限内部 Feign，
      无 REST 入口；若未来暴露 REST 必须补 operatorId 校验）
- [x] presign 强制签发：expireMinutes ≤ 配置上限校验（防 99999 绕过 LICENSE 限时）+
      PRIVATE 纵深防御（PUBLIC 拒签，走直链）
- [x] getMeta(fileId)
- [x] 【方案C】bindRef/unbindRef：引用登记/解绑入口，四条件（fileId+bizType+bizId+ownerId）
      精确定位；bindRef 幂等（已登记跳过），与业务表同事务由调用方保证
- [x] 【方案C】delete(fileId, operatorId) 三步解绑语义：
      Step1 删 file_ref 当前调用方引用行（file_id+owner_id，一次清完名下引用）→
      Step2 仍有活跃引用则结束 → Step3 无引用才 del_flag=1 + delete_time=now()
- 公共辅助：getMetaOrThrow（del_flag=0 过滤+FILE_NOT_FOUND）/ checkOwnerOrAdmin
  （operatorId==ownerId 或 SaRoleHelper.isAdmin，否则 NO_PERMISSION）/ buildPublicUrl
  （buildUploadVO 已复用消除重复）
- 缩略图接入 FileProperties.thumbnail：enabled=false 拒绝、非图片拒绝、
  w/h 空取 yml 默认 300×300、上限 MAX_THUMBNAIL_DIMENSION=2000 防滥用
- 【已知取舍】getThumbnail 接口无 operatorId 参数，按清单"登录即可"实现——
  PRIVATE 原图（LICENSE 若为图片）的缩略图可被任意登录用户获取，建议后续给
  FileService.getThumbnail 补 operatorId 参数收紧（与 getUrl 口径对齐）

【2026-09-22 核查发现（阶段三）】
- 锁语义更正：代码 tryLock(key, 5, TimeUnit.SECONDS) 的 5 是 waitTime（非清单原述 leaseTime=10s），
  未显式传 leaseTime → 看门狗自动续期生效；等锁超 5s 抛 SYSTEM_ERROR"系统繁忙"
- upload 锁外 file.getBytes() 为全量读内存（≤10MB 可接受；未来大文件改造点）
- ImageIO 原生不支持 webp → webp 原图 width/height 为 null（已知取舍）
- 可选优化：锁竞争失败复用 SYSTEM_ERROR，可考虑语义化错误码（如 FILE_UPLOAD_BUSY）

## 阶段四：REST 层（FileController，/api/file，7 接口）

> 状态：✅ 已完成（2026-09-22）——7 接口全部落地：@RestController + @Tag/@Operation
> + 每方法 @SaCheckLogin；ownerId/operatorId 统一 StpUtil.getLoginIdAsLong()（见上方实施偏差）；
> 返回 Result<T>；Knife4j 已扫 controller 包，启动即出文档

## 阶段六：对账定时任务【下一阶段】

- [x] POST /upload（multipart: file + bizType）
- [x] POST /upload/batch（上限 9 张）
- [x] GET /{fileId}/url（PUBLIC 登录即可；PRIVATE 需 owner/ADMIN，口径统一见审查表 #6）
- [x] POST /{fileId}/presign（owner/ADMIN）
- [x] GET /{fileId}/meta（登录）
- [x] POST /{fileId}/thumbnail（登录，w/h 可选）
- [x] DELETE /{fileId}（owner/ADMIN，方案C 解绑语义）
- [x] 【实施偏差】ownerId 未取网关 X-User-Id（HeaderConstant）——网关 Header 注入尚未落地
      （网关计划 G1 未完成），实际用 StpUtil.getLoginIdAsLong()（Sa-Token 会话共享，与
      auth/user 口径一致）；骨架 javadoc 已追加实施更正说明，未来接入 Header 方案时再切换

## 阶段五：缩略图（ThumbnailGenerator，已实现）

- [x] 派生 key：原图 path + _thumb_{w}_{h}（不落库，File-Architecture §4.1 要点5）
- [x] head 探测存在 → 直接返回直链；不存在 → get 原图 → resize → put 回同 bucket
- [x] 并发重复生成不加锁（put 幂等）
- 【已知取舍】缩略图统一按 jpg 输出（image/jpeg），png 透明度会丢失；
  FileProperties.thumbnail 的 enabled/width/height 尚无消费方，getThumbnail 实现时需接上

## 阶段六：对账定时任务（@Scheduled 每日 02:00，多实例需 Redisson 锁）

> 状态：✅ 已完成（2026-09-22）——新建 task/FileReconcileTask.java，
> 约定对齐 litisle-user/UserUnbanTask（cron 占位符可配置 litisle.file.reconcile-task.cron，
> 默认 0 0 2 * * ?）；@EnableScheduling 不再空转

- [x] Step1 补偿上传：扫 status=0 且超 30min → S3 head 存在则置 status=1，
      不存在则 del_flag=1 + delete_time 废弃
- [x] Step2 物理清理：扫 del_flag=1 且 delete_time 超 7 天 → 前置校验 file_ref 无活跃引用
      （仍有引用 → 跳过并告警）→ listKeys 前缀 path 查出本体 + _thumb_* 派生 key 逐个删 →
      删 file_meta + 防御性同删 file_ref 残留行
- 实现细节：tryLock(waitTime=0) 抢不到锁直接放弃本轮（对账无需排队）；
      单条记录独立 try-catch 捕 BizException，单条失败不断整批，靠每日重跑自愈

## 【2026-09-22 阶段〇～二复查结论】
- 阶段〇/一/二清单项全部落地（除 Docker 运行时确认待手动执行）；
- 复查发现 FileServiceImpl 的 upload() 引用的 8 个私有辅助方法缺失（__APPEND_METHODS__ 占位未填充，
  模块无法编译），已补齐：流式 SHA256、LICENSE MIME 白名单合并、PRIVATE 按 owner 隔离判重、
  bucket 映射兜底报错、buildUploadVO 按 access 分流（PUBLIC 直链 / PRIVATE 预签名）
- buildUploadVO 补充细节：步骤⑨回填 ImageIO 解析的宽高；秒传命中 PRIVATE 时按存量 bucket/path
  现签预签名 URL（不落库、短时效）

## 阶段七：验证与收尾【下一阶段（全部为手动验证/手动清理项）】

> 【2026-09-22 全量复查结论】阶段〇～六代码无遗漏；修复 1 个 bug：
> yml collections 键 article-img 与 BizTypeEnum.ARTICLE_IMG 的 code "article" 不匹配
> （Map 键字面量绑定不享受宽松匹配，正文图上传必抛异常），已改为 article: article；
> 顺带记录：REST 的 bizType 参数按枚举名（AVATAR/COVER/ARTICLE_IMG/LICENSE）传，
> 非小写 code；FileProperties 类注释中 "article-img→article" 描述已过时 ---------建议删除此注释或改为 article→article

## 【2026-09-22 企业规范审查】

已修复：
- 锁前缀注释纠偏：RedissonLock 实际追加 lock:（CacheConstant.LOCK_PREFIX），非 biz:；
  实际锁 key 为 lock:file:sha:{hash}，排障按此查 Redis
- 对账锁 key 风格统一：biz:file:reconcile → file:reconcile（避免 lock:biz: 双前缀）
- uploadBatch 语义描述纠偏：逐个独立上传，中途失败不回滚已成功文件（无事务性）

待决策建议项（已全部修复 2026-09-22）：
- [x] FileServiceImpl 补关键日志：秒传命中（快查/锁内双检各一处）、锁竞争失败（warn）、
      put 成功、软删完成（fileId+operatorId）
- [x] selectByDedupKey 改 selectList 取首条（脏数据多行时降级复用而非抛 TooManyResults）
- [x] getMeta 脱敏：签名加 operatorId（接口/实现/Controller 三层同步），
      非 owner 且非 ADMIN 时 ownerId 置 null
- [x] ADMIN 代删语义文档化：行为保持不变（防悬空设计保留），Controller @Operation
      与接口文档明示"ADMIN 只解除本人引用行，被引用文件 ADMIN 也删不了，仅真 owner 可完成全流程"
- [x] 上传接口加 @RateLimiter：新建 constant/FileLimitConstant.java（风格对齐
      UserLimitConstant，long 常量），upload 30 次/分 + batch 5 次/分，KeyType.USER 按登录人限流；
      common-redis 已在依赖中无需改 pom

- [ ] 单文件上传（新文件）/ 秒传（同文件二次上传，deduplicated=true 不落 SeaweedFS）
- [ ] PRIVATE 隔离：不同 owner 传同一 LICENSE 不秒传命中；LICENSE 拿到的必是预签名 URL
- [ ] 方案C 链路：A 传 + B 秒传 → A 删（文件应保留）→ B 解绑 → 再删（7 天后物理清理）
- [ ] 对账任务手动触发验证（status=0 幽灵记录、孤儿对象两场景）
- [ ] 清理启动类底部 user-service 复制来的 @EnableFeignClients 模板块
      （现为 /* */ 注释块不生效，仅残留待清理 ---------建议删除此注释）
- [ ] 手动处理两处"建议删除此注释"标注：AlignEnum 第21行 `//Align:对齐` /
      SeaweedFsConfig 类尾依赖图 ASCII 图（与 SeaweedFsClient 注释重复）---------建议删除此注释

## 暂不实现（注释中已声明的预留项）

- [ ] safe_flag 审核链路（MQ 异步消费 + getUrl 闸口校验）——架构已预留，接入零侵入
- [ ] FileFeignApi（batchGetUrls 跨服务暴露）——当前先编译期依赖，未来再抽
- [ ] 大文件流式下载——当前 ≤10MB 场景 getObjectAsByteArray 足够
- [ ] CDN 接入——public-url-base 已分离，届时仅改配置