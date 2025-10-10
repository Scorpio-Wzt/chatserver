package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.dao.GoodFriendDao;
import com.zzw.chatserver.dao.UserDao;
import com.zzw.chatserver.pojo.GoodFriend;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.*;
import com.zzw.chatserver.service.GoodFriendService;
import com.zzw.chatserver.service.UserService;
import com.zzw.chatserver.utils.DateUtil;
import com.zzw.chatserver.utils.ValidationUtil;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.ConditionalOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.*;

/**
 * 好友服务实现类
 * 实现GoodFriendService接口定义的好友管理逻辑，依赖DAO层和MongoTemplate完成数据操作
 */
@Service
public class GoodFriendServiceImpl implements GoodFriendService {

    // 定义Logger实例
    private static final Logger logger = LoggerFactory.getLogger(GoodFriendServiceImpl.class);

    @Resource
    private GoodFriendDao goodFriendDao;

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private UserDao userDao;

    @Resource
    private UserService userService;


    @Override
    public List<SingleRecentConversationResultVo> getRecentChatFriends(RecentConversationVo recentConversationVo) {
        // 参数校验与处理
        String currentUserId = recentConversationVo.getUserId();
        if (currentUserId == null || !ObjectId.isValid(currentUserId)) {
            throw new BusinessException(ResultEnum.INVALID_USER_ID, "用户ID格式错误");
        }
        ObjectId currentUserObjId = new ObjectId(currentUserId);

        // 处理maxCount参数（核心控制：允许0，默认20条）
        Integer maxCount = recentConversationVo.getMaxCount();
        // 防御性处理：null→默认20条，<0→0，>100→100（避免查询过多）
        int actualMaxCount = (maxCount == null) ? 20 : Math.max(0, Math.min(maxCount, 100));

        // 若maxCount=0，直接返回空列表
        if (actualMaxCount == 0) {
            return new ArrayList<>();
        }
        // 聚合查询
        Aggregation aggregation = Aggregation.newAggregation(
                // 筛选当前用户参与的消息
                Aggregation.match(
                        Criteria.where("senderId").is(currentUserObjId)
                                .orOperator(Criteria.where("receiverId").is(currentUserObjId))
                ),
                // 步骤1：新增对方用户ID字段（otherSideId）
                Aggregation.addFields()
                        .addField("otherSideId")
                        .withValue(
                                ConditionalOperators.when(Criteria.where("senderId").is(currentUserObjId))
                                        .then("receiverId")
                                        .otherwise("senderId")
                        )
                        .build(),
                // 步骤2：新增临时字段（targetFriendField）存储动态关联的字段名
                Aggregation.addFields()
                        .addField("targetFriendField")
                        .withValue(
                                // 根据当前用户ID判断好友表中要关联的字段是userY还是userM
                                ConditionalOperators.when(Criteria.where("userM").is(currentUserObjId))
                                        .then("userY")  // 当前用户是userM → 关联userY
                                        .otherwise("userM")  // 否则关联userM
                        )
                        .build(),
                // 按对方ID分组，取最后一条消息
                Aggregation.group("otherSideId")
                        .first("otherSideId").as("otherSideId")
                        .max("sendTime").as("lastMsgTime")
                        .first("content").as("lastMsgContent"),
                // 按最后消息时间倒序
                Aggregation.sort(Sort.Direction.DESC, "lastMsgTime"),
                // 限制最大返回数量
                Aggregation.limit(actualMaxCount),
                // 关联用户表（正常关联）
                Aggregation.lookup("users", "otherSideId", "_id", "otherUserInfo"),
                // 步骤3：关联好友表 → 使用临时字段targetFriendField（字符串类型）
                Aggregation.lookup(
                        "goodfriends",       // 关联的集合名
                        "otherSideId",       // 本地字段（当前结果中的对方ID）
                        "targetFriendField", // 引用临时字段（存储动态字段名，字符串类型）
                        "friendRelation"     // 结果存储字段
                ),
                // 仅保留好友记录
                Aggregation.match(Criteria.where("friendRelation").not().size(0))
        );

        // 执行查询
        AggregationResults<RecentChatFriendGroupVo> results = mongoTemplate.aggregate(
                aggregation, "message", RecentChatFriendGroupVo.class
        );
        List<RecentChatFriendGroupVo> groupVos = results.getMappedResults();

        // 转换结果（无需分页处理，直接映射）
        List<SingleRecentConversationResultVo> resultList = new ArrayList<>();
        for (RecentChatFriendGroupVo group : groupVos) {
            if (group.getOtherUserInfo() == null || group.getOtherUserInfo().isEmpty()) {
                continue;
            }
            User otherUser = group.getOtherUserInfo().get(0);

            SingleRecentConversationResultVo vo = new SingleRecentConversationResultVo();
            vo.setId(group.getOtherSideId().toString());
            vo.setLastMsgContent(group.getLastMsgContent());
            vo.setLastMsgTime(DateUtil.format(new Date(group.getLastMsgTime()), DateUtil.yyyy_MM_dd_HH_mm_ss));
            vo.setIsFriend(true);

            // 设置用户信息
            SimpleUser currentUser = new SimpleUser();
            currentUser.setUid(currentUserId);
            // currentUser.setNickname(...); // 补充当前用户信息

            SimpleUser otherSideUser = new SimpleUser();
            otherSideUser.setUid(otherUser.getUid());
            otherSideUser.setNickname(otherUser.getNickname());
            otherSideUser.setPhoto(otherUser.getPhoto());
            otherSideUser.setLevel(computedLevel(otherUser.getOnlineTime()));

            vo.setUserM(currentUser);
            vo.setUserY(otherSideUser);

            resultList.add(vo);
        }

        return resultList;
    }

