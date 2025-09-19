package com.zzw.chatserver.dao;

import com.zzw.chatserver.pojo.Order;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OrderDao extends MongoRepository<Order, ObjectId> {
    // 根据用户ID查询订单
    List<Order> findByUserId(String userId);

    // 根据订单编号查询订单
    Order findByOrderNo(String orderNo);
}