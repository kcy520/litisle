package com.litisle.litisleuser.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commonsecurity.context.SecurityContextHolder;
import com.litisle.litisleuser.domain.dto.UserProfileUpdateDTO;
import com.litisle.litisleuser.domain.entity.User;
import com.litisle.litisleuser.domain.entity.UserProfile;
import com.litisle.litisleuser.domain.vo.UserPublicVO;
import com.litisle.litisleuser.domain.vo.UserSelfVO;
import com.litisle.litisleuser.domain.vo.UserUpdateProfileVO;
import com.litisle.litisleuser.enums.DelFlag;
import com.litisle.litisleuser.mapper.UserMapper;
import com.litisle.litisleuser.mapper.UserProfileMapper;
import com.litisle.litisleuser.service.UserProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserProfileServiceImpl
        extends ServiceImpl<UserProfileMapper, UserProfile>
        implements UserProfileService {

    private final UserProfileMapper userProfileMapper;
    private final UserMapper userMapper;

    /**
     * 获取当前登录用户资料
     *
     * @return
     */
    @Override
    public UserSelfVO currentUserProfile() {

        //获取用户id
        Long userId = SecurityContextHolder.getUserId();

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                        .select(User::getId, User::getUsername, User::getPhone,
                                User::getAvatar, User::getBio, User::getVipExpireTime,
                                User::getStatus, User::getFrozenUntil,
                                User::getCancelTime, User::getCreateTime)
        );

        //用户存在校验
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }

        //查询profile表
        UserProfile profile = null;
        //如果非作者身份跳过此表
        if(SecurityContextHolder.isAuthor())
        profile = userProfileMapper.selectOne(
                new LambdaQueryWrapper<UserProfile>()
                        .eq(UserProfile::getUserId, userId)
                        .select(UserProfile::getPenName, UserProfile::getCreationDir,
                                UserProfile::getMasterpiece)
        );

        LocalDateTime now = LocalDateTime.now();
        boolean banned = user.getFrozenUntil() != null && user.getFrozenUntil().isAfter(now);


        return UserSelfVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .bio(user.getBio())
                .vip(user.getVipExpireTime() != null && user.getVipExpireTime().isAfter(now))
                .vipExpireTime(user.getVipExpireTime())
                .status(user.getStatus().getCode())
                .avatarColor(user.getAvatarColor())
                .frozen(user.getFrozenUntil() != null && user.getFrozenUntil().isAfter(now))
//                .enabled(UserStatus.NORMAL.equals(user.getStatus()) && !banned)
                .frozenUntil(banned ? user.getFrozenUntil() : null)
                .cancelTime(user.getCancelTime())
                .penName(profile == null ? null : profile.getPenName())
                .creationDir(profile == null ? null : profile.getCreationDir())
                .masterpiece(profile == null ? null : profile.getMasterpiece())
//                .avatarColor(profile == null ? null : profile.getAvatarColor())
                .createTime(user.getCreateTime())
                .build();

    }

    /**
     * 获取用户公开资料
     * @return
     */
    @Override
    public UserPublicVO publicProfile() {

        //用户id
        Long userId = SecurityContextHolder.getUserId();

        //查询用户信息
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                .select(User::getId, User::getUsername,
                                User::getAvatar, User::getBio, User::getCreateTime,User::getAvatarColor)
        );

        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }


        //如果用户拥有作者角色，就查询用户概述
        UserProfile profile = null;
        if (SecurityContextHolder.isAuthor()){
             profile = userProfileMapper.selectOne(
                    new LambdaQueryWrapper<UserProfile>()
                            .eq(UserProfile::getUserId, userId)
                            .select(UserProfile::getPenName,
                                    UserProfile::getCreationDir,
                                    UserProfile::getMasterpiece
                            ));
        }


        return UserPublicVO.builder()
                .userId(user.getId())
                .username(user.getUsername())
                .avatar(user.getAvatar())
                .bio(user.getBio())
                .avatarColor(user.getAvatarColor())
                .penName(profile != null ? profile.getPenName() : null)
                .creationDir(profile != null ? profile.getCreationDir() : null)
                .masterpiece(profile != null ? profile.getMasterpiece() : null)
//                .avatarColor(profile != null ? user.getAvatarColor() : null)
                .createTime(user.getCreateTime())
                .build();
    }

    /**
     * 修改用户信息
     * @param dto
     */
    /**
     * 修改当前登录用户资料
     */
    @Override
    public UserUpdateProfileVO updateCurrentUserProfile(UserProfileUpdateDTO dto) {

        Long userId = SecurityContextHolder.getUserId();

        // ===== 1. 查当前主表 =====
        User oldUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, 0)
                        .select(User::getId, User::getUsername, User::getBio, User::getAvatarColor)
        );

        if (oldUser == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }

        // ===== 2. 用户名唯一校验 =====
        //可以修改后于修改前值相同
        if (dto.getUsername() != null && !dto.getUsername().equals(oldUser.getUsername())) {
            Long count = userMapper.selectCount(
                    new LambdaQueryWrapper<User>()
                            .eq(User::getUsername, dto.getUsername())
                            .eq(User::getDelFlag, 0)
            );
            if (count > 0) {
                throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
            }
        }

        // ===== 3. 更新主表（只改有值的）=====
        var userUpd = new LambdaUpdateChainWrapper<>(userMapper)
                .eq(User::getId, userId);

        boolean userChanged = false;

        if (dto.getUsername() != null) {
            userUpd.set(User::getUsername, dto.getUsername());
            userChanged = true;
        }
        if (dto.getBio() != null) {
            userUpd.set(User::getBio, dto.getBio());
            userChanged = true;
        }
        if (dto.getAvatarColor() != null) {
            userUpd.set(User::getAvatarColor, dto.getAvatarColor());
            userChanged = true;
        }

        if (userChanged) {
            userUpd.update();
        }

        // ===== 4. 作者：处理 profile =====
        boolean isAuthor = SecurityContextHolder.isAuthor();

        if (isAuthor &&
                (dto.getPenName() != null || dto.getCreationDir() != null || dto.getMasterpiece() != null)) {

            UserProfile oldProfile = userProfileMapper.selectOne(
                    new LambdaQueryWrapper<UserProfile>()
                            .eq(UserProfile::getUserId, userId)
                            .select(UserProfile::getId, UserProfile::getPenName)
            );

            // 笔名唯一校验
            //同样允许修改前后一致
            if (dto.getPenName() != null && oldProfile != null
                    && !dto.getPenName().equals(oldProfile.getPenName())) {

                Long penCount = userProfileMapper.selectCount(
                        new LambdaQueryWrapper<UserProfile>()
                                .eq(UserProfile::getPenName, dto.getPenName())
                );
                if (penCount > 0) {
                    throw new BizException(ErrorCode.PEN_NAME_ALREADY_EXISTS);
                }
            }

            UserProfile profile = oldProfile != null ? oldProfile : new UserProfile();
            profile.setUserId(userId);

            if (dto.getPenName() != null) profile.setPenName(dto.getPenName());
            if (dto.getCreationDir() != null) profile.setCreationDir(dto.getCreationDir());
            if (dto.getMasterpiece() != null) profile.setMasterpiece(dto.getMasterpiece());

            userProfileMapper.insertOrUpdate(profile);
        }

        // ===== 5. 回显：有 profile 才 JOIN =====
        return isAuthor
                ? userProfileMapper.selectUpdateProfileByUserId(userId)
                : userProfileMapper.selectUpdateProfileBaseByUserId(userId);
    }

}