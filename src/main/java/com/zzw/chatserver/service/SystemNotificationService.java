package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.SystemNotification;

import java.util.List;

public interface SystemNotificationService {
    // 发送系统通知（在线实时推送，离线存储）
    void sendSystemNotification(SystemNotification notification);
    // 获取用户未读通知
    List<SystemNotification> getUnreadNotifications(String uid);
    // 标记已读
    void markAsRead(String notificationId, String uid);
}