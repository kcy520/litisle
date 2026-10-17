# 待办事项汇总

## 目录及文档说明

- 目录及文档说明
- 待办部分
- 验证部分

### 文档说明:

- 代办任务的具体实现步骤 参考status.md文档

### 归档与暂不实现（板块完成后的文档迁移规则）

**相关文档职责**：

| 文档 | 职责 |
| --- | --- |
| `docs/next/next_update.md`（本文档） | 仅收纳「待办 + 待验证」的活跃任务，按 类别（代码/验证）→ 模块 → 板块/任务 组织 |
| `docs/next/status.md` | 各待办条目的具体实现步骤（细案），本文档不重复 |
| `docs/next/Temporarily_not_implemented.md` | 收纳暂不实现/暂不做/挂起项，每条必须附触发条件 |
| `docs/archive/` | 已完成板块的实现记录归档，命名惯例：按日期（如 `20260929-implementation-records.md`）或按主题/模块（如 `review-implemention-records.md`） |

**板块完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将该板块的实现步骤/完成记录（原散落在 status.md 或本文档条目内）整理移入 `docs/archive/` 对应归档文档（新板块新建归档文件，遵循上方命名惯例；已有同模块归档则追加章节）。
2. **本文档回填**：将已完成条目从本文档删除（或标注 ✅ 与日期后移除），保持本文档只留活跃待办。
3. **挂起项转移**：板块中因宿主模块未开发而无法落地的事项（如契约已备但入口未建），移入 `Temporarily_not_implemented.md` 对应模块节，并写明触发条件，供宿主模块开发时迁回本文档。
4. **预留项登记**：板块规划中明确「暂不实现/暂不做」的事项（含触发条件与决策原因），一并移入 `Temporarily_not_implemented.md`，不在本文档保留。

> 纪律：迁移时条目内容原样搬运（含口径修正、注意事项），不压缩关键细节；归档文档与本文档/Temporarily 清单之间用相对路径互链，保证可回溯。








--------------------------

## 待办部分

### 代码部分

#### content模块

> 板块划分依据：Content_Function_Develop.md / Content-Architecture.md；推进顺序：板块一 → 二 → 三 → 四/五/六（可并行）→ 七 → 八（依赖 review 板块三 3-5 消费端联调）

##### 板块一：模块基建与文章 CRUD

###### 1.1 实体/Mapper/状态机基建

- [ ] Sql-Document §二 8 表实体与 Mapper；article 状态机（0-草稿/1-待审/2-已发布/3-驳回/4-下架终态）流转与 CAS 铁律（禁 2→1）；方案B（status=2 禁改正文/标题）
  - ✅ 2026-10-17：实体/Mapper、del_flag 逻辑删除配置、ArticleStateMachine（canTransit 白名单，禁 2→1/4 终态）已落地，细目见 status.md 板块一 [1.1]；CAS 铁律与方案B 随 1.2/3.1 落地，本条保持待办

###### 1.2 文章 CRUD 接口

- [x] 创建/编辑/软删；插图 fileId 归属校验（本人 + bizType=ARTICLE_IMG）；article_image 对齐权威 + `{img:file_id}` 占位符渲染链路
  - ✅ 2026-10-17：ArticleServiceImpl 7 方法全量实现 + ArticleHtmlSanitizer（utils/）+ ContentEventProducer + 插图归属校验/渲染收口，细目见 status.md 板块一 [1.2]；跨板块遗留：sendSubmitted（已随三3.4 封装收口）/sendPublished（四4.2）、FOLLOWERS 回查（六）、last-hash 失效（已随三3.4 方案A 收口）、标签挂载（三）；编译验证已通过（2026-10-17）

###### 1.3 常量与错误码

- [x] ContentBizConstant、ARTICLE_* 错误码（✅ 2026-10-17：30007~30014 共 8 码扩展入 common-core ErrorCode，细目见 status.md 板块一 [1.3]）；治理动作 @AuditLog 审计留痕随 [1.2/1.3] Controller 项落地

