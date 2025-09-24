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
import java.util.Arrays;
import java.util.List;

/**
 * JWT前置认证过滤器
 * 用于拦截请求并验证JWT Token，将认证信息存入SecurityContext
 */
@Component
public class JwtPreAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtPreAuthFilter.class);
    private final JwtUtils jwtUtils;
    private final UserDetailsService userDetailsService;

    // 定义需要排除的路径（无需JWT验证的接口）
    private static final List<String> EXCLUDE_PATHS = Arrays.asList(
            "/user/getCode",
            "/user/login",
            "/user/register",
            "/order/create"
    );

    @Value("${jwt.secret}")
    private String SECRET;

    public JwtPreAuthFilter(JwtUtils jwtUtils, UserDetailsService userDetailsService) {
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
    }

    /**
     * 重写该方法，判断当前请求是否需要跳过过滤
     * @param request 当前请求
     * @return true：跳过过滤；false：执行过滤
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getServletPath();
        // 判断当前请求路径是否在排除列表中
        return EXCLUDE_PATHS.stream().anyMatch(path -> requestUri.equals(path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 非排除路径，执行正常JWT验证逻辑
        try {
            // 从请求头获取Authorization
            String authHeader = request.getHeader(JwtUtils.TOKEN_HEADER);
            logger.debug("获取到的Authorization头：{}", authHeader);

            String pureToken = null;
            // 严格校验前缀并剥离
            if (authHeader != null && authHeader.startsWith(JwtUtils.TOKEN_PREFIX) && authHeader.length() > JwtUtils.TOKEN_PREFIX.length()) {
                pureToken = authHeader.substring(JwtUtils.TOKEN_PREFIX.length()).trim();
                logger.debug("剥离前缀后的纯净Token：{}", pureToken);
            } else {
                logger.warn("Authorization头格式错误：无Bearer前缀或长度不足，header={}", authHeader);
            }

            // 用纯净Token解析
            if (pureToken != null) {
                Claims claims = jwtUtils.parseJwt(pureToken);
                if (claims != null) {
                    String userId = claims.get("userId", String.class);
                    String username = claims.get("username", String.class);

                    UserDetails userDetails = userDetailsService.loadUserByUsername(username);

                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(
                                    userDetails,
                                    null,
                                    userDetails.getAuthorities()
                            );
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                    logger.info("JWT认证成功，用户ID：{}，用户名：{}", userId, username);
                } else {
                    logger.warn("JWT解析失败，Token无效或已过期：{}", pureToken);
                }
            } else {
                logger.debug("请求头中无有效Token，header：{}", authHeader);
            }
        } catch (Exception e) {
            logger.error("JWT认证过滤器处理异常", e);
            SecurityContextHolder.clearContext();
        }

        // 继续执行过滤链
        filterChain.doFilter(request, response);
    }
}