package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.vo.SimpleUser;

import java.util.Set;

/**
 * 在线用户服务接口
 * 定义在线用户管理核心操作：
 * - 绑定客户端ID与用户信息
 * - 维护在线用户UID集合
 * - 查询在线状态、在线用户数等
 */
public interface OnlineUserService {

    /**
     * 绑定客户端ID与用户信息（并将用户UID加入在线集合）
     * @param clientId 客户端唯一标识
     * @param simpleUser 用户简化信息（含UID、昵称等）
     */
    void addClientIdToSimpleUser(String clientId, SimpleUser simpleUser);

    /**
     * 获取所有在线用户的UID集合
     * @return 在线用户UID集合（Object类型，需自行强转String）
     */
    Set<Object> getOnlineUidSet();

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
}