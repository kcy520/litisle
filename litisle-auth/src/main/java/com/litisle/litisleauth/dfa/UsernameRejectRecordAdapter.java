package com.litisle.litisleauth.dfa;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.moderation.UsernameRejectRecordDTO;
import com.litisle.litisleapi.feign.ReviewClient;
import com.litisle.litisleapi.moderation.UsernameRejectGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 用户名拒绝取证落库适配器（auth 无 DB，经 ReviewClient 跨服务由 review 侧落库）
 *
 * 当用户名被 DFA 敏感词命中拒绝注册时，把"为什么拒绝"的证据（指纹+命中词+词库版本）异步落库到 review 服务的 moderation_record 表，
 * 供审计追溯——但取证失败绝不阻断注册拒绝本身。
 *
 * <p>【列口径收口】落库列口径（mod_type=1-DFA、biz_type=3-用户名、biz_id=0 占位、
 * scene=USERNAME、verdict=2-REJECT 等）由契约 UsernameRejectGate.RecordAdapter 声明，
 * review 提供方按口径写入；本类只负责载荷组装与失败降级</p>
 *
 * <p>【失败降级】Feign 异常/业务失败一律仅 error 留痕返回 false——取证是审计增强项，
 * 不影响注册拒绝结论（宁严勿松：拦截结论由 DfaPublishGate 独立给出）；连续失败须告警</p>
 *
 * @author litisle
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UsernameRejectRecordAdapter implements UsernameRejectGate.RecordAdapter {

    /** 审核服务 Feign 客户端（降级 fail-fast，异常在本类捕获降级） */
    private final ReviewClient reviewClient;

    /**
     * 落 moderation_record 取证行（经 review 侧）：不建 review_task、不进 MQ、不落用户名明文
     */
    @Override
    public boolean insertRejectRecord(UsernameRejectGate.RejectRecordContext context) {
        try {
            // 步骤1：组装取证载荷（仅派生指纹 + 命中词 + 词库版本，严禁携带用户名明文）
            UsernameRejectRecordDTO dto = new UsernameRejectRecordDTO();
            dto.setContentHash(context.contentHash());
            dto.setHitWords(context.hitResult().hitWords());
            dto.setWordlibVer(context.hitResult().wordlibVer());
            // 步骤2：跨服务取证落库
            Result<Void> result = reviewClient.insertUsernameRejectRecord(dto);
            // 步骤3：业务失败降级（提供方载荷闸门拒收等），仅 error 留痕返回 false
            if (result == null || !result.isSuccess()) {
                log.error("用户名拒绝取证落库业务失败（注册拒绝结论不变）: contentHash={}, msg={}",
                        context.contentHash(), result == null ? "null" : result.getMessage());
                return false;
            }
            return true;
        } catch (Exception e) {
            // Feign 穿透异常（降级工厂 fail-fast 抛出/网络故障）：仅 error 留痕，不阻断注册拒绝流程
            log.error("用户名拒绝取证落库异常（注册拒绝结论不变）: contentHash={}",
                    context.contentHash(), e);
            return false;
        }
    }
}