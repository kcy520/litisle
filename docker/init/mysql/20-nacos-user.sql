-- e:\NewJavaPro\LitIsle\docker\init\mysql\20-nacos-user.sql
-- ============================================================================
-- Nacos 专用数据库账号初始化（最小权限）
-- 仅授予 nacos_config 单库权限（含 DDL）：
--   - DML（增删改查）：Nacos 2.x/3.x 日常运行所需
--   - DDL（建表/改表）：Nacos 3.x 首次启动的 configMigrateService 表结构迁移所需
-- 与业务账号（test_k.1）隔离，符合最小权限规范
--
-- 【注意】MySQL 官方镜像的 /docker-entrypoint-initdb.d 脚本只在数据卷首次初始化时执行。
-- 存量环境（数据卷已存在）需手动执行一次：
--   docker exec -i litisle-mysql mysql -uroot -pverysecret < docker/init/mysql/20-nacos-user.sql
-- ============================================================================
USE mysql;

CREATE USER IF NOT EXISTS 'nacos'@'%' IDENTIFIED BY 'nacos_pass';
GRANT ALL PRIVILEGES ON nacos_config.* TO 'nacos'@'%';
FLUSH PRIVILEGES;