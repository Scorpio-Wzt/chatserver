package com.zzw.chatserver.utils;

import com.zzw.chatserver.pojo.Order; // 导入Order类（内部包含OrderStatus）
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter; // 标记为“读取时的转换器”
import org.springframework.stereotype.Component;

@Component
// 1. 读数据库：将MongoDB中的Integer状态值 → 转换为Order的内部枚举OrderStatus
@ReadingConverter // 必须加：Spring Data MongoDB识别该转换器的作用
public class IntegerToOrderStatusConverterUtil implements Converter<Integer, Order.OrderStatus> {
    @Override
    public Order.OrderStatus convert(Integer source) {
        // 若数据库值为null，返回null（或抛异常，根据业务决定）
        if (source == null) {
            return null;
        }
        // 遍历Order的内部枚举，根据code匹配
        for (Order.OrderStatus status : Order.OrderStatus.values()) {
            if (status.getCode() == source) {
                return status;
            }
        }
        // 匹配不到时抛异常，避免后续空指针
        throw new IllegalArgumentException("无效的订单状态值：" + source);
    }
}