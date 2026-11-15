> **归档说明**：新建板块文件（板块零，2026-11-15 归档）——迁自 [next_update.md](../../next/next_update.md)「litisle-ai（AI）模块」节，条目原样搬运（含口径修正与跨板块遗留标注），不压缩关键细节。
> **板块定位**：一切任务的前置——只立骨架不写业务；依赖与基础设施接入完成后板块一（基建）与板块二（Agent 底座）方可推进。

## 板块零：模块基础骨架接入（当前仅空壳，一切任务的前置）

###### 0.1 依赖与基础设施接入

- [x] litisle-ai 补齐基础依赖与配置：web / openfeign / mybatis-plus / mysql / redis / rabbitmq / nacos 注册与配置中心；确定服务端口；application.properties 迁移 yml 并接入 Nacos（数据源、Redis、MQ 连接均未配置）
  - 现状基线（AI-Architecture §2.1）：pom 仅 spring-boot-starter + test，controller/service/mapper/config 均不存在；本任务只立骨架不写业务
  - ✅ 2026-11-15：pom 补齐 web / openfeign(+loadbalancer) / mybatis-plus / mysql / data-redis(+pool2) / amqp / nacos discovery+config / lombok；端口定 8087（顺延 gateway 8080~search 8086）；application.properties 删除（经确认），迁移为 application.yml + application-local.yaml（profile local，Nacos 接入与 review 同构：config.import litisle-ai.yaml + litisle-shared.yaml，db=litisle_ai）；只立骨架未写业务——@EnableFeignClients 与 litisle-api 依赖随板块一 1.2 接入时补，MQ 消费侧配置随板块四落地；编译验证已通过（2026-11-15，mvn -pl litisle-ai clean compile）
