package com.litisle.litislereview.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.ReportEvidence;
import org.apache.ibatis.annotations.Mapper;

/**
 * report_evidence 举报证据关联表 Mapper（MyBatis-Plus）
 *
 * ⚠️ 复合主键 (report_id, file_id)：BaseMapper 单键 CRUD 慎用（@TableId 仅 report_id 占位），
 * 证据的插入/删除/查询在实现 ReportService 时以自定义 SQL 或 LambdaQueryWrapper 维护；
 * file_id 引用 file_meta.file_id 业务主键（与 review_task.biz_id 引用 file_meta.id 是两套引用），
 * 展示证据图需经 FileClient 批量取 URL（REVIEWER/ADMIN 鉴权口径）
 */
@Mapper
public interface ReportEvidenceMapper extends BaseMapper<ReportEvidence> {
}
