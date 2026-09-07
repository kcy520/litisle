

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
| pom.xml         | ✅   | 纯 jar 契约库（无 boot 插件/启动依赖）           |
| 启动类/配置文件 | ✅   | 已删除                                           |
| feign/ 包       | ✅   | UserClient / AuthClient（含 fallback 降级）      |
| config/ 包      | ✅   | FeignApiConfig（超时/重试统一配置）              |
| fallback/ 包    | ✅   | AuthClientFallbackFactory / UserClientFallbackFactory |
| dto/ 包         | ✅   | auth / captcha / user 子包                       |
| vo/ 包          | ⚠️   | 未独立成包（TokenVO/OAuthAuthorizeVO 暂放 dto/auth） |
| event/ 包       | ⚠️   | 仅 UserRegisteredEvent（空类，字段待补）         |
| enums/ 包       | ⚠️   | 仅 RegisterSourceEnum，其余共享枚举待实现        |
| constant/ 包    | ✅   | MqConstant / BizCacheKey / FeignConstant         |
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

│   ├── ContentFeignApi       # ⏳ 待实现

│   ├── ReviewFeignApi / AiFeignApi / SocialFeignApi / PaymentFeignApi / ShopFeignApi / FileFeignApi / NotificationFeignApi  # ⏳ 待实现

│   └── ...

├── config/

│   └── FeignApiConfig        # ✅ 超时/重试统一配置

├── fallback/

│   ├── AuthClientFallbackFactory   # ✅

│   └── UserClientFallbackFactory   # ✅（UserClient 当前未启用，待统一）

├── dto/

│   ├── auth/                 # ✅ RegisterUserDTO 等（TokenVO/OAuthAuthorizeVO 暂放此处）

│   ├── captcha/              # ✅

│   ├── user/                 # ✅

│   └── content/ review/ ...  # ⏳ 待实现

├── event/

│   └── user/UserRegisteredEvent  # ⚠️ 空类，eventId/eventTime/traceId 字段待补

├── enums/

│   └── RegisterSourceEnum    # ✅ WEB/APP/MINI_PROGRAM（其余共享枚举待实现）

└── constant/

    ├── MqConstant            # ✅

    ├── BizCacheKey           # ✅ biz: 前缀业务缓存 Key

    └── FeignConstant         # ✅ 服务名常量（Header 名复用 common-core HeaderConstant）

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
| 文件传输     | 通过 `fileId`                                           |
| 幂等性       | 调用方加 `@Idempotent`                                  |

> **降级语义**：提供方业务异常 → 全局异常处理器转 `Result.fail` → Feign 正常解码，**不触发降级**；网络层失败 / 5xx → 触发 `XxxClientFallbackFactory`，降级方法内抛 `BizException` 穿透到调用方，由调用方全局异常处理器兜底。

### 4.2 UserClient（用户服务契约，由 litisle-user 实现提供方）

| 方法              | 路径                                    | 入参              | 出参          | 说明                                                |
| ----------------- | --------------------------------------- | ----------------- | ------------- | --------------------------------------------------- |
| `getUserById`     | GET `/api/feign/user/{userId}`          | userId            | `UserDTO`     | 查用户基本信息                                      |
| `createUser`      | POST `/api/feign/user`                  | `RegisterUserDTO` | `AuthUserDTO` | 注册：唯一性校验 + AES 加密手机/邮箱 + 分配 READER  |
| `findByAccount`   | GET `/api/feign/user/account/{account}` | account           | `AuthUserDTO` | 兼容用户名/手机号/邮箱，返回认证聚合信息（含密码哈希、状态、角色、权限） |
| `findByEmail`     | GET `/api/feign/user/email/{email}`     | email             | `AuthUserDTO` | 邮箱验证码登录查账号                                |
| `getAuthUserInfo` | GET `/api/feign/user/auth/{userId}`     | userId            | `AuthUserDTO` | `/auth/me` 用：角色、权限、作家扩展资料             |

> OAuth 相关契约（`findByOAuth` / `createUserByOAuth` / `bindOAuth` / `unbindOAuth`）已预留注释，暂不实现。

### 4.3 AuthClient（认证服务契约，由 litisle-auth 实现提供方）

| 方法                | 路径                                          | 入参                    | 出参 | 说明                                                         |
| ------------------- | --------------------------------------------- | ----------------------- | ---- | ------------------------------------------------------------ |
| `forceLogout`       | DELETE `/api/feign/user/{userId}/sessions`    | userId                  | Void | 强制下线全部在线会话（Sa-Token 会话 + 设备记录 + refreshToken），user 管理员注销/封号时调用 |
| `verifyChangeEmail` | POST `/api/feign/captcha/change-email/verify` | `ChangeEmailVerifyDTO`  | Void | 改绑邮箱双码校验（图形码 → 邮箱码，均一次性消费，防爆破）    |

> **调用方向变更**：auth ⇄ user 为**双向 Feign**——auth 调 `UserClient` 查/建用户；user 调 `AuthClient` 强制下线/验码。

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

------

## 六、共享 VO 设计

### 6.1 设计规范

| 规范项 | 说明             |
| ------ | ---------------- |
| 命名   | `{业务}{视图}VO` |
| 包归属 | `vo/{service}/`  |
| 脱敏   | `@Sensitive`     |
| 校验   | 不加校验注解     |

关键 VO 示例如下：

| VO              | 字段                 | 用途         |
| --------------- | -------------------- | ------------ |
| `UserBriefVO`   | id, nickname, avatar | 用户简要信息 |
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

MQ 命名示例如下：

Exchange: litisle.content.exchange

Queue:    litisle.ai.queue.article.published

Routing:  content.article.published

------

## 八、共享枚举设计

### 8.1 RoleEnum 示例

READER("READER", "读者")

AUTHOR("AUTHOR", "作家")

REVIEWER("REVIEWER", "审核员")

ADMIN("ADMIN", "管理员")

SUPER_ADMIN("SUPER_ADMIN", "超级管理员")

> 注意：`RoleEnum` 与 `RoleType`（SYSTEM/BUSINESS）不同，后者已在 user 模块定义。

------

## 九、共享常量设计

### 9.1 HeaderConstant（复用 common-core）

Header 名统一由 common-core 的 `HeaderConstant` 维护（X-User-Id / X-Roles / X-Trace-Id），litisle-api 不重复定义。

### 9.2 BizCacheKey（biz: 前缀规范）

业务缓存 Key 统一 `biz:` 前缀，与 common 层通用 Key（如 captcha:）分层：

- `biz:user:role:{userId}` — 用户角色缓存
- 内容类 Key（如 article:hot:）待 content 模块落地时补充

------

## 十、统一错误码设计

错误码按模块分段，示例如下：

| 模块 | 区间        |
| ---- | ----------- |
| 通用 | 10000–19999 |
| 用户 | 20000–29999 |
| 认证 | 30000–39999 |
| 内容 | 40000–49999 |
| 支付 | 80000–89999 |

------

## 十一、依赖关系

### 11.1 模块依赖

litisle-api

├── litisle-common-core

├── spring-cloud-openfeign

├── jackson-annotations

├── jakarta.validation-api

└── lombok

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

## 十四、架构亮点总结

- 

  契约先行

- 

  同步 / 异步职责分离

- 

  枚举、常量集中管理

- 

  循环依赖通过 MQ 规避

