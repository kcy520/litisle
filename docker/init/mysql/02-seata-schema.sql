-- ============================================================================
-- Seata Server 端建表脚本 (存储模式: db)
-- 来源: https://github.com/apache/incubator-seata/blob/2.5.0/script/server/db/mysql.sql
-- ============================================================================

CREATE DATABASE IF NOT EXISTS seata_server
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE seata_server;

-- 全局事务表
CREATE TABLE IF NOT EXISTS `global_table`
(
    `xid`                       VARCHAR(128) NOT NULL,
    `transaction_id`            BIGINT,
    `status`                    TINYINT      NOT NULL,
    `application_id`            VARCHAR(32),
    `transaction_service_group` VARCHAR(32),
    `transaction_name`          VARCHAR(128),
    `timeout`                   INT,
    `begin_time`                BIGINT,
    `application_data`          VARCHAR(2000),
    `gmt_create`                DATETIME,
    `gmt_modified`              DATETIME,
    PRIMARY KEY (`xid`),
    KEY `idx_status_gmt_modified` (`status` , `gmt_modified`),
    KEY `idx_transaction_id` (`transaction_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 分支事务表
CREATE TABLE IF NOT EXISTS `branch_table`
(
    `branch_id`         BIGINT       NOT NULL,
    `xid`               VARCHAR(128) NOT NULL,
    `transaction_id`    BIGINT,
    `resource_group_id` VARCHAR(32),
    `resource_id`       VARCHAR(256),
    `branch_type`       VARCHAR(8),
    `status`            TINYINT,
    `client_id`         VARCHAR(64),
    `application_data`  VARCHAR(2000),
    `gmt_create`        DATETIME(6),
    `gmt_modified`      DATETIME(6),
    PRIMARY KEY (`branch_id`),
    KEY `idx_xid` (`xid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 全局锁表
CREATE TABLE IF NOT EXISTS `lock_table`
(
    `row_key`        VARCHAR(128) NOT NULL,
    `xid`            VARCHAR(128),
    `transaction_id` BIGINT,
    `branch_id`      BIGINT       NOT NULL,
    `resource_id`    VARCHAR(256),
    `table_name`     VARCHAR(32),
    `pk`             VARCHAR(36),
    `status`         TINYINT      NOT NULL DEFAULT '0' COMMENT '0:locked ,1:rollbacking',
    `gmt_create`     DATETIME,
    `gmt_modified`   DATETIME,
    PRIMARY KEY (`row_key`),
    KEY `idx_status` (`status`),
    KEY `idx_branch_id` (`branch_id`),
    KEY `idx_xid` (`xid`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- 分布式锁表
CREATE TABLE IF NOT EXISTS `distributed_lock`
(
    `lock_key`   CHAR(20)    NOT NULL,
    `lock_value` VARCHAR(255) NOT NULL,
    `expire`     BIGINT,
    PRIMARY KEY (`lock_key`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

INSERT INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('AsyncCommitting', ' ', 0);
INSERT INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('RetryCommitting', ' ', 0);
INSERT INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('RetryRollbacking', ' ', 0);
INSERT INTO `distributed_lock` (lock_key, lock_value, expire) VALUES ('TxTimeoutCheck', ' ', 0);

-- 事务分组映射表 (Seata 2.x 新增)
CREATE TABLE IF NOT EXISTS `vgroup_table`
(
    `vGroup`    VARCHAR(255),
    `namespace` VARCHAR(255),
    `cluster`   VARCHAR(255),
    UNIQUE KEY `idx_vgroup_namespace_cluster` (`vGroup`, `namespace`, `cluster`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;


-- ============================================================================
-- Client 端 undo_log 建表脚本 (AT 模式必需)
-- 来源: https://github.com/apache/incubator-seata/blob/2.5.0/script/client/at/db/mysql.sql
-- 每个参与分布式事务的业务数据库都需要此表
-- ============================================================================

-- test-service 业务库
CREATE DATABASE IF NOT EXISTS microservices_demo_test
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE microservices_demo_test;

CREATE TABLE IF NOT EXISTS `undo_log`
(
    `branch_id`     BIGINT       NOT NULL COMMENT 'branch transaction id',
    `xid`           VARCHAR(128) NOT NULL COMMENT 'global transaction id',
    `context`       VARCHAR(128) NOT NULL COMMENT 'undo_log context,such as serialization',
    `rollback_info` LONGBLOB     NOT NULL COMMENT 'rollback info',
    `log_status`    INT(11)      NOT NULL COMMENT '0:normal status,1:defense status',
    `log_created`   DATETIME(6)  NOT NULL COMMENT 'create datetime',
    `log_modified`  DATETIME(6)  NOT NULL COMMENT 'modify datetime',
    UNIQUE KEY `ux_undo_log` (`xid`, `branch_id`)
) ENGINE = InnoDB AUTO_INCREMENT = 1 DEFAULT CHARSET = utf8mb4 COMMENT = 'AT transaction mode undo table';

ALTER TABLE `undo_log` ADD INDEX `ix_log_created` (`log_created`);
