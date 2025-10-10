//package com.zzw.chatserver.service.impl;
//
//import com.zzw.chatserver.pojo.vo.SimpleUser;
//import com.zzw.chatserver.service.OnlineUserService;
//import com.zzw.chatserver.utils.RedisKeyUtil;
//import lombok.extern.slf4j.Slf4j;
//import org.springframework.data.redis.core.RedisTemplate;
//import org.springframework.stereotype.Service;
//
//import javax.annotation.Resource;
//import java.util.Collections;
//import java.util.List;
//import java.util.Map;
//import java.util.Set;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.TimeUnit;
//import java.util.stream.Collectors;
//
///**
// * 在线用户服务实现类
// * 基于Redis实现在线用户管理：
// * - String类型：key=客户端ID → value=用户信息（SimpleUser）
// * - Set类型：key=在线用户集合 → value=用户UID（确保唯一）
// * - 基于Redis维护clientId与userId的双向映射，确保Session与用户严格绑定
// */
//@Service
//@Slf4j
//public class OnlineUserServiceImpl implements OnlineUserService {
//
//    @Resource
//    private RedisTemplate<String, Object> redisTemplate;
//
//    // Redis键前缀定义
//    private static final String PREFIX_CLIENT_TO_USER = "chat:client:user:"; // clientId -> SimpleUser
//    private static final String PREFIX_USER_TO_CLIENT = "chat:user:client:"; // userId -> clientId
//    private static final String PREFIX_ONLINE_UID_SET = "chat:online:uids";   // 在线用户集合
//    private static final String PREFIX_ALL_CLIENTS = "chat:all:clients:";
//    // 过期时间定义（1小时，可根据业务调整）
//    private static final long EXPIRE_HOURS = 1;
//    /**
//     * 客户端ID -> 用户信息映射
//     * 线程安全的ConcurrentHashMap，支持高并发读写
//     */
//    private final Map<String, ClientInfo> clientMap = new ConcurrentHashMap<>();
//
//    /**
//     * 用户ID -> 客户端ID映射（一个用户可能有多个客户端）
//     */
//    private final Map<String, Set<String>> uidToClientsMap = new ConcurrentHashMap<>();
//
//    /**
//     * 客户端信息内部类，包含用户信息和最后活动时间
//     */
//    private static class ClientInfo {
//        SimpleUser user;
//        long lastActiveTime; // 最后活动时间（毫秒）
//
//        ClientInfo(SimpleUser user, long lastActiveTime) {
//            this.user = user;
//            this.lastActiveTime = lastActiveTime;
//        }
//    }
//
//    /**
//     * 获取所有过期的客户端ID
//     * @param expirationMs 过期时间（毫秒），超过此时长未活动的客户端视为过期
//     * @return 过期的客户端ID列表
//     */
//    @Override
//    public List<String> getExpiredClientIds(long expirationMs) {
//        if (expirationMs <= 0) {
//            log.warn("获取过期客户端：过期时间必须大于0，expirationMs={}", expirationMs);
//            return Collections.emptyList();
//        }
//
//        long currentTime = System.currentTimeMillis();
//        // 过滤出超过过期时间的客户端ID
//        return clientMap.entrySet().stream()
//                .filter(entry -> currentTime - entry.getValue().lastActiveTime > expirationMs)
//                .map(Map.Entry::getKey)
//                .collect(Collectors.toList());
//    }
//
//    /**
//     * 绑定客户端ID与用户信息（双向绑定）
//     */
//    @Override
//    public void addClientIdToSimpleUser(String clientId, SimpleUser simpleUser) {
//        // clientId -> 用户信息（1小时过期）
//        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
//        redisTemplate.opsForValue().set(clientKey, simpleUser, EXPIRE_HOURS, TimeUnit.HOURS);
//
//        // userId -> clientId（双向绑定，1小时过期）
//        String userKey = PREFIX_USER_TO_CLIENT + simpleUser.getUid();
//        redisTemplate.opsForValue().set(userKey, clientId, EXPIRE_HOURS, TimeUnit.HOURS);
//
//        // 加入在线用户集合
//        redisTemplate.opsForSet().add(PREFIX_ONLINE_UID_SET, simpleUser.getUid());
//    }
//
//    /**
//     * 续期客户端和用户的绑定关系过期时间
//     * @param clientId 客户端ID
//     * @param uid 用户ID
//     * @param expirationMs 过期时间(毫秒)
//     */
//    @Override
//    public void renewExpiration(String clientId, String uid, long expirationMs) {
//        if (clientId == null || uid == null || expirationMs <= 0) {
//            return;
//        }
//
//        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
//        String userKey = PREFIX_USER_TO_CLIENT + uid;
//
//        // 续期客户端-用户绑定关系
//        redisTemplate.expire(clientKey, expirationMs, TimeUnit.MILLISECONDS);
//        // 续期用户-客户端绑定关系
//        redisTemplate.expire(userKey, expirationMs, TimeUnit.MILLISECONDS);
//    }
//
//    /**
//     * 清理过期的客户端绑定
//     * @param expirationThresholdMs 过期阈值(毫秒)，超过此时长未活动的客户端将被清理
//     * @return 清理的客户端数量
//     */
//    @Override
//    public int cleanExpiredClients(long expirationThresholdMs) {
//        int cleanedCount = 0;
//
//        // 获取所有客户端ID
//        Set<Object> allClients = redisTemplate.opsForSet().members(PREFIX_ALL_CLIENTS);
//        if (allClients == null || allClients.isEmpty()) {
//            return 0;
//        }
//
//        // 检查每个客户端是否过期
//        for (Object clientObj : allClients) {
//            String clientId = clientObj.toString();
//            String clientKey = PREFIX_CLIENT_TO_USER + clientId;
//
//            // 检查键是否存在
//            if (!redisTemplate.hasKey(clientKey)) {
//                // 键已过期或不存在，需要清理
//                cleanClientBinding(clientId);
//                cleanedCount++;
//                continue;
//            }
//
//            // 检查过期时间
//            Long ttl = redisTemplate.getExpire(clientKey, TimeUnit.MILLISECONDS);
//            if (ttl == null || ttl < 0 || ttl > expirationThresholdMs) {
//                // 已过期或剩余时间超过阈值，需要清理
//                cleanClientBinding(clientId);
//                cleanedCount++;
//            }
//        }
//
//        return cleanedCount;
//    }
//
//    /**
//     * 清理客户端的所有绑定关系
//     */
//    private void cleanClientBinding(String clientId) {
//        if (clientId == null) {
//            return;
//        }
//
//        // 获取客户端绑定的用户
//        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
//        SimpleUser user = (SimpleUser) redisTemplate.opsForValue().get(clientKey);
//
//        // 删除客户端-用户绑定
//        redisTemplate.delete(clientKey);
//
//        // 删除用户-客户端绑定
//        if (user != null && user.getUid() != null) {
//            String userKey = PREFIX_USER_TO_CLIENT + user.getUid();
//            redisTemplate.delete(userKey);
//        }
//
//        // 从客户端集合中移除
//        redisTemplate.opsForSet().remove(PREFIX_ALL_CLIENTS, clientId);
//    }
//    /**
//     * 获取所有在线用户的UID集合
//     */
//    @Override
//    public Set<Object> getOnlineUidSet() {
//        return redisTemplate.opsForSet().members(PREFIX_ONLINE_UID_SET);
//    }
//
//    /**
//     * 根据客户端ID查询绑定的用户信息
//     */
//    @Override
//    public SimpleUser getSimpleUserByClientId(String clientId) {
//        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
//        Object userObj = redisTemplate.opsForValue().get(clientKey);
//        return userObj instanceof SimpleUser ? (SimpleUser) userObj : null;
//    }
//
//    /**
//     * 根据客户ID查询绑定的用户信息
//     */
//    @Override
//    public String getClientIdByUid(String uid) {
//        String userKey = PREFIX_USER_TO_CLIENT + uid;
//        Object clientObj = redisTemplate.opsForValue().get(userKey);
//        return clientObj instanceof String ? (String) clientObj : null;
//    }
//
//    /**
//     * 移除客户端绑定关系并删除在线UID（用户下线逻辑）
//     */
//    @Override
//    public void removeClientAndUidInSet(String clientId, String uid) {
//        // 1. 删除客户端与用户的绑定关系
//        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
//        redisTemplate.delete(clientKey);
//
//        // 2. 从在线用户Set集合中移除该UID
//        String userKey = PREFIX_USER_TO_CLIENT + uid;
//        redisTemplate.delete(userKey);
//
//        redisTemplate.opsForSet().remove(PREFIX_ONLINE_UID_SET, uid);
//    }
//
//    /**
//     * 统计当前在线用户数量
//     */
//    @Override
//    public int countOnlineUser() {
//        Long count = redisTemplate.opsForSet().size(PREFIX_ONLINE_UID_SET);
//        return count != null ? count.intValue() : 0;
//    }
//
//    /**
//     * 校验指定用户是否在线
//     */
//    @Override
//    public boolean checkCurUserIsOnline(String uid) {
//        Boolean isMember = redisTemplate.opsForSet().isMember(PREFIX_ONLINE_UID_SET, uid);
//        // Redis查询异常（返回null）时，默认视为离线
//        return isMember == null ? false : isMember;
//    }
//}

