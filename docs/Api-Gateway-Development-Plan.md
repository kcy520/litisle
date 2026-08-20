e:\NewJavaPro\LitIsle\docs\Api-Gateway-Implementation-Checklist.md
# 网关与 API 模块基础设施开发清单

> **文档定位**：仅覆盖**基础设施**（契约库骨架 + 网关能力），各模块详细业务代码暂缓开发。
> **版本约束**：Spring Boot 3.5.15 / Spring Cloud 2025.0.3 / Spring Cloud Alibaba 2025.0.0.0 / Sa-Token 1.45.0 / Java 25
> **图例**：⬜ 待办 ｜ ✅ 已有（无需开发） ｜ ⏳ 进行中

---

## 一、litisle-api 模块（基础设施）

### 1.1 契约基础（P0）





- [ ] **A4 实现 FeignConstant**
  - 文件：`litisle-api/.../constant/FeignConstant.java`（填充空壳）
  - 要点：Feign 连接/读取超时、重试次数、上下文透传 Header 名（复用 common-core HeaderConstant）
  - 前置：无

- [ ] **A5 实现 MqConstant**
  - 文件：`litisle-api/.../constant/MqConstant.java`（填充空壳）
  - 要点：Exchange/Queue/RoutingKey 命名规范，如 `litisle.content.exchange` / `content.article.published`
  - 前置：无

- [ ] **A6 实现 BizCacheKey**
  - 文件：`litisle-api/.../constant/BizCacheKey.java`（填充空壳）
  - 要点：**业务跨服务**缓存 Key 前缀（`user:role:`、`article:hot:`），与 common-redis 技术基建层区分
  - 前置：无

- [ ] **A7 实现 FeignApiConfig**
  - 文件：`litisle-api/.../config/FeignApiConfig.java`（填充空壳）
  - 要点：Feign 日志级别（BASIC）、压缩、契约约定
  - 前置：无

### 1.2 User 契约（P0，支撑 auth 模块）

- [ ] **A8 扩展 UserClient**
  - 文件：`litisle-api/.../feign/UserClient.java`（修改）
  - 要点：补充 `queryByUsername` / `queryByPhone` / `createUser` / `queryRoles` / `bindRole` / `cancelAccount`，路径前缀 `/api/feign/user`
  - 前置：A3、A7

- [ ] **A9 启用 OAuth 契约**（暂时不管Oauth）
  - 文件：`litisle-api/.../feign/UserClient.java`（修改）
  - 要点：启用被注释的 `findByOAuth` / `createUserByOAuth` / `bindOAuth` / `unbindOAuth`（支撑 auth 第三方登录）
  - 前置：A8

- [ ] **A10 实现 UserRegisteredEvent**
  - 文件：`litisle-api/.../event/user/UserRegisteredEvent.java`（填充空壳）
  - 要点：eventId / eventTime / traceId / version 基础字段
  - 前置：A5

### 1.3 业务契约（P1-P2，**暂缓**，非基础设施）

> 以下为各业务服务的 Feign 契约，待对应业务模块开发时再建，当前**不开发**。

- [ ] ContentFeignApi / ReviewFeignApi（文章、审核）
- [ ] SocialFeignApi / PaymentFeignApi / ShopFeignApi / AiFeignApi
- [ ] 业务枚举：ArticleStatusEnum / ReviewStateEnum / OrderStatusEnum / ProviderEnum 等

---

## 二、litisle-gateway 模块（基础设施）

### P0（安全必需）

- [ ] **G1 用户上下文透传**
  - 文件：`SaTokenGatewayConfig.java`（修改）
  - 要点：认证成功后，通过 `StpUtil.getLoginId()` 读取用户信息，注入 `X-User-Id` / `X-Roles` / `X-User-Permissions` 请求头；下游经 common-security `FeignAuthInterceptor` 继续透传
  - 前置：无

- [ ] **G2 敏感 Header 过滤**
  - 文件：新建 `SensitiveHeaderFilter.java`（或并入 G1）
  - 要点：**移除客户端伪造的 X-User-Id / X-Roles / X-User-Permissions**，防止提权
  - 前置：G1

### P1（可观测性）

- [ ] **G3 CORS 跨域**
  - 文件：新建 `CorsConfig.java`
  - 要点：`CorsWebFilter`，白名单从配置读取
  - 前置：无

- [ ] **G4 请求日志**
  - 文件：新建 `RequestLogGlobalFilter.java`
  - 要点：traceId 生成 → 写入 MDC + `X-Trace-Id` Header；记录 userId / 方法 / 路径 / 入参摘要 / 响应状态 / 耗时；异步写入
  - 前置：无

