package com.litisle.litisleapi.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.config.FeignApiConfig;
import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;
import com.litisle.litisleapi.dto.moderation.UsernameRejectRecordDTO;
import com.litisle.litisleapi.fallback.ReviewClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 审核服务 Feign 客户端（跨服务契约）
 *
 * 【设计说明】
 * 1. 契约先行：本接口已就绪；提供方端点待 review 服务实现
 *    TODO litisle-review 实现 POST /api/feign/moderation/insert-username-reject-record（A3 取证落库）
 * 2. 路径前缀 /api/feign/moderation 遵循规范：网关未注册该前缀路由，仅服务间可用
 * 3. 用户名拒绝取证是注册拦截链路的审计增强写依赖，降级 fail-fast（见 ReviewClientFallbackFactory）：
 *    静默降级会丢取证行（误拦投诉无据可查），穿透异常由 auth 侧捕获仅 error 留痕、不影响拦截结论
 *
 * 【调用方】
 * 1. auth 模块（注册链路 USERNAME 同步拒绝取证，4-7）
 * 2. auth/user 模块（处罚消费端回查权威态，5-2.2/B1——网关不配置本端点路由，防公网直查处罚态）
 *
 * @author litisle
 */
@FeignClient(
        name = "litisle-review",
        configuration = FeignApiConfig.class,
        fallbackFactory = ReviewClientFallbackFactory.class
)
public interface ReviewClient {

    /**
     * 用户名注册拒绝取证落库（4-7.1 不建任务只取证）
     *
     * <p>【使用场景】auth 注册链路 DFA HIGH 命中（DfaPublishGate.decide 判定 blocked=true）时，
     * 经 UsernameRejectGate.recordReject → RecordAdapter 调用本端点，review 侧按口径追加
     * moderation_record 取证行（biz_type=3-用户名、biz_id=0 占位、scene=USERNAME、
     * mod_type=1-DFA、verdict=2-REJECT、不建 review_task、不进 MQ）；
     * 取证失败不影响注册拒绝结论（宁严勿松：取证是审计增强项，非拦截前置条件）</p>
     *
     * @param dto 取证载荷（contentHash + hitWords + wordlibVer，禁落用户名明文）
     * @return Result&lt;Void&gt;；载荷非法/落库失败时提供方返回 Result.fail，调用方 error 留痕
     */
    @PostMapping("/api/feign/moderation/insert-username-reject-record")
    Result<Void> insertUsernameRejectRecord(@RequestBody UsernameRejectRecordDTO dto);

    /**
     * 查询用户处罚权威态（禁言/封禁快照，5-2.2/B1）
     *
     * <p>【使用场景】auth/user 消费 moderation.punish.result 后按 userId 回查——
     * 「覆盖式」写本地快照：无论事件 op=1/2，以 review 侧现态为准，天然幂等且与到达顺序无关；
     * 撤销处罚时返回全空快照（banned=false/muted=false/frozenUntil=null），消费方据此同步解封</p>
     *
     * <p>【安全纪律】内部契约端点：网关不配置 /api/review/punish/status 路由，防公网直查处罚态；
     * 调用方需对结果做短 TTL 本地缓存削回查峰值，但 punishLogId 类流水 ID 严禁作版本门控</p>
     *
     * @param userId 用户 ID
     * @return 处罚权威态快照（无生效处罚时返回全空快照，非 null）
     */
    @GetMapping("/api/review/punish/status")
    Result<PunishStatusDTO> getPunishStatus(@RequestParam("userId") Long userId);
}
