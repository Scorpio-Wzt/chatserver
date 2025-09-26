package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.pojo.SystemNotification;
import com.zzw.chatserver.pojo.vo.SimpleUser;
import com.zzw.chatserver.service.OnlineUserService;
import com.zzw.chatserver.service.SystemNotificationService;
import com.corundumstudio.socketio.SocketIOServer;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.Date;
import java.util.List;

@Service
public class SystemNotificationServiceImpl implements SystemNotificationService {

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private SocketIOServer socketIOServer;

    @Resource
    private OnlineUserService onlineUserService;

    @Override
    public void sendSystemNotification(SystemNotification notification) {
        // 补全通知基础信息
        notification.setTime(String.valueOf(Instant.now()));
        notification.setRead(false);

        // 保存通知到数据库
        mongoTemplate.insert(notification, "systemnotifications");

        // 检查接收用户是否在线
        if (onlineUserService.checkCurUserIsOnline(notification.getReceiverUid())) {
            // 在线用户：通过SocketIO实时推送
            socketIOServer.getBroadcastOperations()
                    .sendEvent("systemNotification", notification.getReceiverUid(), notification);
        }
    }

    @Override
    public List<SystemNotification> getUnreadNotifications(String uid) {
        // 查询指定用户的未读通知，按时间倒序排列
        Query query = Query.query(Criteria.where("receiverUid").is(uid).and("isRead").is(false))
                .with(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "time"));

        return mongoTemplate.find(query, SystemNotification.class, "systemnotifications");
    }

    @Override
    public void markAsRead(String notificationId, String uid) {
        // 标记通知为已读（仅允许标记自己的通知）
        Query query = Query.query(
                Criteria.where("_id").is(notificationId)
                        .and("receiverUid").is(uid)
        );

        org.springframework.data.mongodb.core.query.Update update = new org.springframework.data.mongodb.core.query.Update()
                .set("isRead", true);

        mongoTemplate.updateFirst(query, update, SystemNotification.class, "systemnotifications");
    }
}