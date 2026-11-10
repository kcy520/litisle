# AI 计量与用途打标（D15 前置）实施计划

## Context

清单 3.1 第一个实施前置项「AI 计量与用途打标」：现有代码只有全站 QPS 计数，无 Token/成本计量、无用途区分，D15 分账无法执行。设计口径已定档于 AI-Architecture「按用途计量与预算分账（R2 实施设计）」§8.3。

用户已定档范围（排除测试/验证类任务）：
- **方案 A**：计量基建 + 契约打标；chat 端点实现留到「AI 对话端点」前置项再做，本次不碰 LLM 通道
- **SQL 文档按 §8.3 重写**：旧版 ai_token_usage（user_id+usage_date+token_total）从未落地建库，直接改为明细表 + 新增汇总/单价表

关键事实：
- `AiChatRequestDTO` 无用途/请求键字段；§8.3 要求「用途由调用方显式传入，缺省拒绝」→ 必须改契约
- 唯一真实 AI 调用链路：review `ModerationTextService` 分片送审 → `LlmTextModerationAdapter` → `AiClient.chat`（端点未实现，现全量 SUSPICIOUS 转人工）
- 图片走本地 ONNX（`OnnxImageModerationAdapter`），不经 AI 服务，本次不打 MODERATION_IMAGE 标
- litisle-ai 仅骨架（pom 只有 starter+test），需加 mybatis-plus/mysql 依赖
- 计量服务/预算判定本次无调用者（端点未实现），属用户选定的落地边界

## 改动清单

### 1. Sql-Document.md §五重写（litisle_ai 库三张表）

文件：`docs/Sql-Document.md`（约 1801–1871 行区域）

- `ai_token_usage` 改为**只追加明细表**（§8.3②）：`request_id`（唯一索引，幂等键）、`user_id`（可空，机审无发起用户）、`purpose`、`scene`、`model_version`、`prompt_version`（文本与 model_version 同值、图片留空）、`token_in`、`token_out`、`cost`（decimal 两位小数）、`status`（SUCCESS/FAIL）、`degraded`、`occurred_at`（归属日期按调用完成时间）、`create_time`
- 新增 `ai_usage_daily`：`stat_date`+`user_id`+`purpose` 唯一键，`calls`、`tokens_in`、`tokens_out`、`cost`、`fail_calls`、`degraded_calls`
- 新增 `ai_pricing`：`model_version`、`currency`、`unit_price_in`、`unit_price_out`、`effective_from`（配置变更只影响未来日期）
- 同步改写「设计原因」说明段落

### 2. litisle-api 契约打标

