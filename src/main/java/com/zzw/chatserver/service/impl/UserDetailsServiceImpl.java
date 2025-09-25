package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.auth.entity.JwtAuthUser;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.service.SuperUserService;
import com.zzw.chatserver.service.UserService;
import org.bson.types.ObjectId;
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
            return buildUserDetails(superUser, superUser.getAccount(), superUser.getPassword(), "admin"); // 超级用户角色编码固定为 "0"
        }

        // 3. 两种用户都不存在，抛出异常
        logger.error("用户认证失败：用户名/账号[{}]不存在", username);
        throw new UsernameNotFoundException("用户名或密码错误");
    }

    /**
     * 通用方法：将普通用户/超级用户转换为 Spring Security 的 UserDetails
     */
// 修改 UserDetailsServiceImpl.java 的 buildUserDetails 方法
    private UserDetails buildUserDetails(Object user, String username, String password, String roleCode) {
        // 1. 创建自定义 JwtAuthUser 实例（继承自业务 User 实体，包含 userId）
        JwtAuthUser jwtAuthUser = new JwtAuthUser();
        jwtAuthUser.setUsername(username);
        jwtAuthUser.setPassword(password);

        boolean isEnabled = true;

        // 2. 处理普通用户（复制 userId 等字段）
        if (user instanceof User) {
            User normalUser = (User) user;
            // 关键：复制 userId（避免 getUserId() 为 null）
            jwtAuthUser.setUserId(normalUser.getUserId());
            // 复制其他必要字段（如 status、role 等）
            jwtAuthUser.setStatus(normalUser.getStatus());
            jwtAuthUser.setRole(normalUser.getRole());
            // 校验账号状态
            isEnabled = normalUser.getStatus() != null && normalUser.getStatus() == 0;
        }
        // 3. 处理超级用户（若超级用户也需要 userId，可自定义逻辑）
        else if (user instanceof SuperUser) {
            SuperUser superUser = (SuperUser) user;
            // 示例：超级用户无 userId，可将 sid 转为 ObjectId 作为 userId
            jwtAuthUser.setUserId(new ObjectId(superUser.getSid().toString()));
            jwtAuthUser.setStatus(0); // 超级用户默认正常状态
        }

        // 4. 校验密码
        if (!StringUtils.hasText(password)) {
            throw new UsernameNotFoundException("账号异常，密码未设置");
        }

        // 5. 设置权限（复用原逻辑）
        jwtAuthUser.setAuthorities(getAuthorities(roleCode));

        // 返回 JwtAuthUser 实例，而非内置 User
        return jwtAuthUser;
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
            case "admin":  // 超级管理员（ADMIN）
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                // 可选：超级管理员自动拥有客服权限
                authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER_SERVICE"));
                break;
            case "customer_service":  // 客服（CUSTOMER_SERVICE）
                authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER_SERVICE"));
                break;
            case "buyer":  // 普通用户（BUYER）
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