//package com.zzw.chatserver.listen;
//
//import com.corundumstudio.socketio.SocketIOClient;
//import com.corundumstudio.socketio.SocketIOServer;
//import com.corundumstudio.socketio.annotation.OnConnect;
//import com.corundumstudio.socketio.annotation.OnDisconnect;
//import com.corundumstudio.socketio.annotation.OnEvent;
//import com.google.common.util.concurrent.Striped;
//import com.zzw.chatserver.common.ConstValueEnum;
//import com.zzw.chatserver.common.UserRoleEnum;
//import com.zzw.chatserver.filter.SensitiveFilter;
//import com.zzw.chatserver.pojo.*;
//import com.zzw.chatserver.pojo.vo.*;
//import com.zzw.chatserver.service.*;
//import com.zzw.chatserver.utils.DateUtil;
//import com.zzw.chatserver.utils.ValidationUtil;
//import lombok.extern.slf4j.Slf4j;
//import org.bson.types.ObjectId;
//import org.springframework.beans.BeanUtils;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.security.core.Authentication;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.stereotype.Component;
//import org.springframework.transaction.annotation.Transactional;
//import org.springframework.util.StringUtils;
//import org.springframework.web.util.HtmlUtils;
//import java.util.concurrent.locks.ReentrantLock;
//
//import javax.annotation.Resource;
//import java.time.Instant;
//import java.time.ZoneId;
//import java.time.format.DateTimeFormatter;
//import java.util.*;
//import java.util.concurrent.Executor;
//import java.util.concurrent.TimeUnit;
//import java.util.concurrent.locks.Lock;
//
///**
// * SocketIO事件监听器（优化：细粒度锁+异步化）
// */
//@Component
//@Transactional(rollbackFor = Throwable.class)
//@Slf4j
//public class SocketIoListener {
//
//    // ======================== 细粒度锁（替代全局锁） ========================
//    // Striped锁：按用户ID哈希分片（16个锁槽），不同用户操作互不阻塞
//    private final Striped<Lock> userLock = Striped.lock(16);
//    // 全局锁降级为房间锁（仅房间相关操作使用，减少范围）
//    private final Striped<Lock> roomLock = Striped.lock(8);
//
//    // ======================== 异步线程池（处理非核心流程） ========================
//    @Resource(name = "socketAsyncExecutor") // 注入专门的异步线程池
//    private Executor socketAsyncExecutor;
//
//    // 事件名称常量（不变）
//    private static final String EVENT_ONLINE_USER = "onlineUser";
//    private static final String EVENT_RECONNECT_SUCCESS = "reconnectSuccess";
//    private static final String EVENT_OFFLINE_SINGLE_MSG = "offlineSingleMessages";
//    private static final String EVENT_OFFLINE_GROUP_MSG_PREFIX = "offlineGroupMessages_";
//    private static final String EVENT_SEND_FAILED = "sendFailed";
//    private static final String EVENT_RECEIVE_MSG = "receiveMessage";
//    private static final String EVENT_JOIN_FAILED = "joinFailed";
//    private static final String EVENT_IS_READ_MSG = "isReadMsg";
//    private static final String EVENT_RECEIVE_VALIDATE_MSG = "receiveValidateMessage";
//    private static final String EVENT_RECEIVE_AGREE_FRIEND = "receiveAgreeFriendValidate";
//    private static final String EVENT_RECEIVE_AGREE_GROUP = "receiveAgreeGroupValidate";
//    private static final String EVENT_RECEIVE_DEL_FRIEND = "receiveDelGoodFriend";
//    private static final String EVENT_RECEIVE_QUIT_GROUP = "receiveQuitGroup";
//    private static final String EVENT_CONFIRM_RECEIVE = "confirmReceive";
//
//    // 错误信息常量（不变）
//    private static final String ERR_INVALID_ROOM_ID = "房间ID格式错误";
//    private static final String ERR_INVALID_SENDER_ID = "发送者ID格式错误";
//    private static final String ERR_INVALID_RECEIVER_ID = "接收者ID格式错误";
//    private static final String ERR_IDENTITY_VERIFY_FAILED = "身份验证失败";
//    private static final String ERR_SENDER_NOT_EXIST = "发送者信息不存在";
//    private static final String ERR_PARAM_INCOMPLETE = "参数不完整";
//    private static final String ERR_NOT_FRIEND = "非好友关系，无法发送消息";
//    private static final String ERR_SERVER_EXCEPTION = "服务器异常，请稍后重试";
//
//    // 心跳和过期时间常量（不变）
//    private static final long HEARTBEAT_EXPIRATION_MS = 3600000; // 1小时过期
//    private static final long HEARTBEAT_INTERVAL_MS = 1800000; // 30分钟心跳间隔
//
//    /** 统一时间格式化器（不变） */
//    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
//            .ofPattern("yyyy-MM-dd HH:mm:ss")
//            .withZone(ZoneId.of("Asia/Shanghai"));
//
//    // 依赖注入（不变）
//    @Resource
//    private SocketIOServer socketIOServer;
//    @Resource
//    private GroupUserService groupUserService;
//    @Resource
//    private GoodFriendService goodFriendService;
//    @Resource
//    private ValidateMessageService validateMessageService;
//    @Resource
//    private GroupMessageService groupMessageService;
//    @Resource
//    private SingleMessageService singleMessageService;
//    @Resource
//    private UserService userService;
//    @Resource
//    private SensitiveFilter sensitiveFilter;
//    @Resource
//    private OnlineUserService onlineUserService;
//    @Resource
//    private SysService sysService;
//
//    // ======================== 定时清理（细粒度锁替代全局锁） ========================
//    @Scheduled(fixedRate = 3600000)
//    public void cleanExpiredClients() {
//        try {
//            // 1. 获取所有过期客户端ID（调用OnlineUserService的实现）
//            long HEARTBEAT_EXPIRATION_MS = 3600000;
//            List<String> expiredClientIds = onlineUserService.getExpiredClientIds(HEARTBEAT_EXPIRATION_MS);
//            if (expiredClientIds.isEmpty()) {
//                log.info("定时清理过期客户端：无过期客户端");
//                return;
//            }
//
//            // 2. 按用户ID分组（将同一用户的多个客户端归为一组）
//            Map<String, List<String>> uidToClients = new java.util.HashMap<>();
//            for (String clientId : expiredClientIds) {
//                String uid = onlineUserService.getUidByClientId(clientId);
//                if (uid != null) {
//                    uidToClients.computeIfAbsent(uid, k -> new java.util.ArrayList<>()).add(clientId);
//                }
//            }
//
//            // 3. 每个用户的客户端清理单独加锁，互不影响（修复锁方法调用）
//            for (Map.Entry<String, List<String>> entry : uidToClients.entrySet()) {
//                String uid = entry.getKey();
//                List<String> clientIds = entry.getValue();
//
//                // 3.1 获取细粒度锁（Striped返回的是Lock接口，需转换为ReentrantLock）
//                Lock lock = userLock.get(uid);
//                ReentrantLock reentrantLock = (ReentrantLock) lock; // 显式转换为实现类
//
//                try {
//                    // 3.2 尝试获取锁（3秒超时，避免死锁）
//                    if (reentrantLock.tryLock(3, TimeUnit.SECONDS)) {
//                        // 异步清理当前用户的所有过期客户端（不阻塞定时任务）
//                        for (String clientId : clientIds) {
//                            cleanLoginInfoAsync(clientId);
//                        }
//                    } else {
//                        log.warn("用户[{}]清理超时：3秒内未获取到锁，跳过该用户的{}个客户端", uid, clientIds.size());
//                    }
//                } catch (InterruptedException e) {
//                    // 处理线程中断（恢复中断状态，避免丢失中断信号）
//                    log.error("用户[{}]获取锁被中断", uid, e);
//                    Thread.currentThread().interrupt();
//                } finally {
//                    // 3.3 安全释放锁（仅当前线程持有锁时才释放）
//                    if (reentrantLock.isHeldByCurrentThread()) {
//                        reentrantLock.unlock();
//                    }
//                }
//            }
//
//            // 4. 异步广播在线用户变化（非核心流程）
//            socketAsyncExecutor.execute(this::broadcastOnlineUser);
//            log.info("定时清理过期客户端完成，总计待清理：{}个，实际分组处理：{}个用户",
//                    expiredClientIds.size(), uidToClients.size());
//        } catch (Exception e) {
//            log.error("定时清理过期客户端失败", e);
//        }
//    }
//
//
//    // ======================== 优化2：断开事件（异步化+细粒度锁） ========================
//    @OnDisconnect
//    public void onDisconnect(SocketIOClient client) {
//        if (client == null) {
//            log.warn("客户端断开连接：客户端为空，跳过处理");
//            return;
//        }
//
//        String clientId = client.getSessionId().toString();
//        log.info("客户端断开连接，clientId: {}", clientId);
//
//        // 核心优化：异步处理清理逻辑（不阻塞SocketIO线程）
//        socketAsyncExecutor.execute(() -> {
//            try {
//                // 1. 先获取客户端绑定的用户ID（无锁操作）
//                String uid = onlineUserService.getUidByClientId(clientId);
//                if (uid == null) {
//                    log.warn("客户端{}无绑定用户，直接清理", clientId);
//                    return;
//                }
//
//                // 2. 按用户ID加细粒度锁（转换为ReentrantLock，解决方法调用问题）
//                Lock lock = userLock.get(uid);
//                ReentrantLock reentrantLock = (ReentrantLock) lock; // 显式转换为实现类
//                try {
//                    if (reentrantLock.tryLock(5, TimeUnit.SECONDS)) { // 超时5秒放弃
//                        cleanLoginInfo(clientId); // 同步清理（确保数据一致性）
//                        log.info("客户端{}绑定用户{}的清理完成", clientId, uid);
//                    } else {
//                        log.error("客户端{}清理超时：获取用户{}锁失败", clientId, uid);
//                    }
//                } catch (InterruptedException e) {
//                    // 处理线程中断，恢复中断状态
//                    log.error("客户端{}获取锁被中断（用户{}）", clientId, uid, e);
//                    Thread.currentThread().interrupt();
//                } finally {
//                    // 使用ReentrantLock的特有方法判断锁持有状态，避免接口方法报错
//                    if (reentrantLock.isHeldByCurrentThread()) {
//                        reentrantLock.unlock();
//                    }
//                }
//
//                // 3. 异步广播在线用户变化（非核心，不阻塞清理流程）
//                socketAsyncExecutor.execute(this::broadcastOnlineUser);
//                log.info("连接关闭，url参数: {}", client.getHandshakeData().getUrlParams());
//            } catch (Exception e) {
//                log.error("处理客户端{}断开连接异常", clientId, e);
//            }
//        });
//    }
//
//
//    // ======================== 优化3：连接事件（异步化+细粒度锁） ========================
//    @OnConnect
//    public void onConnect(SocketIOClient client) {
//        if (client == null) {
//            log.warn("客户端连接：客户端为空，跳过处理");
//            return;
//        }
//
//        // 1. 提取UID（轻量操作，同步执行）
//        String uid = extractUidFromParams(client.getHandshakeData().getUrlParams());
//        if (uid == null) {
//            log.warn("客户端连接：未获取到UID，拒绝连接");
//            client.disconnect();
//            return;
//        }
//        log.info("客户端连接/重连，UID: {}", uid);
//
//        // 2. 异步处理核心逻辑（避免阻塞SocketIO连接线程）
//        socketAsyncExecutor.execute(() -> {
//            try {
//                // 3. 校验用户存在性（轻量查询，同步执行）
//                User user = userService.getUserInfo(uid);
//                if (user == null) {
//                    log.error("用户不存在，UID: {}", uid);
//                    client.disconnect();
//                    return;
//                }
//
//                // 4. 按用户ID加细粒度锁（转换为ReentrantLock解决方法调用问题）
//                Lock lock = userLock.get(uid);
//                ReentrantLock reentrantLock = (ReentrantLock) lock; // 显式转换为实现类
//                try {
//                    if (reentrantLock.tryLock(5, TimeUnit.SECONDS)) {
//                        // 同步绑定客户端与用户（确保数据一致性）
//                        SimpleUser simpleUser = new SimpleUser();
//                        BeanUtils.copyProperties(user, simpleUser);
//                        onlineUserService.addClientIdToSimpleUser(client.getSessionId().toString(), simpleUser);
//                        log.info("用户{}绑定客户端{}完成", uid, client.getSessionId());
//                    } else {
//                        log.error("用户{}连接超时：获取锁失败", uid);
//                        client.disconnect();
//                        return;
//                    }
//                } catch (InterruptedException e) {
//                    // 处理线程中断，恢复中断状态
//                    log.error("用户{}获取锁被中断", uid, e);
//                    Thread.currentThread().interrupt();
//                    client.disconnect();
//                    return;
//                } finally {
//                    // 使用ReentrantLock的特有方法判断锁持有状态
//                    if (reentrantLock.isHeldByCurrentThread()) {
//                        reentrantLock.unlock();
//                    }
//                }
//
//                // 5. 异步加入历史房间（耗时操作，不阻塞连接确认）
//                socketAsyncExecutor.execute(() -> joinUserRooms(client, uid));
//
//                // 6. 同步发送重连成功通知（即时反馈，用户感知）
//                client.sendEvent(EVENT_RECONNECT_SUCCESS, "重连成功");
//
//                // 7. 异步广播在线用户变化（非核心，不阻塞连接反馈）
//                socketAsyncExecutor.execute(this::broadcastOnlineUser);
//                printOnlineUserCount(); // 异步打印在线数
//            } catch (Exception e) {
//                log.error("处理客户端{}连接异常", client.getSessionId(), e);
//                client.disconnect();
//            }
//        });
//    }
//
//
//    // ======================== 其他方法优化（细粒度锁+异步化） ========================
//    /**
//     * 清理用户登录信息（同步执行，确保数据一致性）
//     */
//    private void cleanLoginInfo(String clientId) {
//        if (clientId == null) {
//            log.warn("清理登录信息：clientId为空，跳过处理");
//            return;
//        }
//
//        try {
//            // 1. 先获取用户信息（无锁操作，仅查询）
//            SimpleUser simpleUser = onlineUserService.getSimpleUserByClientId(clientId);
//            if (simpleUser == null) {
//                log.info("客户端{}无绑定用户，无需清理", clientId);
//                printOnlineUserCount();
//                return;
//            }
//
//            String uid = simpleUser.getUid();
//            // 2. 按用户ID获取细粒度锁（仅锁定当前用户，不影响其他用户）
//            Lock lock = userLock.get(uid);
//            ReentrantLock reentrantLock = (ReentrantLock) lock;
//
//            try {
//                // 3. 尝试获取锁（3秒超时，避免死锁）
//                if (reentrantLock.tryLock(3, TimeUnit.SECONDS)) {
//                    // 同步清理绑定关系（确保原子性）
//                    onlineUserService.removeClientAndUidInSet(clientId, uid);
//
//                    // 同步更新在线时长（DB操作，需保证一致性）
//                    long onlineTime = DateUtil.getTimeDelta(
//                            Date.from(Instant.parse(simpleUser.getLastLoginTime())),
//                            new Date()
//                    );
//                    userService.updateOnlineTime(onlineTime, uid);
//                    log.info("用户{}的客户端{}清理完成，在线时长：{}ms", uid, clientId, onlineTime);
//                } else {
//                    log.warn("用户{}的客户端{}清理超时：3秒内未获取到锁", uid, clientId);
//                }
//            } catch (InterruptedException e) {
//                log.error("用户{}的客户端{}清理被中断", uid, clientId, e);
//                Thread.currentThread().interrupt(); // 恢复中断状态
//            } finally {
//                // 4. 安全释放锁（仅当前线程持有锁时）
//                if (reentrantLock.isHeldByCurrentThread()) {
//                    reentrantLock.unlock();
//                }
//            }
//
//            // 轻量化操作：打印在线数（无需加锁）
//            printOnlineUserCount();
//        } catch (Exception e) {
//            log.error("清理用户登录信息异常，clientId={}", clientId, e);
//        }
//    }
//
//    /**
//     * 异步清理登录信息（供定时任务调用）
//     */
//    private void cleanLoginInfoAsync(String clientId) {
//        socketAsyncExecutor.execute(() -> {
//            try {
//                // 调用原有清理逻辑（如移除客户端绑定、更新在线时长等）
//                String uid = onlineUserService.getUidByClientId(clientId);
//                if (uid != null) {
//                    onlineUserService.removeClientAndUidInSet(clientId, uid);
//                    log.debug("异步清理客户端[{}]完成（用户[{}]）", clientId, uid);
//                }
//            } catch (Exception e) {
//                log.error("异步清理客户端[{}]异常", clientId, e);
//            }
//        });
//    }
//
//    /**
//     * 让客户端加入用户参与的所有房间（异步执行，耗时操作）
//     */
//    private void joinUserRooms(SocketIOClient client, String uid) {
//        if (client == null || uid == null) {
//            log.warn("加入房间：客户端或UID为空");
//            return;
//        }
//
//        try {
//            // 1. 获取用户参与的所有房间ID（实现具体逻辑）
//            List<String> roomIds = getRoomsByUid(uid);
//            if (roomIds.isEmpty()) {
//                log.info("用户{}无历史房间，无需重新加入", uid);
//                return;
//            }
//
//            // 2. 按房间ID加细粒度锁，避免并发冲突
//            for (String roomId : roomIds) {
//                if (roomId == null || roomId.trim().isEmpty()) {
//                    continue;
//                }
//
//                // 转换为ReentrantLock以调用特有方法
//                Lock lock = roomLock.get(roomId);
//                ReentrantLock reentrantLock = (ReentrantLock) lock;
//
//                try {
//                    // 尝试获取锁（2秒超时）
//                    if (reentrantLock.tryLock(2, TimeUnit.SECONDS)) {
//                        client.joinRoom(roomId);
//                        log.info("用户{}重新加入房间：{}", uid, roomId);
//                    } else {
//                        log.warn("用户{}加入房间{}超时：获取锁失败", uid, roomId);
//                    }
//                } catch (InterruptedException e) {
//                    log.error("用户{}加入房间{}时线程被中断", uid, roomId, e);
//                    Thread.currentThread().interrupt(); // 恢复中断状态
//                } finally {
//                    // 仅当前线程持有锁时才释放
//                    if (reentrantLock.isHeldByCurrentThread()) {
//                        reentrantLock.unlock();
//                    }
//                }
//            }
//        } catch (Exception e) {
//            log.error("用户{}加入历史房间异常", uid, e);
//        }
//    }
//
//    /**
//     * 实现：获取用户参与的所有房间ID（单聊+群聊）
//     * 依赖项目原有服务：GoodFriendService（好友单聊）、GroupUserService（群聊）
//     */
//    private List<String> getRoomsByUid(String uid) {
//        List<String> roomIds = new ArrayList<>();
//        if (uid == null) {
//            log.warn("查询房间：用户UID为空，返回空列表");
//            return roomIds;
//        }
//
//        try {
//            // 1. 单聊房间：从好友关系中获取（基于项目原有GoodFriendService）
//            List<MyFriendListResultVo> friends = goodFriendService.getMyFriendsList(uid);
//            if (friends != null && !friends.isEmpty()) {
//                for (MyFriendListResultVo friend : friends) {
//                    if (friend != null && !StringUtils.isEmpty(friend.getRoomId())) {
//                        roomIds.add(friend.getRoomId().trim());
//                        log.debug("用户{}的单聊房间：{}", uid, friend.getRoomId());
//                    }
//                }
//            }
//
//            // 2. 群聊房间：从用户加入的群组中获取（基于项目原有GroupUserService）
//            // 先获取用户信息（需用户名查询群组）
//            User user = userService.getUserInfo(uid);
//            if (user != null && !StringUtils.isEmpty(user.getUsername())) {
//                List<MyGroupResultVo> userGroups = groupUserService.getGroupUsersByUserName(user.getUsername());
//                if (userGroups != null && !userGroups.isEmpty()) {
//                    for (MyGroupResultVo group : userGroups) {
//                        if (group != null && group.getGroupId() != null) {
//                            // 群聊房间ID直接使用群组ID的字符串形式（贴合项目原有逻辑）
//                            String groupRoomId = group.getGroupId().toString().trim();
//                            roomIds.add(groupRoomId);
//                            log.debug("用户{}的群聊房间：{}", uid, groupRoomId);
//                        }
//                    }
//                }
//            } else {
//                log.warn("查询群聊房间：用户{}信息不存在或用户名为空", uid);
//            }
//
//        } catch (Exception e) {
//            log.error("查询用户{}的房间列表异常", uid, e);
//            // 异常时返回空列表，避免影响后续流程
//            return Collections.emptyList();
//        }
//
//        return roomIds;
//    }
//
//    /**
//     * 广播在线用户列表（异步执行，避免阻塞）
//     */
//    private void broadcastOnlineUser() {
//        socketAsyncExecutor.execute(() -> {
//            try {
//                socketIOServer.getBroadcastOperations().sendEvent(EVENT_ONLINE_USER, onlineUserService.getOnlineUidSet());
//                log.debug("在线用户列表广播完成");
//            } catch (Exception e) {
//                log.error("广播在线用户列表异常", e);
//            }
//        });
//    }
//
//
//    // ======================== 原有方法（无修改，保持逻辑不变） ========================
//    private String extractUidFromParams(Map<String, List<String>> urlParams) {
//        if (urlParams == null || !urlParams.containsKey("uid") || urlParams.get("uid").isEmpty()) {
//            return null;
//        }
//        return urlParams.get("uid").get(0);
//    }
//
//    private void printOnlineUserCount() {
//        try {
//            log.info("当前在线用户人数：{}", onlineUserService.countOnlineUser());
//        } catch (Exception e) {
//            log.error("获取在线用户数异常", e);
//        }
//    }
//
//    @OnEvent("goOnline")
//    public void goOnline(SocketIOClient client, User user) {
//        try {
//            if (!validateClientAndUser(client, user)) {
//                log.warn("用户上线：客户端或用户信息不完整");
//                return;
//            }
//            String clientId = client.getSessionId().toString();
//            String uid = user.getUid();
//            log.info("用户上线，user：{}", user);
//
//            // 替换全局锁为细粒度锁，并修复锁方法调用问题
//            Lock lock = userLock.get(uid);
//            ReentrantLock reentrantLock = (ReentrantLock) lock; // 显式转换为ReentrantLock
//
//            try {
//                if (reentrantLock.tryLock(5, TimeUnit.SECONDS)) {
//                    cleanOldClientBinding(uid, clientId);
//                    SimpleUser simpleUser = new SimpleUser();
//                    BeanUtils.copyProperties(user, simpleUser);
//                    onlineUserService.addClientIdToSimpleUser(clientId, simpleUser);
//                } else {
//                    log.error("用户{}上线超时：获取锁失败", uid);
//                    return;
//                }
//            } catch (InterruptedException e) {
//                log.error("用户{}上线时获取锁被中断", uid, e);
//                Thread.currentThread().interrupt(); // 恢复中断状态
//                return;
//            } finally {
//                // 使用ReentrantLock的特有方法判断锁持有状态
//                if (reentrantLock.isHeldByCurrentThread()) {
//                    reentrantLock.unlock();
//                }
//            }
//
//            printOnlineUserCount();
//            socketAsyncExecutor.execute(this::broadcastOnlineUser);
//            socketAsyncExecutor.execute(() -> pushOfflineMessages(client, uid));
//        } catch (Exception e) {
//            log.error("处理用户上线事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//    /**
//     * 处理客户端消息接收确认
//     */
//    @OnEvent(EVENT_CONFIRM_RECEIVE)
//    public void confirmReceive(SocketIOClient client, MessageConfirmVo confirmVo) {
//        try {
//            if (client == null || confirmVo == null || StringUtils.isEmpty(confirmVo.getUserId())) {
//                log.warn("消息确认：客户端或确认信息不完整");
//                return;
//            }
//
//            log.info("收到消息确认，userId={}, singleMessageIds={}, groupMessageIds={}",
//                    confirmVo.getUserId(), confirmVo.getSingleMessageIds(), confirmVo.getGroupMessageIds());
//
//            // 标记单聊消息为已读（业务逻辑不变）
//            if (confirmVo.getSingleMessageIds() != null && !confirmVo.getSingleMessageIds().isEmpty()) {
//                singleMessageService.markMessagesAsRead(confirmVo.getUserId(), confirmVo.getSingleMessageIds());
//            }
//
//            // 标记群聊消息为已读（业务逻辑不变）
//            if (confirmVo.getGroupMessageIds() != null && !confirmVo.getGroupMessageIds().isEmpty()) {
//                for (Map.Entry<String, List<String>> entry : confirmVo.getGroupMessageIds().entrySet()) {
//                    String roomId = entry.getKey();
//                    List<String> messageIds = entry.getValue();
//                    if (!StringUtils.isEmpty(roomId) && messageIds != null && !messageIds.isEmpty()) {
//                        groupMessageService.markGroupMessagesAsRead(roomId, confirmVo.getUserId(), messageIds);
//                    }
//                }
//            }
//        } catch (Exception e) {
//            log.error("处理消息确认异常", e);
//        }
//    }
//
//    /**
//     * 校验客户端和用户信息是否完整（无修改）
//     */
//    private boolean validateClientAndUser(SocketIOClient client, User user) {
//        return client != null && user != null && !StringUtils.isEmpty(user.getUid());
//    }
//
//    /**
//     * 清理用户旧的客户端绑定（重连场景）
//     * 优化：全局锁 → 细粒度用户锁，增加锁超时和中断处理
//     */
//    private void cleanOldClientBinding(String uid, String newClientId) {
//        if (uid == null || newClientId == null) {
//            log.warn("清理旧绑定：uid或newClientId为空，uid={}, newClientId={}", uid, newClientId);
//            return;
//        }
//
//        // 按用户ID获取细粒度锁（替换原全局onlineUserLock）
//        Lock lock = userLock.get(uid);
//        ReentrantLock reentrantLock = (ReentrantLock) lock;
//
//        try {
//            // 尝试获取锁（3秒超时，避免死锁）
//            if (reentrantLock.tryLock(3, TimeUnit.SECONDS)) {
//                String oldClientId = onlineUserService.getClientIdByUid(uid);
//                if (oldClientId != null && !oldClientId.equals(newClientId)) {
//                    onlineUserService.removeClientAndUidInSet(oldClientId, uid);
//                    log.info("清理用户[{}]的旧客户端绑定：{}", uid, oldClientId);
//                }
//            } else {
//                log.warn("用户[{}]清理旧绑定超时：3秒内未获取到锁", uid);
//            }
//        } catch (InterruptedException e) {
//            log.error("用户[{}]清理旧绑定时线程被中断", uid, e);
//            Thread.currentThread().interrupt(); // 恢复中断状态
//        } finally {
//            // 仅当前线程持有锁时才释放（避免非法释放）
//            if (reentrantLock.isHeldByCurrentThread()) {
//                reentrantLock.unlock();
//            }
//        }
//    }
//
//    /**
//     * 推送离线消息（单聊+群聊）（无锁修改，仅优化日志）
//     */
//    private void pushOfflineMessages(SocketIOClient client, String uid) {
//        try {
//            log.info("用户{}上线，开始推送离线消息", uid);
//
//            // 推送单聊离线消息（异步执行，避免阻塞）
//            socketAsyncExecutor.execute(() -> pushOfflineSingleMessages(client, uid));
//            // 推送群聊离线消息（异步执行）
//            socketAsyncExecutor.execute(() -> pushOfflineGroupMessages(client, uid));
//        } catch (Exception e) {
//            log.error("推送离线消息异常，uid={}", uid, e);
//        }
//    }
//
//    /**
//     * 推送单聊离线消息（不立即标记为已读）（无修改）
//     */
//    private void pushOfflineSingleMessages(SocketIOClient client, String uid) {
//        try {
//            List<SingleMessageResultVo> offlineMsgs = singleMessageService.getUnreadMessages(uid);
//            if (offlineMsgs != null && !offlineMsgs.isEmpty()) {
//                client.sendEvent(EVENT_OFFLINE_SINGLE_MSG, offlineMsgs);
//                log.info("推送单聊离线消息{}条给用户{}", offlineMsgs.size(), uid);
//                // 不立即标记为已读，等待客户端确认
//            }
//        } catch (Exception e) {
//            log.error("推送单聊离线消息异常，uid={}", uid, e);
//        }
//    }
//
//    /**
//     * 推送群聊离线消息（不立即标记为已读）（无修改）
//     */
//    private void pushOfflineGroupMessages(SocketIOClient client, String uid) {
//        try {
//            List<String> userGroupRooms = getRoomsByUid(uid);
//            if (userGroupRooms == null || userGroupRooms.isEmpty()) {
//                return;
//            }
//            for (String roomId : userGroupRooms) {
//                if (roomId == null) continue;
//                List<GroupMessageResultVo> offlineMsgs = groupMessageService.getUnreadGroupMessages(roomId, uid);
//                if (offlineMsgs != null && !offlineMsgs.isEmpty()) {
//                    client.sendEvent(EVENT_OFFLINE_GROUP_MSG_PREFIX + roomId, offlineMsgs);
//                    log.info("推送群聊[{}]离线消息{}条给用户{}", roomId, offlineMsgs.size(), uid);
//                    // 不立即标记为已读，等待客户端确认
//                }
//            }
//        } catch (Exception e) {
//            log.error("推送群聊离线消息异常，uid={}", uid, e);
//        }
//    }
//
//    /**
//     * 客户端心跳事件
//     * 优化：全局锁 → 细粒度用户锁，增加锁安全处理
//     */
//    @OnEvent("heartbeat")
//    public void handleHeartbeat(SocketIOClient client) {
//        if (client == null) {
//            log.warn("客户端心跳：客户端为空，跳过处理");
//            return;
//        }
//
//        String clientId = client.getSessionId().toString();
//        try {
//            SimpleUser user = onlineUserService.getSimpleUserByClientId(clientId);
//            if (user == null) {
//                log.warn("客户端[{}]心跳验证失败：未找到绑定用户", clientId);
//                return;
//            }
//
//            String uid = user.getUid();
//            // 按用户ID获取细粒度锁（替换原全局onlineUserLock）
//            Lock lock = userLock.get(uid);
//            ReentrantLock reentrantLock = (ReentrantLock) lock;
//
//            try {
//                // 尝试获取锁（2秒超时，心跳场景不宜阻塞过久）
//                if (reentrantLock.tryLock(2, TimeUnit.SECONDS)) {
//                    onlineUserService.renewExpiration(clientId, uid, HEARTBEAT_EXPIRATION_MS);
//                    log.debug("客户端[{}]心跳续期成功，用户：{}，过期时间：{}ms",
//                            clientId, uid, HEARTBEAT_EXPIRATION_MS);
//                } else {
//                    log.warn("客户端[{}]心跳续期超时：用户{}锁竞争激烈", clientId, uid);
//                }
//            } catch (InterruptedException e) {
//                log.error("客户端[{}]心跳处理被中断，用户{}", clientId, uid, e);
//                Thread.currentThread().interrupt();
//            } finally {
//                if (reentrantLock.isHeldByCurrentThread()) {
//                    reentrantLock.unlock();
//                }
//            }
//        } catch (Exception e) {
//            log.error("处理客户端[{}]心跳异常", clientId, e);
//        }
//    }
//
//    /**
//     * 用户主动离开事件
//     * 优化：广播操作异步执行，避免阻塞
//     */
//    @OnEvent("leave")
//    public void leave(SocketIOClient client) {
//        try {
//            if (client == null) {
//                log.warn("用户离开：客户端为空，跳过处理");
//                return;
//            }
//
//            String clientId = client.getSessionId().toString();
//            log.info("用户离开，clientId：{}", clientId);
//
//            // 1. 同步清理登录信息（需保证数据一致性）
//            cleanLoginInfo(clientId);
//            // 2. 异步广播在线用户变化（非核心流程，不阻塞）
//            socketAsyncExecutor.execute(this::broadcastOnlineUser);
//        } catch (Exception e) {
//            log.error("处理用户离开事件异常", e);
//        }
//    }
//
//    /**
//     * 消息已读事件（优化：房间操作加细粒度锁）
//     */
//    @OnEvent("isReadMsg")
//    public void isReadMsg(SocketIOClient client, UserIsReadMsgRequestVo requestVo) {
//        try {
//            // 参数校验（原有逻辑不变）
//            if (!validateClientAndRequest(client, requestVo) || StringUtils.isEmpty(requestVo.getRoomId())) {
//                log.warn("消息已读标记：客户端或请求参数不完整");
//                return;
//            }
//            String roomId = requestVo.getRoomId();
//            // 房间ID格式校验（原有逻辑不变）
//            if (!ValidationUtil.isValidRoomId(roomId)) {
//                log.error("消息已读标记失败：房间ID格式非法，roomId={}", roomId);
//                return;
//            }
//            log.info("消息已读标记，requestVo：{}", requestVo);
//
//            // 优化：房间操作加细粒度锁，避免并发广播冲突
//            Lock lock = roomLock.get(roomId);
//            ReentrantLock reentrantLock = (ReentrantLock) lock;
//
//            try {
//                if (reentrantLock.tryLock(2, TimeUnit.SECONDS)) {
//                    // 发送给房间内其他客户端（原有逻辑不变）
//                    sendToOtherClients(client, roomId, EVENT_IS_READ_MSG, requestVo);
//                } else {
//                    log.warn("房间[{}]消息已读广播超时：获取锁失败", roomId);
//                }
//            } catch (InterruptedException e) {
//                log.error("房间[{}]消息已读处理被中断", roomId, e);
//                Thread.currentThread().interrupt();
//            } finally {
//                if (reentrantLock.isHeldByCurrentThread()) {
//                    reentrantLock.unlock();
//                }
//            }
//        } catch (Exception e) {
//            log.error("处理消息已读事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 加入房间事件（优化：房间操作加细粒度锁）
//     */
//    @OnEvent("join")
//    public void join(SocketIOClient client, CurrentConversationVo conversationVo) {
//        try {
//            if (conversationVo == null || StringUtils.isEmpty(conversationVo.getRoomId())) {
//                log.warn("加入房间：房间ID为空");
//                client.sendEvent(EVENT_JOIN_FAILED, ERR_PARAM_INCOMPLETE);
//                return;
//            }
//            String roomId = conversationVo.getRoomId();
//            // 房间ID格式校验（原有逻辑不变）
//            if (!ValidationUtil.isValidRoomId(roomId)) {
//                log.error("加入房间失败：房间ID格式非法，roomId={}", roomId);
//                client.sendEvent(EVENT_JOIN_FAILED, ERR_INVALID_ROOM_ID);
//                return;
//            }
//            log.info("加入房间，roomId：{}，conversationVo：{}", roomId, conversationVo);
//
//            // 优化：加入房间加细粒度锁，避免并发加入冲突
//            Lock lock = roomLock.get(roomId);
//            ReentrantLock reentrantLock = (ReentrantLock) lock;
//
//            try {
//                if (reentrantLock.tryLock(2, TimeUnit.SECONDS)) {
//                    client.joinRoom(roomId);
//                } else {
//                    log.error("用户加入房间[{}]超时：获取锁失败", roomId);
//                    client.sendEvent(EVENT_JOIN_FAILED, "加入房间超时，请重试");
//                }
//            } catch (InterruptedException e) {
//                log.error("用户加入房间[{}]被中断", roomId, e);
//                Thread.currentThread().interrupt();
//                client.sendEvent(EVENT_JOIN_FAILED, ERR_SERVER_EXCEPTION);
//            } finally {
//                if (reentrantLock.isHeldByCurrentThread()) {
//                    reentrantLock.unlock();
//                }
//            }
//        } catch (Exception e) {
//            log.error("处理加入房间事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_JOIN_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 发送新消息事件（保持原有业务逻辑，无锁修改）
//     */
//    @OnEvent("sendNewMessage")
//    public void sendNewMessage(SocketIOClient client, NewMessageVo newMessageVo) {
//        try {
//            // 基础参数校验（原有逻辑不变）
//            if (!validateNewMessageParams(client, newMessageVo)) {
//                return;
//            }
//            String senderId = newMessageVo.getSenderId();
//            String roomId = newMessageVo.getRoomId();
//            log.info("处理新消息，senderId={}, roomId={}", senderId, roomId);
//
//            // 身份验证（防止会话劫持，注释可根据需求开启）
////        if (!validateSenderIdentity(senderId)) {
////            client.sendEvent(EVENT_SEND_FAILED, ERR_IDENTITY_VERIFY_FAILED);
////            return;
////        }
//
//            // 消息安全处理（XSS防御+敏感词过滤）（原有逻辑不变）
//            processMessageSecurity(newMessageVo);
//
//            // 单聊/群聊特殊处理（原有逻辑不变）
//            if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
//                // 单聊：验证好友关系+生成服务卡片
//                if (!processSingleChatSpecial(newMessageVo, client)) {
//                    return;
//                }
//            } else {
//                // 群聊：清空卡片相关字段
//                clearCardInfo(newMessageVo);
//            }
//
//            // 处理已读用户列表（发送者默认已读+在线接收者）（原有逻辑不变）
//            handleReadUsers(newMessageVo);
//
//            // 保存消息到数据库（原有逻辑不变）
//            saveMessageToDb(newMessageVo);
//
//            // 转发消息给房间内其他客户端（原有逻辑不变）
//            sendToOtherClients(client, roomId, EVENT_RECEIVE_MSG, newMessageVo);
//        } catch (Exception e) {
//            log.error("处理发送新消息事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 校验新消息参数合法性（无修改）
//     */
//    private boolean validateNewMessageParams(SocketIOClient client, NewMessageVo newMessageVo) {
//        if (!validateClientAndRequest(client, newMessageVo)
//                || StringUtils.isEmpty(newMessageVo.getSenderId())
//                || StringUtils.isEmpty(newMessageVo.getRoomId())) {
//            log.warn("发送消息：客户端或参数不完整");
//            client.sendEvent(EVENT_SEND_FAILED, ERR_PARAM_INCOMPLETE);
//            return false;
//        }
//        // 发送者ID格式校验
//        if (!ValidationUtil.isValidObjectId(newMessageVo.getSenderId())) {
//            log.error("发送消息失败：发送者ID格式非法，senderId={}", newMessageVo.getSenderId());
//            client.sendEvent(EVENT_SEND_FAILED, ERR_INVALID_SENDER_ID);
//            return false;
//        }
//        // 房间ID格式校验
//        if (!ValidationUtil.isValidRoomId(newMessageVo.getRoomId())) {
//            log.error("发送消息失败：房间ID格式非法，roomId={}", newMessageVo.getRoomId());
//            client.sendEvent(EVENT_SEND_FAILED, ERR_INVALID_ROOM_ID);
//            return false;
//        }
//        // 单聊接收者ID校验
//        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())
//                && !ValidationUtil.isValidObjectId(newMessageVo.getReceiverId())) {
//            log.error("发送消息失败：接收者ID格式非法，receiverId={}", newMessageVo.getReceiverId());
//            client.sendEvent(EVENT_SEND_FAILED, ERR_INVALID_RECEIVER_ID);
//            return false;
//        }
//        return true;
//    }
//
//    /**
//     * 验证发送者身份（与SecurityContext中的身份匹配）（优化：日志规范）
//     */
//    private boolean validateSenderIdentity(String senderId) {
//        // 获取认证信息前添加空值检查
//        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
//        if (authentication == null) {
//            log.error("身份验证失败：未找到有效的认证信息，senderId={}", senderId);
//            return false;
//        }
//
//        String actualUserId = authentication.getName();
//        // 进一步检查用户名是否为空
//        if (actualUserId == null || actualUserId.trim().isEmpty()) {
//            log.error("身份验证失败：认证信息中的用户名为空，senderId={}", senderId);
//            return false;
//        }
//
//        log.debug("身份验证：实际用户ID={}，待验证senderId={}", actualUserId, senderId);
//
//        if (!actualUserId.equals(senderId)) {
//            log.warn("会话劫持风险：{} 尝试伪造发送者 {}", actualUserId, senderId);
//            return false;
//        }
//        return true;
//    }
//
//    /**
//     * 消息安全处理（XSS防御+敏感词过滤）
//     */
//    private void processMessageSecurity(NewMessageVo newMessageVo) {
//        String originalMsg = newMessageVo.getMessage();
//        if (StringUtils.isEmpty(originalMsg)) {
//            return;
//        }
//        // ========== 判断消息类型，图片类型跳过敏感词过滤 ==========
//        if ("img".equals(newMessageVo.getMessageType())) {
//            // 图片消息仍需XSS防御（防止URL包含恶意脚本），但跳过敏感词过滤
//            String escapedMsg = HtmlUtils.htmlEscape(originalMsg);
//            newMessageVo.setMessage(escapedMsg);
//            return;
//        }
//        // XSS防御：HTML特殊字符转义
//        String escapedMsg = HtmlUtils.htmlEscape(originalMsg);
//        newMessageVo.setMessage(escapedMsg);
//
//        // 敏感词过滤，处理返回null的情况
//        String[] filteredResult = sensitiveFilter.filter(originalMsg);
//        // 过滤结果为null时，默认保留原始消息
//        if (filteredResult == null) {
//            filteredResult = new String[]{originalMsg, "0"};
//            log.warn("敏感词过滤返回null，使用原始消息，senderId={}", newMessageVo.getSenderId());
//        }
//
//        newMessageVo.setMessage(filteredResult[0]);
//        // 记录敏感消息
//        if ("1".equals(filteredResult[1])) {
//            try {
//                SensitiveMessage sensitiveMsg = new SensitiveMessage();
//                sensitiveMsg.setRoomId(newMessageVo.getRoomId());
//                sensitiveMsg.setSenderId(newMessageVo.getSenderId());
//                sensitiveMsg.setSenderName(newMessageVo.getSenderName());
//                sensitiveMsg.setMessage(originalMsg);
//                sensitiveMsg.setType(ConstValueEnum.MESSAGE);
//                sensitiveMsg.setTime(formatTime(Instant.now()));
//                sysService.addSensitiveMessage(sensitiveMsg);
//                log.warn("消息包含敏感词：发送者={}, 原内容={}", newMessageVo.getSenderId(), originalMsg);
//            } catch (Exception e) {
//                log.error("记录敏感消息异常", e);
//                // 记录敏感消息失败不影响主流程
//            }
//        }
//    }
//
//    /**
//     * 单聊特殊处理（好友关系验证+服务卡片生成）
//     */
//    private boolean processSingleChatSpecial(NewMessageVo newMessageVo, SocketIOClient client) {
//        String senderId = newMessageVo.getSenderId();
//        String receiverId = newMessageVo.getReceiverId();
//
//        try {
//            // 校验发送者存在性
//            User sender = userService.getUserInfo(senderId);
//            if (sender == null) {
//                log.error("发送者不存在，senderId={}", senderId);
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SENDER_NOT_EXIST);
//                return false;
//            }
//
//            // 非客服需验证好友关系
//            boolean isCustomerService = UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(sender.getRole());
//            if (!isCustomerService) {
//                if (StringUtils.isEmpty(receiverId)) {
//                    log.error("单聊接收者ID为空");
//                    client.sendEvent(EVENT_SEND_FAILED, ERR_PARAM_INCOMPLETE);
//                    return false;
//                }
//                if (!goodFriendService.checkIsFriend(senderId, receiverId)) {
//                    log.warn("非好友关系，拒绝发送单聊消息：sender={}, receiver={}", senderId, receiverId);
//                    client.sendEvent(EVENT_SEND_FAILED, ERR_NOT_FRIEND);
//                    return false;
//                }
//            }
//
//            // 生成服务卡片
//            handleCardMessage(newMessageVo);
//            return true;
//        } catch (Exception e) {
//            log.error("处理单聊消息特殊逻辑异常，senderId={}, receiverId={}", senderId, receiverId, e);
//            client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            return false;
//        }
//    }
//
//    /**
//     * 清空卡片信息（群聊场景）
//     */
//    private void clearCardInfo(NewMessageVo newMessageVo) {
//        newMessageVo.setCardType(null);
//        newMessageVo.setCardOptions(null);
//    }
//
//    /**
//     * 处理已读用户列表
//     */
//    private void handleReadUsers(NewMessageVo newMessageVo) {
//        List<String> readUsers = new ArrayList<>();
//        readUsers.add(newMessageVo.getSenderId()); // 发送者默认已读
//
//        // 单聊：判断接收者在线状态
//        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())
//                && !StringUtils.isEmpty(newMessageVo.getReceiverId())) {
//            try {
//                boolean isReceiverOnline = onlineUserService.checkCurUserIsOnline(newMessageVo.getReceiverId());
//                if (isReceiverOnline) {
//                    readUsers.add(newMessageVo.getReceiverId());
//                }
//            } catch (Exception e) {
//                log.error("判断接收者在线状态异常，receiverId={}", newMessageVo.getReceiverId(), e);
//                // 异常时不添加接收者到已读列表
//            }
//        }
//        newMessageVo.setIsReadUser(readUsers);
//    }
//
//    /**
//     * 保存消息到数据库（单聊/群聊区分处理）
//     */
//    private void saveMessageToDb(NewMessageVo newMessageVo) {
//        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
//            // 保存单聊消息
//            SingleMessage singleMessage = new SingleMessage();
//            BeanUtils.copyProperties(newMessageVo, singleMessage);
//            singleMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
//            singleMessage.setTime(formatTime(Instant.now()));
//            singleMessageService.addNewSingleMessage(singleMessage);
//            log.debug("保存单聊消息：{}", singleMessage.getId());
//        } else if (ConstValueEnum.GROUP.equals(newMessageVo.getConversationType())) {
//            // 保存群聊消息
//            GroupMessage groupMessage = new GroupMessage();
//            BeanUtils.copyProperties(newMessageVo, groupMessage);
//            groupMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
//            groupMessage.setTime(formatTime(Instant.now()));
//            groupMessageService.addNewGroupMessage(groupMessage);
//            log.debug("保存群聊消息：{}", groupMessage.getId());
//        }
//    }
//
//    /**
//     * 处理服务卡片消息生成（仅单聊）
//     */
//    private void handleCardMessage(NewMessageVo newMessageVo) {
//        String message = newMessageVo.getMessage();
//        if (StringUtils.isEmpty(message)) {
//            return;
//        }
//        String senderId = newMessageVo.getSenderId();
//        String receiverId = newMessageVo.getReceiverId();
//
//        try {
//            // 校验发送者/接收者存在性
//            User sender = userService.getUserInfo(senderId);
//            User receiver = userService.getUserInfo(receiverId);
//            if (sender == null || receiver == null) {
//                log.warn("用户信息不存在，无法生成卡片：sender={}, receiver={}", senderId, receiverId);
//                return;
//            }
//
//            // 校验是否为客服与用户的聊天
//            UserRoleEnum senderRole = UserRoleEnum.fromCode(sender.getRole());
//            UserRoleEnum receiverRole = UserRoleEnum.fromCode(receiver.getRole());
//
//            // 处理未知角色情况
//            if (senderRole == null) {
//                log.error("未知角色代码：senderRole={}, senderId={}", sender.getRole(), senderId);
//                return;
//            }
//            if (receiverRole == null) {
//                log.error("未知角色代码：receiverRole={}, receiverId={}", receiver.getRole(), receiverId);
//                return;
//            }
//
//            boolean isCustomerServiceChat = (senderRole.isCustomerService() && receiverRole.isCustomer())
//                    || (senderRole.isCustomer() && receiverRole.isCustomerService());
//            if (!isCustomerServiceChat) {
//                log.debug("非客服与用户聊天，不生成卡片：senderRole={}, receiverRole={}",
//                        senderRole, receiverRole);
//                newMessageVo.setCardType(null);
//                newMessageVo.setCardOptions(null);
//                return;
//            }
//
//            // 检测关键字并生成卡片
//            boolean hasRefund = message.contains("申请退款");
//            boolean hasQuery = message.contains("查询订单");
//            if (hasRefund || hasQuery) {
//                newMessageVo.setCardType(ConstValueEnum.MESSAGE_TYPE_CARD);
//                List<CardOptionVo> options = new ArrayList<>();
//                if (hasRefund) {
//                    options.add(new CardOptionVo("申请退款", "/chat/order/refund?userId=" + senderId + "&token={token}", "POST"));
//                }
//                if (hasQuery) {
//                    options.add(new CardOptionVo("查询订单", "/chat/order/query?userId=" + senderId + "&token={token}", "GET"));
//                }
//                newMessageVo.setCardOptions(options);
//                log.debug("生成服务卡片：sender={}, 关键字={}", senderId, hasRefund ? "申请退款" : "查询订单");
//            } else {
//                newMessageVo.setCardType(null);
//                newMessageVo.setCardOptions(null);
//            }
//        } catch (Exception e) {
//            log.error("处理服务卡片生成异常", e);
//            // 异常时不生成卡片
//            newMessageVo.setCardType(null);
//            newMessageVo.setCardOptions(null);
//        }
//    }
//
//    /**
//     * 发送验证消息事件
//     */
//    @OnEvent("sendValidateMessage")
//    public void sendValidateMessage(SocketIOClient client, ValidateMessage validateMessage) {
//        try {
//            if (!validateClientAndRequest(client, validateMessage)) {
//                log.warn("发送验证消息：客户端或消息为空");
//                return;
//            }
//            log.info("处理验证消息：senderId={}, roomId={}",
//                    validateMessage.getSenderId(), validateMessage.getRoomId());
//
//            // 敏感词过滤，处理返回null的情况
//            String originalMsg = validateMessage.getAdditionMessage();
//            String[] filteredResult = sensitiveFilter.filter(originalMsg);
//            // 过滤结果为null时，默认保留原始消息
//            if (filteredResult == null) {
//                filteredResult = new String[]{originalMsg != null ? originalMsg : "", "0"};
//                log.warn("验证消息敏感词过滤返回null，使用原始消息，senderId={}", validateMessage.getSenderId());
//            }
//
//            String filteredContent = filteredResult[0];
//            validateMessage.setAdditionMessage(filteredContent);
//
//            // 记录敏感验证消息
//            if ("1".equals(filteredResult[1])) {
//                try {
//                    SensitiveMessage sensitiveMsg = new SensitiveMessage();
//                    sensitiveMsg.setRoomId(validateMessage.getRoomId());
//                    sensitiveMsg.setSenderId(validateMessage.getSenderId().toString());
//                    sensitiveMsg.setSenderName(validateMessage.getSenderName());
//                    sensitiveMsg.setMessage(originalMsg);
//                    sensitiveMsg.setType(ConstValueEnum.VALIDATE);
//                    sensitiveMsg.setTime(formatTime(Instant.now()));
//                    sysService.addSensitiveMessage(sensitiveMsg);
//                } catch (Exception e) {
//                    log.error("记录敏感验证消息异常", e);
//                    // 记录失败不影响主流程
//                }
//            }
//
//            // 保存验证消息并转发
//            ValidateMessage savedMsg = validateMessageService.addValidateMessage(validateMessage);
//            if (savedMsg != null) {
//                sendToOtherClients(client, validateMessage.getRoomId(), EVENT_RECEIVE_VALIDATE_MSG, validateMessage);
//            }
//        } catch (Exception e) {
//            log.error("处理发送验证消息事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 同意好友请求事件
//     */
//    @OnEvent("sendAgreeFriendValidate")
//    public void sendAgreeFriendValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
//        try {
//            if (!validateClientAndRequest(client, validateMessage)) {
//                log.warn("同意好友请求：客户端或消息为空");
//                return;
//            }
//            log.info("同意好友请求：senderId={}, receiverId={}",
//                    validateMessage.getSenderId(), validateMessage.getReceiverId());
//
//            // 创建好友关系
//            GoodFriend goodFriend = new GoodFriend();
//            goodFriend.setUserM(new ObjectId(validateMessage.getSenderId()));
//            goodFriend.setUserY(new ObjectId(validateMessage.getReceiverId()));
//            goodFriendService.addFriend(goodFriend);
//
//            // 更新验证消息状态
//            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 1);
//
//            // 生成单聊房间ID（按字典序拼接）
//            String senderId = validateMessage.getSenderId();
//            String receiverId = validateMessage.getReceiverId();
//            String targetRoomId = generateSingleChatRoomId(senderId, receiverId);
//
//            sendToOtherClients(client, targetRoomId, EVENT_RECEIVE_AGREE_FRIEND, validateMessage);
//        } catch (Exception e) {
//            log.error("处理同意好友请求事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 生成单聊房间ID（按字典序拼接两个uid）
//     */
//    private String generateSingleChatRoomId(String uid1, String uid2) {
//        if (uid1 == null || uid2 == null) {
//            throw new IllegalArgumentException("用户ID不能为空");
//        }
//        // 使用StringBuilder优化拼接
//        StringBuilder sb = new StringBuilder();
//        if (uid1.compareTo(uid2) < 0) {
//            sb.append(uid1).append("-").append(uid2);
//        } else {
//            sb.append(uid2).append("-").append(uid1);
//        }
//        return sb.toString();
//    }
//
//    /**
//     * 拒绝好友请求事件
//     */
//    @OnEvent("sendDisAgreeFriendValidate")
//    public void sendDisAgreeFriendValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
//        try {
//            if (!validateClientAndRequest(client, validateMessage)) {
//                log.warn("拒绝好友请求：客户端或消息为空");
//                return;
//            }
//            log.info("拒绝好友请求：id={}", validateMessage.getId());
//            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
//        } catch (Exception e) {
//            log.error("处理拒绝好友请求事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 删除好友事件
//     */
//    @OnEvent("sendDelGoodFriend")
//    public void sendDelGoodFriend(SocketIOClient client, CurrentConversationVo conversationVo) {
//        try {
//            if (!validateClientAndRequest(client, conversationVo) || StringUtils.isEmpty(conversationVo.getRoomId())) {
//                log.warn("删除好友：客户端或消息为空");
//                return;
//            }
//            log.info("删除好友：roomId={}", conversationVo.getRoomId());
//
//            // 补充操作人ID
//            String uid = onlineUserService.getSimpleUserByClientId(client.getSessionId().toString()).getUid();
//            conversationVo.setId(uid);
//
//            // 转发删除通知
//            sendToOtherClients(client, conversationVo.getRoomId(), EVENT_RECEIVE_DEL_FRIEND, conversationVo);
//        } catch (Exception e) {
//            log.error("处理删除好友事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 同意加入群聊事件
//     */
//    @OnEvent("sendAgreeGroupValidate")
//    public void sendAgreeGroupValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
//        try {
//            if (!validateClientAndRequest(client, validateMessage)) {
//                log.warn("同意加入群聊：客户端或消息为空");
//                return;
//            }
//            log.info("同意加入群聊：groupId={}, userId={}",
//                    validateMessage.getRoomId(), validateMessage.getReceiverId());
//
//            // 添加群成员
//            groupUserService.addNewGroupUser(validateMessage);
//
//            // 更新验证消息状态
//            validateMessageService.changeGroupValidateNewsStatus(validateMessage.getId(), 1);
//
//            // 生成目标房间ID（群聊房间ID直接使用groupId的字符串形式）
//            String targetRoomId = validateMessage.getRoomId().toString();
//
//            sendToOtherClients(client, targetRoomId, EVENT_RECEIVE_AGREE_GROUP, validateMessage);
//        } catch (Exception e) {
//            log.error("处理同意加入群聊事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 拒绝加入群聊事件
//     */
//    @OnEvent("sendDisAgreeGroupValidate")
//    public void sendDisAgreeGroupValidate(SocketIOClient client, ValidateMessageResponseVo validateMessage) {
//        try {
//            if (!validateClientAndRequest(client, validateMessage)) {
//                log.warn("拒绝加入群聊：客户端或消息为空");
//                return;
//            }
//            log.info("拒绝加入群聊：id={}", validateMessage.getId());
//            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
//        } catch (Exception e) {
//            log.error("处理拒绝加入群聊事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 退出群聊事件
//     */
//    @OnEvent("sendQuitGroup")
//    public void sendQuitGroup(SocketIOClient client, CurrentConversationVo conversationVo) {
//        try {
//            if (!validateClientAndRequest(client, conversationVo) || StringUtils.isEmpty(conversationVo.getRoomId())) {
//                log.warn("退出群聊：客户端或消息为空");
//                return;
//            }
//            log.info("退出群聊：roomId={}", conversationVo.getRoomId());
//            sendToOtherClients(client, conversationVo.getRoomId(), EVENT_RECEIVE_QUIT_GROUP, conversationVo);
//        } catch (Exception e) {
//            log.error("处理退出群聊事件异常", e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 申请事件（通用）
//     */
//    @OnEvent("apply")
//    public void apply(SocketIOClient client, CurrentConversationVo conversationVo) {
//        forwardEventToRoom(client, conversationVo, "apply");
//    }
//
//    /**
//     * 回复事件（通用）
//     */
//    @OnEvent("reply")
//    public void reply(SocketIOClient client, CurrentConversationVo conversationVo) {
//        forwardEventToRoom(client, conversationVo, "reply");
//    }
//
//    /**
//     * 1v1通话相关事件转发
//     */
//    @OnEvent("1v1answer")
//    public void answer(SocketIOClient client, CurrentConversationVo conversationVo) {
//        forwardEventToRoom(client, conversationVo, "1v1answer");
//    }
//
//    @OnEvent("1v1ICE")
//    public void ice(SocketIOClient client, CurrentConversationVo conversationVo) {
//        forwardEventToRoom(client, conversationVo, "1v1ICE");
//    }
//
//    @OnEvent("1v1offer")
//    public void offer(SocketIOClient client, CurrentConversationVo conversationVo) {
//        forwardEventToRoom(client, conversationVo, "1v1offer");
//    }
//
//    @OnEvent("1v1hangup")
//    public void hangup(SocketIOClient client, CurrentConversationVo conversationVo) {
//        forwardEventToRoom(client, conversationVo, "1v1hangup");
//    }
//
//    /**
//     * 通用事件转发到房间
//     */
//    private void forwardEventToRoom(SocketIOClient client, CurrentConversationVo conversationVo, String eventName) {
//        try {
//            if (!validateClientAndRequest(client, conversationVo) || StringUtils.isEmpty(conversationVo.getRoomId())) {
//                log.warn("转发事件{}：客户端或消息为空", eventName);
//                return;
//            }
//            log.debug("转发事件{}，roomId：{}", eventName, conversationVo.getRoomId());
//            sendToOtherClients(client, conversationVo.getRoomId(), eventName, conversationVo);
//        } catch (Exception e) {
//            log.error("转发事件{}异常", eventName, e);
//            if (client != null) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_SERVER_EXCEPTION);
//            }
//        }
//    }
//
//    /**
//     * 发送事件给房间内其他客户端（排除自己）
//     */
//    private void sendToOtherClients(SocketIOClient senderClient, String roomId, String eventName, Object data) {
//        try {
//            Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(roomId).getClients();
//            if (clients == null) {
//                log.debug("房间{}内无其他客户端，无需转发事件{}", roomId, eventName);
//                return;
//            }
//            // 使用迭代器遍历，避免ConcurrentModificationException
//            Iterator<SocketIOClient> iterator = clients.iterator();
//            while (iterator.hasNext()) {
//                SocketIOClient client = iterator.next();
//                if (client != null && !client.getSessionId().equals(senderClient.getSessionId())) {
//                    client.sendEvent(eventName, data);
//                }
//            }
//        } catch (Exception e) {
//            log.error("发送事件{}给房间{}内其他客户端异常", eventName, roomId, e);
//        }
//    }
//
//    /**
//     * 校验客户端和请求参数非空
//     */
//    private boolean validateClientAndRequest(SocketIOClient client, Object request) {
//        return client != null && request != null && client.isChannelOpen();
//    }
//
//    /**
//     * 格式化时间（统一格式）
//     */
//    private String formatTime(Instant instant) {
//        return instant.atZone(ZoneId.of("Asia/Shanghai")).format(TIME_FORMATTER);
//    }
//}
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
import com.zzw.chatserver.utils.ChatServerUtil;
import com.zzw.chatserver.utils.DateUtil;
import com.zzw.chatserver.utils.ValidationUtil;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.beans.BeanUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

