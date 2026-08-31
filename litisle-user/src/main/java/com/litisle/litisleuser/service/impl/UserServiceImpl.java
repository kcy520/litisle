package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.litisleapi.dto.user.UserDTO;

import com.litisle.litisleuser.domain.entity.User;
import com.litisle.litisleuser.mapper.UserMapper;
import com.litisle.litisleuser.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl
        extends ServiceImpl<UserMapper, User>
        implements UserService {
    private final UserMapper userMapper;

    @Override
    public UserDTO getDtoById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        log.info("UserServiceImpl getDtoById userId:{}", user.getId());
        UserDTO dto = new UserDTO();
        dto.setUserId(user.getId());
        dto.setUsername(user.getUsername());
        // User 实体无 avatarFileId 字段，头像为 URL（avatar），按需映射
        dto.setAvatarFileId(user.getAvatar());
        // 枚举字段转数据库 int 值（UserStatus.code）
        dto.setStatus(user.getStatus() == null ? null : user.getStatus().getCode());
        dto.setCreatedAt(user.getCreateTime());
        return dto;
    }

    @Override
    public User getExistingUser(Long userId) {
        User user = getById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }
}