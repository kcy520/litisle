package com.litisle.litisleai.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleai.domain.entity.AiSession;
import com.litisle.litisleai.enums.AiAgentType;
import com.litisle.litisleai.enums.XiaoyueMode;
import com.litisle.litisleai.service.AiSessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.Serializable;
import java.util.List;

/**
 * AI 会话 REST 接入层（板块三 3.1：会话创建/列表/删除，路径口径 AI-Architecture §7.1）
 *
 * <p>【职责边界】controller 只做协议转换与统一响应包装；LRU 淘汰、消息上限、软删懒删、
 * 审计留痕全部收口在 AiSessionService；消息明细不在本层暴露（防会话内容经列表接口泄露）。</p>
 *
 * <p>【身份口径】userId 属于登录态数据，禁止客户端传入：本层统一以 Sa-Token 当前登录用户
 * （StpUtil.getLoginIdAsLong）作为归属依据，三个端点均要求登录，不存在 userId 参数（防越权）。</p>
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
     * @param request 创建请求（归属用户取自登录态）
     * @return 新建会话
     */
    @PostMapping
    public Result<AiSession> create(@RequestBody SessionCreateRequest request) {
        // agentType/mode 已为 DictEnum 枚举类型：Jackson 直接绑定（name/desc 兼容，
        // 未知值由反序列化失败统一 400，防脏编码落库）；agentType 缺省默认小悦
        AiSession session = sessionService.createSession(currentUserId(),
                request.getAgentType() == null ? AiAgentType.XIAOYUE : request.getAgentType(),
                request.getMode(), request.getTitle());
        return Result.success(session);
    }

    /**
     * 会话列表（GET /ai/sessions：当前登录用户全部未删会话，按最后活跃时间倒序）
     *
     * @return 会话列表
     */
    @GetMapping
    public Result<List<AiSession>> list() {
        return Result.success(sessionService.listSessions(currentUserId()));
    }

    /**
     * 删除会话（DELETE /ai/sessions/{sessionId}：删除请求由 AI 侧受理——软删懒删 + 定时兜底物理清理）
     *
     * @param sessionId 会话 ID
     * @return 受理结果
     */
    @DeleteMapping("/{sessionId}")
    public Result<Void> delete(@PathVariable("sessionId") Long sessionId) {
        sessionService.deleteSession(currentUserId(), sessionId);
        return Result.success(null);
    }

    /**
     * 取当前登录用户 ID（未登录时抛出未登录异常，由 SaTokenExceptionHandler 转 10002 标准错误）
     *
     * @return 当前登录用户 ID
     */
    private Long currentUserId() {
        return StpUtil.getLoginIdAsLong();
    }

    /**
     * 创建会话请求体
     */
    @Data
    public static class SessionCreateRequest implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Agent 类型（可空，默认 XIAOYUE；AiAgentType 实现 DictEnum，name/desc 均可绑定） */
        private AiAgentType agentType;

        /** 小悦模式（可空：POLISH/STRUCTURE/SUMMARY/DEEP；XiaoyueMode 实现 DictEnum，name/desc 均可绑定） */
        private XiaoyueMode mode;

        /** 会话标题（可空，首条消息摘要） */
        private String title;
    }
}
