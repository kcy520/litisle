package com.litisle.litislereview.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.litisle.litislereview.domain.entity.ViolationScore;
import org.apache.ibatis.annotations.Mapper;

/**
 * violation_score 违规分窄状态表 Mapper（MyBatis-Plus，一用户一行，主键 user_id INPUT）
 *
 * 关键链路（实现 PunishService 时落地，禁用 version 乐观锁，重算依据在 punish_log 流水）：
 * - 原子累加：update set score = score + Δ where user_id=?（落行前 punish_log.score 快照已固化 Δ）
 * - 阈值判定条件更新：set last_punish_action, frozen_until where user_id=? and score >= 阈值
 *   （只记 action∈{1,2}；3-内容下架/4-警告 仅落 punish_log 不改本表）
 * - 首次插入竞态：insert 幂等（主键冲突则转原子累加）
 */
@Mapper
public interface ViolationScoreMapper extends BaseMapper<ViolationScore> {
}
