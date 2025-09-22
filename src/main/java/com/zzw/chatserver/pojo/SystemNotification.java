package com.zzw.chatserver.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;


@Data
@NoArgsConstructor
@Document("systemnotifications")
public class SystemNotification {
    @Id
    private ObjectId id;
    private String receiverUid; // 接收者UID
    private String senderUid; // 客服UID
    private String content; // 文字内容（可包含订单号，如“订单123456已送达，请确认收货”）
    private String type; // 如"CONFIRM_RECEIPT"
    private String orderId; // 订单唯一标识（用于后端关联订单数据）
    private String orderNo; // 订单编号（用于前端展示给用户）
    private Date time;
    private boolean isRead;
}