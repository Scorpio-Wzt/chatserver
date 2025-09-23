package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.GroupMessage;
import com.zzw.chatserver.pojo.vo.GroupHistoryResultVo;
import com.zzw.chatserver.pojo.vo.GroupMessageResultVo;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;

import java.util.List;

/**
 * 群聊消息服务接口
 * 定义群聊消息的查询（历史/最近/最后一条）、保存等核心操作
 */
public interface GroupMessageService {

    /**
     * 按消息ID列表标记群聊消息为已读
     * @param roomId 群聊房间ID
     * @param userId 当前用户ID
     * @param messageIds 需标记为已读的消息ID列表
     */
    void markGroupMessagesAsRead(String roomId, String userId, List<String> messageIds);

    /**
     * 标记群聊中指定用户的未读消息为已读
     * @param roomId 群聊房间ID
     * @param uid 当前用户UID
     */
    void userIsReadGroupMessage(String roomId, String uid);

    /**
     * 查询用户在指定群聊中的未读消息
     * @param roomId 群聊房间ID
     * @param uid 当前用户UID
     * @return 未读消息列表
     */
    List<GroupMessageResultVo> getUnreadGroupMessages(String roomId, String uid);

    /**
     * 获取群聊历史消息（支持按类型、关键词、日期筛选，分页查询）
     * @param groupHistoryVo 历史消息查询参数（含房间ID、筛选条件、分页信息）
     * @return 群聊历史消息结果（含消息列表和总条数）
     */
    GroupHistoryResultVo getGroupHistoryMessages(HistoryMsgRequestVo groupHistoryVo);

    /**
     * 获取群聊房间的最后一条消息（最新消息）
     * @param roomId 群聊房间ID
     * @return 最后一条群聊消息VO（无消息时返回空VO对象）
     */
    GroupMessageResultVo getGroupLastMessage(String roomId);

    /**
     * 获取群聊房间的最近消息（分页查询，最新消息在前）
     * @param roomId 群聊房间ID
     * @param pageIndex 页码（从0开始）
     * @param pageSize 每页条数
     * @return 最近群聊消息列表
     */
    List<GroupMessageResultVo> getRecentGroupMessages(String roomId, Integer pageIndex, Integer pageSize);

    /**
     * 保存新的群聊消息
     * @param groupMessage 群聊消息实体（含发送者、房间ID、消息内容等）
     */
    void addNewGroupMessage(GroupMessage groupMessage);
}