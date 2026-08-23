package com.litisle.litisleuser.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.user.AuthUserDTO;
import com.litisle.litisleapi.dto.user.RegisterUserDTO;
import com.litisle.litisleapi.dto.user.UserDTO;

import com.litisle.litisleapi.feign.UserClient;
import com.litisle.litisleuser.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feign 契约实现
 * 注意：路径由接口上的 @RequestMapping 决定
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class UserFeignProviderController implements UserClient {

    private final UserService userService;

    @Override
    public Result<UserDTO> getUserById(Long userId) {
        log.info("远程api调用getUserById userId:{}", userId);
        UserDTO dto = userService.getDtoById(userId);
        return Result.success(dto);
    }

    @Override
    public Result<AuthUserDTO> createUser(RegisterUserDTO dto) {
        return null;
    }

    @Override
    public Result<AuthUserDTO> findByAccount(String account) {
        return null;
    }

    @Override
    public Result<AuthUserDTO> findByEmail(String email) {
        return null;
    }

    @Override
    public Result<AuthUserDTO> getAuthUserInfo(Long userId) {
        return null;
    }

    // ==================== 以下 4 个方法待实现（auth 模块依赖） ====================

    // TODO 待实现：createUser(RegisterUserDTO)
    //  1. 校验用户名/手机号/邮箱唯一性（不唯一抛 BizException）
    //  2. 手机号/邮箱用 Aes256GcmCrypto 加密后存储
    //  3. 创建 sys_user + 分配 READER 角色（sys_user_role）
    //  4. 返回 AuthUserDTO（含 userId、roles）

    // TODO 待实现：findByAccount(String account)
    //  1. 分别按 username / phone(解密后) / email(解密后) 匹配
    //  2. 返回 AuthUserDTO（含 BCrypt 密码哈希、status、roles、permissions）

    // TODO 待实现：findByEmail(String email)
    //  1. 按 email(解密后) 匹配
    //  2. 返回 AuthUserDTO（同上）

    // TODO 待实现：getAuthUserInfo(Long userId)
    //  1. 查询 sys_user + sys_user_profile + 角色/权限聚合
    //  2. 返回 AuthUserDTO（含作家扩展资料 penName/creationDir/masterpiece）
}