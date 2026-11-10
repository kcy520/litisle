

------

# LitIsle API 跨服务契约库架构文档

> 本文档详细描述 `litisle-api` 模块的定位、包结构、Feign 契约、DTO/VO、MQ 事件、共享枚举/常量/错误码的设计规范，作为跨服务通信的"宪法"。
>
> **说明**：本文档只描述架构与设计，不包含具体代码实现。

------

## 一、文档概述

### 1.1 文档目的

- 

  明确 `litisle-api` 作为**跨服务契约库**的职责边界与设计原则。

- 

  规划完整的包结构，定义 Feign 接口、DTO/VO、MQ 事件、共享枚举/常量/错误码的归属。

- 

  阐明契约库与各业务服务的依赖关系，避免循环依赖与跨服务 internal 包直接引用。

### 1.2 设计依据

- 

  Design-Document.md §2.1.2 litisle-api 模块定义

- 

  commom-des.md common 基建能力

- 

  Auth-User-Architecture.md auth/user 模块设计

- 

  Sql-Document.md 数据库设计

### 1.3 模块定位

`litisle-api` 是**跨服务契约库**（非独立微服务），为所有业务服务提供：

- 

  **Feign 接口契约**：服务间同步调用的接口定义

- 

  **共享 DTO/VO**：跨服务出入参对象

- 

  **MQ 事件 Schema**：异步通信的事件契约

- 

  **共享枚举**：跨服务一致的枚举值（角色/分区/风险等级等）

- 

  **共享常量**：缓存 Key 前缀、MQ Exchange/Queue、Header 名等

- 

  **统一错误码**：按模块分段的 ErrorCode 枚举

**核心约束**：业务服务之间**只通过本模块的 Feign 接口或 MQ 事件通信**，禁止直接 import 对方 internal 包。

### 1.4 在整体架构中的位置

下面的架构图展示了 `litisle-api` 在整个系统中的位置：

┌─────────────────────────────────────────────────────┐

│                    业务服务层                          │

│  auth  user  content  social  review  ai  payment ... │

└──────────┬───────────────────────────────────┬───────┘

│ Feign 调用                          │ MQ 事件

▼                                    ▼

┌─────────────────────────────────────────────────────┐

│              litisle-api（跨服务契约库）               │

│  feign / dto / vo / event / enums / constant / result│

└──────────────────────┬──────────────────────────────┘

│

▼

┌─────────────────────────────────────────────────────┐

│              litisle-common（技术基建库）              │

└─────────────────────────────────────────────────────┘

------

## 二、当前实现状态

### 2.1 现状

| 内容            | 状态 | 说明                                             |
| --------------- | ---- | ------------------------------------------------ |
| pom.xml         | ✅   | 纯 jar 契约库（无 boot 插件/启动依赖）；openfeign / autoconfigure / sensitive-word 为 provided，不强制传递给消费方 |
| 启动类/配置文件 | ✅   | 已删除                                           |
| feign/ 包       | ✅   | UserClient / AuthClient / FileClient / AiClient / ContentClient / SocialClient / ReviewClient（均含 fallbackFactory 降级） |
| config/ 包      | ✅   | FeignApiConfig（超时/重试统一配置）+ FeignFallbackAutoConfiguration（降级工厂自动装配） |
| fallback/ 包    | ✅   | User / Auth / File / Ai / Content / Message / Review 七个 FallbackFactory |
| moderation/ 包  | ✅   | 发布前置/机审联动门面组件 12 个（见 §15） |
| dfa/ 包         | ✅   | DFA 同步闸门共享组件（7 个契约组件 + 3 个引擎组件，见 §14） |
| dto/ 包         | ✅   | auth / captcha / user / file / ai / moderation / content / social 子包 |
| vo/ 包          | ✅   | 已独立成包（vo/user/UserBriefVO、UserRolesVO 暂为空类占位） |
| event/ 包       | ✅   | user/UserRegisteredEvent（空类）+ UserBannedEvent + moderation/ 五个事件 + content/ContentPublishedEvent、ContentUnpublishedEvent（均字段完整） |
| enums/ 包       | ✅   | RegisterSourceEnum + ImageAuditTriggerEnum（RoleEnum 等共享枚举待实现） |
| constant/ 包    | ✅   | MqConstant / BizCacheKey / FeignConstant / ModerationConstant |
| result/ 包      | ❌   | 不再需要（ErrorCode 由 common-core 统一维护）    |

### 2.2 待修正项

