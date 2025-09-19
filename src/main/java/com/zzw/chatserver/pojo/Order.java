package com.zzw.chatserver.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * 订单实体类
 */
@Data
@NoArgsConstructor
@Document(collection = "orders") // 对应MongoDB的orders集合
public class Order {
    @Id
    private ObjectId id;             // MongoDB自动生成的ID

    @Indexed(unique = true) // 自动创建orderNo的唯一索引
    private String orderNo; // 订单编号（唯一）

    @Indexed // 自动创建userId的普通索引
    private String userId;           // 关联的用户ID

    private String productName;      // 商品名称

    private Double amount;           // 订单金额

    private Integer status;          // 订单状态：0-待支付 1-已支付 2-退款中 3-已退款

    private Date createTime;         // 创建时间

    private Date payTime;            // 支付时间

    private Date refundTime;         // 退款时间
}