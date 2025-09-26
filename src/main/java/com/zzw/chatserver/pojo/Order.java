package com.zzw.chatserver.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 订单实体类（绑定用户与客服）
 */
@Data
@NoArgsConstructor
@Document(collection = "orders") // 对应MongoDB的orders集合
public class Order {
    @Id
    private ObjectId id;             // MongoDB自动生成的ID

    @Indexed(unique = true) // 订单编号唯一索引
    private String orderNo; // 订单编号（唯一）

    @Indexed // 用户ID索引（加速用户维度查询）
    private String userId;           // 购买用户ID

    @Indexed // 客服ID索引（加速客服维度查询）
    private String customerId;       // 关联客服ID（必传，非空）

    private String productName;      // 商品名称

    private Double amount;           // 订单金额

    private Integer status;          // 订单状态：0-待支付 1-已支付 2-退款中 3-已退款 4-已签收

    private String refundReason;      // 退款原因

    private String rejectReason;      // 拒绝退款原因

    private String createTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    private String payTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    private String refundTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    private String confirmTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
}