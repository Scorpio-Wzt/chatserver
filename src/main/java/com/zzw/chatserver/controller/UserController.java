package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.common.UserStatusEnum;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.*;
import com.zzw.chatserver.service.SuperUserService;
import com.zzw.chatserver.service.UserService;
import com.zzw.chatserver.utils.ChatServerUtil;
import com.zzw.chatserver.utils.RedisKeyUtil;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@CrossOrigin
@RestController
@RequestMapping("/user")
@Api(tags = "用户相关接口")
@Slf4j // 日志支持
@Validated // 开启参数校验
public class UserController {

    @Resource
    private UserService userService;

    @Resource
    private SuperUserService superUserService;

    @Value("${server.servlet.context-path}")
    private String contextPath;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 获取验证码（用于注册/登录验证）
     */
    @GetMapping("/getCode")
    @ApiOperation(value = "获取验证码", notes = "生成验证码并通过cookie返回标识，验证码有效期60秒")
    public R getKaptcha(HttpServletResponse response) {
        try {
            // 生成验证码唯一标识（用于关联cookie和Redis）
            String kaptchaOwner = ChatServerUtil.generateUUID();
            Cookie cookie = new Cookie("kaptchaOwner", kaptchaOwner);
            cookie.setMaxAge(60); // 有效期60秒（无需加时区，maxAge是绝对秒数）
            cookie.setPath("/"); // 全局有效
            response.addCookie(cookie);

            // 生成验证码并存入Redis
            String verificationCode = ChatServerUtil.generatorCode();
            String redisKey = RedisKeyUtil.getKaptchaKey(kaptchaOwner);
            // redis 有效时间为 60s
            redisTemplate.opsForValue().set(redisKey, verificationCode, 60, TimeUnit.SECONDS);

            log.info("生成验证码成功：owner={}, code={}", kaptchaOwner, verificationCode);
            return R.ok().data("code", verificationCode);
        } catch (Exception e) {
            log.error("生成验证码失败", e);
            return R.error().message("验证码生成失败，请重试");
        }
    }


    /**
     * 注册客服用户（需管理员权限）
     */
    @PostMapping("/registerService")
    @ApiOperation(value = "注册客服用户", notes = "仅超级管理员或普通管理员可操作")
    public R registerServiceUser(
            @ApiParam(value = "客服注册信息", required = true)
            @RequestBody @Valid RegisterRequestVo rVo) {
        try {
            // 校验当前用户是否有指定角色
            checkUserHasAnyRole(new String[]{"SUPER_ADMIN", "ADMIN"});

            String operatorId = getCurrentOperatorId();
            if (operatorId == null) {
                return R.error().code(ResultEnum.SYSTEM_ERROR.getCode()).message("无法获取当前用户ID，注册失败");
            }

            Map<String, Object> resMap = userService.registerServiceUser(rVo, operatorId);
            Integer code = (Integer) resMap.get("code");

            if (code.equals(ResultEnum.REGISTER_SUCCESS.getCode())) {
                log.info("客服注册成功：operatorId={}, userCode={}", operatorId, resMap.get("userCode"));
                return R.ok()
                        .message("客服注册成功")
                        .data("userCode", resMap.get("userCode"))
                        .data("userId", resMap.get("userId"));
            } else {
                log.warn("客服注册失败：operatorId={}, 原因={}", operatorId, resMap.get("msg"));
                return R.error().code(code).message((String) resMap.get("msg"));
            }
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("客服注册异常", e);
            return R.error().message("注册失败，请稍后重试");
        }
    }

    /**
     * 普通用户注册
     */
    @PostMapping("/register")
    @ApiOperation(value = "普通用户注册", notes = "禁止注册客服角色（客服需通过管理员接口创建）")
    public R register(
            @ApiParam(value = "用户注册信息", required = true)
            @RequestBody @Valid RegisterRequestVo rVo) { // 启用参数校验
        try {
            // 禁止注册客服角色
            if (UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(rVo.getRole())) {
                log.warn("普通注册尝试创建客服角色：username={}", rVo.getUsername());
                return R.error().message("客服账号需通过管理员后台创建");
            }

            Map<String, Object> resMap = userService.register(rVo);
            Integer code = (Integer) resMap.get("code");

            if (code.equals(ResultEnum.REGISTER_SUCCESS.getCode())) {
                log.info("用户注册成功：username={}", rVo.getUsername());
                return R.ok().resultEnum(ResultEnum.REGISTER_SUCCESS)
                        .data("userCode", resMap.get("userCode"));
            } else {
                log.warn("用户注册失败：username={}, 原因={}", rVo.getUsername(), resMap.get("msg"));
                return R.error().code(code).message((String) resMap.get("msg"));
            }
        } catch (Exception e) {
            log.error("用户注册异常：username={}", rVo.getUsername(), e);
            return R.error().message("注册失败，请稍后重试");
        }
    }

