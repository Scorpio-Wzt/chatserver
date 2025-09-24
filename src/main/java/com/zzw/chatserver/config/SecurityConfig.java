package com.zzw.chatserver.config;

import com.zzw.chatserver.auth.UnAuthEntryPoint;
import com.zzw.chatserver.filter.JwtLoginAuthFilter;
import com.zzw.chatserver.filter.JwtPreAuthFilter;
import com.zzw.chatserver.filter.KaptchaFilter;
import com.zzw.chatserver.handler.ChatLogoutSuccessHandler;
import com.zzw.chatserver.service.OnlineUserService;
import com.zzw.chatserver.utils.JwtUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;

@Configuration
@EnableWebSecurity
@EnableGlobalMethodSecurity(prePostEnabled = true)
public class SecurityConfig{

    @Autowired
    @Qualifier("userDetailsServiceImpl")
    private UserDetailsService userDetailsService;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private OnlineUserService onlineUserService;

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private ChatLogoutSuccessHandler chatLogoutSuccessHandler;

    @Autowired
    private UnAuthEntryPoint unAuthEntryPoint;

    // 配置认证管理器（用于登录过滤器）
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().antMatchers(
                "/user/getCode",  // 保留：获取验证码无需过滤
                "/sys/getFaceImages",
                "/sys/downloadFile",
                "/swagger-resources/**",
                "/webjars/**",
                "/v2/**",
                "/swagger-ui.html/**",
                "/superuser/login",
                "/user/register"
        );
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationConfiguration authConfig) throws Exception {
        http
                // 1. 跨域配置（保持原有）
                .cors().and()
                // 2. 关闭CSRF（JWT无状态，无需CSRF保护）
                .csrf().disable()
                // 3. 关闭Session（JWT无状态，不依赖Session）
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                // 4. 认证请求规则（保持原有）
                .authorizeRequests()
                    // 放行静态资源和登录接口
                    .antMatchers("/user/login").permitAll()
                    .antMatchers("/expression/**", "/face/**", "/img/**", "/uploads/**").permitAll()
                    // 其他所有请求需认证
                    .anyRequest().authenticated()
                .and()
                // 5. 退出登录配置（保持原有）
                .logout()
                    .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                    .logoutSuccessHandler(chatLogoutSuccessHandler)
                    .permitAll()
                .and()
                // 验证码过滤器：只拦截需要验证码的请求（如普通注册），排除登录请求（避免消耗InputStream）
                .addFilterBefore(
                        new KaptchaFilter(redisTemplate), // 使用修正后的KaptchaFilter（已排除登录接口）
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(
                        new JwtPreAuthFilter(jwtUtils, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class
                )
                // 5. 登录过滤器,处理登录请求
                .addFilterAt(
                        new JwtLoginAuthFilter(
                                authenticationManager(authConfig), // 注入认证管理器
                                mongoTemplate,
                                onlineUserService,
                                jwtUtils
                        ),
                        UsernamePasswordAuthenticationFilter.class
                )
                // 未认证时的处理（返回JSON而非默认页面）
                .httpBasic().authenticationEntryPoint(unAuthEntryPoint);

        return http.build();
    }
}
