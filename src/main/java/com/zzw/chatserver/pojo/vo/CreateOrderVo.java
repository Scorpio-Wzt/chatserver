package com.zzw.chatserver.pojo.vo;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

@Data
public class CreateOrderVo {
    @NotBlank(message = "用户ID不能为空")
    private String userId; // 购买用户ID

    @NotBlank(message = "商品信息不能为空")
    private String productName; // 商品描述（如“会员月卡”）

    @NotNull(message = "订单金额不能为空")
    private BigDecimal amount; // 订单金额（精确到分）
}