### P2（高可用）

- [ ] **G5 Sentinel 限流熔断**
  - 文件：`pom.xml` + 新建配置
  - 要点：引入 `spring-cloud-starter-alibaba-sentinel`，**WebFlux 适配**；规则代码内联 + Nacos 动态下发（无 Dashboard）；429 统一响应
  - 前置：无

- [ ] **G6 多端登录校验**
  - 文件：`application.yml`（sa-token 配置）
  - 要点：`is-concurrent` / `max-login-count` 配置，超限挤下线
  - 前置：无

### P3（增强功能）

- [ ] **G7 WebSocket 透传**
  - 文件：`application.yml`（路由）
  - 要点：`lb:ws://litisle-notification`，处理握手鉴权
  - 前置：无

- [ ] **G8 Knife4j 文档聚合**
  - 文件：`pom.xml` + 配置
  - 要点：knife4j-gateway 聚合各服务文档，统一入口
  - 前置：无

---

## 三、附：user 模块 api 代码模版

> 以下展示 Feign 契约的**完整链路**（api 定义 → user 实现 → 服务层），作为后续各业务模块 Feign 开发的模版。

### 3.1 api 模块：定义 Feign 接口（UserClient）

```java
package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.user.UserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户服务 Feign 客户端（跨服务契约）
 *
 * 【设计说明】
 * 1. 只定义接口，不写实现 —— 实现由 user 模块的 FeignProviderController 完成
 * 2. 路径前缀统一 /api/feign/{service}，与业务 Controller 的 /api/{service} 区分
 * 3. 返回值统一 Result<T>，调用方通过 Result 判断成功/失败
 * 4. configuration 指定 Feign 全局配置（日志/压缩/契约）
 *
 * 【调用方】auth 模块（登录/注册/第三方授权）、其他业务服务
 */
@FeignClient(
        name = "litisle-user",
        configuration = FeignApiConfig.class
        // fallbackFactory = UserClientFallbackFactory.class  // 降级工厂，可选
)
public interface UserClient {

    /**
     * 根据用户ID查询用户
     *
     * @param userId 用户ID
     * @return 用户 DTO（含敏感字段，仅服务间内部使用）
     */
    @GetMapping("/api/feign/user/{userId}")
    Result<UserDTO> getUserById(@PathVariable("userId") Long userId);
}
```

### 3.2 user 模块：实现契约（UserFeignProviderController）

```java
package com.litisle.litisleuser.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.user.UserDTO;
import com.litisle.litisleapi.feign.UserClient;
import com.litisle.litisleuser.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feign 契约实现（Provider 端）
 *
 * 【设计说明】
 * 1. implements UserClient —— 路径由接口上的 @GetMapping 决定，实现类无需重复标注
 * 2. 只做"参数校验 + 调用 Service + 包装 Result"，不写业务逻辑
 * 3. 与业务 Controller（/api/user/**）分离，Feign 接口不暴露给外部客户端
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class UserFeignProviderController implements UserClient {

    private final UserService userService;

    @Override
    public Result<UserDTO> getUserById(Long userId) {
        log.info("远程api调用getUserById userId:{}", userId);
        UserDTO dto = userService.getDtoById(userId);
        return Result.success(dto);
    }
}
```

### 3.3 user 模块：服务层（UserService）

```java
package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleapi.dto.user.UserDTO;
import com.litisle.litisleuser.domain.entity.User;
import org.springframework.transaction.annotation.Transactional;

/**
 * 用户服务接口
 *
 * 【设计说明】
 * 1. 继承 MyBatis-Plus IService，获得通用 CRUD
 * 2. 类级 @Transactional —— 服务内所有方法默认开启事务
 * 3. getDtoById 专供 Feign 使用，返回 DTO 而非实体（避免暴露内部字段）
 */
@Transactional(rollbackFor = Exception.class)
public interface UserService extends IService<User> {

    /**
     * 查询用户 DTO（供 Feign 使用）
     *
     * @param userId 用户ID
     * @return 用户 DTO
     */
    UserDTO getDtoById(Long userId);
}
```

---

## 四、建议执行顺序

1. **A3-A7**（契约基础：ErrorCode/FeignConstant/MqConstant/CacheConstant/FeignApiConfig）
2. **A8-A10**（User 契约：UserClient 扩展 + OAuth + 注册事件）
3. **G1-G2**（上下文透传 + 敏感 Header 过滤）→ 打通认证链路
4. **G3-G4**（CORS + 请求日志）→ 完善可观测性
5. **G5-G8**（限流/多端/WS/文档聚合）→ 按需推进
6. **业务契约（1.3）** → 待对应业务模块开发时再建