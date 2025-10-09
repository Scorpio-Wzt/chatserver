package com.zzw.chatserver.config;

// 关键：导入 Spring 核心的 Converter 接口
import org.springframework.core.convert.converter.Converter;
// 关键：导入 Spring Data 的 WritingConverter 注解
import org.springframework.data.convert.WritingConverter;

import com.zzw.chatserver.pojo.Order;

// 枚举转 Integer（存储到 MongoDB 时使用）
@WritingConverter // 标记为“写入转换器”（枚举 → 数据库值）
public class OrderStatusToIntegerConverter implements Converter<Order.OrderStatus, Integer> {

    @Override
    public Integer convert(Order.OrderStatus source) {
        // 存储时，将枚举转为其 code（如 OrderStatus.PENDING_PAY → 0）
        return source.getCode();
    }
}