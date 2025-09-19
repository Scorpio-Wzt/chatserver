package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.SingleMessage;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;
import com.zzw.chatserver.pojo.vo.IsReadMessageRequestVo;
import com.zzw.chatserver.pojo.vo.SingleHistoryResultVo;
import com.zzw.chatserver.pojo.vo.SingleMessageResultVo;
import java.util.List;

public interface SingleMessageService {

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
