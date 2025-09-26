package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.dao.GroupMessageDao;
import com.zzw.chatserver.dao.GroupUserDao;
import com.zzw.chatserver.pojo.Group;
import com.zzw.chatserver.pojo.GroupMessage;
import com.zzw.chatserver.pojo.GroupUser;
import com.zzw.chatserver.pojo.vo.*;
import com.zzw.chatserver.service.GroupUserService;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
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
 * 群成员服务实现类
 * 实现GroupUserService接口定义的群成员管理逻辑，依赖DAO层和MongoTemplate完成数据交互
 */
@Service
public class GroupUserServiceImpl implements GroupUserService {

    @Resource
    private GroupUserDao groupUserDao;

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private GroupMessageDao groupMessageDao;

    /**
     * 检查用户是否为群成员：查询group_user关联表中是否存在匹配记录
     */
    @Override
    public boolean isGroupMember(String groupId, String userId) {
        // 参数校验（防御性编程）
        if (groupId == null || userId == null
                || !ObjectId.isValid(groupId)
                || !ObjectId.isValid(userId)) {
            return false; // 无效ID直接返回非成员
        }

        // 查询群成员关系
        GroupUser groupUser = groupUserDao.findGroupUserByUserIdAndGroupId(
                new ObjectId(groupId),
                new ObjectId(userId)
        );

        // 存在记录则为群成员
        return groupUser != null;
    }

    /**
     * 根据用户名查询该用户加入的所有群组
     */
    @Override
    public List<MyGroupResultVo> getGroupUsersByUserName(String username) {
        // 聚合查询：根据用户名找到群成员记录，关联群组表获取群组信息
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("username").is(username)),
                Aggregation.lookup("groups", "groupId", "_id", "groupInfo")
        );

        // 执行聚合查询并返回结果
        List<MyGroupResultVo> groupUsers = mongoTemplate.aggregate(
                aggregation, "groupusers", MyGroupResultVo.class
        ).getMappedResults();
        return groupUsers;
    }

    /**
     * 根据群组ID查询该群的所有成员
     */
    @Override
    public List<MyGroupResultVo> getGroupUsersByGroupId(String groupId) {
        // 聚合查询：根据群组ID找到所有群成员记录，关联用户表获取成员信息
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("groupId").is(new ObjectId(groupId))),
                Aggregation.lookup("users", "userId", "_id", "userList")
        );

        // 执行聚合查询，获取原始结果（含用户列表）
        List<MyGroupInfoQueryVo> queryVoList = mongoTemplate.aggregate(
                aggregation, "groupusers", MyGroupInfoQueryVo.class
        ).getMappedResults();

        // 转换为返回VO（提取用户信息到SimpleUser）
        List<MyGroupResultVo> res = new ArrayList<>();
        for (MyGroupInfoQueryVo son : queryVoList) {
            MyGroupResultVo item = new MyGroupResultVo();
            BeanUtils.copyProperties(son, item);
            // 设置成员基本信息（SimpleUser）
            item.setUserInfo(new SimpleUser());
            BeanUtils.copyProperties(son.getUserList().get(0), item.getUserInfo());
            res.add(item);
        }
        return res;
    }

    /**
     * 添加新成员到群组
     * 流程：校验成员是否已在群 → 不在则添加成员 → 群人数+1 → 发送加群系统消息
     */
    @Override
    public void addNewGroupUser(ValidateMessageResponseVo validateMessage) {
        // 校验成员是否已在群中（避免重复添加）
        ObjectId userId = new ObjectId(validateMessage.getSenderId());
        ObjectId groupId = new ObjectId(validateMessage.getGroupInfo().getGid());
        GroupUser existingGroupUser = groupUserDao.findGroupUserByUserIdAndGroupId(userId, groupId);

        if (existingGroupUser == null) {
            // 群成员记录
            GroupUser groupUser = new GroupUser();
            groupUser.setGroupId(groupId);
            groupUser.setUserId(userId);
            groupUser.setUsername(validateMessage.getSenderName());
            groupUserDao.save(groupUser);

            // 群人数加1（更新groups表的userNum字段）
            Update update = new Update();
            Query query = Query.query(Criteria.where("_id").is(groupId));
            mongoTemplate.upsert(query, update.inc("userNum", 1), Group.class);

            // 发送"加入群聊"系统消息（groupmessages表）
            GroupMessage groupMessage = new GroupMessage();
            groupMessage.setRoomId(groupId.toString());
            groupMessage.setSenderId(userId); // 记录发送者ID，便于后续退群删除消息
            groupMessage.setMessageType("sys"); // 系统消息类型
            groupMessage.setMessage(groupUser.getUsername() + "加入群聊");
            groupMessageDao.save(groupMessage);
        }
    }

    /**
     * 根据用户ID和群组ID列表，查询用户的最近群组
     */
    @Override
    public List<MyGroupResultVo> getRecentGroup(RecentGroupVo recentGroupVo) {
        // 转换群组ID列表为ObjectId格式
        List<ObjectId> groupIds = new ArrayList<>();
        for (String son : recentGroupVo.getGroupIds()) {
            groupIds.add(new ObjectId(son));
        }

        // 聚合查询：关联群组表，筛选用户的指定群组
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.lookup("groups", "groupId", "_id", "groupList"),
                Aggregation.match(
                        Criteria.where("groupId").in(groupIds)
                                .and("userId").is(new ObjectId(recentGroupVo.getUserId()))
                )
        );

        // 执行聚合查询，获取原始结果（含群组列表）
        List<RecentGroupQueryVo> groupUsers = mongoTemplate.aggregate(
                aggregation, "groupusers", RecentGroupQueryVo.class
        ).getMappedResults();

        // 转换为返回VO（提取群组信息）
        List<MyGroupResultVo> res = new ArrayList<>();
        for (RecentGroupQueryVo son : groupUsers) {
            MyGroupResultVo item = new MyGroupResultVo();
            BeanUtils.copyProperties(son, item);
            item.setGroupInfo(son.getGroupList().get(0)); // 设置群组基本信息
            res.add(item);
        }
        return res;
    }
}