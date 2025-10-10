package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.pojo.SystemNotification;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.service.SystemNotificationService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.csource.common.MyException;
import com.zzw.chatserver.common.R;
import com.zzw.chatserver.filter.SensitiveFilter;
import com.zzw.chatserver.pojo.FeedBack;
import com.zzw.chatserver.pojo.SensitiveMessage;
import com.zzw.chatserver.pojo.vo.FeedBackResultVo;
import com.zzw.chatserver.pojo.vo.SensitiveMessageResultVo;
import com.zzw.chatserver.pojo.vo.SystemUserResponseVo;
import com.zzw.chatserver.service.OnlineUserService;
import com.zzw.chatserver.service.SysService;
import com.zzw.chatserver.service.UserService;
import com.zzw.chatserver.utils.MinIOUtil;
import com.zzw.chatserver.utils.SystemUtil;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.ServletOutputStream;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/sys")
@Api(tags = "系统相关接口")
@Slf4j
@Validated
public class SysController {

    // 常量定义
    private static final String NOTIFICATION_TYPE_CONFIRM_RECEIPT = "CONFIRM_RECEIPT";
    private static final int FACE_IMAGE_COUNT = 22;

    @Resource
    private SysService sysService;

    @Value("${minio.endpoint}")
    private String minioEndpoint;

    @Value("${minio.bucket-name}")
    private String minioBucketName;

    @Resource
    private SensitiveFilter sensitiveFilter;

    @Resource
    private UserService userService;

    @Resource
    private OnlineUserService onlineUserService;

    @Resource
    private MinIOUtil minIOUtil;

    @Resource
    private SystemNotificationService systemNotificationService;

    // ------------------------------ 权限校验通用方法 ------------------------------

    /**
     * 校验当前登录用户是否拥有指定角色中的任意一个
     * @param allowedRoles 允许的角色列表（使用UserRoleEnum的code）
     */
    private void checkHasAnyRole(String[] allowedRoles) {
        // 获取当前用户认证信息
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 验证用户是否已登录
        if (authentication == null || !authentication.isAuthenticated()) {
            log.warn("权限校验失败：用户未登录或认证失效");
            throw new AuthenticationCredentialsNotFoundException("请先登录");
        }

        // 验证是否为匿名用户
        if (authentication.getPrincipal() instanceof String
                && "anonymousUser".equals(authentication.getPrincipal())) {
            log.warn("权限校验失败：匿名用户无权限操作");
            throw new AccessDeniedException("权限不足，请使用管理员账号登录");
        }

        // 如果允许的角色为空数组，表示“仅需登录即可，不限制角色”
        if (allowedRoles == null || allowedRoles.length == 0) {
            log.debug("权限校验通过：仅需登录状态，不限制角色");
            return; // 直接通过校验
        }

        // 提取用户拥有的角色列表
        List<String> userRoles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toList());
        log.debug("当前用户角色：{}", userRoles);

        // 转换允许的角色（添加ROLE_前缀，与Security保持一致）
        List<String> allowedRolesWithPrefix = Arrays.stream(allowedRoles)
                .map(role -> "ROLE_" + role)
                .collect(Collectors.toList());

        // 校验角色是否匹配
        boolean hasPermission = userRoles.stream()
                .anyMatch(allowedRolesWithPrefix::contains);

