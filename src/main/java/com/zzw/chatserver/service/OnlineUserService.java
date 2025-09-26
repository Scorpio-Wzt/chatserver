package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.vo.SimpleUser;

import java.util.Set;

/**
 * 在线用户服务接口
 * 定义在线用户管理核心操作：
 * - 绑定客户端ID与用户信息
 * - 维护在线用户UID集合
 * - 查询在线状态、在线用户数等
 * - 强化Session与用户的绑定关系，支持clientId与userId的双向映射
 */
public interface OnlineUserService {

    /**
     * 续期客户端和用户的绑定关系过期时间
     * @param clientId 客户端ID
     * @param uid 用户ID
     * @param expirationMs 过期时间(毫秒)
     */
    void renewExpiration(String clientId, String uid, long expirationMs);

    /**
     * 清理过期的客户端绑定
     * @param expirationThresholdMs 过期阈值(毫秒)，超过此时长未活动的客户端将被清理
     * @return 清理的客户端数量
     */
    int cleanExpiredClients(long expirationThresholdMs);

    /**
     * 绑定客户端ID与用户信息（并将用户UID加入在线集合）
     * @param clientId 客户端唯一标识
     * @param simpleUser 用户简化信息（含UID、昵称等）
     */
    void addClientIdToSimpleUser(String clientId, SimpleUser simpleUser);

    /**
     * 获取所有在线用户的UID集合
     * @return 在线用户UID集合（String）
     */
    Set<String> getOnlineUidSet();

    /**
     * 根据客户端ID查询绑定的用户信息
     * @param clientId 客户端唯一标识
     * @return 绑定的用户信息（无绑定返回null）
     */
    SimpleUser getSimpleUserByClientId(String clientId);

    /**
     * 移除客户端绑定关系并删除在线UID（用户下线时调用）
     * @param clientId 客户端唯一标识
     * @param uid 要移除的用户UID
     */
    void removeClientAndUidInSet(String clientId, String uid);

    /**
     * 统计当前在线用户数量
     * @return 在线用户数（无在线用户返回0）
     */
    int countOnlineUser();

    /**
     * 校验指定用户是否在线
     * @param uid 要校验的用户UID
     * @return true=在线，false=离线（Redis查询异常时返回false）
     */
    boolean checkCurUserIsOnline(String uid);

    /**
     * 根据客户ID查询绑定的用户信息
     * @param uid
     * @return
     */
    String getClientIdByUid(String uid);
}