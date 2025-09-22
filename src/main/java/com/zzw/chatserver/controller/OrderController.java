package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import com.zzw.chatserver.service.OrderService;
import com.zzw.chatserver.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.List;
import javax.validation.Valid;

@RestController
@RequestMapping("/order")
@Api(tags = "订单相关接口（绑定用户与客服）")
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    // 获取当前登录用户ID
    private String getCurrentUserId() {
        return (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    /**
     * 确认收货接口
     */
    @PostMapping("/confirmReceipt")
    public R confirmReceipt(
            @ApiParam(value = "用户ID", required = true) @RequestParam String userId,
            @ApiParam(value = "客服ID", required = true) @RequestParam String customerId,
            @ApiParam(value = "订单编号", required = true) @RequestParam String orderNo,
            @RequestParam String token
    ) {
        try {
            // 1. 权限校验
            String currentUserId = getCurrentUserId();
            if (!currentUserId.equals(userId)) {
                return R.error().message("无权操作：用户ID不匹配");
            }

            // 2. 校验用户和客服是否存在
            if (userService.getUserInfo(userId) == null) {
                return R.error().message("用户不存在");
            }
            if (userService.getUserInfo(customerId) == null) {
                return R.error().message("客服不存在");
            }

            // 3. 确认收货
            orderService.confirmReceipt(userId, customerId, orderNo);
            return R.ok().message("确认收货成功");
        } catch (RuntimeException e) {
            return R.error().message(e.getMessage());
        }
    }

    /**
     * 创建订单（绑定用户与客服）
     */
    @PostMapping("/create")
    public R createOrder(
            @Valid @RequestBody CreateOrderVo createOrderVo
    ) {
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

        // 3. 创建订单（自动绑定客服）
        Order order = orderService.createOrder(createOrderVo);
        return R.ok().message("订单创建成功")
                .data("orderNo", order.getOrderNo())
                .data("customerId", order.getCustomerId()); // 返回绑定的客服ID
    }

    /**
     * 申请退款（基于用户+客服的订单）
     */
    @PostMapping("/refund")
    public R applyRefund(
            @ApiParam(value = "用户ID", required = true) @RequestParam String userId,
            @ApiParam(value = "客服ID", required = true) @RequestParam String customerId,
            @RequestParam String token
    ) {
        try {
            // 1. 权限校验
            String currentUserId = getCurrentUserId();
            if (!currentUserId.equals(userId)) {
                return R.error().message("无权操作：用户ID不匹配");
            }

            // 2. 校验用户和客服是否存在
            if (userService.getUserInfo(userId) == null) {
                return R.error().message("用户不存在");
            }
            if (userService.getUserInfo(customerId) == null) {
                return R.error().message("客服不存在");
            }

            // 3. 申请退款
            orderService.applyRefund(userId, customerId);
            return R.ok().message("退款申请已提交，请等待处理");
        } catch (RuntimeException e) {
            return R.error().message(e.getMessage());
        }
    }

    /**
     * 查询订单（用户在特定客服下的订单）
     */
    @GetMapping("/query")
    public R queryOrder(
            @ApiParam(value = "用户ID", required = true) @RequestParam String userId,
            @ApiParam(value = "客服ID", required = true) @RequestParam String customerId,
            @RequestParam String token
    ) {
        try {
            // 1. 权限校验
            String currentUserId = getCurrentUserId();
            if (!currentUserId.equals(userId)) {
                return R.error().message("无权操作：用户ID不匹配");
            }

            // 2. 校验用户和客服是否存在
            if (userService.getUserInfo(userId) == null) {
                return R.error().message("用户不存在");
            }
            if (userService.getUserInfo(customerId) == null) {
                return R.error().message("客服不存在");
            }

            // 3. 查询订单
            List<Order> orders = orderService.getUserOrdersByCustomer(userId, customerId);
            return R.ok().data("orders", orders);
        } catch (RuntimeException e) {
            return R.error().message(e.getMessage());
        }
    }
}