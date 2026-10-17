package com.litisle.litislecontent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislecontent.domain.entity.Book;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Mapper + extends BaseMapper<Book>（MyBatis-Plus）
 *
 * 关键查询：
 * - 书籍详情：selectOne(id + del_flag=0) —— 主键直达，下架(TAKEN_DOWN)仍可查（作者可见），展示侧过滤
 * - 作者书架：selectPage(author_id + del_flag=0，order by create_time desc) —— 走 idx_author_id
 * - 书籍列表（分区浏览）：selectPage(status != TAKEN_DOWN + del_flag=0) —— 对外列表排除下架
 * - 状态流转：update(status，version 乐观锁) —— 完结/下架/恢复，并发编辑由 @Version 兜底
 * - 删除为逻辑删（del_flag=1），书籍下所有章节(article) 的连带处置走业务层（正文方案B约束）
 */
@Mapper
public interface BookMapper extends BaseMapper<Book> {

}