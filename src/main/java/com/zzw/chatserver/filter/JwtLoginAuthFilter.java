package com.zzw.chatserver.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.result.UpdateResult;
import com.zzw.chatserver.auth.entity.JwtAuthUser;
import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.LoginRequestVo;
import com.zzw.chatserver.service.OnlineUserService;
import com.zzw.chatserver.utils.JwtUtils;
import com.zzw.chatserver.utils.ResponseUtil;
import com.zzw.chatserver.utils.SocketIoServerMapUtil;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.AuthenticationManager;
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

public class JwtLoginAuthFilter extends UsernamePasswordAuthenticationFilter {

    private static final Logger logger = LoggerFactory.getLogger(JwtLoginAuthFilter.class);

    private AuthenticationManager authenticationManager;

    private MongoTemplate mongoTemplate;

    private OnlineUserService onlineUserService;

    @Resource
    private final JwtUtils jwtUtils;

    // 在JwtLoginAuthFilter中增加成员变量存储登录参数
    private ThreadLocal<LoginRequestVo> loginRequestVoThreadLocal = new ThreadLocal<>();

    // 构造器参数：AuthenticationManager + 其他业务依赖
    public JwtLoginAuthFilter(AuthenticationManager authenticationManager,
                              MongoTemplate mongoTemplate,
                              OnlineUserService onlineUserService,
                              JwtUtils jwtUtils) {
        this.authenticationManager = authenticationManager;
        this.mongoTemplate = mongoTemplate;
        this.onlineUserService = onlineUserService;
        this.jwtUtils = jwtUtils; // 手动赋值
        this.setFilterProcessesUrl("/user/login");
    }

    //登录验证
    @Override
    public Authentication attemptAuthentication(HttpServletRequest request, HttpServletResponse response) throws AuthenticationException {
        try {
            LoginRequestVo lvo = new ObjectMapper().readValue(request.getInputStream(), LoginRequestVo.class);
            loginRequestVoThreadLocal.set(lvo); // 存储到 ThreadLocal
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
            if (lvo == null) {
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.SYSTEM_ERROR));
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
                logger.error("用户ID为null，登录失败");
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_LOGIN_FAILED));
                return;
            }

            if (jwtUser.getStatus() == 1 || jwtUser.getStatus() == 2) {
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.ACCOUNT_IS_FROZEN_OR_CANCELLED));
                return;
            }
            if (onlineUserService.checkCurUserIsOnline(userId.toString())) {
                ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_HAS_LOGGED));
                return;
            }

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

        } catch (Exception e) {
            logger.error("登录成功处理异常", e);
            ResponseUtil.out(response, R.error().resultEnum(ResultEnum.SYSTEM_ERROR));
        } finally {
            // 清除 ThreadLocal，避免内存泄漏
            loginRequestVoThreadLocal.remove();
        }
    }
    //登录验证失败后调用
    @Override
    protected void unsuccessfulAuthentication(HttpServletRequest request,
                                              HttpServletResponse response,
                                              AuthenticationException failed) throws IOException, ServletException {
        ResponseUtil.out(response, R.error().resultEnum(ResultEnum.USER_LOGIN_FAILED));
    }
}