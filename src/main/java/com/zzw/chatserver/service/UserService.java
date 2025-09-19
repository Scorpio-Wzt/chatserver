package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.*;

import java.util.List;
import java.util.Map;

/**
 * 用户服务接口
 * 定义用户全生命周期管理的核心操作：注册、信息查询、分组管理、信息修改、状态管理等
 */
public interface UserService {

    /**
     * 用户注册（含账号唯一性校验、密码加密、用户编码生成）
     * @param rVo 注册请求参数（含用户名、密码、头像等）
     * @return 注册结果Map（含状态码、提示信息、用户编码）
     */
    Map<String, Object> register(RegisterRequestVo rVo);

    /**
     * 新增好友分组
     * @param requestVo 新增分组参数（含用户ID、分组名称）
     */
    void addNewFenZu(NewFenZuRequestVo requestVo);

    /**
     * 根据用户ID查询用户完整信息
     * @param userId 用户ID（字符串格式）
     * @return 用户实体（不存在返回null）
     */
    User getUserInfo(String userId);

    /**
     * 修改好友备注
     * @param requestVo 修改备注参数（含用户ID、好友ID、备注名）
     */
    void modifyBeiZhu(ModifyFriendBeiZhuRequestVo requestVo);

    /**
     * 移动好友到其他分组
     * @param requestVo 移动分组参数（含用户ID、好友ID、原分组名、新分组名）
     */
    void modifyFriendFenZu(ModifyFriendFenZuRequestVo requestVo);

    /**
     * 删除好友分组
     * @param requestVo 删除分组参数（含用户ID、分组名称）
     */
    void deleteFenZu(DelFenZuRequestVo requestVo);

    /**
     * 编辑分组名称（重命名）
     * @param requestVo 编辑分组参数（含用户ID、旧分组名、新分组名）
     */
    void editFenZu(EditFenZuRequestVo requestVo);

    /**
     * 搜索用户（支持按关键词模糊筛选，排除当前用户）
     * @param requestVo 搜索参数（含搜索类型、关键词、分页信息）
     * @param uid 当前用户ID（用于排除自身）
     * @return 符合条件的用户列表
     */
    List<User> searchUser(SearchRequestVo requestVo, String uid);

    /**
     * 更新用户在线时长
     * @param onlineTime 在线时长（毫秒）
     * @param uid 用户ID（字符串格式）
     */
    void updateOnlineTime(long onlineTime, String uid);

    /**
     * 更新用户基本信息（性别、年龄、邮箱等，含参数合法性校验）
     * @param requestVo 更新信息参数（含用户ID、待更新字段、字段值）
     * @return 更新结果Map（含状态码、提示信息，无异常时无额外数据）
     */
    Map<String, Object> updateUserInfo(UpdateUserInfoRequestVo requestVo);

    /**
     * 修改用户密码（含旧密码校验、新密码一致性校验）
     * @param requestVo 修改密码参数（含用户ID、旧密码、新密码、确认新密码）
     * @return 修改结果Map（含状态码、提示信息）
     */
    Map<String, Object> updateUserPwd(UpdateUserPwdRequestVo requestVo);

    /**
     * 更新用户个性化配置（透明度、模糊度、背景图等）
     * @param requestVo 配置参数（含各类个性化设置项）
     * @param uid 用户ID（字符串格式）
     * @return true=更新成功，false=更新失败
     */
    boolean updateUserConfigure(UpdateUserConfigureRequestVo requestVo, String uid);

    /**
     * 获取所有用户列表
     * @return 系统中所有用户的实体列表
     */
    List<User> getUserList();

    /**
     * 根据注册时间范围查询用户
     * @param lt 时间范围左边界（格式：yyyy-MM）
     * @param rt 时间范围右边界（格式：yyyy-MM）
     * @return 符合时间范围的用户列表
     */
    List<User> getUsersBySignUpTime(String lt, String rt);

    /**
     * 修改用户状态（如禁用、启用）
     * @param uid 用户ID（字符串格式）
     * @param status 目标状态（0=正常、1=禁用等，需符合业务定义）
     */
    void changeUserStatus(String uid, Integer status);
}