        if (!hasPermission) {
            log.warn("权限校验失败：用户角色={}，需要角色={}", userRoles, allowedRolesWithPrefix);
            throw new AccessDeniedException("权限不足，无法执行此操作");
        }
    }

    // ------------------------------ 接口实现 ------------------------------

    /**
     * 系统通知:客服向用户推送【确认收货】等通知
     */
    @PostMapping("/sendSystemNotification")
    @ApiOperation(value = "发送系统通知", notes = "仅客服可向用户推送通知（如确认收货提醒）")
    public R sendSystemNotification(
            @ApiParam(value = "系统通知信息（含发送者ID、接收者ID、类型等）", required = true)
            @RequestBody @Valid SystemNotification notification) {
        try {
            // 校验当前用户是否为客服
            checkHasAnyRole(new String[]{UserRoleEnum.CUSTOMER_SERVICE.getCode()});

            // 校验发送者存在性
            String senderUid = notification.getSenderUid();
            User sender = userService.getUserInfo(senderUid);
            if (sender == null) {
                log.warn("发送系统通知失败：发送者不存在（senderUid={}）", senderUid);
                return R.error().message("发送者不存在");
            }

            // 处理确认收货类型的通知
            if (NOTIFICATION_TYPE_CONFIRM_RECEIPT.equals(notification.getType())) {
                String orderNo = notification.getOrderNo();
                if (orderNo == null || orderNo.trim().isEmpty()) {
                    throw new BusinessException("确认收货通知的订单号不能为空");
                }
                String content = String.format("您的订单【%s】已送达，请确认收货", orderNo.trim());
                notification.setContent(content);
                log.info("确认收货通知内容生成：orderNo={}, content={}", orderNo, content);
            }

            // 发送通知
            systemNotificationService.sendSystemNotification(notification);
            log.info("系统通知发送成功（senderUid={}, receiverUid={}, type={}",
                    senderUid, notification.getReceiverUid(), notification.getType());
            return R.ok().message("通知发送成功");

        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (BusinessException e) {
            log.warn("发送系统通知业务异常：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("发送系统通知系统异常", e);
            return R.error().message("发送通知失败，请稍后重试");
        }
    }

    /**
     * 获取注册时的头像列表
     */
    @GetMapping("/getFaceImages")
    @ApiOperation(value = "获取注册默认头像列表", notes = "返回系统预设的头像文件名列表")
    public R getFaceImages() {
        try {
            List<String> faceFiles = new ArrayList<>(FACE_IMAGE_COUNT + 1);
            for (int i = 1; i <= FACE_IMAGE_COUNT; i++) {
                faceFiles.add("face" + i + ".jpg");
            }
            faceFiles.add("ronaldo1.jpg");
            log.info("获取默认头像列表成功，共{}个", faceFiles.size());
            return R.ok().data("files", faceFiles);
        } catch (Exception e) {
            log.error("获取头像列表异常", e);
            return R.error().message("获取头像列表失败");
        }
    }

    /**
     * 获取系统用户 - 仅超级管理员可访问
     */
    @GetMapping("/getSysUsers")
    @ApiOperation(value = "获取系统用户列表", notes = "返回系统内置用户信息（仅超级管理员可访问）")
    public R getSysUsers() {
        try {
            // 校验权限：仅超级管理员可访问
            checkHasAnyRole(new String[]{UserRoleEnum.ADMIN.getCode()});

            List<SystemUserResponseVo> sysUsers = sysService.getSysUsers();
            log.info("获取系统用户列表成功，共{}条", sysUsers.size());
            return R.ok().data("sysUsers", sysUsers);
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("获取系统用户列表异常", e);
            return R.error().message("获取系统用户失败");
        }
    }

    /**
     * 上传文件
     */
    @PostMapping("/uploadFile")
    @ApiOperation(value = "文件上传", notes = "上传文件到MinIO存储，返回文件访问URL")
    public R uploadFile(
            @ApiParam(value = "待上传的文件", required = true)
            @RequestParam @NotNull(message = "上传文件不能为空") MultipartFile file) {
        try {
            log.info("开始上传文件：文件名={}, 大小={}KB", file.getOriginalFilename(), file.getSize() / 1024);
            String fileId = minIOUtil.uploadFile(file);
            String filePath = minIOUtil.getFileUrl(fileId);
            log.info("文件上传成功：fileId={}, filePath={}", fileId, filePath);
            return R.ok().data("filePath", filePath);
        } catch (Exception e) {
            log.error("文件上传失败：文件名={}", file.getOriginalFilename(), e);
            return R.error().message("文件上传失败，请稍后重试");
        }
    }

    /**
     * 提供文件下载
     */
    @GetMapping("/downloadFile")
    @ApiOperation(value = "文件下载", notes = "根据文件ID和文件名下载文件")
    public void downloadFile(
            @ApiParam(value = "文件ID（上传时返回）", required = true)
            @RequestParam @NotBlank(message = "文件ID不能为空") String fileId,
            @ApiParam(value = "文件名（含扩展名）", required = true)
            @RequestParam @NotBlank(message = "文件名不能为空") String fileName,
            HttpServletResponse resp) {
        try (ServletOutputStream outputStream = resp.getOutputStream()) {
            log.info("开始下载文件：fileId={}, fileName={}", fileId, fileName);

            byte[] fileBytes = minIOUtil.downloadFile(fileId);

            resp.setCharacterEncoding("UTF-8");
            resp.setContentType("application/octet-stream");
            String encodedFileName = URLEncoder.encode(fileName, "UTF-8");
            resp.setHeader("Content-disposition",
                    "attachment;filename=" + encodedFileName +
                            ";filename*=UTF-8''" + encodedFileName);

            IOUtils.write(fileBytes, outputStream);
            outputStream.flush();
            log.info("文件下载成功：fileId={}", fileId);

        } catch (MyException e) {
            log.warn("文件下载业务异常：fileId={}, 原因={}", fileId, e.getMessage());
            handleDownloadError(resp, "下载失败：" + e.getMessage());
        } catch (IOException e) {
            log.error("文件下载IO异常：fileId={}", fileId, e);
            handleDownloadError(resp, "文件读写错误，请稍后重试");
        } catch (Exception e) {
            log.error("文件下载系统异常：fileId={}", fileId, e);
            handleDownloadError(resp, "服务器异常，下载失败");
        }
    }

    /**
     * 用户反馈（仅登录用户可提交）
     */
    @PostMapping("/addFeedBack")
    @ApiOperation(value = "提交用户反馈", notes = "仅登录用户可提交使用过程中的问题或建议，自动关联当前登录用户ID")
    public R addFeedBack(
            @ApiParam(value = "用户反馈信息（无需手动填写userId，会自动关联当前登录用户）", required = true)
            @RequestBody @Valid FeedBack feedBack) {
        try {
            // 校验登录状态（复用现有方法，允许所有已登录用户）
            checkHasAnyRole(new String[0]);

            // 获取当前登录用户信息（适配默认User类）
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            Object principal = authentication.getPrincipal();

            // 提取用户ID
            String currentUserId;
            if (principal instanceof User) {
                // 处理Spring Security默认的User类
                currentUserId = ((User) principal).getUsername();
            } else if (principal instanceof String) {
                // 极端情况：principal直接是用户名（很少见）
                currentUserId = (String) principal;
            } else {
                // 处理其他可能的自定义用户类（根据实际情况补充）
                log.error("无法识别的用户信息类型：{}", principal.getClass().getName());
                return R.error().message("系统异常，无法获取用户信息");
            }

            // 校验用户ID有效性
            if (currentUserId == null || currentUserId.trim().isEmpty()) {
                log.error("获取当前登录用户ID失败");
                return R.error().message("系统异常，无法获取用户信息");
            }

            // 强制关联当前登录用户ID
            feedBack.setUserId(currentUserId);

            // 提交反馈
            log.info("收到用户反馈：userId={}, 内容摘要={}",
                    currentUserId, getContentSummary(feedBack.getFeedBackContent()));
            sysService.addFeedBack(feedBack);
            return R.ok().message("感谢您的反馈，我们会尽快处理！");

        } catch (AuthenticationCredentialsNotFoundException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("提交反馈异常", e);
            return R.error().message("反馈提交失败，请稍后重试");
        }
    }


    /**
     * 过滤发送的消息（敏感词处理）
     */
    @PostMapping("/filterMessage")
    @ApiOperation(value = "消息敏感词过滤", notes = "检测并替换消息中的敏感词，返回过滤后的内容")
    public R filterMessage(
            @ApiParam(value = "待过滤的消息", required = true)
            @RequestBody @Valid SensitiveMessage sensitiveMessage) {
        try {
            String originalMessage = sensitiveMessage.getMessage();
            log.info("开始过滤消息：userId={}, 原始内容摘要={}",
                    sensitiveMessage.getSenderId(), getContentSummary(originalMessage));

            String[] filterResult = sensitiveFilter.filter(originalMessage);
            if (filterResult == null || filterResult.length < 2) {
                log.warn("敏感词过滤结果异常：userId={}", sensitiveMessage.getSenderId());
                return R.ok().data("message", originalMessage);
            }

            String filteredContent = filterResult[0];
            if ("1".equals(filterResult[1])) {
                sysService.addSensitiveMessage(sensitiveMessage);
                log.info("检测到敏感词并记录：userId={}", sensitiveMessage.getSenderId());
            }

            return R.ok().data("message", filteredContent);
        } catch (Exception e) {
            log.error("消息过滤异常：userId={}", sensitiveMessage.getSenderId(), e);
            return R.error().message("消息处理失败，请稍后重试");
        }
    }

    /**
     * 获取系统cpu、内存使用率 - 仅超级管理员可查看
     */
    @GetMapping("/sysSituation")
    @ApiOperation(value = "获取系统资源占用", notes = "返回CPU使用率和内存使用率（仅超级管理员可查看）")
    public R getSysInfo() {
        try {
            // 校验权限：仅超级管理员可访问
            checkHasAnyRole(new String[]{UserRoleEnum.ADMIN.getCode()});

            double cpuUsage = SystemUtil.getSystemCpuLoad();
            double memUsage = SystemUtil.getSystemMemLoad();
            log.info("获取系统资源占用：CPU={}%, 内存={}%", cpuUsage, memUsage);
            return R.ok()
                    .data("cpuUsage", cpuUsage)
                    .data("memUsage", memUsage);
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("获取系统资源信息异常", e);
            return R.error().message("获取系统信息失败");
        }
    }

    /**
     * 获取所有用户信息 - 仅超级管理员可访问
     */
    @GetMapping("/getAllUser")
    @ApiOperation(value = "获取所有用户列表", notes = "返回系统中所有用户的基本信息（仅超级管理员可访问）")
    public R getAllUser() {
        try {
            // 校验权限：仅超级管理员可访问
            checkHasAnyRole(new String[]{UserRoleEnum.ADMIN.getCode()});

            List<User> userList = userService.getUserList();
            log.info("获取所有用户列表成功，共{}人", userList.size());
            return R.ok().data("userList", userList);
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("获取所有用户列表异常", e);
            return R.error().message("获取用户列表失败");
        }
    }

    /**
     * 根据注册时间获取用户 - 仅超级管理员可访问
     */
    @GetMapping("/getUsersBySignUpTime")
    @ApiOperation(value = "按注册时间查询用户", notes = "根据时间范围查询用户（仅超级管理员可访问）")
    public R getUsersBySignUpTime(
            @ApiParam(value = "结束时间（小于该时间，格式：yyyy-MM-dd HH:mm:ss）", required = true)
            @RequestParam @NotBlank(message = "结束时间不能为空") String lt,
            @ApiParam(value = "开始时间（大于该时间，格式：yyyy-MM-dd HH:mm:ss）", required = true)
            @RequestParam @NotBlank(message = "开始时间不能为空") String rt) {
        try {
            // 校验权限：仅超级管理员可访问
            checkHasAnyRole(new String[]{UserRoleEnum.ADMIN.getCode()});

            List<User> userList = userService.getUsersBySignUpTime(lt, rt);
            log.info("按注册时间查询用户：{}~{}，共{}人", lt, rt, userList.size());
            return R.ok().data("userList", userList);
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("按注册时间查询用户异常：lt={}, rt={}", lt, rt, e);
            return R.error().message("查询用户失败");
        }
    }

    /**
     * 获取在线用户个数 - 管理员和客服可查看
     */
    @GetMapping("/countOnlineUser")
    @ApiOperation(value = "统计在线用户数量", notes = "返回当前在线的用户总数（管理员和客服可查看）")
    public R getOnlineUserNums() {
        try {
            // 校验权限：超级管理员和客服可访问
            checkHasAnyRole(new String[]{
                    UserRoleEnum.ADMIN.getCode(),
                    UserRoleEnum.CUSTOMER_SERVICE.getCode()
            });

            int onlineUserCount = onlineUserService.countOnlineUser();
            log.info("统计在线用户数量：{}人", onlineUserCount);
            return R.ok().data("onlineUserCount", onlineUserCount);
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("统计在线用户异常", e);
            return R.error().message("获取在线用户数量失败");
        }
    }

    /**
     * 更改用户状态（启用/禁用）- 仅超级管理员可操作
     */
    @GetMapping("/changeUserStatus")
    @ApiOperation(value = "修改用户状态", notes = "更改用户的启用状态（仅超级管理员可操作）")
    public R changeUserStatus(
            @ApiParam(value = "用户ID", required = true)
            @RequestParam @NotBlank(message = "用户ID不能为空") String uid,
            @ApiParam(value = "状态（1-启用，0-禁用）", required = true)
            @RequestParam @NotNull(message = "状态不能为空") Integer status) {
        try {
            // 校验权限：仅超级管理员可操作
            checkHasAnyRole(new String[]{UserRoleEnum.ADMIN.getCode()});

            // 校验状态合法性
            if (status != 0 && status != 1) {
                throw new BusinessException("状态值无效，只能是0或1");
            }
            userService.changeUserStatus(uid, status);
            log.info("修改用户状态成功：userId={}, 新状态={}", uid, status);
            return R.ok().message("用户状态修改成功");
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (BusinessException e) {
            log.warn("修改用户状态失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("修改用户状态异常：userId={}", uid, e);
            return R.error().message("修改用户状态失败");
        }
    }

    /**
     * 获取所有敏感消息列表 - 仅超级管理员可查看
     */
    @GetMapping("/getSensitiveMessageList")
    @ApiOperation(value = "获取敏感消息记录", notes = "返回系统检测到的所有敏感消息列表（仅超级管理员可查看）")
    public R getSensitiveMessageList() {
        try {
            // 校验权限：仅超级管理员可访问
            checkHasAnyRole(new String[]{UserRoleEnum.ADMIN.getCode()});

            List<SensitiveMessageResultVo> sensitiveMessageList = sysService.getSensitiveMessageList();
            log.info("获取敏感消息列表成功，共{}条", sensitiveMessageList.size());
            return R.ok().data("sensitiveMessageList", sensitiveMessageList);
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("获取敏感消息列表异常", e);
            return R.error().message("获取敏感消息记录失败");
        }
    }

    /**
     * 获取所有反馈记录列表 - 管理员和客服可查看
     */
    @GetMapping("/getFeedbackList")
    @ApiOperation(value = "获取用户反馈记录", notes = "返回所有用户提交的反馈信息列表（管理员和客服可查看）")
    public R getFeedbackList() {
        try {
            // 校验权限：超级管理员和客服可访问
            checkHasAnyRole(new String[]{
                    UserRoleEnum.ADMIN.getCode(),
                    UserRoleEnum.CUSTOMER_SERVICE.getCode()
            });

            List<FeedBackResultVo> feedbackList = sysService.getFeedbackList();
            log.info("获取用户反馈列表成功，共{}条", feedbackList.size());
            return R.ok().data("feedbackList", feedbackList);
        } catch (AuthenticationCredentialsNotFoundException | AccessDeniedException e) {
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("获取用户反馈列表异常", e);
            return R.error().message("获取反馈记录失败");
        }
    }

    // ------------------------------ 私有辅助方法 ------------------------------

    /**
     * 处理文件下载错误
     */
    private void handleDownloadError(HttpServletResponse resp, String message) {
        try {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType("text/plain;charset=UTF-8");
            resp.getWriter().write(message);
        } catch (IOException e) {
            log.error("下载错误处理失败", e);
        }
    }

    /**
     * 获取内容摘要
     */
    private String getContentSummary(String content) {
        if (content == null) {
            return "null";
        }
        return content.length() <= 20 ? content : content.substring(0, 20) + "...";
    }
}
