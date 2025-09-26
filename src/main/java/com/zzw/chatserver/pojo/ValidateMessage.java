package com.zzw.chatserver.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Data
@NoArgsConstructor
@Document(collection = "validatemessages")
public class ValidateMessage {
    @Id
    private ObjectId id;
    private String roomId;
    private ObjectId senderId;
    private String senderName;
    private String senderNickname; // 发送者昵称
    private String senderAvatar;  // 发送者头像
    private ObjectId receiverId;  // 接收者ID
    private String time = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private String additionMessage; // 附加消息
    private Integer status; // 0/1/2，未处理/同意/不同意
    private Integer validateType; // 0/1, 好友/群聊
    private ObjectId groupId; //群id
}
