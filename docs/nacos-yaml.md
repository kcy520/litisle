
## Nacos 共享模板 litisle-shared.yaml
```yaml
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
 