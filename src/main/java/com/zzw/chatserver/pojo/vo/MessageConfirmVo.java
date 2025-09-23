package com.zzw.chatserver.pojo.vo;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * 消息确认VO类
 * 用于客户端确认收到离线消息后向服务端发送确认
 */
@Data
public class MessageConfirmVo {
    /**
     * 用户ID
     */
    private String userId;

    /**
     * 已接收的单聊消息ID列表
     */
    private List<String> singleMessageIds;

    /**
     * 已接收的群聊消息ID列表，key为房间ID，value为消息ID列表
     */
    private Map<String, List<String>> groupMessageIds;
}
