package com.litisle.litislereview.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;
import com.litisle.litislereview.domain.entity.PunishLog;
import com.litisle.litislereview.domain.vo.PunishLogVO;
import com.litisle.litislereview.enums.PunishActionEnum;
import com.litisle.litislereview.enums.PunishStatusEnum;
import com.litisle.litislereview.mapper.PunishLogMapper;
import com.litisle.litislereview.service.PunishService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 处罚链路服务实现（骨架版：逐方法 TODO 占位）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PunishServiceImpl implements PunishService {

    /** 处罚流水 Mapper（append-only 行不删；权威态查询按 status=1 过滤） */
    private final PunishLogMapper punishLogMapper;

    /** 我的处罚流水：TODO 仅本人；status 全量展示，reason UGC 回显由前端 sanitize */
    @Override
    public List<PunishLogVO> listMyPunish(Long userId) {
        throw new UnsupportedOperationException("listMyPunish 待实现");
    }

    /** 内部：查用户当前处罚态（仅 status=1 生效行，登录判定/发帖前拦截用） */
    @Override
    public List<PunishLogVO> getActivePunishes(Long userId) {
        throw new UnsupportedOperationException("getActivePunishes 待实现");
    }

    /**
     * 查询用户处罚权威态快照（B1，5-2.2）：聚合生效禁言/封禁行为覆盖式快照
     * <p>单次索引查询（user_id 过滤，建议 punish_log 已建 user_id 索引）；
     * 无生效行返回全空快照（非 null），auth 消费端据此同步解封</p>
     */
    @Override
    public PunishStatusDTO getPunishStatusSnapshot(Long userId) {
        // 步骤1：查询生效禁言/封禁行（status=1 且 action∈(1,2)；action=3 内容下架/4 警告不进快照——
        //        内容下架不限制登录态，警告无拦截语义）
        List<PunishLog> rows = punishLogMapper.selectList(new LambdaQueryWrapper<PunishLog>()
                .eq(PunishLog::getUserId, userId)
                .eq(PunishLog::getStatus, PunishStatusEnum.EFFECTIVE)
                .in(PunishLog::getAction, List.of(PunishActionEnum.MUTE, PunishActionEnum.BAN)));
        // 步骤2：构造快照（默认全空=无任何生效处罚，auth 侧覆盖写入即同步解封）
        PunishStatusDTO dto = new PunishStatusDTO();
        dto.setUserId(userId);
        dto.setBanned(Boolean.FALSE);
        dto.setMuted(Boolean.FALSE);
        if (CollectionUtils.isEmpty(rows)) {
            return dto;
        }
        // 步骤3：聚合布尔态与禁言到期时间（封禁永久态不落 frozenUntil，banned 布尔即权威；
        //        禁言取生效行 expire_time，若为 null 视为永久禁言——布尔 muted 已表达拦截语义）
        for (PunishLog row : rows) {
            if (row.getAction() == PunishActionEnum.BAN) {
                dto.setBanned(Boolean.TRUE);
            } else if (row.getAction() == PunishActionEnum.MUTE) {
                dto.setMuted(Boolean.TRUE);
                dto.setFrozenUntil(row.getExpireTime());
            }
        }
        // 日志仅落 userId 与布尔态（禁落处罚理由等 UGC）
        log.info("处罚权威态快照查询完成: userId={}, banned={}, muted={}",
                userId, dto.getBanned(), dto.getMuted());
        return dto;
    }
}
