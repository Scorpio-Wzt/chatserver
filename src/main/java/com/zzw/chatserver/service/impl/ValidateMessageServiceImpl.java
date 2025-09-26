package com.zzw.chatserver.service.impl;

import com.mongodb.client.result.UpdateResult;
import com.zzw.chatserver.dao.ValidateMessageDao;
import com.zzw.chatserver.pojo.ValidateMessage;
import com.zzw.chatserver.pojo.vo.SimpleGroup;
import com.zzw.chatserver.pojo.vo.ValidateMessageResponseVo;
import com.zzw.chatserver.pojo.vo.ValidateMessageResultVo;
import com.zzw.chatserver.service.ValidateMessageService;
import com.zzw.chatserver.utils.ValidationUtil;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 验证消息服务实现类
 * 实现ValidateMessageService接口定义的验证消息管理逻辑，依赖DAO层和MongoTemplate完成数据交互
 */
@Service
@Slf4j
public class ValidateMessageServiceImpl implements ValidateMessageService {

    @Resource
    private ValidateMessageDao validateMessageDao;

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 更新好友申请验证消息状态
     */
    @Override
    public void changeFriendValidateNewsStatus(String validateMessageId, Integer status) {
        // 构建查询条件：按消息ID精确匹配
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(validateMessageId)));
        // 构建更新操作：设置目标状态
        Update update = new Update().set("status", status);
        // 执行更新（操作validatemessages集合）
        mongoTemplate.upsert(query, update, "validatemessages");
    }

    /**
     * 更新加群申请验证消息状态
     */
    @Override
    public void changeGroupValidateNewsStatus(String validateMessageId, Integer status) {
        // 逻辑与好友申请状态更新一致，仅业务场景区分
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(validateMessageId)));
        Update update = new Update().set("status", status);
        mongoTemplate.upsert(query, update, "validatemessages");
    }

    /**
     * 查询当前用户收到的所有验证消息
     */
    @Override
    public List<ValidateMessageResponseVo> getMyValidateMessageList(String userId) {
        // 聚合查询：关联groups表获取群组信息，筛选当前用户为接收者的消息
        Aggregation aggregation = Aggregation.newAggregation(
                // 关联群组表（加群申请需获取群组名称、ID）
                Aggregation.lookup("groups", "groupId", "_id", "groupList"),
                // 筛选接收者为当前用户的消息
                Aggregation.match(Criteria.where("receiverId").is(new ObjectId(userId)))
        );

        // 执行聚合查询，获取原始结果（含关联的群组列表）
        List<ValidateMessageResultVo> validateMessages = mongoTemplate.aggregate(
                aggregation, "validatemessages", ValidateMessageResultVo.class
        ).getMappedResults();

        // 转换为返回VO列表（提取关键信息，封装群组简化信息）
        List<ValidateMessageResponseVo> responseVoList = new ArrayList<>();
        for (ValidateMessageResultVo son : validateMessages) {
            ValidateMessageResponseVo item = new ValidateMessageResponseVo();
            BeanUtils.copyProperties(son, item);

            // 若为加群申请（含groupId和groupList），封装群组简化信息
            if (son.getGroupId() != null && son.getGroupList() != null && !son.getGroupList().isEmpty()) {
                item.setGroupInfo(new SimpleGroup());
                item.getGroupInfo().setGid(son.getGroupList().get(0).getGroupId().toString());
                item.getGroupInfo().setTitle(son.getGroupList().get(0).getTitle());
            }

            responseVoList.add(item);
        }
        return responseVoList;
    }

    /**
     * 根据房间ID、状态、类型查询验证消息
     */
    @Override
    public ValidateMessage findValidateMessage(String roomId, Integer status, Integer validateType) {
        // 校验房间ID格式
        if (!ValidationUtil.isValidRoomId(roomId)) {
            log.error("查询验证消息失败：房间ID格式非法，roomId={}", roomId);
            return null; // 或抛出异常，根据业务需求处理
        }

        // 委托DAO层按复合条件查询（房间ID、状态、类型）
        return validateMessageDao.findValidateMessageByRoomIdAndStatusAndValidateType(roomId, status, validateType);
    }

    /**
     * 验证消息（避免重复申请）
     */
    @Override
    public ValidateMessage addValidateMessage(ValidateMessage validateMessage) {
        // 先查询是否存在「未处理」的同类型消息（status=0）
        ValidateMessage existingMsg = findValidateMessage(
                validateMessage.getRoomId(),
                0, // 0=未处理状态
                validateMessage.getValidateType()
        );

        // 不存在未处理消息则新增，存在则返回null（避免重复申请）
        if (existingMsg == null) {
            return validateMessageDao.save(validateMessage);
        }
        return null;
    }
}