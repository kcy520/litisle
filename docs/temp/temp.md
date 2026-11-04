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

###### 1.1 联想最小输入长度门控（L0）

- [x] SuggestServiceImpl#suggest 增加最小输入长度校验：q 长度小于阈值直接返回空列表（当前仅校验空串与 >64 超长）；联想为高频击键场景，最小长度可在最前端砍掉大量单字符穿透请求，成本最低收益最大
  - ✅ 2026-11-04：`SearchBizConstant` 新增 `SUGGEST_MIN_KEYWORD_LENGTH=2`；`SuggestServiceImpl#suggest` 归一化后按「空串 / 少于 2 字 / 超过 64 字」统一返回空列表（联想不做报错，保持静默降级语义）
  - 涉及：litisle-search SuggestServiceImpl；长度阈值走 SearchBizConstant 常量（禁硬编码），风格对齐 content 侧 TAG_SUGGEST_KEYWORD_MAX_LENGTH

###### 1.2 标签联想 Redis 短缓存（L1，推荐主方案）

- [x] TagServiceImpl#searchByPrefix 增加 Redis 短缓存：命中 TTL 5~10min 带 ±10% 抖动（防同批 key 同时过期雪崩），空结果短 TTL 60s（防缓存穿透）；写法对齐同文件 hotTags 既有缓存范式（CacheConstant 新增前缀常量 + RedisUtil）
  - ✅ 2026-11-04：`CacheConstant` 新增 `CONTENT_TAG_SUGGEST="content:tag:suggest:"`；`ContentLimitConstant` 新增基准 TTL 480s（±10% 抖动后落在 8±0.8 分钟）与空结果 TTL 60s；`TagServiceImpl#searchByPrefix` 按「规范化前缀」读写 TagVO 列表 JSON，空结果走 60s 短缓存，非空走 `jitteredSuggestTtl()`；入库条件与排序不变（NORMAL + 未删 + use_count desc），只读聚合不主动失效
  - 涉及：litisle-content TagServiceImpl、commonredis CacheConstant
  - 口径：标签池变更（新建/封禁/删除）不主动失效缓存，靠短 TTL 自然过期收敛（联想场景允许分钟级陈旧）；search 侧 SuggestServiceImpl#collectTags 暂不加缓存（Feign 结果已随 content 侧缓存受益，避免双层缓存口径复杂化）

###### 1.3 /search/suggest 匿名限流（L5）

- [x] SearchController#suggest 补 @RateLimiter：IP 维度（建议 20 次/60s，阈值常量化），对齐 content TagController /tags 的 USER 维度限流风格；suggest 为匿名接口且无 IP 限流，可被脚本刷穿直连 MySQL（经 L1 缓存后 DB 压力已大幅缓解，限流为兜底防线）
  - ✅ 2026-11-04：`SearchBizConstant` 新增 `SUGGEST_RATE_WINDOW_SECONDS=60`、`SUGGEST_IP_MAX_COUNT=20`；`SearchController#suggest` 增加 `@RateLimiter(keyType=IP)`，限流按 IP 维度，超限走 common-redis 统一拒绝口径
  - 涉及：litisle-search SearchController

###### 1.4 标签入 ES completion（L3，挂起不实施）

- [ ] 挂起项（登记触发条件，当前不做）：将 content 标签池同步至 ES completion 字段，suggest 一次 ES 请求拿齐书名/作者/标签三源并消掉 Feign 跨服务调用（大厂主流终态）。触发条件：标签总数 > 5万，或 suggest QPS > 200，或联想 P99 > 100ms；触发后迁回实施（涉及标签双写一致性，需先专项设计同步机制）

------------

##### 批次二：索引同步可靠性补强（thin event 范式的企业级补强）

###### 2.1 索引写入版本比较（防乱序覆盖）

