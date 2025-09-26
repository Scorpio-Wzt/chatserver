package com.zzw.chatserver.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.pojo.vo.LoginRequestVo;
import com.zzw.chatserver.utils.CookieUtil;
import com.zzw.chatserver.utils.RedisKeyUtil;
import com.zzw.chatserver.utils.ResponseUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

// 验证码过滤器
@Slf4j
public class KaptchaFilter extends GenericFilter {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper; // 统一用ObjectMapper解析JSON

    // 构造器注入（需在SecurityConfig中配置注入ObjectMapper）
    public KaptchaFilter(
            @Qualifier("kaptchaRedisTemplate")  // 关键：指定使用kaptchaRedisTemplate
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) servletRequest;
        HttpServletResponse resp = (HttpServletResponse) servletResponse;
        String requestURI = req.getServletPath();
        String method = req.getMethod();

        // 1. 注册接口放行（无需验证码）
        if ("POST".equals(method) && "/user/register".equals(requestURI)) {
            filterChain.doFilter(req, resp);
            return;
        }

        // 2. 登录接口校验验证码
        if ("POST".equals(method) && "/user/login".equals(requestURI)) {
            // 2.1 从Cookie获取kaptchaOwner
            String kaptchaOwner = CookieUtil.getValue(req, "kaptchaOwner");
            System.out.println("kaptchaOwner:" + kaptchaOwner);
            if (StringUtils.isBlank(kaptchaOwner)) {
                ResponseUtil.out(resp, R.error().resultEnum(ResultEnum.KAPTCHA_TIME_OUT_OR_ERROR));
                return;
            }

            // 2.2 包装请求体（解决流只能读一次的问题）
            HttpServletRequestReplacedWrapper requestWrapper = new HttpServletRequestReplacedWrapper(req);

            // 2.3 解析LoginRequestVo，获取前端输入的cvCode
            LoginRequestVo loginVo = null;
            try {
                loginVo = objectMapper.readValue(requestWrapper.getInputStream(), LoginRequestVo.class);
            } catch (IOException e) {
                ResponseUtil.out(resp, R.error().message("登录请求格式错误"));
                return;
            }
            String cvCode = loginVo.getCvCode();
            if (StringUtils.isBlank(cvCode)) {
                ResponseUtil.out(resp, R.error().resultEnum(ResultEnum.KAPTCHA_TIME_OUT_OR_ERROR));
                return;
            }

            // 2.4 从Redis获取存储的验证码
            String redisKey = RedisKeyUtil.getKaptchaKey(kaptchaOwner);
            System.out.println("redisKey:" + redisKey);
            String kaptcha = redisTemplate.opsForValue().get(redisKey);
            log.debug("从Redis读取验证码 - redisKey: {}, 读取到的验证码: {}", redisKey, kaptcha);

            // 验证码对比前添加日志
            log.debug("验证码对比 - 前端输入(cvCode): {}, Redis存储(kaptcha): {}", cvCode, kaptcha);
            // 2.5 验证码校验
            if (StringUtils.isBlank(kaptcha) || !kaptcha.equalsIgnoreCase(cvCode)) {
                ResponseUtil.out(resp, R.error().resultEnum(ResultEnum.KAPTCHA_TIME_OUT_OR_ERROR));
                return;
            }

            // 2.6 校验通过：删除Redis验证码（防止重复使用）
            redisTemplate.delete(redisKey);

            // 2.7 传递包装后的请求给后续过滤器
            filterChain.doFilter(requestWrapper, resp);
            return;
        }

        // 3. 其他请求直接放行
        filterChain.doFilter(req, resp);
    }

    // 正确的HttpServletRequest包装类：缓存请求体，支持多次读取
    private static class HttpServletRequestReplacedWrapper extends javax.servlet.http.HttpServletRequestWrapper {
        private final byte[] body;

        public HttpServletRequestReplacedWrapper(HttpServletRequest request) throws IOException {
            super(request);
            // 读取请求体并缓存（使用UTF-8编码，避免中文乱码）
            body = org.springframework.util.StreamUtils.copyToByteArray(request.getInputStream());
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new ByteArrayServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        // 内部类：实现ServletInputStream
        private static class ByteArrayServletInputStream extends javax.servlet.ServletInputStream {
            private final ByteArrayInputStream inputStream;

            public ByteArrayServletInputStream(byte[] buffer) {
                this.inputStream = new ByteArrayInputStream(buffer);
            }

            @Override
            public int read() throws IOException {
                return inputStream.read();
            }

            @Override
            public boolean isFinished() {
                return inputStream.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(javax.servlet.ReadListener listener) {
                throw new UnsupportedOperationException("不支持ReadListener");
            }
        }
    }
}