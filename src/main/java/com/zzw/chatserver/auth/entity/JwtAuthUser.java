package com.zzw.chatserver.auth.entity;

import com.zzw.chatserver.pojo.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.ArrayList;
import java.util.Collection;

//封装了用户信息，用于认证和鉴权。
public class JwtAuthUser extends User implements UserDetails {
    private Collection<? extends GrantedAuthority> authorities;

    // 继承父类的 userId 字段（来自 User 实体）

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        // 若未设置权限，返回空列表而非 null
        return authorities != null ? authorities : new ArrayList<>();
    }

    public void setAuthorities(Collection<? extends GrantedAuthority> authorities) {
        this.authorities = authorities;
    }

    public JwtAuthUser() {
    }


    @Override
    public String getPassword() {
        return super.getPassword();
    }

    @Override
    public String getUsername() {
        return super.getUsername();
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
        //这里不能写成这样，因为判定账号是否锁定需要特定的逻辑放在认证成功的地方进行处理
        //return !super.getStatus().equals(ConstValueEnum.ACCOUNT_FREEZED);
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
