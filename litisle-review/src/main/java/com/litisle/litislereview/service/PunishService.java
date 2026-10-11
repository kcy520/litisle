package com.litisle.litislereview.service;

import com.litisle.litislereview.domain.vo.PunishLogVO;

import java.util.List;

/**
 * 处罚链路服务（对应实体：punish_log + violation_score）
 *
 * 落地顺序建议：listMyPunish / getActivePunishes（PunishLog status=1）
 *
 * 【注入计划】实现时注入 PunishLogMapper；未来违规分重算/累加联动扩入本服务
 */
public interface PunishService {

    /**
     * 我的处罚流水：仅本人；reason 等 UGC 回显由 Controller/前端 sanitize
     */
    List<PunishLogVO> listMyPunish(Long userId);

    /**
     * 内部：查用户当前处罚态（登录判定/发帖前拦截用；仅返回 status=1 生效行）
     */
    List<PunishLogVO> getActivePunishes(Long userId);
}
