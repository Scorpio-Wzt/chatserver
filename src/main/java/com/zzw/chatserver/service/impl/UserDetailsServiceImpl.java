package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.pojo.User;
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

    /**
     * 核心方法：根据用户名加载用户信息（Spring Security 自动调用）
     * @param username 登录用户名（与你的 User 实体的 username 字段对应）
     * @return 封装了用户信息和权限的 UserDetails 对象
     * @throws UsernameNotFoundException 当用户不存在或状态异常时抛出
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 1. 校验用户名非空
        if (!StringUtils.hasText(username)) {
            logger.error("用户认证失败：用户名为空");
            throw new UsernameNotFoundException("用户名不能为空");
        }

        // 2. 调用你的业务层查询用户（依赖 UserService 已实现的查询逻辑）
        // 注意：你的 UserServiceImpl 中已有 userDao.findUserByUsername 调用，此处直接复用
        User user = userService.findUserByUsername(username);

        // 3. 处理用户不存在的情况
        if (user == null) {
            logger.error("用户认证失败：用户名[{}]不存在", username);
            throw new UsernameNotFoundException("用户名或密码错误");
        }

        // 4. 处理账号状态异常（0=正常，1=冻结，2=注销）
        if (user.getStatus() == null || user.getStatus() != 0) {
            String statusMsg = user.getStatus() == 1 ? "已冻结" : "已注销";
            logger.error("用户认证失败：用户名[{}]账号{}", username, statusMsg);
            throw new UsernameNotFoundException("账号" + statusMsg + "，请联系管理员");
        }

        // 5. 处理密码为空的异常情况（正常注册流程会加密存储，此处防脏数据）
        if (!StringUtils.hasText(user.getPassword())) {
            logger.error("用户认证失败：用户名[{}]密码未设置", username);
            throw new UsernameNotFoundException("账号异常，请联系管理员");
        }

        // 6. 转换业务角色为 Security 权限（适配你的 UserRoleEnum）
        List<GrantedAuthority> authorities = getAuthorities(user.getRole());

        // 7. 构建并返回 Security 标准 UserDetails 对象
        return new org.springframework.security.core.userdetails.User(
                user.getUsername(),    // 认证用户名
                user.getPassword(),    // 数据库中加密后的密码（BCrypt）
                true,                  // 账号是否启用（已通过 status 校验）
                true,                  // 账号是否未过期（业务无过期逻辑）
                true,                  // 密码是否未过期（业务无过期逻辑）
                true,                  // 账号是否未锁定（业务用 status 控制）
                authorities            // 用户权限列表
        );
    }

    /**
     * 转换业务角色编码为 Security 权限列表
     * @param roleCode 你的 User 实体中的角色编码（对应 UserRoleEnum）
     * @return 带 ROLE_ 前缀的权限集合（Security 规范）
     */
    private List<GrantedAuthority> getAuthorities(String roleCode) {
        List<GrantedAuthority> authorities = new ArrayList<>();

        // 角色编码为空时，默认普通用户角色
        if (!StringUtils.hasText(roleCode)) {
            roleCode = UserRoleEnum.BUYER.getCode();
            logger.warn("用户角色编码为空，默认分配普通用户权限");
        }

        // 根据角色编码匹配权限（严格对应你的 UserRoleEnum）
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