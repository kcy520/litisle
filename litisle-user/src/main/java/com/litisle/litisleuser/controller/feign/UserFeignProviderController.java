package com.litisle.litisleuser.controller.feign;

import com.litisle.commoncore.enums.UserStatus;
import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import com.litisle.commoncore.result.Result;
import com.litisle.commoncore.utils.UidGenerator;
import com.litisle.commonsecurity.crypto.Aes256GcmCrypto;
import com.litisle.commonsecurity.crypto.HmacSha256Crypto;
import com.litisle.commonsecurity.enums.RoleCodeEnum;
import com.litisle.commonsecurity.utils.SaRoleHelper;
import com.litisle.litisleapi.dto.user.AuthUserDTO;
import com.litisle.litisleapi.dto.user.RegisterUserDTO;
import com.litisle.litisleapi.dto.user.UserDTO;
import com.litisle.litisleapi.dto.user.UserIndexDocDTO;
import com.litisle.litisleapi.event.user.UserRegisteredEvent;
import com.litisle.litisleapi.feign.UserClient;
import com.litisle.litisleuser.mq.UserEventProducer;
import com.litisle.litisleuser.domain.entity.Permission;
import com.litisle.litisleuser.domain.entity.Role;
import com.litisle.litisleuser.domain.entity.RolePermission;
import com.litisle.litisleuser.domain.entity.User;
import com.litisle.litisleuser.domain.entity.UserProfile;
import com.litisle.litisleuser.domain.entity.UserRole;
import com.litisle.commoncore.enums.DelFlag;
import com.litisle.litisleuser.service.PermissionService;
import com.litisle.litisleuser.service.RolePermissionService;
import com.litisle.litisleuser.service.RoleService;
import com.litisle.litisleuser.service.UserProfileService;
import com.litisle.litisleuser.service.UserRoleService;
import com.litisle.litisleuser.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Feign 契约实现
 * 注意：路径由接口上的 @RequestMapping 决定
 */
@RestController
@Slf4j
@RequiredArgsConstructor
public class UserFeignProviderController implements UserClient {

    private final UserService userService;
    private final Aes256GcmCrypto aes256GcmCrypto;
    private final HmacSha256Crypto hmacSha256Crypto;
    private final RoleService roleService;
    private final UserRoleService userRoleService;
    private final RolePermissionService rolePermissionService;
    private final PermissionService permissionService;
    private final UserProfileService userProfileService;

    /** user 域事件生产收口（批次五 B5：注册/资料更新事件，search 同步 user_idx） */
    private final UserEventProducer userEventProducer;

    /**
     * 测试-id查询用户
     * @param userId
     * @return
     */
    @Override
    public Result<UserDTO> getUserById(Long userId) {
        log.info("远程api调用getUserById userId:{}", userId);
        UserDTO dto = userService.getDtoById(userId);
        return Result.success(dto);
    }

