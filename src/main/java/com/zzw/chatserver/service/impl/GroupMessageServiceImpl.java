package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.dao.GroupMessageDao;
import com.zzw.chatserver.pojo.GroupMessage;
import com.zzw.chatserver.pojo.vo.GroupHistoryResultVo;
import com.zzw.chatserver.pojo.vo.GroupMessageResultVo;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;
import com.zzw.chatserver.service.GroupMessageService;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 群聊消息服务实现类
 * 实现GroupMessageService接口定义的群聊消息操作，依赖DAO层和MongoTemplate完成数据交互
 */
@Service
@Slf4j
public class GroupMessageServiceImpl implements GroupMessageService {

    @Resource
    private GroupMessageDao groupMessageDao;

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 按消息ID列表标记群聊消息为已读
     * 逻辑：筛选指定房间+指定ID的消息，将用户ID加入isReadUser列表
     */
    @Override
    public void markGroupMessagesAsRead(String roomId, String userId, List<String> messageIds) {
        // 参数校验：避免空值/空列表
        if (StringUtils.isEmpty(roomId) || StringUtils.isEmpty(userId)
                || messageIds == null || messageIds.isEmpty()) {
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

        // 构建查询条件：房间ID匹配 + 消息ID在列表中 + 未读（isReadUser不含当前用户）
        Criteria criteria = Criteria.where("roomId").is(roomId)
                .and("_id").in(objectIds)
                .and("isReadUser").nin(userId);

        // 构建更新操作：将当前用户ID加入isReadUser列表
        Update update = new Update();
        update.addToSet("isReadUser", userId);

        // 批量更新消息状态
        mongoTemplate.updateMulti(
                Query.query(criteria),
                update,
                GroupMessage.class,
                "groupmessages"
        );
    }

    @Override
    public void userIsReadGroupMessage(String roomId, String uid) {
        if (roomId == null || uid == null) {
            return; // 参数不全则不执行更新
        }
        Criteria criteria = Criteria.where("roomId").is(roomId)
                .and("isReadUser").nin(uid);

        Update update = new Update();
        update.addToSet("isReadUser", uid);

        mongoTemplate.updateMulti(
                Query.query(criteria),
                update,
                GroupMessage.class,
                "groupmessages"
        );
    }

    @Override
    public List<GroupMessageResultVo> getUnreadGroupMessages(String roomId, String uid) {
        if (roomId == null || uid == null) {
            return new ArrayList<>(); // 返回空列表避免null
        }
        Criteria criteria = Criteria.where("roomId").is(roomId)
                .and("isReadUser").nin(uid);

        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.ASC, "time"));

