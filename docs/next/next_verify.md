# 验证、测试任务清单

## 目录及文档说明

- 目录及文档说明
- 待验证部分
- 待办与决策（非验证）：见 [next_update.md](next_update.md)
- 已知约束与风险（非待办）：见 [constraints-index.md](../constraints/constraints-index.md)

### 文档说明:

> **定位**：本文件只收纳两类任务——①代码/配置已改完、等真实环境验证；②必须实测（起服务、并发、真实流量、动库窗口、中间件就绪）才能定论。
> **不进本文件**：纯代码改造、待决策、待外部条件（这类仍留在 [next_update.md](next_update.md)；边界判断不准的也留在那边并注明「验收方式待定」）。
> **状态口径**：`- [ ]` 待验证；`- [~]` 部分验证通过、仍有未覆盖分支；`- [x]` 实测通过（验完后按 [归档规则](next_update.md) 迁入 `docs/archive/`，不在本文件长期堆积）。
> **唯一副本**：同一条任务只在一个文件里出现；本文件条目于 2026-11-08 自 `next_update.md` 迁入（2026-11-09 追加迁入 9.6-A、14.1、17.5），**编号不重排**（沿用原模块编号 / 9.x / 10.x / 14.x / 15.x / 17.x 等）。
> **互链**：活跃待办见 [next_update.md](next_update.md)；已完成归档见 [docs/archive/](../archive/) 下各模块索引页。

### 与 next_update.md 的分工

| 文件 | 收纳内容 |
| --- | --- |
| `next_update.md` | 待办、代码改造、待决策、待外部条件、风险登记 |
| 本文件（`next_verify.md`） | 运行态验证、环境实测、联调、动库窗口、中间件就绪、测试补齐类任务 |

--------------------------

## 待验证部分

#### content模块

> 板块划分依据：Content_Function_Develop.md / Content-Architecture.md。本模块只保留需实测的验证项；代码任务与待决策项见 next_update.md。

##### V1 发布链路联调（原编号：content V1 / 9.1）

- [ ] 【必须】content V1：发布 → review 送审 → result 回写全链路联调（来源：content V1；前置：litisle-ai chat 端点、review 消费链路；验收：一篇草稿从发布到审核终态，文章状态、索引文档与前端展示一致）

##### V2 可见性与缓存验证（原编号：content V2 / 9.1）

- [ ] 【必须】content V2：visibility 判定链、推荐流展示前回查验证 + 密码防爆破限频演练（来源：content V2；前置：content/review 联调环境；验收：PUBLIC/PRIVATE/FOLLOWERS 三档可见性正确，连续错误密码触发限频）

##### 10.7 验证 V4：文章流无限刷（批次十）

- [ ] 【必须】文章流端到端验证（来源：10.3/10.5/10.6；前置：联调环境（content/Redis/MySQL 可用）；验收：连续 15 页不重不漏、同作者/同分类打散生效、私密与下架与未过审内容 0 命中、匿名与登录序列不同、快照过期续刷正常、频控按维度触发）
  - **待环境验证（2026-11-06，情况 B，未实测、不勾选）**：本机探测 MySQL84 服务 Stopped、Redis 6379/6380 未监听、content 8085 与 Nacos 8848 未启动。恢复环境后按下列命令逐条执行：
    - **执行状态（2026-11-08 补记）**：上次「推荐流端到端验证 10.7/10.8」会话被取消、无结论留存，需重跑；当前仍未实测。
    - **已拍板（2026-11-08｜延后）**：与下次环境验证合并执行，不单独排期；上次会话 canceled、无结论，仍需重跑，不能视为已验证。
    - 前置：`Start-Service MySQL84,Redis`；启动 content（IDE 或 `mvn -pl litisle-content -am spring-boot:run`）；`Test-NetConnection 127.0.0.1 -Port 8085` 应为 True
    - 连续 15 页不重不漏：`$h=@{"X-Anon-Id"=[guid]::NewGuid().ToString()};$c=$null;$s=$null;$ids=@(); 1..15|%{$u="http://127.0.0.1:8085/api/content/feed/articles?pageSize=20"+$(if($c){"&cursor=$c"})+$(if($s){"&snapshotId=$s"});$r=Invoke-RestMethod $u -Headers $h;$ids+=$r.data.items.articleId;$c=$r.data.cursor;$s=$r.data.snapshotId}`；判定：`$ids` 无重复、每页 20 条、cursor 单调递增；池 300 耗尽（第 16 页）后 snapshotId 递增且 reset=false
    - 打散：首屏相邻 authorId 连续 ≤1、category 连续 ≤2；两个 zone 近似 1:1 交替
    - 门控 0 命中：取库内已有 PRIVATE/下架/未过审样本 ID（无样本则该条记「环境样本缺失」），断言不出现在 items
    - 匿名与登录序列不同：不同 `X-Anon-Id` 与登录 token（请求头 `satoken`）各取首屏，ID 集合不同
    - 快照过期续刷：`redis-cli -p 6380 -a 123 --no-auth-warning DEL "content:feed:snapshot:<scope>:<snapshotId>"` 后携旧参数请求，断言 reset=true 且返回新 snapshotId（无 redis-cli 时等待 30 分钟自然过期）
    - 频控：同一 IP 60s 内连发 21 次文章流请求（匿名阈值 20），第 21 次 body.code=429（或 HTTP 429）

