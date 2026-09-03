package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.user.AuthUserDTO;
import com.litisle.litisleapi.dto.user.RegisterUserDTO;
import com.litisle.litisleapi.feign.UserClient;
import com.litisle.litisleapi.dto.user.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * 当 auth 模块通过 UserClient 远程调用 user 模块失败时，自动执行这里的代码，而不是让异常直接抛到上层。
 * 如果走了 FallbackFactory，异常不会继续往上抛，异常处理器（@ExceptionHandler / @RestControllerAdvice）就“感知不到”这个远程异常了。
 * 降级方法里如果抛了异常，FallbackFactory 就“兜不住了”，异常会直接穿透到业务层，然后被全局异常处理器（@RestControllerAdvice）捕获。
 */
@Slf4j
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {

    @Override
    public UserClient create(Throwable cause) {
        return new UserClient() {
            @Override
            public Result<UserDTO> getUserById(Long userId) {
                log.error("远程调用 user 模块 getUserById 失败！id = {}", userId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "用户服务暂不可用，请稍后重试", cause);
            }

            /**
             * 不需要降级策略
             * <p>
             * 原因：注册是写操作，降级返回 null 会导致业务静默失败（用户以为注册成功实则未创建）。
             * 异常应直接穿透到 auth 模块，由全局异常处理器返回明确的错误提示。
             *
             * @param dto 注册用户 DTO
             * @return 永不返回（异常穿透）
             */
            @Override
            public Result<AuthUserDTO> createUser(RegisterUserDTO dto) {
                log.error("远程调用 user 模块 createUser 失败！username = {}", dto.getUsername(), cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "用户服务暂不可用，注册失败，请稍后重试", cause);
            }

            /**
             * 不需要降级策略
             * <p>
             * 原因：登录查询依赖实时用户数据，降级返回 null 会导致"用户不存在"的误判。
             * 异常应直接穿透，由全局异常处理器返回"用户服务暂不可用"提示。
             *
             * @param account 用户名/手机号/邮箱
             * @return 永不返回（异常穿透）
             */
            @Override
            public Result<AuthUserDTO> findByAccount(String account) {
                log.error("远程调用 user 模块 findByAccount 失败！account = {}", account, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "用户服务暂不可用，请稍后重试", cause);
            }

            /**
             * 不需要降级策略
             * <p>
             * 原因：根据邮箱查询用户是读操作，降级返回 null 会导致业务静默失败（用户以为邮箱已注册实则未注册）。
             * 异常应直接穿透到 auth 模块，由全局异常处理器返回明确的错误提示。
             *
             * @param email 邮箱
             * @return 永不返回（异常穿透）
             */
            @Override
            public Result<AuthUserDTO> findByEmail(String email) {
                log.error("远程调用 user 模块 findByEmail 失败！email = {}", email, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "用户服务暂不可用，请稍后重试", cause);
            }

            /**
             * 不需要降级策略
             * <p>
             * 原因：根据用户 ID查询用户是读操作，降级返回 null 会导致业务静默失败（用户以为用户存在实则不存在）。
             * 异常应直接穿透到 auth 模块，由全局异常处理器返回明确的错误提示。
             *
             * @param userId 用户 ID
             * @return 永不返回（异常穿透）
             */
            @Override
            public Result<AuthUserDTO> getAuthUserInfo(Long userId) {
                log.error("远程调用 user 模块 getAuthUserInfo 失败！userId = {}", userId, cause);
                throw new BizException(ErrorCode.REMOTE_SERVICE_ERROR, "用户服务暂不可用，请稍后重试", cause);
            }


        };
    }
}