**✅ 已全部完成**：启动类/application.properties 已删除，pom.xml 已移除 spring-boot-maven-plugin 与 spring-boot-starter，Feign/Jackson/Validation/Lombok 依赖已补齐（另依赖 common-security、spring-web）。

> **残留待清理**：test 目录下的 `LitisleApiApplicationTests.java` 仍引用已删除的启动类，编译会报错，需确认后处理。

------

## 三、代码架构（包结构）

下面是 `litisle-api` 的标准包结构：

litisle-api

├── feign/

│   ├── UserClient            # ✅ 用户服务契约

│   ├── AuthClient            # ✅ 认证服务契约

│   ├── FileClient            # ✅ 文件服务契约（引用登记/隔离/机审取图，见 §4.4）

│   ├── AiClient              # ✅ AI 服务契约（LLM 通用对话通道，提供方端点待实现，见 §4.5）

│   ├── ContentClient         # ✅ 内容服务契约（机审回查正文/配图反查/计数回写，提供方已落地，见 §4.6）
│   ├── SocialClient          # ✅ 私信服务契约（私信机审回查，见 §4.7）
│   ├── ReviewClient          # ✅ 审核服务契约（取证落库/处罚态回查，见 §4.8）
│   └── PaymentFeignApi / ShopFeignApi / NotificationFeignApi  # ⏳ 待实现

├── config/

│   ├── FeignApiConfig        # ✅ 超时/重试统一配置

│   └── FeignFallbackAutoConfiguration  # ✅ 降级工厂自动装配（AutoConfiguration.imports 注册）

├── fallback/

│   ├── UserClientFallbackFactory     # ✅

│   ├── AuthClientFallbackFactory     # ✅

│   ├── FileClientFallbackFactory     # ✅（写操作/闸口 fail-fast，展示读静默空 Map）

│   ├── AiClientFallbackFactory       # ✅（LLM 核心读依赖 fail-fast，转 SUSPICIOUS 人审）
│   ├── ContentClientFallbackFactory  # ✅（机审回查正文 fail-fast）
│   ├── MessageClientFallbackFactory  # ✅（私信回查 fail-fast，服务接口为 SocialClient）
│   └── ReviewClientFallbackFactory   # ✅（取证/处罚态回查 fail-fast）

├── dfa/                      # ✅ DFA 同步闸门共享组件（见 §14）

│   ├── DfaFacade             # 门面：initAll / rebuild / check

│   ├── TextScanEngine        # 扫描引擎接口（宿主/门面可插拔）

│   ├── AcdatTextScanEngine   # AC 自动机 + 双数组 Trie 引擎实现

│   ├── CharNormalizer        # 字符归一化（全角/大小写等，扫描前统一）

│   ├── DfaScenes             # 场景归一映射（ARTICLE/COMMENT/USERNAME/MESSAGE/BIO）

│   ├── DfaHitResult          # 判定结果（hit/maxLevel/hitWords/wordlibVer）

│   ├── WordEntry             # 词库词条（word/level/blacklist 黑白名单分流）

│   ├── WordLibraryLoader     # 词库装载 SPI（宿主各自实现数据源）

│   ├── WordlibSnapshot       # 词库快照 JSON 契约

│   └── WordlibChangedMessage # 词库变更 Pub 广播消息

├── dto/

│   ├── auth/                 # ✅ RegisterUserDTO 等（TokenVO/OAuthAuthorizeVO 暂放此处）

│   ├── captcha/              # ✅ ChangeEmailVerifyDTO

│   ├── user/                 # ✅ UserDTO / AuthUserDTO / RegisterUserDTO

│   ├── file/                 # ✅ FileOwnerDTO / FileRefDTO / FileContentDTO

│   ├── ai/                   # ✅ AiChatRequestDTO / AiChatResponseDTO
│   ├── moderation/           # ✅ PunishStatusDTO / PunishAuditDTO / PunishSpeakCheckDTO / UsernameRejectRecordDTO
│   ├── content/              # ✅ ContentDTO / ArticleImageRefDTO / ContentPreviewCheckDTO / ContentPreviewCheckVO / ArticleCounterUpdateDTO
│   ├── social/               # ✅ MessageDTO（私信机审回查出参）
│   └── review/ payment/ ...  # ⏳ 待实现

├── moderation/               # ✅ 跨服务决策口径 SSOT 门面（12 个，见 §15）

│   ├── SubmitTextAssembler / PublishTextPrecheck / DfaPublishGate
│   ├── UsernameRejectGate / UsernameModerationGate / BioModerationGate
│   ├── LastHashStore / LastHashExemptChecker / StrangerQuotaGate
│   └── MessageRecallGate / MachineRejectPunishGate / ContentUnpublishedInvalidator

