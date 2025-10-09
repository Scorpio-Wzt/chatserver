package com.zzw.chatserver.service.impl;

import com.zzw.chatserver.common.ConstValueEnum;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.common.UserStatusEnum;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.dao.AccountPoolDao;
import com.zzw.chatserver.dao.UserDao;
import com.zzw.chatserver.pojo.AccountPool;
import com.zzw.chatserver.pojo.GoodFriend;
import com.zzw.chatserver.pojo.SuperUser;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.*;
import com.zzw.chatserver.service.GoodFriendService;
import com.zzw.chatserver.service.SuperUserService;
import com.zzw.chatserver.service.UserService;
import com.zzw.chatserver.utils.ChatServerUtil;
import com.zzw.chatserver.utils.DateUtil;
import com.zzw.chatserver.utils.SensitiveInfoDesensitizerUtil;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import javax.annotation.Resource;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 用户服务实现类
 * 实现UserService接口定义的所有用户管理逻辑，依赖DAO层、加密工具、工具类完成业务落地
 */
@Service
@Slf4j
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
    private GoodFriendService goodFriendService;

    @Resource
    private SuperUserService superUserService;

    /**
     * 获取当前登录用户的ID（uid或超级管理员sid的字符串形式）
     * @return 当前登录用户ID，未登录或认证失败时返回null
     */
    @Override
    public String getCurrentUserId() {
        // 1. 从Spring Security上下文获取认证信息
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        // 2. 校验认证状态（未认证或匿名用户直接返回null）
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication.getPrincipal() == "anonymousUser") {
            log.warn("当前用户未认证或为匿名用户，无法获取用户ID");
            return null;
        }

        // 3. 从认证信息中提取用户名
        String username = null;
        if (authentication.getPrincipal() instanceof UserDetails) {
            // 标准认证流程：从UserDetails中获取用户名
            UserDetails userDetails = (UserDetails) authentication.getPrincipal();
            username = userDetails.getUsername();
        } else if (authentication.getPrincipal() instanceof String) {
            // 特殊情况：principal直接存储为用户名字符串
            username = (String) authentication.getPrincipal();
        }

        if (username == null || username.isEmpty()) {
            log.warn("无法从认证信息中提取用户名");
            return null;
        }

        // 4. 先尝试查询普通用户
        User user = userDao.findUserByUsername(username);
        if (user != null && user.getUid() != null) {
            return user.getUid(); // 返回普通用户的uid
        }

        // 5. 普通用户不存在时，尝试查询超级管理员
        SuperUser superUser = superUserService.existSuperUser(username);
        if (superUser != null && superUser.getSid() != null) {
            return superUser.getSid().toString(); // 返回超级管理员sid的字符串形式
        }

        // 6. 所有查询失败的情况
        log.warn("未找到用户名[{}]对应的用户或超级管理员信息", username);
        return null;
    }


    @Override
    public User findUserByUsername(String username) {
        return userDao.findUserByUsername(username);
    }

    @Override
    public Map<String, Object> registerServiceUser(RegisterRequestVo rVo, String operatorId) {
        Map<String, Object> map = new HashMap<>();
        Integer code = null;
        String msg = null;
        String userCode = null;
        String userIdStr = null;
        String uid = null;

        // 校验操作者是否为超级管理员
        if (!isSuperAdmin(operatorId)) {
            code = ResultEnum.PERMISSION_DENIED.getCode();
            msg = "仅超级管理员可创建客服账号";
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        // 强制设置角色为客服
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

        // 生成用户编码、加密密码等
        AccountPool accountPool = new AccountPool();
        accountPool.setType(ConstValueEnum.USERTYPE);
        accountPool.setStatus(ConstValueEnum.ACCOUNT_USED);
        accountPoolDao.save(accountPool);

        String encryptedPwd = bCryptPasswordEncoder.encode(rVo.getPassword());
        User user = new User();
        user.setUserId(new ObjectId()); // 生成userId
        user.setUid(user.getUserId().toString()); // 用userId的字符串作为uid
        user.setUsername(rVo.getUsername());
        user.setPassword(encryptedPwd);
        user.setCode(String.valueOf(accountPool.getCode() + ConstValueEnum.INITIAL_NUMBER));
        user.setPhoto(rVo.getAvatar());
        user.setNickname(rVo.getNickname() != null ? rVo.getNickname() : ChatServerUtil.randomNickname());
        user.setSignUpTime(String.valueOf(Instant.now()));
        user.setStatus(0);
        user.setRole(UserRoleEnum.CUSTOMER_SERVICE.getCode());

        // 保存用户（MongoDB自动生成userId，触发User类的setUserId方法同步uid）
        User savedUser = userDao.save(user);

        // 校验uid是否正确赋值
        if (savedUser.getUid() == null || !savedUser.getUid().equals(savedUser.getUserId().toString())) {
            log.warn("客服用户[{}]的uid未正确同步，手动修正", savedUser.getUsername());
            savedUser.setUid(savedUser.getUserId().toString());
            userDao.save(savedUser);
        }

        // 处理自动好友关系
        handleAutoFriendRelation(savedUser);

        // 封装返回结果（包含userId字符串和uid）
        userCode = savedUser.getCode();
        userIdStr = savedUser.getUserId().toString(); // userId的字符串形式
        uid = savedUser.getUid(); // 与userIdStr一致，冗余返回方便前端使用
        code = ResultEnum.REGISTER_SUCCESS.getCode();
        msg = ResultEnum.REGISTER_SUCCESS.getMessage();
        map.put("code", code);
        map.put("msg", msg);
        map.put("userCode", userCode);
        map.put("userId", userIdStr);
        map.put("uid", uid);

        return map;
    }

    // 判断操作者是否为超级管理员
    public boolean isSuperAdmin(String operatorId) {
        try {
            // operatorId应为uid（即ObjectId的字符串形式），转换为ObjectId查询
            ObjectId sid = new ObjectId(operatorId);
            SuperUser superUser = superUserService.findBySid(sid);
            return superUser != null && superUser.getRole() == 0;
        } catch (IllegalArgumentException e) {
            // operatorId格式错误（非ObjectId字符串），直接返回false
            log.warn("操作者ID[{}]格式错误，非有效的ObjectId", operatorId);
            return false;
        }
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
        String userIdStr = null;
        String uid = null;

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

        // 3. 生成用户唯一编码
        AccountPool accountPool = new AccountPool();
        accountPool.setType(ConstValueEnum.USERTYPE);
        accountPool.setStatus(ConstValueEnum.ACCOUNT_USED);
        accountPoolDao.save(accountPool);

        // 4. 创建用户实体
        String encryptedPwd = bCryptPasswordEncoder.encode(rVo.getPassword());
        User user = new User();
        user.setUserId(new ObjectId()); // 生成userId
        user.setUid(user.getUserId().toString()); // 用userId的字符串作为uid
        user.setUsername(rVo.getUsername());
        user.setPassword(encryptedPwd);
        user.setCode(String.valueOf(accountPool.getCode() + ConstValueEnum.INITIAL_NUMBER));
        user.setPhoto(rVo.getAvatar());
        user.setNickname(rVo.getNickname() != null ? rVo.getNickname() : ChatServerUtil.randomNickname());
        user.setSignUpTime(String.valueOf(Instant.now()));
        user.setStatus(0);
        user.setRole(UserRoleEnum.BUYER.getCode());

        // 保存用户（自动生成userId并同步uid）
        User savedUser = userDao.save(user);

        // 校验uid赋值
        if (savedUser.getUid() == null || !savedUser.getUid().equals(savedUser.getUserId().toString())) {
            log.warn("普通用户[{}]的uid未正确同步，手动修正", savedUser.getUsername());
            savedUser.setUid(savedUser.getUserId().toString());
            userDao.save(savedUser);
        }

        // 处理好友关系
        handleAutoFriendRelation(savedUser);

        // 注册成功，补充返回userId和uid
        userCode = savedUser.getCode();
        userIdStr = savedUser.getUserId().toString();
        uid = savedUser.getUid();
        code = ResultEnum.REGISTER_SUCCESS.getCode();
        msg = ResultEnum.REGISTER_SUCCESS.getMessage();
        map.put("code", code);
        map.put("msg", msg);
        map.put("userCode", userCode);
        map.put("userId", userIdStr);
        map.put("uid", uid);

        return map;
    }

    /**
     * 处理用户注册后的自动好友关系
     */
    private void handleAutoFriendRelation(User newUser) {
        // 强化校验：确保newUser的userId和uid有效
        if (newUser == null || newUser.getUserId() == null || newUser.getUid() == null) {
            log.error("新用户对象或关键字段（userId/uid）为空，无法处理自动好友关系");
            return;
        }

        if (UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(newUser.getRole())) {
            // 客服注册：关联所有现有用户
            List<User> allUsers = mongoTemplate.findAll(User.class);
            List<GoodFriend> friendRelations = new ArrayList<>();
            for (User existUser : allUsers) {
                if (existUser == null || existUser.getUserId() == null || existUser.getUid() == null) {
                    log.warn("发现无效用户数据（userId或uid为空），跳过处理");
                    continue;
                }

                if (!existUser.getUid().equals(newUser.getUid())) { // 使用uid比较，避免ObjectId直接比较问题
                    // 新客服 -> 现有用户
                    GoodFriend friend1 = new GoodFriend();
                    friend1.setUserM(newUser.getUserId()); // 存储ObjectId
                    friend1.setUserY(existUser.getUserId());

                    // 现有用户 -> 新客服
                    GoodFriend friend2 = new GoodFriend();
                    friend2.setUserM(existUser.getUserId());
                    friend2.setUserY(newUser.getUserId());

                    friendRelations.add(friend1);
                    friendRelations.add(friend2);

                    // 添加到好友分组（使用uid）
                    addToFriendGroup(existUser, newUser.getUid());
                    addToFriendGroup(newUser, existUser.getUid());
                }
            }
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
                if (cs == null || cs.getUserId() == null || cs.getUid() == null) {
                    log.warn("发现无效客服数据（userId或uid为空），跳过处理");
                    continue;
                }

                // 买家 -> 客服
                GoodFriend friend1 = new GoodFriend();
                friend1.setUserM(newUser.getUserId());
                friend1.setUserY(cs.getUserId());

                // 客服 -> 买家
                GoodFriend friend2 = new GoodFriend();
                friend2.setUserM(cs.getUserId());
                friend2.setUserY(newUser.getUserId());

                friendRelations.add(friend1);
                friendRelations.add(friend2);

                // 添加到好友分组（使用uid）
                addToFriendGroup(newUser, cs.getUid());
                addToFriendGroup(cs, newUser.getUid());
            }
            if (!friendRelations.isEmpty()) {
                goodFriendService.batchAddFriends(friendRelations);
            }
        }
    }

    /**
     * 将好友添加到用户的"我的好友"分组（使用uid存储）
     */
    private void addToFriendGroup(User user, String friendUid) {
        // 确保friendFenZuMap初始化
        Map<String, ArrayList<String>> friendFenZuMap = user.getFriendFenZu();
        if (friendFenZuMap == null) {
            friendFenZuMap = new HashMap<>();
            user.setFriendFenZu(friendFenZuMap);
        }
        // 添加好友uid到分组
        friendFenZuMap.computeIfAbsent("我的好友", k -> new ArrayList<>())
                .add(friendUid);
        mongoTemplate.save(user);
    }

    /**
     * 根据用户ID（uid字符串）查询用户信息
     */
    @Override
    public User getUserInfo(String userId) {
        if (userId == null || !ObjectId.isValid(userId)) {
            throw new BusinessException("用户ID格式错误，需为有效的ObjectId字符串（24位十六进制）");
        }

        User user = userDao.findById(new ObjectId(userId)).orElse(null);
        if (user != null) {
            // 1. 获取当前登录用户ID
            String currentUserId = getCurrentUserId();
            // 2. 判断当前用户是否为超级管理员
            boolean isSuperAdmin = currentUserId != null && isSuperAdmin(currentUserId);

            // 3. 非超级管理员：对所有敏感字段（手机号、身份证号、邮箱）进行脱敏
            if (!isSuperAdmin) {
                // 手机号脱敏（调用通用方法，字段名匹配"phone"）
                user.setPhone(SensitiveInfoDesensitizerUtil.desensitize("phone", user.getPhone()));
                // 身份证号脱敏（调用通用方法，字段名匹配"idcard"）
                user.setIDcard(SensitiveInfoDesensitizerUtil.desensitize("idcard", user.getIDcard()));
                // 邮箱脱敏（调用通用方法，字段名匹配"email"）
                user.setEmail(SensitiveInfoDesensitizerUtil.desensitize("email", user.getEmail()));
            }
        }
        return user;
    }


    /**
     * 修改好友备注（使用uid作为好友标识）
     */
    @Override
    public void modifyBeiZhu(ModifyFriendBeiZhuRequestVo requestVo) {
        User userInfo = getUserInfo(requestVo.getUserId());
        if (userInfo == null) {
            return;
        }

        Map<String, String> friendBeiZhuMap = userInfo.getFriendBeiZhu();
        if (friendBeiZhuMap == null) {
            friendBeiZhuMap = new HashMap<>();
            userInfo.setFriendBeiZhu(friendBeiZhuMap);
        }
        // 用好友uid作为key存储备注
        friendBeiZhuMap.put(requestVo.getFriendId(), requestVo.getFriendBeiZhuName());

        Update update = new Update().set("friendBeiZhu", friendBeiZhuMap);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
        mongoTemplate.findAndModify(query, update, User.class);
    }


    @Override
    public void addNewFenZu(NewFenZuRequestVo requestVo) {
        User userInfo = userDao.findById(new ObjectId(requestVo.getUserId())).orElse(null);
        if (userInfo == null) {
            return;
        }

        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        if (friendFenZuMap == null) {
            friendFenZuMap = new HashMap<>();
            userInfo.setFriendFenZu(friendFenZuMap);
        }
        if (!friendFenZuMap.containsKey(requestVo.getFenZuName())) {
            friendFenZuMap.put(requestVo.getFenZuName(), new ArrayList<>());
            Update update = new Update().set("friendFenZu", friendFenZuMap);
            Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
            mongoTemplate.findAndModify(query, update, User.class);
        }
    }

    @Override
    public void modifyFriendFenZu(ModifyFriendFenZuRequestVo requestVo) {
        User userInfo = getUserInfo(requestVo.getUserId());
        if (userInfo == null) {
            return;
        }

        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        if (friendFenZuMap == null) {
            return;
        }
        boolean isRemoved = false;

        // 从原分组移除好友友uid
        for (Map.Entry<String, ArrayList<String>> entry : friendFenZuMap.entrySet()) {
            Iterator<String> iterator = entry.getValue().iterator();
            while (iterator.hasNext()) {
                if (iterator.next().equals(requestVo.getFriendId())) {
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

        // 添加到新分组
        friendFenZuMap.computeIfAbsent(requestVo.getNewFenZuName(), k -> new ArrayList<>())
                .add(requestVo.getFriendId());

        Update update = new Update().set("friendFenZu", friendFenZuMap);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
        mongoTemplate.findAndModify(query, update, User.class);
    }

    /**
     * 删除分组：必须确保分组内无好友才能删除，否则抛出异常
     */
    @Override
    public void deleteFenZu(DelFenZuRequestVo requestVo) {
        // 1. 校验核心参数（避免空指针和无效操作）
        if (requestVo == null) {
            throw new BusinessException("请求参数不能为空");
        }
        String userId = requestVo.getUserId();
        String fenZuName = requestVo.getFenZuName();
        if (userId == null || userId.trim().isEmpty()) {
            throw new BusinessException("用户ID不能为空");
        }
        if (fenZuName == null || fenZuName.trim().isEmpty()) {
            throw new BusinessException("分组名称不能为空");
        }
        fenZuName = fenZuName.trim(); // 去除首尾空格，避免名称不一致

        // 2. 查询用户信息（用户不存在直接抛异常，而非return，确保操作可追溯）
        User userInfo = getUserInfo(userId);
        if (userInfo == null) {
            throw new BusinessException("用户不存在：userId=" + userId);
        }

        // 3. 获取用户的分组映射（处理map为null的情况）
        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        if (friendFenZuMap == null || friendFenZuMap.isEmpty()) {
            throw new BusinessException("用户暂无任何分组，无需删除");
        }

        // 4. 校验分组是否存在（避免删除不存在的分组）
        if (!friendFenZuMap.containsKey(fenZuName)) {
            throw new BusinessException("分组不存在：分组名称=" + fenZuName);
        }

        // 5. 校验分组内是否有好友（核心逻辑：非空则不允许删除）
        ArrayList<String> friendListInGroup = friendFenZuMap.get(fenZuName);
        if (friendListInGroup != null && !friendListInGroup.isEmpty()) {
            throw new BusinessException("分组内存在" + friendListInGroup.size() + "个好友，无法删除，请先移除分组内所有好友");
        }

        // 6. 执行删除分组操作
        friendFenZuMap.remove(fenZuName);
        log.debug("用户{}删除空分组成功：分组名称={}", userId, fenZuName);

        // 7. 更新数据库（使用upsert确保更新生效，返回更新结果校验）
        Update update = new Update().set("friendFenZu", friendFenZuMap);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(userId)));
        User updatedUser = mongoTemplate.findAndModify(query, update, User.class);
        if (updatedUser == null) {
            throw new BusinessException("删除分组失败：数据库更新操作未生效");
        }
    }


    @Override
    public void editFenZu(EditFenZuRequestVo requestVo) {
        User userInfo = getUserInfo(requestVo.getUserId());
        if (userInfo == null) {
            return;
        }

        Map<String, ArrayList<String>> friendFenZuMap = userInfo.getFriendFenZu();
        if (friendFenZuMap == null) {
            return;
        }
        ArrayList<String> oldFenZuUsers = friendFenZuMap.get(requestVo.getOldFenZu());
        if (oldFenZuUsers != null) {
            friendFenZuMap.remove(requestVo.getOldFenZu());
            friendFenZuMap.put(requestVo.getNewFenZu(), oldFenZuUsers);
            Update update = new Update().set("friendFenZu", friendFenZuMap);
            Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
            mongoTemplate.findAndModify(query, update, User.class);
        }
    }

    @Override
    public List<User> searchUser(SearchRequestVo requestVo, String uid) {
        Criteria criteria = Criteria.where(requestVo.getType())
                .regex(Pattern.compile("^.*" + requestVo.getSearchContent() + ".*$", Pattern.CASE_INSENSITIVE))
                .and("uid").ne(uid);

        Query query = Query.query(criteria)
                .with(Sort.by(Sort.Direction.DESC, "_id"))
                .skip((long) requestVo.getPageIndex() * requestVo.getPageSize())
                .limit(requestVo.getPageSize());

        List<User> userList = mongoTemplate.find(query, User.class);

        // 1. 获取当前登录用户ID
        String currentUserId = getCurrentUserId();
        // 2. 直接使用isSuperAdmin方法判断
        boolean isSuperAdmin = currentUserId != null && isSuperAdmin(currentUserId);

        // 非超级管理员：用通用脱敏方法处理手机号、身份证号、邮箱
        if (!isSuperAdmin) {
            for (User user : userList) {
                user.setPhone(SensitiveInfoDesensitizerUtil.desensitize("phone", user.getPhone()));
                user.setIDcard(SensitiveInfoDesensitizerUtil.desensitize("idcard", user.getIDcard()));
                user.setEmail(SensitiveInfoDesensitizerUtil.desensitize("email", user.getEmail()));
            }
        }

        return userList;
    }

    @Override
    public void updateOnlineTime(long onlineTime, String uid) {
        Update update = new Update().set("onlineTime", onlineTime);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(uid)));
        mongoTemplate.upsert(query, update, User.class);
    }

    @Override
    public Map<String, Object> updateUserInfo(UpdateUserInfoRequestVo requestVo) {
        Map<String, Object> map = new HashMap<>();
        Integer code = null;
        String msg = null;
        Update update = new Update();
        boolean hasError = false;

        try {
            // 1. 校验用户ID格式
            if (requestVo.getUserId() == null || !ObjectId.isValid(requestVo.getUserId())) {
                code = ResultEnum.INVALID_USER_ID.getCode();
                msg = "用户ID格式错误，需为有效的ObjectId字符串（24位十六进制）";
                hasError = true;
            }

            // 2. 权限校验：基础权限（本人或超级管理员）
            String currentUserId = getCurrentUserId();
            if (!hasError) {
                if (currentUserId == null ||
                        !currentUserId.equals(requestVo.getUserId()) &&
                                !isSuperAdmin(currentUserId)) {
                    code = ResultEnum.PERMISSION_DENIED.getCode();
                    msg = "无权限修改该用户信息";
                    hasError = true;
                }
            }

            // 3. 校验可更新的字段（包含phone和IDcard，增加敏感字段标识）
            List<String> allowUpdateFields = Arrays.asList("sex", "age", "email", "nickname", "photo", "sign", "phone", "IDcard");
            List<String> sensitiveFields = Arrays.asList("phone", "IDcard"); // 敏感字段单独标记
            if (!hasError && !allowUpdateFields.contains(requestVo.getField())) {
                code = ResultEnum.INVALID_FIELD.getCode();
                msg = "不支持修改字段：" + requestVo.getField();
                hasError = true;
            }

            // 4. 敏感字段额外权限校验（可选：仅超级管理员可修改他人敏感信息）
            if (!hasError && sensitiveFields.contains(requestVo.getField())) {
                // 非本人且非超级管理员，禁止修改敏感字段
                if (!currentUserId.equals(requestVo.getUserId()) && !isSuperAdmin(currentUserId)) {
                    code = ResultEnum.PERMISSION_DENIED.getCode();
                    msg = "仅本人或超级管理员可修改敏感信息";
                    hasError = true;
                }
            }

            // 5. 字段具体校验（新增phone和IDcard的校验逻辑）
            if (!hasError) {
                switch (requestVo.getField()) {
                    // ... 保留原有字段的校验逻辑 ...
                    case "sex":
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
                        String ageStr = requestVo.getValue().toString();
                        if (!ChatServerUtil.isNumeric(ageStr)) {
                            code = ResultEnum.ERROR_SETTING_AGE.getCode();
                            msg = ResultEnum.ERROR_SETTING_AGE.getMessage();
                            hasError = true;
                        } else {
                            int age = Integer.parseInt(ageStr);
                            if (age < 0 || age > 150) {
                                code = ResultEnum.ERROR_SETTING_AGE.getCode();
                                msg = "年龄必须在0-150之间";
                                hasError = true;
                            } else {
                                update.set(requestVo.getField(), age);
                            }
                        }
                        break;
                    case "email":
                        String email = (String) requestVo.getValue();
                        if (email == null || !ChatServerUtil.isEmail(email)) {
                            code = ResultEnum.ERROR_SETTING_EMAIL.getCode();
                            msg = ResultEnum.ERROR_SETTING_EMAIL.getMessage();
                            hasError = true;
                        } else {
                            update.set(requestVo.getField(), email);
                        }
                        break;
                    // 手机号校验
                    case "phone":
                        String phone = (String) requestVo.getValue();
                        if (phone == null || !ChatServerUtil.isPhone(phone)) {
                            code = ResultEnum.ERROR_SETTING_PHONE.getCode();
                            msg = "手机号格式错误（需为11位数字）";
                            hasError = true;
                        } else {
                            update.set(requestVo.getField(), phone);
                        }
                        break;
                    // 身份证号校验
                    case "IDcard":
                        String idCard = (String) requestVo.getValue();
                        if (idCard == null || !ChatServerUtil.isIDCard(idCard)) {
                            code = ResultEnum.ERROR_SETTING_IDCARD.getCode();
                            msg = "身份证号格式错误（需为18位，最后一位可为X）";
                            hasError = true;
                        } else {
                            update.set(requestVo.getField(), idCard);
                        }
                        break;
                    default:
                        if (requestVo.getValue() instanceof String) {
                            String valueStr = (String) requestVo.getValue();
                            if (valueStr.length() > 100) {
                                code = ResultEnum.FIELD_TOO_LONG.getCode();
                                msg = requestVo.getField() + "长度不能超过100个字符";
                                hasError = true;
                            } else {
                                update.set(requestVo.getField(), valueStr);
                            }
                        } else {
                            update.set(requestVo.getField(), requestVo.getValue());
                        }
                        break;
                }
            }

            // 6. 执行更新操作
            if (!hasError) {
                Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
                mongoTemplate.upsert(query, update, User.class);

                // 敏感字段日志脱敏记录
                String logValue = sensitiveFields.contains(requestVo.getField())
                        ? SensitiveInfoDesensitizerUtil.desensitize(requestVo.getField(), requestVo.getValue().toString())
                        : requestVo.getValue().toString();
                log.info("用户信息更新成功，用户ID：{}，更新字段：{}，值：{}",
                        requestVo.getUserId(),
                        requestVo.getField(),
                        logValue);

                code = ResultEnum.SUCCESS.getCode();
                msg = "用户信息更新成功";
            }
        } catch (Exception e) {
            log.error("更新用户信息失败，用户ID：{}，错误信息：{}",
                    requestVo.getUserId(),
                    e.getMessage(),
                    e);
            code = ResultEnum.SYSTEM_ERROR.getCode();
            msg = "系统异常，更新失败";
            hasError = true;
        }

        map.put("code", code);
        map.put("msg", msg);
        return map;
    }


    @Override
    public Map<String, Object> updateUserPwd(UpdateUserPwdRequestVo requestVo) {
        Map<String, Object> map = new HashMap<>();
        Integer code = null;
        String msg = null;

        if (!requestVo.getReNewPwd().equals(requestVo.getNewPwd())) {
            code = ResultEnum.INCORRECT_PASSWORD_TWICE.getCode();
            msg = ResultEnum.INCORRECT_PASSWORD_TWICE.getMessage();
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        User userInfo = getUserInfo(requestVo.getUserId());
        if (!bCryptPasswordEncoder.matches(requestVo.getOldPwd(), userInfo.getPassword())) {
            code = ResultEnum.OLD_PASSWORD_ERROR.getCode();
            msg = ResultEnum.OLD_PASSWORD_ERROR.getMessage();
            map.put("code", code);
            map.put("msg", msg);
            return map;
        }

        String encryptedNewPwd = bCryptPasswordEncoder.encode(requestVo.getNewPwd());
        Update update = new Update().set("password", encryptedNewPwd);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(requestVo.getUserId())));
        mongoTemplate.upsert(query, update, User.class);

        code = ResultEnum.SUCCESS.getCode();
        msg = "更新成功，请牢记你的新密码";
        map.put("code", code);
        map.put("msg", msg);
        return map;
    }

    @Override
    public boolean updateUserConfigure(UpdateUserConfigureRequestVo requestVo, String uid) {
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(uid)));
        Update update = new Update()
                .set("opacity", requestVo.getOpacity())
                .set("blur", requestVo.getBlur())
                .set("bgImg", requestVo.getBgImg())
                .set("customBgImgUrl", requestVo.getCustomBgImgUrl())
                .set("notifySound", requestVo.getNotifySound())
                .set("color", requestVo.getColor())
                .set("bgColor", requestVo.getBgColor());

        return mongoTemplate.upsert(query, update, User.class).getModifiedCount() > 0;
    }

    @Override
    public List<User> getUserList() {
        List<User> userList = userDao.findAll();

        // 1. 获取当前登录用户ID
        String currentUserId = getCurrentUserId();
        // 2. 直接使用isSuperAdmin方法判断
        boolean isSuperAdmin = currentUserId != null && isSuperAdmin(currentUserId);

        // 非超级管理员：用通用脱敏方法处理手机号、身份证号、邮箱
        if (!isSuperAdmin) {
            for (User user : userList) {
                user.setPhone(SensitiveInfoDesensitizerUtil.desensitize("phone", user.getPhone()));
                user.setIDcard(SensitiveInfoDesensitizerUtil.desensitize("idcard", user.getIDcard()));
                user.setEmail(SensitiveInfoDesensitizerUtil.desensitize("email", user.getEmail()));
            }
        }

        return userList;
    }

    /**
     * 按注册时间获取用户（带权限控制的脱敏处理）
     */
    @Override
    public List<User> getUsersBySignUpTime(String lt, String rt) {
        Criteria criteria = Criteria.where("signUpTime")
                .gte(DateUtil.parseDate(lt, DateUtil.yyyy_MM))
                .lte(DateUtil.parseDate(rt, DateUtil.yyyy_MM));
        Query query = Query.query(criteria);
        List<User> userList = mongoTemplate.find(query, User.class);

        // 1. 获取当前登录用户ID
        String currentUserId = getCurrentUserId();
        // 2. 直接使用isSuperAdmin方法判断
        boolean isSuperAdmin = currentUserId != null && isSuperAdmin(currentUserId);

        // 非超级管理员：用通用脱敏方法处理手机号、身份证号、邮箱
        if (!isSuperAdmin) {
            for (User user : userList) {
                user.setPhone(SensitiveInfoDesensitizerUtil.desensitize("phone", user.getPhone()));
                user.setIDcard(SensitiveInfoDesensitizerUtil.desensitize("idcard", user.getIDcard()));
                user.setEmail(SensitiveInfoDesensitizerUtil.desensitize("email", user.getEmail()));
            }
        }

        return userList;
    }

    @Override
    public void changeUserStatus(String uid, Integer status) {
        // 验证用户ID有效性
        if (uid == null || !ObjectId.isValid(uid)) {
            throw new BusinessException("用户ID格式错误");
        }

        // 验证状态值有效性
        try {
            UserStatusEnum.valueOfCode(status);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(e.getMessage());
        }

        // 验证操作权限（仅超级管理员和管理员可操作）
        String currentUserId = getCurrentUserId();
        if (currentUserId == null || !isSuperAdminOrAdmin(currentUserId)) {
            throw new BusinessException("无权限修改用户状态，仅管理员可操作");
        }

        // 验证目标用户是否存在
        User user = getUserInfo(uid);
        if (user == null) {
            throw new BusinessException("目标用户不存在");
        }

        Update update = new Update().set("status", status);
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(uid)));
        mongoTemplate.findAndModify(query, update, User.class);
    }

    // 判断是否为超级管理员或普通管理员
    private boolean isSuperAdminOrAdmin(String operatorId) {
        try {
            ObjectId sid = new ObjectId(operatorId);
            SuperUser superUser = superUserService.findBySid(sid);
            // 假设0是超级管理员，1是普通管理员
            return superUser != null && (superUser.getRole() == 0 || superUser.getRole() == 1);
        } catch (IllegalArgumentException e) {
            log.warn("操作者ID[{}]格式错误，非有效的ObjectId", operatorId);
            return false;
        }
    }
}