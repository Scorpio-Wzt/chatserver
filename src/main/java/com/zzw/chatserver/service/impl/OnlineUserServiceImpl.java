package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.pojo.vo.SimpleUser;
import com.zzw.chatserver.service.OnlineUserService;
import com.zzw.chatserver.utils.RedisKeyUtil;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 在线用户服务实现类
 * 基于Redis实现在线用户管理：
 * - String类型：key=客户端ID → value=用户信息（SimpleUser）
 * - Set类型：key=在线用户集合 → value=用户UID（确保唯一）
 * - 基于Redis维护clientId与userId的双向映射，确保Session与用户严格绑定
 */
@Service
public class OnlineUserServiceImpl implements OnlineUserService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    // Redis键前缀定义
    private static final String PREFIX_CLIENT_TO_USER = "chat:client:user:"; // clientId -> SimpleUser
    private static final String PREFIX_USER_TO_CLIENT = "chat:user:client:"; // userId -> clientId
    private static final String PREFIX_ONLINE_UID_SET = "chat:online:uids";   // 在线用户集合
    // 过期时间定义（1小时，可根据业务调整）
    private static final long EXPIRE_HOURS = 1;
    /**
     * 绑定客户端ID与用户信息（双向绑定）
     */
    @Override
    public void addClientIdToSimpleUser(String clientId, SimpleUser simpleUser) {
        // 1. clientId -> 用户信息（1小时过期）
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        redisTemplate.opsForValue().set(clientKey, simpleUser, EXPIRE_HOURS, TimeUnit.HOURS);

        // 2. userId -> clientId（双向绑定，1小时过期）
        String userKey = PREFIX_USER_TO_CLIENT + simpleUser.getUid();
        redisTemplate.opsForValue().set(userKey, clientId, EXPIRE_HOURS, TimeUnit.HOURS);

        // 3. 加入在线用户集合
        redisTemplate.opsForSet().add(PREFIX_ONLINE_UID_SET, simpleUser.getUid());
    }

    /**
     * 延长客户端与用户绑定关系的过期时间（用于心跳续期）
     */
    @Override
    public void renewExpiration(String clientId, String uid) {
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        String userKey = PREFIX_USER_TO_CLIENT + uid;
        // 续期为1小时
        redisTemplate.expire(clientKey, EXPIRE_HOURS, TimeUnit.HOURS);
        redisTemplate.expire(userKey, EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 获取所有在线用户的UID集合
     */
    @Override
    public Set<Object> getOnlineUidSet() {
        return redisTemplate.opsForSet().members(PREFIX_ONLINE_UID_SET);
    }

    /**
     * 根据客户端ID查询绑定的用户信息
     */
    @Override
    public SimpleUser getSimpleUserByClientId(String clientId) {
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        Object userObj = redisTemplate.opsForValue().get(clientKey);
        return userObj instanceof SimpleUser ? (SimpleUser) userObj : null;
    }

    /**
     * 根据客户ID查询绑定的用户信息
     */
    @Override
    public String getClientIdByUid(String uid) {
        String userKey = PREFIX_USER_TO_CLIENT + uid;
        Object clientObj = redisTemplate.opsForValue().get(userKey);
        return clientObj instanceof String ? (String) clientObj : null;
    }

    /**
     * 移除客户端绑定关系并删除在线UID（用户下线逻辑）
     */
    @Override
    public void removeClientAndUidInSet(String clientId, String uid) {
        // 1. 删除客户端与用户的绑定关系
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        redisTemplate.delete(clientKey);

        // 2. 从在线用户Set集合中移除该UID
        String userKey = PREFIX_USER_TO_CLIENT + uid;
        redisTemplate.delete(userKey);

        redisTemplate.opsForSet().remove(PREFIX_ONLINE_UID_SET, uid);
    }

    /**
     * 统计当前在线用户数量
     */
    @Override
    public int countOnlineUser() {
        Long count = redisTemplate.opsForSet().size(PREFIX_ONLINE_UID_SET);
        return count != null ? count.intValue() : 0;
    }

    /**
     * 校验指定用户是否在线
     */
    @Override
    public boolean checkCurUserIsOnline(String uid) {
        Boolean isMember = redisTemplate.opsForSet().isMember(PREFIX_ONLINE_UID_SET, uid);
        // Redis查询异常（返回null）时，默认视为离线
        return isMember == null ? false : isMember;
    }
}