// UserStatusEnum.java
package com.zzw.chatserver.common;

import lombok.Getter;

@Getter
public enum UserStatusEnum {
    NORMAL(0, "正常可用"),
    FREEZED(1, "冻结不可用"),
    CANCELED(2, "注销不可用");

    private final Integer code;
    private final String desc;

    UserStatusEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}