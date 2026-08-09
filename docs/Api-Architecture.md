

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

| 内容                   | 状态     | 位置                       |
| ---------------------- | -------- | -------------------------- |
| pom.xml                | ✅ 已配置 | pom.xml                    |
| LitisleApiApplication  | ✅ 已实现 | LitisleApiApplication.java |
| application.properties | ✅ 空配置 | application.properties     |
| feign/ 包              | ⏳ 待实现 | -                          |
| dto/ 包                | ⏳ 待实现 | -                          |
| vo/ 包                 | ⏳ 待实现 | -                          |
| event/ 包              | ⏳ 待实现 | -                          |
| enums/ 包              | ⏳ 待实现 | -                          |
| constant/ 包           | ⏳ 待实现 | -                          |
| result/ 包             | ⏳ 待实现 | -                          |

### 2.2 待修正项

**⚠️ 启动类问题**：LitisleApiApplication.java 含 `@SpringBootApplication`，但 `litisle-api` 是契约库（jar 依赖），不是独立运行的微服务。

**建议**：

- 

  删除 `LitisleApiApplication.java`

- 

  删除 `application.properties`

- 

  pom.xml 移除 `spring-boot-maven-plugin`

- 

  pom.xml 移除 `spring-boot-starter`，改为依赖 `spring-cloud-openfeign-core` + `lombok`

**⚠️ 依赖缺失**：pom.xml 未引入 Feign、Jackson、Validation、Lombok 等契约库必需依赖。

------

## 三、代码架构（包结构）

下面是 `litisle-api` 的标准包结构：

litisle-api

├── feign/

│   ├── UserFeignApi

│   ├── ContentFeignApi

│   ├── ReviewFeignApi

│   ├── AiFeignApi

│   ├── SocialFeignApi

│   ├── PaymentFeignApi

│   ├── ShopFeignApi

│   ├── FileFeignApi

│   ├── NotificationFeignApi

│   └── config/

│       └── FeignApiConfig

├── dto/

│   ├── user/

│   ├── content/

│   ├── review/

│   ├── ai/

│   ├── social/

│   ├── payment/

│   ├── shop/

│   └── file/

├── vo/

│   ├── user/

│   ├── content/

│   ├── review/

│   ├── ai/

│   ├── social/

│   ├── payment/

│   └── file/

├── event/

│   ├── content/

│   ├── review/

│   ├── user/

│   ├── social/

│   ├── payment/

│   └── notification/

├── enums/

│   ├── RoleEnum

│   ├── ZoneEnum

│   ├── RiskLevelEnum

│   ├── SceneEnum

│   ├── ArticleStatusEnum

│   ├── BookStatusEnum

│   ├── ReviewStateEnum

│   ├── OrderStatusEnum

│   ├── MessageTypeEnum

│   ├── AgentTypeEnum

│   ├── ProviderEnum

│   └── BizTypeEnum

├── constant/

│   ├── MqConstant

│   ├── CacheConstant

│   ├── HeaderConstant

│   └── FeignConstant

└── result/

└── ErrorCode

------

## 四、Feign 接口契约设计

### 4.1 设计规范

| 规范项   | 说明                                  |
| -------- | ------------------------------------- |
| 接口命名 | `{Service}FeignApi`                   |
| 服务名   | `@FeignClient(name = "litisle-user")` |
| 路径前缀 | `/api/feign/{service}`                |
| 返回值   | `Result<T>`                           |
| 入参     | 可序列化                              |
| 文件传输 | 通过 `fileId`                         |
| 幂等性   | 调用方加 `@Idempotent`                |

### 4.2 UserFeignApi（用户服务契约）

| 方法              | 路径                                         | 入参            | 出参          | 说明           |
| ----------------- | -------------------------------------------- | --------------- | ------------- | -------------- |
| `queryById`       | GET `/api/feign/user/{id}`                   | id              | `UserBriefVO` | 查用户简要信息 |
| `queryByUsername` | GET `/api/feign/user/username/{username}`    | username        | `UserBriefVO` | 登录查账号     |
| `queryByPhone`    | GET `/api/feign/user/phone/{phone}`          | phone           | `UserBriefVO` | 手机号查询     |
| `createUser`      | POST `/api/feign/user`                       | `UserCreateDTO` | Long          | 注册用户       |
| `queryRoles`      | GET `/api/feign/user/{id}/roles`             | id              | `UserRolesVO` | 查角色         |
| `bindRole`        | POST `/api/feign/user/{id}/roles/{roleCode}` | id, roleCode    | Void          | 绑定角色       |
| `cancelAccount`   | POST `/api/feign/user/{id}/cancel`           | id              | Void          | 注销账号       |

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

### 9.1 HeaderConstant 示例

X-User-Id

X-Roles

X-Trace-Id

### 9.2 CacheConstant 示例

user:role:

article:hot:

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

