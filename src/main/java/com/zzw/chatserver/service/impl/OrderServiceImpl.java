package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.dao.OrderDao;
import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import com.zzw.chatserver.service.OrderService;
import com.zzw.chatserver.service.UserService;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单服务实现类
 * 实现订单创建、退款、查询、确认收货等核心逻辑，确保权限与业务规则一致
 * 注：无ConstValueEnum，通过静态常量定义订单状态，复用UserRoleEnum判断角色
 */
@Service
public class OrderServiceImpl implements OrderService {

    @Resource
    private OrderDao orderDao;

    @Resource
    private UserService userService;

    // ------------------------------------------ 订单状态静态常量---------------------------------------------------
    // 规则：0=未支付，1=已支付，2=退款中，3=已退款，4=已确认收货
    private static final int ORDER_STATUS_UNPAID = 0;    // 未支付
    private static final int ORDER_STATUS_PAID = 1;      // 已支付
    private static final int ORDER_STATUS_REFUNDING = 2; // 退款中
    private static final int ORDER_STATUS_REFUNDED = 3;  // 已退款
    private static final int ORDER_STATUS_CONFIRMED = 4; // 已确认收货

    /**
     * 同意退款：仅客服可操作，将状态从“退款中”改为“已退款”
     */
    @Override
    public void approveRefund(String userId, String customerId, String orderNo) {
        // 查询订单
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在：" + orderNo);
        }

