package com.litisle.litisleuser.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.utils.AssertUtil;
import com.litisle.commonsecurity.crypto.Aes256GcmCrypto;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litisleapi.dto.captcha.ChangeEmailVerifyDTO;
import com.litisle.litisleapi.feign.AuthClient;
import com.litisle.litisleuser.domain.dto.BindEmailDTO;
import com.litisle.litisleuser.domain.dto.UpdateEmailDTO;
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
import com.litisle.litisleuser.utils.RoleUtils;
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
    private final Aes256GcmCrypto aes256GcmCrypto;
    private final RoleUtils roleUtils;

    /** 跨模块验证码校验（auth 模块提供，验证码发送/校验集中在该模块） */
    private final AuthClient authClient;

    /**
     * 获取当前登录用户资料
     *
     * @return
     */
    @Override
    public UserSelfVO currentUserProfile() {

        //获取用户id（Sa-Token 登录态）
        Long userId = StpUtil.getLoginIdAsLong();

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                        .select(User::getId, User::getUsername, User::getUid, User::getPhone,
                                User::getAvatar, User::getBio, User::getVipExpireTime,
                                User::getStatus, User::getFrozenUntil,
                                User::getCancelTime, User::getCreateTime,
                                User::getAvatarColor)
        );

        //用户存在校验
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }

        //查询profile表
        UserProfile profile = null;
        //如果非作者身份跳过此表
        if(SaRoleHelper.isAuthor())
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
                .uid(user.getUid())
                .username(user.getUsername())
                .phone(user.getPhone())
                .avatar(user.getAvatar())
                .bio(user.getBio())
                .vip(user.getVipExpireTime() != null && user.getVipExpireTime().isAfter(now))
                .vipExpireTime(user.getVipExpireTime())
                .status(user.getStatus())
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
     * 获取指定用户的公开资料
     *
     * @param userId 目标用户ID
     * @return
     */
    @Override
    public UserPublicVO publicProfile(Long userId) {

        //查询目标用户信息
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                .select(User::getId, User::getUsername, User::getUid,
                                User::getAvatar, User::getBio, User::getCreateTime,User::getAvatarColor)
        );

        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }

        //判断目标用户是否拥有作者角色（聚合其角色编码，而非当前登录态）
        boolean isAuthor = roleUtils.isAuthor(userId);

        //如果目标用户拥有作者角色，就查询用户概述
        UserProfile profile = null;
        if (isAuthor){
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
                .uid(user.getUid())
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
     * 按对外 UID 精确搜索用户公开资料
     * <p>
     * 走 uk_uid 唯一索引；仅查询未删除用户；复用 publicProfile 保证与主页展示一致
     */
    @Override
    public UserPublicVO searchByUid(String uid) {
        // 1. 参数校验（同时校验 null 与 blank）
        
        // if (uid == null || uid.isBlank()) {
        //     throw new BizException(ErrorCode.PARAM_ERROR);
        // }
        AssertUtil.notBlank(uid);

        // 2. 按 uid 精确查询未删除用户（命中 uk_uid 索引）
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getUid, uid.trim())
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
        );

        // 3. 不存在抛出业务异常
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 4. 复用公开资料组装逻辑
        return publicProfile(user.getId());
    }

    /**
     * 初次绑定邮箱（注册后可补绑）
     * <p>
     * 高危敏感操作：需先人机识别（图形码）再归属校验（邮箱码，用途 CHANGE_EMAIL），
     * 新邮箱存 AES 密文，不落明文；限流在 Controller 层以 IP 维度控制。
     *
     * @param dto 新邮箱 + 图形验证码 + 邮箱验证码
     */
    @Override
    public void bindEmail(BindEmailDTO dto) {
        // ===== 1. 获取当前登录用户 ID =====
        Long userId = StpUtil.getLoginIdAsLong();

        // ===== 2. 查询当前用户并断言存在（含未删除） =====
        User current = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                        .select(User::getId, User::getEmail)
        );
        if (current == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }

        // ===== 3. 校验未绑定邮箱：已绑定则拒绝，防止覆盖 =====
        if (current.getEmail() != null && !current.getEmail().isBlank()) {
            throw new BizException(ErrorCode.EMAIL_ALREADY_BOUND);
        }

        ChangeEmailVerifyDTO verifyDTO = new ChangeEmailVerifyDTO()
                .setImgUuid(dto.getImgUuid())
                .setImgCode(dto.getImgCode())
                .setEmail(dto.getNewEmail())
                .setEmailCode(dto.getEmailCode());

        authClient.verifyChangeEmail(verifyDTO);

        // ===== 5. 校验新邮箱唯一性（密文比对，命中 uk 判断占用） =====
        String encryptedEmail = aes256GcmCrypto.encrypt(dto.getNewEmail());
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getEmail, encryptedEmail)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
        );
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.EMAIL_EXISTS);
        }

        // ===== 6. 写入 AES 密文（不落明文） =====
        new LambdaUpdateChainWrapper<>(userMapper)
                .eq(User::getId, userId)
                .set(User::getEmail, encryptedEmail)
                .update();
    }

    /**
     * 更换已绑定邮箱
     * <p>
     * 高危敏感操作：需已绑定判定 → 人机识别（图形码）→ 归属校验（新邮箱码）
     * → 新旧邮箱不得相同 → 新邮箱唯一性；存 AES 密文。
     *
     * @param dto 新邮箱 + 图形验证码 + 新邮箱验证码
     */
    @Override
    public void updateEmail(UpdateEmailDTO dto) {
        // ===== 1. 获取当前登录用户 ID =====
        Long userId = StpUtil.getLoginIdAsLong();

        // ===== 2. 查询当前用户并断言存在（未删除） =====
        User current = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                        .select(User::getId, User::getEmail)
        );
        if (current == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }

        // ===== 3. 校验已绑定邮箱：未绑定则需先走 bindEmail =====
        if (current.getEmail() == null || current.getEmail().isBlank()) {
            throw new BizException(ErrorCode.EMAIL_NOT_BOUND);
        }

        // ===== 4. 校验新旧邮箱不得相同 =====
        String oldPlain = aes256GcmCrypto.decrypt(current.getEmail());
        if (oldPlain != null && oldPlain.equals(dto.getNewEmail())) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        // ===== 5. 远程校验双码（图形验→邮箱验，失败由 auth 端抛异常） =====
        ChangeEmailVerifyDTO verifyDTO = new ChangeEmailVerifyDTO()
                .setImgUuid(dto.getImgUuid())
                .setImgCode(dto.getImgCode())
                .setEmail(dto.getNewEmail())
                .setEmailCode(dto.getEmailCode());

        authClient.verifyChangeEmail(verifyDTO);

        // ===== 6. 校验新邮箱唯一性（密文比对，排除自身） =====
        String encryptedEmail = aes256GcmCrypto.encrypt(dto.getNewEmail());
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>()
                        .eq(User::getEmail, encryptedEmail)
                        .ne(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
        );
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.EMAIL_EXISTS);
        }

        // ===== 7. 更新邮箱为 AES 密文 =====
        new LambdaUpdateChainWrapper<>(userMapper)
                .eq(User::getId, userId)
                .set(User::getEmail, encryptedEmail)
                .update();
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

        Long userId = StpUtil.getLoginIdAsLong();

        // ===== 1. 查当前主表 =====
        User oldUser = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
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
                            .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
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
        if (SaRoleHelper.isAuthor() &&
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
        return SaRoleHelper.isAuthor()
                ? userProfileMapper.selectUpdateProfileByUserId(userId)
                : userProfileMapper.selectUpdateProfileBaseByUserId(userId);
    }


    /**
     * 查看本人手机号（明文）
     * <p>
     * 仅用于本人身份校验，不落日志
     */
    @Override
    public String viewCurrentUserPlainPhone() {
        Long userId = StpUtil.getLoginIdAsLong();

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                        .select(User::getPhone)
        );

        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }

        // 解密手机号密文（AES-256-GCM），未绑定手机号时返回 null
        String phone = user.getPhone();
        return phone == null || phone.isBlank() ? null : aes256GcmCrypto.decrypt(phone);
    }



    /**
     * 查看本人邮箱
     * @return
     */
    @Override
    public String viewCurrentUserPlainEmail() {
        Long userId = StpUtil.getLoginIdAsLong();

        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                        .select(User::getEmail)
        );

        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }

        // 解密邮箱密文（AES-256-GCM），未绑定邮箱时返回 null
        String email = user.getEmail();
        return email == null || email.isBlank() ? null : aes256GcmCrypto.decrypt(email);
    }

