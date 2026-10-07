package com.litisle.litislereview.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.Appeal;
import org.apache.ibatis.annotations.Mapper;

/**
 * appeal 申诉表 Mapper（MyBatis-Plus）
 *
 * 关键链路（实现 AppealService 时落地）：
 * - 关联列级防重：同一 review_task_id/report_id/punish_id 存在任意状态申诉即拒绝
 * - 内容级防重：selectCount(biz_type+biz_id, status=0 待裁决)，
 *   active_key 生成列唯一索引 uk_content_active 兜底并发
 * - 裁决 CAS：set status∈{1,2}, judge_by, judge_opinion, decide_time
 *   where id=? and status=0 → 终态拦截重复 judge
 * - 生成列 active_key 不可应用写入（insert/update 均 NEVER，见实体注解）
 */
@Mapper
public interface AppealMapper extends BaseMapper<Appeal> {
}