package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.pojo.vo.SimpleUser;
import com.zzw.chatserver.service.OnlineUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 在线用户服务实现类
 * 基于Redis实现在线用户管理：
 * - 维护客户端与用户的双向绑定关系
 * - 支持过期客户端自动清理
 * - 提供在线状态查询与统计
 */
@Service
@Slf4j
public class OnlineUserServiceImpl implements OnlineUserService {

    @Resource(name = "objectRedisTemplate")
    private RedisTemplate<String, Object> redisTemplate;

    // Redis键前缀定义
    private static final String PREFIX_CLIENT_TO_USER = "chat:client:user:";       // clientId -> SimpleUser
    private static final String PREFIX_USER_TO_CLIENT = "chat:user:client:";       // userId -> clientId
    private static final String PREFIX_ONLINE_UID_SET = "chat:online:uids";         // 在线用户集合
    private static final String PREFIX_ALL_CLIENTS = "chat:all:clients";            // 所有客户端ID集合
    private static final String PREFIX_CLIENT_LAST_ACTIVE = "chat:client:active:";  // 客户端最后活动时间
    // 过期时间定义（1小时，可根据业务调整）
    private static final long EXPIRE_HOURS = 1;

    /**
     * 新增：获取所有过期的客户端ID（基于Redis实现）
     * @param expirationMs 过期时间（毫秒），超过此时长未活动的客户端视为过期
     * @return 过期的客户端ID列表
     */
    @Override
    public List<String> getExpiredClientIds(long expirationMs) {
        if (expirationMs <= 0) {
            log.warn("获取过期客户端：过期时间必须大于0，expirationMs={}", expirationMs);
            return Collections.emptyList();
        }

        long currentTime = System.currentTimeMillis();
        List<String> expiredClients = new ArrayList<>();

        // 1. 获取所有客户端ID（从维护的Set中获取，避免使用KEYS命令）
        Set<Object> allClientObjs = redisTemplate.opsForSet().members(PREFIX_ALL_CLIENTS);
        if (allClientObjs == null || allClientObjs.isEmpty()) {
            return expiredClients;
        }

        // 2. 批量查询最后活动时间（使用pipeline提升效率，显式处理字节数组转换）
        List<Object> activeTimes = redisTemplate.executePipelined((RedisCallback<Object>) session -> {
            // 获取Redis字符串序列化器（用于将String转换为byte[]）
            RedisSerializer<String> stringSerializer = redisTemplate.getStringSerializer();

            for (Object clientObj : allClientObjs) {
                String clientId = clientObj.toString();
                // 构建完整键名
                String key = PREFIX_CLIENT_LAST_ACTIVE + clientId;
                // 将字符串键转换为byte[]
                byte[] keyBytes = stringSerializer.serialize(key);

                if (keyBytes != null) {
                    // 执行GET命令查询最后活动时间
                    session.get(keyBytes);
                } else {
                    log.warn("客户端{}的活动时间键序列化失败", clientId);
                }
            }
            return null; // 管道操作无需返回值
        });

        // 3. 过滤出过期客户端
        Iterator<Object> clientIter = allClientObjs.iterator();
        Iterator<Object> timeIter = activeTimes.iterator();
        while (clientIter.hasNext() && timeIter.hasNext()) {
            String clientId = clientIter.next().toString();
            Object timeObj = timeIter.next();

            if (timeObj == null) {
                // 无活动时间记录，视为过期
                expiredClients.add(clientId);
                continue;
            }

            try {
                long lastActiveTime = Long.parseLong(timeObj.toString());
                if (currentTime - lastActiveTime > expirationMs) {
                    expiredClients.add(clientId);
                }
            } catch (NumberFormatException e) {
                log.error("客户端{}的活动时间格式错误：{}", clientId, timeObj, e);
                expiredClients.add(clientId); // 格式错误视为过期
            }
        }

        log.debug("检测到过期客户端{}个，expirationMs={}", expiredClients.size(), expirationMs);
        return expiredClients;
    }

