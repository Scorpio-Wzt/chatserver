package com.zzw.chatserver.utils;

public class SensitiveInfoDesensitizerUtil {
    // 手机号脱敏：138****5678
    public static String desensitizePhone(String phone) {
        if (phone == null) return null;
        return phone.replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
    }

    // 身份证号脱敏：110********1234
    public static String desensitizeIdCard(String idCard) {
        if (idCard == null) return null;
        return idCard.replaceAll("(\\d{3})\\d{11}(\\d{4})", "$1***********$2");
    }
}