------------

##### 板块二：草稿系统

###### 2.1 草稿快照保存

- [x] baseVersion 乐观锁 + 同版本幂等；归属校验防横向越权
  - ✅ 2026-10-17：DraftServiceImpl#save 四分支严格按序判定（落后冲突/超前非法/同版本幂等/同版本递增）+ locateDraft 归属校验，细目见 status.md 板块二 [2.1]；编译验证已通过

###### 2.2 快照保留与图片暂存

- [x] keep-versions 滚动清理；draft_id 图片暂存 → 发布归属切换；孤儿图片 file 对账兑底
  - ✅ 2026-10-17：afterCommit 滚动清理（keep-versions=10 配置化）+ 草稿图片 draft_id 暂存整体重挂（ARTICLE_IMG 归属校验同口径），细目见 status.md 板块二 [2.2]；跨板块遗留：发布归属切换 draft_id→article_id（板块三 3.1）、孤儿图片 file 对账兑底（file 侧）对账兜底

------------

##### 板块三：发布与送审链路

###### 3.1 发布编排

- [x] 同步段 A1→A3（校验/免审直落/落库发事件）；异步段 A4；afterCommit 事件铁律——已落地：createAndPublish 编排 A0 分区权限（ZoneRouter）→ A1 校验（PublishTextPrecheck 契约门面+图文同空拒+插图归属）→ sanitize 三层铁律 → DFA 同步闸门（DfaPublishGate：HIGH 拦 70001 禁回吐命中词/MID 留痕，zone=2 跳 DFA）→ A2 免审三分支（LastHashExemptChecker.decide：EXEMPT_PASS 直落 CAS 1→2 不发事件、REJECT_HINT 前置拒、SUBMIT 照常送审；免文不免图 imgReReviewPassed 门控）→ A3 落库 status=1 + afterCommit 发单条 submitted；驳回重提 3→1 走 /submit 专口（状态机禁 2→1）。注：CAS 口径等价实现为「insert 1 → CAS 1→2」（免审键需 articleId）；content.published 随板块四 [4.2] 契约缺口接通；异步段 A4 为 review 消费端职责（板块八收口）。

###### 3.2 送审事件与配图复审

- [x] 单条 content.submitted（事件体不含正文）；切片/聚合均在 review 消费端（已落地 SSOT，content 不重复切片）；配图发布复审发 image.submit（ARTICLE_IMG_RE_REVIEW 聚合指纹）——已落地：content.submitted afterCommit 单条发送（ContentEventProducer.sendSubmitted）；配图复审 image.submit（ContentEventProducer.sendImageSubmit，scene=ARTICLE_IMG_RE_REVIEW、contentHash=全部配图 sha256 字典序排序拼接后二次 SHA-256 聚合指纹，复用 SubmitTextAssembler.contentHash 口径）；免审图片连带校验经 LastHashExemptChecker.imgReReviewPassed 门控（免文不免图，EXEMPT_PASS 仅在配图复审已 PASS 时可达，不重复发事件）；新增 FileClient.batchGetSha256 契约端点（file 侧实现，审核链路 fail-fast）

###### 3.3 批量发布与分区

- [x] 单批 ≤20 逐篇事务+结果回执；zone 1/2/3 权限路由——已落地：publishBatch（TransactionTemplate 逐篇独立事务+逐篇回执+publish_batch_id 回填，CAS 0→1 未命中幂等跳过）/batchVisibility（逐篇 CAS+转私密 unpublished 事件，转公开 status=2 不重审）/previewCheck（DfaFacade 同快照，仅回 {hit,risk}）/verifyPassword（BCrypt 不回显）四方法收口 ArticlePublishServiceImpl；ZoneRouter 分区权限单点收口（发布 A0+编辑分区变更复用）；ContentProperties.publish.batchMax 配置绑定（application.yml 默认 20，Nacos 可覆盖）。注：批量发布走送审路径不做免审直落；content.published 事件随板块四 [4.2] 契约缺口接通。

