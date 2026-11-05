# 临时任务清单汇总

## 目录及文档说明

- 目录及文档说明
- 归档记录（已完成批次索引）
- 待办部分

### 文档说明:

- 本文档为临时任务清单（区别于 `docs/next/next_update.md` 正式待办），收纳临时立项、批次化改造类任务，与正式待办并行使用
- 临时清单为独立闭环：任务内容与落地摘要直接记录在本清单条目内，**不引用 status.md 细案**
- **不拆分「代码部分/验证部分」**：模块直接挂在「待办部分」下，代码任务与验证任务（V{n}）统一按 模块 → 批次/任务 组织
- 每阶段/批次完成后勾选 [x] 并补 ✅ 落地摘要；清单全部完成后按「归档与迁移」规则处理，文件是否删除/保留待用户确认（遵循文件删除需确认纪律）

### 文档模版

** temp.md 模版: **

```
# {清单主题}（临时任务清单）

> 方案：{关键方案决策，如：方案A（xx）+ xx 校验}
> 原则：{推进原则，如：xx 链路先行，xx 待确认后跟进；每阶段完成后勾选 [x]}

## 目录及文档说明

- 目录及文档说明
- 待办部分

### 文档说明:

- 临时清单不引用 status.md，任务细案直接写在条目内
- 不拆分代码/验证部分：验证项（V{n}）直接挂在对应模块下，与批次任务并列

--------------------------

## 待办部分

#### {模块名}模块

> 批次划分依据：{排查/设计文档来源}；推进顺序：批次一 → 二 → ...（有依赖的批次注明前置）

##### 批次{n}：{批次名称}

###### {批次n}.{m} {任务短标题}

- [ ] {任务内容一句话 + 关键口径/铁律 + 涉及的表/事件/常量}
  - ✅ {yyyy-MM-dd}：{落地摘要：核心类/方法 + 关键分支决策}；跨批次遗留：{遗留项（所属批次）}；编译验证已通过（{yyyy-MM-dd}）

------------

（每个批次之间用 ------------ 分隔线隔开）

##### V{n} {验证主题}

- [ ] {验证内容}（依赖{前置批次/模块}）
```

### 归档与迁移（批次完成后的处理规则）

**相关文档职责**：

| 文档 | 职责 |
| --- | --- |
| `docs/temp/temp.md`（本文档） | 仅收纳临时立项的活跃任务，按 模块 → 批次/任务（含验证项 V{n}）组织 |
| `docs/archive/` | 已完成批次的实现记录归档：按模块分子目录追加对应板块文件（新模块新建归档子目录 + 同名索引页），如 content-09-temp-checklist.md |
| `docs/next/temporarily/` | 挂起/预留项转移：代码类挂起进 temporarily-01-code.md，验证类挂起进 temporarily-02-verify.md，预留/暂不实现进 temporarily-04-deferred.md，每条必须附触发条件 |

**批次完成后的迁移动作（按顺序执行）**：

1. **实现记录归档**：将已完成批次的条目（含口径修正、注意事项）原样搬运至 `docs/archive/` 对应模块板块文件，不压缩关键细节。
2. **本文档回填**：已完成批次从本文档「待办部分」删除，并在顶部「归档记录」补一行批次索引（日期 + 归档去向 + 挂起项去向），保持本文档只留活跃任务。
3. **挂起项转移**：无法落地的事项移入 `docs/next/temporarily/` 对应文件并写明触发条件，供宿主就绪时迁回。
4. **预留项登记**：明确「暂不实现/暂不做」的事项（含触发条件与决策原因），移入 temporarily-04-deferred.md。

> 纪律：迁移时条目内容原样搬运；归档文档与本文档/temporarily 清单之间用相对路径互链，保证可回溯。

--------------------------


--------------------------

## 待办部分


#### search模块

> **search 模块内容已迁出（2026-11-03，用户确认）**：批次A/B/C 定版记录、批次D（D1 骨架摘要 + D2~D6 待办）与 V1~V3 验证项已原样迁入 [next_update.md](../next/next_update.md) search模块（重组为批次一~八 + V1~V3，user_idx 消费链路暂缓项迁入该文档「暂不实现汇总」）；本清单不再保留 search 条目，后续 search 活跃任务统一在 next_update.md 维护，勿在此重复登记。
> **（2026-11-04 补）**：搜索链路企业级优化为独立临时立项（性能补强 + 可靠性补强，非 next_update.md 既定批次一~八范围），仍登记于本清单，见下方「search模块（搜索链路企业级优化）」批次一~三。

------------

#### search模块（搜索链路企业级优化，临时立项）

