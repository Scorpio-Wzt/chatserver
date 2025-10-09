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
import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单服务实现类
 * 实现订单创建、退款、查询、确认收货等核心逻辑，确保权限与业务规则一致
 */
@Service
public class OrderServiceImpl implements OrderService {

    @Resource
    private OrderDao orderDao;

    @Resource
    private UserService userService;


    /**
     * 同意退款：仅客服可操作，将状态从“退款中”改为“已退款”
     */
    @Override
    public void approveRefund(String userId, String customerId, String orderNo) {
        // 1. 查询订单
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在：" + orderNo);
        }

        // 2. 验证订单归属（用户+客服双匹配）
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权操作此订单：订单归属与当前用户/客服不匹配");
        }

        // 3. 验证订单状态（仅“退款中”可同意）
        if (!Order.OrderStatus.REFUNDING.equals(order.getStatus())) {
            throw new BusinessException("订单状态异常：仅退款中的订单可同意退款，当前状态=" + order.getStatus().getDesc());
        }

        // 4. 调用实体类方法更新状态（自动同步退款完成时间）
        order.markAsRefunded();
        orderDao.save(order);
    }

    /**
     * 拒绝退款：仅客服可操作，将状态从“退款中”改回“已支付”
     */
    @Override
    public void rejectRefund(String userId, String customerId, String orderNo, String reason) {
        // 1. 查询订单
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在：" + orderNo);
        }

        // 2. 验证订单归属
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权操作此订单：订单归属与当前用户/客服不匹配");
        }

        // 3. 验证订单状态（仅“退款中”可拒绝）
        if (!Order.OrderStatus.REFUNDING.equals(order.getStatus())) {
            throw new BusinessException("订单状态异常：仅退款中的订单可拒绝退款，当前状态=" + order.getStatus().getDesc());
        }

        // 4. 验证拒绝原因
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("拒绝退款原因不能为空");
        }

        // 5. 调用实体类方法回滚状态（不修改支付时间）
        order.markAsRejectedRefund();
        orderDao.save(order);
    }

    /**
     * 根据订单号查询订单
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
        // 1. 查询订单
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在：订单编号=" + orderNo);
        }

        // 2. 验证订单归属
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("无权操作此订单：订单归属与当前用户/客服不匹配");
        }

        // 3. 验证订单状态（仅“已支付”可确认）
        if (!Order.OrderStatus.PAID.equals(order.getStatus())) {
            throw new BusinessException("订单状态异常：仅已支付订单可确认收货，当前状态=" + order.getStatus().getDesc());
        }

        // 4. 调用实体类方法更新状态（自动同步确认时间）
        order.markAsConfirmed();
        orderDao.save(order);
    }

    /**
     * 创建订单：客服为绑定用户创建，生成唯一订单号，默认状态为“已支付”（对接支付后需改为待支付）
     */
    @Override
    public Order createOrder(CreateOrderVo createOrderVo) {
        // 1. 校验客服合法性（角色判断）
        User customer = userService.getUserInfo(createOrderVo.getCustomerId());
        if (customer == null) {
            throw new BusinessException("客服不存在：客服ID=" + createOrderVo.getCustomerId());
        }
        UserRoleEnum customerRole = UserRoleEnum.fromCode(customer.getRole());
        if (customerRole == null || !customerRole.isCustomerService()) {
            throw new BusinessException("客服ID不合法：该用户不是客服角色（当前角色：" + customer.getRole() + "）");
        }

        // 2. 校验订单金额合法性（需大于0）
        if (createOrderVo.getAmount() == null || createOrderVo.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("订单金额异常：金额必须大于0（当前金额：" + createOrderVo.getAmount() + "）");
        }

        // 3. 生成唯一订单号
        String orderNo = generateUniqueOrderNo();

        // 4. 构建订单实体（用@RequiredArgsConstructor传入必填的customerId）
        Order order = new Order(createOrderVo.getCustomerId());
        order.setUserId(createOrderVo.getUserId());
        order.setOrderNo(orderNo);
        order.setProductName(createOrderVo.getProductName());
        order.setAmount(createOrderVo.getAmount().doubleValue());
        // 调用实体类方法设置“已支付”状态（自动同步支付时间，对接支付后改为默认待支付）
        order.markAsPaid();

        // 5. 保存订单
        return orderDao.save(order);
    }

    /**
     * 申请退款：筛选用户在该客服下的指定订单，更新状态为“退款中”
     */
    @Override
    public void applyRefund(String userId, String customerId, String orderNo) {
        // 1. 查询订单
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException("订单不存在：" + orderNo);
        }

        // 2. 校验订单归属
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new BusinessException("订单归属异常：订单" + orderNo + "不属于用户" + userId + "或客服" + customerId);
        }

        // 3. 校验订单状态（仅“已支付”可申请退款，排除已退款/退款中状态）
        if (!Order.OrderStatus.PAID.equals(order.getStatus())) {
            throw new BusinessException("订单" + orderNo + "状态异常，当前状态：" + order.getStatus().getDesc() + "，仅已支付订单可申请退款");
        }

        // 4. 调用实体类方法更新状态（自动同步退款申请时间）
        order.markAsRefunding();
        orderDao.save(order);
    }

    /**
     * 查询订单：按用户+客服双维度筛选，按创建时间倒序返回
     */
    @Override
    public List<Order> getUserOrdersByCustomer(String userId, String customerId) {
        // 1. 校验用户与客服合法性
        User user = userService.getUserInfo(userId);
        User customer = userService.getUserInfo(customerId);
        if (user == null) {
            throw new BusinessException("用户不存在：用户ID=" + userId);
        }
        if (customer == null) {
            throw new BusinessException("客服不存在：客服ID=" + customerId);
        }

        // 2. 校验客服角色
        UserRoleEnum customerRole = UserRoleEnum.fromCode(customer.getRole());
        if (customerRole == null || !customerRole.isCustomerService()) {
            throw new BusinessException("客服ID不合法：该用户不是客服角色（当前角色：" + customer.getRole() + "）");
        }

        // 3. 查询并排序
        return orderDao.findByUserIdAndCustomerId(userId, customerId).stream()
                .sorted(Comparator.comparing(Order::getCreateTime).reversed())
                .collect(Collectors.toList());
    }

    /**
     * 生成测试订单：用于模拟业务，快速创建测试数据
     */
    @Override
    public void createTestOrder(String userId, String customerId) {
        // 1. 校验客服合法性
        User customer = userService.getUserInfo(customerId);
        if (customer == null) {
            throw new BusinessException("客服不存在：客服ID=" + customerId);
        }
        UserRoleEnum customerRole = UserRoleEnum.fromCode(customer.getRole());
        if (customerRole == null || !customerRole.isCustomerService()) {
            throw new BusinessException("客服不合法：该用户不是客服角色（当前角色：" + customer.getRole() + "）");
        }

        // 2. 校验用户合法性
        User user = userService.getUserInfo(userId);
        if (user == null) {
            throw new BusinessException("用户不存在：用户ID=" + userId);
        }

        // 3. 构建测试订单
        Order testOrder = new Order(customerId); // 传入必填的customerId
        testOrder.setOrderNo("TEST-" + System.currentTimeMillis() + "-" + new Random().nextInt(1000));
        testOrder.setUserId(userId);
        testOrder.setProductName("测试商品-" + new Random().nextInt(100));
        testOrder.setAmount(99.9 + new Random().nextDouble() * 100); // 随机金额（99.9~199.9）
        testOrder.markAsPaid(); // 测试订单默认已支付（自动同步支付时间）

        // 4. 保存测试订单
        orderDao.save(testOrder);
    }


    // ------------------------------ 私有工具方法 ------------------------------

    /**
     * 生成唯一订单号（格式：ORD+年月日+6位随机数，确保不重复）
     */
    private String generateUniqueOrderNo() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMdd");
        String dateStr = dateFormat.format(new Date());
        Random random = new Random();
        String orderNo;

        // 循环校验：确保订单号唯一（防止随机数重复）
        do {
            String randomStr = String.format("%06d", random.nextInt(999999)); // 6位随机数（不足补0）
            orderNo = "ORD" + dateStr + randomStr;
        } while (orderDao.findByOrderNo(orderNo) != null);

        return orderNo;
    }
}