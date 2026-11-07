# LitIsle（文屿）功能设计文档

> 版本：v2.0
> 说明：涵盖 14 个模块，按"共享层 → 网关 → 业务服务"顺序描述

---

# 一、共享层

## 1. litisle-common（技术基建库）

**定位**：所有服务（含网关）都依赖的通用技术组件，不包含任何业务代码。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 敏感词过滤（DFA） | 引擎在 `litisle-api` 的 `dfa` 包（不在 common）：`DfaFacade` + `AcdatTextScanEngine` 多模式匹配，场景为文章/评论/用户名/私信，`WordlibSnapshot` 快照原子替换，词库变更由 `WordlibChangedMessage` 通知重载 |
| 审计日志 AOP | `@AuditLog` 注解 + 切面，自动记录操作人、操作类型、请求参数、执行耗时，写日志文件 |
| Sa-Token 鉴权与用户解析 | 网关 `SaReactorFilter` 校验登录并注入 `X-User-Id`/`X-Roles`；业务服务用 `StpUtil` / `SaTokenUserIdResolver` 取当前用户，`@SaCheckLogin` 收口；`@Anonymous` + `litisle.security.anonymous.paths` 提供匿名白名单（不再使用 ThreadLocal `SecurityContext`） |
| Redis 工具 | 封装 `RedisUtil`（String/Hash/Set/ZSet/BitMap）+ Redisson 分布式锁 + 布隆过滤器 |
| 统一异常处理 | `BizException` 业务异常 + `ErrorCode` 错误码枚举 + `GlobalExceptionHandler` 统一响应体 |
| Feign 拦截器 | `FeignAuthInterceptor` 自动透传 userId、traceId 到下游服务 |
| 统一响应封装 | `Result<T>` / `PageResult<T>` 统一格式，前端按约定解析 |

**效果**：各服务无需重复实现 Redis 工具、异常处理等基础设施代码；DFA 引擎由 `litisle-api` 统一提供。

---

## 2. litisle-api（跨服务契约库）

**定位**：定义所有跨服务调用的接口、数据传输对象、枚举、事件 schema，**业务服务之间只通过此模块通信**。

**功能清单**：

| 功能 | 说明 |
|------|------|
| Feign 接口定义 | `UserClient` / `AuthClient`（✅ 已实现，含 fallback 降级工厂）；Content/Social/Payment/Shop 等（⏳ 待实现） |
| 跨服务 DTO | `RegisterUserDTO` / `AuthUserDTO` / `UserDTO` 等（✅ 已实现）；其余待实现 |
| 事件 Schema | `UserRegisteredEvent`（⚠️ 空类，字段待补）；`ArticlePublishedEvent` 等待实现 |
| 共享枚举 | `RegisterSourceEnum`（✅ 已实现）；`RoleEnum` 等待实现 |
| 跨服务常量 | `MqConstant`（交换机/队列/路由键）、`BizCacheKey`（`biz:` 前缀缓存 Key）、`FeignConstant`（服务名；Header 名复用 common-core） |
| 错误码枚举 | `ErrorCode` 由 common-core 集中维护，全局统一错误码 |

**效果**：服务间解耦，接口变更只需修改 `litisle-api`，不侵入各服务内部代码。

---

## 3. litisle-file（文件接入库）

**定位**：封装 SeaweedFS 的 S3 兼容接口，为所有业务服务提供文件上传/下载能力。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 文件上传 | 对接 SeaweedFS Filer，支持分片上传、断点续传 |
| 直链生成 | 生成预签名短时效 URL（默认 30 分钟有效），支持私有文件鉴权下载 |
| 缩略图 | 按需生成缩略图（文章封面、头像等场景） |
| 图片对齐信息 | 存储图片在正文中的位置与对齐方式（左/左偏中/中/中偏右/右 5 种对齐） |
| 去重 | sha256 哈希校验，同一文件只存一份 |
| 删除 | 文件软删除 + 定时清理 |

