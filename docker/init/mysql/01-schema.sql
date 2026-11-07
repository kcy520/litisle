-- =============================================================================
-- 初始化顺序入口 1/2：建库建表（schema）
--
-- 背景：MySQL 官方镜像的 docker-entrypoint 只执行 /docker-entrypoint-initdb.d 这一层的
--       *.sql / *.sh，不会递归子目录。本文件显式 source 子目录内的各模块 schema，
--       保证「全新初始化」时也能按固定顺序执行。
--
-- 为什么用绝对路径：docker-entrypoint 调用 mysql 客户端时工作目录不是本目录，
--       相对路径会解析失败；/docker-entrypoint-initdb.d 是镜像内的挂载点，绝对路径稳定。
--
-- 执行顺序约束：本文件（01）→ 02-seata-schema.sql → 03-seed.sql → 10/20 nacos。
--       文件名字典序保证 01 < 02 < 03 < 10 < 20，schema 一定早于种子数据。
--
-- 幂等：各 schema 文件均为 create database/table if not exists，重复执行不报错、不丢数据。
-- =============================================================================

-- 字符集修正（2026-11-06）：容器内 mysql 客户端默认 character_set_client=latin1，
-- 直接执行 UTF-8 的 SQL 文件会把中文写坏（分类名/表注释变 mojibake）。
-- 入口层统一 SET NAMES utf8mb4，被 source 的 schema 文件继承同一会话字符集。
SET NAMES utf8mb4;

-- content 模块（含批次十一合并回来的统计/曝光/负反馈/点赞表，本文件即最终结构）
source /docker-entrypoint-initdb.d/schema/content-schema.sql;

-- user 模块（账号、资料、角色；内置角色与 1001~1006 示例用户种子）
source /docker-entrypoint-initdb.d/schema/user-schema.sql;

-- review 模块（审核工单 + 敏感词库表结构，词表数据见 seed/review-seed.sql）
source /docker-entrypoint-initdb.d/schema/review-schema.sql;

-- file 模块（对象存储元数据）
source /docker-entrypoint-initdb.d/schema/file-schema.sql;

-- search 模块（搜索历史）
source /docker-entrypoint-initdb.d/schema/search-schema.sql;
