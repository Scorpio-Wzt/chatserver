package com.zzw.chatserver.service;

import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

/**
 * 自定义用户详情服务接口
 * 规范用户登录时的用户信息查询逻辑，与Spring Security的UserDetailsService接口对齐
 */
public interface UserDetailsServiceCustom {

    /**
     * 根据用户名（或用户编码）查询用户详情（用于Spring Security认证）
     * @param username 用户名或用户编码（支持双维度查询）
     * @return 封装后的用户详情（含权限信息，需符合Spring Security的UserDetails规范）
     * @throws UsernameNotFoundException 当用户不存在时抛出
     */
    UserDetails loadUserByUsername(String username) throws UsernameNotFoundException;
}