├── event/

│   ├── user/UserRegisteredEvent        # ⚠️ 空类，Schema 字段已在 Javadoc 约定待补

│   ├── user/UserBannedEvent            # ✅ 用户封禁（user 发布 → content 消费下架连带）

│   ├── content/ContentUnpublishedEvent # ✅ 内容下架（content 发布 → ai/search 消费）

│   ├── content/ContentPublishedEvent   # ✅ 内容发布（content 发布 → search/ai/notification 消费）
│   └── moderation/
│       ├── ImageSubmitEvent            # ✅ 图片送审（file 发布 → review 消费）
│       ├── TextSubmitEvent             # ✅ 文本送审（content/message 发布 → review 消费）
│       ├── ModerationResultEvent       # ✅ 审核结果（review 发布 → file/内容/社交 消费）
│       ├── ModerationAppealResultEvent # ✅ 申诉裁决结果（review 发布 → file 消费）
│       └── PunishResultEvent           # ✅ 处罚结果（review 发布 → auth/user 消费回查权威态）

├── vo/

│   └── user/

│       ├── UserBriefVO       # ⚠️ 空类占位

│       └── UserRolesVO       # ⚠️ 空类占位

├── enums/

│   ├── RegisterSourceEnum    # ✅ WEB/APP/MINI_PROGRAM

│   └── ImageAuditTriggerEnum # ✅ 图片审核触发时机（UPLOAD_IMMEDIATE/UPLOAD_PRECHECK/EXEMPT）

└── constant/

    ├── MqConstant            # ✅ Exchange/Queue/Routing（含 moderation 与 DLX）

    ├── BizCacheKey           # ✅ biz: 前缀业务缓存 Key

    ├── FeignConstant         # ✅ 超时/重试常量（Header 名复用 common-core HeaderConstant）

    └── ModerationConstant    # ✅ 审核枚举口径 + Redis key 契约 + 词库 Pub/快照 key

------

## 四、Feign 接口契约设计

### 4.1 设计规范

| 规范项       | 说明                                                    |
| ------------ | ------------------------------------------------------- |
| 接口命名     | `{Service}Client`（如 `UserClient` / `AuthClient`）      |
| 服务名       | `@FeignClient(name = "litisle-user")`                   |
| 配置         | `configuration = FeignApiConfig.class`（超时/重试统一） |
| 路径前缀     | `/api/feign/{service}`                                  |
| 返回值       | `Result<T>`                                             |
| 入参         | 可序列化                                                |
| 降级         | `fallbackFactory` 指向 `fallback/` 包的工厂类（见下）   |
| 生效前提     | ① 调用方启用 Sentinel Feign 适配：pom 添加 `spring-cloud-starter-alibaba-sentinel`，yml 配置 `feign.sentinel.enabled: true`（★ 唯一生效属性名，位于顶层 `feign.*` 而非 `spring.cloud.openfeign` 下，建议放 Nacos 共享模板）；② 降级工厂 Bean 由 litisle-api 的 `FeignFallbackAutoConfiguration` 自动注册（`META-INF/spring/...AutoConfiguration.imports`），业务服务零改动 |
| 判码规范     | 调用方必须手动判 `result.isSuccess()`（或 `result.getCode()`），**禁止把 `Result.fail` 当成功数据处理** |
| 降级错误码   | 工厂内统一抛 `ErrorCode.REMOTE_SERVICE_ERROR`，不伪造业务错误码；登录链路降级禁止返回"用户不存在"语义 |
| 写操作降级   | 核心写操作 fail-fast 穿透，禁止静默降级为成功；旁路写操作（如 forceLogout）可"尽力而为"，需注释说明并确保有后续兜底 |
| 文件传输     | 通过 `fileId`                                           |
| 幂等性       | 调用方加 `@Idempotent`                                  |

> **降级语义**：提供方业务异常 → 全局异常处理器转 `Result.fail` → Feign 正常解码，**不触发降级**；网络层失败 / 5xx → 触发 `XxxClientFallbackFactory`，降级方法内抛 `BizException` 穿透到调用方，由调用方全局异常处理器兜底。
>
> **判码示例**（auth 服务现有写法）：`Result<AuthUserDTO> result = userClient.findByAccount(account); if (!result.isSuccess() || result.getData() == null) { ... }` —— 所有 Feign 调用点必须如此判码。

### 4.2 UserClient（用户服务契约，由 litisle-user 实现提供方）