> 批次划分依据：2026-11-04 搜索链路代码评审（三处代码点：ContentInternalServiceImpl 建索引回查与标签联想端点、UserFeignProviderController 注册事件发布）；方案选型贴合企业主流：thin event（事件只带定位键）+ 回查范式保留不动，联想走「最小输入长度 + Redis 短缓存 + 匿名限流」组合（标签入 ES completion 仅作触发式升级，不提前双写），索引同步补版本比较防乱序覆盖；推进顺序：批次一 → 二 → 三（相互独立，可并行）。

##### 批次一：搜索联想性能优化（推荐组合 L0 + L1 + L5）

> 已完成并归档（1.1~1.3），见 [search-11-temp-checklist.md](../archive/search/search-11-temp-checklist.md)（2026-11-05 迁出）。


###### 1.4 标签入 ES completion（L3，挂起不实施）

> —已迁出至 [temporarily-04-deferred.md](../next/temporarily/temporarily-04-deferred.md)（2026-11-05，与 next_update.md 9.2-c 合并登记）。


##### 批次二：索引同步可靠性补强（thin event 范式的企业级补强）

> 已完成并归档（2.1），见 [search-11-temp-checklist.md](../archive/search/search-11-temp-checklist.md)（2026-11-05 迁出）。


###### 2.2 索引批量回查端点（挂起不实施）

> —已迁出至 [temporarily-04-deferred.md](../next/temporarily/temporarily-04-deferred.md)（2026-11-05，与 next_update.md 9.2-d 合并登记）。


##### 批次三：user 事件发送时机纪律（登记，不改代码）

> 已完成并归档（3.1），见 [search-11-temp-checklist.md](../archive/search/search-11-temp-checklist.md)（2026-11-05 迁出）。


------------

##### V1 联想链路优化验证

- [ ] 验证批次一 L0/L1/L5 生效：同前缀重复请求命中 Redis 缓存（命中率日志）、空结果短 TTL 防穿透生效、超限 IP 请求被限流、TTL 抖动生效（依赖批次一）
  - 2026-11-04：代码侧已就绪（编译通过），运行态验证需启动 search/content/Redis 后联调执行，本次未做，保持未勾选

#### content模块

> 已完成并归档（阅读历史 bizType 枚举化确认 + ReadingHistoryServiceImpl 方法注释补充），见 [content-09-temp-checklist.md](../archive/content/content-09-temp-checklist.md)（2026-11-05 迁出）；枚举化方案与配套检查表保留在下方，不作任务条目。


------------

##### 阅读对象类型 bizType 枚举化：方案与配套检查表（2026-11-04）

**一、方案要点**

- 枚举命名 `ReadingTargetTypeEnum`（`BOOK(1, "书籍")` / `ARTICLE(2, "独立文章")`），禁止命名为 `BizTypeEnum`：content 模块内存在三套语义不同的 bizType —— 审核 `ModerationConstant.BIZ_TYPE_*`（1-文章 / 2-评论）、收藏 `FavoriteTargetTypeEnum`（1-文章 / 2-书籍）、阅读历史（1-书籍 / 2-独立文章），名字必须带 `Reading` 前缀防止误用。
- DB 不迁移：`reading_history.biz_type` 保持 tinyint，编码值与枚举 `code` 完全一致，无 DDL、无存量数据变更。
- 实体用枚举、对外 DTO/VO 保持 Integer：实体字段 `ReadingHistory.bizType` 用枚举（`@EnumValue` 映射 tinyint）；HTTP 请求 DTO / 响应 VO 继续用 Integer，原因是枚举上的 `@JsonValue(desc)` 会把 JSON 输出变成中文描述、请求侧枚举反序列化非法值会直接报错，破坏现有契约；请求侧统一由 Service 调 `resolve(Integer)` 做白名单校验。
- Mapper 注解 SQL 中的 1/2 一律拼接 `ContentBizConstant.READING_BIZ_TYPE_BOOK/ARTICLE` 编译期常量（注解值必须是常量表达式，不能写 `Enum#getCode()`）。
- 枚举提供 `static resolve(Integer)`：null 返回 null（表示“全部”），不在白名单返回 null，由 Service 抛 `PARAM_ERROR`。

**二、必须配套（代码，已随本改造落地）**

