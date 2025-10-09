package com.zzw.chatserver.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import com.zzw.chatserver.auth.entity.JwtAuthUser;
import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.UserStatusEnum;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.LoginRequestVo;
import com.zzw.chatserver.service.OnlineUserService;
import com.zzw.chatserver.utils.JwtUtils;
import com.zzw.chatserver.utils.ResponseUtil;
import com.zzw.chatserver.utils.SocketIoServerMapUtil;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import javax.annotation.Resource;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.TimeUnit;

@Slf4j
public class JwtLoginAuthFilter extends UsernamePasswordAuthenticationFilter {

    // 常量定义（统一维护，便于修改）
    private static final String LOGIN_PROCESS_URL = "/user/login";
    private static final String PASSWORD_ERROR_COUNT_KEY = "login:error:count:"; // Redis存储错误次数的key前缀
    private static final String ACCOUNT_LOCK_KEY = "login:lock:"; // Redis存储账户锁定的key前缀
    private static final int MAX_ERROR_COUNT = 3; // 最大错误次数
    private static final long ERROR_COUNT_EXPIRE_HOURS = 24; // 错误计数过期时间（24小时）
    private static final long LOCK_DURATION_MINUTES = 15; // 账户锁定时长（15分钟）

    // 依赖组件（构造器注入，确保不可变）
    private final AuthenticationManager authenticationManager;
    private final MongoTemplate mongoTemplate;
    private final OnlineUserService onlineUserService;
    private final JwtUtils jwtUtils;
    private final RedisTemplate<String, Object> redisTemplate; // Redis依赖用于计数

    // 线程本地存储（传递登录参数，避免request流重复读取）
    private final ThreadLocal<LoginRequestVo> loginRequestHolder = new ThreadLocal<>();
    // 线程本地存储用户名，用于认证失败时获取
    private final ThreadLocal<String> usernameHolder = new ThreadLocal<>();
    // 在JwtLoginAuthFilter中增加成员变量存储登录参数
    private final ThreadLocal<LoginRequestVo> loginRequestVoThreadLocal = new ThreadLocal<>();


    // 构造器参数：AuthenticationManager + 其他业务依赖
    public JwtLoginAuthFilter(AuthenticationManager authenticationManager,
                              MongoTemplate mongoTemplate,
                              OnlineUserService onlineUserService,
                              JwtUtils jwtUtils,
                              RedisTemplate<String, Object> redisTemplate) {
        this.authenticationManager = authenticationManager;
        this.mongoTemplate = mongoTemplate;
        this.onlineUserService = onlineUserService;
        this.jwtUtils = jwtUtils;
        this.redisTemplate = redisTemplate;
        this.setFilterProcessesUrl(LOGIN_PROCESS_URL);
    }

