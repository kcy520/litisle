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

//  自定义安全模块的auth的第三方登录需要接口  // 1. 根据 provider + openId 查询已绑定的用户
//    Result<UserDTO> findByOAuth(@RequestParam("provider") String provider,
//                                @RequestParam("openId") String openId);
//
//    // 2. 根据第三方用户信息创建新用户（自动注册）
//    Result<UserDTO> createUserByOAuth(@RequestBody OAuthUserDTO dto);
//
//    // 3. 绑定第三方账号到已有用户
//    Result<Void> bindOAuth(@RequestBody OAuthBindDTO dto);
//
//    // 4. 解绑第三方账号
//    Result<Void> unbindOAuth(@RequestParam("userId") Long userId,
//                             @RequestParam("provider") String provider);
}