**效果**：业务服务只需调用 `FileService` 即可完成文件操作，无需关心 SeaweedFS 的底层细节。

---

# 二、网关层

## 4. litisle-gateway（网关服务）

**定位**：整个系统的唯一外部入口，所有客户端请求必须经过网关。

**功能清单**：

| 功能 | 说明 |
|------|------|
| Sa-Token 登录校验 | 网关 `SaReactorFilter`（order=-100）：`addInclude("/**")` + `GatewayJwtProperties#paths` 白名单 `addExclude`，其余请求 `StpUtil.checkLogin()` |
| 路由分发 | 根据请求路径 `/api/{service}/**` 路由到对应微服务（`application.yml` 静态配置，未走 Nacos 动态路由） |
| 限流熔断 | 网关不做应用层限流；业务服务用 common-redis `@RateLimiter`（Redis 固定窗口，可本地兜底），Feign 降级由各服务的 Sentinel 兜底 |
| WebSocket 透传 | 按路由透传到 `litisle-notification` 服务（无独立 WsHandler） |
| 文档聚合 | Knife4j 聚合各微服务 API 文档，统一入口查看 |
| 跨域配置 | 网关未集中配置 CORS，按部署环境由前端代理/服务处理 |
| 会话失效 | 退出登录 `StpUtil.logout()` 使服务端 Redis 会话失效；不使用 Token 黑名单 |

**效果**：客户端只认一个入口地址，登录校验、路由、Header 注入在网关层集中处理；限流在各业务服务执行，下游服务无需关心鉴权细节。

---

# 三、业务服务层

## 5. litisle-auth（认证服务）

**定位**：负责用户身份认证、注册登录、OAuth2 第三方登录（未实现）、Sa-Token 登录态签发。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 账号密码注册/登录 | 用户名 + 密码（BCrypt 加密），图形验证码防刷 |
| 邮箱验证码登录 | 邮箱 + 邮箱验证码，验证码有效期 5 分钟（短信通道预留未实现） |
| OAuth2 第三方登录 | 微信 / QQ 扫码登录，绑定已有账号或直接注册新账号（未实现，预留） |
| Sa-Token 登录态 | Sa-Token 会话管理（Token 1 天 + 记住我 7 天）+ 自研 refreshToken 轮换（7 天，一次性） |
| 在线设备管理 | 设备会话由 `DeviceSessionService` 维护，默认最多 5 端（`litisle.auth.max-devices`），超出踢出最旧设备；Sa-Token 本身 `max-login-count=-1` 不限会话数 |
| 退出登录 | StpUtil.logout() 服务端会话失效即下线，并清理 refreshToken / 设备会话 |
| 风控策略 | 接口级限流 @RateLimiter（10 秒窗口）；连续密码错误锁定（设计保留，暂未落地） |

**效果**：用户可选择多种方式登录，安全策略完善，同一账号多端管理。

---

## 6. litisle-user（用户服务）

**定位**：管理用户账号资料、角色体系、VIP 状态、注销。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 用户资料管理 | 昵称、头像（SeaweedFS）、手机号（加密存储）、邮箱、简介、笔名 |
| 角色体系 | 6 角色：READER（读者）/ AUTHOR（作家）/ LEGACY_AUTHOR（纪念作家）/ REVIEWER（审核员）/ ADMIN（管理员）/ SUPER_ADMIN（超级管理员） |
| 角色叠加校验 | 同一用户可同时拥有多个角色，组合规则（如 LEGACY_AUTHOR 与 AUTHOR 互斥） |
| VIP 状态 | 购买 VIP 后到期时间自动计算，到期前 7 天站内信提醒，到期后自动冻结 VIP 权益（控制器未实现） |
| 头像标识 | 普通用户无标识、作家金色头像框、纪念作家白色头像框（avatarColor 存主表 sys_user） |
| 管理员用户管理 | /api/admin/users：分页/详情/改状态/角色分配/注销用户 |
| 封禁/解封 | 写 sys_user_ban_log + frozen_until，联动强制下线；到期 UserUnbanTask 自动解封 |
| RBAC 权限 | sys_permission / sys_role_permission 权限体系，角色-权限关联 |
| UID 搜索 | sys_user.uid 对外唯一标识，按 UID/用户名搜索用户 |
| 注销账号 | 逻辑注销（`del_flag=1`），不可恢复，需二次确认（控制器未实现） |
| 关注/粉丝计数 | Redis String 存储，异步落库（控制器未实现） |

