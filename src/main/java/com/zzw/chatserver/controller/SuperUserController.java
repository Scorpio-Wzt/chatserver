package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.service.SuperUserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/superuser")
@Api(tags = "管理员相关接口")
//@CrossOrigin(origins = {"${app.cors.allowed-origins}"}, methods = {RequestMethod.POST}) // 限定允许的源和方法，增强安全性
@Slf4j // 引入日志
@Validated // 开启参数校验
public class SuperUserController {

    @Resource
    private SuperUserService superUserService;

    /**
     * 管理员登录
     */
    @PostMapping("/login")
    @ApiOperation(value = "管理员登录", notes = "输入管理员账号和密码登录，成功返回token和用户信息（不含密码）")
    public R superUserLogin(
            @ApiParam(name = "superUser", value = "管理员登录信息（账号和密码为必填）",
                    required = true, example = "{\"username\":\"admin\",\"password\":\"123456\"}")
            @RequestBody @Valid SuperUser superUser // @Valid 触发SuperUser内部的参数校验
    ) {
        try {
            log.info("管理员登录请求：用户名={}", superUser.getAccount()); // 日志记录请求（脱敏处理，不记录密码）

            // 调用服务层登录逻辑（优化点：让service层在失败时直接抛异常，而非返回code）
            Map<String, Object> resMap = superUserService.superUserLogin(superUser);

            // 登录成功处理
            log.info("管理员登录成功：用户名={}", superUser.getAccount());
            return R.ok().resultEnum(ResultEnum.LOGIN_SUCCESS)
                    .data("userInfo", resMap.get("userInfo"))
                    .data("token", resMap.get("token"));

        } catch (BusinessException e) {
            // 捕获已知业务异常（如账号密码错误、账号禁用等）
            log.warn("管理员登录失败（业务异常）：用户名={}，原因={}", superUser.getAccount(), e.getMessage());
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            // 捕获未知系统异常
            log.error("管理员登录失败（系统异常）：用户名={}", superUser.getAccount(), e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR).message("登录失败，请稍后重试");
        }
    }
}