| 方法              | 路径                                    | 入参              | 出参          | 说明                                                |
| ----------------- | --------------------------------------- | ----------------- | ------------- | --------------------------------------------------- |
| `getUserById`     | GET `/api/feign/user/{userId}`          | userId            | `UserDTO`     | 查用户基本信息                                      |
| `createUser`      | POST `/api/feign/user`                  | `RegisterUserDTO` | `AuthUserDTO` | 注册：唯一性校验 + AES 加密手机/邮箱 + 分配 READER  |
| `findByAccount`   | GET `/api/feign/user/account/{account}` | account           | `AuthUserDTO` | 兼容用户名/手机号/邮箱，返回认证聚合信息（含密码哈希、状态、角色、权限） |
| `findByEmail`     | POST `/api/feign/user/email`            | email（body）     | `AuthUserDTO` | 邮箱验证码登录查账号；明文邮箱改 POST + body 传输，避免敏感数据进网关/服务访问日志 |
| `getAuthUserInfo` | GET `/api/feign/user/auth/{userId}`     | userId            | `AuthUserDTO` | `/auth/me` 用：角色、权限、作家扩展资料             |

> OAuth 相关契约（`findByOAuth` / `createUserByOAuth` / `bindOAuth` / `unbindOAuth`）已预留注释，暂不实现。

### 4.3 AuthClient（认证服务契约，由 litisle-auth 实现提供方）

| 方法                | 路径                                          | 入参                    | 出参 | 说明                                                         |
| ------------------- | --------------------------------------------- | ----------------------- | ---- | ------------------------------------------------------------ |
| `forceLogout`       | DELETE `/api/feign/user/{userId}/sessions`    | userId                  | Void | 强制下线全部在线会话（Sa-Token 会话 + 设备记录 + refreshToken），user 管理员注销/封号时调用 |
| `verifyChangeEmail` | POST `/api/feign/captcha/change-email/verify` | `ChangeEmailVerifyDTO`  | Void | 改绑邮箱双码校验（图形码 → 邮箱码，均一次性消费，防爆破）    |

> **调用方向变更**：auth ⇄ user 为**双向 Feign**——auth 调 `UserClient` 查/建用户；user 调 `AuthClient` 强制下线/验码。

### 4.4 FileClient（文件服务契约，由 litisle-file 实现提供方）

| 方法                 | 路径                                        | 入参           | 出参                  | 降级语义     | 说明                                                         |
| -------------------- | ------------------------------------------- | -------------- | --------------------- | ------------ | ------------------------------------------------------------ |
| `getFileInfo`        | GET `/api/feign/file/{fileId}/info`         | fileId         | `FileOwnerDTO`        | fail-fast    | 归属校验闸口（不脱敏，仅服务间，防挂他人文件）             |
| `bindRef`            | POST `/api/feign/file/ref/bind`             | `FileRefDTO`   | Void                  | fail-fast    | 登记引用（四元组幂等），调用方与业务表同事务                |
| `unbindRef`          | POST `/api/feign/file/ref/unbind`           | `FileRefDTO`   | Void                  | fail-fast    | 解除引用（按四元组精确定位，仅删当前调用方引用行）          |
| `batchGetUrls`       | POST `/api/feign/file/batch-urls`           | List\<fileId\> | Map\<fileId,url\>     | 静默空 Map   | 展示类读，避免列表页 N+1；降级后列表页用占位图             |
| `quarantine`         | POST `/api/feign/file/{fileId}/quarantine`  | fileId         | Void                  | fail-fast    | 违规图隔离（quarantine/ 前缀 + safe_flag=0，幂等）          |
| `unquarantine`       | POST `/api/feign/file/{fileId}/unquarantine`| fileId         | Void                  | fail-fast    | 隔离恢复（申诉改判 PASS，幂等，与 quarantine 对称）         |
| `batchGetSafeFlags`  | POST `/api/feign/file/batch-safe-flags`     | List\<fileId\> | Map\<fileId,Integer\> | 静默空 Map   | 批量读 safe_flag（0/1/2/3 int code，SSOT 为 file 侧枚举）；缺失的 fileId 须视为未知态保守处理 |
| `getContent`         | GET `/api/feign/file/{fileId}/content`      | fileId         | `FileContentDTO`      | fail-fast    | 机审取图唯一入口（字节流 Base64，防 SSRF；不过 safe_flag 闸口，申诉复核需重取隔离图） |

### 4.5 AiClient（AI 服务契约，由 litisle-ai 实现提供方，端点待实现）

