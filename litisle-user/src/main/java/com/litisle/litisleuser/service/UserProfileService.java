package com.litisle.litisleuser.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.litisle.litisleuser.domain.dto.AvatarUpdateDTO;
import com.litisle.litisleuser.domain.dto.UserProfileUpdateDTO;
import com.litisle.litisleuser.domain.dto.BindEmailDTO;
import com.litisle.litisleuser.domain.dto.UpdateEmailDTO;
import com.litisle.litisleuser.domain.entity.UserProfile;
import com.litisle.litisleuser.domain.vo.UserPublicVO;
import com.litisle.litisleuser.domain.vo.UserSelfVO;
import com.litisle.litisleuser.domain.vo.UserUpdateProfileVO;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;

public interface UserProfileService extends IService<UserProfile> {

    /**
     * 获取当前登录用户资料
     *
     * @return
     */
    UserSelfVO currentUserProfile();

    /**
     * 获取指定用户的公开资料
     *
     * @param userId 目标用户ID
     * @return
     */
    UserPublicVO publicProfile(Long userId);

    /**
     * 按对外 UID 精确搜索用户公开资料
     * <p>
     * 走 uk_uid 唯一索引，仅查未删除用户；不存在抛 BizException
     *
     * @param uid 对外 UID
     * @return 目标用户公开资料
     */
    UserPublicVO searchByUid(String uid);

    /**
     * 用户资料修改
     * @param dto
     */
    UserUpdateProfileVO updateCurrentUserProfile(@Valid UserProfileUpdateDTO dto);

    /**
     * 设置头像（file_ref 引用登记链路，方案C）
     * <p>① 归属校验：file_meta.owner_id == 当前登录人（防挂他人文件）且须为 AVATAR 类型；
     * ② 旧头像 unbindRef；③ 新头像 bindRef；④ 更新 sys_user.avatar_file_id。
     * 引用登记经 FileClient（Feign）调 file 服务，与业务表同事务（Seata AT 接入后升级 @GlobalTransactional）
     *
     * @param dto 头像文件ID
     */
    void updateAvatar(@Valid AvatarUpdateDTO dto);

    /* ================= 邮箱绑定 / 更换（双码校验，跨模块验验证码） ================= */

    /**
     * 初次绑定邮箱（注册后可补绑）
     * <p>
     * 校验：图形验证码 → 邮箱验证码（CHANGE_EMAIL）→ 新邮箱唯一性；存 AES 密文
     *
     * @param dto 新邮箱 + 图形验证码 + 邮箱验证码
     */
    void bindEmail(@Valid BindEmailDTO dto);

    /**
     * 更换已绑定邮箱
     * <p>
     * 校验：已绑定判定 → 图形验证码 → 邮箱验证码（CHANGE_EMAIL）→ 新邮箱唯一性；存 AES 密文
     *
     * @param dto 新邮箱 + 图形验证码 + 新邮箱验证码
     */
    void updateEmail(@Valid UpdateEmailDTO dto);

    /* ================= 本人敏感信息（明文） ================= */

    @Transactional(rollbackFor = Exception.class)
    void updateAvatar(AvatarUpdateDTO dto);

    /**
     * 查看本人手机号（明文）
     * <p>
     * 仅用于本人身份校验，不落日志
     */
    String viewCurrentUserPlainPhone();

    /**
     * 查看本人邮箱（明文）
     */
    String viewCurrentUserPlainEmail();

//    /**
//     * 查看本人密码（明文/脱敏）
//     * <p>
//     * 仅用于前端二次校验、重设密码前置流程
//     */
//    String viewCurrentUserPlainPassword();

    /* ================= 管理员敏感信息（明文） ================= */

    /**
     * 管理员查看指定用户手机号
     *
     * @param userId 用户ID
     */
    String viewUserPlainPhone(Long userId);

    /**
     * 管理员查看指定用户邮箱
     *
     * @param userId 用户ID
     */
    String viewUserPlainEmail(Long userId);

//    /**
//     * 管理员查看指定用户密码
//     *
//     * @param userId 用户ID
//     */
//    String viewUserPlainPassword(Long userId);


}
