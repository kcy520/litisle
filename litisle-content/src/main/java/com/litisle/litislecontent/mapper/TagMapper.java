package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Update;

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

    /**
     * use_count 对账校正：仅更新与 article_tag 实际挂载数不一致的行（单条 UPDATE 自联，零事务竞争）
     * ——冗余计数兜底机制：挂载/摘除原子增减为主，对账定时校正漂移（防 bug/人工修数累积偏差）
     *
     * @return 被校正的行数（0 = 无漂移）
     */
    @Update("UPDATE tag t SET t.use_count = ("
            + " SELECT COUNT(*) FROM article_tag r WHERE r.tag_id = t.id)"
            + " WHERE t.del_flag = 0"
            + " AND t.use_count <> ("
            + " SELECT COUNT(*) FROM article_tag r2 WHERE r2.tag_id = t.id)")
    int reconcileUseCount();
}