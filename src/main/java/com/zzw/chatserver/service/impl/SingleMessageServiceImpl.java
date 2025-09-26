package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.pojo.SingleMessage;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;
import com.zzw.chatserver.pojo.vo.IsReadMessageRequestVo;
import com.zzw.chatserver.pojo.vo.SingleHistoryResultVo;
import com.zzw.chatserver.pojo.vo.SingleMessageResultVo;
import com.zzw.chatserver.service.SingleMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
@Slf4j // 引入日志
public class SingleMessageServiceImpl implements SingleMessageService {

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 按消息ID列表标记单聊消息为已读
     * 逻辑：筛选指定ID的消息 + 接收者匹配当前用户，将用户ID加入isReadUser列表
     */
    @Override
    public void markMessagesAsRead(String userId, List<String> messageIds) {
        // 参数校验：避免空值/空列表
        if (StringUtils.isEmpty(userId) || messageIds == null || messageIds.isEmpty()) {
            return;
        }

        // 转换消息ID为ObjectId（MongoDB主键类型）
        List<ObjectId> objectIds = messageIds.stream()
                .filter(id -> !StringUtils.isEmpty(id) && ObjectId.isValid(id))
                .map(ObjectId::new)
                .collect(Collectors.toList());
        if (objectIds.isEmpty()) {
            return;
        }

        // 构建查询条件：消息ID在列表中 + 接收者是当前用户（避免标记其他用户的消息）
        Criteria criteria = Criteria.where("_id").in(objectIds)
                .and("receiverId").is(userId)
                .and("isReadUser").nin(userId); // 只更新未读的消息

        // 构建更新操作：将当前用户ID加入isReadUser列表
        Update update = new Update();
        update.addToSet("isReadUser", userId);

        // 批量更新消息状态
        mongoTemplate.updateMulti(
                Query.query(criteria),
                update,
                SingleMessage.class,
                "singlemessages"
        );
    }