import javax.annotation.Resource;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * SocketIO事件监听器
 * 处理客户端连接、断开、消息发送等事件，负责消息转发、离线消息推送、在线状态管理等
 */
@Component
@Transactional(rollbackFor = Throwable.class)
@Slf4j
public class SocketIoListener {

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

    /** 统一时间格式化器（避免解析异常） */
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

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
            log.info("定时清理过期客户端完成，清理数量：{}", cleanedCount);
            if (cleanedCount > 0) {
                broadcastOnlineUser();
            }
        } catch (Exception e) {
            log.error("定时清理过期客户端失败", e);
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
            log.warn("客户端断开连接：客户端为空，跳过处理");
            return;
        }

        try {
            String clientId = client.getSessionId().toString();
            log.info("客户端断开连接，clientId: {}", clientId);
            cleanLoginInfo(clientId);
            log.info("连接关闭，url参数: {}", client.getHandshakeData().getUrlParams());
            broadcastOnlineUser();
        } catch (Exception e) {
            log.error("处理客户端断开连接异常", e);
        }
    }

    /**
     * 客户端连接/重连事件
     * 恢复用户在线状态和房间信息，推送重连成功通知
     */
    @OnConnect
    public void onConnect(SocketIOClient client) {
        if (client == null) {
            log.warn("客户端连接：客户端为空，跳过处理");
            return;
        }

        try {
            // 提取URL参数中的uid
            String uid = extractUidFromParams(client.getHandshakeData().getUrlParams());
            log.info("客户端连接/重连，UID: {}", uid);

            if (uid != null) {
                // 校验用户存在性
                User user = userService.getUserInfo(uid);
                if (user == null) {
                    log.error("用户不存在，UID: {}", uid);
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
            log.error("处理客户端连接异常", e);
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
            log.info("用户{}无历史房间，无需重新加入", uid);
            return;
        }
        for (String roomId : roomIds) {
            if (roomId != null) {
                client.joinRoom(roomId);
                log.info("用户{}重新加入房间：{}", uid, roomId);
            }
        }
    }

    /**
     * 查询用户参与的所有房间ID（单聊+群聊）
     */
    private List<String> getRoomsByUid(String uid) {
        List<String> roomIds = new ArrayList<>();
        if (uid == null) {
            log.warn("查询房间：uid为空，返回空列表");
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
                log.warn("查询群聊房间：用户信息不存在，uid={}", uid);
            }
        } catch (Exception e) {
            log.error("查询用户房间列表异常，uid={}", uid, e);
        }

        return roomIds;
    }

    /**
     * 清理用户登录信息（在线状态、更新在线时长）
     */
    private void cleanLoginInfo(String clientId) {
        if (clientId == null) {
            log.warn("清理登录信息：clientId为空，跳过处理");
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
            log.error("清理用户登录信息异常，clientId={}", clientId, e);
        }
    }

    /**
     * 打印当前在线用户数
     */
    private void printOnlineUserCount() {
        try {
            log.info("当前在线用户人数：{}", onlineUserService.countOnlineUser());
        } catch (Exception e) {
            log.error("获取在线用户数异常", e);
        }
    }

    /**
     * 广播在线用户列表
     */
    private void broadcastOnlineUser() {
        try {
            socketIOServer.getBroadcastOperations().sendEvent(EVENT_ONLINE_USER, onlineUserService.getOnlineUidSet());
        } catch (Exception e) {
            log.error("广播在线用户列表异常", e);
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
                log.warn("用户上线：客户端或用户信息不完整");
                return;
            }
            String clientId = client.getSessionId().toString();
            String uid = user.getUid();
            log.info("用户上线，user：{}", user);

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
            log.error("处理用户上线事件异常", e);
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
                log.warn("消息确认：客户端或确认信息不完整");
                return;
            }

            log.info("收到消息确认，userId={}, singleMessageIds={}, groupMessageIds={}",
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
            log.error("处理消息确认异常", e);
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
                    log.info("清理用户[{}]的旧客户端绑定：{}", uid, oldClientId);
                }
            } finally {
                onlineUserLock.unlock();
            }
        } catch (Exception e) {
            log.error("清理用户旧客户端绑定异常，uid={}", uid, e);
        }
    }

    /**
     * 推送离线消息（单聊+群聊）
     */
    private void pushOfflineMessages(SocketIOClient client, String uid) {
        try {
            log.info("用户{}上线，开始推送离线消息", uid);

            // 推送单聊离线消息
            pushOfflineSingleMessages(client, uid);

            // 推送群聊离线消息
            pushOfflineGroupMessages(client, uid);
        } catch (Exception e) {
            log.error("推送离线消息异常，uid={}", uid, e);
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
                log.info("推送单聊离线消息{}条给用户{}", offlineMsgs.size(), uid);
                // 不立即标记为已读，等待客户端确认
            }
        } catch (Exception e) {
            log.error("推送单聊离线消息异常，uid={}", uid, e);
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
                    log.info("推送群聊[{}]离线消息{}条给用户{}", roomId, offlineMsgs.size(), uid);
                    // 不立即标记为已读，等待客户端确认
                }
            }
        } catch (Exception e) {
            log.error("推送群聊离线消息异常，uid={}", uid, e);
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
                log.debug("客户端[{}]心跳续期成功，用户：{}，过期时间：{}ms",
                        clientId, user.getUid(), HEARTBEAT_EXPIRATION_MS);
            } else {
                log.warn("客户端[{}]心跳验证失败：未找到绑定用户", clientId);
            }
        } catch (Exception e) {
            log.error("处理客户端心跳异常", e);
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
                log.warn("用户离开：客户端为空，跳过处理");
                return;
            }
            log.info("用户离开，clientId：{}", client.getSessionId());
            cleanLoginInfo(client.getSessionId().toString());
            broadcastOnlineUser();
        } catch (Exception e) {
            log.error("处理用户离开事件异常", e);
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
                log.warn("消息已读标记：客户端或请求参数不完整");
                return;
            }
            // 房间ID格式校验
            if (!ValidationUtil.isValidRoomId(requestVo.getRoomId())) {
                log.error("消息已读标记失败：房间ID格式非法，roomId={}", requestVo.getRoomId());
                return;
            }
            log.info("消息已读标记，requestVo：{}", requestVo);

            // 发送给房间内其他客户端
            sendToOtherClients(client, requestVo.getRoomId(), EVENT_IS_READ_MSG, requestVo);
        } catch (Exception e) {
            log.error("处理消息已读事件异常", e);
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
                log.warn("加入房间：房间ID为空");
                client.sendEvent(EVENT_JOIN_FAILED, ERR_PARAM_INCOMPLETE);
                return;
            }
            String roomId = conversationVo.getRoomId();
            // 房间ID格式校验
            if (!ValidationUtil.isValidRoomId(roomId)) {
                log.error("加入房间失败：房间ID格式非法，roomId={}", roomId);
                client.sendEvent(EVENT_JOIN_FAILED, ERR_INVALID_ROOM_ID);
                return;
            }
            log.info("加入房间，roomId：{}，conversationVo：{}", roomId, conversationVo);
            client.joinRoom(roomId);
        } catch (Exception e) {
            log.error("处理加入房间事件异常", e);
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
            log.info("处理新消息，senderId={}, roomId={}", senderId, roomId);

            // 身份验证（防止会话劫持）
//            if (!validateSenderIdentity(senderId)) {
//                client.sendEvent(EVENT_SEND_FAILED, ERR_IDENTITY_VERIFY_FAILED);
//                return;
//            }

            // ========== 消息防篡改校验 ==========
            String message = newMessageVo.getMessage();
            String messageType = newMessageVo.getMessageType();
            String time = newMessageVo.getTime(); // 需与前端时间格式完全一致（如"yyyy-MM-dd HH:mm:ss.SSS"）
            String frontDigest = newMessageVo.getDigest(); // 前端传来的摘要

            // 拼接字段（顺序必须与前端一致）
            // String contentToEncrypt = message + messageType + roomId + time;
            String contentToEncrypt = message + messageType + roomId;
            String generatedMd5 = ChatServerUtil.generateMD5(contentToEncrypt);

            // 比对摘要
            if (frontDigest != null && !generatedMd5.equals(frontDigest)) {
                log.warn("消息篡改检测：生成MD5={}，前端摘要={}，字段拼接内容={}",
                        generatedMd5, frontDigest, contentToEncrypt);
                client.sendEvent(EVENT_SEND_FAILED, "消息已被篡改，发送失败");
                return; // 拒绝篡改的消息
            }

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
            log.error("处理发送新消息事件异常", e);
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
            log.warn("发送消息：客户端或参数不完整");
            client.sendEvent(EVENT_SEND_FAILED, ERR_PARAM_INCOMPLETE);
            return false;
        }
        // 发送者ID格式校验
        if (!ValidationUtil.isValidObjectId(newMessageVo.getSenderId())) {
            log.error("发送消息失败：发送者ID格式非法，senderId={}", newMessageVo.getSenderId());
            client.sendEvent(EVENT_SEND_FAILED, ERR_INVALID_SENDER_ID);
            return false;
        }
        // 房间ID格式校验
        if (!ValidationUtil.isValidRoomId(newMessageVo.getRoomId())) {
            log.error("发送消息失败：房间ID格式非法，roomId={}", newMessageVo.getRoomId());
            client.sendEvent(EVENT_SEND_FAILED, ERR_INVALID_ROOM_ID);
            return false;
        }
        // 单聊接收者ID校验
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())
                && !ValidationUtil.isValidObjectId(newMessageVo.getReceiverId())) {
            log.error("发送消息失败：接收者ID格式非法，receiverId={}", newMessageVo.getReceiverId());
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
            log.error("身份验证失败：未找到有效的认证信息");
            return false;
        }

        String actualUserId = authentication.getName();
        // 进一步检查用户名是否为空
        if (actualUserId == null) {
            log.error("身份验证失败：认证信息中的用户名为空");
            return false;
        }

        System.out.println("actualUserId：" + actualUserId);

        if (!actualUserId.equals(senderId)) {
            log.warn("会话劫持风险：{} 尝试伪造发送者 {}", actualUserId, senderId);
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
            log.warn("敏感词过滤返回null，使用原始消息，senderId={}", newMessageVo.getSenderId());
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
                sensitiveMsg.setTime(formatTime(Instant.now()));
                sysService.addSensitiveMessage(sensitiveMsg);
                log.warn("消息包含敏感词：发送者={}, 原内容={}", newMessageVo.getSenderId(), originalMsg);
            } catch (Exception e) {
                log.error("记录敏感消息异常", e);
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
                log.error("发送者不存在，senderId={}", senderId);
                client.sendEvent(EVENT_SEND_FAILED, ERR_SENDER_NOT_EXIST);
                return false;
            }

            // 非客服需验证好友关系
            boolean isCustomerService = UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(sender.getRole());
            if (!isCustomerService) {
                if (StringUtils.isEmpty(receiverId)) {
                    log.error("单聊接收者ID为空");
                    client.sendEvent(EVENT_SEND_FAILED, ERR_PARAM_INCOMPLETE);
                    return false;
                }
                if (!goodFriendService.checkIsFriend(senderId, receiverId)) {
                    log.warn("非好友关系，拒绝发送单聊消息：sender={}, receiver={}", senderId, receiverId);
                    client.sendEvent(EVENT_SEND_FAILED, ERR_NOT_FRIEND);
                    return false;
                }
            }

            // 生成服务卡片
            handleCardMessage(newMessageVo);
            return true;
        } catch (Exception e) {
            log.error("处理单聊消息特殊逻辑异常，senderId={}, receiverId={}", senderId, receiverId, e);
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
                log.error("判断接收者在线状态异常，receiverId={}", newMessageVo.getReceiverId(), e);
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
            singleMessage.setTime(formatTime(Instant.now()));
            singleMessageService.addNewSingleMessage(singleMessage);
            log.debug("保存单聊消息：{}", singleMessage.getId());
        } else if (ConstValueEnum.GROUP.equals(newMessageVo.getConversationType())) {
            // 保存群聊消息
            GroupMessage groupMessage = new GroupMessage();
            BeanUtils.copyProperties(newMessageVo, groupMessage);
            groupMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            groupMessage.setTime(formatTime(Instant.now()));
            groupMessageService.addNewGroupMessage(groupMessage);
            log.debug("保存群聊消息：{}", groupMessage.getId());
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
                log.warn("用户信息不存在，无法生成卡片：sender={}, receiver={}", senderId, receiverId);
                return;
            }

            // 校验是否为客服与用户的聊天
            UserRoleEnum senderRole = UserRoleEnum.fromCode(sender.getRole());
            UserRoleEnum receiverRole = UserRoleEnum.fromCode(receiver.getRole());

            // 处理未知角色情况
            if (senderRole == null) {
                log.error("未知角色代码：senderRole={}, senderId={}", sender.getRole(), senderId);
                return;
            }
            if (receiverRole == null) {
                log.error("未知角色代码：receiverRole={}, receiverId={}", receiver.getRole(), receiverId);
                return;
            }

            boolean isCustomerServiceChat = (senderRole.isCustomerService() && receiverRole.isCustomer())
                    || (senderRole.isCustomer() && receiverRole.isCustomerService());
            if (!isCustomerServiceChat) {
                log.debug("非客服与用户聊天，不生成卡片：senderRole={}, receiverRole={}",
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
                log.debug("生成服务卡片：sender={}, 关键字={}", senderId, hasRefund ? "申请退款" : "查询订单");
            } else {
                newMessageVo.setCardType(null);
                newMessageVo.setCardOptions(null);
            }
        } catch (Exception e) {
            log.error("处理服务卡片生成异常", e);
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
                log.warn("发送验证消息：客户端或消息为空");
                return;
            }
            log.info("处理验证消息：senderId={}, roomId={}",
                    validateMessage.getSenderId(), validateMessage.getRoomId());

            // 敏感词过滤，处理返回null的情况
            String originalMsg = validateMessage.getAdditionMessage();
            String[] filteredResult = sensitiveFilter.filter(originalMsg);
            // 过滤结果为null时，默认保留原始消息
            if (filteredResult == null) {
                filteredResult = new String[]{originalMsg != null ? originalMsg : "", "0"};
                log.warn("验证消息敏感词过滤返回null，使用原始消息，senderId={}", validateMessage.getSenderId());
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
                    sensitiveMsg.setTime(formatTime(Instant.now()));
                    sysService.addSensitiveMessage(sensitiveMsg);
                } catch (Exception e) {
                    log.error("记录敏感验证消息异常", e);
                    // 记录失败不影响主流程
                }
            }

            // 保存验证消息并转发
            ValidateMessage savedMsg = validateMessageService.addValidateMessage(validateMessage);
            if (savedMsg != null) {
                sendToOtherClients(client, validateMessage.getRoomId(), EVENT_RECEIVE_VALIDATE_MSG, validateMessage);
            }
        } catch (Exception e) {
            log.error("处理发送验证消息事件异常", e);
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
                log.warn("同意好友请求：客户端或消息为空");
                return;
            }
            log.info("同意好友请求：senderId={}, receiverId={}",
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
            log.error("处理同意好友请求事件异常", e);
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
                log.warn("拒绝好友请求：客户端或消息为空");
                return;
            }
            log.info("拒绝好友请求：id={}", validateMessage.getId());
            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
        } catch (Exception e) {
            log.error("处理拒绝好友请求事件异常", e);
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
                log.warn("删除好友：客户端或消息为空");
                return;
            }
            log.info("删除好友：roomId={}", conversationVo.getRoomId());

            // 补充操作人ID
            String uid = onlineUserService.getSimpleUserByClientId(client.getSessionId().toString()).getUid();
            conversationVo.setId(uid);

            // 转发删除通知
            sendToOtherClients(client, conversationVo.getRoomId(), EVENT_RECEIVE_DEL_FRIEND, conversationVo);
        } catch (Exception e) {
            log.error("处理删除好友事件异常", e);
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
                log.warn("同意加入群聊：客户端或消息为空");
                return;
            }
            log.info("同意加入群聊：groupId={}, userId={}",
                    validateMessage.getRoomId(), validateMessage.getReceiverId());

            // 添加群成员
            groupUserService.addNewGroupUser(validateMessage);

            // 更新验证消息状态
            validateMessageService.changeGroupValidateNewsStatus(validateMessage.getId(), 1);

            // 生成目标房间ID（群聊房间ID直接使用groupId的字符串形式）
            String targetRoomId = validateMessage.getRoomId().toString();

            sendToOtherClients(client, targetRoomId, EVENT_RECEIVE_AGREE_GROUP, validateMessage);
        } catch (Exception e) {
            log.error("处理同意加入群聊事件异常", e);
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
                log.warn("拒绝加入群聊：客户端或消息为空");
                return;
            }
            log.info("拒绝加入群聊：id={}", validateMessage.getId());
            validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
        } catch (Exception e) {
            log.error("处理拒绝加入群聊事件异常", e);
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
                log.warn("退出群聊：客户端或消息为空");
                return;
            }
            log.info("退出群聊：roomId={}", conversationVo.getRoomId());
            sendToOtherClients(client, conversationVo.getRoomId(), EVENT_RECEIVE_QUIT_GROUP, conversationVo);
        } catch (Exception e) {
            log.error("处理退出群聊事件异常", e);
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
                log.warn("转发事件{}：客户端或消息为空", eventName);
                return;
            }
            log.debug("转发事件{}，roomId：{}", eventName, conversationVo.getRoomId());
            sendToOtherClients(client, conversationVo.getRoomId(), eventName, conversationVo);
        } catch (Exception e) {
            log.error("转发事件{}异常", eventName, e);
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
                log.debug("房间{}内无其他客户端，无需转发事件{}", roomId, eventName);
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
            log.error("发送事件{}给房间{}内其他客户端异常", eventName, roomId, e);
        }
    }

    /**
     * 校验客户端和请求参数非空
     */
    private boolean validateClientAndRequest(SocketIOClient client, Object request) {
        return client != null && request != null;
    }

    /**
     * 格式化时间（统一格式）
     */
    private String formatTime(Instant instant) {
        return instant.atZone(ZoneId.of("Asia/Shanghai")).format(TIME_FORMATTER);
    }

}
