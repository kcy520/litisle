package com.litisle.litislereview.service.impl;

import com.litisle.litislereview.domain.dto.AppealDecideDTO;
import com.litisle.litislereview.domain.dto.AppealSubmitDTO;
import com.litisle.litislereview.service.AppealService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 申诉链路服务实现（骨架版：逐方法 TODO 占位）
 */
@Service
@Slf4j
public class AppealServiceImpl implements AppealService {

    /** 提交申诉：TODO 归属校验 → 双层防重（关联列级+内容级）→ 落 appeal（bizType/bizId 解析固化） */
    @Override
    public void submitAppeal(AppealSubmitDTO dto, Long appellantId) {
        throw new UnsupportedOperationException("submitAppeal 待实现");
    }

    /** 裁决申诉：TODO ADMIN+原裁决人回避+confirm 双签；撤销联动 punish_log 撤销/违规分重算/举报批量回写 */
    @Override
    public void judgeAppeal(Long appealId, AppealDecideDTO dto, Long judgeId) {
        throw new UnsupportedOperationException("judgeAppeal 待实现");
    }
}
