package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.SingleMessage;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;
import com.zzw.chatserver.pojo.vo.IsReadMessageRequestVo;
import com.zzw.chatserver.pojo.vo.SingleHistoryResultVo;
import com.zzw.chatserver.pojo.vo.SingleMessageResultVo;
import java.util.List;

public interface SingleMessageService {

    /**
     * 按消息ID列表标记单聊消息为已读
     * @param userId 当前用户ID（接收者ID）
     * @param messageIds 需标记为已读的消息ID列表
     */
    void markMessagesAsRead(String userId, List<String> messageIds);

    /**
     * 查询用户的单聊未读消息
     * @param uid 当前用户UID（接收者ID）
     * @return 未读消息列表
     */
    List<SingleMessageResultVo> getUnreadMessages(String uid);

    /**
     * 保存新的单聊消息
     */
    void addNewSingleMessage(SingleMessage singleMessage);

    /**
     * 获取单聊房间的最后一条消息
     */
    SingleMessageResultVo getLastMessage(String roomId);

    /**
     * 获取单聊房间的最近消息
     */
    List<SingleMessageResultVo> getRecentMessage(String roomId, int pageIndex, int pageSize);

    /**
     * 获取单聊房间的历史消息
     */
    SingleHistoryResultVo getSingleHistoryMsg(HistoryMsgRequestVo requestVo);

    /**
     * 更新用户的消息已读状态
     */
    void userIsReadMessage(IsReadMessageRequestVo requestVo);
}
