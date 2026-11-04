package com.litisle.litislesearch.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislesearch.domain.entity.SearchHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 搜索历史 Mapper（批次六 6.2）。
 *
 * 【职责】search_history 单表 CRUD；分页统一使用 MyBatis-Plus Page 对象，
 * 禁止用 QueryWrapper#last() 拼接 LIMIT/ORDER BY。
 *
 * @author litisle
 */
@Mapper
public interface SearchHistoryMapper extends BaseMapper<SearchHistory> {
}