        return mongoTemplate.find(query, GroupMessageResultVo.class, "groupmessages");
    }

    /**
     * 获取群聊历史消息
     * 支持按消息类型、关键词、日期筛选，结合分页返回结果
     */
    @Override
    public GroupHistoryResultVo getGroupHistoryMessages(HistoryMsgRequestVo groupHistoryVo) {
        if (groupHistoryVo == null || StringUtils.isEmpty(groupHistoryVo.getRoomId())) {
            return new GroupHistoryResultVo();
        }
        // 1. 构建基础查询条件：房间ID匹配
        Criteria cri1 = Criteria.where("roomId").is(groupHistoryVo.getRoomId());
        Criteria cri2 = null;

        // 2. 按查询类型筛选
        if (!"all".equals(groupHistoryVo.getType())) {
            cri1.and("messageType").is(groupHistoryVo.getType())
                    .and("fileRawName").regex(Pattern.compile("^.*" + groupHistoryVo.getQuery() + ".*$", Pattern.CASE_INSENSITIVE));
        } else {
            cri2 = new Criteria().orOperator(
                    Criteria.where("message").regex(Pattern.compile("^.*" + groupHistoryVo.getQuery() + ".*$", Pattern.CASE_INSENSITIVE)),
                    Criteria.where("fileRawName").regex(Pattern.compile("^.*" + groupHistoryVo.getQuery() + ".*$", Pattern.CASE_INSENSITIVE))
            );
        }

        // 3. 按日期筛选
        if (groupHistoryVo.getDate() != null) {
            try {
                String dateStr = groupHistoryVo.getDate().trim();
                Instant inputInstant;

                // 定义CST格式解析器（英文星期+月份+时区）
                DateTimeFormatter cstFormatter = DateTimeFormatter.ofPattern(
                        "EEE MMM dd HH:mm:ss zzz yyyy",
                        Locale.US
                );

                // 格式识别与解析
                if (dateStr.matches("^[A-Za-z]{3} [A-Za-z]{3} \\d{2} \\d{2}:\\d{2}:\\d{2} [A-Z]{3} \\d{4}$")) {
                    // 解析CST格式：Fri Sep 26 10:18:46 CST 2025
                    ZonedDateTime cstTime = ZonedDateTime.parse(dateStr, cstFormatter);
                    inputInstant = cstTime.toInstant();
                } else {
                    // 解析ISO格式（如2025-09-26T01:02:38Z）
                    // 注意：Instant.parse()只接受1个参数，默认使用ISO格式
                    inputInstant = Instant.parse(dateStr);
                }

                // 计算当天时间范围
                ZoneId targetZone = ZoneId.of("Asia/Shanghai");
                ZonedDateTime localTime = ZonedDateTime.ofInstant(inputInstant, targetZone);

                ZonedDateTime dayStart = localTime.toLocalDate().atStartOfDay(targetZone);
                ZonedDateTime nextDayStart = dayStart.plusDays(1);

                Instant startInstant = dayStart.toInstant();
                Instant endInstant = nextDayStart.toInstant();

                cri1.and("time").gte(startInstant).lt(endInstant);

            } catch (DateTimeParseException e) {
                log.error("时间格式解析错误：{}", groupHistoryVo.getDate(), e);
                throw new BusinessException("无效的时间格式，请使用以下格式：\n" +
                        "1. 英文日期（如Fri Sep 26 10:18:46 CST 2025）\n" +
                        "2. ISO标准格式（如2025-09-26T01:02:38Z）");
            }
        }




        // 4. 构建最终查询对象
        Query query = new Query();
        if (cri2 != null) {
            query.addCriteria(new Criteria().andOperator(cri1, cri2));
        } else {
            query.addCriteria(cri1);
        }

        // 5. 统计总条数
        long count = mongoTemplate.count(query, GroupMessageResultVo.class, "groupmessages");

        // 6. 设置分页参数
        query.skip((long) groupHistoryVo.getPageIndex() * groupHistoryVo.getPageSize())
                .limit(groupHistoryVo.getPageSize());

        // 7. 执行查询
        List<GroupMessageResultVo> messageList = mongoTemplate.find(query, GroupMessageResultVo.class, "groupmessages");

        // 8. 封装结果
        return new GroupHistoryResultVo(messageList, count);
    }

    /**
     * 获取群聊最后一条消息
     * 按消息ID降序查询（MongoDB _id 自增，降序即最新消息），无消息时返回空VO
     */
    @Override
    public GroupMessageResultVo getGroupLastMessage(String roomId) {
        if (roomId == null) {
            return new GroupMessageResultVo();
        }
        Query query = Query.query(Criteria.where("roomId").is(roomId))
                .with(Sort.by(Sort.Direction.DESC, "_id"));

        GroupMessageResultVo res = mongoTemplate.findOne(query, GroupMessageResultVo.class, "groupmessages");
        return res != null ? res : new GroupMessageResultVo();
    }

    /**
     * 获取群聊最近消息
     * 按ID降序（最新消息在前），结合分页返回指定条数的消息
     */
    @Override
    public List<GroupMessageResultVo> getRecentGroupMessages(String roomId, Integer pageIndex, Integer pageSize) {
        if (roomId == null || pageIndex == null || pageSize == null
                || pageIndex < 0 || pageSize <= 0) {
            return new ArrayList<>();
        }
        Query query = Query.query(Criteria.where("roomId").is(roomId))
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .skip((long) pageIndex * pageSize)
                .limit(pageSize);

        return mongoTemplate.find(query, GroupMessageResultVo.class, "groupmessages");
    }

    /**
     * 保存新的群聊消息
     * 委托DAO层将消息实体持久化到数据库
     */
    @Override
    public void addNewGroupMessage(GroupMessage groupMessage) {
        if (groupMessage == null) {
            return; // 避免保存空消息
        }
        groupMessageDao.save(groupMessage);
    }
}