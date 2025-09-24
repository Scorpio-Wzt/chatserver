package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.RegisterRequestVo;
import org.bson.types.ObjectId;

import java.util.Map;

/**
 * 超级用户服务接口
 * 定义超级用户的创建、校验、登录等核心操作（含账号存在性校验、密码加密存储、JWT令牌生成等）
 */
public interface SuperUserService {

    /**
     * 根据sid查询超级管理员
     * @param sid
     * @return
     */
    SuperUser findBySid(ObjectId sid);

    /**
     * 账号不存在时新增超级用户（避免重复创建）
     * @param superUser 超级用户实体（含账号、密码等信息）
     */
    void notExistThenAddSuperUser(SuperUser superUser);

    /**
     * 新增超级用户（密码会自动加密）
     * @param superUser 超级用户实体（原始密码，未加密）
     */
    void addSuperUser(SuperUser superUser);

    /**
     * 根据账号查询超级用户是否存在
     * @param account 超级用户账号
     * @return 存在则返回超级用户实体，不存在则返回null
     */
    SuperUser existSuperUser(String account);

    /**
     * 超级用户登录（含账号校验、密码匹配、JWT令牌生成）
     * @param superUser 登录请求参数（含账号、原始密码）
     * @return 登录结果Map（含状态码、提示信息、用户信息、JWT令牌）
     */
    Map<String, Object> superUserLogin(SuperUser superUser);
}