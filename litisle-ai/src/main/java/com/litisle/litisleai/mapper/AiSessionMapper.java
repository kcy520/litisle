package com.litisle.litisleai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleai.domain.entity.AiSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 会话 Mapper（板块三 3.1：BaseMapper 通用 CRUD；LRU 淘汰查询用 Lambda 分页排序，不拼 SQL 片段）
 *
 * @author litisle
 */
@Mapper
public interface AiSessionMapper extends BaseMapper<AiSession> {
}
