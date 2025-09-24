package com.zzw.chatserver.filter;

import com.zzw.chatserver.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * JWT前置认证过滤器
 * 用于拦截请求并验证JWT Token，将认证信息存入SecurityContext
 */
@Component
public class JwtPreAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtPreAuthFilter.class);

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;

    // JwtUtils.java 中确保密钥正确（从配置文件读取，32位以上）
    @Value("${jwt.secret}") // 配置文件 application.yml 中添加：jwt.secret=xxxxx (32位随机字符串）
    private String SECRET;

    // 构造器注入依赖（Spring自动注入）
    public JwtPreAuthFilter(JwtUtils jwtUtils, UserDetailsService userDetailsService) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // 1. 从请求头获取Authorization
            String authHeader = request.getHeader(JwtUtils.TOKEN_HEADER);
            logger.debug("获取到的Authorization头：{}", authHeader); // 新增日志，看原始值

            String pureToken = null;
            // 2. 严格校验前缀并剥离（必须包含"Bearer "，且长度足够）
            if (authHeader != null && authHeader.startsWith(JwtUtils.TOKEN_PREFIX) && authHeader.length() > JwtUtils.TOKEN_PREFIX.length()) {
                // 剥离前缀 + 去除前后空格（防止复制时带多余空格）
                pureToken = authHeader.substring(JwtUtils.TOKEN_PREFIX.length()).trim();
                logger.debug("剥离前缀后的纯净Token：{}", pureToken); // 新增日志，验证是否纯净
            } else {
                logger.warn("Authorization头格式错误：无Bearer前缀或长度不足，header={}", authHeader);
            }

            // 3. 用纯净Token解析（不再用带前缀的Token）
            if (pureToken != null) {
                Claims claims = jwtUtils.parseJwt(pureToken); // 这里传pureToken！
                if (claims != null) {
                    // 4. 从Token中提取用户信息（与生成时的claim对应）
                    String userId = claims.get("userId", String.class);
                    String username = claims.get("username", String.class);

                    // 5. 验证用户是否存在（通过UserDetailsService查询数据库/缓存）
                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                    // 6. 创建认证对象（包含用户信息和权限）
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,  // 密码为null（JWT已验证过身份）
                                    userDetails.getAuthorities()  // 用户权限
                            );
                    // 设置请求详情（如IP、Session等）
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    // 7. 将认证信息存入SecurityContext，后续接口可通过SecurityContext获取用户
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    logger.info("JWT认证成功，用户ID：{}，用户名：{}", userId, username);
                } else {
                    logger.warn("JWT解析失败，Token无效或已过期：{}", pureToken);
                }
            } else {
                // 无有效Token，不设置认证信息（后续Security会判断为未认证）
                logger.debug("请求头中无有效Token，header：{}", authHeader);
            }
        } catch (Exception e) {
            // 捕获所有异常，避免过滤器崩溃（如用户不存在、权限解析失败等）
            logger.error("JWT认证过滤器处理异常", e);
            // 清除认证信息（防止部分认证状态残留）
            SecurityContextHolder.clearContext();
        }

        // 8. 继续执行过滤链（无论认证成功与否，都让请求继续处理）
        filterChain.doFilter(request, response);
    }
}
