package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.ReadingProgress;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Mapper + extends BaseMapper<ReadingProgress>（MyBatis-Plus）
 *
 * 关键查询：
 * - 断点续读：selectOne(user_id + book_id) —— (user_id, book_id) 唯一键 uk_user_book，
 *   每人每书一条进度，update_time 即最后阅读时间
 * - 进度落库 upsert：insert 冲突则 update(chapter_id) —— 业务层捕获唯一键冲突转 update，
 *   或用 insert ... on duplicate key update 语义（MP 侧用 saveOrUpdate 思路组装）
 * - 我的书架：selectList(user_id，order by update_time desc) —— 走 uk_user_book 左前缀，
 *   联合 BookMapper 批量取书信息（in 查询防 N+1）
 * - 高频小写：进度上报有限流（ContentLimitConstant），DB 写压力可控，不引入缓存层
 */
@Mapper
public interface ReadingProgressMapper extends BaseMapper<ReadingProgress> {

}