| 方法   | 路径                     | 入参                | 出参                 | 降级语义 | 说明                                                         |
| ------ | ------------------------ | ------------------- | -------------------- | -------- | ------------------------------------------------------------ |
| `chat` | POST `/api/feign/ai/chat`| `AiChatRequestDTO`  | `AiChatResponseDTO`  | fail-fast | OpenAI 兼容通道（base-url/api-key/model 外置 Nacos）；system+user 双段 prompt 最小对话面，few-shot/防注入/JSON 白名单校验由调用方组织；降级由 review 侧转 SUSPICIOUS 人审；**请求须带计量打标字段 `purpose`/`requestId`（用途由调用方显式传入，缺省端点拒绝，见 AI-Architecture「按用途计量与预算分账」）** |

### 4.6 ContentClient（litisle-content 提供方，端点已落地：ContentFeignProviderController，均 fail-fast）

- `getContent(bizType, bizId)` — GET `/api/feign/content/get` → `ContentDTO`：机审回查正文
- `listArticleImages(articleId)` — GET `/api/feign/content/article-images` → `List<ArticleImageRefDTO>`：配图复审反查列表（逐张经 FileClient 取图）
- `listArticleIdsByFileId(fileId)` — GET `/api/feign/content/article-ids-by-file` → `List<Long>`：按 idx_file_id 反查引用文章（图片复审再聚合用；聚合判定收口 review 消费端）
- `updateCounters(ArticleCounterUpdateDTO)` — POST `/api/feign/content/counters/update` → Void：social 计数回写冗余列（view/like/comment cnt，幂等绝对值 SET）

### 4.7 SocialClient（litisle-social 私信域提供方，端点待实现，fail-fast）

- `getContent(bizId)` — GET `/api/feign/message/get` → `MessageDTO`：私信机审回查（降级工厂 MessageClientFallbackFactory）

### 4.8 ReviewClient（litisle-review 提供方，fail-fast）

- `insertUsernameRejectRecord(UsernameRejectRecordDTO)` — POST `/api/feign/moderation/insert-username-reject-record`：注册拒绝取证（不建任务不进 MQ）
- `getPunishStatus(userId)` — GET `/api/review/punish/status` → `PunishStatusDTO`：处罚权威态回查（撤销返回全空快照；网关不路由防公网直查）

------

## 五、共享 DTO 设计

### 5.1 设计规范

| 规范项 | 说明                     |
| ------ | ------------------------ |
| 命名   | `{业务}{操作}DTO`        |
| 包归属 | `dto/{service}/`         |
| 校验   | `jakarta.validation`     |
| 序列化 | `Serializable`           |
| Lombok | `@Data`，禁用 `@Builder` |

User 模块关键 DTO 如下：

| DTO             | 字段                             | 用途       |
| --------------- | -------------------------------- | ---------- |
| `UserCreateDTO` | username, password, phone, email | 注册       |
| `OAuthBindDTO`  | userId, provider, openId         | OAuth 绑定 |

File / Ai 模块关键 DTO 如下：

| DTO                | 字段                                     | 用途                     |
| ------------------ | ---------------------------------------- | ------------------------ |
| `FileOwnerDTO`     | fileId, ownerId, bizType                 | 文件归属校验（不脱敏，仅服务间） |
| `FileRefDTO`       | fileId, bizType, bizId, ownerId          | file_ref 引用登记/解除（四元组幂等） |
| `FileContentDTO`   | fileId, mime, content（byte[]→Base64）   | 机审取图字节流传输（SSRF 防护唯一入口） |
| `AiChatRequestDTO` | systemPrompt, userPrompt, temperature；计量打标：purpose（`AiPurposeEnum`：MODERATION_TEXT/MODERATION_IMAGE/SUMMARY/TAGGING/QA/OTHER，必传）、requestId（业务侧幂等键，切片×重试复用）、scene（可空） | LLM 通用对话请求 + 用途打标（防注入由调用方组织；打标设计 2026-11-09 定案、待实施，`AiPurposeEnum` 落 litisle-api `dto/ai` 包） |
| `AiChatResponseDTO`| content, model, providerTraceId          | LLM 通用对话响应（原始文本，结构校验由调用方负责） |
| `PunishStatusDTO`  | banned, muted, frozenUntil 等             | 处罚权威态快照（ReviewClient 出参） |
| `PunishAuditDTO` / `PunishSpeakCheckDTO` | — | 处罚审计 / 发言前禁言校验 |
| `UsernameRejectRecordDTO` | contentHash, hitWords, wordlibVer  | 用户名拒绝取证载荷（禁落用户名明文） |
| `ContentDTO` / `ArticleImageRefDTO` | — | 机审回查正文 / 配图引用（fileId+file_meta.id） |
| `ArticleCounterUpdateDTO` | articleId, viewCnt, likeCnt, commentCnt 等 | social 计数回写冗余列（幂等绝对值 SET） |
| `MessageDTO`       | —                                          | 私信机审回查出参 |

