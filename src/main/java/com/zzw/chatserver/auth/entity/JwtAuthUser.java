package com.zzw.chatserver.auth.entity;

import com.zzw.chatserver.common.ConstValueEnum;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.pojo.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// 封装用户信息，用于 Spring Security 认证和鉴权
public class JwtAuthUser extends User implements UserDetails, Serializable {
    // 序列化版本号（确保序列化兼容性）
    private static final long serialVersionUID = 1L;

    // Spring Security 所需的权限集合
    private Collection<? extends GrantedAuthority> authorities;

    // 默认构造方法（保留，供框架反射使用）
    public JwtAuthUser() {
    }

    // 核心构造方法：通过 User 实例创建 JwtAuthUser，自动复用字段并生成权限
    public JwtAuthUser(User user) {
        super.setUserId(user.getUserId());
        super.setUid(user.getUid());
        super.setUsername(user.getUsername());
        super.setPassword(user.getPassword());
        super.setStatus(user.getStatus());
        super.setRole(user.getRole());
        super.setPhoto(user.getPhoto());
        super.setNickname(user.getNickname());

        // 根据 User 的角色生成 Spring Security 权限
        this.authorities = buildAuthorities(user.getRole());
    }

    /**
     * 构建权限集合：根据 User 的角色编码（匹配 UserRoleEnum）生成 Spring Security 所需的 GrantedAuthority
     * @param role User 实体中的角色编码（如 "buyer"/"customer_service"/"admin"）
     * @return 权限集合（带 ROLE_ 前缀，符合 Spring Security 角色规范）
     */
    private Collection<? extends GrantedAuthority> buildAuthorities(String role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        // 处理角色编码为空/无效的情况（默认赋予普通用户权限）
        if (role == null || role.trim().isEmpty()) {
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            return authorities;
        }

        // 根据角色编码获取 UserRoleEnum 实例（避免硬编码字符串比较）
        UserRoleEnum userRole = UserRoleEnum.fromCode(role.trim());
        if (userRole == null) { // 未知角色，默认普通用户
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            return authorities;
        }

        // 按角色分配权限（管理员继承客服权限）
        if (userRole.isAdmin()) { // 管理员：拥有管理员权限 + 客服权限
            authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER_SERVICE"));
        } else if (userRole.isCustomerService()) { // 客服：仅客服权限
            authorities.add(new SimpleGrantedAuthority("ROLE_CUSTOMER_SERVICE"));
        } else if (userRole.isCustomer()) { // 普通用户：仅普通用户权限
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        } else { // 兜底：未知角色默认普通用户
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
        }

        return authorities;
    }

    // ------------------------------ UserDetails 接口方法实现 ------------------------------

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 避免返回 null：未设置权限时返回空列表
        return authorities != null ? authorities : new ArrayList<>();
    }

    @Override
    public String getPassword() {
        // 复用 User 实体的密码（已加密存储）
        return super.getPassword();
    }

    @Override
    public String getUsername() {
        // 复用 User 实体的用户名（登录账号）
        return super.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        // 简化逻辑：默认账号永不过期（若需过期管控，可在 User 中添加 expireTime 字段）
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        // 逻辑：账号未被冻结（status != 冻结状态）则为未锁定
        return !ConstValueEnum.ACCOUNT_FREEZED.equals(super.getStatus());
    }

    @Override
    public boolean isCredentialsNonExpired() {
        // 简化逻辑：默认密码永不过期（若需密码过期管控，可在 User 中添加 pwdExpireTime 字段）
        return true;
    }

    @Override
    public boolean isEnabled() {
        // 逻辑：账号正常（status = 正常状态）则为启用
        return ConstValueEnum.ACCOUNT_NORMAL.equals(super.getStatus());
    }

    // ------------------------------ getter/setter ------------------------------
    public void setAuthorities(Collection<? extends GrantedAuthority> authorities) {
        this.authorities = authorities;
    }
}