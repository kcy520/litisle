# ============================================================
# 模块：litisle-review
# 数据库：litisle_review
# 说明：审核任务 / 审核留痕 / 举报 / 申诉 / 违规分 / 敏感词库
# ============================================================

create database if not exists litisle_review
character set utf8mb4
collate utf8mb4_unicode_ci;

use litisle_review;

    # 1. 审核任务表（状态机核心，不可物理删除；append-only 留痕另存 moderation_record）
create table if not exists `review_task`
(
`id`           bigint unsigned not null auto_increment primary key comment '任务ID',
`biz_type`     tinyint         not null comment '业务类型：1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片（biz_type=6 时 biz_id = file_meta.id）',
`biz_id`       bigint unsigned not null comment '业务ID',
`scene`        varchar(32)     not null comment '审核场景：ARTICLE_FEATURED/ARTICLE_COMMUNITY/ARTICLE_RE_REVIEW/COMMENT/COMMENT_RE_REVIEW/USERNAME/MESSAGE/MESSAGE_RE_REVIEW/IMAGE/ARTICLE_IMG_RE_REVIEW（*_RE_REVIEW 为举报/复审触发场景，与发布场景分列防 uk_biz_scene 撞键重置已终态任务，见 Review-Plan §4.5.3；varchar(32) 留余量：最长场景名 ARTICLE_IMG_RE_REVIEW 恰 20 字符，20 无余量易溢出）',
`layer1_hit`   tinyint         not null default 0 comment 'DFA 命中：0-未命中 1-HIGH 2-MID',
`hit_words`    text            null comment 'DFA 命中词快照（逗号分隔，审核时固化，不随词库热更新变化，供人工复核回溯；应用层截断保护后落 text：超长 varchar 在 strict mode 报错会中断审核写入，截断兜底+text 大容量，证据宁存不丢）',
`layer2_risk`  tinyint         not null default 0 comment '小审风险：0-未送审 1-低 2-中 3-高（辅助风险分级：由 LLM 适配器写入供人审队列排序，不承载判定语义，判定以 mod_verdict 为准）',
`status`       tinyint         not null default 0 comment '状态：0-待审 1-自动通过 2-人工待审 3-通过 4-驳回',
`mod_verdict`  tinyint         not null default 0 comment '机审三分态冗余：0-未审 1-PASS 2-REJECT 3-SUSPICIOUS（人审队列列表直读，免 JOIN moderation_record）',
`retry_count`  int             not null default 0 comment 'MQ 重试次数，耗尽转人审（DLX 消费者累加写回，与消息头 retryCount 对账）',
`content_hash` char(64)        null comment '内容指纹SHA256（幂等入口 + 审核缓存复用入口；文本=标题+正文，图片=文件sha256，文章级图片复审=全部配图 sha256 聚合指纹）',
`assignee`     bigint unsigned null comment '认领审核员ID',
`claim_time`   datetime        null comment '认领时间（claim 写入；sweeper 按此判定认领超时回池，勿用 update_time——详情查看/重置等写入会刷新 update_time 致超时判定失准）',
`decided_by`   bigint unsigned null comment '决策者ID',
`opinion`      varchar(500)    null comment '审核意见/修改建议',
`decide_time`  datetime        null comment '终审落库时间（decide 写入；申诉窗口基准与申诉超时判定的权威时间源，见 Review-Plan §4.7）',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_biz_scene` (`biz_type`, `biz_id`, `scene`),
index `idx_status` (`status`, `create_time`),
index `idx_assignee` (`assignee`)
) engine = InnoDB
default charset = utf8mb4 comment = '审核任务表';

    # 1.1 审核记录表（append-only 留痕：机审+人审+申诉改判全程追加，与 review_task 状态机分离）
create table if not exists `moderation_record`
(
`id`                bigint unsigned not null auto_increment primary key comment '记录ID',
`mod_type`          tinyint         not null comment '审核方式：1-关键词DFA 2-LLM语义 3-本地图片模型 4-云API 5-人工复审 6-申诉改判',
`biz_type`          tinyint         not null comment '业务类型（同 review_task.biz_type）：1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片',
`biz_id`            bigint unsigned not null comment '业务ID（biz_type=6 时为 file_meta.id；USERNAME 注册被拒时用户尚不存在，约定 biz_id=0，取证靠 content_hash+reason 自包含）',
`review_task_id`    bigint unsigned null comment '关联审核任务ID（同步闸门可无任务直审，如 USERNAME）',
`scene`             varchar(32)     null comment '审核场景（同 review_task.scene；同 biz_type+biz_id 可并存文本/图片复审多场景，场景列使单行凭证自包含免 JOIN；USERNAME 直审可空；varchar(32) 与 review_task.scene 对齐）',
`content_hash`      char(64)        not null comment '内容指纹SHA256（文本=标题+正文；图片=文件sha256；文章级图片复审=全部配图 sha256 排序拼接后再 sha256 的聚合指纹）——审核缓存查重键，与文件判重键同纪律',
`provider`          varchar(32)     null comment '审核提供方（图片：onnx/aliyun；文本：llm；人工/DFA 为空）——缓存兜底按此过滤，防 onnx→aliyun 切换复用旧结论',
`wordlib_ver`       bigint          null comment '词库版本快照（文本记录写入=sensitive_word_version.version；图片/人工为空）——缓存兜底按此比对，防词库热更后窗口内旧结论继续放行',
`model_version`     varchar(32)     null comment '判定口径版本快照（图片=ONNX 模型版本/云 API 接口版本；文本=LLM few-shot prompt 版本；DFA/人工为空）——缓存兜底按此比对，防同 provider 下模型/prompt 升级后复用旧结论（§2.11）',
`verdict`           tinyint         not null comment '判定：1-PASS 2-REJECT 3-SUSPICIOUS',
`category`          varchar(32)     null comment '违规类目（机审统一映射枚举）：politics/porn/abuse/ad/none',
`hit_words`         text            null comment 'DFA 命中词快照（冗余自 review_task，凭证自包含便于导出审计；与 review_task 同纪律：应用层截断保护后落 text，留痕写入宁存不丢）',
`reason`            varchar(500)    null comment '判定理由（作者申诉与管理员复审依据，LLM 输出透传）',
`provider_trace_id` varchar(128)    null comment '审核凭证ID（云厂商 traceId / LLM 请求ID；本地组件与人工为空）',
`trace_id`          varchar(64)     null comment '网关→MQ→消费端链路追踪ID（排障一串到底；与厂商凭证分列：前者排内部链路，后者对外出凭证）',
`operator_id`       bigint unsigned not null default 0 comment '操作人（0-系统机审）',
`operator_name`     varchar(32)     not null default 'system' comment '操作人名称（审计冗余）',
`cost_ms`           int             null comment '审核耗时（观测 Timer 旁路佐证）',
`create_time`       datetime        not null default current_timestamp comment '记录时间',
index `idx_hash` (`content_hash`, `scene`, `id`),
index `idx_biz` (`biz_type`, `biz_id`),
index `idx_task` (`review_task_id`),
index `idx_create` (`create_time`)
) engine = InnoDB
default charset = utf8mb4 comment = '审核记录表（append-only 不可改不可删：《网络安全法》留痕硬凭证留存≥6个月，到期归档任务迁移历史存储，改判/申诉均追加新行）';

# 1.1 审核记录归档表（6-4 §4.4.1：热表到期迁移只读历史存储——同库独立表承接，列口径与 moderation_record 逐列对齐；
# 唯一写入入口 = ModerationRecordArchiveSweeper 原子迁档事务：迁档+删热行同事务，原子性天然兑底无半途态）
# ⚠️ DBA 权限纪律：应用账号对本表仅授 INSERT/SELECT（无 UPDATE/DELETE），防误改误删历史凭证
create table if not exists `moderation_record_archive`
(
`id`                bigint unsigned not null primary key comment '记录ID（原 moderation_record.id 原样保留，审计链路不断；禁 auto_increment，防重发新 id 破坏按 id 幂等迁档）',
`mod_type`          tinyint         not null comment '审核方式：1-关键词DFA 2-LLM语义 3-本地图片模型 4-云API 5-人工复审 6-申诉改判（同 moderation_record.mod_type）',
`biz_type`          tinyint         not null comment '业务类型：1-文章 2-评论 3-用户名 4-作家申请 5-私信 6-文件/图片（同 moderation_record.biz_type）',
`biz_id`            bigint unsigned not null comment '业务ID（同 moderation_record.biz_id）',
`review_task_id`    bigint unsigned null comment '关联审核任务ID（同 moderation_record.review_task_id）',
`scene`             varchar(32)     null comment '审核场景（同 moderation_record.scene）',
`content_hash`      char(64)        not null comment '内容指纹SHA256（同 moderation_record.content_hash）',
`provider`          varchar(32)     null comment '审核提供方（同 moderation_record.provider）',
`wordlib_ver`       bigint          null comment '词库版本快照（同 moderation_record.wordlib_ver）',
`model_version`     varchar(32)     null comment '判定口径版本快照（同 moderation_record.model_version）',
`verdict`           tinyint         not null comment '判定：1-PASS 2-REJECT 3-SUSPICIOUS（同 moderation_record.verdict）',
`category`          varchar(32)     null comment '违规类目（同 moderation_record.category）',
`hit_words`         text            null comment 'DFA 命中词快照（同 moderation_record.hit_words）',
`reason`            varchar(500)    null comment '判定理由（同 moderation_record.reason）',
`provider_trace_id` varchar(128)    null comment '审核凭证ID（同 moderation_record.provider_trace_id）',
`trace_id`          varchar(64)     null comment '链路追踪ID（同 moderation_record.trace_id）',
`operator_id`       bigint unsigned not null default 0 comment '操作人（同 moderation_record.operator_id）',
`operator_name`     varchar(32)     not null default 'system' comment '操作人名称（同 moderation_record.operator_name）',
`cost_ms`           int             null comment '审核耗时（同 moderation_record.cost_ms）',
`create_time`       datetime        not null default current_timestamp comment '记录时间（=原热表记录时间，合规留存期以此为准）',
index `idx_biz` (`biz_type`, `biz_id`),
index `idx_create` (`create_time`)
) engine = InnoDB
default charset = utf8mb4 comment = '审核记录归档表（只读历史存储：仅 INSERT/SELECT；《网络安全法》≥6个月合规留存收口于此，热表删除不影响审计凭证可查）';

# 2. 举报表
create table if not exists `report`
(
`id`               bigint unsigned not null auto_increment primary key comment '举报ID',
`reporter_id`      bigint unsigned not null comment '举报人ID',
`target_type`      tinyint         not null comment '目标类型：1-文章 2-评论 3-用户 4-私信 5-图片/文件（target_type=5 时 target_id = file_meta.id，与其余 target 对齐；与 review_task.biz_type 映射见 Review-Plan §4.5.2 SSOT 表：私信 5↔4、图片 6↔5 错位，禁按 biz_type==target_type 恒等实现，否则反查举报零行卡死）',
`target_id`        bigint unsigned not null comment '目标ID',
`reason`           varchar(200)    not null comment '举报理由',
`status`           tinyint         not null default 0 comment '状态：0-待处理 1-初审中 2-转人工 3-已裁决 4-已撤回（举报人本人撤回；active_key 生成列按 status<3 判定，4 亦置空允许再举报）',
`review_task_id`   bigint unsigned null comment '关联审核任务ID（转人工建 review_task 时回填；人审 decide 按此定位并回写 report.status=3-已裁决/result，闭环举报，见 Review-Plan §4.5.3）',
`handler`          bigint unsigned null comment '处理人ID',
`result`           varchar(200)    null comment '裁决结果',
`abuse_flag`       tinyint         not null default 0 comment '诬告标记：0-否 1-是（裁决时判定「明确无依据/明显虚假」才置 1，区别于「证据不足未采纳」；累计达阈值触发反向处罚+降信誉，见 Review-Plan §4.5.3）',
`create_time`      datetime        not null default current_timestamp comment '举报时间',
`update_time`      datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
`active_key`       varchar(64)     generated always as (if(`status` < 3, concat(`reporter_id`, '-', `target_type`, '-', `target_id`), null)) stored comment '活跃举报去重键（status<3 非空，唯一索引兜底并发重复举报；status≥3 置空允许再次举报，MySQL 唯一索引忽略 NULL）',
unique key `uk_active` (`active_key`),
index `idx_target` (`target_type`, `target_id`),
index `idx_review_task_id` (`review_task_id`),
index `idx_status` (`status`),
index `idx_reporter_id` (`reporter_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '举报表（防刷：uk_active 生成列唯一索引兜底并发重复，应用层保留友好提示——同一 reporter_id+target_type+target_id 存在 status<3 的举报即拒绝）';

# 2.1 举报证据关联表
create table if not exists `report_evidence`
(
`report_id`   bigint unsigned not null comment '举报ID',
`file_id`     varchar(64)     not null comment '证据文件ID（引用 file_meta.file_id 业务主键，注意与 review_task.biz_id 引用的 file_meta.id 是两套引用）',
`create_time` datetime        not null default current_timestamp comment '关联时间',
primary key (`report_id`, `file_id`),
index `idx_file_id` (`file_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '举报证据关联表（JOIN file_meta 校验证据归属与 safe_flag；管理端展示走 REVIEWER/ADMIN 鉴权预签名直读，不受公开闸口占位影响——审核员是看图裁决的人，详见 Review-Architecture-Development-Plan.md §4.4.5）';

# 3. 申诉表
create table if not exists `appeal`
(
`id`            bigint unsigned not null auto_increment primary key comment '申诉ID',
`report_id`     bigint unsigned null comment '关联举报ID',
`punish_id`     bigint unsigned null comment '关联处罚记录ID（punish_log.id）',
`review_task_id` bigint unsigned null comment '关联审核任务ID（机审驳回申诉：无 report/punish 可关联，靠此定位被驳回内容并回写业务状态；report_id/punish_id/review_task_id 三者至少其一非空，应用层校验）',
`appellant_id`  bigint unsigned not null comment '申诉人ID',
`content`       varchar(500)    not null comment '申诉内容',
`status`        tinyint         not null default 0 comment '状态：0-待裁决 1-维持 2-撤销',
`judge_by`      bigint unsigned null comment '裁决审核员ID',
`judge_opinion` varchar(500)    null comment '裁决意见',
`decide_time`   datetime        null comment '裁决落库时间（judge 写入；② 内容级轮次新鲜度判定权威时间源——防同一驳回经 report/punish/review_task 多路径交叉或维持后换路径重复申诉，见 Review-Plan §4.2.3）',
`biz_type`      tinyint         not null comment '申诉目标内容业务类型（提交时由关联列解析落库：report_id→report.target_type 经 §4.5.2 映射、punish_id/review_task_id→其 biz_type；三者交叉须内容身份一致，冲突拒收）',
`biz_id`        bigint unsigned not null comment '申诉目标内容业务ID（同 biz_type 语义）',
`active_key`    varchar(64)     generated always as (if(`status` = 0, concat(`biz_type`, '-', `biz_id`), null)) stored comment '待裁决内容级去重键（status=0 非空，唯一索引兜底同内容经 report/punish/review_task 多路径并发重复申诉；裁决后置空，不阻断新一轮驳回后的新申诉）',
`create_time`   datetime        not null default current_timestamp comment '申诉时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
unique key `uk_content_active` (`active_key`),
index `idx_appellant_id` (`appellant_id`),
index `idx_content` (`biz_type`, `biz_id`),
index `idx_review_task_id` (`review_task_id`),
index `idx_report_id` (`report_id`),
index `idx_punish_id` (`punish_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = '申诉表';

# 4. 违规分累积表
create table if not exists `violation_score`
(
`user_id`            bigint unsigned not null primary key comment '用户ID',
`score`              int             not null default 0 comment '违规分累计',
`last_punish_action` tinyint         not null default 0 comment '上次处罚动作：0-无 1-禁言 2-封禁（只记影响登录态的处罚；punish_log.action 的 3-内容下架/4-警告 仅落流水不改本表，枚举不对齐系有意设计）',
`frozen_until`       datetime        null comment '禁言截止时间',
`update_time`        datetime        not null default current_timestamp on update current_timestamp comment '更新时间'
) engine = InnoDB
default charset = utf8mb4 comment = '违规分累积表（窄状态表：只存当前态，历史流水见 punish_log；最近处罚按 punish_log.user_id 倒序取一条，避免双写不一致）';

# 4.1 处罚流水表（append-only，与状态表分离）
create table if not exists `punish_log`
(
`id`             bigint unsigned not null auto_increment primary key comment '处罚ID',
`user_id`        bigint unsigned not null comment '被处罚用户ID',
`action`         tinyint         not null comment '处罚动作：1-禁言 2-封禁 3-内容下架（含机审首发驳回场景，此时内容从未发布）4-警告',
`score`          int             not null default 0 comment '本次处罚记入的违规分值快照（分值规则外置，落行快照使「按 status=1 行重放」确定可算；机审 action=3 通常记 0，不参与登录态阈值判定，见 Review-Plan §4.2.3）',
`reason`         varchar(200)    not null comment '处罚原因',
`biz_type`       tinyint         not null comment '关联违规业务类型（同 review_task.biz_type）',
`biz_id`         bigint unsigned not null comment '关联违规业务ID',
`review_task_id` bigint unsigned null comment '关联审核任务ID（回溯审核链路）',
`expire_time`    datetime        null comment '处罚截止时间（永久处罚为 null）',
`operator_id`    bigint unsigned not null default 0 comment '操作人ID（0-系统）',
`operator_name`  varchar(32)     not null comment '操作人名称（审计冗余）',
`status`         tinyint         not null default 1 comment '状态：1-生效 2-已撤销（申诉成立）',
`revoke_time`    datetime        null comment '撤销时间',
`create_time`    datetime        not null default current_timestamp comment '处罚时间',
index `idx_user_id` (`user_id`),
index `idx_review_task_id` (`review_task_id`),
index `idx_biz` (`biz_type`, `biz_id`)
) engine = InnoDB
default charset = utf8mb4 comment = '处罚流水表（历史行不可删除；申诉裁决=2-撤销 时 status 就地置 2 + violation_score 重算——与 moderation_record 的真 append-only（改判追加新行）区分，本表允许状态列就地更新、行不删）';

# 5. 敏感词库版本表（DFA 热更/缓存失效的版本单一来源：scene 级单调递增）
create table if not exists `sensitive_word_version`
(
`scene`       varchar(20)     not null comment '场景：ARTICLE/COMMENT/USERNAME/MESSAGE',
`version`     bigint          not null default 0 comment '该 scene 词库当前版本：任何变更（单条/批量增删改、启停、黑白切换）在同一事务内 +1；Redis Pub 携带此值，消费端 newVersion > curVersion 才重建（防乱序/重复 Pub 旧覆盖新）；缓存 key/留痕快照均取此单一来源，避免行级取版本不一致',
`updated_by`  bigint unsigned not null comment '最后变更人ID（审计）',
`update_time` datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
primary key (`scene`)
) engine = InnoDB
default charset = utf8mb4 comment = '词库版本表（DFA 热更与缓存失效的版本单一来源）';

# 5.1 敏感词库表（DFA 热更新数据源）
create table if not exists `sensitive_word_library`
(
`id`           bigint unsigned not null auto_increment primary key comment '词ID',
`word`         varchar(64)     not null comment '敏感词',
`scene`        varchar(20)     not null comment '场景：ARTICLE/COMMENT/USERNAME/MESSAGE',
`level`        tinyint         not null default 2 comment '级别：1-HIGH 2-MID',
`is_blacklist` tinyint         not null default 1 comment '是否黑名单：0-白名单 1-黑名单',
`status`       tinyint         not null default 1 comment '状态：0-禁用 1-启用',
`updated_by`   bigint unsigned not null comment '修改人ID（审计）',
`version`       bigint          not null default 0 comment '该词最后变更时的 scene 版本快照（只读参考，非版本源；版本源见 sensitive_word_version——行级取版本会因单条变更致同 scene 各行不一致）',
`create_time`  datetime        not null default current_timestamp comment '创建时间',
`update_time`  datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_scene_status` (`scene`, `status`),
unique key `uk_scene_word` (`scene`, `word`)
) engine = InnoDB
default charset = utf8mb4 comment = '敏感词库表（物理删除：唯一键与逻辑删除互斥，配置类小表可全量重建，删除即物理删、updated_by 留审计痕）';

# 6. 作家申请表
create table if not exists `author_apply`
(
`id`            bigint unsigned not null auto_increment primary key comment '申请ID',
`user_id`       bigint unsigned not null comment '申请人ID',
`pen_name`      varchar(32)     not null comment '笔名',
`creation_dir`  varchar(100)    not null comment '创作方向',
`masterpiece`   varchar(500)    null comment '代表作简介',
`status`        tinyint         not null default 0 comment '状态：0-待审 1-通过 2-驳回',
`reviewer_id`   bigint unsigned null comment '审核员ID',
`reject_reason` varchar(200)    null comment '驳回原因',
`create_time`   datetime        not null default current_timestamp comment '申请时间',
`update_time`   datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
index `idx_user_id` (`user_id`),
index `idx_status` (`status`)
) engine = InnoDB
default charset = utf8mb4 comment = '作家申请表';


-- ============================================================================
-- 模块：litisle-review 初始化种子数据
-- 位置：/docker-entrypoint-initdb.d/review-seed.sql（MySQL 首次启动按字母序自动执行）
-- 顺序：review-schema.sql（建表）→ review-seed.sql（本文件）→ seed-words-article.sql（词库本体）
-- 依赖：样本数据引用 user-schema.sql 的测试用户 1001~1006（无外键约束，执行顺序不敏感）
-- 注意：/docker-entrypoint-initdb.d 仅在数据卷首次初始化时执行；存量环境请手动执行一次
-- ============================================================================

use litisle_review;

-- 1. 词库版本基线（四文本场景，版本 0 = 空词库；词库本体由 seed-words-article.sql 灌入并 bump 版本）
-- insert ignore：seed-words-article.sql 稍后 upsert ARTICLE 时自然 +1，不冲突
insert ignore into `sensitive_word_version` (`scene`, `version`, `updated_by`) values
('ARTICLE',  0, 0),
('COMMENT',  0, 0),
('USERNAME', 0, 0),
('MESSAGE',  0, 0);

-- 2. 作家申请示例（READER 1001 提交申请，待审——供审核员队列联调）
INSERT INTO `author_apply` (`id`, `user_id`, `pen_name`, `creation_dir`, `masterpiece`, `status`, `create_time`)
VALUES (1, 1001, '追风少年', '都市 / 青春', '《晚风与海》短篇集', 0, NOW());

-- 3. 举报示例（两条状态：待处理 + 转人工）
-- 3.1 READER 1001 举报用户 1003（target_type=3-用户，target_id 即用户ID）；active_key 生成列自动填 '1001-3-1003'
INSERT INTO `report` (`id`, `reporter_id`, `target_type`, `target_id`, `reason`, `status`, `create_time`)
VALUES (1, 1001, 3, 1003, '该用户多次发布引战言论', 0, NOW());
-- 3.2 AUTHOR 1002 举报评论 1 转人工（status=2，回填 review_task_id=1 关联任务 4）
-- 供联调人审 decide 回写闭环：update report set status=3 where review_task_id=1 and status<3
INSERT INTO `report` (`id`, `reporter_id`, `target_type`, `target_id`, `reason`, `status`, `review_task_id`, `create_time`)
VALUES (2, 1002, 2, 1, '评论内容涉嫌赌博引流', 2, 1, NOW());

-- 4. 审核任务示例（评论 SUSPICIOUS 转人审——供人审队列/认领/裁决链路联调）
-- biz_id=1 为占位评论ID（联调时以内容服务实际评论为准）；content_hash 为占位指纹（64位hex）
INSERT INTO `review_task` (`id`, `biz_type`, `biz_id`, `scene`, `layer1_hit`, `hit_words`, `layer2_risk`,
                           `status`, `mod_verdict`, `content_hash`, `create_time`)
VALUES (1, 2, 1, 'COMMENT', 2, '赌博', 2,
        2, 3, '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', NOW());

-- 5. 机审留痕示例（与任务 1 对应的 DFA 审核记录，append-only）
-- mod_type=1-关键词DFA；verdict=3-SUSPICIOUS；operator 0-系统机审；wordlib_ver=0 对应空词库基线
INSERT INTO `moderation_record` (`id`, `mod_type`, `biz_type`, `biz_id`, `review_task_id`, `scene`,
                                 `content_hash`, `wordlib_ver`, `verdict`, `category`, `hit_words`, `reason`,
                                 `operator_id`, `operator_name`, `cost_ms`, `create_time`)
VALUES (1, 1, 2, 1, 1, 'COMMENT',
        '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef', 0, 3, 'abuse', '赌博', 'DFA命中赌博类敏感词',
        0, 'system', 3, NOW());

-- 6. 处罚流水示例（ADMIN 1005 对用户 1003 落一次警告，生效中）
-- biz_type 走 ModerationBizTypeEnum（同 review_task.biz_type）：2=评论，挂任务 4——勿用 TargetTypeEnum 的 3=用户（两套枚举错位）
-- action=4-警告：仅落流水不改 violation_score.last_punish_action（枚举不对齐系有意设计）
INSERT INTO `punish_log` (`id`, `user_id`, `action`, `score`, `reason`, `biz_type`, `biz_id`, `review_task_id`,
                          `operator_id`, `operator_name`, `status`, `create_time`)
VALUES (1, 1003, 4, 1, '评论涉嫌违规，首次违规警告', 2, 1, 1,
        1005, 'admin_test', 1, NOW());

-- 7. 违规分累积示例（用户 1003 累计 1 分；警告不影响登录态，last_punish_action 保持 0-无）
INSERT INTO `violation_score` (`user_id`, `score`, `last_punish_action`)
VALUES (1003, 1, 0);

-- 8. 申诉示例（已裁决-维持：挂任务 1 与处罚 1，供申诉列表/详情链路联调）
-- status=1-维持时 active_key 生成列为 NULL，不占用内容级去重键；裁决人 ADMIN 1005（原任务尚未裁决无回避冲突）
-- biz_type 走 ModerationBizTypeEnum：2=评论，与 punish_log.biz_type 同口径
INSERT INTO `appeal` (`id`, `report_id`, `punish_id`, `review_task_id`, `appellant_id`, `content`, `status`,
                      `judge_by`, `judge_opinion`, `decide_time`, `biz_type`, `biz_id`, `create_time`)
VALUES (1, NULL, 1, 1, 1003, '评论内容被误判，未涉及赌博仅为讨论剧情', 1,
        1005, '证据不足，维持原判', NOW(), 2, 1, NOW());

-- 9. 词库测试词（每文本场景 1 条，防 DFA 闸门装载失败拒启：词库表为空时 TableWordLibraryLoader
--    报「词库装载失败且无可用本地快照」并禁止放行；正式词库由管理端维护/seed-words-article.sql 灌入）
--    level=2-MID：命中走 LLM 复审而非同步拒绝，避免联调时误拦用户名注册/私信发送；
--    同词跨场景复用（uk_scene_word 以 scene+word 为键，同词各场景独立生效）
INSERT INTO `sensitive_word_library` (`word`, `scene`, `level`, `is_blacklist`, `status`, `updated_by`, `version`)
VALUES
('测试敏感词_文章',   'ARTICLE',  2, 1, 1, 0, 1),
('测试敏感词_评论',   'COMMENT',  2, 1, 1, 0, 1),
('测试敏感词_用户名', 'USERNAME', 2, 1, 1, 0, 1),
('测试敏感词_私信',   'MESSAGE',  2, 1, 1, 0, 1);

-- 测试词落库后各场景版本 bump 到 1（基线 insert ignore 已建 version=0 行，此处 upsert +1；
-- 热更/快照语义要求版本 >0：消费端 newVersion > curVersion 才重建；
-- ARTICLE 随后由 seed-words-article.sql 灌入正式词库继续单调 +1，不冲突）
INSERT INTO `sensitive_word_version` (`scene`, `version`, `updated_by`)
VALUES
('ARTICLE',  1, 0),
('COMMENT',  1, 0),
('USERNAME', 1, 0),
('MESSAGE',  1, 0)
ON DUPLICATE KEY UPDATE `version` = `version` + 1, `updated_by` = VALUES(`updated_by`);

# 设计原因：
#
# review_task 不加 del_flag：审核记录不可删除（设计文档审计规范），所有变更走 status 流转。
#
# review_task 唯一键 uk_biz_scene(biz_type, biz_id, scene) + 幂等 upsert 建任务：一个内容主体
# （同一 biz_type+biz_id+scene）只保留一行任务——否则编辑重发/举报复审/超时补偿重发会堆出
# 同键多行，消费端按此键定位歧义复发。幂等语义：命中已有行则复用同行（重置 status=0、
# 更新 content_hash、retry_count 归零），旧任务未终态时先追加一条 moderation_record 标记
# superseded 再重置（并清空 assignee/claim_time/decided_by/opinion/decide_time，防旧认领人残留；同时清空机审证据列
# mod_verdict=0/layer1_hit=0/hit_words=null/layer2_risk=0——重置仅因内容变更或终态触发，旧证据属
# 旧内容，不清则人审队列按 mod_verdict/layer2_risk 展示与排序时张冠李戴）；历史链路靠
# moderation_record（append-only）保留，不需多行任务——
# 与「review_task 是状态机、moderation_record 是流水」职责分离口径一致。
# 重置例外（多举报不打断在审）：仅 content_hash 变化（编辑重发）或已有任务终态（status∈{1,3,4}）才重置，
# 重置目标态按场景分列——发布场景置 0、*_RE_REVIEW 复审场景置 2（直进人审）；命中行 content_hash 未变
# 且非终态（status∈{0,2}）时只复用不重置（保留 status/assignee，仅回填 report.review_task_id），
# 防同内容第二条举报把已认领在审任务打回 0、清空 assignee。多举报同任务裁决：decide 回写
# `update report set status=3 where review_task_id=? and status<3` 一次闭环全部关联举报。
# （该唯一键覆盖 idx_biz 前缀，原 idx_biz 普通索引已移除避免冗余。）
#
# scene 场景枚举扩展与存量迁移：新增 ARTICLE_RE_REVIEW/COMMENT_RE_REVIEW/MESSAGE_RE_REVIEW 三个
# 举报复审场景（与发布场景分列，防举报转人工 upsert 撞已终态发布任务被重置）。scene 为 not null
# 无默认值：若存量表已有数据，ALTER 须先加可空列 → 按 biz_type 回填默认场景 → 再改 not null，
# 禁直接加 not null 无默认值列（存量迁移会失败）。
#
# 统一建任务规则：凡走异步机审的内容（文本/图片/私信）一律建 review_task——否则 SUSPICIOUS
# 进不了人审队列、申诉也无 appeal.review_task_id 可挂；图片 biz_type=6（biz_id=file_meta.id、
# scene=IMAGE、content_hash=文件 sha256）。唯一例外 USERNAME 同步拒绝不建任务，仅落
# moderation_record（biz_id=0），避免每次注册失败堆一行废任务。
#
# moderation_record 与 review_task 职责分离：review_task 是任务状态机（assignee/status 可变更），
# moderation_record 是审核流水（append-only，每次机审/人审/申诉改判追加一行，不 UPDATE）。
# 同一任务多次审核形成多行流水，链路可回溯；idx_hash 支撑审核缓存查重兜底（Redis miss 后查表，
# 数据丢失不重复烧模型费）；命中词/凭证/理由/场景（scene）四列自包含，单行即可对外出具审核凭证无需 JOIN
# （设计见 Review-Architecture-Development-Plan.md §4.4.1）。trace_id 与 provider_trace_id 分列：
# 前者是网关→MQ→消费端内部链路追踪 ID（排障），后者是云厂商/LLM 对外合规凭证（出证）；
# hit_words 用 text 与 review_task 同纪律，留痕写入宁存不下不可丢；
# idx_hash 三列 (content_hash, scene, id)，scene 前置防跨场景误复用，id 自增即时间序取最近一次结论免
# filesort；provider/wordlib_ver/model_version 列支撑兜底按厂商/词库/模型版本比对（防 onnx→aliyun、
# 词库热更、同 provider 模型/prompt 升级后复用旧结论）。
#
# appeal 表补 review_task_id 列：机审驳回（REJECT）场景既无 report 也无 punish，
# 申诉记录靠该列定位被驳回内容并回写业务状态（见 Review-Architecture-Development-Plan.md §4.2.3）。
# 防重复申诉走应用层校验：同一 review_task_id/report_id/punish_id 存在任意状态申诉即拒绝再次发起
# （含已裁决维持，防维持后反复申诉；review_task_id 可空不便建唯一索引，查询走既有
# idx_review_task_id；@Idempotent 只防请求级重复）。
#
# sensitive_word_library 同时支持黑/白名单与多场景：对应设计文档"评论加载白名单 / 用户名不加载白名单"的差异化策略，按 scene 过滤加载。IMAGE/ARTICLE_IMG_RE_REVIEW 等图片场景不走 DFA：
# WordLibraryLoader 仅加载文本场景（ARTICLE/COMMENT/USERNAME/MESSAGE），图片任务按 scene
# 查词库为空属预期，跳过不报错。
#
# DFA 闸门跨服务部署：DFA 门面与 WordLibraryLoader 下沉共享模块（litisle-api），发布链路在
# 各业务服务本地执行免网络；词库 SSOT 仍在 sensitive_word_library 表，变更经 Redis Pub +
# version 乐观比对广播各实例重建（见 Review-Architecture-Development-Plan.md §4.1/§4.3）；
# 新实例冷启动走 Redis 全量快照优先（词库热更时顺带重写快照 key），miss 时直连本库只读
# 词表兑底——业务方「不跨库直查」约定的唯一特例（配置类小表：无 join、无写竞争、只读）。
#
# violation_score 用 user_id 作主键：一用户一行，分数累加用 SQL 原子自增（set score = score + Δ）
# 与状态列条件更新，不引入 version 乐观锁——本表为窄状态表，重算依据在 punish_log 流水（每行 Δ
# 见 punish_log.score 快照），乐观锁属多余复杂度；Redis 计数器仅作人审队列/预警的快速视图
# （如违规分即将触顶提醒）。重放口径两作用域分列：① 展示/预警 score=全部 status=1 生效行累计
# （含机审 action=3）；② 自动禁言/封禁阈值判定=只重放 action∈{1,2}（影响登录态）的 status=1
# 行，机审 action=3 不计入——避免 Redis 与 DB 双写漂移导致误禁，且机审误判在申诉裁决前不触发
# 真实封禁（与「append-only 流水即重算依据」「机审不驱动自动处罚」口径一致）。
#
# author_apply 独立于 sys_user：申请记录需保留历史，多次申请形成流水。
