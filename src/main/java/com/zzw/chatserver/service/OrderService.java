package com.zzw.chatserver.service;

import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.pojo.Order;
import com.zzw.chatserver.pojo.vo.CreateOrderVo;
import java.util.List;

/**
 * 订单服务接口
 * 定义订单核心业务逻辑，所有操作均基于“用户-客服”双维度绑定
 */
public interface OrderService {

    /**
     * 通过订单号查询订单
     * @param orderNo
     * @return
     */
    Order getOrderByNo(String orderNo);

    /**
     * 创建订单（仅客服调用，强制绑定用户与客服）
     * @param createOrderVo 订单创建参数（含用户ID、客服ID、商品信息等）
     * @return 保存后的完整订单实体
     * @throws BusinessException 当客服不存在、用户不存在或参数异常时抛出
     */
    Order createOrder(CreateOrderVo createOrderVo);

    /**
     * 申请退款（用户本人/客服均可调用，仅针对绑定的用户-客服订单）
     * @param userId 订单所属用户ID
     * @param customerId 绑定的客服ID
     * @throws BusinessException 当无符合条件的可退款订单（如未支付、已退款）时抛出
     */
    void applyRefund(String userId, String customerId, String  orderNo);

    /**
     * 查询订单（用户本人/客服均可调用，按创建时间倒序）
     * @param userId 订单所属用户ID
     * @param customerId 绑定的客服ID
     * @return 该用户在该客服下的所有订单列表
     * @throws BusinessException 当用户/客服不存在时抛出
     */
    List<Order> getUserOrdersByCustomer(String userId, String customerId);

    /**
     * 生成测试订单（用于模拟业务，绑定用户与客服）
     * @param userId 用户ID
     * @param customerId 客服ID
     * @throws BusinessException 当客服不存在或用户不存在时抛出
     */
    void createTestOrder(String userId, String customerId);

    /**
     * 确认收货（仅用户本人调用，需验证订单状态）
     * @param userId 订单所属用户ID
     * @param customerId 绑定的客服ID
     * @param orderNo 订单编号
     * @throws BusinessException 当订单不存在、无权操作或订单状态异常（如未支付）时抛出
     */
    void confirmReceipt(String userId, String customerId, String orderNo);
}