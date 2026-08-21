package com.litisle.litisleapi.feign;



import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.user.AuthUserDTO;
import com.litisle.litisleapi.dto.user.RegisterUserDTO;
import com.litisle.litisleapi.dto.user.UserDTO;
import com.litisle.litisleapi.config.FeignApiConfig;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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

    // ==================== 以下 4 个方法由 auth 模块调用，请自行实现提供方 ====================

    /**
     * 创建用户（注册）
     * 【实现说明】user 模块负责：用户名/手机号/邮箱唯一性校验 + AES 加密手机号/邮箱 + 分配 READER 角色
     * 【返回】创建成功后的聚合用户信息（含 userId、roles）
     */
    @PostMapping("/api/feign/user")
    Result<AuthUserDTO> createUser(@RequestBody RegisterUserDTO dto);

    /**
     * 按账号查询用户（兼容用户名/手机号/邮箱）
     * 【实现说明】user 模块负责解密匹配，返回聚合 DTO（含密码哈希、状态、角色、权限）
     */
    @GetMapping("/api/feign/user/account/{account}")
    Result<AuthUserDTO> findByAccount(@PathVariable("account") String account);

    /**
     * 按邮箱查询用户（邮箱登录用）
     * 【实现说明】user 模块负责解密匹配，返回聚合 DTO
     */
    @GetMapping("/api/feign/user/email/{email}")
    Result<AuthUserDTO> findByEmail(@PathVariable("email") String email);

    /**
     * 查询用户认证聚合信息（/auth/me 用）
     * 【实现说明】返回含角色、权限、作家扩展资料（笔名/创作方向/代表作）的聚合 DTO
     */
    @GetMapping("/api/feign/user/auth/{userId}")
    Result<AuthUserDTO> getAuthUserInfo(@PathVariable("userId") Long userId);

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