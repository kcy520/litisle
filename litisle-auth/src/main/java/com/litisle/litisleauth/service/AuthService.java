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
     * 4. 注册完成即返回，不签发 Token；用户需重新调用登录接口建立会话
     *
     * @param dto 注册请求
     */
    void register(RegisterRequestDTO dto);

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
     * 1. 按 X-Device-Id 定位当前设备令牌族并吊销（Refresh 正反向 key + 设备记录 + 该设备 Access 会话）
     * 2. 请求头缺失时保守吊销该用户全部令牌族（宁严勿松，防 Refresh 残留至 TTL）
     * 3. StpUtil.logout() 注销当前登录态（Sa-Token 服务端会话失效，无需额外黑名单）
     *
     * @param tsuccessen 请求头 Authorization 中的令牌
     */
    void logout(String tsuccessen);

    /**
     * 强制下线指定用户（管理员触发，非本人操作）
     *
     * 【流程】
     * 1. 逐设备吊销该用户全部令牌族（Refresh 正向 auth:refresh:{token} 与反向
     *    auth:refresh:user:{userId}:{deviceId} 两条 key 全覆盖）
     * 2. 清理该用户全部在线设备记录（DeviceSessionService.kickAllDevices）
     * 3. 注销其全部 Sa-Token 会话（StpUtil.logout(userId)）
     * <p>
     * 供 user 模块处罚/注销/角色变更经 Feign 调用，同时作为 Refresh 重放检测的兜底吊销链路。
     *
     * @param userId 目标用户 ID
     */
    void forceLogout(Long userId);

    /**
     * 刷新令牌（令牌轮换 + 三段判断防重放）
     *
     * 【三段判断】
     * 1. 查墓碑（auth:refresh:used:{token}）：命中 = 已轮换票被重放 = 盗用 →
     *    审计告警 + 吊销该用户全部令牌族（复用 forceLogout 链路）+ 拒绝
     * 2. 查有效 key（auth:refresh:{token}）：未命中 → 拒绝（TOKEN_INVALID）
     * 3. 均通过 → 原子认领（DEL 唯一成功端签发）→ 写墓碑 → 设备指纹比对
     *    （不一致吊销该族）→ 同族轮换签发（familyId/deviceId 沿用）
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
