package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.pojo.Group;
import com.zzw.chatserver.pojo.User;
import com.zzw.chatserver.pojo.vo.*;
import com.zzw.chatserver.service.GroupService;
import com.zzw.chatserver.service.GroupUserService;
import com.zzw.chatserver.service.UserService;
import io.swagger.annotations.Api;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/group")
@Api(tags = "群聊相关接口")
public class GroupController {
    @Resource
    private GroupUserService groupUserService;

    @Resource
    private GroupService groupService;

    @Resource
    private UserService userService; // 查询用户角色

    /**
     * 根据用户名获取我的群聊列表
     */
    @GetMapping("/getMyGroupList")
    public R getMyGroupList(String username) {
        List<MyGroupResultVo> myGroupList = groupUserService.getGroupUsersByUserName(username);
        // System.out.println("我的群聊列表为：" + myGroupList);
        return R.ok().data("myGroupList", myGroupList);
    }

    /**
     * 获取最近的群聊
     */
    @PostMapping("/recentGroup")
    public R getRecentGroup(@RequestBody RecentGroupVo recentGroupVo) {
        // System.out.println("最近的群聊列表请求参数为：" + recentGroupVo);
        List<MyGroupResultVo> recentGroups = groupUserService.getRecentGroup(recentGroupVo);
        // System.out.println("最近的群聊列表为：" + recentGroups);
        return R.ok().data("recentGroups", recentGroups);
    }

    /**
     * 获取群聊详情
     */
    @GetMapping("/getGroupInfo")
    public R getGroupInfo(String groupId) {
        Group groupInfo = groupService.getGroupInfo(groupId);
        // System.out.println("查询出的群消息为：" + groupInfo);
        List<MyGroupResultVo> groupUsers = groupUserService.getGroupUsersByGroupId(groupId);
        // System.out.println("群聊详情为：" + groupUsers);
        return R.ok().data("groupInfo", groupInfo).data("users", groupUsers);
    }

    /**
     * 在客户端搜索群聊
     */
    @PostMapping("/preFetchGroup")
    public R searchGroup(@RequestBody SearchRequestVo requestVo) {
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); // 这个 principal 跟校验token时保存认证信息有关
        List<SearchGroupResponseVo> groupResponseVos = groupService.searchGroup(requestVo, userId);
        return R.ok().data("groupList", groupResponseVos);
    }

    /**
     * 创建群聊（仅客服可操作）
     */
    @PostMapping("/createGroup")
    public R createGroup(@RequestBody CreateGroupRequestVo requestVo) {
        // 1. 获取当前登录用户ID
        String currentUserId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        // 2. 查询当前用户信息，判断是否为客服
        User currentUser = userService.getUserInfo(currentUserId); // 假设存在该方法

        if (currentUser == null || !UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(currentUser.getRole())) {
            return R.error().resultEnum(ResultEnum.PERMISSION_DENIED).message("仅客服账号可创建群聊");
        }

        // 3. 校验通过，继续创建群聊
        requestVo.setHolderUserId(currentUserId);
        requestVo.setHolderName(currentUser.getNickname());
        String groupCode = groupService.createGroup(requestVo);
        if (groupCode == null || groupCode.isEmpty()) {
            return R.error().message("群聊创建失败");
        }
        return R.ok().data("groupCode", groupCode);
    }

    /**
     * 获取所有群聊
     */
    @GetMapping("/all")
    public R getAllGroup() {
        List<SearchGroupResultVo> allGroup = groupService.getAllGroup();
        return R.ok().data("allGroup", allGroup);
    }

    /**
     * 退出群聊
     */
    @PostMapping("/quitGroup")
    public R quitGroup(@RequestBody QuitGroupRequestVo requestVo) {
        // System.out.println("退出群聊的请求参数为：" + requestVo);
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); // 这个 principal 跟校验token时保存认证信息有关
        if (!userId.equals(requestVo.getUserId()))
            return R.error().resultEnum(ResultEnum.ILLEGAL_OPERATION); //当前操作人不匹配，非法操作
        groupService.quitGroup(requestVo);
        return R.ok().message("操作成功");
    }
}
