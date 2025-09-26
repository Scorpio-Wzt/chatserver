package com.zzw.chatserver.pojo;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import javax.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Data
@NoArgsConstructor
@Document("sensitivemessages")
public class SensitiveMessage {
    @Id
    private ObjectId id;

    private String roomId; //房间号

    private String senderName; // 发送者登录名

    private String type; // 0/1/2, 好友/群聊/验证消息

    @ApiModelProperty(value = "发送消息的用户ID", required = true)
    @NotBlank(message = "用户ID不能为空")
    private String senderId;

    @ApiModelProperty(value = "待过滤的消息内容", required = true)
    @NotBlank(message = "消息内容不能为空")
    private String message;

    @ApiModelProperty(value = "消息发送时间")
    private String time = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

}