###### 3.4 事件生产

- [x] ContentEventProducer：submitted/published/unpublished；下架四路径统一收口——已落地（2026-10-17）：三事件封装收口（基座四字段统一 fillBase）；下架三路径接入 content 侧（软删/单篇转私密/批量转私密：afterCommit 发 unpublished + 事务内同步调 UnpublishedCacheCleaner 失效 last-hash/text/img-result 缓存键，方案 A SCAN 通配版本段，键拼 SSOT 新增 ModerationConstant 两 SCAN 常量，fail-safe 残留键 TTL 兑底）；复审驳回/封禁连带随板块八 [8.1]/[8.2] 复用接入；published 事件随板块四 [4.2] 契约接通；Invalidator 签名澄清项已闭环（Temporarily_not_implemented.md）

> ⚠️ 前置阻塞已解除：content.unpublished 发送仅依赖本地 RabbitMQ，可独立联调；review 送审契约联调随板块八消费端接入时验证（见 Temporarily_not_implemented.md）

------------

##### 板块四：可见性控制与安全

###### 4.1 visibility 判定链

- [ ] 1/2/3/4 四级；仅关注者回查 social 契约；转公开不重审（content_hash 校验）

###### 4.2 密码访问与下架收口

- [ ] BCrypt + 失败限频防爆破；四路径必发 content.unpublished（隐私红线）

###### 4.3 限流矩阵

- [ ] @RateLimiter(USER) 全端点落地（对齐挂起项 3-7.2）

------------

##### 板块五：书籍合集与阅读进度

###### 5.1 书籍/章节管理

- [ ] book / book_chapter（uk_book_id_chapter_no）；挂载校验文章归属本人

###### 5.2 阅读进度

- [ ] 章节归属校验 + ≥30s 节流 + 仅前进不回退 + 续读

------------

##### 板块六：标签系统

###### 6.1 标签挂载

- [ ] tag/article_tag 多对多；≤5 上限；uk 冲突幂等跳过

###### 6.2 标签池与闸门

- [ ] 前缀搜索已有标签池/热门 TOP N；新建过 DFA 闸门；use_count 异步刷新

------------

##### 板块七：推荐流

###### 7.1 zone=3 收录与缓存

- [ ] 仅 ADMIN/运营写；缓存三防（互斥重建/空值短缓存/TTL 抖动）

###### 7.2 展示前回查

- [ ] 渲染前回查 status=2 且 visibility=1，下架/转私密即时剔除

------------

##### 板块八：审核回写与联动消费

###### 8.1 result 回写消费

- [ ] 消费统一单队列 moderation.result（review v1.2 已废弃 review.decided 旧口径；自筛 bizType=1，事件体含 scene：发布场景=文本链路 / ARTICLE_IMG_RE_REVIEW=图片复审链路）回写状态 + 通知作者（对接 review 板块三 3-5 消费端）
- [ ] 消费 moderation.appeal.result（仅自筛 bizType=1，bizType=2 为评论属评论模块）：allow-list 对称恢复 3→2 / 4→2（挂起项 5-4.4 回收）

###### 8.2 封禁连带与图片复审

- [ ] user.banned 连带下架（发 content.unpublished）；图片复审聚合判定收口 review 消费端（content 仅提供 article_image.idx_file_id 反查契约，不重复实现）

------------

### 验证部分

#### content模块

##### V1 发布链路联调

- [ ] 发布 → review 送审 → result 回写全链路联调（依赖板块三/板块八，前置 litisle-ai chat 端点）

##### V2 可见性与缓存验证

- [ ] visibility 判定链、推荐流展示前回查验证；密码防爆破限频演练





-------------------------

## 暂不实现实现汇总

### 代码部分

#### content模块

------------

### 验证部分

#### content模块