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
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;

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
@Slf4j // 日志支持
@Validated // 启用方法参数校验
public class OrderController {

    @Resource
    private OrderService orderService;

    @Resource
    private UserService userService;

    /**
     * 同意退款接口（仅客服可操作，处理自己负责的用户订单）
     */
    @PostMapping("/approveRefund")
    @ApiOperation(value = "同意退款", notes = "仅客服可操作，且只能处理自己负责的用户订单的退款申请")
    public R approveRefund(
            @ApiParam(value = "订单所属用户ID", required = true)
            @RequestParam @NotBlank(message = "用户ID不能为空") String userId,

            @ApiParam(value = "绑定的客服ID", required = true)
            @RequestParam @NotBlank(message = "客服ID不能为空") String customerId,

            @ApiParam(value = "订单编号", required = true)
            @RequestParam @NotBlank(message = "订单编号不能为空") String orderNo
    ) {
        try {
            String currentUserId = getCurrentUserId();

            // 权限校验：必须是客服角色且只能处理自己负责的订单
            validateCustomerServicePermission(currentUserId, customerId);

            // 校验用户和客服合法性
            validateUserAndCustomerExists(userId, customerId);

            // 执行同意退款
            orderService.approveRefund(userId, customerId, orderNo);
            log.info("客服[{}]同意用户[{}]的订单[{}]退款", currentUserId, userId, orderNo);
            return R.ok().message("已同意退款，将按流程处理");
        } catch (BusinessException e) {
            log.warn("同意退款失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("同意退款系统异常", e);
            return R.error().message("同意退款失败，请稍后重试");
        }
    }

    /**
     * 拒绝退款接口（仅客服可操作，处理自己负责的用户订单）
     */
    @PostMapping("/rejectRefund")
    @ApiOperation(value = "拒绝退款", notes = "仅客服可操作，且只能处理自己负责的用户订单的退款申请")
    public R rejectRefund(
            @ApiParam(value = "订单所属用户ID", required = true)
            @RequestParam @NotBlank(message = "用户ID不能为空") String userId,

            @ApiParam(value = "绑定的客服ID", required = true)
            @RequestParam @NotBlank(message = "客服ID不能为空") String customerId,

            @ApiParam(value = "订单编号", required = true)
            @RequestParam @NotBlank(message = "订单编号不能为空") String orderNo,

            @ApiParam(value = "拒绝原因", required = true)
            @RequestParam @NotBlank(message = "拒绝原因不能为空") String reason
    ) {
        try {
            String currentUserId = getCurrentUserId();

            // 权限校验：必须是客服角色且只能处理自己负责的订单
            validateCustomerServicePermission(currentUserId, customerId);

            // 校验用户和客服合法性
            validateUserAndCustomerExists(userId, customerId);

            // 执行拒绝退款
            orderService.rejectRefund(userId, customerId, orderNo, reason);
            log.info("客服[{}]拒绝用户[{}]的订单[{}]退款，原因：{}", currentUserId, userId, orderNo, reason);
            return R.ok().message("已拒绝退款");
        } catch (BusinessException e) {
            log.warn("拒绝退款失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("拒绝退款系统异常", e);
            return R.error().message("拒绝退款失败，请稍后重试");
        }
    }

    /**
     * 确认收货接口（仅用户本人可操作）
     */
    @PostMapping("/confirmReceipt")
    @ApiOperation(value = "确认收货", notes = "仅订单所属用户本人可操作，需传入用户ID、客服ID和订单编号")
    public R confirmReceipt(
            @ApiParam(value = "订单所属用户ID", required = true)
            @RequestParam @NotBlank(message = "用户ID不能为空") String userId,

            @ApiParam(value = "绑定的客服ID", required = true)
            @RequestParam @NotBlank(message = "客服ID不能为空") String customerId,

            @ApiParam(value = "订单编号", required = true)
            @RequestParam @NotBlank(message = "订单编号不能为空") String orderNo
    ) {
        try {
            String currentUserId = getCurrentUserId();
            // 权限校验：仅用户本人可操作
            if (!currentUserId.equals(userId)) {
                log.warn("用户[{}]尝试操作他人[{}]的订单确认收货，被拒绝", currentUserId, userId);
                throw new BusinessException("无权操作：仅用户本人可确认收货");
            }
            // 校验用户和客服合法性
            validateUserAndCustomerExists(userId, customerId);
            // 执行确认收货
            orderService.confirmReceipt(userId, customerId, orderNo);
            log.info("用户[{}]的订单[{}]确认收货成功", userId, orderNo);
            return R.ok().message("确认收货成功");
        } catch (BusinessException e) {
            log.warn("确认收货失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("确认收货系统异常", e);
            return R.error().message("确认收货失败，请稍后重试");
        }
    }


    /**
     * 2. 创建订单接口（仅客服可操作，为绑定的用户创建）
     */
    @PostMapping("/create")
    @ApiOperation(value = "创建订单", notes = "仅客服可操作，且只能为自己负责的用户创建订单")
//    @PreAuthorize("hasRole('CUSTOMER_SERVICE')") // 结合Spring Security角色校验（需提前配置）
    public R createOrder(
            @ApiParam(value = "创建订单参数（含用户ID、客服ID等）", required = true)
            @RequestBody @Valid CreateOrderVo createOrderVo
    ) {
        try {
            String currentUserId = getCurrentUserId(); // 当前登录客服ID
            String targetUserId = createOrderVo.getUserId(); // 订单所属用户ID
            String targetCustomerId = createOrderVo.getCustomerId(); // 绑定的客服ID

            // 权限校验：当前登录用户必须是客服角色
            if (!isCurrentUserCustomerService()) {
                throw new BusinessException("无权创建订单：仅客服可操作");
            }

            // 权限校验：只能为自己负责的用户创建订单
            if (!currentUserId.equals(targetCustomerId)) {
                log.warn("客服[{}]尝试为其他客服[{}]的用户创建订单，被拒绝", currentUserId, targetCustomerId);
                throw new BusinessException("无权操作：只能为自己负责的用户创建订单");
            }
            // 校验用户存在性
            User targetUser = userService.getUserInfo(targetUserId);
            if (targetUser == null) {
                throw new BusinessException("用户不存在，无法创建订单");
            }

            // 创建订单
            Order order = orderService.createOrder(createOrderVo);
            log.info("客服[{}]为用户[{}]创建订单[{}]成功", currentUserId, targetUserId, order.getOrderNo());
            return R.ok().message("订单创建成功")
                    .data("orderNo", order.getOrderNo())
                    .data("customerId", order.getCustomerId())
                    .data("userId", order.getUserId());
        } catch (BusinessException e) {
            log.warn("创建订单失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("创建订单系统异常", e);
            return R.error().message("创建订单失败，请稍后重试");
        }
    }

    @PostMapping("/refund")
    @ApiOperation(value = "申请退款", notes = "仅订单所属用户本人或绑定的客服可操作，需指定具体订单号")
    public R applyRefund(
            @ApiParam(value = "订单所属用户ID", required = true)
            @RequestParam @NotBlank(message = "用户ID不能为空") String userId,

            @ApiParam(value = "绑定的客服ID", required = true)
            @RequestParam @NotBlank(message = "客服ID不能为空") String customerId,

            @ApiParam(value = "订单编号（需退款的具体订单）", required = true, example = "ORD20231025001")
            @RequestParam @NotBlank(message = "订单编号不能为空") String orderNo // 订单号参数
    ) {
        try {
            String currentUserId = getCurrentUserId();
            // 权限校验：用户本人或绑定的客服
            validateRefundPermission(currentUserId, userId, customerId);
            // 校验用户、客服合法性 + 订单存在性及归属关系
            validateOrderBelongsToUser(userId, customerId, orderNo);
            // 执行退款申请
            orderService.applyRefund(userId, customerId, orderNo); // 传递订单号到服务层
            log.info("用户[{}]的订单[{}]申请退款（操作人：{}）成功", userId, orderNo, currentUserId);
            return R.ok().message("退款申请已提交，请等待处理");
        } catch (BusinessException e) {
            log.warn("订单[{}]申请退款失败：{}", orderNo, e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("订单[{}]申请退款系统异常", orderNo, e);
            return R.error().message("申请退款失败，请稍后重试");
        }
    }

    /**
     * 4. 查询订单接口（用户本人 + 绑定的客服可操作）
     */
    @GetMapping("/query")
    @ApiOperation(value = "查询订单", notes = "仅订单所属用户本人或绑定的客服可查询")
    public R queryOrder(
            @ApiParam(value = "订单所属用户ID", required = true)
            @RequestParam @NotBlank(message = "用户ID不能为空") String userId,

            @ApiParam(value = "绑定的客服ID", required = true)
            @RequestParam @NotBlank(message = "客服ID不能为空") String customerId
    ) {
        try {
            String currentUserId = getCurrentUserId();
            // 权限校验：用户本人或绑定的客服
            validateQueryPermission(currentUserId, userId, customerId);
            // 校验用户和客服合法性
            validateUserAndCustomerExists(userId, customerId);
            // 查询订单
            List<Order> orders = orderService.getUserOrdersByCustomer(userId, customerId);
            log.info("查询用户[{}]的订单（操作人：{}），共{}条", userId, currentUserId, orders.size());
            return R.ok()
                    .data("orders", orders != null ? orders : Collections.emptyList())
                    .data("total", orders != null ? orders.size() : 0);
        } catch (BusinessException e) {
            log.warn("查询订单失败：{}", e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("查询订单系统异常", e);
            return R.error().message("查询订单失败，请稍后重试");
        }
    }

    // ------------------------------ 私有工具方法（权限/校验通用逻辑） ------------------------------

    // 验证当前登录用户是否为指定客服
    private void validateCustomerServicePermission(String currentUserId, String customerId) {
        // 必须是客服角色
        if (!isCustomerService(currentUserId)) {
            throw new BusinessException("无权操作：仅客服可处理退款申请");
        }

        // 必须是负责该用户的客服
        if (!currentUserId.equals(customerId)) {
            throw new BusinessException("无权操作：只能处理自己负责的用户订单");
        }
    }

    private void validateOrderBelongsToUser(String userId, String customerId, String orderNo) {
        // 校验用户和客服存在性（复用已有逻辑）
        validateUserAndCustomerExists(userId, customerId);

        // 校验订单存在性
        Order order = orderService.getOrderByNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在，无法申请退款");
        }

        // 校验订单归属（防止跨用户/跨客服操作订单）
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("订单归属异常，无法申请退款");
        }
    }
    /**
     * 获取当前登录用户ID（兼容JwtAuthUser/String类型的认证主体）
     */
    private String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            throw new BusinessException("用户未登录");
        }
        Object principal = authentication.getPrincipal();
        // 认证主体是自定义JwtAuthUser
        if (principal instanceof JwtAuthUser) {
            return ((JwtAuthUser) principal).getUserId().toString();
        }
        // 认证主体是用户名
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

    /**
     * 校验退款权限（用户本人或绑定的客服）
     */
    private void validateRefundPermission(String currentUserId, String userId, String customerId) {
        boolean isOwner = currentUserId.equals(userId);
        boolean isBindCustomer = currentUserId.equals(customerId) && isCustomerService(currentUserId);
        if (!isOwner && !isBindCustomer) {
            throw new BusinessException("无权操作：仅用户本人或绑定的客服可申请退款");
        }
    }

    /**
     * 校验查询权限（用户本人或绑定的客服）
     */
    private void validateQueryPermission(String currentUserId, String userId, String customerId) {
        boolean isOwner = currentUserId.equals(userId);
        boolean isBindCustomer = currentUserId.equals(customerId) && isCustomerService(currentUserId);
        if (!isOwner && !isBindCustomer) {
            throw new BusinessException("无权操作：仅用户本人或绑定的客服可查询订单");
        }
    }

    /**
     * 判断用户是否为客服角色（复用逻辑）
     */
    private boolean isCustomerService(String userId) {
        User user = userService.getUserInfo(userId);
        if (user == null) {
            throw new BusinessException("用户信息不存在");
        }
        return UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(user.getRole());
    }
}