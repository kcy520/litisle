# ============================================================
# 模块：litisle-ai
# 数据库：litisle_ai
# 说明：AI 计量与用途打标（明细 / 日汇总 / 单价配置）
# 设计来源：AI-Architecture「按用途计量与预算分账（R2 实施设计）§8.3」
# 支撑：D15 预算分账 / F14 预算 / V8 生成配额 / V5 熔断计数（同一套埋点）
# 会话与消息表（ai_session / ai_message）属生成式功能，尚未开工，暂不建表
# ============================================================

create database if not exists litisle_ai
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_ai;

    # 1. Token 用量明细表（只追加，不更新；按用途打标的唯一事实来源）
create table if not exists `ai_token_usage`
(
`id`             bigint unsigned not null auto_increment primary key comment '主键ID',
`request_id`     varchar(64)     not null comment '请求幂等键（业务侧生成，如 taskId:chunkIndex；同一次业务请求的切片×重试复用同一 request_id，重复写入按 upsert 跳过防重复计费）',
`user_id`        bigint unsigned null comment '发起用户ID（机审无发起用户时为空；生成式为调用用户）',
`purpose`        varchar(32)     not null comment '用途打标（调用方显式传入，缺省拒绝）：MODERATION_TEXT/MODERATION_IMAGE/SUMMARY/TAGGING/QA/OTHER；计费与预算只认前五类，OTHER 仅兜底落明细不计费不计预算，出现即告警按未归类调用观察',
`scene`          varchar(32)     null comment '业务场景快照（如 ARTICLE_FEATURED/COMMENT/USERNAME，与 review_task.scene 同名；生成式可空）',
`model_version`  varchar(32)     null comment '判定口径版本快照（文本=LLM few-shot prompt 版本；图片=ONNX 模型版本；失败调用可能未取得版本时为空）',
`prompt_version` varchar(32)     null comment '提示词版本（文本与 model_version 同值；图片无提示词留空）',
`token_in`       int             not null default 0 comment '输入 token 数（失败调用记 0）',
`token_out`      int             not null default 0 comment '输出 token 数（失败调用记 0）',
`cost`           decimal(14,2)   not null default 0.00 comment '本次调用成本 = token 数 × ai_pricing 单价，两位小数；失败调用按已产生 token 计费，避免「失败即无成本」',
`status`         tinyint         not null default 1 comment '调用结果：1-SUCCESS 2-FAIL（失败也留一条明细，供对账与成本统计）',
`degraded`       tinyint         not null default 0 comment '降级标记：0-正常 1-降级期产生（degraded=true 不计入效果评估样本与重判对比基线，见业务指南 §4.5）',
`occurred_at`    datetime        not null comment '调用完成时间（归属日期权威源，不按发起时间；跨天重试只计一次成功成本）',
`create_time`    datetime        not null default current_timestamp comment '写入时间',
unique key `uk_request_id` (`request_id`),
index `idx_purpose_date` (`purpose`, `occurred_at`),
index `idx_occurred` (`occurred_at`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI Token 用量明细表（只追加）';

    # 2. 用量日汇总表（定时任务按 stat_date+user_id+purpose 聚合，幂等覆盖写；预算判定与 F9 个人用量读此表）
create table if not exists `ai_usage_daily`
(
`id`             bigint unsigned not null auto_increment primary key comment '主键ID',
`stat_date`      date            not null comment '统计日期（按明细 occurred_at 归属；预算按自然日 00:00 重置）',
`user_id`        bigint unsigned not null default 0 comment '用户ID（0=系统/机审侧无发起用户的汇总桶，规避 MySQL 唯一键对 NULL 不去重导致覆盖写失效）',
`purpose`        varchar(32)     not null comment '用途（同 ai_token_usage.purpose；预算分账：机审桶=MODERATION_TEXT+MODERATION_IMAGE 合并，SUMMARY/TAGGING/QA 各自独立）',
`calls`          int             not null default 0 comment '调用次数（成功+失败）',
`tokens_in`      bigint          not null default 0 comment '输入 token 合计',
`tokens_out`     bigint          not null default 0 comment '输出 token 合计',
`cost`           decimal(14,2)   not null default 0.00 comment '成本合计（两位小数）；运营看板按 (stat_date, purpose) 对本表求和，不另建第二张同名汇总表',
`fail_calls`     int             not null default 0 comment '失败调用次数（供对账与 V5 熔断计数核对）',
`degraded_calls` int             not null default 0 comment '降级期调用次数（与明细 degraded=1 行数对账）',
`create_time`    datetime        not null default current_timestamp comment '创建时间',
`update_time`    datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_stat` (`stat_date`, `user_id`, `purpose`),
index `idx_purpose_date` (`purpose`, `stat_date`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI 用量日汇总表';

    # 3. 模型单价配置表（成本口径唯一来源；配置变更只影响未来日期，不回改历史汇总）
create table if not exists `ai_pricing`
(
`id`              bigint unsigned not null auto_increment primary key comment '主键ID',
`model_version`   varchar(32)     not null comment '模型/口径版本（对应 ai_token_usage.model_version）',
`currency`        varchar(8)      not null default 'CNY' comment '币种',
`unit_price_in`   decimal(12,8)   not null comment '输入单价（每 token；小数精度高故 8 位小数，金额换算后保留两位）',
`unit_price_out`  decimal(12,8)   not null comment '输出单价（每 token）',
`effective_from`  date            not null comment '生效日期（同版本调价插入新行，不 UPDATE 旧行，保历史成本可复算）',
`create_time`     datetime        not null default current_timestamp comment '创建时间',
`update_time`     datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_model_from` (`model_version`, `effective_from`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI 模型单价配置表';
