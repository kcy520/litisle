-- ============================================================================
-- 00-init-databases.sql
-- 功能：创建所有业务数据库 + 统一授权
-- 说明：MySQL Docker 首次启动时按字母序执行，00 前缀确保最先执行
-- 位置：/docker-entrypoint-initdb.d/00-init-databases.sql
-- ============================================================================

-- ==================== 创建业务数据库 ====================

CREATE DATABASE IF NOT EXISTS litisle_user
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS litisle_content
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Nacos 配置中心数据库（表结构由挂载的官方 mysql-schema.sql 创建：
--   ./docker/init/nacos/mysql-schema.sql → 容器内 10-nacos-schema.sql，
--   该脚本只有 CREATE TABLE，库必须在这里提前建好，否则首次初始化报 No database selected）
CREATE DATABASE IF NOT EXISTS nacos_config
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- Seata TC Server 存储库（表结构由 02-seata-schema.sql 创建：
--   global_table / branch_table / lock_table / distributed_lock / vgroup_table）
CREATE DATABASE IF NOT EXISTS seata_server
  DEFAULT CHARACTER SET utf8mb4
  COLLATE utf8mb4_unicode_ci;

-- 后续模块按需追加：
-- CREATE DATABASE IF NOT EXISTS litisle_social
--   DEFAULT CHARACTER SET utf8mb4
--   COLLATE utf8mb4_unicode_ci;
-- CREATE DATABASE IF NOT EXISTS litisle_payment ...

-- ==================== 统一授权 ====================

-- 给 test_k.1 授予所有业务数据库的全部权限
-- 生产环境应限制为具体 IP 或容器名，这里用 % 适配开发/Docker 环境
GRANT ALL PRIVILEGES ON litisle_user.*   TO 'test_k.1'@'%';
GRANT ALL PRIVILEGES ON litisle_content.* TO 'test_k.1'@'%';

-- Seata 专用账号（最小权限：仅 seata_server 库，含 DDL——首启建表需要）
-- 注意：密码硬编码在此处，必须与 .env 中 SEATA_DB_PASSWORD 保持一致
CREATE USER IF NOT EXISTS 'seata'@'%' IDENTIFIED BY 'seata_pass';
GRANT ALL PRIVILEGES ON seata_server.* TO 'seata'@'%';
-- Nacos 配置库授权（nacos_config 库由 docker/init/nacos/mysql-schema.sql 创建）
-- 另外， 00-init-databases.sql 里这行已过时（Nacos 现在用专用账号`nacos` ，不再用业务账号连配置库）：
-- GRANT ALL PRIVILEGES ON nacos_config.* TO 'test_k.1'@'%';

FLUSH PRIVILEGES;
