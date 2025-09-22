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
 */
@Service
public class OnlineUserServiceImpl implements OnlineUserService {

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 绑定客户端ID与用户信息，并将UID加入在线集合
     */
    @Override
    public void addClientIdToSimpleUser(String clientId, SimpleUser simpleUser) {
        // 1. 绑定客户端ID与用户信息（调整过期时间为10分钟，足够重连场景，避免无效客户端信息残留）
        String clientKey = RedisKeyUtil.getClientKey(clientId);
//        redisTemplate.opsForValue().set(clientKey, simpleUser, 60 * 60 * 24, TimeUnit.SECONDS);
        redisTemplate.opsForValue().set(clientKey, simpleUser, 60 * 10, TimeUnit.SECONDS);

        // 2. 将用户UID加入在线用户Set集合
        String onlineUidSetKey = RedisKeyUtil.getOnlineUidSetKey();
        redisTemplate.opsForSet().add(onlineUidSetKey, simpleUser.getUid());
    }

    /**
     * 获取所有在线用户的UID集合
     */
    @Override
    public Set<Object> getOnlineUidSet() {
        String onlineUidSetKey = RedisKeyUtil.getOnlineUidSetKey();
        return redisTemplate.opsForSet().members(onlineUidSetKey);
    }

    /**
     * 根据客户端ID查询绑定的用户信息
     */
    @Override
    public SimpleUser getSimpleUserByClientId(String clientId) {
        String clientKey = RedisKeyUtil.getClientKey(clientId);
        Object userObj = redisTemplate.opsForValue().get(clientKey);
        // 类型转换：无绑定返回null，有绑定返回SimpleUser
        return userObj == null ? null : (SimpleUser) userObj;
    }

    /**
     * 移除客户端绑定关系并删除在线UID（用户下线逻辑）
     */
    @Override
    public void removeClientAndUidInSet(String clientId, String uid) {
        // 1. 删除客户端与用户的绑定关系
        String clientKey = RedisKeyUtil.getClientKey(clientId);
        redisTemplate.delete(clientKey);

        // 2. 从在线用户Set集合中移除该UID
        String onlineUidSetKey = RedisKeyUtil.getOnlineUidSetKey();
        redisTemplate.opsForSet().remove(onlineUidSetKey, uid);
    }

    /**
     * 统计当前在线用户数量
     */
    @Override
    public int countOnlineUser() {
        String onlineUidSetKey = RedisKeyUtil.getOnlineUidSetKey();
        Set<Object> onlineUids = redisTemplate.opsForSet().members(onlineUidSetKey);
        // 集合为null时返回0，否则返回集合大小
        return onlineUids == null ? 0 : onlineUids.size();
    }

    /**
     * 校验指定用户是否在线
     */
    @Override
    public boolean checkCurUserIsOnline(String uid) {
        String onlineUidSetKey = RedisKeyUtil.getOnlineUidSetKey();
        Boolean isMember = redisTemplate.opsForSet().isMember(onlineUidSetKey, uid);
        // Redis查询异常（返回null）时，默认视为离线
        return isMember == null ? false : isMember;
    }
}