-- =============================================================================
-- 初始化顺序入口 2/2：种子数据（seed）
--
-- 背景：种子文件按模块放在 seed/ 子目录（每模块一个文件），而 MySQL 官方镜像
--       不会递归子目录，所以由本入口文件显式 source，顺序固定在 schema 之后
--       （文件名 03 排在 01/02 之后，字典序可控）。
--
-- 幂等：种子文件统一使用固定 ID + INSERT IGNORE，重复执行不报错、不产生重复行；
--       review 词表按 (scene, word) 去重，版本号行按场景唯一键去重。
--
-- 顺序：user → content → review。content 种子只引用用户 ID（无跨库外键），
--       user 先执行可保证演示用户存在；review 词表与业务数据无关，放最后。
--
-- 字符集修正（2026-11-06）：容器内 mysql 客户端默认 latin1，直接执行 UTF-8 种子会把
-- 中文（文章/书籍分类名、敏感词）写成 mojibake（双重编码），导致分类屏蔽/词库匹配失效。
-- 入口层统一 SET NAMES utf8mb4，被 source 的种子文件继承同一会话字符集。
-- =============================================================================

SET NAMES utf8mb4;

-- user 模块种子：2001/2003 读者、2002 作家（不重复 user-schema.sql 内置的 1001~1006）
source /docker-entrypoint-initdb.d/seed/user-seed.sql;

-- content 模块种子：文章/书籍/章节/标签/收藏/阅读历史 + 统计基线（业务表与统计表同文件自洽）
source /docker-entrypoint-initdb.d/seed/content-seed.sql;

-- review 模块种子：敏感词库（5 个场景），content 启动时的词库快照依赖本文件
source /docker-entrypoint-initdb.d/seed/review-seed.sql;