**效果**：用户有丰富的角色成长体系，VIP 状态管理自动化，隐私数据脱敏保护。

> 角色：
> 一个用户可以拥有多个角色，但“权限判定时只认最高层级”。
>
> ✅ 存储上允许多层级
>
> ❌ 权限判定上不允许多层级并存生效
>
> > **层级是“角色的属性”，不是“独立实体”。**
> >
> > ```java
> > public enum RoleLevel {
> > 
> >     /**
> >      * 普通用户层：READER
> >      */
> >     NORMAL,
> > 
> >     /**
> >      * 业务角色层：AUTHOR / LEGACY_AUTHOR
> >      */
> >     BUSINESS,
> > 
> >     /**
> >      * 审核层：REVIEWER（后台干活的人）
> >      */
> >     REVIEWER,
> > 
> >     /**
> >      * 管理角色层：ADMIN
> >      */
> >     ADMIN,
> > 
> >     /**
> >      * 超级管理员：SUPER_ADMIN（测试/最高权限）
> >      */
> >     SUPER_ADMIN
> > }
> > ```
> >
> > 

---

## 7. litisle-content（内容服务）

**定位**：文章、书籍、草稿的核心管理，支持批量发布。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 文章 CRUD | 富文本编辑器，支持标题、正文（HTML）、摘要、封面、分类、分区 |
| 图片插入与对齐 | 正文任意位置插入图片，5 种对齐方式（左/左偏中/中/中偏右/右） |
| 草稿自动保存 | 未发布的文章自动保存为草稿，支持多次编辑 |
| 分区管理 | 普通分区（所有用户可发） / 作家分区（仅 AUTHOR 角色可发） |
| 批量发布 | 作者写好一批文章/章节暂存着，攒够了再一次性批量发布，同一批次共享 `publish_batch_id` |
| 定时发布 | 草稿态文章可设定时发布时间（`PUT /api/content/article/{id}` 传 scheduledPublishTime，仅 status=0，传 cancelScheduledPublish=true 取消）；到点由定时任务每 1min 扫描并自动走批量送审链路（多实例分布式锁单点 + CAS 幂等防重）；发布成功或失败均清空定时字段，失败不静默（日志留痕，文章停留草稿态待作者处理） |
| 发布可见性 | 公开 / 仅自己可见 / 仅关注者可见，发布后可修改（**原「密码访问」已于阶段9下线，存量 visibility=4 迁移为 1**） |
| 书籍/合集管理 | 创建书籍，文章作为章节挂到书籍，有序号排序；章节批量重排（拖拽后全量提交新序 1..N，两阶段偏移解唯一键冲突，下架书拒重排） |
| 阅读历史 | 书籍历史（含最后阅读章节，支持续读）/ 独立文章历史；阅读文章详情即自动落历史；单用户每类型上限 **1000 条**（批次十一 11.4 由 500 调整，写入时懒删最旧行，保留最近在读 10 条不裁）；`duration_ms` 列已建但**暂不写入**（预留） |
| 收藏 | 文章/书籍收藏与取消（幂等）、我的收藏列表、收藏状态查询（一期 content 临时承载单层收藏，social 立项后迁移）；**11.9 起收藏计数排除作者自收藏** |
| 书籍阅读 | 书籍详情（上次阅读/继续阅读/收藏态）、章节列表分页、章节详情（正文+上一章/下一章导航）；匿名可读公开书籍，登录带上次阅读；读章触发浏览计数与书籍历史；`book_read_quality` 提供「粗完读率」代理统计（追到最大可见章节号的人数 / 有阅读记录的人数，只读不参与排序） |
| 大篇幅切片 | **切片已收口 review 消费端**（content 只发单条 `content.submitted`，事件体不含正文；review 按配置 `llm.max-text-chars` 切片并并行机审，禁止静默截断）；原文"正文超过 2000 字自动切片"属旧口径 |
| 正文长度上限 | 富文本正文硬上限 256KB（≈10 万字 HTML），发布/建档/编辑/草稿保存四路统一拦截（错误码 30018），超限提示拆分书籍章节发布——超长篇创作的正确载体是书籍分章，每章独立过机审 |
| 推荐流 | 原文「zone 1/2 聚合 + `score=(like×3+comment×2+view×0.2)/(小时数+2)^1.5`」为阶段14 旧口径：分区部分仍准确（`zone=3` 已取消），**热度公式已于 11.10 作废**，改由 `FeedScoreSupport` 七项权重 + 贝叶斯平滑统一打分，灌榜增量水位改按 `stat_update_time`，榜空仍回退时间倒序；榜单消费见下方「推荐流（无限刷）」 |
| 推荐流（无限刷） | 文章流 `GET /api/content/feed/articles` 与书籍流 `GET /api/content/feed/books` 两条独立无限刷：游标 + 快照代次分页（翻页不重算召回）、四路 DB 召回（关注/热度/分类偏好/新内容）、融合打分、探索位与打散、冷启动、匿名可刷（`X-Anon-Id`，登录/匿名各自频控）；响应含 `cursor/snapshotId/hasMore/reset/degraded` |
| 推荐降级 | 四级降级标记：`redis-down`（Redis 不可用）/ `build-failed`（建池失败）/ `last-good`（读最近一次可用快照）/ `hot-empty`（热门榜为空回退时间倒序）；降级页同样应用负反馈过滤与曝光去重 |
| 负反馈 | `feed_feedback` 三维度：1-不感兴趣（单条）/ 2-屏蔽作者 / 3-屏蔽分类；支持撤销（幂等），30 天窗口内生效；生效位置：内容级在切片阶段当页消失，作者/分类级在下次建池（下一代快照）生效 |
| 点赞 | 文章/书籍点赞与取消：`user_like` 幂等（重复点赞 40002）、目标不可见 40004、作者不可自赞 40005；计数写 `article_stat`/`book_stat`（下限保护），取消点赞发 `social.unliked` 负修正 search 热度 |
| 统计与对账 | `article_stat`/`book_stat` 为计数真源（`article.like_cnt` 仅展示镜像），`content_stat_window` 预计算 30/90 天窗口；`ContentStatReconcileJob` 每日对账 6 类差异并上报 Gauge，超阈值告警；存量修复走 `tools/db-repair`（手工执行，不参与初始化） |
| 曝光与点击归因 | 切片曝光按日聚合落 `feed_exposure_daily`（同身份同日同条各最多 1 次）；详情接口带 `from`/`snapshotId`/`position` 时记点击；CTR 分 `login`/`anon` 身份口径 |

