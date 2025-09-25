package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.pojo.SystemNotification;
import com.zzw.chatserver.pojo.vo.RegisterRequestVo;
import com.zzw.chatserver.service.SystemNotificationService;
import io.swagger.annotations.Api;
import org.csource.common.MyException;
import com.zzw.chatserver.common.R;
import com.zzw.chatserver.filter.SensitiveFilter;
import com.zzw.chatserver.pojo.FeedBack;
import com.zzw.chatserver.pojo.SensitiveMessage;
import com.zzw.chatserver.pojo.User;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource; // 用于注入MinIOUtil
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import javax.servlet.ServletOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/sys")
@Api(tags = "系统相关接口")
public class SysController {

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

    /**
     * 系统通知:客服向用户推送【确认收货】等通知，需精准触达指定用户
     * @param notification
     * @return
     */
    @PostMapping("/sendSystemNotification")
    public R sendSystemNotification(@RequestBody SystemNotification notification) {
        // 校验发送者是否为客服（通过UserService判断角色）
        User sender = userService.getUserInfo(notification.getSenderUid());
        if (sender == null) {
            return R.error().message("发送者不存在");
        }
        // 校验角色（使用UserRoleEnum的常量，避免硬编码）
        if (!UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(sender.getRole())) {
            return R.error().message("无权限发送系统通知");
        }
//        System.out.println("type: " + notification.getType());
//        System.out.println("orderNo: " + notification.getOrderNo());
//        System.out.println("type匹配结果：" + "CONFIRM_RECEIPT".equals(notification.getType()));
//        System.out.println("orderNo非空结果：" + (notification.getOrderNo() != null));
        // 若为“确认收货”类型，自动拼接订单信息到content
        if ("CONFIRM_RECEIPT".equals(notification.getType()) && notification.getOrderNo() != null) {
            // 打印原始content
            System.out.println("修改前的content：" + notification.getContent());
            // 拼接新content
            String newContent = String.format("您的订单【%s】已送达，请确认收货", notification.getOrderNo());
            notification.setContent(newContent);
            // 打印修改后的content
            System.out.println("修改后的content：" + notification.getContent());
        }
        systemNotificationService.sendSystemNotification(notification);
        return R.ok();
    }

    /**
     * 获取注册时的头像列表
     */
    @GetMapping("/getFaceImages")
    @ResponseBody
    public R getFaceImages() {
        //String path = ClassUtils.getDefaultClassLoader().getResource("").getPath() + "static/face";
        //System.out.println(path);
        ArrayList<String> files = new ArrayList<>();
        //File file = new File(path);
        /*for (File item : Objects.requireNonNull(file.listFiles())) {
            files.add(item.getName());
        }*/
        for (int i = 1; i <= 22; i++) {
            files.add("face" + i + ".jpg");
        }
        files.add("ronaldo1.jpg");
        return R.ok().data("files", files);
    }

    /**
     * 获取系统用户
     */
    @GetMapping("/getSysUsers")
    @ResponseBody
    public R getSysUsers() {
        List<SystemUserResponseVo> sysUsers = sysService.getSysUsers();
        // System.out.println("系统用户有：" + sysUsers);
        return R.ok().data("sysUsers", sysUsers);
    }

    /**
     * 上传文件
     */

    @PostMapping("/uploadFile")
    @ResponseBody
    public R uploadFile(MultipartFile file) throws Exception {
        // 1. 实例调用上传方法（原静态调用MinIOUtil.uploadFile → 改为实例调用）
        String fileId = minIOUtil.uploadFile(file);
        // 2. 实例调用生成URL方法（同理，静态改实例）
        String filePath = minIOUtil.getFileUrl(fileId);
        // 3. 保持原有返回逻辑不变
        return R.ok().data("filePath", filePath);
    }