1. 新增编译期常量 `READING_BIZ_TYPE_BOOK=1`、`READING_BIZ_TYPE_ARTICLE=2` —— `litisle-content/src/main/java/com/litisle/litislecontent/constant/ContentBizConstant.java`（原因：注解 SQL 需要常量表达式；业务层禁止直接引用）。
2. 新增枚举 `ReadingTargetTypeEnum`（`@EnumValue int code` + `@JsonValue String desc` + `implements IntDictEnum` + `resolve`）—— `litisle-content/src/main/java/com/litisle/litislecontent/enums/ReadingTargetTypeEnum.java`（原因：实体映射与类型安全）。
3. 实体字段改枚举 `ReadingHistory.bizType` —— `litisle-content/src/main/java/com/litisle/litislecontent/domain/entity/ReadingHistory.java`（原因：MybatisEnumTypeHandler 按 `@EnumValue` 映射，依赖 `application.yml` 的 `default-enum-type-handler`，该配置不得删除）。
4. 服务层全链路改枚举 —— `litisle-content/src/main/java/com/litisle/litislecontent/service/impl/ReadingHistoryServiceImpl.java`：删除私有 `BIZ_TYPE_*` 常量；`selectHistory` / `upsertHistory` / `trimOverflowHistory` 入参改枚举；`pageMyHistory` 用 `resolve` 校验并把 `getCode()` 传给 Mapper；行过滤与分支判断改「枚举常量在左」（`ReadingTargetTypeEnum.BOOK == r.getBizType()`，防脏数据解析为 null 时误判）；VO 赋值取 `getCode()`；Lambda 查询与裁剪删除传 `getCode()`。
5. Mapper 注解 SQL 去硬编码 —— `litisle-content/src/main/java/com/litisle/litislecontent/mapper/ReadingHistoryMapper.java`：`selectMyHistoryPage` 共 4 处裸数字（book JOIN 1 处、article JOIN 1 处、可见性过滤 2 处）全部改常量拼接；`deleteOverflowOldest` 原本就是 `#{bizType}` 参数，无硬编码，无需改。
6. DTO/VO 类型保持不变、仅补注释指向枚举 —— `ReadingHistoryQueryDTO`、`ReadingHistoryItemVO`（原因：保持前端 JSON 契约；注释说明编码与 `ReadingTargetTypeEnum` 同源）。

**三、建议配套（文档/脚本/测试，按需单独执行）**

1. `docs/index/content-index.md` 第 119 行：`biz_type（1-书籍 2-文章）` 建议改为「2-独立文章」，与 `Sql-Document.md` / `Content-Architeche.md` 口径统一（纯文档措辞，不影响代码）。
2. `docs/Sql-Document.md` 第 519-521 行 与 `docker/init/mysql/content-schema.sql` 第 133-135 行：列注释当前已是「1-书籍 2-独立文章」，取值与枚举一致，**无需改**；可选补充「业务代码见 `ReadingTargetTypeEnum`」的说明（无 DDL 变更）。
3. `docs/Architecture/Content-Architeche.md` 第 216 行、`docs/temp/Content_Function_Develop.md` 第 54/183 行：口径已正确，可选在表说明后附「枚举见 `ReadingTargetTypeEnum`」，便于后续检索。
4. 脏数据校验（必须在上线前执行，仅校验、不自动修数据）：
   ```sql
   -- ① 枚举取值越界：存在则枚举解析失败/被当异常值处理
   SELECT biz_type, COUNT(*) AS cnt FROM reading_history WHERE biz_type NOT IN (1, 2) GROUP BY biz_type;
   -- ② 多态指向失效：biz_type=1 应能查到 book，biz_type=2 应能查到 article（含软删行也算存在）
   SELECT rh.id, rh.user_id, rh.biz_type, rh.biz_id
   FROM reading_history rh
   LEFT JOIN book b ON rh.biz_type = 1 AND b.id = rh.biz_id
   LEFT JOIN article a ON rh.biz_type = 2 AND a.id = rh.biz_id
   WHERE (rh.biz_type = 1 AND b.id IS NULL) OR (rh.biz_type = 2 AND a.id IS NULL);
   ```
5. 可选 CHECK 约束（MySQL 8.0.16+，**需单独变更窗口**，且必须先清理第 4 条校验出的脏数据）：
   ```sql
   ALTER TABLE reading_history ADD CONSTRAINT ck_reading_history_biz_type CHECK (biz_type IN (1, 2));
   ```
6. 测试：当前 `src/test` 下无 `ReadingHistory` 相关用例（已确认），改造后不强制补测；后续补测试体系时优先覆盖「全部/书籍/文章三种列表、非法 bizType=3 返回 PARAM_ERROR、超量裁剪保留最新 500 条、软删对象行过滤」。

**四、无需改动（已确认）**

