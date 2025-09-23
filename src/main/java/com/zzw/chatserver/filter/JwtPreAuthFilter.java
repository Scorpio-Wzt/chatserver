package com.zzw.chatserver.filter;

import com.zzw.chatserver.utils.JwtUtils;
import io.jsonwebtoken.Claims;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;
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
public class JwtPreAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtPreAuthFilter.class);

    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;

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
            // 1. 从请求头获取Token
            String authHeader = request.getHeader(JwtUtils.TOKEN_HEADER);

            // 2. 验证Token格式（存在且以Bearer 开头）
            if (authHeader != null && authHeader.startsWith(JwtUtils.TOKEN_PREFIX)) {
                // 剥离前缀，获取纯Token字符串（关键：避免解析时包含"Bearer "导致格式错误）
                String token = authHeader.substring(JwtUtils.TOKEN_PREFIX.length()).trim();

                // 3. 解析Token（调用优化后的JwtUtils，已处理异常）
                Claims claims = jwtUtils.parseJwt(token);
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
                    logger.warn("JWT解析失败，Token无效或已过期：{}", token);
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
