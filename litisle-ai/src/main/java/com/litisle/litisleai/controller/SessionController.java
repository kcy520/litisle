package com.litisle.litisleai.controller;

import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.domain.entity.AiSession;
import com.litisle.litisleai.service.AiSessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;
import java.util.List;

/**
 * AI 会话 REST 接入层（板块三 3.1：会话创建/列表/删除，路径口径 AI-Architecture §7.1）
 *
 * <p>【职责边界】controller 只做协议转换与统一响应包装；LRU 淘汰、消息上限、软删懒删、
 * 审计留痕全部收口在 AiSessionService；消息明细不在本层暴露（防会话内容经列表接口泄露）。</p>
 *
 * <p>【网关口径】/ai/** 业务前缀按业务指南由网关以 /api/ai/** 暴露（落地前须在
 * Api-Architecture 定稿登记；创建/删除端点为 3.1 功能必需补充项，一并登记）。</p>
 *
 * @author litisle
 */
@RestController
@RequestMapping("/ai/sessions")
@RequiredArgsConstructor
public class SessionController {

    /** 会话治理服务 */
    private final AiSessionService sessionService;

    /**
     * 创建会话（POST /ai/sessions：暂存区默认；达 200 上限 LRU 淘汰最久未活跃）
     *
     * @param request 创建请求
     * @return 新建会话
     */
    @PostMapping
    public Result<AiSession> create(@RequestBody SessionCreateRequest request) {
        // userId 必传（归属与配额治理基础）
        if (request == null || request.getUserId() == null) {
            throw new com.litisle.commoncore.exception.BizException(ErrorCode.PARAM_ERROR, "userId 必传");
        }
        AiSession session = sessionService.createSession(request.getUserId(),
                request.getAgentType() == null ? AiSessionService.AGENT_TYPE_XIAOYUE : request.getAgentType(),
                request.getMode(), request.getTitle());
        return Result.success(session);
    }

    /**
     * 会话列表（GET /ai/sessions：该用户全部未删会话，按最后活跃时间倒序）
     *
     * @param userId 用户 ID
     * @return 会话列表
     */
    @GetMapping
    public Result<List<AiSession>> list(@RequestParam("userId") Long userId) {
        return Result.success(sessionService.listSessions(userId));
    }

    /**
     * 删除会话（DELETE /ai/sessions/{sessionId}：删除请求由 AI 侧受理——软删懒删 + 定时兜底物理清理）
     *
     * @param sessionId 会话 ID
     * @param userId    用户 ID（归属校验）
     * @return 受理结果
     */
    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable("sessionId") Long sessionId, @RequestParam("userId") Long userId) {
        sessionService.deleteSession(userId, sessionId);
        return Result.success(null);
    }

    /**
     * 创建会话请求体
     */
    @Data
    public static class SessionCreateRequest implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 用户 ID（必传） */
        private Long userId;

        /** Agent 类型（可空，默认 XIAOYUE） */
        private String agentType;

        /** 小悦模式（可空：POLISH/STRUCTURE/SUMMARY/DEEP） */
        private String mode;

        /** 会话标题（可空，首条消息摘要） */
        private String title;
    }
}
