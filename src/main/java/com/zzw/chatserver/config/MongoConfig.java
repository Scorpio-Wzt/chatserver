package com.zzw.chatserver.config;

import com.zzw.chatserver.config.IntegerToOrderStatusConverter;
import com.zzw.chatserver.config.OrderStatusToIntegerConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.convert.*;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class MongoConfig {

    /**
     * 配置 MongoDB 转换器，注册自定义枚举转换器
     */
    @Bean
    public MappingMongoConverter mappingMongoConverter(
            MongoDatabaseFactory databaseFactory,
            MongoMappingContext mappingContext) {

        // 创建默认的类型转换器（处理基本类型映射）
        DefaultDbRefResolver dbRefResolver = new DefaultDbRefResolver(databaseFactory);
        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, mappingContext);

        // 添加自定义枚举转换器（顺序不影响，Spring 会自动匹配类型）
        List<Converter<?, ?>> customConverters = new ArrayList<>();
        customConverters.add(new OrderStatusToIntegerConverter()); // 枚举 → Integer（存储）
        customConverters.add(new IntegerToOrderStatusConverter()); // Integer → 枚举（读取）

        // 将自定义转换器注册到 MongoDB 转换器中
        CustomConversions customConversions = new CustomConversions(customConverters);
        converter.setCustomConversions(customConversions);

        // 初始化转换器（必须调用，否则配置不生效）
        converter.afterPropertiesSet();

        return converter;
    }

    /**
     * 注册 MongoTemplate（可选，若 Spring 未自动配置，手动指定使用自定义的 converter）
     */
    @Bean
    public MongoTemplate mongoTemplate(
            MongoDatabaseFactory databaseFactory,
            MappingMongoConverter mappingMongoConverter) {
        return new MongoTemplate(databaseFactory, mappingMongoConverter);
    }
}