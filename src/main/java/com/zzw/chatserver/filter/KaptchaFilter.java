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

    private final RedisTemplate<String, String> customStringRedisTemplate;

    // 仅通过构造函数注入
    public KaptchaFilter(RedisTemplate<String, String> redisTemplate) {
        this.customStringRedisTemplate = redisTemplate;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;
        String requestURI = req.getServletPath();
        String method = req.getMethod();

        // 登录接口（/chat/user/register）直接放行，不校验验证码
        if ("POST".equals(method) && "/user/register".equals(requestURI)) {
            filterChain.doFilter(req, resp);
            return;
        }

        // 对普通登录（/user/login) 校验验证码
        if ("POST".equals(method) && "/user/login".equals(requestURI)) {
            String kaptchaOwner = CookieUtil.getValue(req, "kaptchaOwner");
            ServletRequest requestWrapper = new HttpServletRequestReplacedWrapper(req); // 包装请求体（解决流只能读一次问题）
            String cvCode = HttpServletRequestUtil.getBodyTxt(requestWrapper, "cvCode");
            String kaptcha = null;

            if (StringUtils.isNotBlank(kaptchaOwner)) {
                String redisKey = RedisKeyUtil.getKaptchaKey(kaptchaOwner);
                kaptcha = customStringRedisTemplate.opsForValue().get(redisKey);
            }

            // 验证码校验失败
            if (StringUtils.isBlank(kaptcha) || StringUtils.isBlank(cvCode) || !kaptcha.equalsIgnoreCase(cvCode)) {
                ResponseUtil.out(resp, R.error().resultEnum(ResultEnum.KAPTCHA_TIME_OUT_OR_ERROR));
                return;
            }

            // 验证码校验通过，用包装后的请求继续传递
            filterChain.doFilter(requestWrapper, resp);
        } else {
            // 其他所有请求直接放行
            filterChain.doFilter(req, resp);
        }
    }
}