------

## 六、共享 VO 设计

### 6.1 设计规范

| 规范项 | 说明             |
| ------ | ---------------- |
| 命名   | `{业务}{视图}VO` |
| 包归属 | `vo/{service}/`  |
| 脱敏   | `@Sensitive`     |
| 校验   | 不加校验注解     |

当前 VO 包已独立成包（vo/user/）：`UserBriefVO`、`UserRolesVO` 均为空类占位，字段待业务接入时落地（TokenVO/OAuthAuthorizeVO 仍暂放 dto/auth）。

VO 规划示例如下：

| VO              | 字段                 | 用途         |
| --------------- | -------------------- | ------------ |
| `UserBriefVO`   | id, nickname, avatarFileId | 用户简要信息 |
| `ArticleCardVO` | id, title, coverUrl  | 文章卡片     |

------

## 七、MQ 事件 Schema 设计

### 7.1 设计规范

| 规范项 | 说明                        |
| ------ | --------------------------- |
| 命名   | `{业务}{动作}Event`         |
| 字段   | eventId, eventTime, traceId |
| 序列化 | JSON                        |
| 版本   | 预留 `version`              |

MQ 命名示例如下（moderation 模块）：

Exchange: litisle.moderation.exchange（死信：litisle.moderation.dlx，独立 Direct 类型 DLX）

Queue:    litisle.file.moderation.result.queue（死信：...dlq.queue）

Routing:  moderation.result（死信：moderation.result.dlq）

### 7.2 已实现事件清单

| 事件                           | 归属              | 发布方 | 消费方         | 状态                                             |
| ------------------------------ | ----------------- | ------ | -------------- | ------------------------------------------------ |
| `UserRegisteredEvent`          | event/user        | user   | notification   | ⚠️ 空类（Schema 公共字段 + userId/username/uid/registerSource 已在 Javadoc 约定，须事务 AFTER_COMMIT 后发布） |
| `ImageSubmitEvent`             | event/moderation  | file   | review         | ✅ 字段完整（modType/bizType/bizId/scene/contentHash/retryCount） |
| `ModerationResultEvent`        | event/moderation  | review | file/内容/社交 | ✅ 字段完整（bizType/bizId/verdict/reviewTaskId） |
| `ModerationAppealResultEvent`  | event/moderation  | review | file           | ✅ 字段完整（bizType/bizId/appealId/appealPass/reviewTaskId） |
| `TextSubmitEvent`              | event/moderation  | content/message | review | ✅ 字段完整（modType/bizType/bizId/scene/contentHash/retryCount，不含正文） |
| `PunishResultEvent`            | event/moderation  | review | auth/user      | ✅ 字段完整（userId/action/op/punishLogId/frozenUntil/retryCount，仅触发信号） |
| `ContentUnpublishedEvent`      | event/content     | content | ai/search     | ✅ 字段完整（bizType/bizId/contentHash/reason，下架失效向量库/ES） |
| `ContentPublishedEvent`        | event/content     | content | search/ai/notification | ✅ 字段完整（bizType/bizId/contentHash，发布同步索引/欢迎推送） |
| `UserBannedEvent`              | event/user        | user    | content       | ✅ 字段完整（eventId/eventTime/traceId/version + userId/reason，封禁下架连带） |

> 全部事件均含公共字段 eventId/eventTime/traceId/version，JSON 序列化（Jackson2JsonMessageConverter），禁止 @Builder。

------

## 八、共享枚举设计

### 8.1 RoleEnum 示例

READER("READER", "读者")

AUTHOR("AUTHOR", "作家")

REVIEWER("REVIEWER", "审核员")

ADMIN("ADMIN", "管理员")

SUPER_ADMIN("SUPER_ADMIN", "超级管理员")

> 注意：`RoleEnum` 与 `RoleType`（SYSTEM/BUSINESS）不同，后者已在 user 模块定义。

### 8.2 ImageAuditTriggerEnum（图片审核触发时机）

