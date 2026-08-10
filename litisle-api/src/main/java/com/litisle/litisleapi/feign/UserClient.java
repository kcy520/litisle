package com.litisle.litisleapi.feign;



import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.user.UserDTO;
import com.litisle.litisleapi.config.FeignApiConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 用户服务 Feign 客户端
 * 供微服务内部调用
 */
@FeignClient(
        name = "litisle-user",
        configuration = FeignApiConfig.class
        //      fallbackFactory = UserClientFallbackFactory.class
)
public interface UserClient {

    /**
     * 根据用户ID查询用户
     */
    @GetMapping("/api/feign/user/{userId}")
    Result<UserDTO> getUserById(@PathVariable("userId") Long userId);
}