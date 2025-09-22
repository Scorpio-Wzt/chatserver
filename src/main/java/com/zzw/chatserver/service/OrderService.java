package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import java.util.List;

/**
 * 订单服务接口
 * 所有操作均基于“用户-客服”双维度，确保订单与特定客服绑定
 */

public interface OrderService {

    /**
     * 创建订单（强制关联用户与客服）
     * @param createOrderVo 订单创建参数，包含用户ID、客服ID等核心信息
     * @return 保存到数据库后的完整订单实体
     */
    Order createOrder(CreateOrderVo createOrderVo);

    /**
     * 申请退款（仅针对用户在特定客服下的订单）
     * @param userId 用户ID（订单所属用户）
     * @param customerId 客服ID（订单绑定的客服）
     */
    void applyRefund(String userId, String customerId);

    /**
     * 查询用户在特定客服下的所有订单
     * @param userId 用户ID
     * @param customerId 客服ID
     * @return 该用户在该客服下的订单列表（按创建时间倒序）
     */
    List<Order> getUserOrdersByCustomer(String userId, String customerId);

    /**
     * 生成测试订单（用于模拟业务，绑定用户与客服）
     * @param userId 用户ID
     * @param customerId 客服ID
     */
    void createTestOrder(String userId, String customerId);

    /**
     * 确认收货
     * @param userId 用户ID
     * @param customerId 客服ID
     * @param orderNo 订单编号
     */
    void confirmReceipt(String userId, String customerId, String orderNo);
}