与另两枚举正交（禁合并/互相补齐）：`StorageBizTypeEnum`（file，存储维度：定 bucket/访问级别）、`ModerationBizTypeEnum`（review，审核维度：review_task.biz_type）、本枚举（触发时机维度）。

| 枚举值            | code | 说明                                     |
| ----------------- | ---- | ---------------------------------------- |
| `UPLOAD_IMMEDIATE`| 1    | 上传即审（AVATAR/COVER，无发布链路可挂） |
| `UPLOAD_PRECHECK` | 2    | 上传预审 + 发布复审（ARTICLE_IMG）       |
| `EXEMPT`          | 3    | 私有面豁免（LICENSE，仅预签名访问）      |

> 运行时按 `FileOwnerDTO.bizType`（StorageBizTypeEnum 枚举名字符串）映射；未知/空一律回退 `UPLOAD_PRECHECK`（宁严勿松：误判代价是多一次复审而非漏审）。SSOT 为 StorageBizTypeEnum 枚举名，两侧改名须同步。

------

## 九、共享常量设计

### 9.1 HeaderConstant（复用 common-core）

Header 名统一由 common-core 的 `HeaderConstant` 维护（X-User-Id / X-Roles / X-Trace-Id），litisle-api 不重复定义。

### 9.2 BizCacheKey（biz: 前缀规范）

业务缓存 Key 统一 `biz:` 前缀，与 common 层通用 Key（如 captcha:）分层：

- `biz:user:public:{userId}` — 用户公开信息缓存
- `biz:user:role:{userId}` — 用户角色缓存
- `biz:article:detail:{articleId}` — 文章详情缓存
- `biz:social:follower:count:{userId}` — 粉丝数缓存

### 9.3 ModerationConstant（审核共享契约）

- **枚举值口径**：MOD_TYPE_AUTO=1（机审）、BIZ_TYPE_IMAGE=6（图片）、VERDICT_PASS/REJECT/SUSPICIOUS=1/2/3、SCENE_IMAGE
- **Redis key 契约**（review 写 / file 读，双方禁止各自拼字符串，防 key 漂移）：
  - `review:img-blacklist:{sha256}` — 图片拒传黑名单（TTL 30 天±10% 抖动；申诉改判 PASS 时须移除）
  - `review:img-result:{provider}:{modelVersion}:{sha256}` — 图片审核结果缓存（仅终局结论，TTL 72h±10%，版本化隔离）
- **词库 Pub/快照契约**（配合 §14 DFA 共享化）：
  - `review:wordlib:change` — 词库变更广播频道（Redis Pub，fire-and-forget）
  - `review:wordlib-snapshot:{scene}` — 词库快照（WordlibSnapshot JSON，无 TTL 随版本覆盖）

------

## 十、统一错误码设计

错误码按模块分段，示例如下（SSOT 为 common-core `ErrorCode`）：

| 模块 | 区段 |
| ---- | ---- |
| 通用 | 10000–19999（10001 参数 / 10005 限流 / 10014 无权限） |
| 用户 | 20000–29999 |
| 内容 | 30000–39999（30001–30019 已落地，见 content-index §十一） |
| 社交 | 40000–49999 |
| 支付 | 50000–59999 |
| 商城 | 60000–69999 |
| 审核 | 70000–79999（70001 敏感词命中） |
| AI   | 80000–89999 |
| 文件 | 90000–99999 |
| 搜索 | 100000–109999（100001–100004 SEARCH_PARAM_INVALID / SEARCH_PAGE_EXCEEDED / SEARCH_SOURCE_NOT_FOUND / SEARCH_ES_UNAVAILABLE 已落地，2026-11-03 temp search批次B B3） |

------

## 十一、依赖关系

### 11.1 模块依赖

litisle-api

├── litisle-common-core / litisle-common-security

├── spring-cloud-starter-openfeign（provided）

├── spring-boot-autoconfigure（provided，自动装配注解）

├── spring-web

├── sensitive-word（provided，DFA 引擎，宿主服务自行声明）

├── jackson-annotations

├── jakarta.validation-api

└── lombok（provided）

### 11.2 业务服务依赖

litisle-service

├── litisle-common-*

├── litisle-api

└── litisle-file

------

## 十二、使用规范与约束

### 12.1 必须遵守

| 约束        | 说明             |
| ----------- | ---------------- |
| 通信方式    | Feign / MQ       |
| DTO/VO 定义 | 仅在 litisle-api |
| 枚举定义    | 仅在 litisle-api |
| 文件传输    | fileId           |

### 12.2 禁止做法

- 

  在 litisle-api 写业务逻辑

