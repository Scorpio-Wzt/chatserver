package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.auth.entity.JwtAuthUser;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import com.zzw.chatserver.service.OrderService;
import com.zzw.chatserver.service.UserService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.List;
import javax.validation.Valid;

/**
 * 订单控制器
 * 权限规则：
 * 1. 订单创建：仅客服可操作（为绑定的用户创建）
 * 2. 订单查询：用户本人 + 绑定的客服可操作
 * 3. 申请退款：用户本人 + 绑定的客服可操作
 * 4. 确认收货：仅用户本人可操作
 */
@RestController
@RequestMapping("/order")
@Api(tags = "订单相关接口（绑定用户与客服）")
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    /**
     * 1. 确认收货接口（仅用户本人可操作）
     */
    @PostMapping("/confirmReceipt")
    public R confirmReceipt(
            @ApiParam(value = "订单所属用户ID", required = true) @RequestParam String userId,
            @ApiParam(value = "绑定的客服ID", required = true) @RequestParam String customerId,
            @ApiParam(value = "订单编号", required = true) @RequestParam String orderNo
    ) {
        try {
            String currentUserId = getCurrentUserId();
            // 权限校验：仅订单所属用户本人可操作
            if (!currentUserId.equals(userId)) {
                throw new BusinessException("无权操作：仅用户本人可确认收货");
            }
            // 基础校验：用户和客服必须存在
            validateUserAndCustomerExists(userId, customerId);
            // 调用服务层执行确认收货
            orderService.confirmReceipt(userId, customerId, orderNo);
            return R.ok().message("确认收货成功");
        } catch (BusinessException e) {
            return R.error().message(e.getMessage());
        }
    }

    /**
     * 2. 创建订单接口（仅客服可操作，为绑定的用户创建）
     */
    @PostMapping("/create")
    public R createOrder(
            @Valid @RequestBody CreateOrderVo createOrderVo
    ) {
        try {
            // 解析请求参数
            String targetUserId = createOrderVo.getUserId(); // 订单所属用户ID
            String targetCustomerId = createOrderVo.getCustomerId(); // 绑定的客服ID
            String currentUserId = getCurrentUserId(); // 当前登录用户ID

            // 权限校验1：当前登录用户必须是客服角色
            if (!isCurrentUserCustomerService()) {
                throw new BusinessException("无权创建订单：仅客服可操作");
            }
            // 权限校验2：订单绑定的客服ID必须等于当前登录客服ID（防止替其他客服创建）
            if (!currentUserId.equals(targetCustomerId)) {
                throw new BusinessException("无权操作：只能为自己负责的用户创建订单");
            }
            // 基础校验：订单所属用户必须存在
            User targetUser = userService.getUserInfo(targetUserId);
            if (targetUser == null) {
                throw new BusinessException("用户不存在，无法创建订单");
            }

            // 调用服务层创建订单
            Order order = orderService.createOrder(createOrderVo);
            return R.ok().message("订单创建成功")
                    .data("orderNo", order.getOrderNo())
                    .data("customerId", order.getCustomerId())
                    .data("userId", order.getUserId());
        } catch (BusinessException e) {
            return R.error().message(e.getMessage());
        }
    }

    /**
     * 3. 申请退款接口（用户本人 + 绑定的客服可操作）
     */
    @PostMapping("/refund")
    public R applyRefund(
            @ApiParam(value = "订单所属用户ID", required = true) @RequestParam String userId,
            @ApiParam(value = "绑定的客服ID", required = true) @RequestParam String customerId
    ) {
        try {
            String currentUserId = getCurrentUserId();
            // 权限校验：满足任一条件即可（用户本人 / 绑定的客服）
            boolean isOwner = currentUserId.equals(userId); // 用户本人
            boolean isBindCustomer = currentUserId.equals(customerId) && isCurrentUserCustomerService(); // 绑定的客服
            if (!isOwner && !isBindCustomer) {
                throw new BusinessException("无权操作：仅用户本人或绑定的客服可申请退款");
            }
            // 基础校验：用户和客服必须存在
            validateUserAndCustomerExists(userId, customerId);
            // 调用服务层执行退款申请
            orderService.applyRefund(userId, customerId);
            return R.ok().message("退款申请已提交，请等待处理");
        } catch (BusinessException e) {
            return R.error().message(e.getMessage());
        }
    }

    /**
     * 4. 查询订单接口（用户本人 + 绑定的客服可操作）
     */
    @GetMapping("/query")
    public R queryOrder(
            @ApiParam(value = "订单所属用户ID", required = true) @RequestParam String userId,
            @ApiParam(value = "绑定的客服ID", required = true) @RequestParam String customerId
    ) {
        try {
            String currentUserId = getCurrentUserId();
            // 权限校验：满足任一条件即可（用户本人 / 绑定的客服）
            boolean isOwner = currentUserId.equals(userId); // 用户本人
            boolean isBindCustomer = currentUserId.equals(customerId) && isCurrentUserCustomerService(); // 绑定的客服
            if (!isOwner && !isBindCustomer) {
                throw new BusinessException("无权操作：仅用户本人或绑定的客服可查询订单");
            }
            // 基础校验：用户和客服必须存在
            validateUserAndCustomerExists(userId, customerId);
            // 调用服务层查询订单（按创建时间倒序）
            List<Order> orders = orderService.getUserOrdersByCustomer(userId, customerId);
            return R.ok().data("orders", orders).data("total", orders.size());
        } catch (BusinessException e) {
            return R.error().message(e.getMessage());
        }
    }

    // ------------------------------ 私有工具方法（权限/校验通用逻辑） ------------------------------

    /**
     * 获取当前登录用户ID（兼容JwtAuthUser/String类型的认证主体）
     */
    private String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new BusinessException("用户未登录");
        }
        Object principal = authentication.getPrincipal();
        // 场景1：认证主体是自定义JwtAuthUser（包含用户ID等信息）
        if (principal instanceof JwtAuthUser) {
            return ((JwtAuthUser) principal).getUserId().toString();
        }
        // 场景2：认证主体是用户名（String类型，视认证逻辑而定）
        else if (principal instanceof String) {
            // 若主体是用户名，需通过UserService查询用户ID（根据实际认证逻辑调整）
            User user = userService.findUserByUsername((String) principal);
            if (user == null) {
                throw new BusinessException("登录用户信息不存在");
            }
            return user.getUserId().toString();
        }
        throw new BusinessException("无法获取用户ID：认证主体类型异常");
    }

    /**
     * 判断当前登录用户是否为客服角色
     */
    private boolean isCurrentUserCustomerService() {
        String currentUserId = getCurrentUserId();
        User currentUser = userService.getUserInfo(currentUserId);
        if (currentUser == null) {
            throw new BusinessException("登录用户信息异常");
        }
        // 匹配客服角色编码（与UserRoleEnum枚举保持一致）
        return UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(currentUser.getRole());
    }

    /**
     * 校验用户和客服是否存在（通用逻辑提取）
     */
    private void validateUserAndCustomerExists(String userId, String customerId) {
        User user = userService.getUserInfo(userId);
        if (user == null) {
            throw new BusinessException("订单所属用户不存在");
        }
        User customer = userService.getUserInfo(customerId);
        if (customer == null) {
            throw new BusinessException("绑定的客服不存在");
        }
        // 额外校验：客服角色合法性（防止用普通用户ID冒充客服）
        if (!UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(customer.getRole())) {
            throw new BusinessException("客服ID不合法：该用户不是客服角色");
        }
    }
}