1. 数据库迁移/初始化数据：无。编码值不变；`docker/init/mysql` 的 seed 脚本无 `reading_history` 数据。
2. Redis 缓存：无。阅读历史链路未使用任何 Redis 缓存键，无缓存值兼容问题。
3. MQ / Feign / 跨服务契约：无。阅读历史无事件、无跨服务 DTO（`litisle-api` 未承载该类型）。
4. 前端 / 其它服务：无。仓库内无前端代码；DTO/VO 仍为 Integer，HTTP JSON 不变，无需配合发版。
5. 其它模块：无。search/user/gateway 均未引用阅读历史 `bizType`。
6. 既有注释里的 `biz_type=1/2`（`ReadingHistoryService` 接口、Mapper/Entity/VO 注释、`BookReadSupport` 注释）：与枚举取值一致，保留不改（不删既有注释）。
7. 归档文档 `docs/archive/**`：历史记录，不回改。

**五、分批实施与验证/回滚**

- 批一（✅ 2026-11-04）：新增常量 + 枚举。验证：`mvn -q -pl litisle-content -am compile -DskipTests`。回滚点：删除两个新增文件/常量块（无引用时零影响）。
- 批二（✅ 2026-11-04）：实体 + Service + Mapper SQL + DTO/VO 注释。验证：同上编译已通过（exit 0）；运行态按下方回归点冒烟。回滚点：整体回退这 5 个文件改动，无 DB/缓存副作用。
- 批三（✅ 2026-11-04，已实施）：采用「code + name」双字段口径 —— `ReadingHistoryItemVO` 保留 `bizType` 为 Integer（前端逻辑判断用），新增 `bizTypeDesc`（展示用，取自枚举 `desc`，null 时给 null）；Service 仅补 `setBizTypeDesc(row.getBizType() == null ? null : row.getBizType().getDesc())`，其余逻辑未动；DTO、Mapper、DB 均未改。兼容性：新增字段属向后兼容，前端可忽略、无需改；`litisle-content` 编译通过（exit 0）。回滚点：删除 `bizTypeDesc` 字段及其两处赋值。

**六、回归功能点（批二上线前必跑）**

1. 阅读历史列表：`bizType=null` 全部混合时间线、`=1` 书籍、`=2` 独立文章三种筛选，倒序与分页 total 正确。
2. `bizType=3` 等非法值返回 `PARAM_ERROR`。
3. 书籍阅读页 `PUT /reading-history` 上报：仅前进不回退、跨书章节拒绝、限频生效。
4. 书籍详情续读 `GET /reading-history/{bookId}`：无历史/章节已删返回 null。
5. 文章详情命中自动落历史：0 挂载=文章历史、1 挂载=书籍历史、多挂载跳过。
6. 章节阅读页自动落历史：最后一次阅读语义、可回退旧章。
7. 超量裁剪：单用户单类型保留最新 500 条、单批封顶 100。
8. 对象软删：历史行静默跳过、total 不虚高；书籍行章节已删时书名与时间仍返回。
9. 前端跳转：按 `bizType` 1/2 区分书籍/文章详情路由不受影响。

**七、风险与不做事项**

- 风险：① 存量 `biz_type` 非 1/2 的脏数据会导致枚举解析异常或被当文章行静默跳过，上线前必须先跑校验 SQL；② 依赖 `mybatis-plus.configuration.default-enum-type-handler`，误删会退化为按枚举 name 入库；③ JSON 契约虽保持 Integer，但实体若被直接序列化会出现中文 desc（当前无此路径）；④ 注解 SQL 只能用编译期常量拼接，不能调用枚举方法。
- 不做：DB 迁移/改列类型；改 HTTP DTO/VO 类型；统一审核/收藏的 bizType；新增 Redis 缓存、MQ 事件、Feign 端点；批次三；加 CHECK 约束（单独窗口）；回收/修改归档文档。

--------------------------

#### search 模块：索引同步并发竞态决策记录（2026-11-05）

> 背景：批次九 9.7 将消费者并发从串行提升为 4/8 后，版本守卫出现「同一文章旧快照覆盖新文档」的竞态窗口。本轮完成风险评估并定稿处置方案，代码已落地。

**一、风险评估结论**

- 同队列竞态（并发引入）：版本守卫是「先 GET 当前 updateTime → 比较 → 写入」，GET 与写入不是原子操作。并发 >1 时同一 articleId 的多条事件可被不同线程同时处理，较旧快照可能在较新快照写完之后落地，造成索引文档回退（标题/正文/标签短时陈旧）。
- 跨队列竞态（并发无关）：发布/元数据事件与下架事件位于不同队列。即使串行消费，下架删除索引后仍可能有一条早已入队的发布事件被消费，把已下架文章重新写回索引。该问题属隐私红线（已下架/私密内容不得出现在检索结果），优先级高于吞吐。
- 影响面：串行时前者不存在，后者始终存在；4/8 配置把前者变成真实窗口，收益只是吞吐，而当前量级远未成为瓶颈。

