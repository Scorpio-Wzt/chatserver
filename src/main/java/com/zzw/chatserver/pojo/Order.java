package com.zzw.chatserver.pojo;

import lombok.Data;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 订单实体类（绑定用户与客服）
 */
@Data
@RequiredArgsConstructor // 生成包含@NonNull字段的构造方法
@Document(collection = "orders") // 对应MongoDB的orders集合
public class Order {
    /** 时间格式化器（统一时区：Asia/Shanghai） */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    @Id
    private ObjectId id;             // MongoDB自动生成的ID

    @Indexed(unique = true) // 订单编号唯一索引，确保不重复
    private String orderNo; // 订单编号（业务生成，如ORD20251009000001）

    @Indexed // 用户ID索引（加速用户维度查询）
    private String userId;           // 购买用户ID（可为空，如游客下单）

    @Indexed // 客服ID索引（加速客服维度查询）
    @NonNull // 强制非空，Lombok自动在构造方法中校验
    private String customerId;       // 关联客服ID（必传）

    private String productName;      // 商品名称（如“会员服务1个月”）

    private Double amount;           // 订单金额（单位：元）

    // 修正：引用Order内部的OrderStatus枚举，无需全路径
    private OrderStatus status;      // 订单状态（使用内部枚举，解决“找不到OrderStatus”问题）

    private String createTime;       // 创建时间（订单生成时自动设置）

    private String payTime;          // 支付时间（支付成功时设置）

    private String refundTime;       // 退款时间（发起/完成退款时设置）

    private String confirmTime;      // 确认收货时间（用户确认时设置）


    // ------------------------------ 核心：补充OrderStatus内部枚举 ------------------------------
    /**
     * 订单状态枚举（作为Order内部类，与订单强关联，避免路径错误）
     * code：存储到MongoDB的数字（与之前转换器逻辑匹配）
     * desc：前端展示的状态描述
     */
    public enum OrderStatus {
        PENDING_PAY(0, "待支付"),   // 初始状态：未支付
        PAID(1, "已支付"),         // 支付成功：可确认收货
        REFUNDING(2, "退款中"),    // 发起退款：待客服审核
        REFUNDED(3, "已退款"),     // 客服同意：退款完成
        CONFIRMED(4, "已签收");    // 用户确认：订单完成

        private final int code;    // 存储到MongoDB的数字（与转换器逻辑对应）
        private final String desc; // 前端展示用的描述

        // 枚举构造方法（默认private，无需显式写）
        OrderStatus(int code, String desc) {
            this.code = code;
            this.desc = desc;
        }

        // Getter：供转换器、Service层获取code/desc
        public int getCode() {
            return code;
        }

        public String getDesc() {
            return desc;
        }
    }


    // ------------------------------ 初始化与业务方法 ------------------------------
    /**
     * 初始化块：订单创建时自动设置默认值
     */
    {
        this.createTime = formatCurrentTime(); // 自动填充创建时间
        this.status = OrderStatus.PENDING_PAY; // 默认初始状态：待支付
    }

    /**
     * 格式化当前时间为字符串（统一处理，避免重复代码）
     */
    private static String formatCurrentTime() {
        return TIME_FORMATTER.format(Instant.now());
    }

    // 以下业务方法不变（与你之前的逻辑一致，确保Service层可正常调用）
    /** 标记订单为已支付 */
    public void markAsPaid() {
        this.status = OrderStatus.PAID;
        this.payTime = formatCurrentTime();
    }

    /** 标记订单为已签收 */
    public void markAsConfirmed() {
        this.status = OrderStatus.CONFIRMED;
        this.confirmTime = formatCurrentTime();
    }

    /** 标记订单为退款中 */
    public void markAsRefunding() {
        this.status = OrderStatus.REFUNDING;
        this.refundTime = formatCurrentTime();
    }

    /** 拒绝退款：回滚到已支付 */
    public void markAsRejectedRefund() {
        this.status = OrderStatus.PAID;
    }

    /** 标记订单为已退款 */
    public void markAsRefunded() {
        this.status = OrderStatus.REFUNDED;
        this.refundTime = formatCurrentTime();
    }
}