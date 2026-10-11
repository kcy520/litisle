package com.litisle.litisleapi.dto.content;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 发布前预检请求 DTO（板块四 4-5 / Review-Plan §4.2.1 ②）
 *
 * 【端点契约】POST /api/content/preview-check（content 服务实现，嵌入挂起待 content 开发）
 *
 * 【口径铁律】
 * 1. 预检必须复用 DfaFacade.check 同 scene 同词库版本快照——与发布闸门（DfaPublishGate）
 *    同一版本源（WordLibraryLoader），防「预检 PASS 后词库热更，发布时被新词拦截」的口径漂移；
 *    禁止预检侧另建 DFA 实例或读不同快照；
 * 2. 场景仅开放文本三场景：ARTICLE/COMMENT/MESSAGE（scene 原始值，由 DfaFacade 经
 *    DfaScenes 归一）；USERNAME 不开放预检（注册即终态，无「预检-再试」路径，4-7）；
 *    图片场景（IMAGE/_RE_REVIEW）传错时直接拒绝；
 * 3. 【禁回吐】响应只允许 {hit, risk}（ContentPreviewCheckVO），严禁扩展返回
 *    hitWords/命中位置——命中词表是审核资产，回吐=向攻击者开放词库探测接口（§2.12）；
 * 4. 【鉴权】登录态即可（本人草稿预检，不校验草稿归属——纯文本 DFA 预检不落库
 *    不建任务，无越权面）；网关白名单不得收录本端点；
 * 5. 【限频】实现侧必须挂 @RateLimiter(USER)（阈值外置 yml，与既有发布链路对齐），
 *    防高频预检探测词库边界；预检结果不替代发布闸门（发布时仍走完整 4-1/4-2 链路）。
 *
 * @author litisle
 */
@Data
public class ContentPreviewCheckDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审核场景（仅 ARTICLE/COMMENT/MESSAGE；USERNAME/图片场景不开放预检） */
    @NotBlank(message = "审核场景不能为空")
    private String scene;

    /** 标题（可空；与正文拼接口径同 SubmitTextAssembler，预检文本=标题+分隔符+正文） */
    @Size(max = 100, message = "标题长度不能超过100字符")
    private String title;

    /** 正文/内容文本（去标签前原文；空内容校验同 PublishTextPrecheck 口径） */
    @NotBlank(message = "内容不能为空")
    private String content;
}
