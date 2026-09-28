-- ============================================================================
-- file-schema.sql
-- 模块：litisle-file
-- 数据库：litisle_file
-- 说明：SeaweedFS 文件元数据 + 引用登记（方案C 秒传共享），含 Seata AT 模式 undo_log
-- 位置：/docker-entrypoint-initdb.d/file-schema.sql（MySQL 首次启动按字母序自动执行）
-- 参考：user-schema.sql（风格保持一致）
-- 设计来源：docs/Sql-Document.md 第八节「litisle-file 模块」
-- ============================================================================

create database if not exists litisle_file
    character set utf8mb4
    collate utf8mb4_unicode_ci;

use litisle_file;

/* seata 的 undolog表（AT 模式硬性要求：file_ref 登记/解绑需与 user/content 等业务表
   同一全局事务（跨服务 AT 分支），每个参与全局事务的库都要有 undo_log） */
CREATE TABLE IF NOT EXISTS `undo_log`
(
    `branch_id`     BIGINT       NOT NULL COMMENT 'branch transaction id',
    `xid`           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    `context`       VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    `rollback_info` LONGBLOB     NOT NULL COMMENT 'rollback info',
    `log_status`    INT          NOT NULL COMMENT '0:normal status,1:defense status',
    `log_created`   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    `log_modified`  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='AT transaction mode undo table';

/* ================= 1. 文件元数据表 ================= */
create table if not exists `file_meta`
(
    `id`          bigint unsigned not null auto_increment primary key comment '主键ID',
    `file_id`     varchar(64)     not null unique comment 'SeaweedFS 文件ID（业务主键）',
    `owner_id`    bigint unsigned not null comment '上传者ID',
    `biz_type`    varchar(20)     not null comment '业务类型：AVATAR/COVER/ARTICLE_IMG/LICENSE',
    `bucket`      varchar(32)     not null comment 'SeaweedFS collection',
    `path`        varchar(255)    not null comment 'Filer 路径',
    `mime`        varchar(64)     not null comment 'MIME 类型',
    `size`        bigint unsigned not null comment '文件大小（字节）',
    `width`       int             null comment '图片宽度',
    `height`      int             null comment '图片高度',
    `align`       tinyint         null comment '对齐：1-左 2-左偏中 3-中 4-中偏右 5-右（图片专用）',
    `access`      tinyint         not null default 0 comment '访问级别：0-私有 1-公开',
    `status`      tinyint         not null default 0 comment '上传状态：0-上传中（锁内占位写库）1-上传完成（锁外 put 成功后更新）；put 失败保留 0，由对账任务清理（见 seaweedfs笔记.md §3.4）',
    `safe_flag`   tinyint         not null default 2 comment '【预留｜已定接入，暂未开发】违规审核（三态 default-deny）：0-违规（禁止访问）1-正常 2-待检测（默认；放行以检测完成为前提，而非未检出违规，避免异步检测窗口内未检图被放行）；启用链路：status=1 后异步消息 → 病毒/违规检测 → 写本列；getUrl/batchGetUrls 返回前校验（访问闸口，见 seaweedfs笔记.md §3.6/§3.6.3）',
    `sha256`      varchar(64)     not null comment 'SHA256 摘要（联合判重键：sha256+size+biz_type，PRIVATE 追加 owner_id 隔离）',
    `del_flag`    tinyint         not null default 0 comment '删除标志：仅当 file_ref 无活跃引用才可置 1（方案C，防秒传共享悬空他人引用）；置 1 后不再参与秒传判重',
    `create_time` datetime        not null default current_timestamp comment '上传时间',
    `update_time` datetime        not null default current_timestamp on update current_timestamp comment '更新时间',
    `delete_time` datetime        null comment '软删除时间（del_flag 置 1 时写入，物理清理按此判超 7 天）',
    index `idx_owner_biz` (`owner_id`, `biz_type`),
    index `idx_dedup` (`sha256`, `size`, `biz_type`, `del_flag`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '文件元数据表';

/* ================= 2. 文件引用登记表（方案C：秒传使 file_meta 成为多方共享资产，删除=解绑引用，
   物理清理前置校验本表，杜绝悬空引用；详见 seaweedfs笔记.md §3.4） ================= */
create table if not exists `file_ref`
(
    `id`          bigint unsigned not null auto_increment primary key comment '主键ID',
    `file_id`     varchar(64)     not null comment '引用的文件ID（file_meta.file_id）',
    `biz_type`    varchar(20)     not null comment '引用方业务类型：AVATAR/COVER/ARTICLE_IMG/LICENSE',
    `biz_id`      bigint unsigned not null comment '引用方业务主键：如 article_image.id / 换头像场景为 sys_user.id',
    `owner_id`    bigint unsigned not null comment '引用归属用户ID（审计与授权依据）',
    `create_time` datetime        not null default current_timestamp comment '引用建立时间',
    unique key `uk_ref` (`file_id`, `biz_type`, `biz_id`, `owner_id`),
    index `idx_file` (`file_id`),
    index `idx_biz` (`biz_type`, `biz_id`)
    ) engine = InnoDB
    default charset = utf8mb4 comment = '文件引用登记表：业务方绑定/解绑引用时与业务表同事务登记';

-- uk_ref 四元组唯一索引：bindRef 的 check-then-act（selectCount→insert）在并发下会双插，
-- 唯一索引兜底（后到者被 DB 拒绝，代码侧吞 DuplicateKeyException 维持幂等，见 FileServiceImpl#bindRef）。
-- 注意：uk_ref 最左前缀即 file_id，idx_file 已冗余（暂保留，如需精简可后续移除）。

-- **设计原因**：
--
-- - `file_id` 业务主键 + 唯一索引：业务层只持有 fileId，与 SeaweedFS 解耦。
-- - `idx_dedup` 联合判重索引：上传前按 (sha256+size+biz_type) 查重，命中直接复用 fileId 实现秒传；
--   判重限定 biz_type → 命中记录 bucket 必等于本次请求 bucket，URL 永不跨桶坏链（seaweedfs笔记.md §3.4.5）；
--   PRIVATE/LICENSE 去重追加 owner_id（最左前缀匹配 + 回表过滤，后期量大再扩 owner_id 进索引）。
-- - `access` 区分公开/私有：公开文件走 CDN 直链，私有文件走预签名短时效 URL。
-- - `safe_flag` 预留列：违规信息审核（已定接入、暂未开发）。建表一次到位避免将来改表，三态化
--   default-deny（default 2）：公开资源放行以"检测完成"为前提，而非"未检出违规"；启用仅需
--   MQ 异步消费写本列，主链路零改动（设计见 seaweedfs笔记.md §3.6）。
-- - 闸口按曝光面分层放行：高曝光（ARTICLE_IMG/COVER，公开可见）仅 safe_flag=1 放行、2 展示占位图；
--   低曝光（AVATAR / LICENSE，私有仅本人/管理员可见）2 放行、检出违规置 0 并强制刷新。
-- - `align` 放此表为冗余可选：实际对齐信息以 article_image 为准，此处冗余便于文件维度查询。
-- - `file_ref` 引用登记表（方案C）：秒传使一条 file_meta 成为多方共享资产（B 秒传命中 A 的
--   记录并把 fileId 写进业务表），而原有"owner 删除 → 7 天后物理清理"会悬空 B 的所有引用
--   ——7 天缓冲只防"误删恢复"，不防"共享悬空"。规则：
--   ① 业务方绑定/解绑引用时同步写/删 file_ref（与业务表同事务，跨服务场景走 Seata AT）；
--   ② delete 接口仅解绑当前调用方引用；仅当 file_ref 无活跃引用才置 del_flag=1；
--   ③ 对账任务物理清理前置校验 file_ref 无记录（仍有引用则跳过并告警），杜绝悬空引用；
--   ④ 引用可审计：随时可查"谁在引用该文件"，漏登记可定位。
