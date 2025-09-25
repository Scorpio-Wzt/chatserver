package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.GoodFriend;
import com.zzw.chatserver.pojo.vo.DelGoodFriendRequestVo;
import com.zzw.chatserver.pojo.vo.MyFriendListResultVo;
import com.zzw.chatserver.pojo.vo.RecentConversationVo;
import com.zzw.chatserver.pojo.vo.SingleRecentConversationResultVo;

import java.util.List;

/**
 * 好友服务接口
 * 定义好友管理相关核心操作（查询好友列表、最近会话、添加/删除好友、好友关系校验等）
 */
public interface GoodFriendService {

    /**
     * 查询最近聊天的好友
     * @param recentConversationVo
     * @return
     */
    List<SingleRecentConversationResultVo> getRecentChatFriends(RecentConversationVo recentConversationVo);

    /**
     * 获取当前用户的好友列表
     * @param userId 当前用户ID（字符串格式）
     * @return 好友列表VO（包含好友基本信息、等级、单聊房间ID等）
     */
    List<MyFriendListResultVo> getMyFriendsList(String userId);

    /**
     * 添加好友
     * @param goodFriend 好友关系实体（包含双方用户ID）
     */
    void addFriend(GoodFriend goodFriend);

    /**
     * 批量添加好友
     * @param friends 好友关系实体（包含双方用户ID）
     */
    void  batchAddFriends(List<GoodFriend> friends);

    /**
     * 删除好友
     * @param requestVo 删除好友参数（包含主动删除者ID、被动删除者ID、单聊房间ID）
     */
    void deleteFriend(DelGoodFriendRequestVo requestVo);

    /**
     * 校验两个用户是否为好友关系
     * @param userId 用户A的ID（字符串格式）
     * @param friendId 用户B的ID（字符串格式）
     * @return true=是好友，false=非好友
     */
    boolean checkIsFriend(String userId, String friendId);
}