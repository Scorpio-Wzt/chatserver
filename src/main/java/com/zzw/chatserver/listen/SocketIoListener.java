package com.zzw.chatserver.listen;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.zzw.chatserver.common.ConstValueEnum;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.filter.SensitiveFilter;
import com.zzw.chatserver.pojo.*;
import com.zzw.chatserver.pojo.vo.*;
import com.zzw.chatserver.service.*;
import com.zzw.chatserver.utils.DateUtil;
import com.zzw.chatserver.utils.ValidationUtil;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * SocketIO事件监听器
 * 核心职责：处理客户端连接/断开、消息收发、心跳维护、离线消息推送、在线状态管理
 */
@Component
@Transactional(rollbackFor = Throwable.class)
@Slf4j
public class SocketIoListener {
    // ============================== 常量归类（提升可维护性）==============================
    /** 事件名称常量 */
    private static class EventName {
        public static final String ONLINE_USER = "onlineUser";
        public static final String RECONNECT_SUCCESS = "reconnectSuccess";
        public static final String OFFLINE_SINGLE_MSG = "offlineSingleMessages";
        public static final String OFFLINE_GROUP_MSG_PREFIX = "offlineGroupMessages_";
        public static final String SEND_FAILED = "sendFailed";
        public static final String RECEIVE_MSG = "receiveMessage";
        public static final String JOIN_FAILED = "joinFailed";
        public static final String IS_READ_MSG = "isReadMsg";
        public static final String RECEIVE_VALIDATE_MSG = "receiveValidateMessage";
        public static final String RECEIVE_AGREE_FRIEND = "receiveAgreeFriendValidate";
        public static final String RECEIVE_AGREE_GROUP = "receiveAgreeGroupValidate";
        public static final String RECEIVE_DEL_FRIEND = "receiveDelGoodFriend";
        public static final String RECEIVE_QUIT_GROUP = "receiveQuitGroup";
        public static final String CONFIRM_RECEIVE = "confirmReceive";
        // 1v1通话事件
        public static final String CALL_ANSWER = "1v1answer";
        public static final String CALL_ICE = "1v1ICE";
        public static final String CALL_OFFER = "1v1offer";
        public static final String CALL_HANGUP = "1v1hangup";
        // 通用事件
        public static final String APPLY = "apply";
        public static final String REPLY = "reply";
    }

    /** 错误信息常量 */
    private static class ErrorMsg {
        public static final String INVALID_ROOM_ID = "房间ID格式错误";
        public static final String INVALID_SENDER_ID = "发送者ID格式错误";
        public static final String INVALID_RECEIVER_ID = "接收者ID格式错误";
        public static final String IDENTITY_VERIFY_FAILED = "身份验证失败";
        public static final String SENDER_NOT_EXIST = "发送者信息不存在";
        public static final String PARAM_INCOMPLETE = "参数不完整";
        public static final String NOT_FRIEND = "非好友关系，无法发送消息";
        public static final String SERVER_EXCEPTION = "服务器异常，请稍后重试";
        public static final String UNSUPPORTED_CONVERSATION_TYPE = "不支持的会话类型";
        public static final String CLIENT_NULL = "客户端连接为空";
        public static final String USER_NOT_EXIST = "用户不存在";
        public static final String CUSTOMER_NOT_EXIST = "绑定的客服不存在";
        public static final String CUSTOMER_ROLE_INVALID = "客服ID不合法：该用户不是客服角色";
    }

    /** 时间常量（毫秒） */
    private static class TimeConstant {
        public static final long HEARTBEAT_EXPIRATION = 3600000L; // 1小时（客户端过期时间）
        public static final long HEARTBEAT_INTERVAL = 1800000L;  // 30分钟（心跳间隔）
        public static final long CLEAN_EXPIRED_INTERVAL = 3600000L; // 1小时（清理过期客户端间隔）
    }

    /** 消息类型常量 */
    private static class MsgType {
        public static final String IMG = "img";
        public static final String CARD = "card";
    }

