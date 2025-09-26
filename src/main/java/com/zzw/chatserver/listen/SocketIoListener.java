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
import com.zzw.chatserver.utils.ValidationUtil;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SocketIO事件监听器
 * 处理客户端连接、断开、消息发送等事件，负责消息转发、离线消息推送、在线状态管理等
 */
@Component
@Transactional(rollbackFor = Throwable.class)
public class SocketIoListener {
    private static final Logger logger = LoggerFactory.getLogger(SocketIoListener.class);

    // 同步锁，保证在线用户操作的线程安全
    private final ReentrantLock onlineUserLock = new ReentrantLock();

    // 事件名称常量
    private static final String EVENT_ONLINE_USER = "onlineUser";
    private static final String EVENT_RECONNECT_SUCCESS = "reconnectSuccess";
    private static final String EVENT_OFFLINE_SINGLE_MSG = "offlineSingleMessages";
    private static final String EVENT_OFFLINE_GROUP_MSG_PREFIX = "offlineGroupMessages_";
    private static final String EVENT_SEND_FAILED = "sendFailed";
    private static final String EVENT_RECEIVE_MSG = "receiveMessage";
    private static final String EVENT_JOIN_FAILED = "joinFailed";
    private static final String EVENT_IS_READ_MSG = "isReadMsg";
    private static final String EVENT_RECEIVE_VALIDATE_MSG = "receiveValidateMessage";
    private static final String EVENT_RECEIVE_AGREE_FRIEND = "receiveAgreeFriendValidate";
    private static final String EVENT_RECEIVE_AGREE_GROUP = "receiveAgreeGroupValidate";
    private static final String EVENT_RECEIVE_DEL_FRIEND = "receiveDelGoodFriend";
    private static final String EVENT_RECEIVE_QUIT_GROUP = "receiveQuitGroup";
    private static final String EVENT_CONFIRM_RECEIVE = "confirmReceive";

    // 错误信息常量
    private static final String ERR_INVALID_ROOM_ID = "房间ID格式错误";
    private static final String ERR_INVALID_SENDER_ID = "发送者ID格式错误";
    private static final String ERR_INVALID_RECEIVER_ID = "接收者ID格式错误";
    private static final String ERR_IDENTITY_VERIFY_FAILED = "身份验证失败";
    private static final String ERR_SENDER_NOT_EXIST = "发送者信息不存在";
    private static final String ERR_PARAM_INCOMPLETE = "参数不完整";
    private static final String ERR_NOT_FRIEND = "非好友关系，无法发送消息";
    private static final String ERR_SERVER_EXCEPTION = "服务器异常，请稍后重试";

    // 心跳和过期时间常量
    private static final long HEARTBEAT_EXPIRATION_MS = 3600000; // 1小时过期
    private static final long HEARTBEAT_INTERVAL_MS = 1800000; // 30分钟心跳间隔

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

    /**
     * 定时清理过期的客户端绑定（每小时执行一次）
     */
    @Scheduled(fixedRate = 3600000)
    public void cleanExpiredClients() {
        try {
            onlineUserLock.lock();
            int cleanedCount = onlineUserService.cleanExpiredClients(HEARTBEAT_EXPIRATION_MS);
            logger.info("定时清理过期客户端完成，清理数量：{}", cleanedCount);
            if (cleanedCount > 0) {
                broadcastOnlineUser();
            }
        } catch (Exception e) {
            logger.error("定时清理过期客户端失败", e);
        } finally {
            onlineUserLock.unlock();
        }
    }

    /**
     * 客户端断开连接事件
     * 清理用户在线状态，更新在线时长，广播在线用户变化
     */
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        if (client == null) {
            logger.warn("客户端断开连接：客户端为空，跳过处理");
            return;
        }