    @PostMapping("/changeStatus")
    @ApiOperation(value = "修改用户账号状态", notes = "仅管理员可操作，支持设置正常、冻结、注销三种状态")
    public R changeUserStatus(
            @ApiParam(value = "用户状态修改请求", required = true)
            @RequestBody @Valid ChangeUserStatusRequestVo requestVo) {
        try {
            // 权限校验：调用已有工具方法检查是否为管理员角色
            checkUserHasAnyRole(new String[]{"SUPER_ADMIN", "ADMIN"});

            // 校验状态值是否合法
            if (!Arrays.asList(
                    UserStatusEnum.NORMAL.getCode(),
                    UserStatusEnum.FREEZED.getCode(),
                    UserStatusEnum.CANCELED.getCode()
            ).contains(requestVo.getStatus())) {
                return R.error().message("无效的状态值，允许的值：0(正常),1(冻结),2(注销)");
            }

            // 执行状态修改
            userService.changeUserStatus(requestVo.getUserId(), requestVo.getStatus());

            // 记录操作日志
            String operatorId = getCurrentOperatorId();
            log.info("管理员[{}]修改用户[{}]状态为[{}({})]",
                    operatorId,
                    requestVo.getUserId(),
                    requestVo.getStatus(),
                    UserStatusEnum.valueOfCode(requestVo.getStatus()).getDesc());

            return R.ok().message("用户状态修改成功");
        } catch (AuthenticationCredentialsNotFoundException e) {
            log.warn("修改用户状态失败：{}", e.getMessage());
            return R.error().message("请先登录");
        } catch (AccessDeniedException e) {
            log.warn("修改用户状态失败：{}", e.getMessage());
            return R.error().message("权限不足，仅管理员可操作");
        } catch (BusinessException e) {
            log.warn("修改用户状态失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("修改用户状态异常：{}", requestVo, e);
            return R.error().message("修改用户状态失败");
        }
    }


    /**
     * 添加新的好友分组
     */
    @PostMapping("/addFenZu")
    @ApiOperation(value = "添加好友分组", notes = "创建用户的好友分组（如“家人”“同事”）")
    public R addNewFenZu(
            @ApiParam(value = "新分组信息（含用户ID和分组名）", required = true)
            @RequestBody @Valid NewFenZuRequestVo requestVo) { // 启用参数校验
        try {
            userService.addNewFenZu(requestVo);
            log.info("添加新分组成功：userId={}, 分组名={}", requestVo.getUserId(), requestVo.getFenZuName());
            return R.ok().message("添加新分组成功");
        } catch (BusinessException e) {
            log.warn("添加分组失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("添加分组系统异常：{}", requestVo, e);
            return R.error().message("添加分组失败");
        }
    }

    /**
     * 获取用户详情
     */
    @GetMapping("/getUserInfo")
    @ApiOperation(value = "获取用户详情", notes = "根据用户ID查询用户基本信息")
    public R getUserInfo(
            @ApiParam(value = "用户ID", required = true, example = "60d21b4667d0d8992e610c8")
            @RequestParam @NotBlank(message = "用户ID不能为空") String userId) {
        try {
            User user = userService.getUserInfo(userId);
            if (user == null) {
                log.warn("查询用户详情失败：用户不存在（userId={}）", userId);
                return R.error().message("用户不存在");
            }
            log.info("查询用户详情成功：userId={}", userId);
            return R.ok().data("userInfo", user);
        } catch (Exception e) {
            log.error("查询用户详情异常：userId={}", userId, e);
            return R.error().message("获取用户信息失败");
        }
    }

    /**
     * 修改好友备注信息
     */
    @PostMapping("/modifyFriendBeiZhu")
    @ApiOperation(value = "修改好友备注", notes = "更新用户对好友的备注名称")
    public R modifyFriendBeiZhu(
            @ApiParam(value = "备注修改信息（含用户ID、好友ID、新备注）", required = true)
            @RequestBody @Valid ModifyFriendBeiZhuRequestVo requestVo) { // 启用参数校验
        try {
            userService.modifyBeiZhu(requestVo);
            log.info("修改好友备注成功：userId={}, friendId={}, 新备注={}",
                    requestVo.getUserId(), requestVo.getFriendId(), requestVo.getFriendBeiZhuName());
            return R.ok().message("修改备注成功！");
        } catch (BusinessException e) {
            log.warn("修改备注失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("修改备注异常：{}", requestVo, e);
            return R.error().message("修改备注失败");
        }
    }


    /**
     * 修改好友分组
     */
    @PostMapping("/modifyFriendFenZu")
    @ApiOperation(value = "修改好友分组", notes = "将好友从一个分组移动到另一个分组")
    public R modifyFriendFenZu(
            @ApiParam(value = "分组修改信息（含用户ID、好友ID、新旧分组名）", required = true)
            @RequestBody @Valid ModifyFriendFenZuRequestVo requestVo) { // 启用参数校验
        try {
            userService.modifyFriendFenZu(requestVo);
            log.info("修改好友分组成功：userId={}, friendId={}, 新分组={}",
                    requestVo.getUserId(), requestVo.getFriendId(), requestVo.getNewFenZuName());
            return R.ok().message("修改分组成功！");
        } catch (BusinessException e) {
            log.warn("修改分组失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("修改分组异常：{}", requestVo, e);
            return R.error().message("修改分组失败");
        }
    }


    /**
     * 删除分组
     */
    @DeleteMapping("/delFenZu")
    @ApiOperation(value = "删除好友分组", notes = "删除用户的指定好友分组（需确保分组为空）")
    public R deleteFenZu(
            @ApiParam(value = "删除分组信息（含用户ID和分组名）", required = true)
            @RequestBody @Valid DelFenZuRequestVo requestVo) { // 启用参数校验
        try {
            userService.deleteFenZu(requestVo);
            log.info("删除分组成功：userId={}, 分组名={}", requestVo.getUserId(), requestVo.getFenZuName());
            return R.ok().message("删除成功！");
        } catch (BusinessException e) {
            log.warn("删除分组失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("删除分组异常：{}", requestVo, e);
            return R.error().message("删除分组失败");
        }
    }


    /**
     * 更新分组名（编辑分组）
     */
    @PostMapping("/editFenZu")
    @ApiOperation(value = "编辑分组名称", notes = "修改用户已有分组的名称")
    public R editFenZu(
            @ApiParam(value = "分组编辑信息（含用户ID、原分组名、新分组名）", required = true)
            @RequestBody @Valid EditFenZuRequestVo requestVo) { // 启用参数校验
        try {
            userService.editFenZu(requestVo);
            log.info("更新分组名成功：userId={}, 原分组={}, 新分组={}",
                    requestVo.getUserId(), requestVo.getOldFenZu(), requestVo.getNewFenZu());
            return R.ok().message("更新成功！");
        } catch (BusinessException e) {
            log.warn("更新分组名失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("更新分组名异常：{}", requestVo, e);
            return R.error().message("更新分组名失败");
        }
    }

    /**
     * 搜索好友
     */
    @PostMapping("/preFetchUser")
    @ApiOperation(value = "搜索好友", notes = "根据关键词（用户名/昵称）搜索用户，排除已添加的好友")
    public R searchUser(
            @ApiParam(value = "搜索条件（含关键词）", required = true)
            @RequestBody @Valid SearchRequestVo requestVo) { // 启用参数校验
        try {
            // 获取当前登录用户ID（通过安全上下文）
            String currentUserId = getCurrentUserId();
            if (currentUserId == null) {
                return R.error().message("未获取到当前用户信息，搜索失败");
            }

            List<User> userList = userService.searchUser(requestVo, currentUserId);
            log.info("搜索好友成功：currentUserId={}, 关键词={}, 结果数={}",
                    currentUserId, requestVo.getSearchContent(), userList.size());
            return R.ok().data("userList", userList);
        } catch (Exception e) {
            log.error("搜索好友异常：currentUserId={}, 请求参数={}", getCurrentUserId(), requestVo, e);
            return R.error().message("搜索失败，请稍后重试");
        }
    }

    /**
     * 更新用户的重要信息（如昵称、头像等）
     */
    @PostMapping("/updateUserInfo")
    @ApiOperation(value = "更新用户基本信息", notes = "修改用户昵称、头像、个人简介等信息")
    public R updateUserInfo(
            @ApiParam(value = "用户信息更新请求", required = true)
            @RequestBody @Valid UpdateUserInfoRequestVo requestVo) { // 启用参数校验
        try {
            Map<String, Object> resMap = userService.updateUserInfo(requestVo);
            if (!resMap.isEmpty()) {
                log.warn("更新用户信息失败：userId={}, 原因={}", requestVo.getUserId(), resMap.get("msg"));
                return R.error().code((Integer) resMap.get("code")).message((String) resMap.get("msg"));
            }
            log.info("更新用户信息成功：userId={}", requestVo.getUserId());
            return R.ok().message("修改成功");
        } catch (Exception e) {
            log.error("更新用户信息异常：{}", requestVo, e);
            return R.error().message("更新信息失败");
        }
    }


    /**
     * 更新用户的配置信息（如通知设置等）
     */
    @PostMapping("/updateUserConfigure")
    @ApiOperation(value = "更新用户配置", notes = "修改用户的系统配置（如消息通知开关、主题等）")
    public R updateUserConfigure(
            @ApiParam(value = "用户配置更新请求", required = true)
            @RequestBody @Valid UpdateUserConfigureRequestVo requestVo) { // 启用参数校验
        try {
            // 获取当前登录用户ID
            String currentUserId = getCurrentUserId();
            if (currentUserId == null) {
                return R.error().message("未获取到当前用户信息，更新配置失败");
            }

            boolean res = userService.updateUserConfigure(requestVo, currentUserId);
            if (res) {
                User userInfo = userService.getUserInfo(currentUserId);
                log.info("更新用户配置成功：userId={}", currentUserId);
                return R.ok().data("userInfo", userInfo);
            } else {
                log.warn("更新用户配置失败：userId={}", currentUserId);
                return R.error().message("更新配置失败");
            }
        } catch (Exception e) {
            log.error("更新用户配置异常：userId={}, 请求参数={}", getCurrentUserId(), requestVo, e);
            return R.error().message("更新配置失败");
        }
    }


    /**
     * 更新用户密码
     */
    @PostMapping("/updateUserPwd")
    @ApiOperation(value = "更新用户密码", notes = "验证原密码后修改为新密码")
    public R updateUserPwd(
            @ApiParam(value = "密码更新请求（含原密码、新密码）", required = true)
            @RequestBody @Valid UpdateUserPwdRequestVo requestVo) { // 启用参数校验
        try {
            Map<String, Object> resMap = userService.updateUserPwd(requestVo);
            Integer code = (Integer) resMap.get("code");

            if (code.equals(ResultEnum.SUCCESS.getCode())) {
                log.info("更新密码成功：userId={}", requestVo.getUserId());
                return R.ok().message((String) resMap.get("msg"));
            } else {
                log.warn("更新密码失败：userId={}, 原因={}", requestVo.getUserId(), resMap.get("msg"));
                return R.error().code(code).message((String) resMap.get("msg"));
            }
        } catch (Exception e) {
            log.error("更新密码异常：{}", requestVo, e);
            return R.error().message("更新密码失败");
        }
    }


    // ------------------------------ 私有辅助方法 ------------------------------

    /**
     * 获取当前登录用户的ID（通用方法，减少重复代码）
     */
    private String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("当前用户未认证");
            return null;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof String) {
            // 若principal直接是用户ID字符串（如JWT认证时可能设置）
            return (String) principal;
        } else if (principal instanceof UserDetails) {
            // 若principal是UserDetails，通过用户名查询用户ID
            String username = ((UserDetails) principal).getUsername();
            User user = userService.findUserByUsername(username);
            return user != null ? user.getUserId().toString() : null;
        } else {
            log.warn("未知的principal类型：{}", principal.getClass().getName());
            return null;
        }
    }

    /**
     * 获取当前操作人ID（用于客服注册等场景，区分普通用户和超级用户）
     */
    private String getCurrentOperatorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof UserDetails)) {
            throw new AuthenticationCredentialsNotFoundException("用户未认证或认证信息异常，无法执行操作");
        }

        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String currentUsername = userDetails.getUsername();

        // 先查普通用户
        User currentUser = userService.findUserByUsername(currentUsername);
        if (currentUser != null) {
            return currentUser.getUserId().toString();
        }

        // 再查超级用户
        SuperUser currentSuperUser = superUserService.existSuperUser(currentUsername);
        if (currentSuperUser != null) {
            return currentSuperUser.getSid().toString();
        }

        log.warn("未找到当前操作人信息：username={}", currentUsername);
        return null;
    }

    // 角色校验工具方法
    private void checkUserHasAnyRole(String[] requiredRoles) {
        // 获取当前用户认证信息
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 验证用户是否已登录
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("用户未登录或认证失效");
            throw new AuthenticationCredentialsNotFoundException("请先登录");
        }

        // 验证是否为匿名用户
        if (authentication.getPrincipal() instanceof String
                && "anonymousUser".equals(authentication.getPrincipal())) {
            log.warn("匿名用户尝试访问需要权限的接口");
            throw new AccessDeniedException("权限不足，请使用管理员账号登录");
        }

        // 提取用户拥有的角色列表
        List<String> userRoles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        log.debug("当前用户拥有的角色: {}", userRoles);

        // 处理需要的角色（添加ROLE_前缀，根据实际情况调整）
        List<String> requiredRolesWithPrefix = Arrays.stream(requiredRoles)
                .map(role -> "ROLE_" + role) // 若角色存储时不带ROLE_前缀则删除此行
                .collect(Collectors.toList());

        // 校验角色是否匹配
        boolean hasRequiredRole = userRoles.stream()
                .anyMatch(requiredRolesWithPrefix::contains);

        if (!hasRequiredRole) {
            log.warn("用户角色不匹配，需要: {}，实际拥有: {}", requiredRolesWithPrefix, userRoles);
            throw new AccessDeniedException("权限不足，仅允许以下角色操作: " + Arrays.toString(requiredRoles));
        }
    }
}
