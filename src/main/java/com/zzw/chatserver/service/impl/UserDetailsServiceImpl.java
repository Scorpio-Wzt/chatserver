package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.service.SuperUserService;
import com.zzw.chatserver.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring Security 标准用户详情服务实现
 * 作用：将业务层用户信息转换为 Security 认证所需的 UserDetails 对象
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private static final Logger logger = LoggerFactory.getLogger(UserDetailsServiceImpl.class);

    // 注入你的业务层 UserService（已包含用户查询逻辑）
    @Autowired
    private UserService userService;

    @Autowired
    private SuperUserService superUserService; // 注入超级用户服务，用于查询超级用户
    /**
     * 核心方法：根据用户名加载用户信息（Spring Security 自动调用）
     * @param username 登录用户名（与你的 User 实体的 username 字段对应）
     * @return 封装了用户信息和权限的 UserDetails 对象
     * @throws UsernameNotFoundException 当用户不存在或状态异常时抛出
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 1. 先查询【普通用户】（匹配 username 字段）
        User normalUser = userService.findUserByUsername(username);
        if (normalUser != null) {
            return buildUserDetails(normalUser, normalUser.getUsername(), normalUser.getPassword(), normalUser.getRole());
        }

        // 2. 普通用户不存在，查询【超级用户】（匹配 account 字段）
        SuperUser superUser = superUserService.existSuperUser(username);
        if (superUser != null) {
            return buildUserDetails(superUser, superUser.getAccount(), superUser.getPassword(), "0"); // 超级用户角色编码固定为 "0"
        }

        // 3. 两种用户都不存在，抛出异常
        logger.error("用户认证失败：用户名/账号[{}]不存在", username);
        throw new UsernameNotFoundException("用户名或密码错误");
    }
    /**
     * 通用方法：将普通用户/超级用户转换为 Spring Security 的 UserDetails
     */
    private UserDetails buildUserDetails(Object user, String username, String password, String roleCode) {
        // 处理账号启用状态（普通用户用 status 字段，超级用户默认启用）
        boolean isEnabled = true;
        // 校验密码是否为空（普通用户和超级用户都需要密码）
        if (!StringUtils.hasText(password)) {
            logger.error("用户认证失败：{}[{}]密码未设置",
                    user instanceof User ? "普通用户" : "超级用户",
                    username);
            throw new UsernameNotFoundException("账号异常，请联系管理员");
        }

        // 仅普通用户需要校验 status 状态
        if (user instanceof User) {
            User normalUser = (User) user;
            isEnabled = normalUser.getStatus() != null && normalUser.getStatus() == 0;
            if (!isEnabled) {
                String statusMsg = normalUser.getStatus() == 1 ? "已冻结" : "已注销";
                logger.error("普通用户认证失败：用户名[{}]账号{}", username, statusMsg);
                throw new UsernameNotFoundException("账号" + statusMsg + "，请联系管理员");
            }
        }
        // 转换角色编码为 Security 权限
        List<GrantedAuthority> authorities = getAuthorities(roleCode);

        // 构建标准 UserDetails 对象
        return new org.springframework.security.core.userdetails.User(
                username,          // 认证用的用户名/账号
                password,          // 数据库中加密后的密码（BCrypt）
                isEnabled,         // 账号是否启用
                true,              // 账号是否未过期
                true,              // 密码是否未过期
                true,              // 账号是否未锁定
                authorities        // 用户权限列表
        );
    }

    /**
     * 转换角色编码为 Security 权限（与业务角色枚举对齐）
     */
    private List<GrantedAuthority> getAuthorities(String roleCode) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        if (!StringUtils.hasText(roleCode)) {
            roleCode = UserRoleEnum.BUYER.getCode();
            logger.warn("用户角色编码为空，默认分配普通用户权限");
        }

        switch (roleCode) {
            case "0":  // 超级管理员（ADMIN）
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                // 可选：超级管理员自动拥有客服权限
                authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER_SERVICE"));
                break;
            case "1":  // 客服（CUSTOMER_SERVICE）
                authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER_SERVICE"));
                break;
            case "2":  // 普通用户（BUYER）
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                break;
            default:   // 未知角色默认普通用户
                logger.warn("未知角色编码[{}]，默认分配普通用户权限", roleCode);
                authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
                break;
        }

        return authorities;
    }
}