    /**
     * 绑定客户端ID与用户信息（双向绑定）
     */
    @Override
    public void addClientIdToSimpleUser(String clientId, SimpleUser simpleUser) {
        if (clientId == null || simpleUser == null || simpleUser.getUid() == null) {
            log.warn("添加客户端绑定：参数不完整，clientId={}, user={}", clientId, simpleUser);
            return;
        }

        long currentTime = System.currentTimeMillis();
        String uid = simpleUser.getUid();

        // 1. 存储客户端-用户映射（带过期时间）
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        redisTemplate.opsForValue().set(clientKey, simpleUser, EXPIRE_HOURS, TimeUnit.HOURS);

        // 2. 存储用户-客户端映射（带过期时间）
        String userKey = PREFIX_USER_TO_CLIENT + uid;
        redisTemplate.opsForValue().set(userKey, clientId, EXPIRE_HOURS, TimeUnit.HOURS);

        // 3. 记录客户端最后活动时间
        String activeKey = PREFIX_CLIENT_LAST_ACTIVE + clientId;
        redisTemplate.opsForValue().set(activeKey, currentTime, EXPIRE_HOURS, TimeUnit.HOURS);

        // 4. 将客户端ID加入全局客户端集合
        redisTemplate.opsForSet().add(PREFIX_ALL_CLIENTS, clientId);

        // 5. 将用户ID加入在线用户集合
        redisTemplate.opsForSet().add(PREFIX_ONLINE_UID_SET, uid);

        log.debug("客户端{}与用户{}绑定成功", clientId, uid);
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
            log.warn("续期参数不完整：clientId={}, uid={}, expirationMs={}", clientId, uid, expirationMs);
            return;
        }

