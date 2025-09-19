package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import java.util.List;

/**
 * 订单服务接口
 * 定义订单的创建、退款、查询等核心操作
 */
public interface OrderService {

    /**
     * 创建订单
     * @param createOrderVo 订单创建参数VO
     * @return 保存后的订单实体
     */
    Order createOrder(CreateOrderVo createOrderVo);

    /**
     * 申请退款
     * @param userId 用户ID
     */
    void applyRefund(String userId);

    /**
     * 查询用户订单列表
     * @param userId 用户ID
     * @return 该用户的所有订单
     */
    List<Order> getUserOrders(String userId);

    /**
     * 生成测试订单（用于模拟业务）
     * @param userId 用户ID
     */
    void createTestOrder(String userId);
}
