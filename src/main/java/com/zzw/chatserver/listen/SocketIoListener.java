package com.zzw.chatserver.listen;

import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import com.zzw.chatserver.common.ConstValueEnum;
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


    @OnConnect
    public void eventOnConnect(SocketIOClient client) {
        Map<String, List<String>> urlParams = client.getHandshakeData().getUrlParams();
        logger.info("链接开启，urlParams：{}", urlParams);
    }

    @OnDisconnect
    public void eventOnDisConnect(SocketIOClient client) {
        logger.info("eventOnDisConnect ---> 客户端唯一标识为：{}", client.getSessionId());
        Map<String, List<String>> urlParams = client.getHandshakeData().getUrlParams();
        cleanLoginInfo(client.getSessionId().toString());
        logger.info("链接关闭，urlParams：{}", urlParams);
        socketIOServer.getBroadcastOperations().sendEvent("onlineUser", onlineUserService.getOnlineUidSet());
    }

    private void cleanLoginInfo(String clientId) {
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
        logger.info("goOnline ---> user：{}", user);
        String clientId = client.getSessionId().toString();
        SimpleUser simpleUser = new SimpleUser();
        BeanUtils.copyProperties(user, simpleUser);

        onlineUserService.addClientIdToSimpleUser(clientId, simpleUser);
        printMessage();
        socketIOServer.getBroadcastOperations().sendEvent("onlineUser", onlineUserService.getOnlineUidSet());
    }

    @OnEvent("leave")
    public void leave(SocketIOClient client) {
        logger.info("leave ---> client：{}", client);
        cleanLoginInfo(client.getSessionId().toString());
        socketIOServer.getBroadcastOperations().sendEvent("onlineUser", onlineUserService.getOnlineUidSet());
    }

    @OnEvent("isReadMsg")
    public void isReadMsg(SocketIOClient client, UserIsReadMsgRequestVo requestVo) {
        logger.info("isReadMsg ---> requestVo：{}", requestVo);
        if (requestVo.getRoomId() != null) {
            Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(requestVo.getRoomId()).getClients();
            for (SocketIOClient item : clients) {
                if (item != client) {
                    item.sendEvent("isReadMsg", requestVo);
                }
            }
        }
    }

    @OnEvent("join")
    public void join(SocketIOClient client, CurrentConversationVo conversationVo) {
        logger.info("加入房间号码：{} ---> conversationVo：{}", conversationVo.getRoomId(), conversationVo);
        client.joinRoom(conversationVo.getRoomId());
    }

    @OnEvent("sendNewMessage")
    public void sendNewMessage(SocketIOClient client, NewMessageVo newMessageVo) {
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
            // 【新增：单聊消息发送前，强制验证好友关系】
            boolean isFriend = goodFriendService.checkIsFriend(newMessageVo.getSenderId(), newMessageVo.getReceiverId());
            if (!isFriend) {
                logger.warn("非好友关系，拒绝发送单聊消息：sender={}, receiver={}",
                        newMessageVo.getSenderId(), newMessageVo.getReceiverId());
                // 给发送者客户端返回“非好友不能发送消息”的提示
                client.sendEvent("sendFailed", "非好友关系，无法发送消息");
                return; // 直接返回，不执行后续保存和转发
            }

            handleCardMessage(newMessageVo);
        } else {
            // 群聊时强制清空卡片相关字段，确保不会有残留
            newMessageVo.setCardType(null);
            newMessageVo.setCardOptions(null);
        }

        // 保存消息到数据库
        if (ConstValueEnum.FRIEND.equals(newMessageVo.getConversationType())) {
            SingleMessage singleMessage = new SingleMessage();
            BeanUtils.copyProperties(newMessageVo, singleMessage);
            singleMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            singleMessage.setCardType(newMessageVo.getCardType());
            singleMessage.setCardOptions(newMessageVo.getCardOptions());
            singleMessage.setTime(new Date());
            logger.info("待插入的单聊消息（含卡片）为：{}", singleMessage);
            singleMessageService.addNewSingleMessage(singleMessage);
        } else if (ConstValueEnum.GROUP.equals(newMessageVo.getConversationType())) {
            GroupMessage groupMessage = new GroupMessage();
            BeanUtils.copyProperties(newMessageVo, groupMessage);
            groupMessage.setSenderId(new ObjectId(newMessageVo.getSenderId()));
            groupMessage.setTime(new Date());
            // 群聊消息不保存卡片相关字段
            groupMessageService.addNewGroupMessage(groupMessage);
        }

        // 转发消息给房间内其他客户端
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(newMessageVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (!item.getSessionId().equals(client.getSessionId())) {
                item.sendEvent("receiveMessage", newMessageVo);
            }
        }
    }


    /**
     * 处理卡片消息生成，仅在单聊场景被调用
     */
    private void handleCardMessage(NewMessageVo newMessageVo) {
        String message = newMessageVo.getMessage();
        if (StringUtils.isEmpty(message)) {
            return;
        }

        // 验证发送者与接收者是否为好友
        boolean isFriend = goodFriendService.checkIsFriend(newMessageVo.getSenderId(), newMessageVo.getReceiverId());
        if (!isFriend) {
            logger.warn("非好友关系，拒绝生成服务卡片：sender={}, receiver={}",
                    newMessageVo.getSenderId(), newMessageVo.getReceiverId());
            return;
        }

        // 检测关键字
        boolean hasRefund = message.contains("申请退款");
        boolean hasQuery = message.contains("查询订单");

        // 生成服务卡片
        if (hasRefund || hasQuery) {
            newMessageVo.setCardType(ConstValueEnum.MESSAGE_TYPE_CARD); // 使用常量定义，避免硬编码
            List<CardOption> options = new ArrayList<>();

            if (hasRefund) {
                options.add(new CardOption(
                        "申请退款",
                        "/chat/order/refund?userId=" + newMessageVo.getSenderId() + "&token={token}",
                        "POST"
                ));
            }

            if (hasQuery) {
                options.add(new CardOption(
                        "查询订单",
                        "/chat/order/query?userId=" + newMessageVo.getSenderId() + "&token={token}",
                        "GET"
                ));
            }

            newMessageVo.setCardOptions(options);
            logger.info("生成服务卡片：sender={}, 关键字={}", newMessageVo.getSenderId(),
                    hasRefund ? "申请退款" : "查询订单");
        } else {
            // 无关键字时清空卡片字段
            newMessageVo.setCardType(null);
            newMessageVo.setCardOptions(null);
        }
    }

    @OnEvent("sendValidateMessage")
    public void sendValidateMessage(SocketIOClient client, ValidateMessage validateMessage) {
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
        logger.info("sendDisAgreeFriendValidate ---> validateMessage：{}", validateMessage);
        validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
    }

    @OnEvent("sendDelGoodFriend")
    public void sendDelGoodFriend(SocketIOClient client, CurrentConversationVo conversationVo) {
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
        logger.info("sendDisAgreeGroupValidate ---> validateMessage：{}", validateMessage);
        validateMessageService.changeFriendValidateNewsStatus(validateMessage.getId(), 2);
    }

    @OnEvent("sendQuitGroup")
    public void sendQuitGroup(SocketIOClient client, CurrentConversationVo conversationVo) {
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
        logger.info("1v1hangup ---> roomId：{}", conversationVo.getRoomId());
        Collection<SocketIOClient> clients = socketIOServer.getRoomOperations(conversationVo.getRoomId()).getClients();
        for (SocketIOClient item : clients) {
            if (item != client) {
                item.sendEvent("1v1hangup", conversationVo);
            }
        }
    }
}
