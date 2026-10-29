> **拆分归档**：本文件拆分自同目录索引页 [content-implemention-records.md](content-implemention-records.md)，原单文件对应板块正文原样搬运（2026-10-28 拆分，内容零修改），各板块入口见索引页。
> 通用纪律（适用板块一~八）：事件发送一律本地事务提交后 afterCommit；SQL 全走 #{}；所有 CAS 补 del_flag=0；CAS affected=0 一律静默跳过事件发送（不落库不发送，对齐 review publisher「落库失败不发」铁律，防免审直落并发双击/重复提交把重复事件打进 search/notification）。

## 板块二：草稿系统

**落位**：`service/DraftService`、`controller/DraftController`

**任务完成概览**（迁自 next_update.md）：

- [x] 2.1 baseVersion 乐观锁 + 同版本幂等；归属校验防横向越权
  - ✅ 2026-10-17：DraftServiceImpl#save 四分支严格按序判定（落后冲突/超前非法/同版本幂等/同版本递增）+ locateDraft 归属校验，细目见下方 [2.1]；编译验证已通过
- [x] 2.2 快照保留与图片暂存
  - ✅ 2026-10-17：afterCommit 滚动清理（keep-versions=10 配置化）+ 草稿图片 draft_id 暂存整体重挂（ARTICLE_IMG 归属校验同口径），细目见下方 [2.2]；跨板块遗留：发布归属切换 draft_id→article_id（已随板块三 [3.1] 落地）、孤儿图片 file 对账兑底（file 侧）

**实现细案**（迁自 status.md）：

- [x] [2.1] DraftSaveDTO 携带 baseVersion；保存流程：归属校验（draft/article 的 author_id 比对当前登录用户，防横向越权）→ 版本判定严格按序四分支（防重复请求误入冲突分支）：①相等且内容 hash 未变→幂等跳过（网络抖动重发同请求，不再递增）；②相等且内容有变→UPSERT+version+1；③baseVersion<draft_version→抛 ARTICLE_DRAFT_CONFLICT（后写不覆盖先写）；④baseVersion>draft_version→非法参数拒绝（客户端版本超前，理论不可达）。（2026-10-17 落地：DraftServiceImpl#save——locateDraft 定位+归属校验（articleId 传值→article.author_id 比对，非本人按不存在处理；null→复用本人最新未关联草稿）+ 严格按序四分支：cmp<0 抛 ARTICLE_DRAFT_CONFLICT、cmp>0 抛 ARTICLE_PARAM_INVALID、同版本 sameContent 全字段比对幂等跳过（visibility=null 沿用不构成变更）+ imagesUnchanged 图片比对、同版本变内容 UPSERT draft_version+1；单行 UPSERT 语义，DraftSaveVO.draftId 闭环）
- [x] [2.2] 快照保留：仅留最近 N 版（`litisle.content.draft.keep-versions` 默认 10），保存事务提交后（afterCommit）异步滚动清理最旧快照——清理不进主事务，防 longtext 删除拉长保存 RT；防 longtext 膨胀。（2026-10-17 落地：cleanupOldSnapshotsAfterCommit 注册 afterCommit（事务提交点后执行，不进主事务；无 @Async 基建不新引入线程池）+ dimensionWrapper 维度口径与 locateDraft 一致；主链路单行 UPSERT 正常仅 1 行，清理为并发/异常残留行防膨胀兑底；keep-versions 配置 litisle.content.draft.keep-versions:10 已入 application.yml）
- [x] [2.2] 草稿图片暂存：article_image.draft_id 关联；新草稿插图时 article_id 空置仅挂 draft_id（DDL article_id 已 nullable，发布归属切换时回填）；发布时归属切换 draft_id→article_id 并清空 draft_id；废弃草稿孤儿图片由 file 对账链路兑底（content 不直删）。（2026-10-17 落地：remountDraftImages——images 非 null 整体重挂（物理删旧+按序插新，article_image 无 del_flag）、article_id 空置仅挂 draft_id；checkImageOwnership 与 ArticleServiceImpl 同口径（本人+ARTICLE_IMG 双条件 fail-fast 抛 ARTICLE_IMG_REFUSED）；imagesUnchanged 排序后逐项比对防顺序抖动误判；遗留：发布归属切换 draft_id→article_id 属板块三 [3.1]、孤儿图片 file 对账兑底属 file 侧）

**验收**：并发保存乐观锁生效；版本冲突不覆盖先写；快照滚动清理正确。

------------

