package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.dao.SuperUserDao;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.service.SuperUserService;
import com.zzw.chatserver.utils.JwtUtils;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

/**
 * 超级用户服务实现类
 * 实现SuperUserService接口定义的超级用户管理逻辑，依赖DAO层、密码加密工具、JWT工具完成业务
 */
@Service
public class SuperUserServiceImpl implements SuperUserService {

    @Resource
    private SuperUserDao superUserDao;

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    @Resource
    private JwtUtils jwtUtils;

    /**
     * 账号不存在时新增超级用户
     */
    @Override
    public void notExistThenAddSuperUser(SuperUser superUser) {
        // 先校验账号是否已存在
        SuperUser existSuperUser = existSuperUser(superUser.getAccount());
        // 不存在则新增
        if (existSuperUser == null) {
            addSuperUser(superUser);
        }
    }

    /**
     * 新增超级用户（密码自动加密存储）
     */
    @Override
    public void addSuperUser(SuperUser superUser) {
        // 对原始密码进行BCrypt加密（避免明文存储）
        superUser.setPassword(bCryptPasswordEncoder.encode(superUser.getPassword()));
        // 保存到数据库
        superUserDao.save(superUser);
    }

    /**
     * 根据账号查询超级用户是否存在
     */
    @Override
    public SuperUser existSuperUser(String account) {
        // 构建查询条件：账号精确匹配
        Query query = Query.query(Criteria.where("account").is(account));
        // 执行查询，返回单个结果（账号唯一）
        return mongoTemplate.findOne(query, SuperUser.class);
    }

    /**
     * 超级用户登录逻辑
     */
    @Override
    public Map<String, Object> superUserLogin(SuperUser superUser) {
        Map<String, Object> resultMap = new HashMap<>();
        Integer code = null;
        String msg = null;

        // 1. 校验账号是否存在
        SuperUser existUser = existSuperUser(superUser.getAccount());
        if (existUser == null) {
            // 账号不存在
            code = ResultEnum.ACCOUNT_NOT_FOUND.getCode();
            msg = ResultEnum.ACCOUNT_NOT_FOUND.getMessage();
        } else {
            // 2. 校验密码是否匹配（原始密码 vs 加密密码）
            boolean isPwdMatch = bCryptPasswordEncoder.matches(
                    superUser.getPassword(),  // 登录时传入的原始密码
                    existUser.getPassword()   // 数据库中存储的加密密码
            );
            if (!isPwdMatch) {
                // 密码错误
                code = ResultEnum.OLD_PASSWORD_ERROR.getCode();
                msg = ResultEnum.OLD_PASSWORD_ERROR.getMessage();
            } else {
                // 3. 登录成功：生成JWT令牌，返回用户信息
                code = ResultEnum.LOGIN_SUCCESS.getCode();
                msg = ResultEnum.LOGIN_SUCCESS.getMessage();
                // 生成JWT（参数：超级用户ID、账号）
                String jwtToken = jwtUtils.createJwt(
                        existUser.getSid().toString(),
                        existUser.getAccount()
                );
                // 封装返回数据
                resultMap.put("userInfo", existUser);
                resultMap.put("token", jwtToken);
            }
        }

        // 统一封装状态码和提示信息
        resultMap.put("code", code);
        resultMap.put("msg", msg);
        return resultMap;
    }
}