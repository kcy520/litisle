> **归档说明**：新建板块文件（批次一，2026-11-05 归档）——迁自 docs/next/next_update.md search 模块「批次一：基础骨架收尾（对齐批次D D1 余项）」节，正文原样搬运（含 ✅ 摘要、偏差说明与跨板块遗留）。
> **来源**：[next_update.md](../../next/next_update.md) 批次一；活跃待办/验证见该文档；挂起项见 [temporarily-04-deferred.md](../../next/temporarily/temporarily-04-deferred.md)。

## 板块二：基础骨架收尾（批次一，2026-11-05 归档）

### search模块

#### 批次一：基础骨架收尾（对齐批次D D1 余项）

##### 1.1 ES 索引 mapping 初始化

- [x] article_idx mapping（title/tags/summary/content ik 分词 + visibility 闸门字段 + authorId/authorName（multi-field 含 .completion）+ like_cnt/comment_cnt/view_cnt + heat + vec_dense(dims=1024,cosine,indexed) + updateTime + suggest completion 子字段）；book_idx mapping（书名/作者/简介 text+ik + 分类 keyword）；article_chunks mapping（chunk_id/article_id/author_id/title/author_name/chunk_text/chunk_vector(dense_vector,cosine,indexed)/zone/visibility/publish_time/ingest_time，按 C2 一期仅公开文章正文）；索引名走 `litisle.search.index.*` 配置；Embedding 模型版本写入索引 meta（C1 单模型口径）；user_idx mapping 可一并创建（消费链路暂缓，见暂不实现汇总）
  - ✅ 2026-11-03：EsIndexInitializer（es 包，ApplicationRunner 启动时逐索引幂等创建，exists 跳过防覆盖；单索引失败仅告警不阻断启动，降级链路兜底）；四索引 mapping 全量落地：article_idx（ik_max_word/ik_smart + title/authorName completion 子字段 + vec_dense 1024 维 cosine indexed）+ book_idx（书名/作者/简介/分类）+ user_idx（笔名/简介，占位）+ article_chunks（chunk_id/article_id/chunk_text/chunk_vector 等 11 字段）；mappings._meta 写入 embedding_model（占位 unset-1024d，选型随 VectorService 定）+ embedding_dims（C1）；SearchBizConstant 补 book/user/chunk 字段名常量 + SEARCH_KEYWORD_MAX_LENGTH(64)/EMBEDDING_DIMS/ik 分词器名/meta 键；SearchProperties.Index + yml 补 `index.chunk=article_chunks`；跨板块遗留：Embedding 模型选型后改写 EMBEDDING_MODEL_PLACEHOLDER 并全量重建（批次七）

##### 1.2 search_history 表 DDL 登记

- [x] search_history DDL（id / user_id+keyword 组合 uk / keyword varchar 长度对齐 SEARCH_PARAM_INVALID 上限 / updated_at）登记 Sql-Document.md + local 库建表（闭环 G8）
  - ✅ 2026-11-03：docker/init/mysql/search-schema.sql 新建（search_history 单表：id / user_id / keyword varchar(64) / updated_at，uk(user_id,keyword) + idx_user_updated；keyword 长度 64 与新增常量 SEARCH_KEYWORD_MAX_LENGTH 同源，批次四参数校验按 SEARCH_PARAM_INVALID 拒绝超长）；00-init-databases.sql 补 litisle_search 建库 + GRANT 授权行；Sql-Document.md 新增「五、litisle-search 模块」章节（含存量环境手动执行说明 + ES 侧无 DDL 备注）；实体/Mapper 随批次六落地

##### 1.3 Feign 启用与骨架遗留收口

- [x] 主类/配置补 @EnableFeignClients（扫描 litisle-api）等 D1 骨架跨批次遗留项收口
  - ✅ 2026-11-03：LitisleSearchApplication 补 @EnableFeignClients(basePackages = "com.litisle.litisleapi.feign")（ContentClient 等消费方 Bean 注册，批次二回查起消费；Provider 端点 /api/feign/search/** 由本服务实现无需额外注解），主类注释同步更新
  - ✅ 2026-11-03：**D1 骨架先行落地（编译验证已通过，本批次 1.1~1.3 余项保留不勾选）**：pom 依赖对齐 file 模块（web/actuator/validation/amqp/common 四件套/mybatis-plus+mysql/Feign+Sentinel+Nacos/ES Java Client 8.18.8/knife4j/litisle-api）；主类 LitisleSearchApplication（端口 8086，无 @EnableScheduling）；application.yml（`litisle.search.*` §六 全量配置外置 + Nacos/MySQL litisle_search 库/Redis/RabbitMQ 手动 ACK）+ application-local/dev.yaml（Sentinel 心跳 8722、ES localhost/litisle-elasticsearch:9200）；enums SearchTypeEnum/SearchSortEnum（DictEnum 三值绑定）；constant SearchBizConstant（EXCHANGE_SEARCH_DLX=litisle.search.dlx / QUEUE_SEARCH_INDEX_DLQ / PUBLIC 闸门值 / ES 字段名收口 / 热度 pending 键前缀与 hash 字段）；config 三件 SearchProperties（§六 参数绑定）+ SearchMqConfig（六同步队列 + 独立 DLX/DLQ 拓扑声明，消费者留批次二）+ ElasticsearchConfig（客户端装配，索引初始化留 1.1）；Architecture §1.2 已同步 ✅骨架标注、§六 rabbit 行默认值修正