        long currentTime = System.currentTimeMillis();
        // 1. 更新最后活动时间
        String activeKey = PREFIX_CLIENT_LAST_ACTIVE + clientId;
        redisTemplate.opsForValue().set(activeKey, currentTime, expirationMs, TimeUnit.MILLISECONDS);

        // 2. 续期客户端-用户映射
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        redisTemplate.expire(clientKey, expirationMs, TimeUnit.MILLISECONDS);

        // 3. 续期用户-客户端映射
        String userKey = PREFIX_USER_TO_CLIENT + uid;
        redisTemplate.expire(userKey, expirationMs, TimeUnit.MILLISECONDS);

        log.trace("客户端{}续期成功，剩余过期时间{}ms", clientId, expirationMs);
    }

    /**
     * 清理过期的客户端绑定（基于getExpiredClientIds实现）
     * @param expirationThresholdMs 过期阈值(毫秒)
     * @return 清理的客户端数量
     */
    @Override
    public int cleanExpiredClients(long expirationThresholdMs) {
        // 1. 获取所有过期客户端
        List<String> expiredClientIds = getExpiredClientIds(expirationThresholdMs);
        if (expiredClientIds.isEmpty()) {
            return 0;
        }

        // 2. 批量清理过期客户端
        int cleanedCount = 0;
        for (String clientId : expiredClientIds) {
            if (cleanClientBinding(clientId)) {
                cleanedCount++;
            }
        }

        log.info("清理过期客户端完成，共清理{}个", cleanedCount);
        return cleanedCount;
    }

    /**
     * 清理单个客户端的所有绑定关系
     * @return true=清理成功，false=客户端不存在
     */
    private boolean cleanClientBinding(String clientId) {
        if (clientId == null) {
            return false;
        }

        // 1. 获取客户端绑定的用户信息
        SimpleUser user = getSimpleUserByClientId(clientId);
        String uid = user != null ? user.getUid() : null;

        // 2. 批量删除相关键
        List<String> keysToDelete = new ArrayList<>();
        keysToDelete.add(PREFIX_CLIENT_TO_USER + clientId);
        keysToDelete.add(PREFIX_CLIENT_LAST_ACTIVE + clientId);
        if (uid != null) {
            keysToDelete.add(PREFIX_USER_TO_CLIENT + uid);
        }
        redisTemplate.delete(keysToDelete);

        // 3. 从集合中移除
        redisTemplate.opsForSet().remove(PREFIX_ALL_CLIENTS, clientId);
        if (uid != null) {
            // 检查用户是否还有其他客户端（如果没有则从在线集合移除）
            String userKey = PREFIX_USER_TO_CLIENT + uid;
            if (!redisTemplate.hasKey(userKey)) {
                redisTemplate.opsForSet().remove(PREFIX_ONLINE_UID_SET, uid);
            }
        }

        log.debug("客户端{}的绑定关系已清理", clientId);
        return true;
    }

    /**
     * 获取所有在线用户的UID集合
     */
    @Override
    public Set<Object> getOnlineUidSet() {
        Set<Object> onlineUids = redisTemplate.opsForSet().members(PREFIX_ONLINE_UID_SET);
        return onlineUids != null ? onlineUids : Collections.emptySet();
    }

    /**
     * 根据客户端ID查询绑定的用户信息
     */
    @Override
    public SimpleUser getSimpleUserByClientId(String clientId) {
        if (clientId == null) {
            return null;
        }
        String clientKey = PREFIX_CLIENT_TO_USER + clientId;
        Object userObj = redisTemplate.opsForValue().get(clientKey);
        return userObj instanceof SimpleUser ? (SimpleUser) userObj : null;
    }

    /**
     * 根据用户ID查询绑定的客户端ID
     */
    @Override
    public String getClientIdByUid(String uid) {
        if (uid == null) {
            return null;
        }
        String userKey = PREFIX_USER_TO_CLIENT + uid;
        Object clientObj = redisTemplate.opsForValue().get(userKey);
        return clientObj instanceof String ? (String) clientObj : null;
    }

    /**
     * 移除客户端绑定关系并处理用户下线
     */
    @Override
    public void removeClientAndUidInSet(String clientId, String uid) {
        if (clientId == null || uid == null) {
            log.warn("移除绑定参数不完整：clientId={}, uid={}", clientId, uid);
            return;
        }

        // 直接调用清理方法
        cleanClientBinding(clientId);
        log.info("用户{}的客户端{}已下线", uid, clientId);
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
        if (uid == null) {
            return false;
        }
        Boolean isMember = redisTemplate.opsForSet().isMember(PREFIX_ONLINE_UID_SET, uid);
        return Boolean.TRUE.equals(isMember); // 处理null情况
    }

    /**
     * 根据客户端ID查询用户ID
     */
    @Override
    public String getUidByClientId(String clientId) {
        SimpleUser user = getSimpleUserByClientId(clientId);
        return user != null ? user.getUid() : null;
    }
}
