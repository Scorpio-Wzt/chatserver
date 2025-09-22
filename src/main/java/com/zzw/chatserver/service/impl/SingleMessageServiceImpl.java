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
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SingleMessageServiceImpl implements SingleMessageService {

    @Resource
    private MongoTemplate mongoTemplate;

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
        if (roomId == null || pageIndex < 0 || pageSize <= 0) {
            return new ArrayList<>();
        }
        Query query = Query.query(Criteria.where("roomId").is(roomId))
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .skip((long) pageIndex * pageSize)
                .limit(pageSize);

        List<SingleMessage> messages = mongoTemplate.find(query, SingleMessage.class, "singlemessages");
        return messages.stream().map(this::convertToVo).collect(Collectors.toList());
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
