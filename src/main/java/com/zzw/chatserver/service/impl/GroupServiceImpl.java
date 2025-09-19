package com.zzw.chatserver.service.impl;

import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import com.zzw.chatserver.common.ConstValueEnum;
import com.zzw.chatserver.dao.AccountPoolDao;
import com.zzw.chatserver.dao.GroupDao;
import com.zzw.chatserver.dao.GroupUserDao;
import com.zzw.chatserver.pojo.AccountPool;
import com.zzw.chatserver.pojo.Group;
import com.zzw.chatserver.pojo.GroupUser;
import com.zzw.chatserver.pojo.vo.CreateGroupRequestVo;
import com.zzw.chatserver.pojo.vo.QuitGroupRequestVo;
import com.zzw.chatserver.pojo.vo.SearchGroupResponseVo;
import com.zzw.chatserver.pojo.vo.SearchGroupResultVo;
import com.zzw.chatserver.pojo.vo.SearchRequestVo;
import com.zzw.chatserver.service.GroupService;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 群组服务实现类
 * 实现GroupService接口定义的群组操作，依赖DAO层和MongoTemplate完成数据交互，含事务管理
 */
@Service
public class GroupServiceImpl implements GroupService {

    @Resource
    private GroupDao groupDao;

    @Resource
    private GroupUserDao groupUserDao;

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private AccountPoolDao accountPoolDao;

    /**
     * 根据群组ID获取群信息
     */
    @Override
    public Group getGroupInfo(String groupId) {
        Optional<Group> res = groupDao.findById(new ObjectId(groupId));
        return res.orElse(null);
    }

    /**
     * 搜索群组
     * 聚合查询群组表与用户表，按条件筛选并排除当前用户创建的群
     */
    @Override
    public List<SearchGroupResponseVo> searchGroup(SearchRequestVo requestVo, String uid) {
        // 聚合查询：关联群主用户表，按关键词模糊筛选，分页排序
        Aggregation aggregation = Aggregation.newAggregation(
                // 关联users表，获取群主信息
                Aggregation.lookup("users", "holderUserId", "_id", "holderUsers"),
                // 按搜索条件模糊匹配（不区分大小写）
                Aggregation.match(Criteria.where(requestVo.getType())
                        .regex(Pattern.compile("^.*" + requestVo.getSearchContent() + ".*$", Pattern.CASE_INSENSITIVE))),
                // 分页
                Aggregation.skip((long) requestVo.getPageIndex() * requestVo.getPageSize()),
                Aggregation.limit((long) requestVo.getPageSize()),
                // 按群ID降序（最新创建的群在前）
                Aggregation.sort(Sort.Direction.DESC, "_id")
        );

        // 执行聚合查询，获取原始结果
        List<SearchGroupResultVo> results = mongoTemplate.aggregate(
                aggregation, "groups", SearchGroupResultVo.class
        ).getMappedResults();

        // 转换为返回VO，排除当前用户创建的群
        List<SearchGroupResponseVo> groups = new ArrayList<>();
        for (SearchGroupResultVo son : results) {
            // 群主ID != 当前用户ID，才添加到结果列表
            if (!son.getHolderUsers().get(0).getUid().equals(uid)) {
                SearchGroupResponseVo item = new SearchGroupResponseVo();
                BeanUtils.copyProperties(son, item);
                item.setGid(son.getId()); // 设置群ID
                BeanUtils.copyProperties(son.getHolderUsers().get(0), item.getHolderUserInfo()); // 设置群主信息
                groups.add(item);
            }
        }
        return groups;
    }

    /**
     * 创建新群组
     * 流程：生成群账号 → 保存群信息 → 保存群主群成员关系 → 更新群ID字段
     */
    @Override
    public String createGroup(CreateGroupRequestVo requestVo) {
        // 1. 创建群账号（AccountPool），标记为已使用
        AccountPool accountPool = new AccountPool();
        accountPool.setType(2); // 2=群聊账号
        accountPool.setStatus(1); // 1=已使用（删除/注销时设为0）
        accountPoolDao.save(accountPool);

        // 2. 保存群基本信息
        Group group = new Group();
        if (requestVo.getTitle() != null) group.setTitle(requestVo.getTitle());
        if (requestVo.getDesc() != null) group.setDesc(requestVo.getDesc());
        if (requestVo.getImg() != null) group.setImg(requestVo.getImg());
        group.setHolderName(requestVo.getHolderName());
        group.setHolderUserId(new ObjectId(requestVo.getHolderUserId()));
        // 生成群编号：账号code + 初始值（ConstValueEnum.INITIAL_NUMBER）
        group.setCode(String.valueOf(accountPool.getCode() + ConstValueEnum.INITIAL_NUMBER));
        groupDao.save(group);

        // 3. 保存群主的群成员关系（标记为群主）
        GroupUser groupUser = new GroupUser();
        groupUser.setGroupId(group.getGroupId());
        groupUser.setUserId(group.getHolderUserId());
        groupUser.setUsername(group.getHolderName());
        groupUser.setHolder(1); // 1=群主
        groupUserDao.save(groupUser);

        // 4. 更新群表中的gid字段（与群ID一致，便于查询）
        Update update = new Update().set("gid", group.getGroupId().toString());
        Query query = Query.query(Criteria.where("_id").is(group.getGroupId()));
        mongoTemplate.upsert(query, update, Group.class);

        // 返回生成的群编号
        return group.getCode();
    }

