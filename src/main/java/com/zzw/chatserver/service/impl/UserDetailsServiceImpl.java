package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.auth.entity.JwtAuthUser;
import com.zzw.chatserver.dao.UserDao;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.service.UserDetailsServiceCustom;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * 用户详情服务实现类
 * 1. 实现Spring Security的UserDetailsService接口，供认证框架调用
 * 2. 实现自定义的UserDetailsServiceCustom接口，规范业务逻辑
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService, UserDetailsServiceCustom {

    @Resource
    private UserDao userDao;

    /**
     * 核心认证逻辑：根据用户名/编码查询用户，并转换为Spring Security认可的UserDetails
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 1. 双维度查询用户：支持用户名或用户编码登录（依赖UserDao的findUserByUsernameOrCode方法）
        User user = userDao.findUserByUsernameOrCode(username, username);

        // 2. 用户不存在时，抛出Spring Security标准异常（触发认证失败）
        if (user == null) {
            throw new UsernameNotFoundException("该用户不存在！");
        }

        // 3. 转换为自定义的JwtAuthUser（需实现UserDetails接口，封装用户信息和权限）
        JwtAuthUser jwtAuthUser = new JwtAuthUser();
        BeanUtils.copyProperties(user, jwtAuthUser);

        // 4. 返回UserDetails实例，供Spring Security完成后续认证（如密码比对）
        return jwtAuthUser;
    }
}