**效果**：作者可以灵活管理自己的创作内容，支持草稿→批量发布→书籍组织的完整创作流程。

---

## 8. litisle-social（社交服务）

**定位**：关注、点赞、评论、收藏、佳句、私信等社交功能。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 关注/取关 | 用户互相关注，关注关系走 Redis 缓存 + MySQL 持久化 |
| 粉丝计数 | 实时展示粉丝数、关注数，Redis 异步更新 |
| 点赞/取消点赞 | 文章、评论、佳句三类目标点赞，Redis 异步同步计数 |
| 评论（三级折叠） | 一级评论 + 回复（折叠展示），root_id 支持整棵子树拉取，楼层号自动递增 |
| 收藏夹 | 用户创建自定义收藏夹，收藏文章/书籍到指定文件夹（一期文章/书籍单层收藏由 content 模块 favorite 临时承载，本模块为收藏夹完整版设计，立项后迁移/合并） |
| 佳句收藏 | 选中文章段落收藏为佳句，记录出处（文章标题、作者）、用户备注 |
| 私信（陌生人限制） | 陌生人最多发 3 条消息，需对方确认才能继续；支持黑名单、私信设置 |
| 消息撤回 | 已发送消息可撤回（状态置 0），对方可见「消息已撤回」 |

**效果**：完整的社交互动闭环，关注→点赞→评论→收藏→私信，陌生人私信风控机制保护用户不被骚扰。

