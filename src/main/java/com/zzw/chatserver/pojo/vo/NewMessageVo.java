package com.zzw.chatserver.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NewMessageVo {
    private String roomId;
    private String senderId;// 发送者Id
    private String receiverId;     // 接收者ID
    private String senderName;// 发送者登录名
    private String senderNickname;// 发送者昵称
    private String senderAvatar; // 发送者头像
    private String time = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private String fileRawName; //文件的原始名字
    private String digest; //md5加密字段
    private String message;// 消息内容
    private String messageType;// 消息的类型：emoji/text/img/file/sys
    private List<String> isReadUser; // 判断已经读取的用户，在发送消息时默认发送方已读取
    private String conversationType;
    private String cardType; // 卡片类型：如"serviceCard"标识服务卡片
    private List<CardOptionVo> cardOptions; // 卡片中的操作选项
//    private String signature; // 消息签名（Base64编码）
//    private Long timestamp; // 时间戳（毫秒级，防重放+签名内容）
}