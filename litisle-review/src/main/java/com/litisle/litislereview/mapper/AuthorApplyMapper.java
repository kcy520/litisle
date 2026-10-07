package com.litisle.litislereview.mapper;



import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.AuthorApply;
import org.apache.ibatis.annotations.Mapper;

/**
 * author_apply 作家申请表 Mapper（MyBatis-Plus）
 *
 * 关键链路（实现作家申请审核时落地）：
 * - 待审队列：selectList(status=待审)（REVIEWER/ADMIN）
 * - 本人最新申请：selectList(user_id=? order by create_time desc limit 1)（防重复提交校验用）
 * - 审核通过 → 经 UserClient 授予 AUTHOR 角色；驳回 → 记录原因通知申请人
 */
@Mapper
public interface AuthorApplyMapper extends BaseMapper<AuthorApply> {
}
