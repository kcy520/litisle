
```markdown e:\NewJavaPro\LitIsle\docs\next_update.md
# 【待办】litisle-user 模块配套修改清单（phone_hash/email_hash 落地）

> 背景：sys_user 新增 `phone_hash`（not null）/ `email_hash`（null）列，
> 唯一索引改为 `uk_phone_hash_del(phone_hash, del_flag)` / `uk_email_hash_del(email_hash, del_flag)`；
> phone/email 密文列只管存储（AES-GCM 随机 IV，密文不可比对），
> 查重/唯一判断一律走 HMAC-SHA256 确定性摘要列（HmacSha256Crypto.digest）。
> 优先级：P0 功能失效 > P1 密文外泄 > P2 语义/一致性。

## 🔴 P0 —— 功能直接失效（必须改）

### 1. User 实体补 hash 字段
- 文件：`domain/entity/User.java`
- 问题：DDL 新增 `phone_hash` / `email_hash` 列，实体无对应字段，MyBatis-Plus 无法读写
- 改动：新增两个字段
  ```java
  /** 手机号 HMAC-SHA256 摘要（唯一索引与查重走本列） */
  @TableField("phone_hash")
  private String phoneHash;

  /** 邮箱 HMAC-SHA256 摘要（同上） */
  @TableField("email_hash")
  private String emailHash;
  ```

### 2. 注册链路：查重失效 + 写库必报错
- 文件：`controller/feign/UserFeignProviderController.java` → `createUser`
- 问题 a（查重失效）：`eq(User::getPhone, encryptedPhone)` 用「加密后密文」比对，
  AES-GCM 随机 IV 下同一手机号每次密文不同 → count 永远 = 0，重复手机号/邮箱可无限注册
- 问题 b（写库报错）：`phone_hash` 为 not null 且无默认值，`new User()` 不写该列 → 注册直接 SQL 异常
- 改动：
  - 手机号查重改：`eq(User::getPhoneHash, hmacSha256Crypto.digest(dto.getPhone()))`
  - 邮箱查重改：`eq(User::getEmailHash, hmacSha256Crypto.digest(dto.getEmail()))`
  - 保存时同步写入：`setPhoneHash(...)`；email 非空时 `setEmailHash(...)`
  - 需注入 `HmacSha256Crypto`（当前只注入了 `Aes256GcmCrypto`）

### 3. 登录链路：手机号/邮箱登录全部失效
- 文件：`controller/feign/UserFeignProviderController.java` → `findByAccount`（按手机号分支）、`findByEmail`
- 问题：同样用「加密后密文」查库，随机 IV 下永远查不到用户 → 手机号/邮箱登录 100% 失败
- 改动：查询条件全部切到 hash 列等值匹配（命中 uk 索引，免全表解密比对）

### 4. 邮箱绑定/换绑：查重失效 + hash 未同步
- 文件：`service/impl/UserProfileServiceImpl.java` → `bindEmail` / `updateEmail`
- 问题：`eq(User::getEmail, encryptedEmail)` 查重失效；写入只更新密文列，`email_hash` 不刷新
- 改动：
  - 查重改走 `eq(User::getEmailHash, digest(...))`
  - 换绑成功时 `email_hash` 必须随密文同一条 UPDATE 更新（防「密文 A + hash B」脏数据）
  - 注入 `HmacSha256Crypto`

## 🟠 P1 —— 密文外泄到前端

### 5. 两处把 AES 密文当明文返回
- 位置 a：`UserProfileServiceImpl.currentUserProfile` → `UserSelfVO.phone`
  现状：直接返回 `user.getPhone()`（密文）
  改动：解密后脱敏（138****1234），符合「敏感字段不回显明文，走安全通道」规范
- 位置 b：`UserFeignProviderController.buildAuthUserDTO` → `setPhone/setEmail`
  现状：密文跨服务传输；下游 `AuthServiceImpl` 已注释掉 `desensitizePhone/Email`，密文会原样到前端
  改动：解密 + 脱敏后传输；顺手把 `setPassword(user.getPassword())` 置空（BCrypt 哈希无回传必要）

## 🟡 P2 —— 语义/一致性

### 6. 注销冷却期清理走 hash 列
- 依据：SQL 设计说明承诺「重新注册前先清理旧的 (phone_hash, 1) 记录」
- 现状：注销主流程（del_flag=1 + cancel_time）未在 UserCancelLogServiceImpl 中体现，需确认位置
- 改动：落地时冷却期/换绑判断全部从 `phone` 切到 `phone_hash`

### 7. 换绑后旧 hash 同步刷新
- 与第 4 条合并落地：updateEmail 的「新旧邮箱解密比对」逻辑本身正确，保留；
  仅需保证 hash 与密文原子更新

## 🔵 附带发现（与 hash 改造无关但同表）

### 8. 实体有 uid 字段，DDL 没有 uid 列
- 现状：`User.java` 有 `@TableField("uid")`，`searchByUid`、`generateUniqueUid` 在用，
  注释提到 `uk_uid` 唯一索引；但 sys_user 建表语句无 `uid` 列 → 运行时 SQL 报错
- 改动：SQL 文档 sys_user 补列
  `uid varchar(16) not null comment '对外UID（用户间搜索唯一标识，随机生成，永久不变）'`
  + `unique key uk_uid(uid)`（注册时回填需兼容存量数据）

## 修改范围汇总

| # | 文件 | 优先级 |
|---|---|---|
| 1 | User.java | P0 |
| 2 | UserFeignProviderController.createUser | P0 |
| 3 | UserFeignProviderController.findByAccount/findByEmail | P0 |
| 4 | UserProfileServiceImpl.bindEmail/updateEmail | P0 |
| 5 | UserProfileServiceImpl.currentUserProfile + buildAuthUserDTO | P1 |
| 6-7 | 注销/换绑 hash 一致性 | P2 |
| 8 | Sql-Document.md sys_user 补 uid 列 | P2 |

建议落地顺序：1 → 2 → 3 → 4 → 5 → 6/7 → 8（先实体后链路，每步可独立编译验证）
```

