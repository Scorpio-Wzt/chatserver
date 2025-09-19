package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.pojo.SingleMessage;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;
import com.zzw.chatserver.pojo.vo.IsReadMessageRequestVo;
import com.zzw.chatserver.pojo.vo.SingleHistoryResultVo;
import com.zzw.chatserver.pojo.vo.SingleMessageResultVo;
import com.zzw.chatserver.service.SingleMessageService;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SingleMessageServiceImpl implements SingleMessageService {

    // 在实现类中注入MongoTemplate，避免接口注入的初始化问题
    @Resource
    private MongoTemplate mongoTemplate;

    @Override
    public void addNewSingleMessage(SingleMessage singleMessage) {
        // 明确指定集合名，与测试代码和数据库保持一致
        mongoTemplate.insert(singleMessage, "singlemessages");
    }

    @Override
    public SingleMessageResultVo getLastMessage(String roomId) {
        // 按_id降序查询最新消息
        Query query = Query.query(Criteria.where("roomId").is(roomId))
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .limit(1);

        SingleMessage message = mongoTemplate.findOne(query, SingleMessage.class, "singlemessages");
        if (message == null) {
            return new SingleMessageResultVo(); // 返回空对象避免NPE
        }

        return convertToVo(message);
    }

    @Override
    public List<SingleMessageResultVo> getRecentMessage(String roomId, int pageIndex, int pageSize) {
        Query query = Query.query(Criteria.where("roomId").is(roomId))
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .skip((long) pageIndex * pageSize)
                .limit(pageSize);

        List<SingleMessage> messages = mongoTemplate.find(query, SingleMessage.class, "singlemessages");
        return messages.stream().map(this::convertToVo).collect(Collectors.toList());
    }

    @Override
    public SingleHistoryResultVo getSingleHistoryMsg(HistoryMsgRequestVo requestVo) {
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

    /**
     * 工具方法：将SingleMessage实体转换为SingleMessageResultVo
     */
    private SingleMessageResultVo convertToVo(SingleMessage message) {
        SingleMessageResultVo vo = new SingleMessageResultVo();
        vo.setId(message.getId().toString());
        vo.setRoomId(message.getRoomId());
        vo.setSenderId(message.getSenderId().toString());
        vo.setReceiverId(message.getReceiverId()); // 设置接收者ID（依赖VO新增的字段）
        vo.setSenderName(message.getSenderName());
        vo.setSenderNickname(message.getSenderNickname());
        vo.setSenderAvatar(message.getSenderAvatar());
        vo.setTime(message.getTime());
        vo.setFileRawName(message.getFileRawName());
        vo.setMessage(message.getMessage());
        vo.setMessageType(message.getMessageType());
        vo.setIsReadUser(message.getIsReadUser());
        vo.setCardType(message.getCardType());
        vo.setCardOptions(message.getCardOptions());
        return vo;
    }
}
    