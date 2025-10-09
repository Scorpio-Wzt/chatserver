package com.zzw.chatserver.config;

import com.zzw.chatserver.utils.IntegerToOrderStatusConverterUtil;
import com.zzw.chatserver.utils.OrderStatusToIntegerConverterUtil;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import java.util.Arrays;

/**
 * MongoDB自定义配置：注册枚举转换器
 */
@Configuration
public class MongoConverterConfig {

    @Bean
    public MongoCustomConversions mongoCustomConversions(
            IntegerToOrderStatusConverterUtil integerToOrderStatusConverter,
            OrderStatusToIntegerConverterUtil orderStatusToIntegerConverter) {
        // 将两个转换器加入Mongo的自定义转换列表
        return new MongoCustomConversions(Arrays.asList(
                integerToOrderStatusConverter,
                orderStatusToIntegerConverter
        ));
    }
}