**说明**：内容与上一轮扫描结论一致，按优先级重排并补充了「修改范围汇总表」和「建议落地顺序」，方便你后续逐项勾销。等你粘贴保存后，告诉我是否按 1→4 顺序开始出 diff。

---

# 【待办·补充】litisle-api / litisle-auth / litisle-gateway 模块扫描结果

> 前提：hash 列（phone_hash/email_hash）是 user 模块**内部实现细节**，不出服务边界，
> 所以 api/auth/gateway 不需要 hash 字段本身；需要改的是「明文日志、密文透传、契约语义」三件事。

## litisle-api（共享契约模块）

### 9. AuthUserDTO 跨服务契约语义标注【P2】
- 文件：`litisle-api/.../dto/user/AuthUserDTO.java`（phone L37 / email L40 / password 字段）
- 问题：该 DTO 是 user → auth 的传输载体，修复后 phone/email 应为**脱敏值**，
  password 仅登录校验链路使用；当前无任何语义标注，下游易误用为明文
- 改动：字段 javadoc 标注「脱敏值，禁止明文/密文传输」；password 字段建议标注
  「仅密码登录校验链路填充，其他场景必须为 null」或直接评估移除

### 10. findByEmail 明文邮箱进 URL 路径【P2】
- 文件：`litisle-api/.../feign/UserClient.java` L56-57
- 现状：`GET /api/feign/user/email/{email}` 明文邮箱拼在 URL 中 → 进网关/服务访问日志
- 影响面：网关路由表无 `/api/feign/**`，外部不可达；风险限于内部日志留痕
- 改动：改为 `POST` + body 或 `@RequestParam`（内部 Feign 无历史兼容包袱，改造成本低）

## litisle-auth

### 11. 多处日志明文打印邮箱【P1·合规】
- 位置：
  - `AuthServiceImpl.register` L57：`log.info("用户注册请求: username={}, email={}")`
  - `AuthServiceImpl.emailLogin` L129 / L137
  - `CaptchaServiceImpl.sendEmailCode` L95
  - `AuthFeignProviderController.verifyChangeEmail` L37
- 问题：违反「敏感数据不落日志」规范（与 @AuditLog saveRequestData=false 的合规要求同源）
- 改动：统一脱敏工具（如 `a***@domain.com`）或仅打 userId/长度；
  建议放 common-core 的 DesensitizeUtil 供全项目复用

### 12. 脱敏职责归属拍板（与 user 清单 #5 联动）【P1·需拍板】
- 位置：`AuthServiceImpl` L249-252（desensitizePhone/Email 被注释，直接透传）、
  L374+（脱敏方法体被注释）
- 现状：authUser.getPhone() 是密文 → 原样进 LoginUserInfoVO → 前端
- 方案二选一（避免双重脱敏）：
  - **A（推荐）**：脱敏只在 user 端 buildAuthUserDTO 做一次（清单 #5b），auth 端维持透传，
    删除/保留注释均可——单一职责，auth 拿到的已是脱敏值
  - **B**：user 端只解密不脱敏，auth 端恢复 L249-252 注释代码做脱敏——但意味着
    明文/密文仍跨服务传输，违背「敏感数据不出安全通道」原则
- 拍板 A 后：L374 起的注释方法建议删除（---------建议删除此注释，请手动确认）