    /**
     * 获取当前用户的好友列表
     * 聚合查询好友关系表与用户表，返回包含好友信息、等级、房间ID的列表
     */
    @Override
    public List<MyFriendListResultVo> getMyFriendsList(String userId) {
        // 聚合查询：当前用户为"发起方"的好友关系（userM=当前用户）
        Aggregation aggregation1 = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userM").is(new ObjectId(userId))),
                Aggregation.lookup("users", "userY", "_id", "uList")
        );
        // 聚合查询：当前用户为"接收方"的好友关系（userY=当前用户）
        Aggregation aggregation2 = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("userY").is(new ObjectId(userId))),
                Aggregation.lookup("users", "userM", "_id", "uList")
        );

        // 执行聚合查询，获取结果
        List<MyFriendListVo> results1 = mongoTemplate.aggregate(aggregation1, "goodfriends", MyFriendListVo.class).getMappedResults();
        List<MyFriendListVo> results2 = mongoTemplate.aggregate(aggregation2, "goodfriends", MyFriendListVo.class).getMappedResults();

        // 转换为返回VO列表
        List<MyFriendListResultVo> resList = new ArrayList<>();
        resList.addAll(convertToFriendResultVo(results1, userId, true));  // 发起方好友
        resList.addAll(convertToFriendResultVo(results2, userId, false)); // 接收方好友

        // 关键：按好友唯一ID去重（假设MyFriendListResultVo中有friendId字段标识好友的唯一ID）
        Set<String> uniqueFriendIds = new HashSet<>();
        List<MyFriendListResultVo> uniqueResList = new ArrayList<>();
        for (MyFriendListResultVo vo : resList) {
            // 假设好友的唯一标识字段为friendId（根据实际字段名调整）
            String friendId = vo.getId();
            if (friendId != null && uniqueFriendIds.add(friendId)) {
                // add()返回true表示该好友ID未出现过，保留当前记录
                uniqueResList.add(vo);
            }
        }

        return uniqueResList;
    }

    /**
     * 添加好友（补充双向关系）
     * 同时创建A→B和B→A两条记录，确保好友关系双向生效
     */
    @Override
    public void addFriend(GoodFriend goodFriend) {
        ObjectId userM = goodFriend.getUserM();
        ObjectId userY = goodFriend.getUserY();

        // 校验用户ID格式（转换为字符串后校验）
        if (!ValidationUtil.isValidObjectId(userM.toString())
                || !ValidationUtil.isValidObjectId(userY.toString())) {
            logger.error("添加好友失败：用户ID格式非法，userM={}, userY={}", userM, userY);
            throw new IllegalArgumentException("用户ID格式错误");
        }

        // 校验正向（A→B）和反向（B→A）关系是否已存在
        Query queryForward = Query.query(
                Criteria.where("userM").is(userM).and("userY").is(userY)
        );
        Query queryReverse = Query.query(
                Criteria.where("userM").is(userY).and("userY").is(userM)
        );

        GoodFriend existingForward = mongoTemplate.findOne(queryForward, GoodFriend.class);
        GoodFriend existingReverse = mongoTemplate.findOne(queryReverse, GoodFriend.class);

        // 若双向关系都不存在，则创建并保存
        if (existingForward == null && existingReverse == null) {
            // 保存正向关系（A→B）
            goodFriend.setCreateDate(String.valueOf(Instant.now())); // 补充创建时间（如果需要）
            goodFriendDao.save(goodFriend);

            // 创建并保存反向关系（B→A）
            GoodFriend reverseFriend = new GoodFriend();
            reverseFriend.setUserM(userY);       // 原接收方变为发起方
            reverseFriend.setUserY(userM);       // 原发起方变为接收方
            reverseFriend.setCreateDate(String.valueOf(Instant.now())); // 与正向关系同时间
            // 若有其他属性（如备注、分组），也需要同步设置
            // reverseFriend.setRemark(goodFriend.getRemark());

            goodFriendDao.save(reverseFriend);

            // 双向添加到"我的好友"分组
            modifyNewUserFenZu(userM.toString(), userY.toString());
            modifyNewUserFenZu(userY.toString(), userM.toString());
        }
    }


    // 批量添加好友（用于批量处理）
    @Override
    public void batchAddFriends(List<GoodFriend> friends) {
        mongoTemplate.insertAll(friends);
    }

    /**
     * 删除好友
     *  删除好友关系、双方单聊记录、双方好友分组及备注信息
     */
    @Override
    public void deleteFriend(DelGoodFriendRequestVo requestVo) {
        String userM = requestVo.getUserM();
        String userY = requestVo.getUserY();
        String roomId = requestVo.getRoomId();
        // 校验用户ID和房间ID格式
        if (!ValidationUtil.isValidObjectId(userM) || !ValidationUtil.isValidObjectId(userY)) {
            logger.error("删除好友失败：用户ID格式非法，userM={}, userY={}", userM, userY);
            throw new IllegalArgumentException("用户ID格式错误");
        }
        if (!ValidationUtil.isValidSingleRoomId(roomId)) {
            logger.error("删除好友失败：单聊房间ID格式非法，roomId={}", roomId);
            throw new IllegalArgumentException("单聊房间ID格式错误");
        }
        // 构建双向删除条件（主动删除者与被动删除者的双向关系）
        Criteria criteriaA = Criteria.where("userY").is(new ObjectId(requestVo.getUserM()))
                .and("userM").is(new ObjectId(requestVo.getUserY()));
        Criteria criteriaB = Criteria.where("userM").is(new ObjectId(requestVo.getUserM()))
                .and("userY").is(new ObjectId(requestVo.getUserY()));
        Criteria criteria = new Criteria().orOperator(criteriaA, criteriaB);

        // 删除好友关系
        Query query = Query.query(criteria);
        mongoTemplate.findAndRemove(query, GoodFriend.class);

        // 删除双方单聊记录
        delSingleHistoryMessage(requestVo.getRoomId());

        // 双向删除好友分组及备注信息
        delFriendFenZuAndBeiZhu(requestVo.getUserM(), requestVo.getUserY());
        delFriendFenZuAndBeiZhu(requestVo.getUserY(), requestVo.getUserM());
    }

    /**
     * 校验两个用户是否可以对话
     * 规则：
     * 1. 任意一方是客服（role=service），可以直接对话
     * 2. 双方都是买家，需要已建立好友关系
     */
    @Override
    public boolean checkIsFriend(String userId, String friendId) {
        // 1. 参数格式校验
        if (!ValidationUtil.isValidObjectId(userId) || !ValidationUtil.isValidObjectId(friendId)) {
            logger.error("好友关系校验失败：用户ID格式非法，userId={}, friendId={}", userId, friendId);
            return false;
        }

        // 参数为空直接返回非好友
        if (StringUtils.isEmpty(userId) || StringUtils.isEmpty(friendId)) {
            return false;
        }

        // 查询双方用户信息，判断是否有客服角色
        User user = userService.getUserInfo(userId);
        User friend = userService.getUserInfo(friendId);

        // 若任意一方是客服，直接判定为可对话（默认是好友）
        if ((user != null && "service".equals(user.getRole())) ||
                (friend != null && "service".equals(friend.getRole()))) {
            return true;
        }

        // 买家之间需校验实际好友关系（双向校验）
        ObjectId userM = new ObjectId(userId);
        ObjectId userY = new ObjectId(friendId);
        // 校验A->B的关系
        long count1 = mongoTemplate.count(
                Query.query(Criteria.where("userM").is(userM).and("userY").is(userY)),
                GoodFriend.class
        );
        // 校验B->A的关系（防止单向添加）
        long count2 = mongoTemplate.count(
                Query.query(Criteria.where("userM").is(userY).and("userY").is(userM)),
                GoodFriend.class
        );
        return count1 > 0 || count2 > 0;
    }

    // -------------------------- 私有工具方法 --------------------------

    /**
     * 工具方法：将MyFriendListVo转换为MyFriendListResultVo
     * @param sourceList 源列表（聚合查询结果）
     * @param currentUserId 当前用户ID
     * @param isInitiator 是否为"发起方"好友（userM=当前用户）
     * @return 转换后的好友列表VO
     */
    private List<MyFriendListResultVo> convertToFriendResultVo(List<MyFriendListVo> sourceList, String currentUserId, boolean isInitiator) {
        List<MyFriendListResultVo> resultList = new ArrayList<>();
        for (MyFriendListVo son : sourceList) {
            MyFriendListResultVo item = new MyFriendListResultVo();
            if (son.getUList() == null || son.getUList().isEmpty()) {
                continue; // 跳过无效数据
            }
            User friendUser = son.getUList().get(0);
            // 设置好友基本信息
            item.setCreateDate(son.getCreateDate());
            item.setNickname(friendUser.getNickname());
            item.setUsername(friendUser.getUsername());
            item.setRole(friendUser.getRole());
            item.setPhoto(friendUser.getPhoto());
            item.setSignature(friendUser.getSignature());
            item.setId(friendUser.getUserId().toString());
            // 计算并设置好友等级
            item.setLevel(computedLevel(friendUser.getOnlineTime()));
            // 生成单聊房间ID（按ID字典序排序，避免重复）
            String friendUid = friendUser.getUserId().toString();
            if (currentUserId.compareTo(friendUid) < 0) {
                item.setRoomId(currentUserId + "-" + friendUid);
            } else {
                item.setRoomId(friendUid + "-" + currentUserId);
            }
            resultList.add(item);
        }
        return resultList;
    }

    /**
     * 工具方法：根据用户在线时间计算等级
     * @param onlineTime 在线时间（毫秒）
     * @return 用户等级（1-8级，超过8级按8级算）
     */
    private Integer computedLevel(Long onlineTime) {
        if (onlineTime == null) {
            return 1;
        }
        // 在线时间转换为小时（向上取整）
        double toHour = onlineTime.doubleValue() / 1000.0 / 60.0 / 60.0;
        int res = (int) Math.ceil(toHour);
        // 等级上限为8级
        return Math.min(res, 8);
    }

    /**
     * 工具方法：将好友添加到用户的"我的好友"分组
     * @param uid 当前用户ID
     * @param friendId 好友ID
     */
    private void modifyNewUserFenZu(String uid, String friendId) {
        // 获取当前用户信息
        User userInfo = getUser(uid);
        if (userInfo == null) {
            return;
        }

        // 获取好友分组Map，添加好友到"我的好友"
        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        friendFenZuMap.get("我的好友").add(friendId);

        // 更新用户分组信息
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(uid)));
        Update update = new Update().set("friendFenZu", friendFenZuMap);
        mongoTemplate.findAndModify(query, update, User.class);
    }

    /**
     * 工具方法：删除单聊历史消息
     * @param roomId 单聊房间ID
     */
    private void delSingleHistoryMessage(String roomId) {
        Query query = Query.query(Criteria.where("roomId").is(roomId));
        mongoTemplate.remove(query, "singlemessages");
    }

    /**
     * 工具方法：根据用户ID获取用户信息
     * @param uid 用户ID（字符串格式）
     * @return 用户实体（不存在返回null）
     */
    private User getUser(String uid) {
        return userDao.findById(new ObjectId(uid)).orElse(null);
    }

    /**
     * 工具方法：删除用户的好友分组及备注信息
     * @param myId 当前用户ID
     * @param friendId 被删除好友ID
     */
    private void delFriendFenZuAndBeiZhu(String myId, String friendId) {
        // 获取当前用户信息
        User userInfo = getUser(myId);
        if (userInfo == null) {
            return;
        }

        // 1. 删除好友分组中的该好友
        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        boolean isRemoved = false;
        for (Map.Entry<String, ArrayList<String>> entry : friendFenZuMap.entrySet()) {
            Iterator<String> iterator = entry.getValue().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().equals(friendId)) {
                    iterator.remove();
                    isRemoved = true;
                    break;
                }
            }
            if (isRemoved) {
                break;
            }
        }

        // 2. 删除好友备注信息
        Map<String, String> friendBeiZhuMap = userInfo.getFriendBeiZhu();
        friendBeiZhuMap.remove(friendId);

        // 3. 更新用户信息到数据库
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(myId)));
        Update update = new Update()
                .set("friendFenZu", friendFenZuMap)
                .set("friendBeiZhu", friendBeiZhuMap);
        mongoTemplate.findAndModify(query, update, User.class);
    }
}