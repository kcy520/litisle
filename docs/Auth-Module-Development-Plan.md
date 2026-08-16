好的，我帮你把 **从 3.1 开始错位、混排的内容全部重新梳理**，严格区分「说明文字」「表格」「代码块」，保持原意不变，仅修正排版与结构。下面是整理后的完整版本（3.1 及之后部分已重排，前面部分保持你原有结构未动）：

------

## 三、Auth 模块开发优先级与依赖关系

### 3.1 功能依赖图（文字版）

```
1. 密码登录 (passwordLogin)
        │
        ▼
2. 注册 (register) ─────────┐
        │                    │
        ▼                    ▼
3. 邮箱验证码登录           4. 退出登录 (logout)
        │                    │
        └────── 共用 ─────────┘
               │
               ▼
        5. 刷新令牌 (refreshToken)
               │
               ▼
        6. 当前登录用户 (currentLoginUser)
```

> 说明：
>
> - 
>
>   1 / 2 / 3 都是「登录态生成」的前置能力
>
> - 
>
>   4 / 5 依赖 Token 黑名单机制
>
> - 
>
>   6 依赖 `SecurityContextHolder`

------

### 3.2 各功能实现步骤拆解

#### ① 密码登录（最高优先级）

1. 

   校验参数（用户名、密码、图形验证码）

2. 

   调用 `UserClient.getUserByUsername(username)` 获取用户

3. 

   调用 `UserClient.getUserRoles(userId)` 获取角色

4. 

   校验账号状态（封禁 / 禁用 / 注销）

5. 

   BCrypt 校验密码

   `passwordCrypto.matches(rawPassword, user.getPassword())`

6. 

   校验失败：

   - 

     记录失败次数（Redis）

   - 

     达到阈值后临时锁定

7. 

   校验成功：

   - 

     清除失败次数

   - 

     生成 JWT（包含 `userId / username / roles / jti`）

   - 

     可选：生成 Refresh Token

8. 

   构建 `TokenVO` 返回

------

#### ② 注册（依赖 User Feign）

1. 

   校验图形验证码（Redis 读取并立即删除）

2. 

   校验邮箱验证码（Redis 读取并立即删除）

3. 

   校验用户名唯一性 → `UserClient.getUserByUsername`

4. 

   校验邮箱唯一性 → `UserClient.getUserByEmail`

5. 

   密码 BCrypt 加密

   `passwordCrypto.encode(rawPassword)`

6. 

   调用 `UserClient.createUser()` 创建用户

7. 

   调用 `UserClient.assignRole(userId, "READER")`

8. 

   清理 Redis 中的临时验证码（已在第 1/2 步完成）

9. 

   签发 JWT，返回 `TokenVO`（注册后自动登录）

------

#### ③ 邮箱验证码登录

1. 

   校验邮箱验证码（Redis 读取并删除）

2. 

   调用 `UserClient.getUserByEmail(email)`

3. 

   用户不存在 → 自动注册流程：

   - 

     生成默认用户名（email 前缀 + 随机后缀）

   - 

     生成随机密码（仅占位，不用于登录）

   - 

     调用 `UserClient.createUser()`

   - 

     分配 `READER` 角色

4. 

   用户存在 → 校验账号状态

5. 

   调用 `UserClient.getUserRoles(userId)`

6. 

   签发 JWT，返回 `TokenVO`

------

#### ④ 退出登录（依赖黑名单）

1. 

   从 `Authorization` Header 提取 Token

2. 

   解析 JWT，获取 `jti` 与剩余有效期 `ttl`

3. 

   调用 `TokenBlacklistService.addToBlacklist(jti, ttl)`

4. 

   如 Refresh Token 存储在 Redis，一并删除

5. 

   返回成功

------

#### ⑤ 刷新令牌（Refresh Token 轮换）

1. 

   校验 Refresh Token（JWT 解析）

2. 

   检查是否在黑名单中

3. 

   生成新的 Access Token（新 `jti`）

4. 

   生成新的 Refresh Token

5. 

   旧 Refresh Token 加入黑名单

6. 

   返回新的 `TokenVO`

------

#### ⑥ 当前登录用户

1. 

   从 `SecurityContextHolder` 获取：

   - 

     `userId`

   - 

     `username`

   - 

     `roles`

   - 

     `tokenId`

2. 

   可选：调用 `UserClient` 补充 email / avatar 等信息

3. 

   构建 `LoginUserInfoVO` 返回

------

### 3.3 Auth 模块推荐包结构

```
litisle-auth/
└── src/main/java/com/litisle/litisleauth/
    ├── controller/
    │   ├── AuthController.java        # 注册/登录/退出/刷新/me
    │   └── EmailController.java       # 发送邮箱验证码（补全）
    ├── service/
    │   ├── AuthService.java
    │   ├── impl/
    │   │   └── AuthServiceImpl.java
    │   ├── CaptchaService.java
    │   ├── EmailCodeService.java
    │   └── TokenBlacklistService.java
    ├── domain/
    │   ├── dto/
    │   │   ├── request/
    │   │   │   ├── RegisterRequestDTO.java
    │   │   │   ├── PasswordLoginDTO.java
    │   │   │   ├── EmailLoginDTO.java
    │   │   │   └── RefreshTokenDTO.java
    │   │   └── response/
    │   │       ├── TokenVO.java
    │   │       ├── LoginUserInfoVO.java
    │   │       └── CaptchaVO.java
    ├── redis/
    │   └── TokenBlacklistService.java
    ├── enums/
    │   └── （已有 EmailCodeUsage / EmailRedisKey）
    └── config/
        └── AuthConfig.java
```

> ⚠️ 说明：原文档中 `vo/` 与 `response/` 重复，统一收敛到 `response` 包，避免歧义。

------

### 3.4 开发推进建议（最终结论）

如果你准备开始落地，我建议严格按下面顺序推进（每一步都可单独验证）：

| 阶段       | 内容                                                    | 是否阻塞后续 |
| ---------- | ------------------------------------------------------- | ------------ |
| ✅ 准备阶段 | 补全 `litisle-api` + `litisle-user` 的 Feign 接口 & DTO | 是           |
| ①          | Token 黑名单服务（Redis + Redisson）                    | 是           |
| ②          | 密码登录                                                | 是           |
| ③          | 注册                                                    | 是           |
| ④          | 邮箱验证码登录                                          | 否           |
| ⑤          | 退出 / 刷新                                             | 否           |
| ⑥          | 当前登录用户                                            | 否           |

------

### 3.5 你现在可以直接做的决定

你可以直接告诉我以下之一，我就按你选的 **精确生成代码文件**：

1. 

   ✅「**先补全 User 模块的 Feign + 服务层（准备阶段）**」

2. 

   ✅「**直接写 Token 黑名单服务**」

3. 

   ✅「**直接写密码登录（AuthController + AuthServiceImpl）**」

4. 

   ✅「**按阶段 1→6 全部自动推进，你只负责确认**」

> 如果你愿意，下一步我可以直接给你 **“准备阶段” 的完整代码清单（含文件级 diff）**，这样你复制粘贴就能跑。你选哪个？

------

需要我把「3.1 之前的章节」也顺便帮你做一次**格式统一 + 表头对齐 + 标题层级规范化**吗？这样整份文档可以直接当正式设计文档入库。