    //登录验证
    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
        try {
            // 解析登录请求参数
            LoginRequestVo lvo = new ObjectMapper().readValue(request.getInputStream(), LoginRequestVo.class);
            log.debug("接收登录请求，用户名: {}", lvo.getUsername());

            // 验证必要参数
            if (!isValidLoginParam(lvo)) {
                log.warn("登录参数不完整，username: {}", lvo.getUsername());
                throw new IllegalArgumentException("用户名或密码不能为空");
            }

            // 检查用户是否已被冻结
            if (isUserAccountLocked(lvo.getUsername())) {
                log.warn("用户账号已冻结，拒绝登录，username: {}", lvo.getUsername());
                throw new BadCredentialsException("账号已被冻结，请24小时后再试");
            }

            // 存储参数到线程本地，供后续使用
            loginRequestVoThreadLocal.set(lvo); // 存储到 ThreadLocal
            usernameHolder.set(lvo.getUsername());

            return authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(lvo.getUsername(), lvo.getPassword(), new ArrayList<>())
            );
        } catch (IOException e) {
            logger.error("读取登录参数失败", e);
            throw new RuntimeException("登录参数解析失败");
        }
    }

    //登录验证成功后调用，生成Token并返回结果
    // 修改 JwtLoginAuthFilter 的 successfulAuthentication 方法
    @Override
    protected void successfulAuthentication(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain,
                                            Authentication authResult) throws IOException, ServletException {
        try {
            // 读取登录参数（修复 InputStream 重复读取问题，改用 ThreadLocal）
            LoginRequestVo lvo = loginRequestVoThreadLocal.get(); // 需提前在 attemptAuthentication 中存入
            String username = usernameHolder.get();

            if (lvo == null || username == null) {
                log.error("登录参数丢失，认证失败");
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.SYSTEM_ERROR));
                return;
            }

            if (!(authResult.getPrincipal() instanceof JwtAuthUser)) {
                log.error("认证结果类型错误，预期JwtAuthUser，实际: {}",
                        authResult.getPrincipal().getClass().getName());
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_LOGIN_FAILED));
                return;
            }

            // 获取认证用户（此时已为 JwtAuthUser 类型）
            JwtAuthUser jwtUser = (JwtAuthUser) authResult.getPrincipal();

            if (jwtUser == null) {
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_LOGIN_FAILED));
                return;
            }

            // 检查 userId 是否为 null（关键防御）
            ObjectId userId = jwtUser.getUserId();
            if (userId == null) {
                log.error("用户ID为空，登录失败，username: {}", jwtUser.getUsername());
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_LOGIN_FAILED));
                return;
            }

            String uid = userId.toString();
            log.info("用户认证成功，username: {}, uid: {}", jwtUser.getUsername(), uid);

            // 检查用户状态（冻结/注销）
            if (isUserDisabled(jwtUser.getStatus())) {
                log.warn("用户账号异常，status: {}, uid: {}", jwtUser.getStatus(), uid);
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.ACCOUNT_IS_FROZEN_OR_CANCELLED));
                return;
            }

            if (onlineUserService.checkCurUserIsOnline(uid)) {
                log.warn("用户已在线，拒绝重复登录，uid: {}", uid);
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_HAS_LOGGED));
                return;
            }

            // 登录成功，重置密码错误计数
            resetPasswordErrorCount(username);

            // 更新用户登录信息
            updateUserLoginInfo(username, uid, lvo);

            // 更新用户信息（使用非 null 的 userId）
            Query query = new Query();
            query.addCriteria(new Criteria().orOperator(
                    Criteria.where("username").is(jwtUser.getUsername()),
                    Criteria.where("code").is(jwtUser.getUsername())
            ));
            Update update = new Update();
            update.set("lastLoginTime", new Date());
            update.set("loginSetting", lvo.getSetting());
            update.set("uid", userId.toString()); // 此处已确保 userId 非 null

            // 生成 token（使用非 null 的 userId）
            String token = jwtUtils.createJwt(userId.toString(), jwtUser.getUsername());
            ResponseUtil.out(response, R.ok()
                    .resultEnum(ResultEnum.LOGIN_SUCCESS)
                    .data("token", token)
                    .data("userInfo", jwtUser));

        } catch (IllegalArgumentException e) {
            log.warn("登录参数校验失败: {}", e.getMessage());
            ResponseUtil.out(response, R.error().message(e.getMessage()));
        } catch (Exception e) {
            log.error("登录成功后处理异常", e);
            ResponseUtil.out(response, R.error().resultEnum(ResultEnum.SYSTEM_ERROR));
        } finally {
            // 清除线程本地存储，避免内存泄漏
            loginRequestHolder.remove();
            usernameHolder.remove();
        }
    }
    /**
     * 认证失败处理：记录错误次数，达到阈值则锁定账户15分钟
     */
    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request,
                                              HttpServletResponse response,
                                              AuthenticationException failed) throws IOException, ServletException {
        String username = usernameHolder.get();
        try {
            // 如果是密码错误，记录错误次数
            if (failed instanceof BadCredentialsException && username != null) {
                log.warn("用户登录失败，用户名: {}, 原因: {}", username, failed.getMessage());

                // 增加错误计数
                int errorCount = incrementPasswordErrorCount(username);

                // 检查是否达到锁定阈值
                if (errorCount >= MAX_ERROR_COUNT) {
                    // 锁定账户15分钟
                    lockUserAccount(username);
                    log.warn("用户密码错误次数达到{}次，已锁定账户15分钟，username: {}", MAX_ERROR_COUNT, username);
                    ResponseUtil.out(response, R.error()
                            .resultEnum(ResultEnum.USER_LOGIN_FAILED)
                            .message("密码错误次数过多，账号已锁定，请15分钟后再试"));
                    return;
                } else {
                    // 提示剩余次数
                    int remainingCount = MAX_ERROR_COUNT - errorCount;
                    ResponseUtil.out(response, R.error()
                            .resultEnum(ResultEnum.USER_LOGIN_FAILED)
                            .message("用户名或密码错误，还剩" + remainingCount + "次机会"));
                    return;
                }
            }

            // 其他认证失败原因
            String message = "登录失败";
            if (failed.getMessage().contains("Locked")) {
                message = "账号已锁定";
            } else if (failed.getMessage().contains("Disabled")) {
                message = "账号已禁用";
            }
            ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_LOGIN_FAILED).message(message));

        } catch (Exception e) {
            log.error("处理登录失败异常，username: {}", username, e);
            ResponseUtil.out(response, R.error().resultEnum(ResultEnum.SYSTEM_ERROR));
        } finally {
            // 清除线程本地存储
            usernameHolder.remove();
        }
    }

    // ------------------------------ 私有工具方法 ------------------------------

    /**
     * 验证登录参数是否完整
     */
    private boolean isValidLoginParam(LoginRequestVo loginVo) {
        return loginVo != null
                && org.springframework.util.StringUtils.hasText(loginVo.getUsername())
                && org.springframework.util.StringUtils.hasText(loginVo.getPassword());
    }

    /**
     * 判断用户是否被禁用（状态1:冻结，2:注销）
     */
    private boolean isUserDisabled(Integer status) {
        return status != null && (status == UserStatusEnum.FREEZED.getCode()
                || status == UserStatusEnum.CANCELED.getCode());
    }

    /**
     * 更新用户登录信息（最后登录时间、登录设置、UID）
     */
    private void updateUserLoginInfo(String username, String uid, LoginRequestVo loginVo) {
        Query query = new Query(Criteria.where("username").is(username)
                .orOperator(Criteria.where("code").is(username)));

        Update update = new Update()
                .set("lastLoginTime", new Date())
                .set("uid", uid);

        // 仅当登录设置不为空时更新
        if (loginVo.getSetting() != null) {
            update.set("loginSetting", loginVo.getSetting());
        }

        mongoTemplate.updateFirst(query, update, "users");
        log.debug("更新用户登录信息，username: {}", username);
    }

    /**
     * 生成密码错误计数的Redis键
     */
    private String getPasswordErrorCountKey(String username) {
        return PASSWORD_ERROR_COUNT_KEY + username;
    }

    /**
     * 生成账户锁定的Redis键
     */
    private String getAccountLockKey(String username) {
        return ACCOUNT_LOCK_KEY + username;
    }

    /**
     * 增加密码错误计数
     */
    private int incrementPasswordErrorCount(String username) {
        String key = getPasswordErrorCountKey(username);
        Long count = redisTemplate.opsForValue().increment(key);
        // 设置过期时间（仅在第一次设置时）
        if (count != null && count == 1) {
            redisTemplate.expire(key, ERROR_COUNT_EXPIRE_HOURS, TimeUnit.HOURS);
        }
        return count != null ? count.intValue() : 1;
    }

    /**
     * 重置密码错误计数
     */
    private void resetPasswordErrorCount(String username) {
        String key = getPasswordErrorCountKey(username);
        redisTemplate.delete(key);
        log.debug("重置密码错误计数，username: {}", username);
    }

    /**
     * 锁定用户账户（15分钟）
     */
    private void lockUserAccount(String username) {
        String key = getAccountLockKey(username);
        redisTemplate.opsForValue().set(key, true);
        redisTemplate.expire(key, LOCK_DURATION_MINUTES, TimeUnit.MINUTES);
    }

    /**
     * 解锁用户账户
     */
    private void unlockUserAccount(String username) {
        String key = getAccountLockKey(username);
        redisTemplate.delete(key);
        log.debug("解锁用户账户，username: {}", username);
    }

    /**
     * 冻结用户账户
     */
    private void freezeUserAccount(String username) {
        Query query = new Query(Criteria.where("username").is(username)
                .orOperator(Criteria.where("code").is(username)));

        Update update = new Update()
                .set("status", UserStatusEnum.FREEZED.getCode())
                .set("freezeTime", new Date());

        mongoTemplate.updateFirst(query, update, "users");
    }

    /**
     * 检查用户账户是否已被冻结
     */
    private boolean isUserAccountLocked(String username) {
        Query query = new Query(Criteria.where("username").is(username)
                .orOperator(Criteria.where("code").is(username))
                .and("status").is(UserStatusEnum.FREEZED.getCode()));

        return mongoTemplate.exists(query, "users");
    }

    /**
     * 检查锁定是否已过期
     */
    private boolean isLockExpired(String username) {
        String key = getAccountLockKey(username);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.MINUTES);
        return ttl == null || ttl <= 0;
    }

    /**
     * 获取剩余锁定时间（分钟）
     */
    private long getRemainingLockTime(String username) {
        String key = getAccountLockKey(username);
        Long ttl = redisTemplate.getExpire(key, TimeUnit.MINUTES);
        return ttl != null && ttl > 0 ? ttl : 0;
    }
}