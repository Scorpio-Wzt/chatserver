package com.zzw.chatserver.listen;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.zzw.chatserver.common.ConstValueEnum;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.filter.SensitiveFilter;
import com.zzw.chatserver.pojo.*;
import com.zzw.chatserver.pojo.vo.*;
import com.zzw.chatserver.service.*;
import com.zzw.chatserver.utils.DateUtil;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.util.*;

@Component
@Transactional(rollbackFor = Throwable.class)
public class SocketIoListener {
    private Logger logger = LoggerFactory.getLogger(SocketIoListener.class);

    @Resource
    private SocketIOServer socketIOServer;

    @Resource
    private GroupUserService groupUserService;

    @Resource
    private GoodFriendService goodFriendService;

    @Resource
    private ValidateMessageService validateMessageService;

    @Resource
    private GroupMessageService groupMessageService;

    @Resource
    private SingleMessageService singleMessageService;

    @Resource
    private UserService userService;

    @Resource
    private SensitiveFilter sensitiveFilter;

    @Resource
    private OnlineUserService onlineUserService;

    @Resource
    private SysService sysService;

    @OnDisconnect
    public void eventOnDisConnect(SocketIOClient client) {
        if (client == null) {
            logger.warn("eventOnDisConnect: 客户端为空，跳过处理");
            return;
        }
        logger.info("eventOnDisConnect ---> 客户端唯一标识为：{}", client.getSessionId());
        Map<String, List<String>> urlParams = client.getHandshakeData().getUrlParams();
        cleanLoginInfo(client.getSessionId().toString());
        logger.info("链接关闭，urlParams：{}", urlParams);
        socketIOServer.getBroadcastOperations().sendEvent("onlineUser", onlineUserService.getOnlineUidSet());
    }

    @OnConnect
    public void eventOnConnect(SocketIOClient client) {
        if (client == null) {
            logger.warn("eventOnConnect: 客户端为空，跳过处理");
            return;
        }
        Map<String, List<String>> urlParams = client.getHandshakeData().getUrlParams();
        // 正确获取URL参数中"uid"的第一个值（处理空指针）
        String uid = (urlParams != null && urlParams.containsKey("uid") && !urlParams.get("uid").isEmpty())
                ? urlParams.get("uid").get(0)
                : null;
        logger.info("客户端连接/重连，UID: {}", uid);

        // 若为重新连接，恢复用户在线状态和房间信息
        if (uid != null) {
            // 1. 恢复客户端与用户的绑定（复用现有方法）
            User user = userService.getUserInfo(uid);
            if (user == null) { // 增加用户不存在的校验
                logger.error("用户不存在，UID: {}", uid);
                return;
            }
            SimpleUser simpleUser = new SimpleUser();
            BeanUtils.copyProperties(user, simpleUser);
            onlineUserService.addClientIdToSimpleUser(client.getSessionId().toString(), simpleUser);

            // 2. 重新加入用户之前的房间（需从数据库查询用户参与的会话）
            List<String> roomIds = getRoomsByUid(uid); // 实现自定义方法
            if (roomIds != null && !roomIds.isEmpty()) {
                for (String roomId : roomIds) {
                    if (roomId != null) {
                        client.joinRoom(roomId);
                    }
                }
            }

            // 3. 推送重连成功通知
            client.sendEvent("reconnectSuccess", "重连成功");
        }
    }

