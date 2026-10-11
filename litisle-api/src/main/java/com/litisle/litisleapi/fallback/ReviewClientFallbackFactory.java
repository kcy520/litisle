package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.moderation.PunishStatusDTO;
import com.litisle.litisleapi.dto.moderation.UsernameRejectRecordDTO;
import com.litisle.litisleapi.feign.ReviewClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * ReviewClient 降级工厂
 *
 * 【降级语义】fail-fast：用户名拒绝取证是注册拦截链路的审计增强写依赖，静默降级会丢
 * 取证行（误拦投诉无据可查）；异常穿透到 auth 侧由 RecordAdapter 捕获仅 error 留痕，
 * 注册拒绝结论不受影响（取证失败不影响拦截，宁严勿松）
 *
 * @author litisle
 */
@Slf4j
public class ReviewClientFallbackFactory implements FallbackFactory<ReviewClient> {

    @Override
    public ReviewClient create(Throwable cause) {
        return new ReviewClient() {

            /**
             * 取证写依赖 fail-fast：静默返回成功会让 auth 侧误判取证已落库，
             * 穿透异常由调用方适配器捕获降级为「取证失败留痕」
             */
            @Override
            public Result<Void> insertUsernameRejectRecord(UsernameRejectRecordDTO dto) {
                log.error("远程调用 review 模块 insertUsernameRejectRecord 失败！contentHash = {}",
                        dto == null ? null : dto.getContentHash(), cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "审核服务暂不可用，取证落库失败", cause);
            }

            /**
             * 处罚权威态回查 fail-fast（5-2.2/B1）：静默返回空快照会让消费端误判「无处罚」
             * 覆盖本地致漏封——漏封是安全风险，禁静默吞错；异常穿透到消费端走
             * 指数退避重试/耗尽入 DLQ，由双向对账 sweeper（5-3.2）兑底补漏
             */
            @Override
            public Result<PunishStatusDTO> getPunishStatus(Long userId) {
                log.error("远程调用 review 模块 getPunishStatus 失败！userId = {}", userId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "审核服务暂不可用，处罚态回查失败", cause);
            }
        };
    }
}