### 13. BindPhoneDTO 已定义未使用【P2·预留提示】
- 位置：`litisle-auth/.../domain/dto/BindPhoneDTO.java`（全模块无引用，按规则保留不删）
- 提示：未来实现绑定手机号时，必须复用 bindEmail 模式：
  AES 密文落 phone 列 + HMAC 摘要同步写 phone_hash + 查重走 hash 列；
  禁止明文落库或密文比对（正是本轮整改消灭的两类问题）

## litisle-gateway

### 14. 无需修改【结论】
- 网关无 phone/email/crypto 处理逻辑
- 匿名白名单（L119-139）均为正常公开接口，未包含任何 feign 内部路径
- `/api/feign/**` 不在任何路由表中 → 内部接口外部不可达，无暴露面
- 唯一相关条目 `/api/auth/captcha/email` 是验证码发送接口，与本次改造无关

## 补充修改范围汇总

| # | 模块 | 文件 | 优先级 |
|---|---|---|---|
| 9 | litisle-api | AuthUserDTO 契约注释 | P2 |
| 10 | litisle-api | UserClient.findByEmail 改 POST | P2 |
| 11 | litisle-auth | AuthServiceImpl/CaptchaServiceImpl/AuthFeignProviderController 日志脱敏 | P1 |
| 12 | litisle-auth | AuthServiceImpl 脱敏归属拍板（联动 #5） | P1 |
| 13 | litisle-auth | BindPhoneDTO（预留，暂不改） | P2 |
| 14 | litisle-gateway | 无需修改 | — |

补充落地顺序建议：#11/#12 与 user 清单 #5 同批改（同一条链路），
#9/#10 随手改，#13 留待绑定手机号功能立项时再看。

---

# 【已完成】落地情况闭环记录（2026-09-20）

> 上方清单正文保留作为历史依据；其中 #9/#12 的「脱敏值/方案A/B」描述为拍板前草案，
> 最终落地架构以本节为准。

## 最终脱敏架构（与项目既有 @Sensitive 注解体系对齐）

```
DB 密文列 --解密--> 明文（仅限内网 Feign / 服务内传递）
                        │
                        ▼
              VO 字段 @Sensitive(PHONE/EMAIL)（既有注解，零改动）
                        │
                        ▼
              Jackson 序列化时自动脱敏 → 前端只见 138****8888
```

- DTO/VO 字段传解密明文，不做手动 mask；对外响应由 VO 注解统一脱敏
- 日志脱敏直接调用既有 `SensitiveStrategy.EMAIL.desensitize(...)`，不新造工具类

## 逐项状态

| # | 状态 | 备注 |
|---|---|---|
| 1 | ✅ | User.java 补 phoneHash/emailHash 字段 |
| 2 | ✅ | 注册查重走 hash + 写库同步写 hash（含 del_flag 复合索引语义） |
| 3 | ✅ | 手机号/邮箱登录全部切 hash 列等值查询 |
| 4 | ✅ | bindEmail/updateEmail 查重走 email_hash，hash 与密文同条 UPDATE |
| 5 | ✅ | 传解密明文，VO @Sensitive 注解自动脱敏（替代原「手动 mask」草案） |
| 6 | ✅ | 查重补 del_flag 条件；自查注销功能未立项（控制器整体注释），管理端注销已正确写状态，冷却期判断留待立项时按 phone_hash 落地 |
| 7 | ✅ | 并入 #4 原子更新 |
| 8 | ✅ | sys_user 补 uid 列 + uk_uid + 6 条种子 INSERT 全部带 uid |
| 9 | ✅ | AuthUserDTO 契约改为「明文仅限内网 + VO 注解脱敏，禁止 DTO 透传前端」 |
| 10 | ✅ | findByEmail 改 POST+body（实现方签名不变，无需改 impl/fallback） |
| 11 | ✅ | 5 处日志改用 SensitiveStrategy.EMAIL.desensitize() |
| 12 | ✅ | 脱敏职责归属 VO 注解（替代原方案A/B草案），注释方法已标注待手动确认 |
| 13 | 预留 | BindPhoneDTO 保留，绑定手机号立项时按「密文+hash 同步写+hash 查重」实现 |
| 14 | ✅ | 网关无需修改 |

## 遗留事项（已拍板忽略/待后续）

- 忽略清理项：DesensitizeUtil（若已创建）、残留 import、AuthServiceImpl 底部注释方法、
  RegisterRequestDTO 的 // @NotBlank 注释 —— 均保留，不做处理
- 种子用户 hash 为 SHA2 占位值，与应用 HMAC 不一致（已知局限，见 Sql-Document 注释块），
  种子账号不可走手机号/邮箱登录
- 建议执行编译验证：`mvn compile -pl litisle-user,litisle-auth,litisle-api -am`