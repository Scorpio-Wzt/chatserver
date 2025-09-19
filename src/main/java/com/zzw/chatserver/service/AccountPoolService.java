package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.AccountPool;

/**
 * 账号池服务接口
 * 定义账号池数据的保存、查询等核心操作契约
 */
public interface AccountPoolService {

    /**
     * 保存账号池数据
     * @param accountPool 账号池实体（包含账号类型、状态等信息）
     */
    void saveAccount(AccountPool accountPool);
}