**二、方案分层与决策**

| 层级 | 方案 | 处置 |
| --- | --- | --- |
| P0 | E：索引同步消费者并发回退为串行 1/1 | 立即实施 |
| P0 | D：文章下架 Redis 墓碑 + 写前校验 | 立即实施 |
| P2 | A：ES external version；B：update 脚本条件判断 | 条件触发，暂不实施 |
| 不采纳 | C：按 articleId 路由到同一消费者 | 不实施 |

- P0（E）并发回退：把 `spring.rabbitmq.listener.simple.concurrency` 与 `max-concurrency` 由 4/8 降回 1/1。理由：当前量级串行吞吐有数十倍余量；上一轮 4/8 属提前消费，换来一个真实存在的写覆盖窗口，不划算。企业/社区主流在小体量索引同步任务上也默认串行消费，等积压成为真瓶颈再横向扩。恢复条件（引用下方 P2 触发条件）：积压持续 >5 分钟或峰值持续 >50 条/秒，且确认串行成为瓶颈，先落地 P2 后再提高该值。
- P0（D）下架墓碑：下架事件删除索引后写 Redis 墓碑 `search:index:tombstone:article:{id}`，TTL 10 分钟；发布（整篇 upsert）与元数据 partial update 写前先查墓碑，命中则重新回查门控。理由：这是与并发无关的跨队列竞态，串行也挡不住，且属隐私红线，优先级高于吞吐优化。
  - TTL 取值依据：消费端最多 5 次延迟重投 × 30 秒退避 ≈ 150 秒，再加处理与网络余量；10 分钟可覆盖最大重投窗口，同时避免墓碑长期残留。
  - 生效范围：只对文章生效；书籍/用户没有「下架」语义，删除即最终态。
  - 失败语义：写/查失败抛可恢复异常交 MQ 延迟重投，不静默吞掉；查询失败宁可重试也不放行（防隐私泄露）。
- P2（A+B）条件项：ES external version + update 脚本条件判断，暂不实施。二者必须配合——ES Update API 不支持 external version，只上 A 会漏掉元数据 partial update 路径，故 B（update 脚本内嵌 updateTime 条件）为 A 的必要补充，并需统一处理 409 `version_conflict_engine_exception`（旧事件跳过并 ack）。触发条件：积压持续 >5 分钟或峰值持续 >50 条/秒，且确认串行成为瓶颈后再做。
- 不采纳（C）按 id 路由同一消费者：标准 RabbitMQ 不保证 key 顺序，需一致性哈希插件或一 id 一队列，跨模块代价过高。
- 待拍板（本轮不实施）：`updateTime` 精度。当前秒级，同一秒内两次编辑会被版本守卫判为「旧」，建议后续补毫秒或引入单调递增版本号。

**三、实施落点**

- 并发：`litisle-search/src/main/resources/application.yml` → `concurrency: 1`、`max-concurrency: 1`，保留原注释并追加回退说明与恢复条件。
- 墓碑键：`SearchBizConstant.ARTICLE_TOMBSTONE_KEY_PREFIX`（`search:index:tombstone:article:`）。
- 墓碑 TTL：`SearchProperties.Index.tombstoneTtl`（默认 `Duration.ofMinutes(10)`），yml `litisle.search.index.tombstone-ttl: 10m`。
- 新增 `ArticleTombstoneService` / `ArticleTombstoneServiceImpl`：`markUnpublished(articleId)`、`exists(articleId)`，写/查失败记 error 日志并抛 `IllegalStateException`。
- `ArticleIndexServiceImpl`：`handleUnpublished` 删除索引成功后、删切片前写墓碑；`handlePublished` / `handleArticleUpdated` 在门控之后、版本比较之前查墓碑，命中则重新回查：非公开跳过（元数据路径走下架清理），仍公开则改用最新快照继续并留痕。

**四、对既有语义的影响**

- 手动 ACK、幂等 upsert、5 次延迟重投、DLQ 语义均不变。
- 新增一次 Redis 读（发布/元数据）与一次 Redis 写（下架）；Redis 故障时按可恢复异常重投，不静默吞消息。
- 墓碑命中不改变既有门控口径，只是把「跨队列旧发布事件」重新拉回门控流程，避免误伤合法的二次发布。
