package com.litisle.litisleai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litisleai.domain.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 消息 Mapper（板块三 3.1：BaseMapper 通用 CRUD；消息跟随会话级联清理）
 *
 * @author litisle
 */
@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {
}
