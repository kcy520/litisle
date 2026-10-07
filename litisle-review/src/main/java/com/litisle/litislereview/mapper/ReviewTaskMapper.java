package com.litisle.litislereview.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.ReviewTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * review_task 审核任务表 Mapper（MyBatis-Plus）
 *
 * 便捷方法由 BaseMapper<ReviewTask> 提供，以下关键 CAS 链路在实现 ReviewTaskService 时
 * 以 @Update 条件更新落地（受影响行数=0 即失败）：
 * - 认领 CAS：set status=2(已认领), assignee=?, claim_time=now()
 *   where id=? and status=2(人工待审) and assignee is null → affected=0 返回 70004（已被他人认领）
 * - 裁决终态 CAS：set status∈{3,4}, decided_by, opinion, decide_time
 *   where id=? and status=2 → 终态特性天然拦截重复 decide
 * - sweeper 超时回池：claim_time 早于阈值的 status=2 且无 decide_time 行 → 置回待审
 *   （判定用 claim_time 而非 update_time，详情查看会刷新 update_time）
 * - upsert 幂等：依赖 uk_biz_scene(biz_type,biz_id,scene)，复用/重置规则见实体 Javadoc
 */
@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {
}
