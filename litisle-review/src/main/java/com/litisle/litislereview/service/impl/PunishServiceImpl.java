package com.litisle.litislereview.service.impl;

import com.litisle.litislereview.domain.vo.PunishLogVO;
import com.litisle.litislereview.service.PunishService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 处罚链路服务实现（骨架版：逐方法 TODO 占位）
 */
@Service
@Slf4j
public class PunishServiceImpl implements PunishService {

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
}
