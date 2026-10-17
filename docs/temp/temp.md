思考过程

思考

基于前两轮已修复的内容，我把剩余风险面归纳为 8 个检查方向 ，按优先级排序，你可以按批次推进：

## 检查方向清单
### 方向一：事件契约全量核对（优先级 ★★★）
范围 ：Content-Architeche.md §3.2 ↔ Content_Function_Develop.md §事件表 ↔ Review-Architecture.md §MQ

- 逐事件核对 6 条契约：`content.submitted / published / unpublished / moderation.result / appeal.result / image.submit`
- 核对点：①事件体字段逐项比对（review §157-158 已定义 submit/result 体，content 侧消费字段是否有遗漏如`reviewTaskId` 、`scene` ）；②消费方列表是否与 Design-Document §2.4 事件矩阵一致；③`image.submit` 的发布方归属——是 content 发还是 file 发，两文档口径是否统一（这是目前唯一未深查的发送方歧义）
### 方向二：状态机与 CAS 全景推演（优先级 ★★★）
范围 ：Content-Architeche.md §2.3 ↔ status.md 各板块 CAS 口径 ↔ Sql-Document article 表

- 把 8 个板块里 所有 CAS 语句汇总成一张流转表，逐条验证：是否覆盖`del_flag=0` 、是否有漏掉的合法流转、是否存在非法流转口子
- 特别推演边界序列：免审直落与提交并发、转公开(→1)与复审驳回(→4)并发、批量发布部分成功后重提、申诉改判 4→2 与封禁连带→4 并发
### 方向三：跨服务契约完整性（优先级 ★★☆）
范围 ：content ↔ social（visibility=3 回查）、↔ file（FileClient/占位图/对账）、↔ search / ai / notification（事件消费方就位情况）

- 逐条列出 content 依赖的 所有外部契约 ：关注关系回查、fileId 归属校验、unquarantine、ContentClient 正文回查、ContentUnpublishedInvalidator
- 核对：每个契约是否已在 Temporarily_not_implemented.md 标注挂起状态与触发条件；fallback 策略是否全部 fail-fast、有无静默放行漏网
### 方向四：缓存一致性闭环（优先级 ★★☆）
范围 ：last-hash 键、text/img-result 键、推荐流缓存、social 回查短 TTL 缓存

- 画一张「写入→失效」对照表：每类缓存键的写入时机与全部失效触发点是否 对称
- 特别检查：转私密发 unpublished → 失效 last-hash，那 转公开 呢？免审直落会 写 last-hash 相关数据吗（还是只在 review 侧写）？content 侧是否有任何路径写了 review 的缓存键却没在失效清单里
### 方向五：SQL DDL 与设计文档字段级比对（优先级 ★★☆）
范围 ：Sql-Document §二 8 表 ↔ Content-Architeche.md §2.2/§4 ↔ status.md 落位描述

- 逐表核对：设计文档提到的每个字段在 DDL 中存在且类型/注释一致（上轮只修了 content_hash 一列，其余 7 表未逐字段比对过）
- 核对唯一键设计与流程假设匹配：`uk(book_id,chapter_no)` 、`uk_user_book` 、`uk(article_id,tag_id)` 是否真支撑了对应板块的「冲突即拒绝/幂等跳过」逻辑
### 方向六：并发与幂等专项（优先级 ★☆☆）
范围 ：草稿乐观锁、批量发布、事件消费幂等、计数异步刷新

- 草稿：同版本重复提交幂等跳过 vs baseVersion 落后拒绝——两个分支的判定顺序是否明确（先比等再比落后）
- 事件消费：`affected=0 静默 ACK` 口径下，哪些场景会导致 合法事件被吞 （如事件乱序：REJECT 先于落库到达）——是否有补偿兜底
### 方向七：安全清单复核（优先级 ★☆☆）
范围 ：Content-Architeche.md §七 十条红线逐条验证

- 重点：sanitize 的实现层次（入库前净化 vs 出库时编码，两文档表述是否一致）、CRLF 防注入在 content 侧日志的覆盖、preview-check 不回吐命中词在返回体设计里是否真正落实
### 方向八：验证部分覆盖度（优先级 ★☆☆）
范围 ：status.md V1/V2 ↔ 各板块「验收」行

- 把 8 个板块的验收项逐条映射到 V1/V2 步骤，找出 有验收标准但无验证步骤 的孤儿项（如 appeal.result 3→2/4→2、封禁连带、DRQ 重投），必要时补 V3 批次