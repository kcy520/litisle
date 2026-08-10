package com.litisle.litisleuser.controller.feign;

import com.litisle.commoncore.result.Result;
import com.litisle.litisleapi.dto.user.UserDTO;
import com.litisle.litisleapi.feign.UserFeignApi;
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
public class UserFeignProviderController implements UserFeignApi {

    private final UserService userService;

    @Override
    public Result<UserDTO> getUserById(Long userId) {
        UserDTO dto = userService.getDtoById(userId);
        return Result.success(dto);
    }
}