##### 10.8 验证 V5：书籍流与降级（批次十）

- [ ] 【必须】书籍流端到端验证（来源：10.4/10.6；前置：10.7 通过；验收：书籍门控正确（下架/软删 0 命中）、收藏与阅读人数排序与 SQL 聚合一致、新书保底曝光生效、书籍流不依赖 zone、停 Redis 后仍可刷、匿名频控触发）
  - **待环境验证（2026-11-06，情况 B，未实测、不勾选）**：环境同上不可用。恢复后执行：
    - **执行状态（2026-11-08 补记）**：上次「推荐流端到端验证 10.7/10.8」会话被取消、无结论留存，需重跑；当前仍未实测。
    - **已拍板（2026-11-08｜延后）**：与下次环境验证合并执行，不单独排期；上次会话 canceled、无结论，仍需重跑，不能视为已验证。
    - 连续翻页：同 10.7 循环，端点改 `/api/content/feed/books`，断言 `items.id` 无重复、每页 20 条、cursor 递增
    - 门控 0 命中：断言 items 中无 `status=2`（下架）/`del_flag=1` 书籍；抽查 `SELECT id,status,del_flag FROM book WHERE id IN (<首页ID>)`
    - 计数与 SQL 一致：`SELECT book_id,COUNT(*) FROM favorite WHERE target_type=2 AND book_id IN (<首页ID>) GROUP BY book_id` 对照返回收藏数；`SELECT biz_id,COUNT(DISTINCT user_id) FROM reading_history WHERE biz_type=1 AND biz_id IN (<首页ID>) GROUP BY biz_id` 对照阅读人数
    - 新书保底：7 天内新建书籍在首屏出现条数 ≥ `new-book-quota`(6)；池内新书不足 6 条时按实际条数核
    - 不依赖 zone：`/feed/books` 无 zone 语义，带 `zoneCode=1/2` 传参结果应一致
    - 停 Redis 仍可刷：`Stop-Service Redis` 后请求，断言 items 非空、hasMore=false、degraded ∈ {last-good, redis-down}，日志出现「推荐流降级」WARN；随后 `Start-Service Redis` 恢复
    - 匿名频控：20/60s 阈值，第 21 次 body.code=429

##### 15.2-A 客户端 IP 解析口径回归（批次十五，部分验证）

