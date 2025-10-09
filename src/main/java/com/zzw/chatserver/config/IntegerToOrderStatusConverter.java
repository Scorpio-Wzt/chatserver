package com.zzw.chatserver.config;

// 关键：导入 Spring 核心的 Converter 接口
import org.springframework.core.convert.converter.Converter;
// 关键：导入 Spring Data 的 ReadingConverter 注解
import org.springframework.data.convert.ReadingConverter;

import com.zzw.chatserver.pojo.Order;

// Integer 转枚举（从 MongoDB 读取时使用）
@ReadingConverter // 标记为“读取转换器”（数据库值 → 枚举）
public class IntegerToOrderStatusConverter implements Converter<Integer, Order.OrderStatus> {

    @Override
    public Order.OrderStatus convert(Integer source) {
        // 读取时，根据数据库的 code 匹配对应的枚举
        for (Order.OrderStatus status : Order.OrderStatus.values()) {
            if (status.getCode() == source) {
                return status;
            }
        }
        // 若 code 不存在（非法值），抛出异常（避免空指针）
        throw new IllegalArgumentException("非法的订单状态码：" + source);
    }
}