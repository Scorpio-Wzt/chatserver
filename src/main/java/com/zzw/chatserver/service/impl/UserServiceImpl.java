package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.ConstValueEnum;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.dao.AccountPoolDao;
import com.zzw.chatserver.dao.SuperUserDao;
import com.zzw.chatserver.dao.UserDao;
import com.zzw.chatserver.pojo.AccountPool;
import com.zzw.chatserver.pojo.GoodFriend;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.*;
import com.zzw.chatserver.service.GoodFriendService;
import com.zzw.chatserver.service.UserService;
import com.zzw.chatserver.utils.ChatServerUtil;
import com.zzw.chatserver.utils.DateUtil;
import org.apache.commons.lang3.StringUtils;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 用户服务实现类
 * 实现UserService接口定义的所有用户管理逻辑，依赖DAO层、加密工具、工具类完成业务落地
 */
@Service
public class UserServiceImpl implements UserService {

    @Resource
    private UserDao userDao;

    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private AccountPoolDao accountPoolDao;

    @Resource
    private BCryptPasswordEncoder bCryptPasswordEncoder;

    @Resource
    private GoodFriendService goodFriendService;  // 新增这一行

    @Override
    public Map<String, Object> registerServiceUser(RegisterRequestVo rVo, String operatorId) {
        Map<String, Object> map = new HashMap<>();
        Integer code = null;
        String msg = null;
        String userCode = null;
        String userId = null;

        // 校验操作者是否为超级管理员（需根据你的超级管理员判断逻辑实现）
        if (!isSuperAdmin(operatorId)) {
            code = ResultEnum.PERMISSION_DENIED.getCode();
            msg = "仅超级管理员可创建客服账号";
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        // 强制设置角色为客服（忽略请求中的role参数，防止恶意修改）
        rVo.setRole(UserRoleEnum.CUSTOMER_SERVICE.getCode());

        // 校验两次密码是否一致
        if (!rVo.getRePassword().equals(rVo.getPassword())) {
            code = ResultEnum.INCORRECT_PASSWORD_TWICE.getCode();
            msg = ResultEnum.INCORRECT_PASSWORD_TWICE.getMessage();
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        // 校验用户名是否已存在
        User existUser = userDao.findUserByUsername(rVo.getUsername());
        if (existUser != null) {
            code = ResultEnum.USER_HAS_EXIST.getCode();
            msg = ResultEnum.USER_HAS_EXIST.getMessage();
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        // 生成用户编码、加密密码等（复用注册逻辑）
        AccountPool accountPool = new AccountPool();
        accountPool.setType(ConstValueEnum.USERTYPE);
        accountPool.setStatus(ConstValueEnum.ACCOUNT_USED);
        accountPoolDao.save(accountPool);

        String encryptedPwd = bCryptPasswordEncoder.encode(rVo.getPassword());
        User user = new User();
        user.setUsername(rVo.getUsername());
        user.setPassword(encryptedPwd);
        user.setCode(String.valueOf(accountPool.getCode() + ConstValueEnum.INITIAL_NUMBER));
        user.setPhoto(rVo.getAvatar());
        user.setNickname(rVo.getNickname() != null ? rVo.getNickname() : ChatServerUtil.randomNickname());
        user.setSignUpTime(new Date());
        user.setStatus(0);
        user.setRole(UserRoleEnum.CUSTOMER_SERVICE.getCode()); // 强制客服角色

        userDao.save(user);

        // 4. 处理客服的自动好友关系（与所有现有用户关联）
        handleAutoFriendRelation(user);

        // 5. 返回结果（包含userId，供管理员后续操作）
        userCode = user.getCode();
        code = ResultEnum.REGISTER_SUCCESS.getCode();
        msg = ResultEnum.REGISTER_SUCCESS.getMessage();
        map.put("code", code);
        map.put("msg", msg);
        map.put("userCode", userCode);

        return map;
    }

    // 新增：判断操作者是否为超级管理员（需根据你的实际逻辑实现）
    private boolean isSuperAdmin(String operatorId) {
        // 示例逻辑：查询用户角色是否为超级管理员（需根据你的用户表设计调整）
        User operator = userDao.findById(new ObjectId(operatorId)).orElse(null);
        if (operator == null) {
            return false;
        }
        // 假设超级管理员的角色标识为 "super_admin"（需与你的枚举或常量一致）
        return "super_admin".equals(operator.getRole());
    }

    /**
     * 普通注册逻辑
     */
    @Override
    public Map<String, Object> register(RegisterRequestVo rVo) {
        Map<String, Object> map = new HashMap<>();
        Integer code = null;
        String msg = null;
        String userCode = null;

        // 1. 校验两次密码是否一致
        if (!rVo.getRePassword().equals(rVo.getPassword())) {
            code = ResultEnum.INCORRECT_PASSWORD_TWICE.getCode();
            msg = ResultEnum.INCORRECT_PASSWORD_TWICE.getMessage();
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        // 2. 校验用户名是否已存在
        User existUser = userDao.findUserByUsername(rVo.getUsername());
        if (existUser != null) {
            code = ResultEnum.USER_HAS_EXIST.getCode();
            msg = ResultEnum.USER_HAS_EXIST.getMessage();
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        // 3. 生成用户唯一编码（基于AccountPool）
        AccountPool accountPool = new AccountPool();
        accountPool.setType(ConstValueEnum.USERTYPE); // 用户类型标识
        accountPool.setStatus(ConstValueEnum.ACCOUNT_USED); // 标记为已使用
        accountPoolDao.save(accountPool);

        // 4. 加密密码并创建用户实体
        String encryptedPwd = bCryptPasswordEncoder.encode(rVo.getPassword());
        User user = new User();
        user.setUsername(rVo.getUsername());
        user.setPassword(encryptedPwd);
        user.setCode(String.valueOf(accountPool.getCode() + ConstValueEnum.INITIAL_NUMBER));
        user.setPhoto(rVo.getAvatar());
        user.setNickname(rVo.getNickname() != null ? rVo.getNickname() : ChatServerUtil.randomNickname()); // 生成随机昵称
        user.setSignUpTime(new Date());
        user.setStatus(0); // 正常状态
        user.setRole(UserRoleEnum.BUYER.getCode());

        userDao.save(user);

        //处理好友关系自动关联
        handleAutoFriendRelation(user);

        // 注册成功，封装结果
        userCode = user.getCode();
        code = ResultEnum.REGISTER_SUCCESS.getCode();
        msg = ResultEnum.REGISTER_SUCCESS.getMessage();
        map.put("code", code);
        map.put("msg", msg);
        map.put("userCode", userCode);
        return map;
    }

    /**
     * 处理用户注册后的自动好友关系：
     * - 若为客服：自动添加所有现有用户为好友，并让现有用户添加该客服为好友
     * - 若为买家：自动添加所有现有客服为好友
     */
    private void handleAutoFriendRelation(User newUser) {
        if (UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(newUser.getRole())) {
            // 客服注册：关联所有现有用户
            List<User> allUsers = mongoTemplate.findAll(User.class);
            List<GoodFriend> friendRelations = new ArrayList<>();
            for (User existUser : allUsers) {
                if (!existUser.getUid().equals(newUser.getUid())) { // 排除自己
                    // 新增：新客服 -> 现有用户 的好友关系
                    GoodFriend friend1 = new GoodFriend();
                    friend1.setUserM(newUser.getUserId());
                    friend1.setUserY(existUser.getUserId());

                    // 新增：现有用户 -> 新客服 的好友关系
                    GoodFriend friend2 = new GoodFriend();
                    friend2.setUserM(existUser.getUserId());
                    friend2.setUserY(newUser.getUserId());

                    friendRelations.add(friend1);
                    friendRelations.add(friend2);

                    // 添加到好友分组（默认"我的好友"）
                    addToFriendGroup(existUser, newUser.getUid());
                    addToFriendGroup(newUser, existUser.getUid());
                }
            }
            // 批量添加好友关系（减少数据库交互）
            if (!friendRelations.isEmpty()) {
                goodFriendService.batchAddFriends(friendRelations);
            }
        } else {
            // 买家注册：关联所有现有客服
            List<User> customerServices = mongoTemplate.find(
                    Query.query(Criteria.where("role").is(UserRoleEnum.CUSTOMER_SERVICE.getCode())),
                    User.class
            );
            List<GoodFriend> friendRelations = new ArrayList<>();
            for (User cs : customerServices) {
                // 新增：买家 -> 客服 的好友关系
                GoodFriend friend1 = new GoodFriend();
                friend1.setUserM(newUser.getUserId());
                friend1.setUserY(cs.getUserId());

                // 新增：客服 -> 买家 的好友关系
                GoodFriend friend2 = new GoodFriend();
                friend2.setUserM(cs.getUserId());
                friend2.setUserY(newUser.getUserId());

                friendRelations.add(friend1);
                friendRelations.add(friend2);

                // 添加到好友分组
                addToFriendGroup(newUser, cs.getUid());
                addToFriendGroup(cs, newUser.getUid());
            }
            if (!friendRelations.isEmpty()) {
                goodFriendService.batchAddFriends(friendRelations);
            }
        }
    }

    /**
     * 新增好友分组
     */
    @Override
    public void addNewFenZu(NewFenZuRequestVo requestVo) {
        // 1. 获取用户信息
        User userInfo = userDao.findById(new ObjectId(requestVo.getUserId())).orElse(null);
        if (userInfo == null) {
            return; // 用户不存在，不执行操作
        }

        // 2. 校验分组是否已存在，不存在则新增
        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        if (!friendFenZuMap.containsKey(requestVo.getFenZuName())) {
            friendFenZuMap.put(requestVo.getFenZuName(), new ArrayList<>());

            // 3. 更新用户分组信息到数据库
            Update update = new Update().set("friendFenZu", friendFenZuMap);
            Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
            mongoTemplate.findAndModify(query, update, User.class);
        }
    }

    /**
     * 将好友添加到用户的"我的好友"分组
     */
    private void addToFriendGroup(User user, String friendUid) {
        user.getFriendFenZu().computeIfAbsent("我的好友", k -> new java.util.ArrayList<>())
                .add(friendUid);
        mongoTemplate.save(user);
    }

    /**
     * 根据用户ID查询用户信息
     */
    @Override
    public User getUserInfo(String userId) {
        return userDao.findById(new ObjectId(userId)).orElse(null);
    }

    /**
     * 修改好友备注
     */
    @Override
    public void modifyBeiZhu(ModifyFriendBeiZhuRequestVo requestVo) {
        // 1. 获取用户信息
        User userInfo = getUserInfo(requestVo.getUserId());
        if (userInfo == null) {
            return;
        }

        // 2. 更新好友备注Map
        Map<String, String> friendBeiZhuMap = userInfo.getFriendBeiZhu();
        friendBeiZhuMap.put(requestVo.getFriendId(), requestVo.getFriendBeiZhuName());

        // 3. 保存更新到数据库
        Update update = new Update().set("friendBeiZhu", friendBeiZhuMap);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
        mongoTemplate.findAndModify(query, update, User.class);
    }

    /**
     * 移动好友到其他分组
     */
    @Override
    public void modifyFriendFenZu(ModifyFriendFenZuRequestVo requestVo) {
        // 1. 获取用户信息
        User userInfo = getUserInfo(requestVo.getUserId());
        if (userInfo == null) {
            return;
        }

        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        boolean isRemoved = false;

        // 2. 从原分组中移除好友
        for (Map.Entry<String, ArrayList<String>> entry : friendFenZuMap.entrySet()) {
            Iterator<String> iterator = entry.getValue().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().equals(requestVo.getFriendId())) {
                    // 原分组不是新分组才移除
                    if (!entry.getKey().equals(requestVo.getNewFenZuName())) {
                        iterator.remove();
                    }
                    isRemoved = true;
                    break;
                }
            }
            if (isRemoved) {
                break;
            }
        }

        // 3. 将好友添加到新分组
        friendFenZuMap.get(requestVo.getNewFenZuName()).add(requestVo.getFriendId());

        // 4. 保存更新到数据库
        Update update = new Update().set("friendFenZu", friendFenZuMap);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
        mongoTemplate.findAndModify(query, update, User.class);
    }

    /**
     * 删除好友分组
     */
    @Override
    public void deleteFenZu(DelFenZuRequestVo requestVo) {
        // 1. 获取用户信息
        User userInfo = getUserInfo(requestVo.getUserId());
        if (userInfo == null) {
            return;
        }

        // 2. 从分组Map中移除指定分组
        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        friendFenZuMap.remove(requestVo.getFenZuName());

        // 3. 保存更新到数据库
        Update update = new Update().set("friendFenZu", friendFenZuMap);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
        mongoTemplate.findAndModify(query, update, User.class);
    }

    /**
     * 编辑分组名称（重命名）
     */
    @Override
    public void editFenZu(EditFenZuRequestVo requestVo) {
        // 1. 获取用户信息
        User userInfo = getUserInfo(requestVo.getUserId());
        if (userInfo == null) {
            return;
        }

        // 2. 迁移旧分组的用户到新分组，删除旧分组
        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        ArrayList<String> oldFenZuUsers = friendFenZuMap.get(requestVo.getOldFenZu());
        friendFenZuMap.remove(requestVo.getOldFenZu());
        friendFenZuMap.put(requestVo.getNewFenZu(), oldFenZuUsers);

        // 3. 保存更新到数据库
        Update update = new Update().set("friendFenZu", friendFenZuMap);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
        mongoTemplate.findAndModify(query, update, User.class);
    }

    /**
     * 搜索用户
     */
    @Override
    public List<User> searchUser(SearchRequestVo requestVo, String uid) {
        // 1. 构建模糊查询条件（不区分大小写）
        Criteria criteria = Criteria.where(requestVo.getType())
                .regex(Pattern.compile("^.*" + requestVo.getSearchContent() + ".*$", Pattern.CASE_INSENSITIVE))
                .and("uid").ne(uid); // 排除当前用户

        // 2. 构建查询（分页+按ID降序）
        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .skip((long) requestVo.getPageIndex() * requestVo.getPageSize())
                .limit(requestVo.getPageSize());

        // 3. 执行查询并返回结果
        return mongoTemplate.find(query, User.class);
    }

    /**
     * 更新用户在线时长
     */
    @Override
    public void updateOnlineTime(long onlineTime, String uid) {
        Update update = new Update().set("onlineTime", onlineTime);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(uid)));
        mongoTemplate.upsert(query, update, User.class);
    }

    /**
     * 更新用户基本信息
     */
    @Override
    public Map<String, Object> updateUserInfo(UpdateUserInfoRequestVo requestVo) {
        Map<String, Object> map = new HashMap<>();
        Integer code = null;
        String msg = null;
        Update update = new Update();
        boolean hasError = false;

        // 1. 按字段类型进行合法性校验
        switch (requestVo.getField()) {
            case "sex":
                // 性别需为数字（0=未知、1=男、3=女）
                String sexStr = (String) requestVo.getValue();
                if (!ChatServerUtil.isNumeric(sexStr)) {
                    code = ResultEnum.ERROR_SETTING_GENDER.getCode();
                    msg = ResultEnum.ERROR_SETTING_GENDER.getMessage();
                    hasError = true;
                } else {
                    Integer sex = Integer.valueOf(sexStr);
                    if (sex != 0 && sex != 1 && sex != 3) {
                        code = ResultEnum.ERROR_SETTING_GENDER.getCode();
                        msg = ResultEnum.ERROR_SETTING_GENDER.getMessage();
                        hasError = true;
                    } else {
                        update.set(requestVo.getField(), sex);
                    }
                }
                break;
            case "age":
                // 年龄需为数字
                String ageStr = requestVo.getValue().toString();
                if (!ChatServerUtil.isNumeric(ageStr)) {
                    code = ResultEnum.ERROR_SETTING_AGE.getCode();
                    msg = ResultEnum.ERROR_SETTING_AGE.getMessage();
                    hasError = true;
                } else {
                    update.set(requestVo.getField(), Integer.valueOf(ageStr));
                }
                break;
            case "email":
                // 邮箱格式校验
                String email = (String) requestVo.getValue();
                if (!ChatServerUtil.isEmail(email)) {
                    code = ResultEnum.ERROR_SETTING_EMAIL.getCode();
                    msg = ResultEnum.ERROR_SETTING_EMAIL.getMessage();
                    hasError = true;
                } else {
                    update.set(requestVo.getField(), email);
                }
                break;
            default:
                // 其他字段（如昵称、签名）直接更新
                update.set(requestVo.getField(), requestVo.getValue());
                break;
        }

        // 2. 无错误则执行更新，有错误则返回错误信息
        if (!hasError) {
            Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
            mongoTemplate.upsert(query, update, User.class);
        } else {
            map.put("code", code);
            map.put("msg", msg);
        }
        return map;
    }

    /**
     * 修改用户密码
     */
    @Override
    public Map<String, Object> updateUserPwd(UpdateUserPwdRequestVo requestVo) {
        Map<String, Object> map = new HashMap<>();
        Integer code = null;
        String msg = null;

        // 1. 校验两次新密码是否一致
        if (!requestVo.getReNewPwd().equals(requestVo.getNewPwd())) {
            code = ResultEnum.INCORRECT_PASSWORD_TWICE.getCode();
            msg = ResultEnum.INCORRECT_PASSWORD_TWICE.getMessage();
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        // 2. 校验旧密码是否正确
        User userInfo = getUserInfo(requestVo.getUserId());
        if (!bCryptPasswordEncoder.matches(requestVo.getOldPwd(), userInfo.getPassword())) {
            code = ResultEnum.OLD_PASSWORD_ERROR.getCode();
            msg = ResultEnum.OLD_PASSWORD_ERROR.getMessage();
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        // 3. 加密新密码并更新
        String encryptedNewPwd = bCryptPasswordEncoder.encode(requestVo.getNewPwd());
        Update update = new Update().set("password", encryptedNewPwd);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
        mongoTemplate.upsert(query, update, User.class);

        // 4. 更新成功，返回结果
        code = ResultEnum.SUCCESS.getCode();
        msg = "更新成功，请牢记你的新密码";
        map.put("code", code);
        map.put("msg", msg);
        return map;
    }

    /**
     * 更新用户个性化配置
     */
    @Override
    public boolean updateUserConfigure(UpdateUserConfigureRequestVo requestVo, String uid) {
        // 构建更新条件和字段
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(uid)));
        Update update = new Update()
                .set("opacity", requestVo.getOpacity())
                .set("blur", requestVo.getBlur())
                .set("bgImg", requestVo.getBgImg())
                .set("customBgImgUrl", requestVo.getCustomBgImgUrl())
                .set("notifySound", requestVo.getNotifySound())
                .set("color", requestVo.getColor())
                .set("bgColor", requestVo.getBgColor());

        // 执行更新，返回是否修改成功（修改行数>0即为成功）
        return mongoTemplate.upsert(query, update, User.class).getModifiedCount() > 0;
    }

    /**
     * 获取所有用户列表
     */
    @Override
    public List<User> getUserList() {
        return userDao.findAll();
    }

    /**
     * 根据注册时间范围查询用户
     */
    @Override
    public List<User> getUsersBySignUpTime(String lt, String rt) {
        // 转换时间格式并构建查询条件
        Criteria criteria = Criteria.where("signUpTime")
                .gte(DateUtil.parseDate(lt, DateUtil.yyyy_MM))
                .lte(DateUtil.parseDate(rt, DateUtil.yyyy_MM));
        Query query = Query.query(criteria);

        // 执行查询并返回结果
        return mongoTemplate.find(query, User.class);
    }

    /**
     * 修改用户状态
     */
    @Override
    public void changeUserStatus(String uid, Integer status) {
        Update update = new Update().set("status", status);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(uid)));
        mongoTemplate.findAndModify(query, update, User.class);
    }
}