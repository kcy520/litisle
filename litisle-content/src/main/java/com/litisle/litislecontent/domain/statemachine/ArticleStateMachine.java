package com.litisle.litislecontent.domain.statemachine;


import com.litisle.litislecontent.enums.ArticleStatusEnum;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.litisle.litislecontent.enums.ArticleStatusEnum.DRAFT;
import static com.litisle.litislecontent.enums.ArticleStatusEnum.PENDING_REVIEW;
import static com.litisle.litislecontent.enums.ArticleStatusEnum.PUBLISHED;
import static com.litisle.litislecontent.enums.ArticleStatusEnum.REJECTED;
import static com.litisle.litislecontent.enums.ArticleStatusEnum.TAKEN_DOWN;


//statemachine 包
//statemachine 是英文 State Machine（状态机）​ 的连写，在软件开发中通常指有限状态机（FSM）。
// 定位：它属于内容服务（litisle-content）的领域模型层（domain），专门用来存放“文章状态机”的核心逻辑。
//它的核心职责是作为文章状态流转的唯一校验收口（白名单机制），防止文章状态被随意、非法地修改
// （例如：已发布的文章不能直接退回到待审核）。


/// ArticleStateMachine 是一个“文章状态能不能这么改”的校验器，是整个系统里文章状态流转的「唯一合法入口守门员」。
///  给定「当前状态 from」和「目标状态 to」，判断这次状态变更是否合法
// 草稿 → 待审核 → 已发布 → 下架
//              ↘ 驳回 ↗


/**
 * 文章状态机：status 流转白名单唯一校验收口（next_update 板块一 [1.1]）
 * <p>状态字典：0-草稿 / 1-待审核 / 2-已发布 / 3-驳回 / 4-下架（终态），对齐 Content-Architecture §2.3</p>
 * <p>使用场景：发布 CAS 前预校验、审核结果回写（板块八）、申诉改判、可见性变更等所有 status 变更入口，
 * 业务侧 CAS（where status=? and del_flag=0）为并发兜底，本状态机为流转合法性前置防线</p>
 */
@Component
public class ArticleStateMachine {

    /**
     * 流转白名单：key=源状态，value=允许到达的目标状态集合
     * <p>不在白名单内的流转一律拒绝，核心红线：</p>
     * <p>① 禁 2→1（已发布禁回待审，改判只认申诉来源 moderation.appeal.result）</p>
     * <p>② 4-下架为终态，唯一出口 4→2 仅限申诉改判调用方（业务侧须保证来源为 moderation.appeal.result 消费端）</p>
     * <p>③ 2→4 仅复审下架兜底/封禁连带（宁严勿松，防违规内容滞留已发布态）</p>
     */
    private static final Map<ArticleStatusEnum, Set<ArticleStatusEnum>> TRANSIT_WHITELIST;

    static {
        Map<ArticleStatusEnum, Set<ArticleStatusEnum>> whitelist = new EnumMap<>(ArticleStatusEnum.class);
        // 0-草稿：发布送审（0→1）/ 免审直落（0→2）
        whitelist.put(DRAFT, EnumSet.of(PENDING_REVIEW, PUBLISHED));
        // 1-待审核：机审 PASS 回写（1→2）/ REJECT 首发驳回（1→3）
        whitelist.put(PENDING_REVIEW, EnumSet.of(PUBLISHED, REJECTED));
        // 2-已发布：仅复审下架兜底（2→4）；禁回 1-待审核
        whitelist.put(PUBLISHED, EnumSet.of(TAKEN_DOWN));
        // 3-驳回：修改重提（3→1）/ 免审直落竞态余量+申诉改判（3→2）
        whitelist.put(REJECTED, EnumSet.of(PENDING_REVIEW, PUBLISHED));
        // 4-下架：终态，唯一出口 4→2 仅限申诉改判（仅 moderation.appeal.result 来源）
        whitelist.put(TAKEN_DOWN, EnumSet.of(PUBLISHED));
        TRANSIT_WHITELIST = whitelist;
    }

    /**
     * 判断 status 流转是否合法（白名单校验）
     * <p>注意：4→2 虽在白名单内，但调用方必须保证来源为申诉改判链路（moderation.appeal.result 消费端），
     * 其他入口禁止调用本方法放行 4→2</p>
     *
     * @param from 当前状态
     * @param to   目标状态
     * @return true=允许流转；false=拒绝（含入参为 null）
     */
    public boolean canTransit(ArticleStatusEnum from, ArticleStatusEnum to) {
        if (from == null || to == null) {
            return false;
        }
        //从“状态流转白名单”里，查出当前状态 from 允许变成哪些状态；
        //然后判断目标状态 to 是否在允许的集合里。
        return TRANSIT_WHITELIST.getOrDefault(from, EnumSet.noneOf(ArticleStatusEnum.class)).contains(to);
    }
}
