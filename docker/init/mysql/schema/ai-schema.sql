# ============================================================
# 模块：litisle-ai
# 数据库：litisle_ai
# 说明：AI 计量与用途打标（明细 / 日汇总 / 单价配置）+ 会话与消息（板块三 3.1）
# 设计来源：AI-Architecture「按用途计量与预算分账（R2 实施设计）§8.3」+ Sql-Document §五
# 支撑：D15 预算分账 / F14 预算 / V8 生成配额 / V5 熔断计数（同一套埋点）/ D25 会话治理
# 会话与消息表（ai_session / ai_message）随板块三 3.1 建表（2026-11-15）；已有库重跑本脚本即可补建
# ============================================================

create database if not exists litisle_ai
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_ai;

    # 1. Token 用量明细表（只追加，不更新；按用途打标的唯一事实来源）
    # 2026-11-15 修订：幂等键 = request_id + call_seq 联合唯一（原 request_id 单键只覆盖机审切片，
    # Agent 多轮会在同一 request_id 下产生多条明细，单键会撞键漏计）；已有库需手动 ALTER：
    #   ALTER TABLE ai_token_usage ADD COLUMN call_seq int NOT NULL DEFAULT 1 COMMENT '调用序号' AFTER request_id;
    #   ALTER TABLE ai_token_usage DROP INDEX uk_request_id, ADD UNIQUE KEY uk_request_call (request_id, call_seq);
create table if not exists `ai_token_usage`
(
`id`             bigint unsigned not null auto_increment primary key comment '主键ID',
`request_id`     varchar(64)     not null comment '请求幂等键（业务侧生成，如 taskId:chunkIndex；重试复用同一 request_id，重复写入按 upsert 跳过防重复计费）',
`call_seq`       int             not null default 1 comment '调用序号（同一业务请求的第 N 次模型调用：机审切片通道固定 1、Agent 多轮按轮次递增；重试复用同一序号，与 request_id 联合唯一）',
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
unique key `uk_request_call` (`request_id`, `call_seq`),
index `idx_purpose_date` (`purpose`, `occurred_at`),
index `idx_occurred` (`occurred_at`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI Token 用量明细表（只追加）';

    # 1a. AI 会话表（板块三 3.1，DDL 以 Sql-Document §五为准；is_lru=1 暂存区按 D25 治理 200 会话上限 LRU 淘汰）
create table if not exists `ai_session`
(
`id`         bigint unsigned not null auto_increment primary key comment '会话ID',
`user_id`    bigint unsigned not null comment '用户ID',
`agent_type` varchar(16)     not null comment 'Agent 类型：XIAOYUE / XIAOSHEN',
`title`      varchar(100)    null comment '会话标题（首条消息摘要）',
`mode`       varchar(20)     null comment '小悦模式：POLISH/STRUCTURE/SUMMARY/DEEP',
`is_lru`     tinyint         not null default 1 comment '是否暂存区：1-是 0-已保存区',
`last_active_time` datetime  not null default current_timestamp comment '最后活跃时间（LRU 依据）',
`del_flag`   tinyint         not null default 0 comment '删除标志（软删=懒删；物理清理由定时兜底任务执行）',
`create_time` datetime       not null default current_timestamp comment '创建时间',
`update_time` datetime       not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_user_lru` (`user_id`, `is_lru`, `last_active_time`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI 会话表';

    # 1b. AI 消息表（跟随会话级联清理，不加 del_flag；prompt/completion token 逐条记录与 ai_token_usage 对账）
create table if not exists `ai_message`
(
`id`         bigint unsigned not null auto_increment primary key comment '消息ID',
`session_id` bigint unsigned not null comment '会话ID',
`role`       varchar(16)     not null comment '角色：USER / ASSISTANT / SYSTEM',
`content`    text            not null comment '消息内容',
`token_cnt`  int             not null default 0 comment '本条消息 token 数（当前未接估算器，默认 0 预留）',
`prompt_tokens`     int      null comment '本条消息输入 token 消耗（记于该轮终答行，与 ai_token_usage 日汇总对账，防统计口径漂移）',
`completion_tokens` int      null comment '本条消息输出 token 消耗（同上）',
`create_time` datetime       not null default current_timestamp comment '创建时间',
index `idx_session_id` (`session_id`)
) engine = InnoDB
default charset = utf8mb4 comment = 'AI 消息表';

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
