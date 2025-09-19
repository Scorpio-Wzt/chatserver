package com.zzw.chatserver.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum UserRoleEnum {
    BUYER("buyer", "买家"),
    CUSTOMER_SERVICE("customer_service", "客服");

    private final String code;
    private final String desc;
}