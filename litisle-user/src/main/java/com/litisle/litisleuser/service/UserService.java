package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleapi.dto.user.UserDTO;
import com.litisle.litisleuser.domain.entity.User;
import org.springframework.transaction.annotation.Transactional;

@Transactional(rollbackFor = Exception.class)
public interface UserService extends IService<User> {

    /**
     * 查询用户 DTO（供 Feign 使用）
     */
    UserDTO getDtoById(Long userId);

    /**
     * 获取存在的用户，不存在抛 USER_NOT_FOUND
     */
    User getExistingUser(Long userId);
}