    /**
     * 注册用户
     * @param dto
     * @return
     */
    @Override
    public Result<AuthUserDTO> createUser(RegisterUserDTO dto) {
        log.info("Feign 创建用户: username={}", dto.getUsername());

        // 1. 校验用户名唯一性
        if (userService.lambdaQuery().eq(User::getUsername, dto.getUsername()).count() > 0) {
            throw new BizException(ErrorCode.USERNAME_ALREADY_EXISTS);
        }

        // 2. 校验手机号唯一性（随机 IV 密文不可比对，查重走 HMAC 确定性摘要列；
        //    只判活跃记录，否则已注销用户（del_flag=1）会永久阻塞同手机号重新注册，
        //    对应唯一索引 uk_phone_hash_del(phone_hash, del_flag) 的复合语义）
        String encryptedPhone = aes256GcmCrypto.encrypt(dto.getPhone());
        String phoneHash = hmacSha256Crypto.digest(dto.getPhone());
        if (userService.lambdaQuery()
                .eq(User::getPhoneHash, phoneHash)
                .eq(User::getDelFlag, DelFlag.NOT_DELETED)
                .count() > 0) {
            throw new BizException(ErrorCode.PHONE_EXISTS);
        }

        // 3. 校验邮箱唯一性（可选字段）
        String encryptedEmail = null;
        String emailHash = null;
        if (dto.getEmail() != null && !dto.getEmail().isBlank()) {
            encryptedEmail = aes256GcmCrypto.encrypt(dto.getEmail());
            emailHash = hmacSha256Crypto.digest(dto.getEmail());
            if (userService.lambdaQuery()
                    .eq(User::getEmailHash, emailHash)
                    .eq(User::getDelFlag, DelFlag.NOT_DELETED)
                    .count() > 0) {
                throw new BizException(ErrorCode.DATA_EXISTS, "邮箱已被注册");
            }
        }

        // 4. 构建 User 实体并保存（avatarColor 由数据库默认值填充；avatarFileId 无默认值，待上传头像后写入）
        User user = new User()
                .setUsername(dto.getUsername())
                .setUid(generateUniqueUid())
                .setPassword(dto.getPassword()) // 已由 auth 模块 BCrypt 加密
                .setPhone(encryptedPhone)
                .setPhoneHash(phoneHash)
                .setEmail(encryptedEmail)
                .setEmailHash(emailHash)
                .setStatus(UserStatus.NORMAL)
                .setDelFlag(DelFlag.NOT_DELETED);
        userService.save(user);

        // 5. 分配 READER 角色（新注册用户默认为读者）
        Role readerRole = roleService.getByRoleCode(RoleCodeEnum.READER.getCode());
        if (readerRole != null) {
            UserRole userRole = new UserRole();
            userRole.setUserId(user.getId());
            userRole.setRoleId(readerRole.getId());
            userRoleService.save(userRole);
        }

        // 6. 发布用户注册事件（批次五 B5，best-effort：发送失败仅告警，不影响注册结果）
        //    注：新用户尚无笔名/简介，search 侧门控会判定「无可索引公开资料」而不建文档；
        //    待资料更新事件（user.profile-updated）再正式入索引
        UserRegisteredEvent registeredEvent = new UserRegisteredEvent();
        registeredEvent.setUserId(user.getId());
        registeredEvent.setUsername(user.getUsername());
        registeredEvent.setUid(user.getUid());
        userEventProducer.sendRegistered(registeredEvent);

        // 7. 构建返回 DTO
        AuthUserDTO authUser = new AuthUserDTO();
        authUser.setUserId(user.getId());
        authUser.setUsername(user.getUsername());
        authUser.setRoles(List.of(RoleCodeEnum.READER.getCode()));

        log.info("用户创建成功: userId={}, username={}", user.getId(), user.getUsername());
        return Result.success(authUser);
    }

