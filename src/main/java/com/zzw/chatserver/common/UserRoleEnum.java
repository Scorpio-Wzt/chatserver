package com.zzw.chatserver.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

@Getter
@AllArgsConstructor
public enum UserRoleEnum {
    BUYER("buyer", "普通用户"),
    CUSTOMER_SERVICE("customer_service", "客服");

    private final String code;
    private final String desc;

    // 根据code获取枚举实例
    public static UserRoleEnum fromCode(String code) {
        return Arrays.stream(values())
                .filter(role -> role.code.equals(code))
                .findFirst()
                .orElse(null); // 默认为null，代表未知角色
    }

    // 检查是否为客服角色
    public boolean isCustomerService() {
        return CUSTOMER_SERVICE.code.equals(this.code);
    }

    // 检查是否为普通用户角色
    public boolean isCustomer() {
        return BUYER.code.equals(this.code);
    }
}