package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.dao.AccountPoolDao;
import com.zzw.chatserver.pojo.AccountPool;
import com.zzw.chatserver.service.AccountPoolService;
import org.springframework.stereotype.Service;
import javax.annotation.Resource;

/**
 * 账号池服务实现类
 * 实现AccountPoolService接口，通过DAO层完成账号池数据的保存操作
 */
@Service
public class AccountPoolServiceImpl implements AccountPoolService {

    // 注入账号池DAO层依赖，用于数据持久化
    @Resource
    private AccountPoolDao accountPoolDao;

    /**
     * 保存账号池数据
     * 调用DAO层save方法，将账号池实体持久化到数据库
     * @param accountPool 账号池实体
     */
    @Override
    public void saveAccount(AccountPool accountPool) {
        // 直接委托DAO层完成保存（若后续需扩展逻辑，可在此处添加参数校验、日志记录等）
        accountPoolDao.save(accountPool);
    }
}