package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.FeedBack;
import com.zzw.chatserver.pojo.SensitiveMessage;
import com.zzw.chatserver.pojo.SystemUser;
import com.zzw.chatserver.pojo.vo.FeedBackResultVo;
import com.zzw.chatserver.pojo.vo.SensitiveMessageResultVo;
import com.zzw.chatserver.pojo.vo.SystemUserResponseVo;

import java.util.List;

/**
 * 系统服务接口
 * 定义系统核心操作：系统用户管理、反馈管理、敏感消息管理等
 */
public interface SysService {

    /**
     * 系统启动时检查系统用户是否存在，不存在则新增（避免重复创建）
     * @param user 系统用户实体（含唯一标识code等信息）
     */
    void notExistThenAddSystemUser(SystemUser user);

    /**
     * 获取所有系统用户列表
     * @return 系统用户列表VO（含用户ID、code、昵称等信息）
     */
    List<SystemUserResponseVo> getSysUsers();

    /**
     * 新增用户反馈
     * @param feedBack 反馈实体（含反馈内容、用户信息等）
     */
    void addFeedBack(FeedBack feedBack);

    /**
     * 新增敏感消息记录（含敏感词的消息）
     * @param sensitiveMessage 敏感消息实体（含发送者、消息内容、时间等）
     */
    void addSensitiveMessage(SensitiveMessage sensitiveMessage);

    /**
     * 获取所有敏感消息记录列表
     * @return 敏感消息列表VO
     */
    List<SensitiveMessageResultVo> getSensitiveMessageList();

    /**
     * 获取所有用户反馈列表
     * @return 反馈列表VO
     */
    List<FeedBackResultVo> getFeedbackList();
}