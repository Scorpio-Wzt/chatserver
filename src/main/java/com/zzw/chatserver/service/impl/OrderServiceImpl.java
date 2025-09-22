package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.ConstValueEnum;
import com.zzw.chatserver.dao.OrderDao;
import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import com.zzw.chatserver.service.OrderService;
import com.zzw.chatserver.service.UserService;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 订单服务实现类（绑定用户与客服）
 */
@Service
public class OrderServiceImpl implements OrderService {

    @Resource
    private OrderDao orderDao;

    @Resource
    private UserService userService; // 用于校验客服是否存在

    /**
     * 确认收货实现逻辑
     */
    @Override
    public void confirmReceipt(String userId, String customerId, String orderNo) {
        // 1. 查找订单
        Order order = orderDao.findByOrderNo(orderNo);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }

        // 2. 验证订单是否属于该用户和客服
        if (!order.getUserId().equals(userId) || !order.getCustomerId().equals(customerId)) {
            throw new RuntimeException("无权操作此订单");
        }

        // 3. 验证订单状态是否为已支付（只有已支付订单可以确认收货）
        if (order.getStatus() != ConstValueEnum.ORDER_STATUS_PAID) {
            throw new RuntimeException("只有已支付的订单才能确认收货");
        }

        // 4. 更新订单状态为已确认收货
        order.setStatus(ConstValueEnum.ORDER_STATUS_CONFIRMED);

        // 5. 添加确认收货时间
        order.setConfirmTime(new Date());

        // 6. 保存更新
        orderDao.save(order);
    }

    /**
     * 创建订单（强制绑定用户与客服）
     */
    @Override
    public Order createOrder(CreateOrderVo createOrderVo) {
        // 1. 校验客服是否存在
        User customer = userService.getUserInfo(createOrderVo.getCustomerId());
        if (customer == null) {
            throw new RuntimeException("客服不存在，无法创建订单");
        }

        // 2. 生成唯一订单号
        String orderNo = generateUniqueOrderNo();

        // 3. 构建Order实体（绑定用户与客服）
        Order order = new Order();
        order.setUserId(createOrderVo.getUserId());
        order.setCustomerId(createOrderVo.getCustomerId()); // 绑定客服ID
        order.setOrderNo(orderNo);
        order.setProductName(createOrderVo.getProductName());
        order.setAmount(createOrderVo.getAmount().doubleValue());
        order.setStatus(ConstValueEnum.ORDER_STATUS_PAID); // 默认已支付（实际需对接支付）
        order.setCreateTime(new Date());
        order.setPayTime(new Date());
        order.setRefundTime(null);

        // 4. 保存订单
        return orderDao.save(order);
    }

    /**
     * 申请退款（仅针对用户在特定客服下的订单）
     */
    @Override
    public void applyRefund(String userId, String customerId) {
        // 1. 查询用户在该客服下的所有订单
        List<Order> userCustomerOrders = orderDao.findByUserIdAndCustomerId(userId, customerId);
        if (userCustomerOrders.isEmpty()) {
            throw new RuntimeException("该用户在当前客服下无订单，无法退款");
        }

        // 2. 筛选最新的可退款订单（已支付且未退款）
        Order latestRefundableOrder = userCustomerOrders.stream()
                .filter(order -> order.getStatus() == 1) // 1-已支付
                .max(Comparator.comparing(Order::getPayTime))
                .orElseThrow(() -> new RuntimeException("该用户在当前客服下无符合条件的可退款订单"));

        // 3. 更新订单状态为退款中
        latestRefundableOrder.setStatus(2); // 2-退款中
        latestRefundableOrder.setRefundTime(new Date());
        orderDao.save(latestRefundableOrder);
    }

    /**
     * 查询用户在特定客服下的订单列表
     */
    @Override
    public List<Order> getUserOrdersByCustomer(String userId, String customerId) {
        // 直接查询用户+客服双条件的订单
        return orderDao.findByUserIdAndCustomerId(userId, customerId);
    }

    /**
     * 生成测试订单（绑定用户与客服）
     */
    @Override
    public void createTestOrder(String userId, String customerId) {
        // 校验客服是否存在
        User customer = userService.getUserInfo(customerId);
        if (customer == null) {
            throw new RuntimeException("客服不存在，无法生成测试订单");
        }

        Order order = new Order();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        order.setUserId(userId);
        order.setCustomerId(customerId); // 绑定客服
        order.setProductName("测试商品-" + System.currentTimeMillis() % 1000);
        order.setAmount(99.9 + Math.random() * 100);
        order.setStatus(1); // 已支付
        order.setCreateTime(new Date());
        order.setPayTime(new Date());
        orderDao.save(order);
    }

    /**
     * 生成唯一订单号
     */
    private String generateUniqueOrderNo() {
        String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());
        String randomStr = String.format("%06d", new Random().nextInt(999999));
        String orderNo = "ORD" + dateStr + randomStr;

        // 双重校验：防止重复
        while (orderDao.findByOrderNo(orderNo) != null) {
            randomStr = String.format("%06d", new Random().nextInt(999999));
            orderNo = "ORD" + dateStr + randomStr;
        }
        return orderNo;
    }
}
