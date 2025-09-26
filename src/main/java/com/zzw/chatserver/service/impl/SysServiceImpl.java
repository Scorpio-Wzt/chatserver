package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.dao.SysDao;
import com.zzw.chatserver.pojo.FeedBack;
import com.zzw.chatserver.pojo.SensitiveMessage;
import com.zzw.chatserver.pojo.SystemUser;
import com.zzw.chatserver.pojo.vo.FeedBackResultVo;
import com.zzw.chatserver.pojo.vo.SensitiveMessageResultVo;
import com.zzw.chatserver.pojo.vo.SystemUserResponseVo;
import com.zzw.chatserver.service.SysService;
import org.springframework.beans.BeanUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统服务实现类
 * 实现SysService接口定义的系统操作逻辑，依赖DAO层和MongoTemplate完成数据持久化
 */
@Service
public class SysServiceImpl implements SysService {

    @Resource
    private SysDao sysDao;

    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 系统用户不存在时新增（系统启动初始化用）
     */
    @Override
    public void notExistThenAddSystemUser(SystemUser user) {
        // 按系统用户唯一标识code查询是否存在
        Query query = Query.query(Criteria.where("code").is(user.getCode()));
        SystemUser existingUser = mongoTemplate.findOne(query, SystemUser.class);

        // 不存在则新增到数据库
        if (existingUser == null) {
            sysDao.save(user);
        }
    }

    /**
     * 获取所有系统用户列表
     */
    @Override
    public List<SystemUserResponseVo> getSysUsers() {
        // 查询所有系统用户
        List<SystemUser> systemUsers = sysDao.findAll();
        // 转换为返回VO列表（隐藏敏感字段，仅返回必要信息）
        List<SystemUserResponseVo> resultList = new ArrayList<>();
        for (SystemUser son : systemUsers) {
            SystemUserResponseVo item = new SystemUserResponseVo();
            BeanUtils.copyProperties(son, item);
            // 将MongoDB自动生成的ObjectId转为字符串，便于前端处理
            item.setSid(son.getId().toString());
            resultList.add(item);
        }
        return resultList;
    }

    /**
     * 新增用户反馈
     */
    @Override
    public void addFeedBack(FeedBack feedBack) {
        // 明确指定集合名"feedbacks"，将反馈数据插入MongoDB
        mongoTemplate.insert(feedBack, "feedbacks");
    }

    /**
     * 敏感消息记录
     */
    @Override
    public void addSensitiveMessage(SensitiveMessage sensitiveMessage) {
        // 明确指定集合名"sensitivemessages"，将敏感消息插入MongoDB
        mongoTemplate.insert(sensitiveMessage, "sensitivemessages");
    }

    /**
     * 获取所有敏感消息记录
     */
    @Override
    public List<SensitiveMessageResultVo> getSensitiveMessageList() {
        // 从"sensitivemessages"集合查询所有敏感消息，直接转换为VO列表
        return mongoTemplate.findAll(SensitiveMessageResultVo.class, "sensitivemessages");
    }

    /**
     * 获取所有用户反馈
     */
    @Override
    public List<FeedBackResultVo> getFeedbackList() {
        // 从"feedbacks"集合查询所有反馈，直接转换为VO列表
        return mongoTemplate.findAll(FeedBackResultVo.class, "feedbacks");
    }
}