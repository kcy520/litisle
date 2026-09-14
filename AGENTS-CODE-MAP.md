# AGENTS-CODE-MAP · LitIsle（文屿）

> 面向 AI 编码助手的代码地图。配合 `E:\NewJavaPro\LitIsle\docs\` 下文档使用，不重复其细节。

## 一、项目定位
Spring Cloud Alibaba 多模块微服务文学社区，集成 RAG 知识库与 AI 助手（小月 / 小审）。

## 二、总体架构（自上而下）

| 层级 | 组件 | 职责 |
|------|------|------|
| 接入层 | litisle-gateway | JWT 校验 · 路由 · Sentinel 限流 · WS 透传 |
| 业务层 | 11 个业务服务 | 见第三节 |
| 共享层 | common / api / file | DFA、AOP、Feign 客户端、文件存储 |
| 基础设施 | Nacos / Redis / MySQL / ES / RabbitMQ / Seata / SeaweedFS | 注册配置 / 缓存锁 / 持久化 / 检索 / 消息 / 事务 / 文件 |

## 三、业务服务清单

| 服务 | 职责 | 关键约束 |
|------|------|----------|
| litisle-auth | 认证 / JWT 签发与校验 | — |
| litisle-user | 账号 / 角色 | — |
| litisle-content | 文章 / 书籍 / 草稿 | 仅 PUBLIC、≥200字、非灌水、DFA 低风险、AUTHOR 优先入 RAG |
| litisle-review | 审核 / 举报 / 申诉 | 文章更新按 articleId 幂等更新向量库 |
| litisle-social | 关注 / 赞 / 评论 / 私信 | 积分商城购物需 SocialFeign 实时校验关注数 ≥ 1 |
| litisle-search | ES 全文 + 向量检索 | RAG 相似度阈值 ≥ 0.65；向量库不可用静默降级纯 LLM |
| litisle-ai | 小月（助手）/ 小审（审核） | 简单问题旁路 RAG，直连 LLM |
| litisle-notification | WebSocket + 站内信 | — |
| litisle-payment | VIP / 小硬币 / 赞赏 / 提现 | 赞赏无需 Seata，读者作者账户同库本地事务 |
| litisle-shop | 积分商城 / 商品 / 订单 / 运单 | 必须 Seata AT 强一致；Redis Lua 预扣库存 + 超时自动释放 |

## 四、共享层

- **litisle-common**：DFA SDK、审计 AOP、安全上下文、Redis 工具（含 security / core / redis 子模块）
- **litisle-api**：跨服务 Feign 客户端、DTO、事件 schema、错误码（共享 enum/constant/DTO/VO 集中于此）
- **litisle-file**：SeaweedFS 接入（上传 / 直链 / 鉴权）

## 五、基础设施

| 组件 | 用途 |
|------|------|
| Nacos | 服务注册 + 配置中心（环境值在本地 yml，共享模板在 Nacos） |
| Redis | 缓存 / 分布式锁 / 库存预扣 |
| MySQL | 业务持久化 |
| Elasticsearch | 全文检索 + dense_vector 向量存储（V1 不用 pgvector） |
| RabbitMQ | Confirm / ACK / DLQ / 延迟队列（RAG 异步入库） |
| Seata AT | 仅积分商城事务 |
| Sentinel SDK | 限流 / 熔断（无 Dashboard） |
| SeaweedFS | Master×3 + Volume×3 + Filer×1 |

## 六、工程约定

- 跨服务共享的 enum / constant / DTO / VO → `litisle-api`
- 服务私有 enum / constant / DTO / VO → 各服务模块
- 文章 RAG 入库走 MQ 异步，避免阻塞发布流程
- 私有 / 草稿 / 拒绝文章永不入向量库
- spring-boot版本：3.5.15
- redis的key需要符合企业规范，比如：业务域:模块:功能:唯一标识   如果查到之前的redis的key不符合规范，可优化
Spring Cloud版本：2025.0.0.0
不可使用其他版本不兼容的方法
- 如果我在会话的最开始的时候，只给了你几个单独的文件或文件夹，如果没有必要，只扫描这些文件或文件夹，不扫描其他文件或文件夹，不需要理解整体的项目结构与其他部分的业务逻辑

## 七、文档索引（docs/）

> 路径：`E:\NewJavaPro\LitIsle\docs\`

| 文件 | 内容 |
|------|------|
| Api-Architecture.md | 跨服务契约库（Feign / DTO / 事件 schema / 错误码）架构 |
| Auth-User-Architecture.md | 认证模块 + User 模块架构 |
| commom-des.md | common 模块（security / core / redis 子模块）说明 |
| Design-Document.md | 总体架构 + 分模块架构（文本量大，按需查阅） |
| Function-Document.md | LitIsle 功能设计文档 |
| Sql-Document.md | 数据库表 SQL |
| \Auth-Module-Development-Plan.md | 当前auth模块开发规划 |
| Api-Gateway-Development-Plan.md | api与gateway模块文档 |
| File-Architecture.md | 文件存储模块架构 |



## 八、常用命令（Windows / Win11）

```powershell
# 全量编译
mvn clean compile
# 跳过测试打包
mvn install -DskipTests
# 指定模块编译（含依赖）
mvn -pl litisle-common -am clean compile
```

## 九、AI 代理使用提示

1. 涉及跨服务契约（Feign / DTO / 事件）→ 先查 `Api-Architecture.md`
2. 涉及认证 / 账号 → 查 `Auth-User-Architecture.md`
3. 涉及 common 安全 / Redis / core → 查 `commom-des.md`
4. 涉及库表结构 → 查 `Sql-Document.md`
5. 涉及功能边界 → 查 `Function-Document.md`
6. 总体或某模块完整架构 → 按需查 `Design-Document.md`（量大，避免全量读取）
