package com.litisle.litislereview.mapper;


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.PunishLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * punish_log 处罚流水表 Mapper（MyBatis-Plus，append-only 行不删）
 *
 * 关键链路（实现 PunishService / 申诉改判链路时落地）：
 - 登录态判定/我的处罚：selectList(user_id=?, status=1 生效)
 * - 阈值重放：只重放 action∈{1,2} 的 status=1 行（展示/预警口径含全部 status=1）
 * - 撤销（申诉成立）：set status=2, revoke_time=now() where id=? and status=1
 *   （允许状态列就地更新、行不删，与 moderation_record 真 append-only 区分）
 */
@Mapper
public interface PunishLogMapper extends BaseMapper<PunishLog> {
}
