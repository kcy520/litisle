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

FLUSH PRIVILEGES;
