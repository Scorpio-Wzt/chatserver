package com.zzw.chatserver.utils;

import com.zzw.chatserver.pojo.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.stereotype.Component;

/**
 * 2. 写数据库：将OrderStatus枚举→转换为Integer状态值
 */
@Component
@WritingConverter // 标记为“写转换器”（区别于读转换器）
public class OrderStatusToIntegerConverterUtil implements Converter<Order.OrderStatus, Integer> {
    @Override
    public Integer convert(Order.OrderStatus source) {
        // 枚举转code（若为null，可返回默认值或抛异常）
        return source == null ? null : source.getCode();
    }
}