package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.pojo.vo.RegisterRequestVo;
import com.zzw.chatserver.service.SuperUserService;
import com.zzw.chatserver.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.Map;

@RestController
@RequestMapping("/superuser")
@Api(tags = "管理员相关接口")
public class SuperUserController {

    @Resource
    private SuperUserService superUserService;

    @Resource
    private UserService userService;

    /**
     * 管理员登录
     */
    @PostMapping("/login")
    @ApiOperation(value = "管理员登录", notes = "输入账号密码进行登录，返回token和用户信息") // 方法级别注解，说明接口功能
    public R superUserLogin(
            @ApiParam(name = "superUser", value = "管理员登录信息", required = true) // 参数注解，说明参数
            @RequestBody SuperUser superUser
    ) {
        Map<String, Object> resMap = superUserService.superUserLogin(superUser);
        Integer code = (Integer) resMap.get("code");
        if (code.equals(ResultEnum.LOGIN_SUCCESS.getCode()))
            return R.ok().resultEnum(ResultEnum.LOGIN_SUCCESS)
                    .data("userInfo", resMap.get("userInfo"))
                    .data("token", resMap.get("token"));
        else return R.error().code(code).message((String) resMap.get("msg"));
    }

    /**
     * 客服注册接口（仅超级管理员可访问）
     */
    @PostMapping("/registerService")
    public R registerServiceUser(@RequestBody RegisterRequestVo rVo) {
        // 获取当前登录用户（必须是超级管理员）
        String operatorId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Map<String, Object> resMap = userService.registerServiceUser(rVo, operatorId);
        Integer code = (Integer) resMap.get("code");
        if (code.equals(ResultEnum.REGISTER_SUCCESS.getCode())) {
            return R.ok()
                    .message("客服注册成功")
                    .data("userCode", resMap.get("userCode"))
                    .data("userId", resMap.get("userId"));
        } else {
            return R.error().code(code).message((String) resMap.get("msg"));
        }
    }
}
