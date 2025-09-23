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

import javax.annotation.Resource;

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

    @Autowired
    private AuthenticationManager authenticationManager;

    // 1. 保留密码编码器
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // 2. 注册AuthenticationManager（之前的配置）
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authConfig) throws Exception {
        return authConfig.getAuthenticationManager();
    }

    // 3. 替换WebSecurity配置为WebSecurityCustomizer Bean
    @Bean
    public WebSecurityCustomizer webSecurityCustomizer() {
        return (web) -> web.ignoring().antMatchers(
                "/user/getCode",
                "/sys/getFaceImages",
                "/user/register",
                "/sys/downloadFile",
                "/swagger-resources/**",
                "/webjars/**",
                "/v2/**",
                "/swagger-ui.html/**",
                "/superuser/login"
        );
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
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
                .antMatchers("/expression/**", "/face/**", "/img/**", "/uploads/**", "/chat/user/login").permitAll()
                // 其他所有请求需认证
                .anyRequest().authenticated().and()
                // 5. 退出登录配置（保持原有）
                .logout().logoutSuccessHandler(chatLogoutSuccessHandler).and()
                // 6. 验证码过滤器（放在登录过滤器之前，保持原有）
                .addFilterBefore(new KaptchaFilter(redisTemplate), UsernamePasswordAuthenticationFilter.class)
                // 7. JWT认证过滤器：放在UsernamePasswordAuthenticationFilter之前，优先验证JWT
                .addFilterBefore(
                        new JwtPreAuthFilter(jwtUtils, userDetailsService), // 修正构造器参数
                        UsernamePasswordAuthenticationFilter.class
                )
                // 8. 登录过滤器：处理/login请求，生成JWT（保持原有，但注意顺序）
                .addFilter(
                        new JwtLoginAuthFilter(authenticationManager, mongoTemplate, onlineUserService)
                )
                // 9. 未认证处理（保持原有）
                .httpBasic().authenticationEntryPoint(unAuthEntryPoint);

        return http.build();
    }
}