    /** 通用分隔符 */
    private static final String ROOM_ID_SEPARATOR = "-";
    /** 统一时间格式化器（避免解析异常） */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));


    // ============================== 成员变量 ===============================
    // 线程安全锁：保护在线用户操作（避免并发修改异常）
    private final ReentrantLock onlineUserLock = new ReentrantLock();

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


    // ============================== 核心定时任务 ===============================
    /**
     * 定时清理过期客户端（每小时执行）
     * 职责：移除超时未心跳的客户端，更新在线用户列表
     */
    @Scheduled(fixedRate = TimeConstant.CLEAN_EXPIRED_INTERVAL)
    public void cleanExpiredClients() {
        onlineUserLock.lock();
        try {
            int cleanedCount = onlineUserService.cleanExpiredClients(TimeConstant.HEARTBEAT_EXPIRATION);
            log.info("定时清理过期客户端完成，清理数量：{}", cleanedCount);
            // 清理后广播最新在线用户列表
            if (cleanedCount > 0) {
                broadcastOnlineUser();
            }
        } catch (Exception e) {
            log.error("定时清理过期客户端失败", e);
        } finally {
            onlineUserLock.unlock();
        }
    }


    // ============================== 连接/断开事件 ===============================
    /**
     * 客户端断开连接事件
     * 职责：清理用户在线状态、更新在线时长、广播在线用户变化
     */
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) {
        if (client == null) {
            log.warn("客户端断开连接：{}", ErrorMsg.CLIENT_NULL);
            return;
        }

        try {
            String clientId = client.getSessionId().toString();
            log.info("客户端断开连接，clientId: {}", clientId);

            // 清理用户登录信息（解绑+更新在线时长）
            cleanLoginInfo(clientId);
            // 广播在线用户变化
            broadcastOnlineUser();

            log.info("连接关闭完成，clientId: {}, url参数: {}", clientId, client.getHandshakeData().getUrlParams());
        } catch (Exception e) {
            log.error("处理客户端断开连接异常，clientId: {}", client.getSessionId(), e);
        }
    }

    /**
     * 客户端连接/重连事件
     * 职责：恢复用户在线状态、重加入历史房间、推送重连通知
     */
    @OnConnect
    public void onConnect(SocketIOClient client) {
        if (client == null) {
            log.warn("客户端连接：{}", ErrorMsg.CLIENT_NULL);
            return;
        }

        try {
            // 提取URL参数中的用户ID
            String uid = extractUidFromParams(client.getHandshakeData().getUrlParams());
            log.info("客户端连接/重连，UID: {}", uid);

            if (uid == null) {
                log.warn("客户端连接：未获取到UID，跳过绑定");
                return;
            }

            // 校验用户合法性
            User user = userService.getUserInfo(uid);
            if (user == null) {
                log.error("客户端连接：{}，UID: {}", ErrorMsg.USER_NOT_EXIST, uid);
                return;
            }

            // 绑定客户端与用户（线程安全）
            SimpleUser simpleUser = convertToSimpleUser(user);
            onlineUserLock.lock();
            try {
                onlineUserService.addClientIdToSimpleUser(client.getSessionId().toString(), simpleUser);
            } finally {
                onlineUserLock.unlock();
            }

            // 重加入历史房间（单聊+群聊）
            joinUserHistoricalRooms(client, uid);

            // 推送重连成功通知
            sendEventToClient(client, EventName.RECONNECT_SUCCESS, "重连成功");
        } catch (Exception e) {
            log.error("处理客户端连接异常", e);
        }
    }


    // ============================== 核心业务事件 ===============================
    /**
     * 用户上线事件
     * 职责：绑定用户与客户端、清理旧连接、推送离线消息
     */
    @OnEvent("goOnline")
    public void goOnline(SocketIOClient client, User user) {
        try {
            // 基础校验
            if (!validateClientAndUser(client, user)) {
                log.warn("用户上线：{}", ErrorMsg.PARAM_INCOMPLETE);
                return;
            }

            String clientId = client.getSessionId().toString();
            String uid = user.getUid();
            log.info("用户上线，UID: {}, clientId: {}", uid, clientId);

            // 清理该用户的旧客户端绑定（避免多端登录冲突）
            cleanOldClientBinding(uid, clientId);

            // 绑定新客户端（线程安全）
            SimpleUser simpleUser = convertToSimpleUser(user);
            onlineUserLock.lock();
            try {
                onlineUserService.addClientIdToSimpleUser(clientId, simpleUser);
            } finally {
                onlineUserLock.unlock();
            }

            // 更新在线状态（日志+广播）
            printOnlineUserCount();
            broadcastOnlineUser();

            // 推送离线消息（单聊+群聊）
            pushOfflineMessages(client, uid);
        } catch (BusinessException e) {
            log.warn("用户上线业务异常：{}", e.getMessage());
            sendSendFailedEvent(client, e.getMessage());
        } catch (Exception e) {
            log.error("处理用户上线事件异常", e);
            sendSendFailedEvent(client, ErrorMsg.SERVER_EXCEPTION);
        }
    }

    /**
     * 消息接收确认事件
     * 职责：标记单聊/群聊消息为已读
     */
    @OnEvent(EventName.CONFIRM_RECEIVE)
    public void confirmReceive(SocketIOClient client, MessageConfirmVo confirmVo) {
        try {
            // 基础校验
            if (!validateClientAndParam(client, confirmVo) || StringUtils.isEmpty(confirmVo.getUserId())) {
                log.warn("消息确认：{}", ErrorMsg.PARAM_INCOMPLETE);
                return;
            }

            String userId = confirmVo.getUserId();
            log.info("收到消息确认，userId: {}, 单聊消息数: {}, 群聊消息数: {}",
                    userId,
                    Optional.ofNullable(confirmVo.getSingleMessageIds()).map(List::size).orElse(0),
                    Optional.ofNullable(confirmVo.getGroupMessageIds()).map(Map::size).orElse(0));

            // 标记单聊消息已读
            if (confirmVo.getSingleMessageIds() != null && !confirmVo.getSingleMessageIds().isEmpty()) {
                singleMessageService.markMessagesAsRead(userId, confirmVo.getSingleMessageIds());
            }

            // 标记群聊消息已读（按房间维度）
            if (confirmVo.getGroupMessageIds() != null && !confirmVo.getGroupMessageIds().isEmpty()) {
                confirmVo.getGroupMessageIds().forEach((roomId, msgIds) -> {
                    if (isValidRoomId(roomId) && msgIds != null && !msgIds.isEmpty()) {
                        groupMessageService.markGroupMessagesAsRead(roomId, userId, msgIds);
                    }
                });
            }
        } catch (Exception e) {
            log.error("处理消息确认异常，userId: {}", confirmVo.getUserId(), e);
        }
    }

    /**
     * 心跳事件
     * 职责：延长客户端过期时间，维持在线状态
     */
    @OnEvent("heartbeat")
    public void handleHeartbeat(SocketIOClient client) {
        try {
            if (client == null) {
                log.warn("心跳处理：{}", ErrorMsg.CLIENT_NULL);
                return;
            }

            String clientId = client.getSessionId().toString();
            // 获取客户端绑定的用户
            SimpleUser simpleUser = onlineUserService.getSimpleUserByClientId(clientId);
            if (simpleUser == null) {
                log.warn("心跳验证失败：未找到客户端绑定用户，clientId: {}", clientId);
                return;
            }

            // 续期客户端过期时间（线程安全）
            onlineUserLock.lock();
            try {
                onlineUserService.renewExpiration(clientId, simpleUser.getUid(), TimeConstant.HEARTBEAT_EXPIRATION);
            } finally {
                onlineUserLock.unlock();
            }

            log.debug("客户端心跳续期成功，clientId: {}, UID: {}, 过期时间: {}ms",
                    clientId, simpleUser.getUid(), TimeConstant.HEARTBEAT_EXPIRATION);
        } catch (Exception e) {
            log.error("处理客户端心跳异常", e);
        }
    }

    /**
     * 发送新消息事件（核心）
     * 职责：参数校验、身份验证、安全处理、消息存储、转发
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
            log.info("处理新消息，senderId: {}, roomId: {}, 会话类型: {}",
                    senderId, roomId, newMessageVo.getConversationType());

            // 身份验证（防会话劫持）
            if (!validateSenderIdentity(senderId)) {
                sendSendFailedEvent(client, ErrorMsg.IDENTITY_VERIFY_FAILED);
                return;
            }

            // 消息安全处理（XSS+敏感词）
            processMessageSecurity(newMessageVo);

            // 会话类型特殊逻辑（单聊/群聊）
            if (!processConversationSpecialLogic(newMessageVo, client)) {
                return;
            }

            // 处理已读用户列表（发送者默认已读+在线接收者）
            handleReadUsers(newMessageVo);

            saveMessageToDatabase(newMessageVo);

            // 转发消息给房间内其他客户端
            forwardMessageToOtherClients(client, roomId, EventName.RECEIVE_MSG, newMessageVo);
        } catch (BusinessException e) {
            log.warn("处理新消息业务异常：{}", e.getMessage());
            sendSendFailedEvent(client, e.getMessage());
        } catch (Exception e) {
            log.error("处理发送新消息系统异常", e);
            sendSendFailedEvent(client, ErrorMsg.SERVER_EXCEPTION);
        }
    }


    // ============================== 好友/群聊相关事件 ===============================
    /**
     * 发送验证消息事件（好友请求/群申请）
     */
    @OnEvent("sendValidateMessage")
    public void sendValidateMessage(SocketIOClient client, ValidateMessage validateMessage) {
        try {
            if (!validateClientAndParam(client, validateMessage)) {
                log.warn("发送验证消息：{}", ErrorMsg.PARAM_INCOMPLETE);
                return;
            }

            String senderId = validateMessage.getSenderId().toString();
            String roomId = validateMessage.getRoomId();
            log.info("处理验证消息，senderId: {}, roomId: {}", senderId, roomId);

            // 敏感词过滤（处理null结果）
            String originalMsg = validateMessage.getAdditionMessage();
            String[] filteredResult = Optional.ofNullable(sensitiveFilter.filter(originalMsg))
                    .orElse(new String[]{originalMsg != null ? originalMsg : "", "0"});
            validateMessage.setAdditionMessage(filteredResult[0]);

            // 记录敏感消息（若有）
            if ("1".equals(filteredResult[1])) {
                recordSensitiveMessage(validateMessage, originalMsg, ConstValueEnum.VALIDATE);
            }

            // 保存并转发消息
            ValidateMessage savedMsg = validateMessageService.addValidateMessage(validateMessage);
            if (savedMsg != null) {
                forwardMessageToOtherClients(client, roomId, EventName.RECEIVE_VALIDATE_MSG, validateMessage);
            }
        } catch (Exception e) {
            log.error("处理发送验证消息异常", e);
            sendSendFailedEvent(client, ErrorMsg.SERVER_EXCEPTION);
        }
    }

    /**
     * 同意好友请求事件
     */
    @OnEvent("sendAgreeFriendValidate")
    public void sendAgreeFriendValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        try {
            if (!validateClientAndParam(client, validateMessage)) {
                log.warn("同意好友请求：{}", ErrorMsg.PARAM_INCOMPLETE);
                return;
            }

            String senderId = validateMessage.getSenderId();
            String receiverId = validateMessage.getReceiverId();
            log.info("同意好友请求，senderId: {}, receiverId: {}", senderId, receiverId);

            // 创建好友关系
            GoodFriend goodFriend = new GoodFriend();
            goodFriend.setUserM(new ObjectId(senderId));
            goodFriend.setUserY(new ObjectId(receiverId));
            goodFriendService.addFriend(goodFriend);

            // 更新验证消息状态（已同意）
            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 1);

            // 生成单聊房间ID（按字典序避免重复）
            String roomId = generateSingleChatRoomId(senderId, receiverId);

            // 转发同意通知
            forwardMessageToOtherClients(client, roomId, EventName.RECEIVE_AGREE_FRIEND, validateMessage);
        } catch (Exception e) {
            log.error("处理同意好友请求异常", e);
            sendSendFailedEvent(client, ErrorMsg.SERVER_EXCEPTION);
        }
    }

    /**
     * 同意群聊申请事件
     */
    @OnEvent("sendAgreeGroupValidate")
    public void sendAgreeGroupValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
        try {
            if (!validateClientAndParam(client, validateMessage)) {
                log.warn("同意群聊申请：{}", ErrorMsg.PARAM_INCOMPLETE);
                return;
            }

            String groupId = validateMessage.getRoomId().toString();
            String userId = validateMessage.getReceiverId();
            log.info("同意群聊申请，groupId: {}, userId: {}", groupId, userId);

            // 添加群成员
            groupUserService.addNewGroupUser(validateMessage);

            // 更新验证消息状态（已同意）
            validateMessageService.changeGroupValidateNewsStatus(validateMessage.getId(), 1);

            // 转发同意通知
            forwardMessageToOtherClients(client, groupId, EventName.RECEIVE_AGREE_GROUP, validateMessage);
        } catch (Exception e) {
            log.error("处理同意群聊申请异常", e);
            sendSendFailedEvent(client, ErrorMsg.SERVER_EXCEPTION);
        }
    }


    // ============================== 通用工具方法（消除冗余）==============================
    /**
     * 提取URL参数中的UID
     */
    private String extractUidFromParams(Map<String, List<String>> urlParams) {
        return Optional.ofNullable(urlParams)
                .map(params -> params.get("uid"))
                .filter(list -> !list.isEmpty())
                .map(list -> list.get(0))
                .orElse(null);
    }

    /**
     * 转换User为SimpleUser（属性拷贝）
     */
    private SimpleUser convertToSimpleUser(User user) {
        SimpleUser simpleUser = new SimpleUser();
        BeanUtils.copyProperties(user, simpleUser);
        return simpleUser;
    }

    /**
     * 重加入用户历史房间（单聊+群聊）
     */
    private void joinUserHistoricalRooms(SocketIOClient client, String uid) {
        List<String> roomIds = getRoomsByUid(uid);
        if (roomIds.isEmpty()) {
            log.info("用户{}无历史房间，无需重加入", uid);
            return;
        }

        roomIds.forEach(roomId -> {
            if (isValidRoomId(roomId)) {
                client.joinRoom(roomId);
                log.info("用户{}重加入房间：{}", uid, roomId);
            }
        });
    }

    /**
     * 查询用户所有房间ID（单聊+群聊）
     */
    private List<String> getRoomsByUid(String uid) {
        if (uid == null) {
            log.warn("查询房间：UID为空");
            return Collections.emptyList();
        }

        List<String> roomIds = new ArrayList<>();
        try {
            // 单聊房间（好友关系）
            roomIds.addAll(Optional.ofNullable(goodFriendService.getMyFriendsList(uid))
                    .orElse(Collections.emptyList())
                    .stream()
                    .filter(Objects::nonNull)
                    .map(MyFriendListResultVo::getRoomId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList()));

            // 群聊房间（用户加入的群组）
            User user = userService.getUserInfo(uid);
            if (user != null && !StringUtils.isEmpty(user.getUsername())) {
                roomIds.addAll(Optional.ofNullable(groupUserService.getGroupUsersByUserName(user.getUsername()))
                        .orElse(Collections.emptyList())
                        .stream()
                        .filter(Objects::nonNull)
                        .map(MyGroupResultVo::getGroupId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList()));
            }
        } catch (Exception e) {
            log.error("查询用户房间列表异常，uid: {}", uid, e);
        }

        return roomIds;
    }

    /**
     * 清理用户登录信息（解绑客户端+更新在线时长）
     */
    private void cleanLoginInfo(String clientId) {
        if (clientId == null) {
            log.warn("清理登录信息：clientId为空");
            return;
        }

        onlineUserLock.lock();
        try {
            SimpleUser simpleUser = onlineUserService.getSimpleUserByClientId(clientId);
            if (simpleUser != null) {
                // 解绑客户端
                onlineUserService.removeClientAndUidInSet(clientId, simpleUser.getUid());
                // 更新在线时长
                long onlineTime = DateUtil.getTimeDelta(
                        Date.from(Instant.parse(simpleUser.getLastLoginTime())),
                        new Date()
                );
                userService.updateOnlineTime(onlineTime, simpleUser.getUid());
                log.info("清理用户登录信息，UID: {}, clientId: {}, 在线时长: {}ms",
                        simpleUser.getUid(), clientId, onlineTime);
            }
        } catch (Exception e) {
            log.error("清理用户登录信息异常，clientId: {}", clientId, e);
        } finally {
            onlineUserLock.unlock();
        }
    }

    /**
     * 清理用户旧客户端绑定（重连场景）
     */
    private void cleanOldClientBinding(String uid, String newClientId) {
        onlineUserLock.lock();
        try {
            String oldClientId = onlineUserService.getClientIdByUid(uid);
            if (oldClientId != null && !oldClientId.equals(newClientId)) {
                onlineUserService.removeClientAndUidInSet(oldClientId, uid);
                log.info("清理用户旧客户端绑定，UID: {}, 旧clientId: {}, 新clientId: {}",
                        uid, oldClientId, newClientId);
            }
        } catch (Exception e) {
            log.error("清理用户旧客户端绑定异常，uid: {}", uid, e);
        } finally {
            onlineUserLock.unlock();
        }
    }

    /**
     * 推送离线消息（单聊+群聊）
     */
    private void pushOfflineMessages(SocketIOClient client, String uid) {
        log.info("用户{}上线，开始推送离线消息", uid);
        // 推送单聊离线消息
        pushOfflineSingleMessages(client, uid);
        // 推送群聊离线消息
        pushOfflineGroupMessages(client, uid);
    }

    /**
     * 推送单聊离线消息
     */
    private void pushOfflineSingleMessages(SocketIOClient client, String uid) {
        try {
            List<SingleMessageResultVo> offlineMsgs = singleMessageService.getUnreadMessages(uid);
            if (offlineMsgs != null && !offlineMsgs.isEmpty()) {
                sendEventToClient(client, EventName.OFFLINE_SINGLE_MSG, offlineMsgs);
                log.info("推送单聊离线消息{}条给用户{}", offlineMsgs.size(), uid);
            }
        } catch (Exception e) {
            log.error("推送单聊离线消息异常，uid: {}", uid, e);
        }
    }

    /**
     * 推送群聊离线消息
     */
    private void pushOfflineGroupMessages(SocketIOClient client, String uid) {
        try {
            getRoomsByUid(uid).forEach(roomId -> {
                if (isValidRoomId(roomId)) {
                    List<GroupMessageResultVo> offlineMsgs = groupMessageService.getUnreadGroupMessages(roomId, uid);
                    if (offlineMsgs != null && !offlineMsgs.isEmpty()) {
                        sendEventToClient(client, EventName.OFFLINE_GROUP_MSG_PREFIX + roomId, offlineMsgs);
                        log.info("推送群聊[{}]离线消息{}条给用户{}", roomId, offlineMsgs.size(), uid);
                    }
                }
            });
        } catch (Exception e) {
            log.error("推送群聊离线消息异常，uid: {}", uid, e);
        }
    }

    /**
     * 生成单聊房间ID（按字典序拼接，避免重复）
     */
    private String generateSingleChatRoomId(String uid1, String uid2) {
        if (uid1 == null || uid2 == null) {
            throw new IllegalArgumentException("用户ID不能为空");
        }
        return uid1.compareTo(uid2) < 0
                ? uid1 + ROOM_ID_SEPARATOR + uid2
                : uid2 + ROOM_ID_SEPARATOR + uid1;
    }

    /**
     * 记录敏感消息
     */
    private void recordSensitiveMessage(ValidateMessage validateMessage, String originalMsg, String type) {
        try {
            SensitiveMessage sensitiveMsg = new SensitiveMessage();
            sensitiveMsg.setRoomId(validateMessage.getRoomId());
            sensitiveMsg.setSenderId(validateMessage.getSenderId().toString());
            sensitiveMsg.setSenderName(validateMessage.getSenderName());
            sensitiveMsg.setMessage(originalMsg);
            sensitiveMsg.setType(type);
            sensitiveMsg.setTime(formatTime(Instant.now()));
            sysService.addSensitiveMessage(sensitiveMsg);
            log.warn("记录敏感消息，senderId: {}, 原内容: {}", sensitiveMsg.getSenderId(), originalMsg);
        } catch (Exception e) {
            log.error("记录敏感消息异常", e);
            // 记录失败不阻断主流程
        }
    }

    /**
     * 格式化时间（统一格式）
     */
    private String formatTime(Instant instant) {
        return instant.atZone(ZoneId.of("Asia/Shanghai")).format(TIME_FORMATTER);
    }


    // ============================== 校验工具方法 ===============================
    /**
     * 校验客户端和用户非空
     */
    private boolean validateClientAndUser(SocketIOClient client, User user) {
        return client != null && user != null && !StringUtils.isEmpty(user.getUid());
    }

    /**
     * 校验客户端和请求参数非空
     */
    private boolean validateClientAndParam(SocketIOClient client, Object param) {
        return client != null && param != null;
    }

    /**
     * 校验ObjectId格式（用户ID/消息ID）
     */
    private boolean isValidObjectId(String id) {
        return !StringUtils.isEmpty(id) && ObjectId.isValid(id);
    }

    /**
     * 校验房间ID格式
     */
    private boolean isValidRoomId(String roomId) {
        return !StringUtils.isEmpty(roomId) && ValidationUtil.isValidRoomId(roomId);
    }

    /**
     * 校验新消息参数合法性
     */
    private boolean validateNewMessageParams(SocketIOClient client, NewMessageVo newMessageVo) {
        // 基础非空校验
        if (!validateClientAndParam(client, newMessageVo)
                || StringUtils.isEmpty(newMessageVo.getSenderId())
                || StringUtils.isEmpty(newMessageVo.getRoomId())
                || StringUtils.isEmpty(newMessageVo.getConversationType())) {
            sendSendFailedEvent(client, ErrorMsg.PARAM_INCOMPLETE);
            return false;
        }

        // 格式校验
        if (!isValidObjectId(newMessageVo.getSenderId())) {
            sendSendFailedEvent(client, ErrorMsg.INVALID_SENDER_ID);
            return false;
        }
        if (!isValidRoomId(newMessageVo.getRoomId())) {
            sendSendFailedEvent(client, ErrorMsg.INVALID_ROOM_ID);
            return false;
        }
        // 单聊接收者ID校验
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())
                && !isValidObjectId(newMessageVo.getReceiverId())) {
            sendSendFailedEvent(client, ErrorMsg.INVALID_RECEIVER_ID);
            return false;
        }

        return true;
    }

    /**
     * 校验发送者身份（防会话劫持）
     */
    private boolean validateSenderIdentity(String senderId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            log.error("身份验证失败：无认证信息");
            return false;
        }

        // 提取实际用户ID（支持UserDetails/String类型）
        String actualUserId = Optional.ofNullable(authentication.getPrincipal())
                .map(principal -> {
                    if (principal instanceof UserDetails) {
                        return ((UserDetails) principal).getUsername();
                    } else if (principal instanceof String) {
                        return (String) principal;
                    } else {
                        log.error("身份验证失败：不支持的主体类型：{}", principal.getClass().getName());
                        return null;
                    }
                })
                .orElse(null);

        if (actualUserId == null) {
            log.error("身份验证失败：用户名为空");
            return false;
        }

        // 身份匹配校验
        boolean isMatch = actualUserId.equals(senderId);
        if (!isMatch) {
            log.warn("会话劫持风险：实际用户{} 伪造发送者 {}", actualUserId, senderId);
        }
        return isMatch;
    }


    // ============================== 消息处理工具方法 ===============================
    /**
     * 消息安全处理（XSS防御+敏感词过滤）
     */
    private void processMessageSecurity(NewMessageVo newMessageVo) {
        String originalMsg = newMessageVo.getMessage();
        if (StringUtils.isEmpty(originalMsg)) {
            return;
        }

        // XSS防御：HTML特殊字符转义
        String escapedMsg = HtmlUtils.htmlEscape(originalMsg);

        // 图片消息跳过敏感词过滤
        if (MsgType.IMG.equals(newMessageVo.getMessageType())) {
            newMessageVo.setMessage(escapedMsg);
            return;
        }

        // 敏感词过滤（处理null结果）
        String[] filteredResult = Optional.ofNullable(sensitiveFilter.filter(originalMsg))
                .orElse(new String[]{escapedMsg, "0"});
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
                sensitiveMsg.setTime(formatTime(Instant.now()));
                sysService.addSensitiveMessage(sensitiveMsg);
                log.warn("消息含敏感词，senderId: {}, 原内容: {}", newMessageVo.getSenderId(), originalMsg);
            } catch (Exception e) {
                log.error("记录敏感消息异常", e);
            }
        }
    }

    /**
     * 处理会话类型特殊逻辑（单聊/群聊）
     */
    private boolean processConversationSpecialLogic(NewMessageVo newMessageVo, SocketIOClient client) {
        String conversationType = newMessageVo.getConversationType();
        if (ConstValueEnum.FRIEND.equals(conversationType)) {
            return processSingleChatLogic(newMessageVo, client);
        } else if (ConstValueEnum.GROUP.equals(conversationType)) {
            clearCardInfo(newMessageVo);
            return true;
        } else {
            sendSendFailedEvent(client, ErrorMsg.UNSUPPORTED_CONVERSATION_TYPE);
            return false;
        }
    }

    /**
     * 单聊特殊逻辑（好友验证+卡片生成）
     */
    private boolean processSingleChatLogic(NewMessageVo newMessageVo, SocketIOClient client) {
        String senderId = newMessageVo.getSenderId();
        String receiverId = newMessageVo.getReceiverId();

        // 校验发送者存在性
        User sender = userService.getUserInfo(senderId);
        if (sender == null) {
            sendSendFailedEvent(client, ErrorMsg.SENDER_NOT_EXIST);
            return false;
        }

        // 非客服校验好友关系
        boolean isCustomerService = UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(sender.getRole());
        if (!isCustomerService) {
            if (StringUtils.isEmpty(receiverId)) {
                sendSendFailedEvent(client, ErrorMsg.PARAM_INCOMPLETE);
                return false;
            }
            if (!goodFriendService.checkIsFriend(senderId, receiverId)) {
                sendSendFailedEvent(client, ErrorMsg.NOT_FRIEND);
                return false;
            }
        }

        // 生成服务卡片
        handleCardMessage(newMessageVo);
        return true;
    }

    /**
     * 清理卡片信息（群聊场景）
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
        // 发送者默认已读
        readUsers.add(newMessageVo.getSenderId());

        // 单聊：在线接收者加入已读列表
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())
                && !StringUtils.isEmpty(newMessageVo.getReceiverId())) {
            try {
                boolean isReceiverOnline = onlineUserService.checkCurUserIsOnline(newMessageVo.getReceiverId());
                if (isReceiverOnline) {
                    readUsers.add(newMessageVo.getReceiverId());
                }
            } catch (Exception e) {
                log.error("判断接收者在线状态异常，receiverId: {}", newMessageVo.getReceiverId(), e);
            }
        }

        newMessageVo.setIsReadUser(readUsers);
    }

    /**
     * 保存消息到数据库（区分单聊/群聊）
     */
    private void saveMessageToDatabase(NewMessageVo newMessageVo) {
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
            // 单聊消息
            SingleMessage singleMessage = new SingleMessage();
            BeanUtils.copyProperties(newMessageVo, singleMessage);
            singleMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            singleMessage.setTime(formatTime(Instant.now()));
            singleMessageService.addNewSingleMessage(singleMessage);
            log.debug("保存单聊消息，ID: {}", singleMessage.getId());
        } else if (ConstValueEnum.GROUP.equals(newMessageVo.getConversationType())) {
            // 群聊消息
            GroupMessage groupMessage = new GroupMessage();
            BeanUtils.copyProperties(newMessageVo, groupMessage);
            groupMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            groupMessage.setTime(formatTime(Instant.now()));
            groupMessageService.addNewGroupMessage(groupMessage);
            log.debug("保存群聊消息，ID: {}", groupMessage.getId());
        }
    }

    /**
     * 处理服务卡片生成（仅单聊）
     */
    private void handleCardMessage(NewMessageVo newMessageVo) {
        String message = newMessageVo.getMessage();
        if (StringUtils.isEmpty(message)) {
            return;
        }

        String senderId = newMessageVo.getSenderId();
        String receiverId = newMessageVo.getReceiverId();

        // 校验用户存在性
        User sender = userService.getUserInfo(senderId);
        User receiver = userService.getUserInfo(receiverId);
        if (sender == null || receiver == null) {
            log.warn("生成卡片失败：用户不存在，senderId: {}, receiverId: {}", senderId, receiverId);
            return;
        }

        // 校验是否为客服-用户聊天
        boolean isCustomerChat = (isCustomerRole(sender.getRole()) && isCustomerRole(receiver.getRole()))
                ? false
                : (isCustomerRole(sender.getRole()) || isCustomerRole(receiver.getRole()));
        if (!isCustomerChat) {
            log.debug("非客服-用户聊天，不生成卡片，senderRole: {}, receiverRole: {}",
                    sender.getRole(), receiver.getRole());
            clearCardInfo(newMessageVo);
            return;
        }

        // 关键字匹配生成卡片
        List<CardOptionVo> options = new ArrayList<>();
        if (message.contains("申请退款")) {
            options.add(new CardOptionVo("申请退款",
                    "/chat/order/refund?userId=" + senderId + "&token={token}", "POST"));
        }
        if (message.contains("查询订单")) {
            options.add(new CardOptionVo("查询订单",
                    "/chat/order/query?userId=" + senderId + "&token={token}", "GET"));
        }

        if (!options.isEmpty()) {
            newMessageVo.setCardType(MsgType.CARD);
            newMessageVo.setCardOptions(options);
            log.debug("生成服务卡片，senderId: {}, 选项数: {}", senderId, options.size());
        } else {
            clearCardInfo(newMessageVo);
        }
    }

    /**
     * 判断是否为客服角色
     */
    private boolean isCustomerRole(String roleCode) {
        return UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(roleCode);
    }


    // ============================== 事件发送工具方法 ===============================
    /**
     * 发送失败事件
     */
    private void sendSendFailedEvent(SocketIOClient client, String errorMsg) {
        sendEventToClient(client, EventName.SEND_FAILED, errorMsg);
    }

    /**
     * 发送事件给指定客户端（判断连接状态）
     */
    private void sendEventToClient(SocketIOClient client, String eventName, Object data) {
        if (client != null && client.isChannelOpen()) {
            client.sendEvent(eventName, data);
        }
    }

    /**
     * 转发消息给房间内其他客户端（排除发送者）
     */
    private void forwardMessageToOtherClients(SocketIOClient senderClient, String roomId, String eventName, Object data) {
        try {
            if (!isValidRoomId(roomId)) {
                log.warn("转发消息：无效房间ID，roomId: {}", roomId);
                return;
            }

            // 获取房间内所有客户端（排除发送者）
            Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(roomId).getClients();
            if (clients == null || clients.isEmpty()) {
                log.debug("转发消息：房间{}无其他客户端", roomId);
                return;
            }

            // 过滤发送者并发送消息
            String senderClientId = senderClient.getSessionId().toString();
            clients.stream()
                    .filter(Objects::nonNull)
                    .filter(client -> !client.getSessionId().toString().equals(senderClientId))
                    .forEach(client -> sendEventToClient(client, eventName, data));

        } catch (Exception e) {
            log.error("转发消息异常，roomId: {}, eventName: {}", roomId, eventName, e);
        }
    }

    /**
     * 广播在线用户列表
     */
    private void broadcastOnlineUser() {
        try {
            Set<String> onlineUids = onlineUserService.getOnlineUidSet();
            socketIOServer.getBroadcastOperations().sendEvent(EventName.ONLINE_USER, onlineUids);
            log.debug("广播在线用户列表，在线人数: {}", onlineUids.size());
        } catch (Exception e) {
            log.error("广播在线用户列表异常", e);
        }
    }

    /**
     * 打印当前在线用户数
     */
    private void printOnlineUserCount() {
        try {
            int count = onlineUserService.countOnlineUser();
            log.info("当前在线用户人数：{}", count);
        } catch (Exception e) {
            log.error("获取在线用户数异常", e);
        }
    }


    // ============================== 其他通用事件（简化转发逻辑）==============================
    /**
     * 加入房间事件
     */
    @OnEvent("join")
    public void join(SocketIOClient client, CurrentConversationVo conversationVo) {
        try {
            if (!validateClientAndParam(client, conversationVo) || StringUtils.isEmpty(conversationVo.getRoomId())) {
                sendEventToClient(client, EventName.JOIN_FAILED, ErrorMsg.PARAM_INCOMPLETE);
                return;
            }

            String roomId = conversationVo.getRoomId();
            if (!isValidRoomId(roomId)) {
                sendEventToClient(client, EventName.JOIN_FAILED, ErrorMsg.INVALID_ROOM_ID);
                return;
            }

            client.joinRoom(roomId);
            log.info("客户端加入房间，clientId: {}, roomId: {}", client.getSessionId(), roomId);
        } catch (Exception e) {
            log.error("处理加入房间事件异常", e);
            sendEventToClient(client, EventName.JOIN_FAILED, ErrorMsg.SERVER_EXCEPTION);
        }
    }

    /**
     * 用户离开事件
     */
    @OnEvent("leave")
    public void leave(SocketIOClient client) {
        try {
            if (client == null) {
                log.warn("用户离开：{}", ErrorMsg.CLIENT_NULL);
                return;
            }

            String clientId = client.getSessionId().toString();
            log.info("用户主动离开，clientId: {}", clientId);
            cleanLoginInfo(clientId);
            broadcastOnlineUser();
        } catch (Exception e) {
            log.error("处理用户离开事件异常", e);
        }
    }

    /**
     * 消息已读事件
     */
    @OnEvent(EventName.IS_READ_MSG)
    public void isReadMsg(SocketIOClient client, UserIsReadMsgRequestVo requestVo) {
        try {
            if (!validateClientAndParam(client, requestVo) || StringUtils.isEmpty(requestVo.getRoomId())) {
                log.warn("消息已读标记：{}", ErrorMsg.PARAM_INCOMPLETE);
                return;
            }

            String roomId = requestVo.getRoomId();
            if (!isValidRoomId(roomId)) {
                log.error("消息已读标记失败：{}，roomId: {}", ErrorMsg.INVALID_ROOM_ID, roomId);
                return;
            }

            log.info("消息已读标记，roomId: {}, request: {}", roomId, requestVo);
            forwardMessageToOtherClients(client, roomId, EventName.IS_READ_MSG, requestVo);
        } catch (Exception e) {
            log.error("处理消息已读事件异常", e);
            sendSendFailedEvent(client, ErrorMsg.SERVER_EXCEPTION);
        }
    }

    // 1v1通话事件转发（统一逻辑）
    @OnEvent(EventName.CALL_ANSWER)
    public void answer(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardCommonEvent(client, conversationVo, EventName.CALL_ANSWER);
    }

    @OnEvent(EventName.CALL_ICE)
    public void ice(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardCommonEvent(client, conversationVo, EventName.CALL_ICE);
    }

    @OnEvent(EventName.CALL_OFFER)
    public void offer(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardCommonEvent(client, conversationVo, EventName.CALL_OFFER);
    }

    @OnEvent(EventName.CALL_HANGUP)
    public void hangup(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardCommonEvent(client, conversationVo, EventName.CALL_HANGUP);
    }

    // 通用事件转发
    @OnEvent(EventName.APPLY)
    public void apply(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardCommonEvent(client, conversationVo, EventName.APPLY);
    }

    @OnEvent(EventName.REPLY)
    public void reply(SocketIOClient client, CurrentConversationVo conversationVo) {
        forwardCommonEvent(client, conversationVo, EventName.REPLY);
    }

    /**
     * 通用事件转发（简化重复代码）
     */
    private void forwardCommonEvent(SocketIOClient client, CurrentConversationVo conversationVo, String eventName) {
        try {
            if (!validateClientAndParam(client, conversationVo) || StringUtils.isEmpty(conversationVo.getRoomId())) {
                log.warn("转发事件{}：{}", eventName, ErrorMsg.PARAM_INCOMPLETE);
                return;
            }

            String roomId = conversationVo.getRoomId();
            if (!isValidRoomId(roomId)) {
                log.error("转发事件{}失败：{}，roomId: {}", eventName, ErrorMsg.INVALID_ROOM_ID, roomId);
                return;
            }

            log.debug("转发事件{}，roomId: {}", eventName, roomId);
            forwardMessageToOtherClients(client, roomId, eventName, conversationVo);
        } catch (Exception e) {
            log.error("转发事件{}异常", eventName, e);
            sendSendFailedEvent(client, ErrorMsg.SERVER_EXCEPTION);
        }
    }
}