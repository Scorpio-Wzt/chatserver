package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.ConstValueEnum;
import com.zzw.chatserver.dao.OrderDao;
import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import com.zzw.chatserver.service.OrderService;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * 订单服务实现类
 * 实现OrderService接口定义的所有订单操作
 */
@Service
public class OrderServiceImpl implements OrderService {

    // 注入数据访问层依赖
    @Resource
    private OrderDao orderDao;

    /**
     * 创建订单核心逻辑
     * @param createOrderVo 订单创建参数
     * @return 保存后的订单实体
     */
    @Override
    public Order createOrder(CreateOrderVo createOrderVo) {
        // 1. 生成唯一订单号
        String orderNo = generateUniqueOrderNo();

        // 2. 构建Order实体
        Order order = new Order();
        order.setUserId(createOrderVo.getUserId());
        order.setOrderNo(orderNo);
        order.setProductName(createOrderVo.getProductName());
        order.setAmount(createOrderVo.getAmount().doubleValue()); // 适配Double类型
        order.setStatus(ConstValueEnum.ORDER_STATUS_PAID); // 默认已支付（实际需对接支付接口）
        order.setCreateTime(new Date());
        order.setPayTime(new Date()); // 同步设置支付时间
        order.setRefundTime(null);

        // 3. 保存订单到数据库
        return orderDao.save(order);
    }

    /**
     * 申请退款
     * @param userId 用户ID
     */
    @Override
    public void applyRefund(String userId) {
        // 查询用户所有订单
        List<Order> userOrders = orderDao.findByUserId(userId);
        if (userOrders == null || userOrders.isEmpty()) {
            throw new RuntimeException("无订单可退款");
        }

        // 筛选最新的可退款订单（已支付且未退款）
        Order latestRefundableOrder = userOrders.stream()
                .filter(order -> order.getStatus() == 1) // 1-已支付状态
                .max(Comparator.comparing(Order::getPayTime))
                .orElseThrow(() -> new RuntimeException("无符合条件的可退款订单"));

        // 更新订单状态为退款中
        latestRefundableOrder.setStatus(2); // 2-退款中
        latestRefundableOrder.setRefundTime(new Date());
        orderDao.save(latestRefundableOrder);
    }

    /**
     * 查询用户订单列表
     * @param userId 用户ID
     * @return 订单列表
     */
    @Override
    public List<Order> getUserOrders(String userId) {
        return orderDao.findByUserId(userId);
    }

    /**
     * 生成测试订单（用于模拟业务）
     * @param userId 用户ID
     */
    @Override
    public void createTestOrder(String userId) {
        Order order = new Order();
        order.setOrderNo(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        order.setUserId(userId);
        order.setProductName("测试商品-" + System.currentTimeMillis() % 1000);
        order.setAmount(99.9 + Math.random() * 100);
        order.setStatus(1); // 模拟已支付状态
        order.setCreateTime(new Date());
        order.setPayTime(new Date());
        orderDao.save(order);
    }

    /**
     * 工具方法：生成唯一订单号
     * 规则：ORD+日期（yyyyMMdd）+6位随机数，确保不重复
     */
    private String generateUniqueOrderNo() {
        String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());
        String randomStr = String.format("%06d", new Random().nextInt(999999));
        String orderNo = "ORD" + dateStr + randomStr;

        // 双重校验：防止极端情况下随机数重复
        while (orderDao.findByOrderNo(orderNo) != null) {
            randomStr = String.format("%06d", new Random().nextInt(999999));
            orderNo = "ORD" + dateStr + randomStr;
        }
        return orderNo;
    }
}
