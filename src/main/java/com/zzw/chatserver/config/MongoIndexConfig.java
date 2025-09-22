package com.zzw.chatserver.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Criteria;

import javax.annotation.Resource;

/**
 * MongoDB索引配置类，启动时为消息集合创建复合索引，优化查询性能
 */
@Configuration
public class MongoIndexConfig {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 初始化单聊消息集合索引
     * 优化场景：查询用户未读消息（receiverId + isReadUser）
     */
    @Bean
    public void initSingleMessageIndexes() {
        IndexOperations indexOps = mongoTemplate.indexOps("singlemessages");
        // 创建复合索引：receiverId(升序) + isReadUser(升序)
        indexOps.ensureIndex(new Index()
                .on("receiverId", org.springframework.data.domain.Sort.Direction.ASC)
                .on("isReadUser", org.springframework.data.domain.Sort.Direction.ASC)
                .named("idx_single_receiver_isRead"));
    }

    /**
     * 初始化群聊消息集合索引
     * 优化场景：查询群聊未读消息（roomId + isReadUser）
     */
    @Bean
    public void initGroupMessageIndexes() {
        IndexOperations indexOps = mongoTemplate.indexOps("groupmessages");
        // 创建复合索引：roomId(升序) + isReadUser(升序)
        indexOps.ensureIndex(new Index()
                .on("roomId", org.springframework.data.domain.Sort.Direction.ASC)
                .on("isReadUser", org.springframework.data.domain.Sort.Direction.ASC)
                .named("idx_group_room_isRead"));
    }
}
