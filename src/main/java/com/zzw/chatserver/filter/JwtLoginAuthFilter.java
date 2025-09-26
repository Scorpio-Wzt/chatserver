package com.zzw.chatserver.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzw.chatserver.auth.entity.JwtAuthUser;
import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.UserStatusEnum;
import com.zzw.chatserver.pojo.vo.LoginRequestVo;
import com.zzw.chatserver.service.OnlineUserService;
import com.zzw.chatserver.utils.JwtUtils;
import com.zzw.chatserver.utils.ResponseUtil;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
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

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT登录认证过滤器
 * 职责：处理用户登录请求、验证凭据、生成JWT令牌、更新用户登录状态
 * 新增功能：密码错误三次自动冻结账户
 */
@Slf4j
public class JwtLoginAuthFilter extends UsernamePasswordAuthenticationFilter {

    // 常量定义（统一维护，便于修改）
    private static final String LOGIN_PROCESS_URL = "/user/login";
    private static final String PASSWORD_ERROR_COUNT_KEY = "login:error:count:"; // Redis存储错误次数的key前缀
    private static final int MAX_ERROR_COUNT = 3; // 最大错误次数
    private static final long ERROR_COUNT_EXPIRE_HOURS = 24; // 错误计数过期时间（24小时）

    // 依赖组件（构造器注入，确保不可变）
    private final AuthenticationManager authenticationManager;
    private final MongoTemplate mongoTemplate;
    private final OnlineUserService onlineUserService;
    private final JwtUtils jwtUtils;
    private final RedisTemplate<String, Object> redisTemplate; // 新增Redis依赖用于计数

    // 线程本地存储（传递登录参数，避免request流重复读取）
    private final ThreadLocal<LoginRequestVo> loginRequestHolder = new ThreadLocal<>();
    // 线程本地存储用户名，用于认证失败时获取
    private final ThreadLocal<String> usernameHolder = new ThreadLocal<>();

    /**
     * 全参数构造器（依赖注入）
     */
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
        // 设置登录请求处理路径
        this.setFilterProcessesUrl(LOGIN_PROCESS_URL);
    }

    /**
     * 处理登录请求：解析参数并执行认证
     */
    @Override
    public Authentication attemptAuthentication(HttpServletRequest request,
                                                HttpServletResponse response) throws AuthenticationException {
        try {
            // 解析登录请求参数
            LoginRequestVo loginVo = new ObjectMapper().readValue(request.getInputStream(), LoginRequestVo.class);
            log.debug("接收登录请求，用户名: {}", loginVo.getUsername());

            // 验证必要参数
            if (!isValidLoginParam(loginVo)) {
                log.warn("登录参数不完整，username: {}", loginVo.getUsername());
                throw new IllegalArgumentException("用户名或密码不能为空");
            }

            // 检查用户是否已被冻结
            if (isUserAccountLocked(loginVo.getUsername())) {
                log.warn("用户账号已冻结，拒绝登录，username: {}", loginVo.getUsername());
                throw new BadCredentialsException("账号已被冻结，请24小时后再试");
            }

            // 存储参数到线程本地，供后续使用
            loginRequestHolder.set(loginVo);
            usernameHolder.set(loginVo.getUsername());

            // 执行认证（委托给AuthenticationManager）
            return authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginVo.getUsername(),
                            loginVo.getPassword(),
                            new ArrayList<>()
                    )
            );
        } catch (IOException e) {
            log.error("解析登录请求参数失败", e);
            throw new RuntimeException("登录请求格式错误");
        }
    }

    /**
     * 认证成功处理：生成令牌并返回结果，同时重置错误计数
     */
    @Override
    protected void successfulAuthentication(HttpServletRequest request,
                                            HttpServletResponse response,
                                            FilterChain chain,
                                            Authentication authResult) throws IOException, ServletException {
        try {
            // 从线程本地获取登录参数
            LoginRequestVo loginVo = loginRequestHolder.get();
            String username = usernameHolder.get();

            if (loginVo == null || username == null) {
                log.error("登录参数丢失，认证失败");
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.SYSTEM_ERROR));
                return;
            }

            // 验证认证结果类型
            if (!(authResult.getPrincipal() instanceof JwtAuthUser)) {
                log.error("认证结果类型错误，预期JwtAuthUser，实际: {}",
                        authResult.getPrincipal().getClass().getName());
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_LOGIN_FAILED));
                return;
            }

            // 获取认证用户信息
            JwtAuthUser jwtUser = (JwtAuthUser) authResult.getPrincipal();
            ObjectId userId = jwtUser.getUserId();

            // 核心校验：用户ID非空
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

            // 检查是否已登录
            if (onlineUserService.checkCurUserIsOnline(uid)) {
                log.warn("用户已在线，拒绝重复登录，uid: {}", uid);
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_HAS_LOGGED));
                return;
            }

            // 登录成功，重置密码错误计数
            resetPasswordErrorCount(username);

            // 更新用户登录信息
            updateUserLoginInfo(username, uid, loginVo);

            // 生成JWT令牌
            String token = jwtUtils.createJwt(uid, jwtUser.getUsername());
            log.debug("生成登录令牌，uid: {}, token: {}", uid, token.substring(0, 10) + "...");

            // 返回成功结果
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
     * 认证失败处理：记录错误次数，达到阈值则冻结账户
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

                // 检查是否达到冻结阈值
                if (errorCount >= MAX_ERROR_COUNT) {
                    // 冻结账户
                    freezeUserAccount(username);
                    log.warn("用户密码错误次数达到{}次，已冻结账户，username: {}", MAX_ERROR_COUNT, username);
                    ResponseUtil.out(response, R.error()
                            .resultEnum(ResultEnum.USER_LOGIN_FAILED)
                            .message("密码错误次数过多，账户已冻结，请24小时后再试"));
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
}
