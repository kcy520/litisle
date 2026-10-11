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

    /**
     * 查询用户处罚权威态快照（ReviewClient.getPunishStatus 提供方，临时清单 B1，5-2.2）
     *
     * <p>【覆盖式回查语义】查 punish_log 中 action∈(1-禁言,2-封禁) 且 status=1-生效 行，
     * 聚合为 PunishStatusDTO；无任何生效行返回全空快照（banned=false/muted=false），
     * auth 消费端据此覆盖本地快照=同步解封——无论撤销/生效一律以本快照覆盖，天然幂等</p>
     *
     * @param userId 用户 ID
     * @return 处罚权威态快照（非 null）
     */
    com.litisle.litisleapi.dto.moderation.PunishStatusDTO getPunishStatusSnapshot(Long userId);
}
