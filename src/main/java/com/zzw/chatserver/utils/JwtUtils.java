package com.zzw.chatserver.utils;

import io.jsonwebtoken.*;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.UUID;

/**
 * JWT工具类：负责Token生成、解析、合法性校验
 */
// 若使用Spring，建议加@Component，从配置文件读取密钥（非Spring环境可删除@Component和@Value）
@Component
@Slf4j
public class JwtUtils {

    // 认证头Key（与前端一致）
    public static final String TOKEN_HEADER = "Authorization";
    // 认证前缀（必须带空格，与前端/Filter一致）
    public static final String TOKEN_PREFIX = "Bearer ";
    // Token过期时间：1天（可根据业务调整，如后台系统建议2小时+刷新Token）
    private static final Long EXPIRE = 24 * 3600 * 1000L;

    // 密钥：从配置文件读取（避免硬编码，生产环境需加密存储）
    // 要求：HS256算法需≥256位（32个UTF-8字符），建议用随机字符串（如UUID生成32位）
    @Value("${jwt.secret}") // 配置文件中添加：jwt.secret=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
    private String SECRET;

    /**
     * 生成JWT Token
     * @param userId 用户ID（建议字符串类型，避免类型转换问题）
     * @param username 用户名（用于附加信息，非必需但便于日志排查）
     * @return 完整的JWT Token（不含前缀Bearer）
     */
    public String createJwt(String userId, String username) {
        // 参数非空校验（避免生成无效Token）
        Assert.notNull(userId, "用户ID不能为空");
        Assert.notNull(username, "用户名不能为空");

        return Jwts.builder()
                .setSubject(userId) // 设置Token主题（通常存用户ID，与claims中的userId一致）
                .claim("userId", userId) // 自定义Claim：用户ID（方便解析时直接获取）
                .claim("username", username) // 自定义Claim：用户名（用于日志/权限校验）
                .setIssuedAt(new Date()) // 签发时间（当前时间）
                .setId(UUID.randomUUID().toString()) // 唯一标识（jti，用于防止Token重放攻击）
                .setExpiration(new Date(System.currentTimeMillis() + EXPIRE)) // 过期时间（1天）
                .signWith(SignatureAlgorithm.HS256, SECRET.getBytes()) // 算法+密钥（必须用字节数组）
                .compact(); // 生成最终Token
    }

    /**
     * 解析JWT Token，返回Claims（包含用户信息）
     * @param token 待解析的Token（不含前缀Bearer，需前端传入时剥离前缀）
     * @return Claims 解析后的用户信息；若解析失败，返回null（通过日志输出错误原因）
     */
    public Claims parseJwt(String token) {
        // Token非空校验
        if (!StringUtils.hasText(token)) {
            log.error("JWT解析失败：Token为空");
            return null;
        }

        try {
            // 解析Token（HS256算法，用SECRET验证签名）
            return Jwts.parser()
                    .setSigningKey(SECRET.getBytes()) // 密钥与生成时一致（必须用字节数组）
                    .parseClaimsJws(token) // 解析Token（自动校验签名、格式、过期时间）
                    .getBody(); // 获取Claims（用户信息）

        } catch (ExpiredJwtException e) {
            log.error("JWT解析失败：Token已过期，token={}", token, e);
        } catch (MalformedJwtException e) {
            log.error("JWT解析失败：Token格式非法（如乱码、缺失部分），token={}", token, e);
        } catch (SignatureException e) {
            log.error("JWT解析失败：Token签名被篡改，token={}", token, e);
        } catch (IllegalArgumentException e) {
            log.error("JWT解析失败：Token参数非法（如Claims为空），token={}", token, e);
        } catch (Exception e) {
            log.error("JWT解析失败：未知错误，token={}", token, e);
        }

        // 解析失败时返回null（上层调用需判断null，返回“非法Token”等提示）
        return null;
    }

    /**
     * 【删除Token方法无需保留】
     * 说明：JWT是无状态Token，生成后无法主动“删除”，所谓“删除”本质是：
     * 1. 客户端：丢弃本地存储的Token（如清空LocalStorage/Cookie）；
     * 2. 服务端：若需强制失效，需维护“Token黑名单”（如Redis存储失效Token，解析时校验）。
     * 若无需黑名单逻辑，此方法可删除。
     */
    // public void removeToken(String token) { }

    // 测试方法：验证生成和解析是否正常
    public static void main(String[] args) {
        // 注意：测试时需先给SECRET赋值（32位随机字符串），或注释@Component和@Value，直接硬编码SECRET
        JwtUtils jwtUtils = new JwtUtils();
        jwtUtils.SECRET = "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"; // 替换为32位密钥

        // 生成Token
        String token = jwtUtils.createJwt("64f2a1b3c8d7e6f7a1b2c3d", "super_admin");
        System.out.println("生成的Token：" + token);

        // 解析Token
        Claims claims = jwtUtils.parseJwt(token);
        if (claims != null) {
            System.out.println("解析的用户ID：" + claims.get("userId"));
            System.out.println("解析的用户名：" + claims.get("username"));
            System.out.println("Token过期时间：" + claims.getExpiration());
        } else {
            System.out.println("Token解析失败");
        }
    }
}