    /**
     * 提供文件下载
     */
    @GetMapping("/downloadFile")
    public void downloadFile(
            @RequestParam("fileId") String fileId,
            @RequestParam("fileName") String fileName,
            HttpServletResponse resp) {
        try (
                // 仅将需要关闭的流对象放在try-with-resources中（ServletOutputStream需要关闭）
                ServletOutputStream outputStream = resp.getOutputStream()
        ) {
            // 1. 调用下载方法（byte[]不是资源，无需放在try-with-resources声明中）
            byte[] bytes = minIOUtil.downloadFile(fileId);

            // 2. 设置响应头和编码（处理中文文件名）
            resp.setCharacterEncoding("UTF-8");
            resp.setContentType("application/octet-stream"); // 通用二进制流类型
            resp.setHeader(
                    "Content-disposition",
                    "attachment;filename=" + URLEncoder.encode(fileName, "UTF-8") +
                            ";filename*=UTF-8''" + URLEncoder.encode(fileName, "UTF-8")
            );

            // 3. 写入文件内容到响应流
            IOUtils.write(bytes, outputStream);
            outputStream.flush();

        } catch (IOException e) {
            // 捕获MinIOUtil抛出的IOException（如流操作异常）
            e.printStackTrace();
            handleDownloadError(resp, "文件IO错误：" + e.getMessage());
        } catch (MyException e) {
            // 捕获MinIOUtil抛出的自定义异常（如文件不存在、ID无效）
            e.printStackTrace();
            handleDownloadError(resp, "下载失败：" + e.getMessage());
        } catch (Exception e) {
            // 捕获其他未声明的异常（如运行时异常）
            e.printStackTrace();
            handleDownloadError(resp, "服务器异常：" + e.getMessage());
        }
    }

    // 错误处理辅助方法
    private void handleDownloadError(HttpServletResponse resp, String message) {
        try {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType("text/plain;charset=UTF-8");
            resp.getWriter().write(message);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    /**
     * 搜索好友或加过的群聊列表
     */
    /*@GetMapping("/topSearch")
    @ResponseBody
    public R topSearch(String keyword) {

        return R.ok();
    }*/

    /**
     * 用户反馈
     */
    @PostMapping("/addFeedBack")
    @ResponseBody
    public R addFeedBack(@RequestBody FeedBack feedBack) {
        // System.out.println("反馈请求参数为：" + feedBack);
        sysService.addFeedBack(feedBack);
        return R.ok().message("感谢您的反馈！");
    }

    /**
     * 过滤发送的消息
     */
    @PostMapping("/filterMessage")
    @ResponseBody
    public R filterMessage(@RequestBody SensitiveMessage sensitiveMessage) {
        String[] res = sensitiveFilter.filter(sensitiveMessage.getMessage());
        String filterContent = "";
        if (res != null) {
            filterContent = res[0];
            if (res[1].equals("1")) {
                //判断出敏感词，插入到敏感词表中
                sysService.addSensitiveMessage(sensitiveMessage);
            }
        }
        return R.ok().data("message", filterContent);
    }

    /**
     * 获取系统cpu、内存使用率
     */
    @GetMapping("/sysSituation")
    @ResponseBody
    public R getSysInfo() {
        double cpuUsage = SystemUtil.getSystemCpuLoad();
        double memUsage = SystemUtil.getSystemMemLoad();
        return R.ok().data("cpuUsage", cpuUsage).data("memUsage", memUsage);
    }

    /**
     * 获取所有用户信息
     */
    @GetMapping("/getAllUser")
    @ResponseBody
    public R getAllUser() {
        List<User> userList = userService.getUserList();
        return R.ok().data("userList", userList);
    }

    /**
     * 根据注册时间获取用户
     */
    @GetMapping("/getUsersBySignUpTime")
    @ResponseBody
    public R getUsersBySignUpTime(String lt, String rt) {
        List<User> userList = userService.getUsersBySignUpTime(lt, rt);
        return R.ok().data("userList", userList);
    }

    /**
     * 获取在线用户个数
     */
    @GetMapping("/countOnlineUser")
    @ResponseBody
    public R getOnlineUserNums() {
        int onlineUserCount = onlineUserService.countOnlineUser();
        return R.ok().data("onlineUserCount", onlineUserCount);
    }

    /**
     * 更改用户状态
     */
    @GetMapping("/changeUserStatus")
    @ResponseBody
    public R changeUserStatus(String uid, Integer status) {
        userService.changeUserStatus(uid, status);
        return R.ok();
    }

    /**
     * 获取所有敏感消息列表
     */
    @GetMapping("/getSensitiveMessageList")
    @ResponseBody
    public R getSensitiveMessageList() {
        List<SensitiveMessageResultVo> sensitiveMessageList = sysService.getSensitiveMessageList();
        return R.ok().data("sensitiveMessageList", sensitiveMessageList);
    }

    /**
     * 获取所有反馈记录列表
     */
    @GetMapping("/getFeedbackList")
    @ResponseBody
    public R getFeedbackList() {
        List<FeedBackResultVo> feedbackList = sysService.getFeedbackList();
        return R.ok().data("feedbackList", feedbackList);
    }
}