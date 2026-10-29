> **拆分归档**：本文件拆分自同目录索引页 [Temporarily_not_implemented.md](Temporarily_not_implemented.md)，原单文件对应部分原样搬运（2026-10-28 拆分，内容零修改），各部分入口见索引页。

## 部署部分（生产上线前必做）

### 通用（全服务）

#### 监控端口网络隔离（监控链路安全前提）

各服务 8xxx 端口的 /actuator/** 免登录放行的是「路径」而非「来源 IP」，生产环境必须通过防火墙/安全组确保业务端口仅对内网开放（Prometheus 所在网段可达即可，公网不可达）；或升级为 management.server.port 独立管理端口 + 网络层仅放行 Prometheus IP 段（与网关 8081 方案同一套纪律）。开发环境单机直连现状合规，无需处理。

#### Sentinel 规则操作纪律

Sentinel Dashboard 改规则只推内存、重启丢——操作纪律：规则增删改一律去 Nacos 控制台。

### file 文件模块

#### replication ≥ 010 + Volume 多机

用户上传原件不可再生，单副本（replication=001）不达生产标准；⚠️ 副本策略只影响新写入 volume，存量不自动补副本，须在首次生产写入前定稿。

#### Filer 元数据切 MySQL

当前 leveldb 与数据同盘，盘坏 = 目录全丢（比丢文件更致命）；复用已有 MySQL 实例。开发期 leveldb 无所谓。

#### instance-count 动态化（触发条件：file 服务多实例部署上线时）

接 Nacos 服务发现自动计算实例数（扩缩容免人工同步）。Micrometer 指标埋点与暴露已于 2026-09-29 落地并验证通过（详见 commom-des.md §3.5），剩余 Prometheus/Alertmanager 为部署侧工作，零代码改动。