    @Override
    public List<SingleMessageResultVo> getUnreadMessages(String uid) {
        if (uid == null) {
            return new ArrayList<>(); // 避免返回null，返回空列表
        }
        // 条件：接收者为当前用户，且未读（isReadUser不包含当前用户UID）
        Criteria criteria = Criteria.where("receiverId").is(uid)
                .and("isReadUser").nin(uid);

        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "time"));

        return mongoTemplate.find(query, SingleMessageResultVo.class, "singlemessages");
    }

    @Override
    public void addNewSingleMessage(SingleMessage singleMessage) {
        if (singleMessage == null) {
            return; // 避免保存空消息
        }
        mongoTemplate.insert(singleMessage, "singlemessages");
    }

    @Override
    public SingleMessageResultVo getLastMessage(String roomId) {
        if (roomId == null) {
            return new SingleMessageResultVo(); // 返回空VO
        }
        Query query = Query.query(Criteria.where("roomId").is(roomId))
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .limit(1);

        SingleMessage message = mongoTemplate.findOne(query, SingleMessage.class, "singlemessages");
        return message != null ? convertToVo(message) : new SingleMessageResultVo();
    }

    @Override
    public List<SingleMessageResultVo> getRecentMessage(String roomId, int pageIndex, int pageSize) {
        // 参数校验优化：pageIndex从1开始，避免小于1的无效值
        if (roomId == null || roomId.trim().isEmpty() || pageIndex < 1 || pageSize <= 0) {
            log.warn("获取最近单聊消息参数无效：roomId={}, pageIndex={}, pageSize={}", roomId, pageIndex, pageSize);
            return Collections.emptyList(); // 返回空列表而非new ArrayList()，更高效
        }

        // 构建查询条件：按房间ID匹配，按消息发送时间倒序（最新消息优先）
        Query query = Query.query(Criteria.where("roomId").is(roomId.trim())) // trim()处理避免空格问题
                .with(Sort.by(Sort.Direction.DESC, "time")) // 核心修改：按time字段倒序
                .skip((long) (pageIndex - 1) * pageSize) // 修正分页：pageIndex从1开始时，跳过前(pageIndex-1)*pageSize条
                .limit(pageSize);

        List<SingleMessage> messages = mongoTemplate.find(query, SingleMessage.class, "singlemessages");
        log.info("查询单聊消息成功：roomId={}, 页码={}, 条数={}, 实际返回={}条",
                roomId, pageIndex, pageSize, messages.size());

        return messages.stream()
                .map(this::convertToVo)
                .collect(Collectors.toList());
    }


    @Override
    public SingleHistoryResultVo getSingleHistoryMsg(HistoryMsgRequestVo requestVo) {
        if (requestVo == null || StringUtils.isEmpty(requestVo.getRoomId())) {
            return new SingleHistoryResultVo();
        }
        Criteria criteria = Criteria.where("roomId").is(requestVo.getRoomId());

        // 搜索词模糊匹配
        if (requestVo.getQuery() != null && !requestVo.getQuery().isEmpty()) {
            criteria.and("message").regex(requestVo.getQuery(), "i");
        }

        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "time"))
                .skip((long) requestVo.getPageIndex() * requestVo.getPageSize())
                .limit(requestVo.getPageSize());

        long total = mongoTemplate.count(Query.query(criteria), "singlemessages");
        List<SingleMessage> messages = mongoTemplate.find(query, SingleMessage.class, "singlemessages");

        SingleHistoryResultVo resultVo = new SingleHistoryResultVo();
        resultVo.setTotal(total);
        resultVo.setMsgList(messages.stream().map(this::convertToVo).collect(Collectors.toList()));
        return resultVo;
    }

    @Override
    public void userIsReadMessage(IsReadMessageRequestVo requestVo) {
        if (requestVo == null || StringUtils.isEmpty(requestVo.getUserId()) || StringUtils.isEmpty(requestVo.getRoomId())) {
            return; // 参数不全则不执行更新
        }
        Criteria criteria = Criteria.where("roomId").is(requestVo.getRoomId())
                .and("receiverId").is(requestVo.getUserId())
                .and("isReadUser").nin(requestVo.getUserId());

        Update update = new Update();
        update.addToSet("isReadUser", requestVo.getUserId());

        mongoTemplate.updateMulti(
                Query.query(criteria),
                update,
                SingleMessage.class,
                "singlemessages"
        );
    }

    private SingleMessageResultVo convertToVo(SingleMessage message) {
        if (message == null) {
            return new SingleMessageResultVo();
        }
        SingleMessageResultVo vo = new SingleMessageResultVo();
        vo.setId(message.getId().toString());
        vo.setRoomId(message.getRoomId());
        vo.setSenderId(message.getSenderId().toString());
        vo.setReceiverId(message.getReceiverId());
        vo.setSenderName(message.getSenderName());
        vo.setSenderNickname(message.getSenderNickname());
        vo.setSenderAvatar(message.getSenderAvatar());
        // 将UTC时间（Instant）转换为本地时区（如UTC+8）
        if (message.getTime() != null && !message.getTime().trim().isEmpty()) {
            try {
                // 定义匹配 "Fri Sep 26 10:18:46 CST 2025" 格式的解析器
                DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern(
                        "EEE MMM dd HH:mm:ss zzz yyyy",
                        Locale.US  // 必须使用英文Locale解析英文月份/星期
                );
                // 解析原始时间字符串为ZonedDateTime（包含原始时区信息）
                ZonedDateTime inputTime = ZonedDateTime.parse(message.getTime().trim(), inputFormatter);

                // 转换为目标时区（Asia/Shanghai = UTC+8）
                ZonedDateTime shanghaiTime = inputTime.withZoneSameInstant(ZoneId.of("Asia/Shanghai"));

                // 格式化输出为"yyyy-MM-dd HH:mm:ss"
                DateTimeFormatter outputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
                vo.setTime(shanghaiTime.format(outputFormatter));
            } catch (DateTimeParseException e) {
                // 解析失败时记录日志并返回原始值，避免影响整体功能
                log.error("时间解析失败: 原始时间={}, 错误={}", message.getTime(), e.getMessage());
                vo.setTime(message.getTime()); // 保留原始值便于排查问题
            }
        }
        vo.setFileRawName(message.getFileRawName());
        vo.setMessage(message.getMessage());
        vo.setMessageType(message.getMessageType());
        vo.setIsReadUser(message.getIsReadUser());
        vo.setCardType(message.getCardType());
        vo.setCardOptions(message.getCardOptions());
        return vo;
    }
}