    /**
     * 查询用户参与的所有房间ID（单聊+群聊）
     * @param uid 用户UID
     * @return 房间ID列表
     */
    private List<String> getRoomsByUid(String uid) {
        List<String> roomIds = new ArrayList<>();
        if (uid == null) {
            logger.warn("getRoomsByUid: uid为空，返回空列表");
            return roomIds;
        }

        // 1. 查询单聊房间（基于好友关系）
        List<MyFriendListResultVo> friends = goodFriendService.getMyFriendsList(uid);
        if (friends != null && !friends.isEmpty()) {
            for (MyFriendListResultVo friend : friends) {
                if (friend != null && !StringUtils.isEmpty(friend.getRoomId())) {
                    roomIds.add(friend.getRoomId());
                }
            }
        }

        // 2. 查询群聊房间（基于用户加入的群组）
        User user = userService.getUserInfo(uid);
        if (user != null && !StringUtils.isEmpty(user.getUsername())) {
            List<MyGroupResultVo> myGroups = groupUserService.getGroupUsersByUserName(user.getUsername());
            if (myGroups != null && !myGroups.isEmpty()) {
                for (MyGroupResultVo group : myGroups) {
                    if (group != null && !StringUtils.isEmpty(group.getGroupId())) {
                        roomIds.add(group.getGroupId());
                    }
                }
            }
        } else {
            logger.warn("getRoomsByUid: 用户信息不存在，无法查询群聊房间，uid={}", uid);
        }

        return roomIds;
    }

    private void cleanLoginInfo(String clientId) {
        if (clientId == null) {
            logger.warn("cleanLoginInfo: clientId为空，跳过处理");
            return;
        }
        SimpleUser simpleUser = onlineUserService.getSimpleUserByClientId(clientId);
        if (simpleUser != null) {
            onlineUserService.removeClientAndUidInSet(clientId, simpleUser.getUid());
            long onlineTime = DateUtil.getTimeDelta(simpleUser.getLastLoginTime(), new Date());
            userService.updateOnlineTime(onlineTime, simpleUser.getUid());
        }
        printMessage();
    }

    private void printMessage() {
        logger.info("当前在线用户人数为：{}", onlineUserService.countOnlineUser());
    }

    @OnEvent("goOnline")
    public void goOnline(SocketIOClient client, User user) {
        if (client == null || user == null || StringUtils.isEmpty(user.getUid())) {
            logger.warn("goOnline: 客户端或用户信息不完整，跳过处理");
            return;
        }
        logger.info("goOnline ---> user：{}", user);
        String clientId = client.getSessionId().toString();
        SimpleUser simpleUser = new SimpleUser();
        BeanUtils.copyProperties(user, simpleUser);

        onlineUserService.addClientIdToSimpleUser(clientId, simpleUser);
        printMessage();
        socketIOServer.getBroadcastOperations().sendEvent("onlineUser", onlineUserService.getOnlineUidSet());

        // 查询并推送离线消息
        String uid = user.getUid();
        logger.info("用户{}上线，开始推送离线消息", uid);

        // 2.1 推送单聊离线消息（接收者为当前用户，且未读）
        IsReadMessageRequestVo singleUnreadReq = new IsReadMessageRequestVo();
        singleUnreadReq.setUserId(uid);
        List<SingleMessageResultVo> singleOfflineMsgs = singleMessageService.getUnreadMessages(uid);
        if (singleOfflineMsgs != null && !singleOfflineMsgs.isEmpty()) {
            client.sendEvent("offlineSingleMessages", singleOfflineMsgs);
            logger.info("推送单聊离线消息{}条给用户{}", singleOfflineMsgs.size(), uid);
            // 标记为已读（可选：上线后自动标记离线消息为已读）
            singleMessageService.userIsReadMessage(singleUnreadReq);
        }

        // 2.2 推送群聊离线消息（用户所在群聊中，未读的消息）
        List<String> userGroupRooms = getRoomsByUid(uid);
        if (userGroupRooms != null && !userGroupRooms.isEmpty()) {
            for (String roomId : userGroupRooms) {
                if (roomId == null) continue;
                List<GroupMessageResultVo> groupOfflineMsgs = groupMessageService.getUnreadGroupMessages(roomId, uid);
                if (groupOfflineMsgs != null && !groupOfflineMsgs.isEmpty()) {
                    client.sendEvent("offlineGroupMessages_" + roomId, groupOfflineMsgs);
                    logger.info("推送群聊[{}]离线消息{}条给用户{}", roomId, groupOfflineMsgs.size(), uid);
                    // 标记为已读（可选）
                    groupMessageService.userIsReadGroupMessage(roomId, uid);
                }
            }
        }
    }

