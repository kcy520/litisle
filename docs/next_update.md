User wants a TODO/checklist written to next_update.md (empty file, new file creation → output code block directly per chat mode rules). Compile all issues from this conversation: SeaweedFsClient constructor fix, SQL seed data, gateway fixes, sa-token config sync, JWT secret placeholder, max-login-count, UserContextHeaderFilter, Nacos shared config for sa-token, etc.

Organize by priority P0/P1/P2 with status, files, verification steps. Write in Chinese, enterprise style.
```markdown /e:/NewJavaPro/LitIsle/docs/next_update.md
# LitIsle 待办清单（2026-09-20 整理）

> 来源：本轮排查发现的认证链路、种子数据、网关配置问题。
> 状态标记：⬜ 待办 / 🔧 待确认后执行 / ✅ 已完成（代码或 SQL 已落地，待验证）

---

## P0 认证链路（阻塞联调，立即处理）

### 1. ✅ user 模块补充 sa-token 配置（与 auth 对齐）
- **问题**：user 模块所有 yml 无 `sa-token` 段，Sa-Token 回退默认 `token-name: satoken`，
  读取不到 `Authorization: Bearer xxx` 头 → 所有需登录接口 401。
- **修复**：`litisle-user/src/main/resources/application.yml` 已插入与 auth 一致的
  `token-name: Authorization` / `token-prefix: Bearer` / `timeout: 86400` 等配置。
- **验证**：重启 user 服务，用登录 token 调 `GET /api/user/profile/me` 应返回资料。

### 2. ✅ common-security 注册 SaTokenExceptionHandler
- **问题**：`SaTokenExceptionHandler` 是 `@RestControllerAdvice` 但所在包不在业务服务
  组件扫描范围内，`NotLoginException` 漏到兜底处理器，被包装成
  `10000 系统繁忙`（误导性响应）。
- **修复**：`SaTokenAutoConfiguration` 已新增 `@Bean` 注册（带 `@ConditionalOnMissingBean`）。
- **验证**：故意改错 token 调 user 接口，应返回 `10002 令牌无效` 而非 `10000`。

### 3. ⬜ 网关补齐 Redis / Nacos 配置
- **问题**：
  - 网关 yml 无 `spring.data.redis` 配置 → SaReactorFilter 连不上 auth 写会话的
    Redis（localhost:6380），所有 token 一律「令牌无效」；
  - 网关 yml 无 Nacos 注册中心配置 → 全部 `lb://` 路由 503。
- **修复**：修改预览已生成（`litisle-gateway/src/main/resources/application.yml`），
  待 Review and Accept 后重启网关。
- **验证**：
  ```cmd
  curl -H "Authorization: Bearer <accessToken>" http://localhost:8080/api/user/profile/me
  ```
  带 token 返回资料；去掉 header 返回 HTTP 401 + `10002 未登录，请先登录`。

### 4. ⬜ 网关白名单通配符语法修正
- **问题**：`/api/content/article/{id}` 使用 Spring MVC 路径变量语法，
  Sa-Token 匹配器不识别，文章详情会被拦截。
- **修复**：改为 `/api/content/article/*`（已包含在 #3 的 diff 中）。

### 5. ✅ 种子数据 phone_hash / phone 密文替换
- **问题**：SQL 种子数据用 MySQL 端 `SHA2()` 生成 hash，与应用端
  HMAC-SHA256（派生子密钥）不同源 → 查重/登录永远匹配不上；phone 为明文。
- **修复**：`docs/Sql-Document.md` 6 条种子 INSERT 已替换为
  `test.genSeedValues()` 生成的真实 hash（HMAC）与密文（AES-256-GCM）。
- **约束**：主密钥 `RT6uWj/...` 此后不可更换；换密钥需重跑 `genSeedValues()`
  并全量刷新 SQL 与存量用户密文。生产库种子须用 `LITISLE_CRYPTO_KEY` 重新生成。

---

## P1 架构规范（近期处理）

### 6. 🔧 删除网关 JWT 死配置
- **问题**：登录返回的 token 是 UUID（Redis 会话模式）而非 JWT，
  网关 pom 的 `sa-token-jwt` 依赖与 yml 的 `jwt-secret-key` 均未激活（无
  StpLogicJwtFor Bean），属死配置，误导维护者。
- **建议**：删除 `litisle-gateway/pom.xml` 中 `sa-token-jwt` 依赖；
  删除 yml 中 `jwt-secret-key` 配置项。
- **说明**：涉及依赖与配置删除，需开发者确认后执行。

