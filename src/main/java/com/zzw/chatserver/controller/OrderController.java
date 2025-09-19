package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import com.zzw.chatserver.service.OrderService;
import com.zzw.chatserver.service.UserService;
import io.swagger.annotations.Api;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.List;
import javax.validation.Valid;

@RestController
@RequestMapping("/order")
@Api(tags = "订单相关接口")
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    private String getCurrentUserId() {
        // 从 Security 上下文获取认证信息，principal 存储的是当前登录用户的ID
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    // 创建订单接口（模拟购买功能）
    @PostMapping("/create")
    public R createOrder(@Valid @RequestBody CreateOrderVo createOrderVo) {
        // 1. 校验用户是否存在
        String userId = createOrderVo.getUserId();
        User user = userService.getUserInfo(userId);
        if (user == null) {
            return R.error().message("用户不存在，无法创建订单");
        }

        // 2. 校验当前登录用户与订单用户是否一致（防止越权）
        String currentUserId = getCurrentUserId();
        if (!currentUserId.equals(userId)) {
            return R.error().message("无权为他人创建订单");
        }

        // 3. 调用服务层创建订单
        Order order = orderService.createOrder(createOrderVo);
        return R.ok().message("订单创建成功").data("orderNo", order.getOrderNo());
    }

    /**
     * 申请退款接口
     */
    @PostMapping("/refund")
    public R applyRefund(@RequestParam String userId, @RequestParam String token) { // 接收token参数
        try {
            String currentUserId = getCurrentUserId();
            if (!currentUserId.equals(userId)) {
                return R.error().message("无权操作：用户ID不匹配");
            }

            User user = userService.getUserInfo(userId);
            if (user == null) {
                return R.error().message("用户不存在");
            }

            orderService.applyRefund(userId);
            return R.ok().message("退款申请已提交，请等待处理");
        } catch (RuntimeException e) {
            return R.error().message(e.getMessage()); // 返回具体异常信息
        }
    }

    /**
     * 查询订单接口
     */
    @GetMapping("/query")
    public R queryOrder(@RequestParam String userId, @RequestParam String token) {
        try {
            String currentUserId = getCurrentUserId();
            if (!currentUserId.equals(userId)) {
                return R.error().message("无权操作：用户ID不匹配");
            }

            User user = userService.getUserInfo(userId);
            if (user == null) {
                return R.error().message("用户不存在");
            }

            List<Order> orders = orderService.getUserOrders(userId);
            return R.ok().data("orders", orders);
        } catch (RuntimeException e) {
            return R.error().message(e.getMessage());
        }
    }
}