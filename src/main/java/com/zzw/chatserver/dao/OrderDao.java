package com.zzw.chatserver.dao;

import com.zzw.chatserver.pojo.Order;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface OrderDao extends MongoRepository<Order, String> {

    // 根据订单号查询（用于订单号唯一性校验）
    Order findByOrderNo(String orderNo);

    // 根据用户ID和客服ID查询订单
    List<Order> findByUserIdAndCustomerId(String userId, String customerId);

}
