package com.litisle.litislereview.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.ModerationRecordArchive;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审核记录归档表 Mapper（只读历史存储写入通道——6-4 §4.4.1）
 * <p>唯一消费方 = ModerationRecordArchiveSweeper 原子迁档事务（insert 幂等迁档 + id 主键兜底）；
 * 仅继承 BaseMapper 的 insert/select 能力使用，不定义任何 update/delete 自定义方法，
 * 与「应用账号仅授 INSERT/SELECT」的 DBA 权限纪律同口径</p>
 */
@Mapper
public interface ModerationRecordArchiveMapper extends BaseMapper<ModerationRecordArchive> {
}
