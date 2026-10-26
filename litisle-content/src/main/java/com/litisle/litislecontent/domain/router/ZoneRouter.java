package com.litisle.litislecontent.domain.router;


import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litislecontent.enums.ZoneEnum;
import org.springframework.stereotype.Component;

/**
 * 分区路由权限校验（板块三 [3.3] 支撑组件，权限口径单点收口）：
 *
 * 【口径】zone=2 作家分区需 AUTHOR 角色；zone=1/null 普通分区放行。
 * （阶段12：原 zone=3 推荐流仅 ADMIN/运营 口径已随 RECOMMEND 枚举删除，推荐流为 zone 1/2 聚合视图）
 * 「一篇仅一分区」由 Article.zone 单值字段天然保证，不存在多分区冲突入口；
 * 编辑变更分区沿用发布同款权限（同口径复用）。
 *
 * 【收口范围】createAndPublish（A0）/ update（分区变更）统一调用本组件，
 * 禁止各业务入口再写内联 if 校验（防口径漂移）。
 *
 * @author litisle
 */
@Component
public class ZoneRouter {

    /**
     * 分区发布/变更权限校验（不满足由 Sa 抛 NOT_ROLE，统一 70002 拒绝）
     *
     * @param zone 目标分区（null 视为普通分区放行）
     */
    public void checkPublishPermission(ZoneEnum zone) {
        if (zone == ZoneEnum.WRITER) {
            SaRoleHelper.checkAuthor();
        }
    }
}
