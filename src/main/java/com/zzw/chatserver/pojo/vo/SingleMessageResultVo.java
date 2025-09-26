package com.zzw.chatserver.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SingleMessageResultVo {
    private String id;
    private String roomId;
    private String senderId;
    private String receiverId; // 接收者ID字段
    private String senderName;
    private String senderNickname;
    private String senderAvatar;
    // 注册时间（默认初始化当前时间，格式：yyyy-MM-dd HH:mm:ss，时区：Asia/Shanghai）
    private String time = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private String fileRawName; // 文件的原始名字
    private String message;
    private String messageType;
    private List<String> isReadUser = new ArrayList<>();

    // 卡片字段（与 SingleMessage 对齐）
    private String cardType;
    private List<CardOptionVo> cardOptions;

}
