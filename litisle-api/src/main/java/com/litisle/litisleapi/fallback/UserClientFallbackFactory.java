package com.litisle.litisleapi.fallback;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.feign.UserClient;
import com.litisle.litisleapi.dto.user.UserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

@Slf4j
public class UserClientFallbackFactory implements FallbackFactory<UserClient> {

    @Override
    public UserClient create(Throwable cause) {
        return new UserClient() {
            @Override
            public Result<UserDTO> getUserById(Long userId) {
                log.error("远程调用 user 模块 getUserById 失败！id = {}", userId, cause);
                return null;
            }


        };
    }
}