        try {
            String clientId = client.getSessionId().toString();
            logger.info("客户端断开连接，clientId: {}", clientId);
            cleanLoginInfo(clientId);
            logger.info("连接关闭，url参数: {}", client.getHandshakeData().getUrlParams());
            broadcastOnlineUser();
        } catch (Exception e) {
            logger.error("处理客户端断开连接异常", e);
        }
    }

    /**
     * 客户端连接/重连事件
     * 恢复用户在线状态和房间信息，推送重连成功通知
     */
    @OnConnect
    public void onConnect(SocketIOClient client) {
        if (client == null) {
            logger.warn("客户端连接：客户端为空，跳过处理");
            return;
        }

        try {
            // 提取URL参数中的uid
            String uid = extractUidFromParams(client.getHandshakeData().getUrlParams());
            logger.info("客户端连接/重连，UID: {}", uid);

            if (uid != null) {
                // 校验用户存在性
                User user = userService.getUserInfo(uid);
                if (user == null) {
                    logger.error("用户不存在，UID: {}", uid);
                    return;
                }
                // 绑定客户端与用户
                SimpleUser simpleUser = new SimpleUser();
                BeanUtils.copyProperties(user, simpleUser);

                onlineUserLock.lock();
                try {
                    onlineUserService.addClientIdToSimpleUser(client.getSessionId().toString(), simpleUser);
                } finally {
                    onlineUserLock.unlock();
                }

                // 重新加入历史房间
                joinUserRooms(client, uid);

                // 推送重连成功通知
                client.sendEvent(EVENT_RECONNECT_SUCCESS, "重连成功");
            }
        } catch (Exception e) {
            logger.error("处理客户端连接异常", e);
        }
    }

    /**
     * 提取URL参数中的uid
     */
    private String extractUidFromParams(Map<String, List<String>> urlParams) {
        if (urlParams == null || !urlParams.containsKey("uid") || urlParams.get("uid").isEmpty()) {
            return null;
        }
        return urlParams.get("uid").get(0);
    }

    /**
     * 让客户端加入用户参与的所有房间（单聊+群聊）
     */
    private void joinUserRooms(SocketIOClient client, String uid) {
        List<String> roomIds = getRoomsByUid(uid);
        if (roomIds == null || roomIds.isEmpty()) {
            logger.info("用户{}无历史房间，无需重新加入", uid);
            return;
        }
        for (String roomId : roomIds) {
            if (roomId != null) {
                client.joinRoom(roomId);
                logger.info("用户{}重新加入房间：{}", uid, roomId);
            }
        }
    }

    /**
     * 查询用户参与的所有房间ID（单聊+群聊）
     */
    private List<String> getRoomsByUid(String uid) {
        List<String> roomIds = new ArrayList<>();
        if (uid == null) {
            logger.warn("查询房间：uid为空，返回空列表");
            return roomIds;
        }

        try {
            // 单聊房间（基于好友关系）
            List<MyFriendListResultVo> friends = goodFriendService.getMyFriendsList(uid);
            if (friends != null && !friends.isEmpty()) {
                for (MyFriendListResultVo friend : friends) {
                    if (friend != null && !StringUtils.isEmpty(friend.getRoomId())) {
                        roomIds.add(friend.getRoomId());
                    }
                }
            }

            // 群聊房间（基于用户加入的群组）
            User user = userService.getUserInfo(uid);
            if (user != null && !StringUtils.isEmpty(user.getUsername())) {
                List<MyGroupResultVo> myGroups = groupUserService.getGroupUsersByUserName(user.getUsername());
                if (myGroups != null && !myGroups.isEmpty()) {
                    for (MyGroupResultVo group : myGroups) {
                        if (group != null && group.getGroupId() != null) {
                            // 显式转换为字符串，避免ObjectId直接toString的问题
                            roomIds.add(group.getGroupId().toString());
                        }
                    }
                }
            } else {
                logger.warn("查询群聊房间：用户信息不存在，uid={}", uid);
            }
        } catch (Exception e) {
            logger.error("查询用户房间列表异常，uid={}", uid, e);
        }

        return roomIds;
    }

    /**
     * 清理用户登录信息（在线状态、更新在线时长）
     */
    private void cleanLoginInfo(String clientId) {
        if (clientId == null) {
            logger.warn("清理登录信息：clientId为空，跳过处理");
            return;
        }

        try {
            onlineUserLock.lock();
            try {
                SimpleUser simpleUser = onlineUserService.getSimpleUserByClientId(clientId);
                if (simpleUser != null) {
                    onlineUserService.removeClientAndUidInSet(clientId, simpleUser.getUid());
                    long onlineTime = DateUtil.getTimeDelta(Date.from(Instant.parse(simpleUser.getLastLoginTime())), new Date());
                    userService.updateOnlineTime(onlineTime, simpleUser.getUid());
                }
            } finally {
                onlineUserLock.unlock();
            }
            printOnlineUserCount();
        } catch (Exception e) {
            logger.error("清理用户登录信息异常，clientId={}", clientId, e);
        }
    }

    /**
     * 打印当前在线用户数
     */
    private void printOnlineUserCount() {
        try {
            logger.info("当前在线用户人数：{}", onlineUserService.countOnlineUser());
        } catch (Exception e) {
            logger.error("获取在线用户数异常", e);
        }
    }

    /**
     * 广播在线用户列表
     */
    private void broadcastOnlineUser() {
        try {
            socketIOServer.getBroadcastOperations().sendEvent(EVENT_ONLINE_USER, onlineUserService.getOnlineUidSet());
        } catch (Exception e) {
            logger.error("广播在线用户列表异常", e);
        }
    }

    /**
     * 用户上线事件
     * 处理用户在线状态绑定、离线消息推送
     */
    @OnEvent("goOnline")
    public void goOnline(SocketIOClient client, User user) {
        try {
            // 参数校验
            if (!validateClientAndUser(client, user)) {
                logger.warn("用户上线：客户端或用户信息不完整");
                return;
            }
            String clientId = client.getSessionId().toString();
            String uid = user.getUid();
            logger.info("用户上线，user：{}", user);

            // 处理重连时的旧客户端清理
            cleanOldClientBinding(uid, clientId);

            // 绑定新的客户端与用户
            SimpleUser simpleUser = new SimpleUser();
            BeanUtils.copyProperties(user, simpleUser);

            onlineUserLock.lock();
            try {
                onlineUserService.addClientIdToSimpleUser(clientId, simpleUser);
            } finally {
                onlineUserLock.unlock();
            }

            printOnlineUserCount();
            broadcastOnlineUser();

            // 推送离线消息（不立即标记为已读，等待客户端确认）
            pushOfflineMessages(client, uid);
        } catch (Exception e) {
            logger.error("处理用户上线事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 处理客户端消息接收确认
     */
    @OnEvent(EVENT_CONFIRM_RECEIVE)
    public void confirmReceive(SocketIOClient client, MessageConfirmVo confirmVo) {
        try {
            if (client == null || confirmVo == null || StringUtils.isEmpty(confirmVo.getUserId())) {
                logger.warn("消息确认：客户端或确认信息不完整");
                return;
            }

            logger.info("收到消息确认，userId={}, singleMessageIds={}, groupMessageIds={}",
                    confirmVo.getUserId(), confirmVo.getSingleMessageIds(), confirmVo.getGroupMessageIds());

            // 标记单聊消息为已读
            if (confirmVo.getSingleMessageIds() != null && !confirmVo.getSingleMessageIds().isEmpty()) {
                singleMessageService.markMessagesAsRead(confirmVo.getUserId(), confirmVo.getSingleMessageIds());
            }

            // 标记群聊消息为已读
            if (confirmVo.getGroupMessageIds() != null && !confirmVo.getGroupMessageIds().isEmpty()) {
                for (Map.Entry<String, List<String>> entry : confirmVo.getGroupMessageIds().entrySet()) {
                    String roomId = entry.getKey();
                    List<String> messageIds = entry.getValue();
                    if (!StringUtils.isEmpty(roomId) && messageIds != null && !messageIds.isEmpty()) {
                        groupMessageService.markGroupMessagesAsRead(roomId, confirmVo.getUserId(), messageIds);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("处理消息确认异常", e);
        }
    }

    /**
     * 校验客户端和用户信息是否完整
     */
    private boolean validateClientAndUser(SocketIOClient client, User user) {
        return client != null && user != null && !StringUtils.isEmpty(user.getUid());
    }

    /**
     * 清理用户旧的客户端绑定（重连场景）
     */
    private void cleanOldClientBinding(String uid, String newClientId) {
        try {
            onlineUserLock.lock();
            try {
                String oldClientId = onlineUserService.getClientIdByUid(uid);
                if (oldClientId != null && !oldClientId.equals(newClientId)) {
                    onlineUserService.removeClientAndUidInSet(oldClientId, uid);
                    logger.info("清理用户[{}]的旧客户端绑定：{}", uid, oldClientId);
                }
            } finally {
                onlineUserLock.unlock();
            }
        } catch (Exception e) {
            logger.error("清理用户旧客户端绑定异常，uid={}", uid, e);
        }
    }

    /**
     * 推送离线消息（单聊+群聊）
     */
    private void pushOfflineMessages(SocketIOClient client, String uid) {
        try {
            logger.info("用户{}上线，开始推送离线消息", uid);

            // 推送单聊离线消息
            pushOfflineSingleMessages(client, uid);

            // 推送群聊离线消息
            pushOfflineGroupMessages(client, uid);
        } catch (Exception e) {
            logger.error("推送离线消息异常，uid={}", uid, e);
        }
    }

    /**
     * 推送单聊离线消息（不立即标记为已读）
     */
    private void pushOfflineSingleMessages(SocketIOClient client, String uid) {
        try {
            List<SingleMessageResultVo> offlineMsgs = singleMessageService.getUnreadMessages(uid);
            if (offlineMsgs != null && !offlineMsgs.isEmpty()) {
                client.sendEvent(EVENT_OFFLINE_SINGLE_MSG, offlineMsgs);
                logger.info("推送单聊离线消息{}条给用户{}", offlineMsgs.size(), uid);
                // 不立即标记为已读，等待客户端确认
            }
        } catch (Exception e) {
            logger.error("推送单聊离线消息异常，uid={}", uid, e);
        }
    }

    /**
     * 推送群聊离线消息（不立即标记为已读）
     */
    private void pushOfflineGroupMessages(SocketIOClient client, String uid) {
        try {
            List<String> userGroupRooms = getRoomsByUid(uid);
            if (userGroupRooms == null || userGroupRooms.isEmpty()) {
                return;
            }
            for (String roomId : userGroupRooms) {
                if (roomId == null) continue;
                List<GroupMessageResultVo> offlineMsgs = groupMessageService.getUnreadGroupMessages(roomId, uid);
                if (offlineMsgs != null && !offlineMsgs.isEmpty()) {
                    client.sendEvent(EVENT_OFFLINE_GROUP_MSG_PREFIX + roomId, offlineMsgs);
                    logger.info("推送群聊[{}]离线消息{}条给用户{}", roomId, offlineMsgs.size(), uid);
                    // 不立即标记为已读，等待客户端确认
                }
            }
        } catch (Exception e) {
            logger.error("推送群聊离线消息异常，uid={}", uid, e);
        }
    }

    /**
     * 客户端心跳事件
     * 延长用户在线状态过期时间
     */
    @OnEvent("heartbeat")
    public void handleHeartbeat(SocketIOClient client) {
        try {
            String clientId = client.getSessionId().toString();
            SimpleUser user = onlineUserService.getSimpleUserByClientId(clientId);
            if (user != null) {
                onlineUserLock.lock();
                try {
                    onlineUserService.renewExpiration(clientId, user.getUid(), HEARTBEAT_EXPIRATION_MS);
                } finally {
                    onlineUserLock.unlock();
                }
                logger.debug("客户端[{}]心跳续期成功，用户：{}，过期时间：{}ms",
                        clientId, user.getUid(), HEARTBEAT_EXPIRATION_MS);
            } else {
                logger.warn("客户端[{}]心跳验证失败：未找到绑定用户", clientId);
            }
        } catch (Exception e) {
            logger.error("处理客户端心跳异常", e);
        }
    }

    /**
     * 用户主动离开事件
     * 清理在线状态，广播在线用户变化
     */
    @OnEvent("leave")
    public void leave(SocketIOClient client) {
        try {
            if (client == null) {
                logger.warn("用户离开：客户端为空，跳过处理");
                return;
            }
            logger.info("用户离开，clientId：{}", client.getSessionId());
            cleanLoginInfo(client.getSessionId().toString());
            broadcastOnlineUser();
        } catch (Exception e) {
            logger.error("处理用户离开事件异常", e);
        }
    }

    /**
     * 消息已读事件
     * 通知房间内其他客户端消息已读状态
     */
    @OnEvent("isReadMsg")
    public void isReadMsg(SocketIOClient client, UserIsReadMsgRequestVo requestVo) {
        try {
            // 参数校验
            if (!validateClientAndRequest(client, requestVo) || StringUtils.isEmpty(requestVo.getRoomId())) {
                logger.warn("消息已读标记：客户端或请求参数不完整");
                return;
            }
            // 房间ID格式校验
            if (!ValidationUtil.isValidRoomId(requestVo.getRoomId())) {
                logger.error("消息已读标记失败：房间ID格式非法，roomId={}", requestVo.getRoomId());
                return;
            }
            logger.info("消息已读标记，requestVo：{}", requestVo);

            // 发送给房间内其他客户端
            sendToOtherClients(client, requestVo.getRoomId(), EVENT_IS_READ_MSG, requestVo);
        } catch (Exception e) {
            logger.error("处理消息已读事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 加入房间事件
     */
    @OnEvent("join")
    public void join(SocketIOClient client, CurrentConversationVo conversationVo) {
        try {
            if (conversationVo == null || StringUtils.isEmpty(conversationVo.getRoomId())) {
                logger.warn("加入房间：房间ID为空");
                client.sendEvent(EVENT_JOIN_FAILED, ERR_PARAM_INCOMPLETE);
                return;
            }
            String roomId = conversationVo.getRoomId();
            // 房间ID格式校验
            if (!ValidationUtil.isValidRoomId(roomId)) {
                logger.error("加入房间失败：房间ID格式非法，roomId={}", roomId);
                client.sendEvent(EVENT_JOIN_FAILED, ERR_INVALID_ROOM_ID);
                return;
            }
            logger.info("加入房间，roomId：{}，conversationVo：{}", roomId, conversationVo);
            client.joinRoom(roomId);
        } catch (Exception e) {
            logger.error("处理加入房间事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_JOIN_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 发送新消息事件
     * 处理消息验证、敏感词过滤、卡片生成、消息存储及转发
     */
    @OnEvent("sendNewMessage")
    public void sendNewMessage(SocketIOClient client, NewMessageVo newMessageVo) {
        try {
            // 基础参数校验
            if (!validateNewMessageParams(client, newMessageVo)) {
                return;
            }
            String senderId = newMessageVo.getSenderId();
            String roomId = newMessageVo.getRoomId();
            logger.info("处理新消息，senderId={}, roomId={}", senderId, roomId);

            // 身份验证（防止会话劫持）
//            if (!validateSenderIdentity(senderId)) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_IDENTITY_VERIFY_FAILED);
//                return;
//            }

            // 消息安全处理（XSS防御+敏感词过滤）
            processMessageSecurity(newMessageVo);

            // 单聊/群聊特殊处理
            if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
                // 单聊：验证好友关系+生成服务卡片
                if (!processSingleChatSpecial(newMessageVo, client)) {
                    return;
                }
            } else {
                // 群聊：清空卡片相关字段
                clearCardInfo(newMessageVo);
            }

            // 处理已读用户列表（发送者默认已读+在线接收者）
            handleReadUsers(newMessageVo);

            // 保存消息到数据库
            saveMessageToDb(newMessageVo);

            // 转发消息给房间内其他客户端
            sendToOtherClients(client, roomId, EVENT_RECEIVE_MSG, newMessageVo);
        } catch (Exception e) {
            logger.error("处理发送新消息事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 校验新消息参数合法性
     */
    private boolean validateNewMessageParams(SocketIOClient client, NewMessageVo newMessageVo) {
        if (!validateClientAndRequest(client, newMessageVo)
                || StringUtils.isEmpty(newMessageVo.getSenderId())
                || StringUtils.isEmpty(newMessageVo.getRoomId())) {
            logger.warn("发送消息：客户端或参数不完整");
            client.sendEvent(EVENT_SEND_FAILED, ERR_PARAM_INCOMPLETE);
            return false;
        }
        // 发送者ID格式校验
        if (!ValidationUtil.isValidObjectId(newMessageVo.getSenderId())) {
            logger.error("发送消息失败：发送者ID格式非法，senderId={}", newMessageVo.getSenderId());
            client.sendEvent(EVENT_SEND_FAILED, ERR_INVALID_SENDER_ID);
            return false;
        }
        // 房间ID格式校验
        if (!ValidationUtil.isValidRoomId(newMessageVo.getRoomId())) {
            logger.error("发送消息失败：房间ID格式非法，roomId={}", newMessageVo.getRoomId());
            client.sendEvent(EVENT_SEND_FAILED, ERR_INVALID_ROOM_ID);
            return false;
        }
        // 单聊接收者ID校验
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())
                && !ValidationUtil.isValidObjectId(newMessageVo.getReceiverId())) {
            logger.error("发送消息失败：接收者ID格式非法，receiverId={}", newMessageVo.getReceiverId());
            client.sendEvent(EVENT_SEND_FAILED, ERR_INVALID_RECEIVER_ID);
            return false;
        }
        return true;
    }

    /**
     * 验证发送者身份（与SecurityContext中的身份匹配）
     */
    private boolean validateSenderIdentity(String senderId) {
        // 获取认证信息前添加空值检查
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            logger.error("身份验证失败：未找到有效的认证信息");
            return false;
        }

        String actualUserId = authentication.getName();
        // 进一步检查用户名是否为空
        if (actualUserId == null) {
            logger.error("身份验证失败：认证信息中的用户名为空");
            return false;
        }

        System.out.println("actualUserId：" + actualUserId);

        if (!actualUserId.equals(senderId)) {
            logger.warn("会话劫持风险：{} 尝试伪造发送者 {}", actualUserId, senderId);
            return false;
        }
        return true;
    }

    /**
     * 消息安全处理（XSS防御+敏感词过滤）
     */
    private void processMessageSecurity(NewMessageVo newMessageVo) {
        String originalMsg = newMessageVo.getMessage();
        if (StringUtils.isEmpty(originalMsg)) {
            return;
        }
        // ========== 判断消息类型，图片类型跳过敏感词过滤 ==========
        if ("img".equals(newMessageVo.getMessageType())) {
            // 图片消息仍需XSS防御（防止URL包含恶意脚本），但跳过敏感词过滤
            String escapedMsg = HtmlUtils.htmlEscape(originalMsg);
            newMessageVo.setMessage(escapedMsg);
            return;
        }
        // XSS防御：HTML特殊字符转义
        String escapedMsg = HtmlUtils.htmlEscape(originalMsg);
        newMessageVo.setMessage(escapedMsg);

        // 敏感词过滤，处理返回null的情况
        String[] filteredResult = sensitiveFilter.filter(originalMsg);
        // 过滤结果为null时，默认保留原始消息
        if (filteredResult == null) {
            filteredResult = new String[]{originalMsg, "0"};
            logger.warn("敏感词过滤返回null，使用原始消息，senderId={}", newMessageVo.getSenderId());
        }

        newMessageVo.setMessage(filteredResult[0]);
        // 记录敏感消息
        if ("1".equals(filteredResult[1])) {
            try {
                SensitiveMessage sensitiveMsg = new SensitiveMessage();
                sensitiveMsg.setRoomId(newMessageVo.getRoomId());
                sensitiveMsg.setSenderId(newMessageVo.getSenderId());
                sensitiveMsg.setSenderName(newMessageVo.getSenderName());
                sensitiveMsg.setMessage(originalMsg);
                sensitiveMsg.setType(ConstValueEnum.MESSAGE);
                sensitiveMsg.setTime(String.valueOf(Instant.now()));
                sysService.addSensitiveMessage(sensitiveMsg);
                logger.warn("消息包含敏感词：发送者={}, 原内容={}", newMessageVo.getSenderId(), originalMsg);
            } catch (Exception e) {
                logger.error("记录敏感消息异常", e);
                // 记录敏感消息失败不影响主流程
            }
        }
    }

    /**
     * 单聊特殊处理（好友关系验证+服务卡片生成）
     */
    private boolean processSingleChatSpecial(NewMessageVo newMessageVo, SocketIOClient client) {
        String senderId = newMessageVo.getSenderId();
        String receiverId = newMessageVo.getReceiverId();

        try {
            // 校验发送者存在性
            User sender = userService.getUserInfo(senderId);
            if (sender == null) {
                logger.error("发送者不存在，senderId={}", senderId);
                client.sendEvent(EVENT_SEND_FAILED, ERR_SENDER_NOT_EXIST);
                return false;
            }

            // 非客服需验证好友关系
            boolean isCustomerService = UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(sender.getRole());
            if (!isCustomerService) {
                if (StringUtils.isEmpty(receiverId)) {
                    logger.error("单聊接收者ID为空");
                    client.sendEvent(EVENT_SEND_FAILED, ERR_PARAM_INCOMPLETE);
                    return false;
                }
                if (!goodFriendService.checkIsFriend(senderId, receiverId)) {
                    logger.warn("非好友关系，拒绝发送单聊消息：sender={}, receiver={}", senderId, receiverId);
                    client.sendEvent(EVENT_SEND_FAILED, ERR_NOT_FRIEND);
                    return false;
                }
            }

            // 生成服务卡片
            handleCardMessage(newMessageVo);
            return true;
        } catch (Exception e) {
            logger.error("处理单聊消息特殊逻辑异常，senderId={}, receiverId={}", senderId, receiverId, e);
            client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            return false;
        }
    }

    /**
     * 清空卡片信息（群聊场景）
     */
    private void clearCardInfo(NewMessageVo newMessageVo) {
        newMessageVo.setCardType(null);
        newMessageVo.setCardOptions(null);
    }

    /**
     * 处理已读用户列表
     */
    private void handleReadUsers(NewMessageVo newMessageVo) {
        List<String> readUsers = new ArrayList<>();
        readUsers.add(newMessageVo.getSenderId()); // 发送者默认已读

        // 单聊：判断接收者在线状态
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())
                && !StringUtils.isEmpty(newMessageVo.getReceiverId())) {
            try {
                boolean isReceiverOnline = onlineUserService.checkCurUserIsOnline(newMessageVo.getReceiverId());
                if (isReceiverOnline) {
                    readUsers.add(newMessageVo.getReceiverId());
                }
            } catch (Exception e) {
                logger.error("判断接收者在线状态异常，receiverId={}", newMessageVo.getReceiverId(), e);
                // 异常时不添加接收者到已读列表
            }
        }
        newMessageVo.setIsReadUser(readUsers);
    }

    /**
     * 保存消息到数据库（单聊/群聊区分处理）
     */
    private void saveMessageToDb(NewMessageVo newMessageVo) {
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
            // 保存单聊消息
            SingleMessage singleMessage = new SingleMessage();
            BeanUtils.copyProperties(newMessageVo, singleMessage);
            singleMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            singleMessage.setTime(String.valueOf(Instant.now()));
            singleMessageService.addNewSingleMessage(singleMessage);
            logger.debug("保存单聊消息：{}", singleMessage.getId());
        } else if (ConstValueEnum.GROUP.equals(newMessageVo.getConversationType())) {
            // 保存群聊消息
            GroupMessage groupMessage = new GroupMessage();
            BeanUtils.copyProperties(newMessageVo, groupMessage);
            groupMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            groupMessage.setTime(String.valueOf(Instant.now()));
            groupMessageService.addNewGroupMessage(groupMessage);
            logger.debug("保存群聊消息：{}", groupMessage.getId());
        }
    }

    /**
     * 处理服务卡片消息生成（仅单聊）
     */
    private void handleCardMessage(NewMessageVo newMessageVo) {
        String message = newMessageVo.getMessage();
        if (StringUtils.isEmpty(message)) {
            return;
        }
        String senderId = newMessageVo.getSenderId();
        String receiverId = newMessageVo.getReceiverId();

        try {
            // 校验发送者/接收者存在性
            User sender = userService.getUserInfo(senderId);
            User receiver = userService.getUserInfo(receiverId);
            if (sender == null || receiver == null) {
                logger.warn("用户信息不存在，无法生成卡片：sender={}, receiver={}", senderId, receiverId);
                return;
            }

            // 校验是否为客服与用户的聊天
            UserRoleEnum senderRole = UserRoleEnum.fromCode(sender.getRole());
            UserRoleEnum receiverRole = UserRoleEnum.fromCode(receiver.getRole());

            // 处理未知角色情况
            if (senderRole == null) {
                logger.error("未知角色代码：senderRole={}, senderId={}", sender.getRole(), senderId);
                return;
            }
            if (receiverRole == null) {
                logger.error("未知角色代码：receiverRole={}, receiverId={}", receiver.getRole(), receiverId);
                return;
            }

            boolean isCustomerServiceChat = (senderRole.isCustomerService() && receiverRole.isCustomer())
                    || (senderRole.isCustomer() && receiverRole.isCustomerService());
            if (!isCustomerServiceChat) {
                logger.debug("非客服与用户聊天，不生成卡片：senderRole={}, receiverRole={}",
                        senderRole, receiverRole);
                newMessageVo.setCardType(null);
                newMessageVo.setCardOptions(null);
                return;
            }

            // 检测关键字并生成卡片
            boolean hasRefund = message.contains("申请退款");
            boolean hasQuery = message.contains("查询订单");
            if (hasRefund || hasQuery) {
                newMessageVo.setCardType(ConstValueEnum.MESSAGE_TYPE_CARD);
                List<CardOptionVo> options = new ArrayList<>();
                if (hasRefund) {
                    options.add(new CardOptionVo("申请退款", "/chat/order/refund?userId=" + senderId + "&token={token}", "POST"));
                }
                if (hasQuery) {
                    options.add(new CardOptionVo("查询订单", "/chat/order/query?userId=" + senderId + "&token={token}", "GET"));
                }
                newMessageVo.setCardOptions(options);
                logger.debug("生成服务卡片：sender={}, 关键字={}", senderId, hasRefund ? "申请退款" : "查询订单");
            } else {
                newMessageVo.setCardType(null);
                newMessageVo.setCardOptions(null);
            }
        } catch (Exception e) {
            logger.error("处理服务卡片生成异常", e);
            // 异常时不生成卡片
            newMessageVo.setCardType(null);
            newMessageVo.setCardOptions(null);
        }
    }

    /**
     * 发送验证消息事件
     */
    @OnEvent("sendValidateMessage")
    public void sendValidateMessage(SocketIOClient client, ValidateMessage validateMessage) {
        try {
            if (!validateClientAndRequest(client, validateMessage)) {
                logger.warn("发送验证消息：客户端或消息为空");
                return;
            }
            logger.info("处理验证消息：senderId={}, roomId={}",
                    validateMessage.getSenderId(), validateMessage.getRoomId());

            // 敏感词过滤，处理返回null的情况
            String originalMsg = validateMessage.getAdditionMessage();
            String[] filteredResult = sensitiveFilter.filter(originalMsg);
            // 过滤结果为null时，默认保留原始消息
            if (filteredResult == null) {
                filteredResult = new String[]{originalMsg != null ? originalMsg : "", "0"};
                logger.warn("验证消息敏感词过滤返回null，使用原始消息，senderId={}", validateMessage.getSenderId());
            }

            String filteredContent = filteredResult[0];
            validateMessage.setAdditionMessage(filteredContent);

            // 记录敏感验证消息
            if ("1".equals(filteredResult[1])) {
                try {
                    SensitiveMessage sensitiveMsg = new SensitiveMessage();
                    sensitiveMsg.setRoomId(validateMessage.getRoomId());
                    sensitiveMsg.setSenderId(validateMessage.getSenderId().toString());
                    sensitiveMsg.setSenderName(validateMessage.getSenderName());
                    sensitiveMsg.setMessage(originalMsg);
                    sensitiveMsg.setType(ConstValueEnum.VALIDATE);
                    sensitiveMsg.setTime(String.valueOf(Instant.now()));
                    sysService.addSensitiveMessage(sensitiveMsg);
                } catch (Exception e) {
                    logger.error("记录敏感验证消息异常", e);
                    // 记录失败不影响主流程
                }
            }

            // 保存验证消息并转发
            ValidateMessage savedMsg = validateMessageService.addValidateMessage(validateMessage);
            if (savedMsg != null) {
                sendToOtherClients(client, validateMessage.getRoomId(), EVENT_RECEIVE_VALIDATE_MSG, validateMessage);
            }
        } catch (Exception e) {
            logger.error("处理发送验证消息事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 同意好友请求事件
     */
    @OnEvent("sendAgreeFriendValidate")
    public void sendAgreeFriendValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        try {
            if (!validateClientAndRequest(client, validateMessage)) {
                logger.warn("同意好友请求：客户端或消息为空");
                return;
            }
            logger.info("同意好友请求：senderId={}, receiverId={}",
                    validateMessage.getSenderId(), validateMessage.getReceiverId());

            // 创建好友关系
            GoodFriend goodFriend = new GoodFriend();
            goodFriend.setUserM(new ObjectId(validateMessage.getSenderId()));
            goodFriend.setUserY(new ObjectId(validateMessage.getReceiverId()));
            goodFriendService.addFriend(goodFriend);

            // 更新验证消息状态
            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 1);

            // 生成单聊房间ID（按字典序拼接）
            String senderId = validateMessage.getSenderId();
            String receiverId = validateMessage.getReceiverId();
            String targetRoomId = generateSingleChatRoomId(senderId, receiverId);

            sendToOtherClients(client, targetRoomId, EVENT_RECEIVE_AGREE_FRIEND, validateMessage);
        } catch (Exception e) {
            logger.error("处理同意好友请求事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 生成单聊房间ID（按字典序拼接两个uid）
     */
    private String generateSingleChatRoomId(String uid1, String uid2) {
        if (uid1 == null || uid2 == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        // 使用StringBuilder优化拼接
        StringBuilder sb = new StringBuilder();
        if (uid1.compareTo(uid2) < 0) {
            sb.append(uid1).append("-").append(uid2);
        } else {
            sb.append(uid2).append("-").append(uid1);
        }
        return sb.toString();
    }

    /**
     * 拒绝好友请求事件
     */
    @OnEvent("sendDisAgreeFriendValidate")
    public void sendDisAgreeFriendValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        try {
            if (!validateClientAndRequest(client, validateMessage)) {
                logger.warn("拒绝好友请求：客户端或消息为空");
                return;
            }
            logger.info("拒绝好友请求：id={}", validateMessage.getId());
            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
        } catch (Exception e) {
            logger.error("处理拒绝好友请求事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 删除好友事件
     */
    @OnEvent("sendDelGoodFriend")
    public void sendDelGoodFriend(SocketIOClient client, CurrentConversationVo conversationVo) {
        try {
            if (!validateClientAndRequest(client, conversationVo) || StringUtils.isEmpty(conversationVo.getRoomId())) {
                logger.warn("删除好友：客户端或消息为空");
                return;
            }
            logger.info("删除好友：roomId={}", conversationVo.getRoomId());

            // 补充操作人ID
            String uid = onlineUserService.getSimpleUserByClientId(client.getSessionId().toString()).getUid();
            conversationVo.setId(uid);

            // 转发删除通知
            sendToOtherClients(client, conversationVo.getRoomId(), EVENT_RECEIVE_DEL_FRIEND, conversationVo);
        } catch (Exception e) {
            logger.error("处理删除好友事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 同意加入群聊事件
     */
    @OnEvent("sendAgreeGroupValidate")
    public void sendAgreeGroupValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        try {
            if (!validateClientAndRequest(client, validateMessage)) {
                logger.warn("同意加入群聊：客户端或消息为空");
                return;
            }
            logger.info("同意加入群聊：groupId={}, userId={}",
                    validateMessage.getRoomId(), validateMessage.getReceiverId());

            // 添加群成员
            groupUserService.addNewGroupUser(validateMessage);

            // 更新验证消息状态
            validateMessageService.changeGroupValidateNewsStatus(validateMessage.getId(), 1);

            // 生成目标房间ID（群聊房间ID直接使用groupId的字符串形式）
            String targetRoomId = validateMessage.getRoomId().toString();

            sendToOtherClients(client, targetRoomId, EVENT_RECEIVE_AGREE_GROUP, validateMessage);
        } catch (Exception e) {
            logger.error("处理同意加入群聊事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 拒绝加入群聊事件
     */
    @OnEvent("sendDisAgreeGroupValidate")
    public void sendDisAgreeGroupValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        try {
            if (!validateClientAndRequest(client, validateMessage)) {
                logger.warn("拒绝加入群聊：客户端或消息为空");
                return;
            }
            logger.info("拒绝加入群聊：id={}", validateMessage.getId());
            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
        } catch (Exception e) {
            logger.error("处理拒绝加入群聊事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 退出群聊事件
     */
    @OnEvent("sendQuitGroup")
    public void sendQuitGroup(SocketIOClient client, CurrentConversationVo conversationVo) {
        try {
            if (!validateClientAndRequest(client, conversationVo) || StringUtils.isEmpty(conversationVo.getRoomId())) {
                logger.warn("退出群聊：客户端或消息为空");
                return;
            }
            logger.info("退出群聊：roomId={}", conversationVo.getRoomId());
            sendToOtherClients(client, conversationVo.getRoomId(), EVENT_RECEIVE_QUIT_GROUP, conversationVo);
        } catch (Exception e) {
            logger.error("处理退出群聊事件异常", e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 申请事件（通用）
     */
    @OnEvent("apply")
    public void apply(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardEventToRoom(client, conversationVo, "apply");
    }

    /**
     * 回复事件（通用）
     */
    @OnEvent("reply")
    public void reply(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardEventToRoom(client, conversationVo, "reply");
    }

    /**
     * 1v1通话相关事件转发
     */
    @OnEvent("1v1answer")
    public void answer(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardEventToRoom(client, conversationVo, "1v1answer");
    }

    @OnEvent("1v1ICE")
    public void ice(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardEventToRoom(client, conversationVo, "1v1ICE");
    }

    @OnEvent("1v1offer")
    public void offer(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardEventToRoom(client, conversationVo, "1v1offer");
    }

    @OnEvent("1v1hangup")
    public void hangup(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardEventToRoom(client, conversationVo, "1v1hangup");
    }

    /**
     * 通用事件转发到房间
     */
    private void forwardEventToRoom(SocketIOClient client, CurrentConversationVo conversationVo, String eventName) {
        try {
            if (!validateClientAndRequest(client, conversationVo) || StringUtils.isEmpty(conversationVo.getRoomId())) {
                logger.warn("转发事件{}：客户端或消息为空", eventName);
                return;
            }
            logger.debug("转发事件{}，roomId：{}", eventName, conversationVo.getRoomId());
            sendToOtherClients(client, conversationVo.getRoomId(), eventName, conversationVo);
        } catch (Exception e) {
            logger.error("转发事件{}异常", eventName, e);
            if (client != null) {
                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
            }
        }
    }

    /**
     * 发送事件给房间内其他客户端（排除自己）
     */
    private void sendToOtherClients(SocketIOClient senderClient, String roomId, String eventName, Object data) {
        try {
            Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(roomId).getClients();
            if (clients == null) {
                logger.debug("房间{}内无其他客户端，无需转发事件{}", roomId, eventName);
                return;
            }
            // 使用迭代器遍历，避免ConcurrentModificationException
            Iterator<SocketIOClient> iterator = clients.iterator();
            while (iterator.hasNext()) {
                SocketIOClient client = iterator.next();
                if (client != null && !client.getSessionId().equals(senderClient.getSessionId())) {
                    client.sendEvent(eventName, data);
                }
            }
        } catch (Exception e) {
            logger.error("发送事件{}给房间{}内其他客户端异常", eventName, roomId, e);
        }
    }

    /**
     * 校验客户端和请求参数非空
     */
    private boolean validateClientAndRequest(SocketIOClient client, Object request) {
        return client != null && request != null;
    }
}
