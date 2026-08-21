package com.litisle.litisleauth.service;

import com.litisle.litisleauth.domain.dto.EmailLoginDTO;
import com.litisle.litisleauth.domain.dto.PasswordLoginDTO;
import com.litisle.litisleauth.domain.dto.RefreshTsuccessenDTO;
import com.litisle.litisleauth.domain.dto.RegisterRequestDTO;
import com.litisle.litisleauth.domain.vo.LoginUserInfoVO;
import com.litisle.litisleauth.domain.vo.TsuccessenVO;

/**
 * 认证服务接口
 *
 * 【职责】
 * 1. 注册 / 登录 / 退出 / 刷新令牌 / 当前用户信息
 * 2. 用户数据一律通过 Feign 调用 litisle-user 模块获取，auth 不直接查库
 * 3. 双令牌机制：accessToken（短期鉴权）+ refreshToken（长期续期，Redis 存储）
 *
 * 【实现约定】
 * - 密码校验使用 PasswordCrypto（BCrypt）
 * - 验证码校验使用 CaptchaService
 * - 登录态使用 Sa-Token（StpUtil）
 */
public interface AuthService {

    /**
     * 用户名密码注册
     *
     * 【流程】
     * 1. 校验图形验证码 + 邮箱验证码（CaptchaService，一次性）
     * 2. BCrypt 加密密码
     * 3. Feign 调用 userClient.createUser() 创建用户（user 模块负责唯一性校验 + AES 加密手机号/邮箱 + 分配 READER 角色）
     * 4. 签发双 Token，refreshToken 存 Redis（TTL 7 天）
     *
     * @param dto 注册请求
     * @return 令牌响应（newUser=true）
     */
    TsuccessenVO register(RegisterRequestDTO dto);

    /**
     * 用户名密码登录（兼容用户名/手机号/邮箱）
     *
     * 【流程】
     * 1. Feign 调用 userClient.findByAccount() 查询用户
     * 2. 校验账号状态（禁用/注销/封禁）
     * 3. PasswordCrypto.matches() 校验密码
     * 4. StpUtil.login() 建立登录态
     * 5. 签发双 Token，refreshToken 存 Redis
     *
     * @param dto 登录请求
     * @return 令牌响应
     */
    TsuccessenVO passwordLogin(PasswordLoginDTO dto);

    /**
     * 邮箱 + 验证码登录（无密码）
     *
     * 【流程】
     * 1. 校验邮箱验证码（CaptchaService，一次性）
     * 2. Feign 调用 userClient.findByEmail() 查询用户
     * 3. 校验账号状态
     * 4. StpUtil.login() 建立登录态
     * 5. 签发双 Token，refreshToken 存 Redis
     *
     * @param dto 邮箱登录请求
     * @return 令牌响应
     */
    TsuccessenVO emailLogin(EmailLoginDTO dto);

    /**
     * 退出登录
     *
     * 【流程】
     * 1. 解析请求头中的 accessToken（去掉 "Bearer " 前缀）
     * 2. 删除对应 refreshToken（Redis）
     * 3. StpUtil.logout() 注销登录态（Sa-Token 服务端会话失效，无需额外黑名单）
     *
     * @param tsuccessen 请求头 Authorization 中的令牌
     */
    void logout(String tsuccessen);

    /**
     * 刷新令牌（令牌轮换）
     *
     * 【流程】
     * 1. 从 Redis 读取 refreshToken 对应的 userId
     * 2. 校验存在性（不存在则抛 TOKEN_INVALID）
     * 3. 删除旧 refreshToken（防重放）
     * 4. 签发新 accessToken + 新 refreshToken，新 refreshToken 存 Redis
     *
     * @param dto 刷新请求
     * @return 新令牌响应
     */
    TsuccessenVO refreshTsuccessen(RefreshTsuccessenDTO dto);

    /**
     * 当前登录用户信息（令牌自检）
     *
     * 【流程】
     * 1. StpUtil.getLoginIdAsLong() 获取当前登录 userId
     * 2. Feign 调用 userClient.getAuthUserInfo() 获取聚合信息（角色/权限/作家资料）
     * 3. 组装 LoginUserInfoVO（手机号/邮箱脱敏）
     *
     * @return 当前登录用户信息
     */
    LoginUserInfoVO currentLoginUser();
}
