package com.zzw.chatserver.pojo.vo;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class CreateOrderVo {
    @NotBlank(message = "用户ID不能为空")
    private String userId;  // 购买用户ID

    @NotBlank(message = "客服ID不能为空")
    private String customerId;  // 关联客服ID（新增，必传）

    @NotBlank(message = "商品名称不能为空")
    private String productName; // 商品名称

    @NotNull(message = "订单金额不能为空")
    private BigDecimal amount;  // 订单金额（精确到分）
}
