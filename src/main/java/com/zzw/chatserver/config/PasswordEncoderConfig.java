package com.zzw.chatserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 独立的密码编码器配置类
 * 作用：避免与 SecurityConfig 产生循环依赖
 */
@Configuration
public class PasswordEncoderConfig {

    // 密码编码器 Bean（原 SecurityConfig 中的方法移到这里）
    @Bean
    public BCryptPasswordEncoder bCryptPasswordEncoder() {
        return new BCryptPasswordEncoder();
    }
}