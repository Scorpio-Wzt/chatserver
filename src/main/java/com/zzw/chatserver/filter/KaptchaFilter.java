package com.zzw.chatserver.filter;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.utils.CookieUtil;
import com.zzw.chatserver.utils.HttpServletRequestUtil;
import com.zzw.chatserver.utils.RedisKeyUtil;
import com.zzw.chatserver.utils.ResponseUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.RedisTemplate;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

// 验证码过滤器
public class KaptchaFilter extends GenericFilter {

    private RedisTemplate<String, String> redisTemplate;

    public KaptchaFilter(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;
        String requestURI = req.getServletPath();
        String method = req.getMethod();

        // 1. 登录接口（/chat/user/login）直接放行，不校验验证码
        if ("POST".equals(method) && "/chat/user/login".equals(requestURI)) {
            filterChain.doFilter(req, resp); // 用filterChain传递请求，而非super
            return;
        }

        // 2. 对普通登录（/user/login）和注册（/user/register）校验验证码
        if ("POST".equals(method) && ("/user/login".equals(requestURI) || "/user/register".equals(requestURI))) {
            String kaptchaOwner = CookieUtil.getValue(req, "kaptchaOwner");
            ServletRequest requestWrapper = new HttpServletRequestReplacedWrapper(req); // 包装请求体
            String cvCode = HttpServletRequestUtil.getBodyTxt(requestWrapper, "cvCode");
            String kaptcha = null;

            if (StringUtils.isNotBlank(kaptchaOwner)) {
                String redisKey = RedisKeyUtil.getKaptchaKey(kaptchaOwner);
                kaptcha = redisTemplate.opsForValue().get(redisKey);
            }

            // 验证码校验失败
            if (StringUtils.isBlank(kaptcha) || StringUtils.isBlank(cvCode) || !kaptcha.equalsIgnoreCase(cvCode)) {
                ResponseUtil.out(resp, R.error().resultEnum(ResultEnum.KAPTCHA_TIME_OUT_OR_ERROR));
                return;
            }

            // 验证码校验通过，用包装后的请求继续传递
            filterChain.doFilter(requestWrapper, resp);
        } else {
            // 3. 其他所有请求直接放行
            filterChain.doFilter(req, resp); // 始终用filterChain传递
        }
    }
}