- 新增 `AiPurposeEnum`（`com.litisle.litisleapi.dto.ai` 或 enums 包）：`MODERATION_TEXT` / `MODERATION_IMAGE` / `SUMMARY` / `TAGGING` / `QA` / `OTHER`（计费与预算只认前五类，OTHER 仅兜底落明细）
- [AiChatRequestDTO](file:///e:/NewJavaPro/LitIsle/litisle-api/src/main/java/com/litisle/litisleapi/dto/ai/AiChatRequestDTO.java) 加字段：`purpose`（AiPurposeEnum，注释注明必传、缺省端点侧拒绝）、`requestId`（String，业务侧生成，切片×重试幂等键）、`scene`（String，可空）

### 3. review 侧调用点传参

- [TextModerationRequest](file:///e:/NewJavaPro/LitIsle/litisle-review/src/main/java/com/litisle/litislereview/moderation/spi/dto/TextModerationRequest.java) 加字段：`requestId`、`scene`
- SPI [ContentModerationService](file:///e:/NewJavaPro/LitIsle/litisle-review/src/main/java/com/litisle/litislereview/moderation/spi/ContentModerationService.java) `moderateText(String content)` 改签名 `moderateText(TextModerationRequest request)`；波及实现类 `LlmTextModerationAdapter`（取 content + requestId/scene 塞入 AiChatRequestDTO，purpose 固定 MODERATION_TEXT）与 `OnnxImageModerationAdapter`（仅改签名适配）；实施前 grep 确认无其他实现/调用方
- [ModerationTextService](file:///e:/NewJavaPro/LitIsle/litisle-review/src/main/java/com/litisle/litislereview/moderation/service/ModerationTextService.java) `moderateWithChunking`：签名加 task 上下文，逐片构造 requestId = `taskId + ":" + chunkIndex`（分片确定性切分 → 延迟重试复用同一 requestId，满足 §8.3① 重试不重复计费）；scene 取 `task.getScene().name()`

### 4. litisle-ai 计量基建

pom 新增依赖（版本由父 POM/依赖管理托管，对齐 review）：`mybatis-plus-spring-boot3-starter`、`mysql-connector-java`、`lombok`；新增 `litisle-api` compile 依赖（复用 AiPurposeEnum）；不加 web/nacos/mq/redis（§8.3 预算判定读 MySQL + 本地缓存，明确不引入新组件）

配置：`application.properties` 加 litisle_ai 数据源（本地 3307 库，对齐 review 本地配置的密钥惯例）

包结构（对齐 AI-Architecture §3.2 规划）：
- `enums/`：若 AiPurposeEnum 在 api 侧则不重复定义
- `domain/entity/`：`AiTokenUsage`、`AiUsageDaily`、`AiPricing`（MyBatis-Plus 实体，注释齐全，风格照抄 review `WordlibHitStat`）
- `mapper/`：三个 extends BaseMapper
- `service/quota/`：
  - `AiUsageRecordService` + impl：写明细，request_id 幂等（撞唯一键跳过）；失败调用也落一条（status=FAIL）
  - `AiBudgetService`：预算桶判定——机审桶 = MODERATION_TEXT + MODERATION_IMAGE 合并；SUMMARY/TAGGING/QA 各自桶。读 ai_usage_daily 当日按桶合计（user 维度求和），本地缓存 60 秒（ConcurrentHashMap+时间戳，不引缓存组件）；预算值来自 `AiBudgetProperties`（Map，≤0 表示未设预算=只计量不限额，等财务科目与参数定档后补值）；返回 ALLOWED/WARN(≥80%)/EXCEEDED(100%)
- `task/AiUsageDailyAggregateTask`：@Scheduled 定时聚合当日+前一日明细 → ai_usage_daily 幂等覆盖写（每小时 + 启动延迟一次；保证预算判定当日数据可用），并对账明细与汇总差异 >1% 记日志告警
- `config/`：@MapperScan + AiBudgetProperties（@ConfigurationProperties）
- 启动类加 @MapperScan 或经配置类

所有新增类/方法/字段按用户规范带中文注释；不用字符串硬编码字段名（Lambda/常量）；无分页场景不涉及 #last() 约束

### 5. 文档同批更新（维护规则 1：同改同更）

- `AI-Doc-Checklist.md` 3.1 计量行 `[ ]` → `[~]`（基建+契约打标已落地；chat 端点未实现故无真实计量数据、预算值待定），文首统计同步
- `AI-Architecture.md` §8.1/§8.2 相应行状态更新（按用途计数/Token 计量：基建已落地、接线待端点）；§2.3 设计态清单 ai_token_usage 行标注

## 验证（编译级，不做运行时测试）

```
mvn -pl litisle-api,litisle-ai,litisle-review -am compile
```

不执行建库/建表 SQL、不启动服务、不跑单测（用户排除验证类任务）。

## 明确不做（边界）

- chat 端点实现（POST /api/feign/ai/chat）、LLM 通道、V5 熔断计数、V8 用户配额扣减——归「AI 对话端点」前置项
- 告警触达（站内信/群机器人）、F14 报表、预算科目参数定值——外部前置
- 图片用途打标——图片不经 AI 服务，待 AI 图片判定端点实现时接入