---

## 9. litisle-review（审核服务）

**定位**：内容审核、举报处理、申诉流程。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 内容审核 | 接收 `content.submitted` 事件，调 DFA 敏感词过滤 + AI 小审辅助判断 |
| 风险等级 | 低风险（自动通过）/ 中风险（人工审核）/ 高风险（驳回） |
| 举报处理 | 用户举报文章/评论/私信，审核员介入处理 |
| 申诉流程 | 作者对驳回决定不满可申诉，管理员二审 |
| 审核记录 | 完整记录每次审核的操作人、操作时间、审核结果、备注 |

**效果**：内容安全有保障，敏感词 + AI + 人工三层审核，审核流程可追溯。

---

## 10. litisle-ai（AI 服务）

**定位**：提供 AI 能力，支持两个 Agent：小月（有状态对话）/ 小审（无状态审核辅助）。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 小月（创作助手） | 有状态对话，可记住上下文，辅助作者写作建议、灵感启发、润色修改 |
| 小审（审核助手） | 无状态，接收文章内容，输出风险等级评估，辅助审核员决策 |
| LangChain4j 集成 | 基于 LangChain4j 框架，可对接多种大模型后端 |

**效果**：作者有创作助手，审核员有审核助手，AI 提升创作与审核效率。

---

## 11. litisle-payment（支付服务）

**定位**：小硬币、VIP 会员、赞赏、提现、积分商城扣币。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 小硬币 | 充值与消费，余额存 `coin_account` 表，BIGINT 存储（最小单位 1 微币，避免浮点精度问题） |
| 赞赏 | 读者向作者打赏，读者扣币 + 作者加币，单库本地事务（币账户统一在 payment 库） |
| VIP 会员 | 购买 VIP 套餐，自动延长 `vip_expire_time`，通过 MQ 通知 user 服务更新 |
| 提现 | 作者申请提现（有最低门槛），审核后打款，状态变更走 MQ 通知 |
| 积分商城扣币 | 提供 `CoinDeductionService` 作为 Seata AT 分支参与者，供 shop 服务调用 |

**效果**：虚拟货币体系完整，赞赏/提现/商城支付一体化，币账户同库避免分布式事务。

---

## 12. litisle-shop（积分商城服务）

**定位**：积分商城，管理员上架限时限量商品，满足条件的用户用小硬币购买。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 商品管理（管理员） | 上架/下架/编辑商品，设置标题、描述、价格（小硬币）、库存、限购数量、活动时间 |
| 限时抢购 | 商品设置上架时间与下架时间，过期自动下架 |
| 库存管理 | Redis Lua 脚本预扣库存防超卖，超时未支付自动释放库存 |
| 购买资格校验 | 用户关注粉丝数 ≥ 1（通过 `SocialFeign` 实时校验，方便测试） |
| 下单流程 | 资格校验 → 库存预扣 → Seata AT 全局事务（扣币 + 创建订单 + 生成运单） |
| 订单管理 | 订单状态机：待支付 → 已支付 → 已发货 → 已送达 → 已取消 |
| 运单管理 | 关联快递公司、快递单号、发货时间、签收时间 |
| 超时释放 | 订单创建后 30 分钟未支付自动取消，释放库存 |