### 7. 🔧 网关 UserContextHeaderFilter 定位收敛
- **问题**：下游服务无任何代码消费 `X-User-Id` / `X-Roles` 等 header
  （业务全部 `StpUtil` 直查 Redis）；且服务端口（8081/8082）可绕过网关直连，
  若将来信任该 header 存在身份伪造风险（FeignAuthInterceptor 也注入同名 header）。
- **建议**：保留过滤器但注释标明「仅用于日志追踪，不作为鉴权依据」；
  或精简为仅透传 userId/username。
- **说明**：需开发者确认取舍后执行。

### 8. ⬜ 确认多设备在线策略
- **问题**：需求为允许多设备同时在线，但 auth 配置 `max-login-count: 2`，
  第 3 台设备会触发顶下线。
- **建议**：按真实需求改 auth 的 `sa-token.max-login-count`（`-1` = 不限）。
  仅需改 auth 侧，user/网关侧该配置无实际作用。

### 9. ⬜ sa-token 配置上收 Nacos 共享配置
- **问题**：目前 auth/user/网关各自 yml 维护一份 sa-token 配置，
  后续 content/social/shop 等模块接入时需重复复制，易出现不一致（本次事故根因）。
- **建议**：将 `sa-token:` 公共段同步到 Nacos `litisle-shared.yaml`
  （环境差异项仅 token-name 等极少量，可全量共享）；
  各服务 yml 只保留必要覆写。与项目既有约定（共享配置 DataId 为
  `litisle-shared.yaml`，需 `.yaml` 后缀）保持一致。

### 10. ⬜ 排查网关 common-security 依赖冲突
- **问题**：网关 pom 引入 common-security（传递 `sa-token-spring-boot3-starter`，
  Servlet 版）与网关自身 `sa-token-reactor-spring-boot3-starter`（Reactive 版）共存。
- **建议**：启动网关观察是否报 Sa-Token Bean 冲突；若冲突，对 common-security
  dependency 做 exclusion 排除 Servlet 版 starter。

---

## P2 安全加固与遗留（规划处理）

### 11. ⬜ 替换 auth JWT 占位密钥
- **位置**：`litisle-auth/src/main/resources/application.yml:161`
  `jwt-secret-key: ${LITISLE_JWT_SECRET:your-32-character-jwt-secret-key}`。
- **说明**：当前 JWT 未实际启用（见 #6）；若保留该能力，上线前必须替换为
  真随机密钥并经 `LITISLE_JWT_SECRET` 环境变量注入。

### 12. ⬜ 白名单最小化复核
- **位置**：网关白名单中 `/api/search/**`、`/api/file/download/**` 整段放行。
- **说明**：搜索/文件服务落地时，复核这些前缀下是否存在需鉴权接口，
  收敛为具体公开路径。网关白名单原则：宁窄勿宽。

### 13. ⬜ SeaweedFsClient 构造注入落地（file 模块）
- **问题**：final 字段未初始化导致编译错误（已给出构造方法 diff）。
- **后续**：接受 diff 后，还需新建配置类（如 `SeaweedFsConfig`）构建
  `S3Client` / `S3Presigner` Bean 注入（endpoint 指向 SeaweedFS、
  pathStyle=true、凭证与 s3.json 一致、Region 任意）。

### 14. ⬜ 存量用户数据兼容性检查
- **说明**：若本地库已有历史注册用户（旧 hash/密文），种子数据替换后
  需确认其 `phone_hash` 与当前派生子密钥一致；不一致则需重置或重新生成，
  否则登录查重异常。
```

清单已整理完毕，可直接保存为 `e:\NewJavaPro\LitIsle\docs\next_update.md`（文件当前为空，内容如上）。几点说明：

1. **结构**：按企业规范分 P0（阻塞联调）/ P1（架构规范）/ P2（安全加固与遗留）三级，每项含「问题 → 修复/建议 → 验证方式」，并标注了状态（✅ 本轮已修复、⬜ 待办、🔧 待你确认后执行）。
2. **🔧 项的处理**：删除网关 JWT 死配置（#6）、UserContextHeaderFilter 收敛（#7）这类涉及删代码/删配置的动作，遵循你的文件删除确认规则，标为「待确认后执行」，你没有点头我不会动。
3. **#9 是防复发关键**：这次 user/auth 配置不一致的根因就是 sa-token 配置分散在各个 yml——上收到 Nacos `litisle-shared.yaml` 后，新模块接入不再需要复制粘贴，建议优先排期。