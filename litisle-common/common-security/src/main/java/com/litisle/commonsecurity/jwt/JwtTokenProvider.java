package com.litisle.commonsecurity.jwt;

import com.litisle.commoncore.exception.BizException;
import com.litisle.commoncore.exception.ErrorCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SecurityException;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JWT 令牌提供者
 *
 * 【设计说明】
 * 1. 负责 JWT 令牌的签发（create）与验证（parse）
 * 2. 使用 HMAC-SHA256（HS256）对称签名算法
 * 3. 密钥至少 256 bit（32 字符），由 JwtProperties 配置
 * 4. 线程安全：JwtParser 和 SecretKey 在初始化后不可变
 * 5. 支持自定义 Claims（userId、username、roles、permissions）
 *
 * 【令牌结构】
 * Header:  { "alg": "HS256", "typ": "JWT" }
 * Payload: {
 *     "sub":       "用户ID",
 *     "userId":    "用户ID",
 *     "username":  "用户名",
 *     "roles":     "ADMIN,READER",
 *     "permissions": "article:publish,comment:delete",
 *     "jti":       "UUID（令牌唯一标识）",
 *     "iat":       签发时间,
 *     "exp":       过期时间
 * }
 *
 * 【使用场景】
 * - litisle-auth：登录成功后签发 Access Token
 * - litisle-gateway：校验请求中的 JWT 令牌，提取用户信息
 * - 业务服务：通过 JwtTokenProvider 验证令牌有效性（可选）
 *
 * 【安全注意事项】
 * - 密钥必须保密，生产环境通过环境变量或配置中心注入
 * - 每次签发生成唯一 jti（JWT ID），用于黑名单踢人
 * - 令牌过期后，客户端应使用 Refresh Token 刷新
 *
 * @author litisle
 */
@Slf4j
public class JwtTokenProvider {

    /**
     * HMAC-SHA256 密钥
     */
    private final SecretKey secretKey;

    /**
     * JWT 解析器（线程安全）
     */
    private final JwtParser jwtParser;

    /**
     * Access Token 有效期（毫秒）
     */
    private final long accessTokenExpireMs;

    /**
     * 记住我模式 Access Token 有效期（毫秒）
     */
    private final long rememberMeExpireMs;

    /**
     * 签发者标识
     */
    private static final String ISSUER = "LitIsle";

    /**
     * 构造 JwtTokenProvider
     *
     * @param secret              Base64 编码的密钥字符串（至少 256 bit）
     * @param accessTokenExpireMs Access Token 有效期（毫秒）
     * @param rememberMeExpireMs  记住我模式有效期（毫秒）
     */
    public JwtTokenProvider(String secret, long accessTokenExpireMs, long rememberMeExpireMs) {
        // 密钥校验：至少 256 bit（32 字节）
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException(
                    "JWT 密钥长度不足 256 bit（32 字符），当前为 " + keyBytes.length + " 字符");
        }
        this.secretKey = Keys.hmacShaKeyFor(keyBytes);
        this.jwtParser = Jwts.parser()
                .verifyWith(this.secretKey)
                .requireIssuer(ISSUER)
                .build();
        this.accessTokenExpireMs = accessTokenExpireMs;
        this.rememberMeExpireMs = rememberMeExpireMs;
    }

    /**
     * 签发 Access Token
     *
     * @param userId      用户ID
     * @param username    用户名
     * @param roles       角色集合（可为空）
     * @param permissions 权限集合（可为空）
     * @param rememberMe  是否记住我（true 使用更长有效期）
     * @return JWT 令牌字符串
     */
    public String createToken(Long userId, String username,
                              Set<String> roles, Set<String> permissions,
                              boolean rememberMe) {
        long expireMs = rememberMe ? rememberMeExpireMs : accessTokenExpireMs;
        Date now = new Date();
        Date expiration = new Date(now.getTime() + expireMs);

        return Jwts.builder()
                .issuer(ISSUER)
                .subject(String.valueOf(userId))
                .claim(JwtConstant.CLAIM_USER_ID, String.valueOf(userId))
                .claim(JwtConstant.CLAIM_USERNAME, username)
                .claim(JwtConstant.CLAIM_ROLES,
                        (roles != null && !roles.isEmpty()) ? String.join(",", roles) : "")
                .claim(JwtConstant.CLAIM_PERMISSIONS,
                        (permissions != null && !permissions.isEmpty()) ? String.join(",", permissions) : "")
                .id(UUID.randomUUID().toString().replace("-", ""))
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    /**
     * 从 Token 中解析 Claims
     *
     * @param token JWT 令牌字符串
     * @return 解析后的 Claims
     * @throws BizException 令牌无效或已过期时抛出
     */
    public Claims parseToken(String token) {
        try {
            return jwtParser.parseSignedClaims(token).getPayload();
        } catch (ExpiredJwtException e) {
            log.warn("JWT 已过期: {}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_EXPIRED, "登录已过期，请重新登录");
        } catch (UnsupportedJwtException e) {
            log.warn("不支持的 JWT 格式: {}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_INVALID, "不支持的令牌格式");
        } catch (MalformedJwtException e) {
            log.warn("JWT 格式错误: {}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_INVALID, "令牌格式错误");
        } catch (SecurityException e) {
            log.warn("JWT 签名验证失败: {}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_INVALID, "令牌签名验证失败");
        } catch (JwtException e) {
            log.warn("JWT 解析异常: {}", e.getMessage());
            throw new BizException(ErrorCode.TOKEN_INVALID, "令牌无效");
        }
    }

    /**
     * 从 Token 中提取用户ID
     *
     * @param token JWT 令牌字符串
     * @return 用户ID
     */
    public Long getUserIdFromToken(String token) {
        Claims claims = parseToken(token);
        String userIdStr = claims.get(JwtConstant.CLAIM_USER_ID, String.class);
        return userIdStr != null ? Long.parseLong(userIdStr) : null;
    }

    /**
     * 从 Token 中提取用户名
     *
     * @param token JWT 令牌字符串
     * @return 用户名
     */
    public String getUsernameFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.get(JwtConstant.CLAIM_USERNAME, String.class);
    }

    /**
     * 从 Token 中提取角色集合
     *
     * @param token JWT 令牌字符串
     * @return 角色集合（空集表示无角色）
     */
    public Set<String> getRolesFromToken(String token) {
        Claims claims = parseToken(token);
        String rolesStr = claims.get(JwtConstant.CLAIM_ROLES, String.class);
        if (rolesStr == null || rolesStr.isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(rolesStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 从 Token 中提取权限集合
     *
     * @param token JWT 令牌字符串
     * @return 权限集合（空集表示无权限）
     */
    public Set<String> getPermissionsFromToken(String token) {
        Claims claims = parseToken(token);
        String permsStr = claims.get(JwtConstant.CLAIM_PERMISSIONS, String.class);
        if (permsStr == null || permsStr.isEmpty()) {
            return Collections.emptySet();
        }
        return Arrays.stream(permsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    /**
     * 验证 Token 是否有效
     *
     * @param token JWT 令牌字符串
     * @return true 有效，false 无效
     */
    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (BizException e) {
            return false;
        }
    }

    /**
     * 获取 Token 的过期时间
     *
     * @param token JWT 令牌字符串
     * @return 过期时间
     */
    public Date getExpirationFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getExpiration();
    }

    /**
     * 获取 Token 的唯一标识（jti）
     *
     * @param token JWT 令牌字符串
     * @return jti（令牌唯一标识）
     */
    public String getTokenIdFromToken(String token) {
        Claims claims = parseToken(token);
        return claims.getId();
    }
}