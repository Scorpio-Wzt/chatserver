package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.pojo.vo.SimpleUser;
import com.zzw.chatserver.service.OnlineUserService;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
    private static final String PREFIX_ALL_CLIENTS = "chat:all:clients:";
    // 过期时间定义（1小时，可根据业务调整）
    private static final long EXPIRE_HOURS = 1;

    /**
     * 获取所有在线用户的UID集合（修正为返回Set<String>，匹配广播方法需求）
     */
    @Override
    public Set<String> getOnlineUidSet() {
        // 从Redis获取Object类型的UID集合
        Set<Object> uidObjects = redisTemplate.opsForSet().members(PREFIX_ONLINE_UID_SET);

        // 转换为String类型集合（过滤null值，避免空指针）
        if (uidObjects == null || uidObjects.isEmpty()) {
            return Collections.emptySet();
        }

        return uidObjects.stream()
                .filter(Objects::nonNull)  // 过滤null元素
                .map(Object::toString)     // 转换为字符串类型
                .collect(Collectors.toSet()); // 收集为Set<String>
    }

    /**
     * 绑定客户端ID与用户信息（双向绑定）
     */
    @Override
    public void addClientIdToSimpleUser(String clientId, SimpleUser simpleUser) {
        // clientId -> 用户信息（1小时过期）
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        redisTemplate.opsForValue().set(clientKey, simpleUser, EXPIRE_HOURS, TimeUnit.HOURS);

        // userId -> clientId（双向绑定，1小时过期）
        String userKey = PREFIX_USER_TO_CLIENT + simpleUser.getUid();
        redisTemplate.opsForValue().set(userKey, clientId, EXPIRE_HOURS, TimeUnit.HOURS);

        // 加入在线用户集合
        redisTemplate.opsForSet().add(PREFIX_ONLINE_UID_SET, simpleUser.getUid());
    }

    /**
     * 续期客户端和用户的绑定关系过期时间
     * @param clientId 客户端ID
     * @param uid 用户ID
     * @param expirationMs 过期时间(毫秒)
     */
    @Override
    public void renewExpiration(String clientId, String uid, long expirationMs) {
        if (clientId == null || uid == null || expirationMs <= 0) {
            return;
        }

        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        String userKey = PREFIX_USER_TO_CLIENT + uid;

        // 续期客户端-用户绑定关系
        redisTemplate.expire(clientKey, expirationMs, TimeUnit.MILLISECONDS);
        // 续期用户-客户端绑定关系
        redisTemplate.expire(userKey, expirationMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 清理过期的客户端绑定
     * @param expirationThresholdMs 过期阈值(毫秒)，超过此时长未活动的客户端将被清理
     * @return 清理的客户端数量
     */
    @Override
    public int cleanExpiredClients(long expirationThresholdMs) {
        int cleanedCount = 0;

        // 获取所有客户端ID
        Set<Object> allClients = redisTemplate.opsForSet().members(PREFIX_ALL_CLIENTS);
        if (allClients == null || allClients.isEmpty()) {
            return 0;
        }

        // 检查每个客户端是否过期
        for (Object clientObj : allClients) {
            String clientId = clientObj.toString();
            String clientKey = PREFIX_CLIENT_TO_USER + clientId;

            // 检查键是否存在
            if (!redisTemplate.hasKey(clientKey)) {
                // 键已过期或不存在，需要清理
                cleanClientBinding(clientId);
                cleanedCount++;
                continue;
            }

            // 检查过期时间
            Long ttl = redisTemplate.getExpire(clientKey, TimeUnit.MILLISECONDS);
            if (ttl == null || ttl < 0 || ttl > expirationThresholdMs) {
                // 已过期或剩余时间超过阈值，需要清理
                cleanClientBinding(clientId);
                cleanedCount++;
            }
        }

        return cleanedCount;
    }

    /**
     * 清理客户端的所有绑定关系
     */
    private void cleanClientBinding(String clientId) {
        if (clientId == null) {
            return;
        }

        // 获取客户端绑定的用户
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        SimpleUser user = (SimpleUser) redisTemplate.opsForValue().get(clientKey);

        // 删除客户端-用户绑定
        redisTemplate.delete(clientKey);

        // 删除用户-客户端绑定
        if (user != null && user.getUid() != null) {
            String userKey = PREFIX_USER_TO_CLIENT + user.getUid();
            redisTemplate.delete(userKey);
        }

        // 从客户端集合中移除
        redisTemplate.opsForSet().remove(PREFIX_ALL_CLIENTS, clientId);
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
        // 删除客户端与用户的绑定关系
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        redisTemplate.delete(clientKey);

        // 从在线用户Set集合中移除该UID
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