**数据库表**：`shop_product`（商品）、`shop_order`（订单）、`shipping`（运单）

**效果**：用户可以用小硬币兑换实体/虚拟商品，活动运营能力强，库存安全不超卖。

---

## 13. litisle-search（搜索服务）

**定位**：全文检索 + 向量相似度搜索，提供文章、书籍、用户的搜索能力。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 全文检索 | 基于 Elasticsearch：标题(权重4)→标签(3)→摘要(2)→正文，支持文章/书籍/用户三类；filter 支持可见性、标签、分类、分区、时间范围 |
| 搜索联想 | `GET /api/search/suggest?q=`：标题/作者 completion + prefix 匹配；标签联想复用 content 标签接口 |
| 相似推荐 | `dense_vector` KNN（1024 维）+ 标签重合 + 行为加权；强制仅 PUBLIC，排除自身与已下架内容；向量在发布时预计算落索引 |
| 搜索历史 | 登录用户最近搜索词（同词 UPSERT 只前进、超上限懒删最旧）；关键词不入 ES 索引，只落 `litisle_search.search_history` |
| 索引同步 | 监听 MQ：文章/书籍/用户三类索引异步 upsert/删除；文本事件按 ID 幂等 + 索引版本门控防乱序 + 下架墓碑防回灌；互动事件只累加 Redis 热度待刷新键，定时批量重算 heat（取消点赞负数累加并做 0 下限保护）；失败指数退避延迟重投，超限入死信队列 |
| 搜索排序 | 相关度（默认）/ 最新 / 热度 |
| 降级 | ES 不可用 → 回退 content 热门列表（`source=DEGRADED` 不缓存）；降级关闭或热门获取失败返回 `SEARCH_ES_UNAVAILABLE`(100004) |
| 向量重建 | Embedding 模型/维度变更时走管理端点全量重建（`VectorRebuildAdminController`），索引 meta 记录模型版本 |

**效果**：用户能快速找到感兴趣的内容，系统能推荐相似文章。

---

## 14. litisle-notification（通知服务）

**定位**：站内信通知 + WebSocket 实时推送。

**功能清单**：

| 功能 | 说明 |
|------|------|
| 站内信通知 | 订阅全业务 MQ 事件（审核结果、新粉丝、点赞、评论回复、VIP 到期提醒等），写入通知表 |
| WebSocket 实时推送 | 在线用户通过 WebSocket 实时接收通知，不在线用户下次登录时拉取 |
| 未读计数 | Redis 维护未读通知数，用户在导航栏可见红点 |
| 通知设置 | 用户可自定义接收哪些类型的通知（点赞/评论/关注/系统通知等） |
| 通知保留 | 超过 90 天的通知自动清理 |

**效果**：用户实时感知互动动态，不错过重要消息，通知不过度打扰。

---

## 四、模块依赖关系

**litisle-gateway** 直接路由到以下所有业务服务：

- litisle-auth（认证）
- litisle-user（用户）
- litisle-content（内容）
- litisle-social（社交）
- litisle-review（审核）
- litisle-ai（AI）
- litisle-payment（支付）
- litisle-shop（商城）
- litisle-search（搜索）
- litisle-notification（通知）

**跨服务调用依赖：**

| 调用方 | 被调用方 | 说明 |
|--------|----------|------|
| litisle-shop | litisle-payment | Seata AT 扣币 |
| litisle-shop | litisle-social | 校验粉丝数 |
| litisle-content | litisle-review | 内容送审 |
| litisle-review | litisle-ai | AI 辅助审核 |

**所有业务服务 → litisle-common / litisle-api / litisle-file**（编译期依赖，非运行时调用）