    /**
     * 查询用户
     * @param account
     * @return
     */
    @Override
    public Result<AuthUserDTO> findByAccount(String account) {
        log.info("Feign 按账号查询用户: account={}", account);

        // 1. 按用户名匹配（精确匹配）
        User user = userService.lambdaQuery()
                .eq(User::getUsername, account)
                .eq(User::getDelFlag, DelFlag.NOT_DELETED)
                .one();

        // 2. 按手机号匹配（随机 IV 密文不可比对，走 HMAC 摘要列命中 uk_phone_hash_del 索引）
        if (user == null) {
            user = userService.lambdaQuery()
                    .eq(User::getPhoneHash, hmacSha256Crypto.digest(account))
                    .eq(User::getDelFlag, DelFlag.NOT_DELETED)
                    .one();
        }

        // 3. 按邮箱匹配（随机 IV 密文不可比对，走 HMAC 摘要列命中 uk_email_hash_del 索引）
        if (user == null) {
            user = userService.lambdaQuery()
                    .eq(User::getEmailHash, hmacSha256Crypto.digest(account))
                    .eq(User::getDelFlag, DelFlag.NOT_DELETED)
                    .one();
        }

        // 4. 用户不存在
        if (user == null) {
            log.warn("账号不存在: account={}", account);
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 5. 聚合角色和权限并返回
        return Result.success(buildAuthUserDTO(user));
    }

    /**
     * 邮箱查询用户
     * @param email
     * @return
     */
    @Override
    public Result<AuthUserDTO> findByEmail(String email) {
        log.info("Feign 按邮箱查询用户: email={}", email);

        // 1. 按邮箱 HMAC 摘要查询（随机 IV 密文不可比对，命中 uk_email_hash_del 索引）
        User user = userService.lambdaQuery()
                .eq(User::getEmailHash, hmacSha256Crypto.digest(email))
                .eq(User::getDelFlag, DelFlag.NOT_DELETED)
                .one();

        // 2. 用户不存在
        if (user == null) {
            log.warn("邮箱未注册: email={}", email);
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 3. 聚合角色和权限并返回
        return Result.success(buildAuthUserDTO(user));
    }

    /**
     *
     * @param userId
     * @return
     */
    @Override
    public Result<AuthUserDTO> getAuthUserInfo(Long userId) {
        log.info("Feign 查询用户认证信息: userId={}", userId);

        // 1. 查询用户
        User user = userService.lambdaQuery()
                .eq(User::getId, userId)
                .eq(User::getDelFlag, DelFlag.NOT_DELETED)
                .one();
        if (user == null) {
            log.warn("用户不存在: userId={}", userId);
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 2. 构建基础 DTO（含角色 + 权限，buildAuthUserDTO 已查询角色列表）
        AuthUserDTO authUser = buildAuthUserDTO(user);

        // 3. 仅作者才查扩展资料（减少非作者用户的数据库查询）
        if (SaRoleHelper.isAuthor()) {
            UserProfile profile = userProfileService.lambdaQuery()
                    .eq(UserProfile::getUserId, userId)
                    .one();
            if (profile != null) {
                authUser.setPenName(profile.getPenName());
                authUser.setCreationDir(profile.getCreationDir());
                authUser.setMasterpiece(profile.getMasterpiece());
            }
        }

        return Result.success(authUser);
    }

    /**
     * 回查用户建索引所需的公开资料（批次五 B5，search 同步 user_idx 用）。
     *
     * 作用：只返回可公开字段（笔名/简介）与状态门控字段；用户不存在或已删除时抛
     * USER_NOT_FOUND，search 消费端据此删除索引文档（注销/删除的正常收敛路径）。
     *
     * @param userId 用户 ID
     * @return 用户索引回查 DTO（笔名/简介/状态/删除标志/更新时间）
     */
    @Override
    public Result<UserIndexDocDTO> getUserIndexDoc(Long userId) {
        log.info("Feign 查询用户索引资料: userId={}", userId);

        // 1. 查用户（不带 del_flag 过滤：已删除也按不存在返回，供 search 删索引）
        User user = userService.getById(userId);
        if (user == null || user.getDelFlag() == DelFlag.DELETED) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 2. 查作者扩展资料（笔名为对外展示名，普通用户可能没有档案行）
        UserProfile profile = userProfileService.lambdaQuery()
                .eq(UserProfile::getUserId, userId)
                .one();

        // 3. 组装 DTO（仅公开字段：不含手机号/邮箱/密码等隐私数据）
        UserIndexDocDTO dto = new UserIndexDocDTO();
        dto.setUserId(user.getId());
        dto.setPenName(profile == null ? null : profile.getPenName());
        dto.setBio(user.getBio());
        dto.setStatus(user.getStatus() == null ? null : user.getStatus().getCode());
        dto.setDelFlag(user.getDelFlag() == null ? null : user.getDelFlag().getCode());
        dto.setUpdateTime(user.getUpdateTime());
        return Result.success(dto);
    }

    // ==================== 私有方法 ====================

    
    /**
     * 生成全局唯一 UID（利用 uk_uid 唯一索引做存在性判重的兜底）
     * <p>
     * 同秒内 17 位随机序列理论上可碰撞，此处若碰撞则重新生成，避免唯一键冲突写库失败
     *
     * @return 唯一且未被占用的对外 UID
     */
    private String generateUniqueUid() {
        String uid;
        do {
            uid = UidGenerator.generate();
        } while (userService.lambdaQuery().eq(User::getUid, uid).count() > 0);
        return uid;
    }

    /**
     * 构建认证用户聚合 DTO
     * <p>
     * 聚合用户基本信息 + 角色编码列表 + 权限编码列表
     *
     * @param user 用户实体
     * @return AuthUserDTO
     */
    private AuthUserDTO buildAuthUserDTO(User user) {
        // 1. 获取角色列表
        List<UserRole> userRoles = userRoleService.lambdaQuery()
                .eq(UserRole::getUserId, user.getId())
                .list();

        // 1.1 批量查询角色（避免 N+1：循环内逐条 getById 改为一次 in 查询）

        List<String> roleCodes = Collections.emptyList();
        if (!userRoles.isEmpty()) {

                    // 第一步：把所有 roleId 收集到一个 List 里
            List<Long> roleIds = userRoles.stream()
                    .map(UserRole::getRoleId)
                    .distinct()
                    .collect(Collectors.toList());

                    // 第二步：一次 IN 查询，把所有 role 捞出来
            Map<Long, Role> roleMap = roleService.listByIds(roleIds).stream()
                    .collect(Collectors.toMap(Role::getId, Function.identity()));
                    //Function.identity()一个返回自 : 身的函数，x → x，告诉 toMap "value 就用元素自己"
                    
                    // 第三步：从内存 Map 里取，不再查数据库
            roleCodes = userRoles.stream()
                    .map(ur -> roleMap.get(ur.getRoleId()))
                    .filter(Objects::nonNull)
                    .map(Role::getRoleCode)
                    .collect(Collectors.toList());
        }

        // 2. 获取权限列表
        List<String> permCodes = Collections.emptyList();
        if (!userRoles.isEmpty()) {
            List<Long> roleIds = userRoles.stream()
                    .map(UserRole::getRoleId)
                    .collect(Collectors.toList());
            List<RolePermission> rolePerms = rolePermissionService.lambdaQuery()
                    .in(RolePermission::getRoleId, roleIds)
                    .list();
            List<Long> permIds = rolePerms.stream()
                    .map(RolePermission::getPermissionId)
                    .distinct()
                    .collect(Collectors.toList());
            if (!permIds.isEmpty()) {
                permCodes = permissionService.lambdaQuery()
                        .in(Permission::getId, permIds)
                        .list()
                        .stream()
                        .map(Permission::getPermCode)
                        .collect(Collectors.toList());
            }
        }

        // 3. 构建 AuthUserDTO
        AuthUserDTO authUser = new AuthUserDTO()
                .setUserId(user.getId())
                .setUid(user.getUid())
                .setUsername(user.getUsername())
                // BCrypt 哈希，仅密码登录校验链路使用，禁止外传（契约语义见 AuthUserDTO）
                .setPassword(user.getPassword())
                // 解密为明文传输（内网 Feign），最终响应由 VO 层 @Sensitive 注解自动脱敏（业务无感知）
                .setPhone(user.getPhone() == null ? null : aes256GcmCrypto.decrypt(user.getPhone()))
                .setEmail(user.getEmail() == null || user.getEmail().isBlank()
                        ? null : aes256GcmCrypto.decrypt(user.getEmail()))
                .setAvatarFileId(user.getAvatarFileId())
                .setAvatarColor(user.getAvatarColor() != null ? user.getAvatarColor().getCode() : null)
                .setBio(user.getBio())
                .setVip(user.getVipExpireTime() != null && user.getVipExpireTime().isAfter(LocalDateTime.now()))
                .setVipExpireTime(user.getVipExpireTime())
                .setStatus(user.getStatus() != null ? user.getStatus().getCode() : null)
                .setFrozenUntil(user.getFrozenUntil())
                .setCancelTime(user.getCancelTime())
                .setCreateTime(user.getCreateTime())
                .setRoles(roleCodes)
                .setPermissions(permCodes);

        return authUser;
    }
}