//    /**
//     * 查看本人密码
//     * @return
//     */
//    @Override
//    public String viewCurrentUserPlainPassword() {
//        Long userId = StpUtil.getLoginIdAsLong();
//
//        User user = userMapper.selectOne(
//                new LambdaQueryWrapper<User>()
//                        .eq(User::getId, userId)
//                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
//                        .select(User::getPassword)
//        );
//
//        if (user == null) {
//            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
//        }
//
//        // BCrypt 不可逆，无法解密返回明文；仅返回脱敏占位符表示已设置密码
//        return user.getPassword() == null || user.getPassword().isBlank()
//                ? null : "********";
//    }

    /**
     * 管理员查看用户手机号
     * @param userId 用户ID
     * @return
     */
    @Override
    public String viewUserPlainPhone(Long userId) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                        .select(User::getPhone)
        );
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }
        // 解密手机号密文（AES-256-GCM）
        String phone = user.getPhone();
        return phone == null || phone.isBlank() ? null : aes256GcmCrypto.decrypt(phone);
    }

    /**
     * 管理员查看用户邮箱
     * @param userId 用户ID
     * @return
     */
    @Override
    public String viewUserPlainEmail(Long userId) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getId, userId)
                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
                        .select(User::getEmail)
        );
        if (user == null) {
            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
        }
        // 解密邮箱密文（AES-256-GCM）
        String email = user.getEmail();
        return email == null || email.isBlank() ? null : aes256GcmCrypto.decrypt(email);
    }

//    /**
//     * 管理员查看用户密码
//     * @param userId 用户ID
//     * @return
//     */
//    @Override
//    public String viewUserPlainPassword(Long userId) {
//        User user = userMapper.selectOne(
//                new LambdaQueryWrapper<User>()
//                        .eq(User::getId, userId)
//                        .eq(User::getDelFlag, DelFlag.NOT_DELETED.getCode())
//                        .select(User::getPassword)
//        );
//        if (user == null) {
//            throw new BizException(ErrorCode.USER_NOT_EXIST_OR_CANCELLED);
//        }
//        // BCrypt 不可逆，仅返回脱敏占位符表示已设置密码
//        return user.getPassword() == null || user.getPassword().isBlank()
//                ? null : "********";
//    }



}