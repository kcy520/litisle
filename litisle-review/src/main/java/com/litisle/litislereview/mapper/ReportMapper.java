package com.litisle.litislereview.mapper;



import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.Report;
import org.apache.ibatis.annotations.Mapper;

/**
 * report 举报表 Mapper（MyBatis-Plus）
 *
 * 关键链路（实现 ReportService 时落地）：
 * - 提交防重：selectCount(reporter_id+target_type+target_id, status<3)，
 *   active_key 生成列唯一索引兜底并发（应用层保留友好提示，DB 层最终防线）
 * - 撤回 CAS：set status=4(已撤回) where id=? and reporter_id=? and status<3
 *   （仅举报人本人 + 仅活跃态；已建 review_task 不撤销）
 * - 申诉改判 PASS 批量回写：set status=3 where target_type=? and target_id=? and status<3
 *   （按内容身份回写同内容全部活跃举报，而非仅 appeal.report_id 关联行）
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {
}