- [~] 【P1｜15.2-A】统一 content 内两份 IP 解析口径
  - **现状（口径不同）**：
    - [ClientIpSupport.java:21-28](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/ClientIpSupport.java#L21-L28)：`X-Forwarded-For` 首段 → `remoteAddr`，**不读 `X-Real-IP`**，取不到可能返回空串；调用点 [ArticleController.java:170](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/ArticleController.java#L170)（文章浏览去重）、[BookController.java:229](../../litisle-content/src/main/java/com/litisle/litislecontent/controller/BookController.java#L229)（章节浏览去重）。
    - [FeedRateLimitSupport.java:80-95](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedRateLimitSupport.java#L80-L95)：`X-Forwarded-For` 首段 → `X-Real-IP` → `remoteAddr` → `"unknown"`；用于匿名推荐流限流键（[同文件:70](../../litisle-content/src/main/java/com/litisle/litislecontent/service/support/FeedRateLimitSupport.java#L70)）。
  - **统一口径（必须一致）**：`X-Forwarded-For` 首段 → `X-Real-IP` → `remoteAddr` → `unknown`。这是 `FeedRateLimitSupport`、common-redis `RateLimiterKeyBuilder`、auth `IpUtil`、`IdempotentAspect` 已有的口径；统一到它等于把 `ClientIpSupport` 补齐，而不是悄悄改匿名限流键的算法。
  - **改法**：`ClientIpSupport` 改为完整四级回退（并复用 `HeaderConstant.X_FORWARDED_FOR` / `X_REAL_IP` 代替字面头名）；`FeedRateLimitSupport` 的私有方法与 `ClientIpSupport` 合并为一份，二者调用同一组件；`FeedRateLimitSupport` 不再自带解析副本。
  - **行为影响**：`ClientIpSupport` 的调用点从「不读 X-Real-IP、可能空串」变为「读 X-Real-IP、兜底 unknown」，匿名浏览去重键会更稳定；匿名推荐流限流键口径不变。
  - **动哪些文件 / 代价**：2 个类（`ClientIpSupport`、`FeedRateLimitSupport`）+ 调用点无需改签名；无 DDL、无配置。
  - **验收**：同一请求分别经浏览与限流两条路径解析出的 IP 相同；构造仅有 `X-Real-IP`、仅有 `remoteAddr`、四者全缺三种请求，分别断言取到 `X-Real-IP`、`remoteAddr`、`unknown`。
  - **已拍板（2026-11-08｜第二批·必修，需回归）**：统一口径为 **XFF → X-Real-IP → remoteAddr → unknown**，这是硬性要求；不得改成其他顺序或省略 `X-Real-IP`，否则等于静默改变匿名限流行为。回归文章详情、章节阅读、匿名刷流三种身份。
  - ⏳ 2026-11-08（代码已改 + 限流/去重冒烟通过；`unknown` 用例不可构造）：`ClientIpSupport` 已统一为四级回退；`FeedRateLimitSupport` 删除自带解析、改注入 `ClientIpSupport`；编译通过。实测（content 8085 + Redis 6380）：①XFF=10.8.8.8 连发 21 次匿名推荐流，前 20 次 `code=0`、第 21 次 `code=10005`，限流键 `rate_limit:feed:ip:10.8.8.8:stream`=21；②仅带 `X-Real-IP=10.7.7.7` 的请求，限流键落在 10.7.7.7（统一组件生效）；③文章详情分别仅带 `X-Real-IP=10.5.5.5` 与四头全不带（`remoteAddr=127.0.0.1`），均生成对应 `content:article:view:dedup:5001:a<hash>` 键。未验证：四头全缺的 `UNKNOWN_IP` 分支在真实 HTTP 下无法构造（`remoteAddr` 恒有值），仅代码评审确认，故不标 `[x]`。

-------------------------

#### search模块

> 板块划分依据：[Search-Architecture.md](../Architecture/Search-Architecture.md) §九 + [Search-Business_Guide.md](../Study/Search-Business_Guide.md) §四缺口清单（G1~G8）。本模块收纳需实测/环境就绪的验证项与测试执行项（9.6-A 于 2026-11-09 自 next_update.md 迁入）；待决策项见 next_update.md。

##### V1 索引同步与可见性红线验证（原编号：search V1 / 9.1）

- [ ] 【必须】search V1：索引同步与可见性红线验证（来源：批次二/三/五 + search V1；前置：content/review/search 联调环境；验收：四条下架路径删净 article_idx 与 article_chunks、published 迟到不写回、未过审/私密永不入索引、元数据编辑同步生效、heat 累加→定时 bulk 链路生效且重复消费不重复累加）

##### V2 检索功能验证（原编号：search V2 / 9.1）

- [ ] 【必须】search V2：检索功能验证（来源：批次四/五/六/七 + search V2；前置：批次四~七代码已编译；验收：tag/category/zone/时间范围组合生效，空结果 source=fallback 且不写缓存，联想去重合并，搜索历史去重/上限懒删且 keyword 不出现在任何 ES 文档，KNN 结果无私密/下架内容）

##### V3 降级验证（原编号：search V3 / 9.1）

- [ ] 【必须】search V3：ES 降级实测（来源：批次八 8.1 + search V3；前置：content 热门端点可用；验收：停 ES 后 ARTICLE 返回 content 热门且 source=DEGRADED，BOOK/USER 空页+DEGRADED，`fallback.enabled=false` 或热门获取失败返回 SEARCH_ES_UNAVAILABLE）

##### 向量与书籍索引存量重建（批次九 9.1）

- [ ] 【必须】向量存量重建（来源：批次一 1.1 跨板块遗留 + 批次七 7.1 重建框架；前置：Embedding 配置可用、管理端点可登录调用；验收：存量 article_idx `vec_dense` 与 article_chunks `chunk_vector` 非空且 1024 维，两索引 `_meta.embedding_model` 更新为真实模型名）
- [ ] 【必须】book_idx authorId 存量回填（来源：批次五 5.2 明确「存量文档不回填」；前置：书籍事件重放或全量重建；验收：检索结果 `BookDocVO.authorId` 不再为 null，抽查与全量统计均通过）

##### 9.4 部署与配置就绪（必须）

- [ ] 【必须】联调环境与中间件就绪（来源：批次一~八运行前置；前置：ES 8.18、Redis、RabbitMQ、MySQL、Nacos 与 content/review/user/search 服务可启动；验收：search 启动无致命错误，四索引创建成功，MQ 队列/绑定声明无冲突）
- [ ] 【必须】Embedding 配置与连通性（来源：批次七 7.1；前置：`LITISLE_EMBEDDING_API_KEY` 注入、base-url/model=bge-m3/dimension=1024 配置完成；验收：`VectorService#available()` 为 true，单条与批量调用返回 1024 维向量）

##### 9.5 前端 DEGRADED 提示联调（必须）

- [ ] 【必须】前端 DEGRADED 提示联调（来源：批次八 8.1「前端提示搜索不可用」；证据：`SearchServiceImpl.java:792-798` 已返回 `source=DEGRADED`，仓库内无前端代码承接；前置：search V3 通过；验收：停 ES 时前端展示「搜索暂不可用」，BOOK/USER 空页不误报；降级固定第一页、total=当页条数口径经前端确认）

##### 9.8-P2 时间列精度（需动库窗口）

- [~] 【P2｜该修｜9.8-P2】四张表的时间列升级为 `datetime(3)`
  - **范围**：`article.update_time`（[content-schema.sql:43](../../docker/init/mysql/schema/content-schema.sql#L43)）、`book.update_time`（[content-schema.sql:110](../../docker/init/mysql/schema/content-schema.sql#L110)）、`sys_user.update_time`（[user-schema.sql:60](../../docker/init/mysql/schema/user-schema.sql#L60)）、`sys_user_profile.update_time`（[user-schema.sql:134](../../docker/init/mysql/schema/user-schema.sql#L134)）。
  - **改法**：`ALTER TABLE ... MODIFY COLUMN update_time datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)`；同步修改初始化 schema，保持全新库一致。**代码零改动**：守卫比较逻辑不用变、ES mapping 已支持毫秒、DTO 已是 `LocalDateTime`。
  - **代价 / DDL**：需要一次 DDL 窗口（四张表），存量行的毫秒位补 `.000`，无需回填；有 DDL、无代码改动。
  - **验收**：`SHOW COLUMNS` 四列均为 `datetime(3)`；同一秒内连续两次编辑，第二次索引不被守卫跳过；跨秒/乱序仍按现有规则跳过旧快照。
  - **已拍板（2026-11-08｜第三批·需 DDL）**：攒到下次动库时一起执行，本轮不改库。
  - ⏳ 2026-11-08（只改初始化脚本，未执行 DDL）：`content-schema.sql` 的 `article.update_time`（L43）、`book.update_time`（L110）与 `user-schema.sql` 的 `sys_user.update_time`（L60）、`sys_user_profile.update_time`（L134）均已改为 `datetime(3) ... CURRENT_TIMESTAMP(3)`；运行库仍是秒精度，`SHOW COLUMNS` 验收待动库窗口执行。

##### 9.6-A search 关键路径自动化测试（Testcontainers，批次九）

- [ ] 【可选｜9.6-A】search 关键路径自动化测试（来源：批次二\~八均仅编译验证）
  - **迁移说明（2026-11-09）**：自 next_update.md 迁入；集成测试的执行需 Docker 与 ES 容器就绪（环境实测类），测试代码实现与跑通不可分，故整体迁入本文件。
  - **方案结论（2026-11-08）**：选 **Testcontainers**，不用嵌入式 ES。理由：ES 8.x 无官方进程内支持；社区 `embedded-elasticsearch` 停在 7.x，与 `elasticsearch-java 8.18.8` 不兼容；项目自定义 IK 镜像的分词与 KNN 行为无法被嵌入式方案代表。
  - **容器边界**：只让 ES 走真容器（项目镜像 `elasticsearch:8.18.8-ik`、单节点、关闭安全认证）；`ContentClient`、`UserClient`、Redis 全部用 Mockito 替身，不引入额外容器。
  - **前置**：先执行 `docker build -t elasticsearch:8.18.8-ik docker/init/elasticsearch`（[compose.yaml:265-269](../../compose.yaml#L265-L269) 已使用同一镜像名）；测试运行机需 Docker 可用。
  - **隔离要求**：测试使用本次运行唯一的索引名（如 `article_idx_it_<uuid>`），用例结束在 `finally` 中删除；不得读写本地固定的 `article_idx` / `book_idx` / `user_idx`，避免污染开发数据。
  - **动哪些文件**：`litisle-search/pom.xml`（新增 `org.testcontainers:elasticsearch` 等测试依赖，版本按 Spring Boot BOM 或显式锁定）、`litisle-search/src/test/` 下新增集成测试与容器/索引清理夹具；不改业务代码。
  - **代价 / DDL**：新增测试依赖与容器/索引清理夹具，CI 需具备 Docker 能力；无 DDL、不改业务代码。
  - **验收**：检索参数组合、兜底不缓存、降级开关、KNN 强制 PUBLIC、索引版本守卫（含同秒相等跳过）至少各有 1 条集成用例通过；无 Docker 时用例明确 skip，而不是失败。
  - **已拍板（2026-11-08｜第四批·大工程）**：单独排期，不与本轮小改动混做。

-------------------------

#### auth/file 模块（批次十四：基础设施与安全加固）

> 板块划分依据：批次十四（批次十三 Resilience4j 熔断的配套遗留；14.1 于 2026-11-09 自 next_update.md 迁入，横跨 litisle-auth 与 litisle-file 两个服务，单独立项）。本节收纳评估与实施需环境实测（Redis 故障演练）的任务；评估结论落定前保持 `- [ ]`。

##### 14.1 验证码与文件上传纳入熔断覆盖评估（待评估）

- [ ] 【评估｜P1】14.1 验证码与文件上传纳入 breaker：按资源名拆分熔断实例（`captcha` / `file-upload`），覆盖真正的 Redis 调用点，而不是继续只包限流
  - **迁移说明（2026-11-09）**：自 next_update.md 迁入（2026-11-08 已拍板为第四批·大工程，与 9.6-A 同批）；评估结论需 Redis 故障实测支撑，验收口径（OPEN/半开/CLOSED 演练）属环境实测，故迁入本文件。
  - > **为什么单独立项**：批次十三的 breaker 只包住 `RedisRateLimiter` 的限流调用；验证码与文件上传是「在线必经 + 无本地兜底」，不属于限流降级，混进其他小节会掩盖真实风险面。本项只做评估，不在本轮实施。
  - > **判断依据**：全仓 38 个直连 Redis 的业务类中，[CaptchaServiceImpl.java](../../litisle-auth/src/main/java/com/litisle/litisleauth/service/impl/CaptchaServiceImpl.java)（验证码）与 [FileUploadServiceImpl.java](../../litisle-file/src/main/java/com/litisle/litislefile/service/impl/FileUploadServiceImpl.java)（文件上传）直接依赖 Redis 完成在线必经步骤，且没有本地兜底；Redis 故障时不是「降级限流」，而是功能不可用。
  - 代价：约 1 天；需要按资源名建多个 breaker 实例（避免一个资源拖垮另一个），并补「打开后的业务返回」与「恢复探测」用例；不改 DDL、不新增中间件。
  - 验收：Redis 故障时两条链路进入 OPEN 并按既定策略返回可见错误（或本地兜底），Redis 恢复后自动半开探测回 CLOSED；限流 breaker 与业务 breaker 指标可区分。
  - 边界：file 模块另有 Sentinel `@SentinelResource`（加载情况见 constraints R8），本项不替代 Sentinel，只评估是否需要用统一的 Resilience4j 口径覆盖。
  - **已拍板（2026-11-08｜第四批·大工程）**：单独立项、单独排期，不与本轮小改动混做。

-------------------------

#### 跨模块验证数据清理（批次十七）

> 板块划分依据：批次十七（分布式锁工具与消息中间件凭证收尾）；本节收纳验证过程中产生的残留数据清理，横跨 litisle_review / litisle_content / litisle_file 三库，需动库窗口并经用户确认后执行。

##### 17.5 测试数据清理（需动库窗口，待用户确认）

- [ ] 【数据清理｜17.5】清理本轮验证残留数据（**只登记 SQL，未执行**）
  - **迁移说明（2026-11-09）**：自 next_update.md 迁入；清理 SQL 需在动库窗口执行并经用户确认，属动库窗口类任务。
  - **现状**：本轮验证在库中仍留有测试数据——`litisle_review.punish_log` id=2/3/4、`litisle_review.review_task` id=2/3、`litisle_review.moderation_record` id=2、`litisle_content.article_image` id=1、`litisle_file.file_ref`（`file_id='25fd4ac95b6248ec995dd5dd4205d326'`、`biz_type='article'`、`biz_id=5008`）。
  - **为什么做**：避免验证数据污染审核列表、处罚记录与文件引用对账口径。
  - **待确认点**：①执行窗口与是否需要先备份；②`litisle_file.file_meta` 对应记录的 `safe_flag` 原值需确认（作为回滚参照）；③`litisle_content.article` id=5008 已回到原值 4，**无需 UPDATE**。
  - **SQL（登记待确认，禁止直接执行）**：
    ```sql
    -- litisle_review
    DELETE FROM punish_log WHERE id IN (2, 3, 4);
    DELETE FROM review_task WHERE id IN (2, 3);
    DELETE FROM moderation_record WHERE id = 2;

    -- litisle_content
    DELETE FROM article_image WHERE id = 1;

    -- litisle_file
    DELETE FROM file_ref
     WHERE file_id = '25fd4ac95b6248ec995dd5dd4205d326'
       AND biz_type = 'article'
       AND biz_id = 5008;
    ```
  - **2026-11-08 16.1-V1 运行态验证新增残留（仅登记，未清理）**：
    - `litisle_content.article` id=5011（合成行，author=2001，status=2）
    - `litisle_content.article_image` id=2（article\_id=5011，file\_id=`72b89e8c0c454707abda5617c0e7f697`）
    - `litisle_file.file_ref` id=2（file\_id 同上，biz\_type=`article`，biz\_id=5011）
    - `litisle_review.review_task` id=4（scene=ARTICLE 非法测试行，status=0）、id=5（scene=ARTICLE\_FEATURED，status=2）
    - `litisle_review.moderation_record` id=3（biz\_id=5008，scene=ARTICLE\_FEATURED，content\_hash=`dddd…`）
    - MQ 残留（服务已停，消息留在 broker）：`litisle.moderation.image.submit.queue` 3 条（含 provider=none 重投）、`litisle.content.published.queue` 3 条（5011 恢复发布及相关历史残留）
    - Redis：`auth:punish:snapshot:2002`（17.4 写入，TTL 已过期）、`review:dedup:*`（本次文本送审短锁，TTL 已过期）
  - SQL 登记（待确认，禁止直接执行）：
    ```sql
    DELETE FROM litisle_content.article WHERE id = 5011;
    DELETE FROM litisle_content.article_image WHERE id = 2;
    DELETE FROM litisle_file.file_ref WHERE id = 2;
    DELETE FROM litisle_review.review_task WHERE id IN (4, 5);
    DELETE FROM litisle_review.moderation_record WHERE id = 3;
    ```
  - **验收标准**：上述行均不存在；`article` id=5008 与 `file_meta` 的 `safe_flag` 为预期原值；无其他业务数据被误删。
  - **涉及文件**：数据库 `litisle_review` / `litisle_content` / `litisle_file`（无代码改动）。

-------------------------

## 验证完成后的收尾

- 验完并确认通过后：条目从本文件移除，按 [next_update.md](next_update.md)「归档与暂不实现」规则迁入 `docs/archive/<模块>/`，同时在本文件不再保留副本。
- 验证不通过：把失败证据（日志 / 请求响应 / SQL 结果）补进对应条目，保持 `- [ ]`，不升级状态。
- 编号不重排：迁移/归档后留下的编号空缺不要按新序号理解旧引用。
