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
@Document("systemnotifications")
public class SystemNotification {
    @Id
    private ObjectId id;

    private String orderId; // 订单唯一标识（用于后端关联订单数据）

    private String time = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    private boolean isRead;

    @ApiModelProperty(value = "发送者ID（客服ID）", required = true)
    @NotBlank(message = "发送者ID不能为空")
    private String senderUid;

    @ApiModelProperty(value = "接收者ID（用户ID）", required = true)
    @NotBlank(message = "接收者ID不能为空")
    private String receiverUid;

    @ApiModelProperty(value = "通知类型（如CONFIRM_RECEIPT-确认收货）", required = true)
    @NotBlank(message = "通知类型不能为空")
    private String type;

    @ApiModelProperty(value = "订单号（确认收货类型必填）")
    private String orderNo;

    @ApiModelProperty(value = "通知内容")
    private String content;
}