package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.Group;
import com.zzw.chatserver.pojo.vo.CreateGroupRequestVo;
import com.zzw.chatserver.pojo.vo.QuitGroupRequestVo;
import com.zzw.chatserver.pojo.vo.SearchGroupResponseVo;
import com.zzw.chatserver.pojo.vo.SearchGroupResultVo;
import com.zzw.chatserver.pojo.vo.SearchRequestVo;

import java.util.List;

/**
 * 群组服务接口
 * 定义群组的查询、创建、退出等核心操作（含群信息查询、群搜索、群创建、退群逻辑等）
 */
public interface GroupService {

    /**
     * 根据群组ID获取群信息
     * @param groupId 群组ID（字符串格式）
     * @return 群组实体（不存在返回null）
     */
    Group getGroupInfo(String groupId);

    /**
     * 搜索群组（支持按关键词、类型筛选，分页查询）
     * @param requestVo 搜索参数（含搜索类型、关键词、分页信息）
     * @param uid 当前登录用户ID（用于排除自己创建的群）
     * @return 搜索到的群组列表VO
     */
    List<SearchGroupResponseVo> searchGroup(SearchRequestVo requestVo, String uid);

    /**
     * 创建新群组
     * @param requestVo 群组创建参数（含群名称、描述、群主信息等）
     * @return 生成的群组编号（code）
     */
    String createGroup(CreateGroupRequestVo requestVo);

    /**
     * 获取所有群组列表
     * @return 所有群组的基础信息列表
     */
    List<SearchGroupResultVo> getAllGroup();

    /**
     * 退出群组
     * 群主退出：删除群所有消息、成员、群本身；普通成员退出：删除个人群消息、移除群成员、群人数减1
     * @param requestVo 退群参数（含群ID、用户ID、是否为群主标识）
     */
    void quitGroup(QuitGroupRequestVo requestVo);
}