- 

  Feign 返回 void 且无幂等 key

- 

  跨服务传递 HttpServletRequest

------

## 十三、开发优先级

| 阶段     | 内容                    |
| -------- | ----------------------- |
| 第一阶段 | RoleEnum / UserFeignApi |
| 第二阶段 | Content / Review        |
| 第三阶段 | Social / Payment / AI   |

------

## 十四、DFA 同步闸门共享组件（dfa/ 包）

词库共享化方案：DFA 引擎与算法收口契约库，词库数据源由各宿主通过 SPI 提供，发布链路本地执行零网络。

| 组件                    | 职责                                                         |
| ----------------------- | ------------------------------------------------------------ |
| `DfaFacade`             | 门面：`initAll`（启动逐场景构建，失败 fail-fast 中断启动）/ `rebuild`（version 乐观比对防乱序 + 原子换引用，在途查询不受影响）/ `check`（HIGH 拦 70001，MID 只标记送审，未初始化抛异常绝不 default-allow） |
| `TextScanEngine`        | 扫描引擎接口（可插拔），`AcdatTextScanEngine` 为 AC 自动机+双数组 Trie 默认实现 |
| `CharNormalizer`        | 字符归一化（全角/大小写等），扫描前统一文本形态防变体绕过     |
| `DfaScenes`             | 场景归一映射：去 `_RE_REVIEW` 后缀；ARTICLE_FEATURED/ARTICLE_COMMUNITY→ARTICLE，BIO 直映自身，图片场景返回 null（不走 DFA） |
| `DfaHitResult`          | 判定结果 record（hit / maxLevel / hitWords / wordlibVer）     |
| `WordEntry`             | 词条 record（word / level 1-HIGH 2-MID / blacklist 黑白名单分流） |
| `WordLibraryLoader`     | 装载 SPI：review 查表、content/message 读 Redis 快照；加载失败回退本地持久化快照并告警，绝不 default-allow |
| `WordlibSnapshot`       | 快照 JSON 契约（version + words），禁 Java 原生反序列化       |
| `WordlibChangedMessage` | Pub 广播消息 record（scene/version/operatorId/occurTime），订阅端调 `rebuild` |

> houbb sensitive-word 为 **provided** 依赖：使用 DFA 的服务（review/content/message）自行声明，网关等不需要的服务不被污染。词库完全来自 `WordLibraryLoader`，不链 houbb 内置词库（避免与内置双词库源不一致）。

## 十五、moderation 门面组件（moderation/ 包）✅

跨服务决策口径 SSOT 收口（宿主服务经适配器接入，禁两侧各写漂移）：

| 组件 | 职责 |
| ---- | ---- |
| `SubmitTextAssembler` | 送审文本组装 `assemble(title, body)` + `contentHash`（分隔符 TEXT_SUBMIT_SEPARATOR，发布/消费两侧共用） |
| `PublishTextPrecheck` | 发布前置校验：空内容（trim+去标签可见文本）/ 标题≤100 |
| `DfaPublishGate` | DFA 闸门决策：HIGH 拦 70001、MID 只标记（decide→DfaGateDecision） |
| `UsernameRejectGate` | 注册拒绝取证编排（取证失败仅留痕，不影响拦截结论） |
| `UsernameModerationGate` | 用户名链路编排：HIGH 拦截 + 联动 `UsernameRejectGate` 取证（注册/改名共用，违规请求零写库） |
| `BioModerationGate` | 简介类文本闸门：BIO 场景词库同步快筛，HIGH 拦 70001 不取证不建审核任务；空串=清空语义直接放行 |
| `LastHashStore` | 免审 last-hash 读写删（value JSON：hash+verdict，SUSPICIOUS 禁写） |
| `LastHashExemptChecker` | 免审判定 SSOT：decide / statusExcluded / imgReReviewPassed（图片连带免审） |
| `StrangerQuotaGate` | 陌生人配额：Redis INCR 主计数，故障降级 DB 原子条件更新（上限 3） |
| `MessageRecallGate` | 私信撤回 CAS：仅本人、仅正常态可撤回 |
| `MachineRejectPunishGate` | 机审拒绝处罚统一收口：记账同事务 + afterCommit 发 punish 事件（禁双链路各写） |
| `ContentUnpublishedInvalidator` | 下架失效：text/img-result + last-hash 缓存键批量删除 |

## 十六、架构亮点总结

- 

  契约先行

- 

  同步 / 异步职责分离

- 

  枚举、常量集中管理

- 

  循环依赖通过 MQ 规避