        // 验证订单归属
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权操作此订单：订单归属与当前用户/客服不匹配");
        }

        // 验证订单状态（仅“退款中”的订单可同意退款）
        if (!Objects.equals(order.getStatus(), ORDER_STATUS_REFUNDING)) {
            throw new BusinessException("订单状态异常：仅退款中的订单可同意退款，当前状态=" + getOrderStatusDesc(order.getStatus()));
        }

        // 更新订单状态与退款完成时间
        order.setStatus(ORDER_STATUS_REFUNDED);
        order.setRefundTime(String.valueOf(Instant.now()));
        orderDao.save(order);
    }

    /**
     * 拒绝退款：仅客服可操作，将状态从“退款中”改回“已支付”
     */
    @Override
    public void rejectRefund(String userId, String customerId, String orderNo, String reason) {
        // 查询订单
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在：" + orderNo);
        }

        // 验证订单归属
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权操作此订单：订单归属与当前用户/客服不匹配");
        }

        // 验证订单状态（仅“退款中”的订单可拒绝退款）
        if (!Objects.equals(order.getStatus(), ORDER_STATUS_REFUNDING)) {
            throw new BusinessException("订单状态异常：仅退款中的订单可拒绝退款，当前状态=" + getOrderStatusDesc(order.getStatus()));
        }

        // 验证拒绝原因
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("拒绝退款原因不能为空");
        }

        // 更新订单状态
        order.setStatus(ORDER_STATUS_PAID);
        // 可以考虑添加一个字段存储拒绝原因，这里简化处理
        orderDao.save(order);
    }
    /**
     * 根据订单号查询订单（Service层实现）
     * @param orderNo 订单编号
     * @return 订单对象
     * @throws BusinessException 当订单不存在时抛出异常
     */
    @Override
    public Order getOrderByNo(String orderNo) {

        if (orderNo == null || orderNo.trim().isEmpty()) {
            throw new BusinessException("订单编号不能为空");
        }
        Order order = orderDao.findByOrderNo(orderNo.trim());
        if (order == null) {
            throw new BusinessException("订单不存在：" + orderNo);
        }

        return order;
    }

    /**
     * 确认收货：仅已支付订单可操作，更新订单状态为“已确认”
     */
    @Override
    public void confirmReceipt(String userId, String customerId, String orderNo) {
        // 查询订单（按订单号唯一匹配）
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在：订单编号=" + orderNo);
        }
        // 验证订单归属（防止操作他人订单）
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权操作此订单：订单归属与当前用户/客服不匹配");
        }
        // 验证订单状态（仅“已支付”订单可确认收货，用静态常量替换原ConstValueEnum）
        if (!Objects.equals(order.getStatus(), ORDER_STATUS_PAID)) {
            throw new BusinessException("订单状态异常：仅已支付订单可确认收货，当前状态=" + getOrderStatusDesc(order.getStatus()));
        }
        // 更新订单状态与确认时间（用静态常量替换）
        order.setStatus(ORDER_STATUS_CONFIRMED);
        order.setConfirmTime(String.valueOf(Instant.now()));
        orderDao.save(order);
    }

    /**
     * 创建订单：客服为绑定用户创建，生成唯一订单号，默认状态为“已支付”（需对接支付系统时调整）
     */
    @Override
    public Order createOrder(CreateOrderVo createOrderVo) {
        // 校验客服合法性（复用UserRoleEnum判断，替换原ConstValueEnum.USER_ROLE_CUSTOMER_SERVICE）
        User customer = userService.getUserInfo(createOrderVo.getCustomerId());
        if (customer == null) {
            throw new BusinessException("客服不存在：客服ID=" + createOrderVo.getCustomerId());
        }
        // 关键修改：通过UserRoleEnum判断是否为客服角色（避免硬编码字符串）
        UserRoleEnum customerRole = UserRoleEnum.fromCode(customer.getRole());
        if (customerRole == null || !customerRole.isCustomerService()) {
            throw new BusinessException("客服ID不合法：该用户不是客服角色（当前角色：" + customer.getRole() + "）");
        }

        // 校验订单金额合法性（防止负数或0金额）
        if (createOrderVo.getAmount() == null || createOrderVo.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("订单金额异常：金额必须大于0（当前金额：" + createOrderVo.getAmount() + "）");
        }

        // 生成唯一订单号（格式：ORD+年月日+6位随机数）
        String orderNo = generateUniqueOrderNo();

        // 构建订单实体（用静态常量设置默认状态）
        Order order = new Order();
        order.setUserId(createOrderVo.getUserId());
        order.setCustomerId(createOrderVo.getCustomerId());
        order.setOrderNo(orderNo);
        order.setProductName(createOrderVo.getProductName());
        order.setAmount(createOrderVo.getAmount().doubleValue());
        order.setStatus(ORDER_STATUS_PAID); // 临时默认已支付（对接支付后改为ORDER_STATUS_UNPAID）
        order.setCreateTime(String.valueOf(Instant.now()));
        order.setPayTime(String.valueOf(Instant.now()));
        order.setRefundTime(null);
        order.setConfirmTime(null);

        // 保存订单到数据库
        return orderDao.save(order);
    }

    /**
     * 申请退款：筛选用户在该客服下的最新已支付订单，更新状态为“退款中”
     */
    @Override
    public void applyRefund(String userId, String customerId, String orderNo) {
        // 根据订单号查询订单（精确匹配）
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在：" + orderNo);
        }

        // 校验订单归属（必须属于当前用户和客服）
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("订单归属异常：订单" + orderNo + "不属于用户" + userId + "或客服" + customerId);
        }

        // 校验订单状态（仅已支付订单可申请退款）
        if (!Objects.equals(order.getStatus(), ORDER_STATUS_PAID)) {
            throw new BusinessException("订单" + orderNo + "状态异常，当前状态：" + order.getStatus() + "，仅已支付订单可申请退款");
        }

        // 校验是否已发起过退款（避免重复申请）
        if (Objects.equals(order.getStatus(), ORDER_STATUS_REFUNDING) ||
                Objects.equals(order.getStatus(), ORDER_STATUS_REFUNDED)) {
            throw new BusinessException("订单" + orderNo + "已申请退款，当前状态：" + order.getStatus());
        }

        // 更新订单状态为“退款中”
        order.setStatus(ORDER_STATUS_REFUNDING);
        order.setRefundTime(String.valueOf(Instant.now()));
        orderDao.save(order);
    }

    /**
     * 查询订单：按用户+客服双维度筛选，按创建时间倒序返回
     */
    @Override
    public List<Order> getUserOrdersByCustomer(String userId, String customerId) {
        // 基础校验（用户/客服存在性，客服角色合法性）
        User user = userService.getUserInfo(userId);
        User customer = userService.getUserInfo(customerId);
        if (user == null) {
            throw new BusinessException("用户不存在：用户ID=" + userId);
        }
        if (customer == null) {
            throw new BusinessException("客服不存在：客服ID=" + customerId);
        }
        // 额外校验：客服角色合法性（避免用普通用户ID冒充客服）
        UserRoleEnum customerRole = UserRoleEnum.fromCode(customer.getRole());
        if (customerRole == null || !customerRole.isCustomerService()) {
            throw new BusinessException("客服ID不合法：该用户不是客服角色（当前角色：" + customer.getRole() + "）");
        }

        // 查询订单并按创建时间倒序
        return orderDao.findByUserIdAndCustomerId(userId, customerId).stream()
                .sorted(Comparator.comparing(Order::getCreateTime).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 生成测试订单：用于模拟业务，快速创建测试数据
     */
    @Override
    public void createTestOrder(String userId, String customerId) {
        // 校验客服合法性（复用UserRoleEnum判断）
        User customer = userService.getUserInfo(customerId);
        if (customer == null) {
            throw new BusinessException("客服不存在：客服ID=" + customerId);
        }
        UserRoleEnum customerRole = UserRoleEnum.fromCode(customer.getRole());
        if (customerRole == null || !customerRole.isCustomerService()) {
            throw new BusinessException("客服不合法：该用户不是客服角色（当前角色：" + customer.getRole() + "）");
        }

        // 校验用户合法性
        User user = userService.getUserInfo(userId);
        if (user == null) {
            throw new BusinessException("用户不存在：用户ID=" + userId);
        }

        // 构建测试订单（用静态常量设置状态）
        Order testOrder = new Order();
        testOrder.setOrderNo("TEST-" + System.currentTimeMillis() + "-" + new Random().nextInt(1000));
        testOrder.setUserId(userId);
        testOrder.setCustomerId(customerId);
        testOrder.setProductName("测试商品-" + new Random().nextInt(100));
        testOrder.setAmount(99.9 + new Random().nextDouble() * 100); // 随机金额（99.9~199.9）
        testOrder.setStatus(ORDER_STATUS_PAID); // 测试订单默认已支付
        testOrder.setCreateTime(String.valueOf(Instant.now()));
        testOrder.setPayTime(String.valueOf(Instant.now()));

        // 保存测试订单
        orderDao.save(testOrder);
    }

    // ------------------------------ 私有工具方法 ------------------------------

    /**
     * 生成唯一订单号（确保不重复）
     */
    private String generateUniqueOrderNo() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        String dateStr = dateFormat.format(new Date());
        Random random = new Random();
        String orderNo;

        // 循环校验：确保订单号唯一（防止随机数重复）
        do {
            String randomStr = String.format("%06d", random.nextInt(999999)); // 6位随机数（补0）
            orderNo = "ORD" + dateStr + randomStr;
        } while (orderDao.findByOrderNo(orderNo) != null);

        return orderNo;
    }

    /**
     * 获取订单状态描述
     * @param status 订单状态值（对应类顶部的final静态常量）
     * @return 可读的状态描述文本
     */
    private String getOrderStatusDesc(Integer status) {
        // 处理status为null的情况
        if (status == null) {
            return "未知状态（状态值为null）";
        }

        // 传统switch语句（case后使用final静态常量，Java 8可识别）
        String statusDesc;
        switch (status) {
            case ORDER_STATUS_UNPAID:
                statusDesc = "未支付";
                break;
            case ORDER_STATUS_PAID:
                statusDesc = "已支付";
                break;
            case ORDER_STATUS_REFUNDING:
                statusDesc = "退款中";
                break;
            case ORDER_STATUS_REFUNDED:
                statusDesc = "已退款";
                break;
            case ORDER_STATUS_CONFIRMED:
                statusDesc = "已确认收货";
                break;
            // 3. 兜底：未定义的状态值
            default:
                statusDesc = "未知状态（状态值：" + status + "）";
                break;
        }

        return statusDesc;
    }
}