package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.dao.GoodFriendDao;
import com.zzw.chatserver.dao.UserDao;
import com.zzw.chatserver.pojo.GoodFriend;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.DelGoodFriendRequestVo;
import com.zzw.chatserver.pojo.vo.MyFriendListResultVo;
import com.zzw.chatserver.pojo.vo.MyFriendListVo;
import com.zzw.chatserver.pojo.vo.RecentConversationVo;
import com.zzw.chatserver.pojo.vo.SingleRecentConversationResultVo;
import com.zzw.chatserver.pojo.vo.SimpleUser;
import com.zzw.chatserver.service.GoodFriendService;
import com.zzw.chatserver.service.UserService;
import com.zzw.chatserver.utils.DateUtil;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 好友服务实现类
 * 实现GoodFriendService接口定义的好友管理逻辑，依赖DAO层和MongoTemplate完成数据操作
 */
@Service
public class GoodFriendServiceImpl implements GoodFriendService {

    @Resource
    private GoodFriendDao goodFriendDao;

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private UserDao userDao;

    @Resource
    private UserService userService;

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
        return resList;
    }

    /**
     * 获取当前用户的最近好友会话列表
     * 聚合查询符合条件的好友关系，返回包含会话双方信息的列表
     */
    @Override
    public List<SingleRecentConversationResultVo> getRecentConversation(RecentConversationVo recentConversationVo) {
        // 转换好友ID列表为ObjectId格式
        List<ObjectId> friendIds = new ArrayList<>();
        for (String son : recentConversationVo.getRecentFriendIds()) {
            friendIds.add(new ObjectId(son));
        }

        // 构建查询条件：当前用户与最近好友的双向关系
        Criteria criteriaA = Criteria.where("userM").in(friendIds).and("userY").is(new ObjectId(recentConversationVo.getUserId()));
        Criteria criteriaB = Criteria.where("userY").in(friendIds).and("userM").is(new ObjectId(recentConversationVo.getUserId()));
        Criteria criteria = new Criteria().orOperator(criteriaA, criteriaB);

        // 聚合查询：关联用户表获取双方信息
        Aggregation aggregation = Aggregation.newAggregation(
                Aggregation.match(criteria),
                Aggregation.lookup("users", "userY", "_id", "uList1"),
                Aggregation.lookup("users", "userM", "_id", "uList2")
        );
        List<MyFriendListVo> friendlies = mongoTemplate.aggregate(aggregation, "goodfriends", MyFriendListVo.class).getMappedResults();

        // 转换为最近会话VO列表
        List<SingleRecentConversationResultVo> resultVoList = new ArrayList<>();
        for (MyFriendListVo son : friendlies) {
            SingleRecentConversationResultVo item = new SingleRecentConversationResultVo();
            // 格式化创建时间
            item.setCreateDate(DateUtil.format(son.getCreateDate(), DateUtil.yyyy_MM_dd_HH_mm_ss));
            item.setId(son.getId());

            // 构建会话双方用户信息（保持好友关系原始顺序）
            SimpleUser userM = new SimpleUser();
            SimpleUser userY = new SimpleUser();
            if (son.getUList1().get(0).getUid().equals(son.getUserM())) {
                BeanUtils.copyProperties(son.getUList1().get(0), userM);
                BeanUtils.copyProperties(son.getUList2().get(0), userY);
            } else {
                BeanUtils.copyProperties(son.getUList1().get(0), userY);
                BeanUtils.copyProperties(son.getUList2().get(0), userM);
            }

            // 设置用户等级并添加到结果列表
            item.setUserM(userM);
            item.setUserY(userY);
            item.getUserM().setLevel(computedLevel(son.getUList1().get(0).getOnlineTime()));
            item.getUserY().setLevel(computedLevel(son.getUList2().get(0).getOnlineTime()));
            resultVoList.add(item);
        }
        return resultVoList;
    }

    /**
     * 添加好友
     * 校验好友关系是否已存在，不存在则保存，并将双方添加到"我的好友"分组
     */
    @Override
    public void addFriend(GoodFriend goodFriend) {
        // 校验好友关系是否已存在
        Query query = Query.query(
                Criteria.where("userM").is(goodFriend.getUserM())
                        .and("userY").is(goodFriend.getUserY())
        );
        GoodFriend existingFriend = mongoTemplate.findOne(query, GoodFriend.class);

        // 不存在则保存，并更新双方分组
        if (existingFriend == null) {
            goodFriendDao.save(goodFriend);
            // 双向添加到"我的好友"分组
            modifyNewUserFenZu(goodFriend.getUserM().toString(), goodFriend.getUserY().toString());
            modifyNewUserFenZu(goodFriend.getUserY().toString(), goodFriend.getUserM().toString());
        }
    }

    /**
     * 删除好友
     *  删除好友关系、双方单聊记录、双方好友分组及备注信息
     */
    @Override
    public void deleteFriend(DelGoodFriendRequestVo requestVo) {
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
            User friendUser = son.getUList().get(0);
            // 设置好友基本信息
            item.setCreateDate(son.getCreateDate());
            item.setNickname(friendUser.getNickname());
            item.setPhoto(friendUser.getPhoto());
            item.setSignature(friendUser.getSignature());
            item.setId(friendUser.getUserId().toString());
            // 计算并设置好友等级
            item.setLevel(computedLevel(friendUser.getOnlineTime()));
            // 生成单聊房间ID（按ID字典序排序，避免重复）
            String friendUid = friendUser.getUserId().toString();
            item.setRoomId(isInitiator ? currentUserId + "-" + friendUid : friendUid + "-" + currentUserId);
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