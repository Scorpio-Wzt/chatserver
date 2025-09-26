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

    // 根据code获取枚举实例
    public static UserStatusEnum valueOfCode(Integer code) {
        for (UserStatusEnum status : values()) {
            if (status.getCode().equals(code)) {
                return status;
            }
        }
        throw new IllegalArgumentException("无效的用户状态码: " + code);
    }
}