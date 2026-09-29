## Nacos 共享模板

### litisle-shared.yaml

```YAML
spring:
  cloud:
    openfeign:
      sentinel:
        enabled: true        # SCA 2025.0.0.0 的属性名；若重启后降级仍未生效，补配 feign.sentinel.enabled: true（老属性名，兼容用）
    sentinel:
      transport:
        dashboard: localhost:8090   # 你 Docker 里的 Dashboard（user 模块注释里记的 1.8.9）
      eager: true                   # 关闭懒加载，启动即注册到 Dashboard，方便验证
      # ⚠️ transport.port 已从共享配置移除 → 移回各服务本地 yml（见下文说明）

sa-token:
  # ===== 认证公共配置（SSOT：auth/user/网关统一从此处读取）=====
  token-name: Authorization
  token-prefix: Bearer
  timeout: 86400
  active-timeout: 604800
  is-concurrent: true
  max-login-count: -1              # 已同步清单 #8 决策：允许多设备在线
  is-write-header: true
  is-log: true

seata:
  enabled: true
  application-id: ${spring.application.name}
  tx-service-group: litisle_tx_group
  service:
    vgroup-mapping:
      litisle_tx_group: default
  registry:
    type: nacos
    nacos:
      application: seata-server
      server-addr: localhost:8848
      group: DEFAULT_GROUP
      namespace:
      username: nacos
      password: nacos


```

| **字段**               | **含义**                      |
| :------------------- | :-------------------------- |
| `resource`           | 资源名（被保护的方法 / 接口 / Feign 调用） |
| `grade`              | 熔断策略（0=慢调用比例，1=异常比例，2=异常数）  |
| `count`              | 阈值（含义随 grade 变化）            |
| `slowRatioThreshold` | 慢调用比例阈值（仅 grade=0 有效）       |
| `timeWindow`         | 熔断时长（秒）                     |
| `minRequestAmount`   | 触发熔断的最小请求数（防小样本误判）          |
| `statIntervalMs`     | 统计窗口（毫秒）                    |

### litisle-file-degrade-rules.json

```json
[
  {"resource":"seaweedfs-put","grade":0,"count":3000,"slowRatioThreshold":0.8,"timeWindow":30,"minRequestAmount":20,"statIntervalMs":10000},
  {"resource":"seaweedfs-get-stream","grade":0,"count":3000,"slowRatioThreshold":0.8,"timeWindow":30,"minRequestAmount":20,"statIntervalMs":10000}
]
```

**seaweedfs-put**
每 10 秒统计一次：如果调用 ≥20 次，且其中响应超过 3 秒的请求占到 80%，就熔断 30 秒。
间上传直接快速失败，不走 S3/SeaweedFS。

**seaweedfs-get-stream**
下载流同理：10 秒内 ≥20 次请求，80% 都超过 3 秒 → 熔断 30 秒。

> `seaweedfs-put` 规则 （保护所有对 SeaweedFS 的写入口，调用点 2 处）：
> `seaweedfs-get-stream` 规则 （保护下载读流）：
>
> 对应关系靠的是代码里的两个注解—— JSON 规则里的`resource` 名就是`@SentinelResource` 注解的值 ，Sentinel 靠这个名字把「规则」和「代码」连起来。
> ```java
> //file模块的client：
> @SentinelResource("seaweedfs-put")        // ← 5 参重载 put 方法
> public void put(String bucket, String key, byte[] bytes, String contentType, String checksumSha256Base64)
> 
> @SentinelResource("seaweedfs-get-stream") // ← 流式读取方法
> public ResponseInputStream<GetObjectResponse> getStream(String bucket, String key, Long start, Long end)
> ```
>
> 

| **字段**               | **含义**                                                                                                           |
| :------------------- | :--------------------------------------------------------------------------------------------------------------- |
| `resource`           | 资源名，对应代码中 `@SentinelResource("xxx")` 的值，指定这条规则保护的是哪个方法、接口或 Feign 调用                                              |
| `grade`              | 熔断策略类型：`0` = 慢调用比例，`1` = 异常比例，`2` = 异常数                                                                          |
| `count`              | 阈值，含义随 `grade` 变化： • `grade=0` 时单位为**毫秒**，表示"超过此值算慢调用" • `grade=1` 时取值范围 `0.0~1.0`，表示异常比例上限 • `grade=2` 时表示异常数上限 |
| `slowRatioThreshold` | 慢调用比例阈值，仅 `grade=0` 时生效，取值范围 `0.0~1.0`；统计窗口内慢调用占比达到此值才触发熔断                                                       |
| `timeWindow`         | 熔断持续时间，单位**秒**；期间所有请求直接拒绝，到期后进入半开探测状态                                                                            |
| `minRequestAmount`   | 触发熔断的最小请求数；统计窗口内请求数低于此值时不熔断，防止冷启动或小样本误判                                                                          |
| `statIntervalMs`     | 统计窗口长度，单位**毫秒**；Sentinel 在此时间范围内滚动计算请求总数、慢调用数/异常数及其比例                                                            |

它们是 Sentinel 熔断规则的「实体存放处」 。整个链路是：

````
yml 数据源配置（每个服务本地）          Nacos 配置实体（这两个 JSON）
┌─────────────────────────────┐      ┌──────────────────────────────────┐
│ "去 Nacos 监听               │ 监听  │ litisle-auth-degrade-rules.json  │
│  litisle-auth-degrade-rules │ ────→ │  （auth 调 user 的 5 条熔断规则） │
│  .json，组 DEFAULT_GROUP，   │      │ litisle-user-degrade-rules.json  │
│  类型是 degrade 熔断规则"     │      │  （user 调 file/auth 的 6 条）    │
└─────────────────────────────┘      └──────────────────────────────────┘
        ↓ 服务启动/配置变更时                      ↓ 你在控制台改 JSON
   规则加载进服务内存，Sentinel 据此          保存 → 秒级推送到服务
   判断「某资源错误率超 50% → 熔断 20s」      热生效，无需重启

````







### itisle-auth-degrade-rules.json

```json
[
  {"resource":"GET:http://litisle-user/api/feign/user/{userId}","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"POST:http://litisle-user/api/feign/user","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"GET:http://litisle-user/api/feign/user/account/{account}","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"POST:http://litisle-user/api/feign/user/email","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"GET:http://litisle-user/api/feign/user/auth/{userId}","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000}
]
```

### litisle-user-degrade-rules.json

```json
[
  {"resource":"GET:http://litisle-file/api/feign/file/{fileId}/info","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"POST:http://litisle-file/api/feign/file/ref/bind","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"POST:http://litisle-file/api/feign/file/ref/unbind","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"POST:http://litisle-file/api/feign/file/batch-urls","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"DELETE:http://litisle-auth/api/feign/user/{userId}/sessions","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000},
  {"resource":"POST:http://litisle-auth/api/feign/captcha/change-email/verify","grade":1,"count":0.5,"timeWindow":20,"minRequestAmount":10,"statIntervalMs":10000}
]
```

