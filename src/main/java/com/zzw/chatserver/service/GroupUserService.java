package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.vo.MyGroupResultVo;
import com.zzw.chatserver.pojo.vo.RecentGroupVo;
import com.zzw.chatserver.pojo.vo.ValidateMessageResponseVo;

import java.util.List;

/**
 * 群成员服务接口
 * 定义群成员管理相关操作（查询群成员、添加群成员、获取最近群组等）
 */
public interface GroupUserService {

    /**
     * 检查用户是否为指定群聊的成员
     * @param groupId 群聊ID
     * @param userId 用户ID
     * @return true=是群成员，false=非群成员
     */
    boolean isGroupMember(String groupId, String userId);

    /**
     * 根据用户名查询该用户加入的所有群组
     * @param username 用户名
     * @return 该用户加入的群组列表VO（含群组基本信息）
     */
    List<MyGroupResultVo> getGroupUsersByUserName(String username);

    /**
     * 根据群组ID查询该群的所有成员
     * @param groupId 群组ID（字符串格式）
     * @return 群成员列表VO（含成员基本信息）
     */
    List<MyGroupResultVo> getGroupUsersByGroupId(String groupId);

    /**
     * 添加新成员到群组（处理加群请求）
     * @param validateMessage 加群验证响应参数（含申请者信息、群组信息）
     */
    void addNewGroupUser(ValidateMessageResponseVo validateMessage);

    /**
     * 根据用户ID和群组ID列表，查询用户的最近群组
     * @param recentGroupVo 最近群组查询参数（含用户ID、群组ID列表）
     * @return 用户的最近群组列表VO
     */
    List<MyGroupResultVo> getRecentGroup(RecentGroupVo recentGroupVo);
}