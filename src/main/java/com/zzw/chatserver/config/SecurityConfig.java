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
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity; // 旧版注解
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import javax.annotation.Resource;

@Configuration
@EnableWebSecurity
// 旧版注解：开启方法级权限（兼容Spring Security 5.6以下版本）
@EnableGlobalMethodSecurity(prePostEnabled = true) // 核心：开启@PreAuthorize注解
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

    @Bean
    public BCryptPasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().antMatchers(
                "/user/getCode",
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
                .cors().and()
                .csrf().disable()
                .sessionManagement().sessionCreationPolicy(SessionCreationPolicy.STATELESS).and()
                .authorizeRequests()
                .antMatchers("/user/login").permitAll()
                .antMatchers("/expression/**", "/face/**", "/img/**", "/uploads/**").permitAll()
                .anyRequest().authenticated()
                .and()
                .logout()
                .logoutRequestMatcher(new AntPathRequestMatcher("/logout", "POST"))
                .logoutSuccessHandler(chatLogoutSuccessHandler)
                .permitAll()
                .and()
                .addFilterBefore(
                        new KaptchaFilter(redisTemplate),
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterBefore(
                        new JwtPreAuthFilter(jwtUtils, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class
                )
                .addFilterAt(
                        new JwtLoginAuthFilter(
                                authenticationManager(authConfig),
                                mongoTemplate,
                                onlineUserService,
                                jwtUtils
                        ),
                        UsernamePasswordAuthenticationFilter.class
                )
                .httpBasic().authenticationEntryPoint(unAuthEntryPoint);

        return http.build();
    }

    // 关键：配置角色前缀（解决@PreAuthorize("hasRole('CUSTOMER_SERVICE')")匹配问题）
    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler() {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        // 根据你的角色存储格式设置：
        // 1. 若角色存储为"CUSTOMER_SERVICE"（无前缀）→ 设置为空
        // 2. 若角色存储为"ROLE_CUSTOMER_SERVICE"（有前缀）→ 保持默认"ROLE_"
        handler.setDefaultRolePrefix("");
        return handler;
    }
}
