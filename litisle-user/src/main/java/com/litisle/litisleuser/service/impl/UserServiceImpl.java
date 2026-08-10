package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.litisleapi.dto.user.UserDTO;

import com.litisle.litisleuser.domain.entity.User;
import com.litisle.litisleuser.mapper.UserMapper;
import com.litisle.litisleuser.service.IUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl
        extends ServiceImpl<UserMapper, User>
        implements IUserService {
    private final UserMapper userMapper;

    @Override
    public UserDTO getDtoById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        log.info("UserServiceImpl getDtoById userId:{}", user.getId());
        UserDTO dto = new UserDTO();
//        dto.setUserId(user.getId());
//        dto.setUsername(user.getUsername());
//        dto.setAvatarFileId(user.getAvatarFileId());
//        dto.setStatus(user.getStatus());
//        dto.setCreatedAt(user.getCreatedAt());
        return dto;
    }
}