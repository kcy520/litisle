package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.Tag;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Mapper + extends BaseMapper<Tag>（MyBatis-Plus）
 *
 * 关键查询：
 * - 打标签取ID：selectOne(name + del_flag=0) —— 唯一索引 uk_name；先查后插的并发窗口
 *   靠 uk_name 兜底（业务层捕获冲突后重查，标签池受控新建不放大）
 * - 热门 TOP N：selectList(status=NORMAL + del_flag=0，order by use_count desc limit N) ——
 *   走 idx_use_count，结果进 Redis 短缓存（防穿透+抖动 TTL）
 * - 标签改名：update(name，version 不在本表) —— 改名不影响 article_tag 关联与 content_hash（与正文方案B解耦）
 * - 封禁处置：update(status=BANNED) —— 封禁后列表侧不再聚合展示，关联关系保留待解封
 * - use_count 冗余计数：异步回刷 setSql("use_count = use_count + #{delta}") 原子自增
 */
@Mapper
public interface TagMapper extends BaseMapper<Tag> {

}