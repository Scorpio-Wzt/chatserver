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

    // 通用脱敏方法：根据字段类型自动选择脱敏策略
    public static String desensitize(String fieldName, String value) {
        if (value == null) return null;

        // 根据字段名判断需要使用的脱敏规则
        switch (fieldName.toLowerCase()) {
            case "phone":
            case "tel":
            case "telephone":
                return desensitizePhone(value);
            case "idcard":
            case "id_card":
            case "identity_card":
                return desensitizeIdCard(value);
            case "email":
                return desensitizeEmail(value);
            case "name":
                return desensitizeName(value);
            default:
                // 未知字段默认不脱敏
                return value;
        }
    }

    // 邮箱脱敏（保留@前1位和域名）：a***@qq.com
    public static String desensitizeEmail(String email) {
        if (email == null) return null;
        return email.replaceAll("(^.)[^@]*(@.*$)", "$1***$2");
    }

    // 姓名脱敏（保留姓氏，其他用*代替）：张**、李*
    public static String desensitizeName(String name) {
        if (name == null || name.length() <= 1) {
            return name; // 空值或单字姓名不脱敏
        }
        // 兼容Java 8及以下版本：用循环生成*
        StringBuilder star = new StringBuilder();
        for (int i = 1; i < name.length(); i++) {
            star.append("*");
        }
        return name.charAt(0) + star.toString();
    }
}