    /**
     * 获取所有群组列表
     * 聚合查询群组表与用户表，返回包含群主信息的所有群
     */
    @Override
    public List<SearchGroupResultVo> getAllGroup() {
        Aggregation aggregation = Aggregation.newAggregation(
                // 关联users表，获取群主信息
                Aggregation.lookup("users", "holderUserId", "_id", "holderUsers")
        );

        // 执行聚合查询并返回结果
        AggregationResults<SearchGroupResultVo> groups = mongoTemplate.aggregate(
                aggregation, "groups", SearchGroupResultVo.class
        );
        return groups.getMappedResults();
    }

    /**
     * 退出群组（含事务管理，异常时回滚）
     */
    @Override
    @Transactional(rollbackFor = Throwable.class)
    public void quitGroup(QuitGroupRequestVo requestVo) {
        if (requestVo.getHolder() == 1) {
            // 【群主退群】：删除群所有数据
            // 1. 删除群所有消息（groupmessages集合）
            delGroupAllMessagesByGroupId(requestVo.getGroupId());
            // 2. 删除群所有成员（groupusers集合）
            delGroupAllUsersByGroupId(requestVo.getGroupId());
            // 3. 删除群本身（groups集合）
            groupDao.deleteById(new ObjectId(requestVo.getGroupId()));
        } else {
            // 【普通成员退群】：仅删除个人相关数据
            // 1. 删除当前用户发送的群消息
            delGroupMessagesByGroupIdAndSenderId(requestVo.getGroupId(), requestVo.getUserId());
            // 2. 删除当前用户的群成员关系
            delGroupUserByGroupIdAndUserId(requestVo.getGroupId(), requestVo.getUserId());
            // 3. 群人数减1
            decrGroupUserNum(requestVo.getGroupId());
        }
    }

    // -------------------------- 私有工具方法（仅内部使用） --------------------------

    /**
     * 工具方法：删除指定群的所有消息
     * @param groupId 群组ID
     */
    private void delGroupAllMessagesByGroupId(String groupId) {
        Query query = Query.query(Criteria.where("roomId").is(groupId));
        DeleteResult result = mongoTemplate.remove(query, "groupmessages");
    }

    /**
     * 工具方法：删除指定群的所有成员
     * @param groupId 群组ID
     */
    private void delGroupAllUsersByGroupId(String groupId) {
        Query query = Query.query(Criteria.where("groupId").is(new ObjectId(groupId)));
        DeleteResult result = mongoTemplate.remove(query, "groupusers");
    }

    /**
     * 工具方法：删除指定群中指定用户发送的所有消息
     * @param groupId 群组ID
     * @param senderId 发送者用户ID
     */
    private void delGroupMessagesByGroupIdAndSenderId(String groupId, String senderId) {
        Query query = Query.query(
                Criteria.where("roomId").is(groupId)
                        .and("senderId").is(new ObjectId(senderId))
        );
        DeleteResult result = mongoTemplate.remove(query, "groupmessages");
    }

    /**
     * 工具方法：删除指定群中的指定成员
     * @param groupId 群组ID
     * @param userId 用户ID
     */
    private void delGroupUserByGroupIdAndUserId(String groupId, String userId) {
        Query query = Query.query(
                Criteria.where("groupId").is(new ObjectId(groupId))
                        .and("userId").is(new ObjectId(userId))
        );
        DeleteResult result = mongoTemplate.remove(query, "groupusers");
    }

    /**
     * 工具方法：将指定群的人数减1
     * @param groupId 群组ID
     */
    private void decrGroupUserNum(String groupId) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(groupId)));
        Update update = new Update().inc("userNum", -1); // 人数自减1
        UpdateResult result = mongoTemplate.upsert(query, update, "groups");
    }
}