package com.litisle.litislereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.WordlibChangeAudit;
import org.apache.ibatis.annotations.Mapper;

/**
 * wordlib_change_audit 词库变更审计表 Mapper（MyBatis-Plus）
 *
 * <p>插入收口：仅 WordlibVersionService.bumpAndBroadcast 在版本推进同事务内调用
 * （红线：审计不同事务=不可信）；查询供追溯（误报灰度转正闭环等）</p>
 */
@Mapper
public interface WordlibChangeAuditMapper extends BaseMapper<WordlibChangeAudit> {
}