    @OnEvent("leave")
    public void leave(SocketIOClient client) {
        if (client == null) {
            logger.warn("leave: 客户端为空，跳过处理");
            return;
        }
        logger.info("leave ---> client：{}", client);
        cleanLoginInfo(client.getSessionId().toString());
        socketIOServer.getBroadcastOperations().sendEvent("onlineUser", onlineUserService.getOnlineUidSet());
    }

    @OnEvent("isReadMsg")
    public void isReadMsg(SocketIOClient client, UserIsReadMsgRequestVo requestVo) {
        if (client == null || requestVo == null || StringUtils.isEmpty(requestVo.getRoomId())) {
            logger.warn("isReadMsg: 客户端或请求参数不完整，跳过处理");
            return;
        }
        logger.info("isReadMsg ---> requestVo：{}", requestVo);
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(requestVo.getRoomId()).getClients();
        if (clients != null) {
            for (SocketIOClient item : clients) {
                if (item != null && !item.getSessionId().equals(client.getSessionId())) {
                    item.sendEvent("isReadMsg", requestVo);
                }
            }
        }
    }

    @OnEvent("join")
    public void join(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null || StringUtils.isEmpty(conversationVo.getRoomId())) {
            logger.warn("join: 客户端或房间信息不完整，跳过处理");
            return;
        }
        logger.info("加入房间号码：{} ---> conversationVo：{}", conversationVo.getRoomId(), conversationVo);
        client.joinRoom(conversationVo.getRoomId());
    }

    @OnEvent("sendNewMessage")
    public void sendNewMessage(SocketIOClient client, NewMessageVo newMessageVo) {
        if (client == null || newMessageVo == null
                || StringUtils.isEmpty(newMessageVo.getSenderId())
                || StringUtils.isEmpty(newMessageVo.getRoomId())) {
            logger.warn("sendNewMessage: 客户端或消息参数不完整，跳过处理");
            return;
        }
        logger.info("sendNewMessage ---> newMessageVo：{}", newMessageVo);

        // 敏感词过滤（所有消息类型都需要）
        String originalMsg = newMessageVo.getMessage();
        if (!StringUtils.isEmpty(originalMsg)) {
            String[] filteredResult = sensitiveFilter.filter(originalMsg);
            if (filteredResult != null) {
                newMessageVo.setMessage(filteredResult[0]); // 更新为过滤后的消息
                // 若包含敏感词，记录日志+保存敏感消息记录
                if ("1".equals(filteredResult[1])) {
                    SensitiveMessage sensitiveMsg = new SensitiveMessage();
                    sensitiveMsg.setRoomId(newMessageVo.getRoomId());
                    sensitiveMsg.setSenderId(newMessageVo.getSenderId());
                    sensitiveMsg.setSenderName(newMessageVo.getSenderName());
                    sensitiveMsg.setMessage(originalMsg); // 记录原内容
                    sensitiveMsg.setType(ConstValueEnum.MESSAGE);
                    sensitiveMsg.setTime(new Date());
                    sysService.addSensitiveMessage(sensitiveMsg);
                    logger.warn("普通消息包含敏感词：发送者={}, 原内容={}", newMessageVo.getSenderId(), originalMsg);
                }
            }
        }

        // 仅单聊(FRIEND)才进行关键字检测和服务卡片生成
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
            // 获取发送者角色
            User sender = userService.getUserInfo(newMessageVo.getSenderId());
            if (sender == null) {
                logger.error("sendNewMessage: 发送者不存在，senderId={}", newMessageVo.getSenderId());
                client.sendEvent("sendFailed", "发送者信息不存在");
                return;
            }
            boolean isCustomerService = UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(sender.getRole());

            // 单聊消息发送前，强制验证好友关系
            // 客服直接放行，买家需校验好友关系
            if (!isCustomerService) {
                String receiverId = newMessageVo.getReceiverId();
                if (StringUtils.isEmpty(receiverId)) {
                    logger.error("sendNewMessage: 单聊接收者ID为空");
                    client.sendEvent("sendFailed", "接收者信息不完整");
                    return;
                }
                boolean isFriend = goodFriendService.checkIsFriend(newMessageVo.getSenderId(), receiverId);
                if (!isFriend) {
                    logger.warn("非好友关系，拒绝发送单聊消息：sender={}, receiver={}",
                            newMessageVo.getSenderId(), receiverId);
                    client.sendEvent("sendFailed", "非好友关系，无法发送消息");
                    return;
                }
            }
            handleCardMessage(newMessageVo);
        } else {
            // 群聊时强制清空卡片相关字段，确保不会有残留
            newMessageVo.setCardType(null);
            newMessageVo.setCardOptions(null);
        }

        // 判断接收者在线状态（单聊/群聊区分处理）
        List<String> readUsers = new ArrayList<>();
        readUsers.add(newMessageVo.getSenderId()); // 发送者默认已读

        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
            // 单聊：接收者为明确的receiverId
            String receiverId = newMessageVo.getReceiverId();
            if (!StringUtils.isEmpty(receiverId)) {
                boolean isReceiverOnline = onlineUserService.checkCurUserIsOnline(receiverId);
                if (isReceiverOnline) {
                    readUsers.add(receiverId);
                }
            } else {
                logger.warn("sendNewMessage: 单聊消息接收者ID为空，无法判断在线状态");
            }
        }
        newMessageVo.setIsReadUser(readUsers);

        // 保存消息到数据库
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
            SingleMessage singleMessage = new SingleMessage();
            BeanUtils.copyProperties(newMessageVo, singleMessage);
            singleMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            singleMessage.setCardType(newMessageVo.getCardType());
            singleMessage.setCardOptions(newMessageVo.getCardOptions());
            singleMessage.setTime(new Date());
            singleMessage.setIsReadUser(newMessageVo.getIsReadUser());
            logger.info("待插入的单聊消息（含卡片）为：{}", singleMessage);
            singleMessageService.addNewSingleMessage(singleMessage);
        } else if (ConstValueEnum.GROUP.equals(newMessageVo.getConversationType())) {
            GroupMessage groupMessage = new GroupMessage();
            BeanUtils.copyProperties(newMessageVo, groupMessage);
            groupMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            groupMessage.setTime(new Date());
            groupMessage.setIsReadUser(newMessageVo.getIsReadUser());
            groupMessageService.addNewGroupMessage(groupMessage);
        }

        // 转发消息给房间内其他客户端
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(newMessageVo.getRoomId()).getClients();
        if (clients != null) {
            for (SocketIOClient item : clients) {
                if (item != null && !item.getSessionId().equals(client.getSessionId())) {
                    item.sendEvent("receiveMessage", newMessageVo);
                }
            }
        }
    }


    /**
     * 处理卡片消息生成，仅在单聊场景被调用
     */
    private void handleCardMessage(NewMessageVo newMessageVo) {
        if (newMessageVo == null) {
            logger.warn("handleCardMessage: 消息对象为空，跳过处理");
            return;
        }
        String message = newMessageVo.getMessage();
        if (StringUtils.isEmpty(message)) {
            return;
        }

        // 验证发送者与接收者是否为好友
        String senderId = newMessageVo.getSenderId();
        String receiverId = newMessageVo.getReceiverId();
        if (StringUtils.isEmpty(senderId) || StringUtils.isEmpty(receiverId)) {
            logger.warn("handleCardMessage: 发送者或接收者ID为空，无法生成卡片");
            return;
        }
        boolean isFriend = goodFriendService.checkIsFriend(senderId, receiverId);
        if (!isFriend) {
            logger.warn("非好友关系，拒绝生成服务卡片：sender={}, receiver={}", senderId, receiverId);
            return;
        }

        // 获取双方角色信息
        User sender = userService.getUserInfo(senderId);
        User receiver = userService.getUserInfo(receiverId);
        if (sender == null || receiver == null) {
            logger.warn("用户信息不存在，无法生成服务卡片：sender={}, receiver={}", senderId, receiverId);
            return;
        }

        // 转换角色枚举
        UserRoleEnum senderRole = UserRoleEnum.fromCode(sender.getRole());
        UserRoleEnum receiverRole = UserRoleEnum.fromCode(receiver.getRole());
        if (senderRole == null || receiverRole == null) {
            logger.warn("存在未知角色，不生成服务卡片：senderRole={}, receiverRole={}",
                    sender.getRole(), receiver.getRole());
            return;
        }

        // 验证是否为客服与用户的聊天
        boolean isCustomerServiceChat =
                (senderRole.isCustomerService() && receiverRole.isCustomer()) ||
                        (senderRole.isCustomer() && receiverRole.isCustomerService());
        if (!isCustomerServiceChat) {
            logger.info("非客服与用户聊天，不生成服务卡片：senderRole={}, receiverRole={}",
                    senderRole.getCode(), receiverRole.getCode());
            return;
        }

        // 检测关键字并生成卡片
        boolean hasRefund = message.contains("申请退款");
        boolean hasQuery = message.contains("查询订单");
        if (hasRefund || hasQuery) {
            newMessageVo.setCardType(ConstValueEnum.MESSAGE_TYPE_CARD);
            List<CardOptionVo> options = new ArrayList<>();
            if (hasRefund) {
                options.add(new CardOptionVo(
                        "申请退款",
                        "/chat/order/refund?userId=" + senderId + "&token={token}",
                        "POST"
                ));
            }
            if (hasQuery) {
                options.add(new CardOptionVo(
                        "查询订单",
                        "/chat/order/query?userId=" + senderId + "&token={token}",
                        "GET"
                ));
            }
            newMessageVo.setCardOptions(options);
            logger.info("生成服务卡片：sender={}, 关键字={}", senderId,
                    hasRefund ? "申请退款" : "查询订单");
        } else {
            newMessageVo.setCardType(null);
            newMessageVo.setCardOptions(null);
        }
    }

    @OnEvent("sendValidateMessage")
    public void sendValidateMessage(SocketIOClient client, ValidateMessage validateMessage) {
        if (client == null || validateMessage == null) {
            logger.warn("sendValidateMessage: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("sendValidateMessage ---> validateMessage：{}", validateMessage);
        String[] res = sensitiveFilter.filter(validateMessage.getAdditionMessage());
        String filterContent = "";
        if (res != null) {
            filterContent = res[0];
            if (res[1].equals("1")) {
                SensitiveMessage sensitiveMessage = new SensitiveMessage();
                sensitiveMessage.setRoomId(validateMessage.getRoomId());
                sensitiveMessage.setSenderId(validateMessage.getSenderId().toString());
                sensitiveMessage.setSenderName(validateMessage.getSenderName());
                sensitiveMessage.setMessage(validateMessage.getAdditionMessage());
                sensitiveMessage.setType(ConstValueEnum.VALIDATE);
                sensitiveMessage.setTime(new Date());
                sysService.addSensitiveMessage(sensitiveMessage);
            }
        }
        validateMessage.setAdditionMessage(filterContent);
        ValidateMessage addValidateMessage = validateMessageService.addValidateMessage(validateMessage);
        if (addValidateMessage != null) {
            Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(validateMessage.getRoomId()).getClients();
            for (SocketIOClient item : clients) {
                if (item != client) {
                    item.sendEvent("receiveValidateMessage", validateMessage);
                }
            }
        }
    }

    @OnEvent("sendAgreeFriendValidate")
    public void sendAgreeFriendValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        if (client == null || validateMessage == null) {
            logger.warn("sendAgreeFriendValidate: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("sendAgreeFriendValidate ---> validateMessage：{}", validateMessage);
        GoodFriend goodFriend = new GoodFriend();
        goodFriend.setUserM(new ObjectId(validateMessage.getSenderId()));
        goodFriend.setUserY(new ObjectId(validateMessage.getReceiverId()));
        goodFriendService.addFriend(goodFriend);

        validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 1);

        String roomId = validateMessage.getRoomId();
        String receiverId = validateMessage.getReceiverId();
        String senderId = validateMessage.getSenderId();
        String senderRoomId = roomId.replaceAll(receiverId, senderId);

        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(senderRoomId).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("receiveAgreeFriendValidate", validateMessage);
            }
        }
    }

    @OnEvent("sendDisAgreeFriendValidate")
    public void sendDisAgreeFriendValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        if (client == null || validateMessage == null) {
            logger.warn("sendDisAgreeFriendValidate: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("sendDisAgreeFriendValidate ---> validateMessage：{}", validateMessage);
        validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
    }

    @OnEvent("sendDelGoodFriend")
    public void sendDelGoodFriend(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null) {
            logger.warn("sendDelGoodFriend: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("sendDelGoodFriend ---> conversationVo：{}", conversationVo);
        String uid = onlineUserService.getSimpleUserByClientId(client.getSessionId().toString()).getUid();
        conversationVo.setId(uid);
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("receiveDelGoodFriend", conversationVo);
            }
        }
    }

    @OnEvent("sendAgreeGroupValidate")
    public void sendAgreeGroupValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        if (client == null || validateMessage == null) {
            logger.warn("sendAgreeGroupValidate: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("sendAgreeGroupValidate ---> validateMessage：{}", validateMessage);
        groupUserService.addNewGroupUser(validateMessage);
        validateMessageService.changeGroupValidateNewsStatus(validateMessage.getId(), 1);

        String roomId = validateMessage.getRoomId();
        String receiverId = validateMessage.getReceiverId();
        String senderId = validateMessage.getSenderId();
        String senderRoomId = roomId.replaceAll(receiverId, senderId);

        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(senderRoomId).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("receiveAgreeGroupValidate", validateMessage);
            }
        }
    }

    @OnEvent("sendDisAgreeGroupValidate")
    public void sendDisAgreeGroupValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        if (client == null || validateMessage == null) {
            logger.warn("sendDisAgreeGroupValidate: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("sendDisAgreeGroupValidate ---> validateMessage：{}", validateMessage);
        validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
    }

    @OnEvent("sendQuitGroup")
    public void sendQuitGroup(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null) {
            logger.warn("sendQuitGroup: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("sendQuitGroup ---> conversationVo：{}", conversationVo);
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("receiveQuitGroup", conversationVo);
            }
        }
    }

    @OnEvent("apply")
    public void apply(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null) {
            logger.warn("apply: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("apply ---> roomId：{}", conversationVo.getRoomId());
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("apply", conversationVo);
            }
        }
    }

    @OnEvent("reply")
    public void reply(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null) {
            logger.warn("reply: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("reply ---> roomId：{}", conversationVo.getRoomId());
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("reply", conversationVo);
            }
        }
    }

    @OnEvent("1v1answer")
    public void answer(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null) {
            logger.warn("1v1answer: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("1v1answer ---> roomId：{}", conversationVo.getRoomId());
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("1v1answer", conversationVo);
            }
        }
    }

    @OnEvent("1v1ICE")
    public void ICE(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null) {
            logger.warn("1v1ICE: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("1v1ICE ---> roomId：{}", conversationVo.getRoomId());
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("1v1ICE", conversationVo);
            }
        }
    }

    @OnEvent("1v1offer")
    public void offer(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null) {
            logger.warn("1v1offer: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("1v1offer ---> roomId：{}", conversationVo.getRoomId());
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("1v1offer", conversationVo);
            }
        }
    }

    @OnEvent("1v1hangup")
    public void hangup(SocketIOClient client, CurrentConversationVo conversationVo) {
        if (client == null || conversationVo == null) {
            logger.warn("1v1hangup: 客户端或消息为空，跳过处理");
            return;
        }
        logger.info("1v1hangup ---> roomId：{}", conversationVo.getRoomId());
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("1v1hangup", conversationVo);
            }
        }
    }
}