- [x] ArticleIndexServiceImpl 写入前增加版本比较：回查 DTO 已带 publishTime（=上游 updateTime），写入前读 ES 现有文档 updateTime，小于等于现值则跳过写入；消除「发布/更新事件乱序到达 → 旧快照覆盖新快照」的最终一致窗口（现状为按 articleId 无条件 upsert 整篇覆盖）
  - ✅ 2026-11-04：新增 `IndexVersionGuard`（只按主键读 `updateTime` 的 sourceIncludes 查询）：现有版本 >= 新快照（含相等，幂等跳过）→ 跳过写入；文档不存在/任一侧 updateTime 缺失 → 照常写入（不漏同步）；读 ES 失败抛可恢复异常交 MQ 重试
  - ✅ 2026-11-04：`ArticleIndexServiceImpl` 发布路径（跳过时同步跳过切片重建）与元数据 partial update 路径均接入；`BookIndexServiceImpl`、`UserIndexServiceImpl` 按同口径跟进（清单要求的 book_idx/user_idx 一致性）
  - 涉及：litisle-search ArticleIndexServiceImpl（article_idx 优先落地，book_idx/user_idx 同口径跟进）
  - 备选方案（暂不采用）：同一 articleId 事件固定队列 + 单消费者串行消费——约束更强但牺牲吞吐，且多队列扩容时失效

###### 2.2 索引批量回查端点（挂起不实施）

- [ ] 挂起项（登记触发条件，当前不做）：ContentClient 新增 getArticleIndexDocs(List<Long>) 批量回查，将消费端 N 次逐条 RPC 压成 1 次（当前一次消息一次回查）。触发条件：索引同步消息量级上来（如日均消费 > 1万条）后实施

------------

##### 批次三：user 事件发送时机纪律（登记，不改代码）

###### 3.1 注册/资料更新事件发送时机纪律

- [x] 纪律登记（本条即为登记动作本身，不产生代码改动）：UserFeignProviderController#createUser 当前无 @Transactional，「先落库后发事件」成立，现有实现正确；若将来为 createUser 加事务，必须改为 afterCommit 发送（参考 litisle-content BookServiceImpl#publishBookChangedAfterCommit 写法），否则出现「事件已发、事务回滚」的脏事件导致 user_idx 脏数据
  - ✅ 2026-11-04：已登记入清单；触发时机（createUser 引入事务时）由 code review 按本条执行
  - 涉及：litisle-user UserFeignProviderController

------------

##### V1 联想链路优化验证

- [ ] 验证批次一 L0/L1/L5 生效：同前缀重复请求命中 Redis 缓存（命中率日志）、空结果短 TTL 防穿透生效、超限 IP 请求被限流、TTL 抖动生效（依赖批次一）
  - 2026-11-04：代码侧已就绪（编译通过），运行态验证需启动 search/content/Redis 后联调执行，本次未做，保持未勾选

#### content模块

阅读历史实体类：阅读对象类型：是否应该替换为枚举？
> 待确认（2026-11-04）：属架构/契约级调整，会牵动 MyBatis 枚举映射（IEnum/@EnumValue）、DTO/VO 的 bizType 字段类型与前端契约、存量 DB 的 int 列兼容，未确认前不擅自改。建议保留 int + 常量（现状）或单独立项统一枚举化。
> ✅ 2026-11-04 已确认并实施批次一 + 批次二：新增 `ReadingTargetTypeEnum` 与 `ContentBizConstant.READING_BIZ_TYPE_*`；`ReadingHistory.bizType` 改枚举；Service 全链路改用枚举（resolve 校验/枚举常量在左/getCode 传参）；Mapper 注解 SQL 的裸数字改常量拼接；DTO/VO 保持 Integer 仅补注释；`litisle-content` 编译通过。批次三已于 2026-11-04 实施（口径：code + name 双字段）：VO 新增 `bizTypeDesc`、`bizType` 仍为 Integer，前端无需改。
ReadingHistoryServiceImpl：补充方法体上的注释，缺少方法作用描述、参数说明
> ✅ 2026-11-04：已为 report / getLastChapter / getLastRead / pageMyHistory / recordFromArticle / recordBookRead 六个 public 方法补充方法级 Javadoc（作用概括 + 参数 + 返回值），原有方法体内流程注释保留未删。

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
