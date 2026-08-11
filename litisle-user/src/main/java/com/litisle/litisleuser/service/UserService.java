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
}