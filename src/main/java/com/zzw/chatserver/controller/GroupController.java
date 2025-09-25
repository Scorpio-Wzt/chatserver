//package com.zzw.chatserver.controller;
//
//import com.zzw.chatserver.common.R;
//import com.zzw.chatserver.common.ResultEnum;
//import com.zzw.chatserver.common.UserRoleEnum;
//import com.zzw.chatserver.pojo.Group;
//import com.zzw.chatserver.pojo.User;
//import com.zzw.chatserver.pojo.vo.*;
//import com.zzw.chatserver.service.GroupService;
//import com.zzw.chatserver.service.GroupUserService;
//import com.zzw.chatserver.service.UserService;
//import io.swagger.annotations.Api;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.web.bind.annotation.*;
//
//import javax.annotation.Resource;
//import java.util.List;
//
//@RestController
//@RequestMapping("/group")
//@Api(tags = "群聊相关接口")
//public class GroupController {
//    @Resource
//    private GroupUserService groupUserService;
//
//    @Resource
//    private GroupService groupService;
//
//    @Resource
//    private UserService userService; // 查询用户角色
//
//    /**
//     * 根据用户名获取我的群聊列表
//     */
//    @GetMapping("/getMyGroupList")
//    public R getMyGroupList(String username) {
//        List<MyGroupResultVo> myGroupList = groupUserService.getGroupUsersByUserName(username);
//        // System.out.println("我的群聊列表为：" + myGroupList);
//        return R.ok().data("myGroupList", myGroupList);
//    }
//
//    /**
//     * 获取最近的群聊
//     */
//    @PostMapping("/recentGroup")
//    public R getRecentGroup(@RequestBody RecentGroupVo recentGroupVo) {
//        // System.out.println("最近的群聊列表请求参数为：" + recentGroupVo);
//        List<MyGroupResultVo> recentGroups = groupUserService.getRecentGroup(recentGroupVo);
//        // System.out.println("最近的群聊列表为：" + recentGroups);
//        return R.ok().data("recentGroups", recentGroups);
//    }
//
//    /**
//     * 获取群聊详情
//     */
//    @GetMapping("/getGroupInfo")
//    public R getGroupInfo(String groupId) {
//        Group groupInfo = groupService.getGroupInfo(groupId);
//        // System.out.println("查询出的群消息为：" + groupInfo);
//        List<MyGroupResultVo> groupUsers = groupUserService.getGroupUsersByGroupId(groupId);
//        // System.out.println("群聊详情为：" + groupUsers);
//        return R.ok().data("groupInfo", groupInfo).data("users", groupUsers);
//    }
//
//    /**
//     * 在客户端搜索群聊
//     */
//    @PostMapping("/preFetchGroup")
//    public R searchGroup(@RequestBody SearchRequestVo requestVo) {
//        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); // 这个 principal 跟校验token时保存认证信息有关
//        List<SearchGroupResponseVo> groupResponseVos = groupService.searchGroup(requestVo, userId);
//        return R.ok().data("groupList", groupResponseVos);
//    }
//
//    /**
//     * 创建群聊（仅客服可操作）
//     */
//    @PostMapping("/createGroup")
//    public R createGroup(@RequestBody CreateGroupRequestVo requestVo) {
//        // 1. 获取当前登录用户ID
//        String currentUserId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
//
//        // 2. 查询当前用户信息，判断是否为客服
//        User currentUser = userService.getUserInfo(currentUserId); // 假设存在该方法
//
//        if (currentUser == null || !UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(currentUser.getRole())) {
//            return R.error().resultEnum(ResultEnum.PERMISSION_DENIED).message("仅客服账号可创建群聊");
//        }
//
//        // 3. 校验通过，继续创建群聊
//        requestVo.setHolderUserId(currentUserId);
//        requestVo.setHolderName(currentUser.getNickname());
//        String groupCode = groupService.createGroup(requestVo);
//        if (groupCode == null || groupCode.isEmpty()) {
//            return R.error().message("群聊创建失败");
//        }
//        return R.ok().data("groupCode", groupCode);
//    }
//
//    /**
//     * 获取所有群聊
//     */
//    @GetMapping("/all")
//    public R getAllGroup() {
//        List<SearchGroupResultVo> allGroup = groupService.getAllGroup();
//        return R.ok().data("allGroup", allGroup);
//    }
//
//    /**
//     * 退出群聊
//     */
//    @PostMapping("/quitGroup")
//    public R quitGroup(@RequestBody QuitGroupRequestVo requestVo) {
//        // System.out.println("退出群聊的请求参数为：" + requestVo);
//        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); // 这个 principal 跟校验token时保存认证信息有关
//        if (!userId.equals(requestVo.getUserId()))
//            return R.error().resultEnum(ResultEnum.ILLEGAL_OPERATION); //当前操作人不匹配，非法操作
//        groupService.quitGroup(requestVo);
//        return R.ok().message("操作成功");
//    }
//}
package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.exception.BusinessException;
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
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/group")
@Api(tags = "群聊相关接口")
@Slf4j // 引入日志
@Validated // 开启方法参数校验
public class GroupController {

    @Resource
    private GroupUserService groupUserService;

    @Resource
    private GroupService groupService;

    @Resource
    private UserService userService;


    /**
     * 根据当前登录用户获取我的群聊列表
     * （移除username参数，直接通过当前登录用户信息查询，避免越权）
     */
    @GetMapping("/getMyGroupList")
    @ApiOperation(value = "查询当前用户的群聊列表", notes = "无需传入参数，自动获取当前登录用户的群聊列表")
    public R getMyGroupList() {
        try {
            // 1. 获取当前登录用户信息（避免通过参数传入导致越权）
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null) {
                return R.error().resultEnum(ResultEnum.USER_NEED_AUTHORITIES);
            }
            User currentUser = userService.getUserInfo(currentUserId);
            if (currentUser == null || currentUser.getUsername() == null) {
                return R.error().resultEnum(ResultEnum.ACCOUNT_NOT_FOUND);
            }

            // 2. 查询当前用户的群聊列表
            List<MyGroupResultVo> myGroupList = groupUserService.getGroupUsersByUserName(currentUser.getUsername());
            return R.ok().data("myGroupList", myGroupList != null ? myGroupList : Collections.emptyList());
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("查询我的群聊列表异常", e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }


    /**
     * 获取当前用户的最近群聊
     */
    @PostMapping("/recentGroup")
    @ApiOperation(value = "查询当前用户的最近群聊", notes = "需传入当前用户ID和分页参数，仅能查询自己的最近群聊")
    public R getRecentGroup(
            @ApiParam(value = "最近群聊请求参数（包含userId、pageIndex、pageSize）", required = true)
            @RequestBody RecentGroupVo recentGroupVo) {
        try {
            // 1. 参数校验
            if (recentGroupVo == null || recentGroupVo.getUserId() == null) {
                return R.error().message("用户ID不能为空");
            }
            String userId = recentGroupVo.getUserId();
            if (!ObjectId.isValid(userId)) {
                return R.error().resultEnum(ResultEnum.INVALID_USER_ID);
            }

            // 2. 权限校验：只能查询自己的最近群聊
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null || !currentUserId.equals(userId)) {
                return R.error().resultEnum(ResultEnum.PERMISSION_DENIED);
            }

            // 3. 查询最近群聊
            List<MyGroupResultVo> recentGroups = groupUserService.getRecentGroup(recentGroupVo);
            return R.ok().data("recentGroups", recentGroups != null ? recentGroups : Collections.emptyList());
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("查询最近群聊异常", e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }


    /**
     * 获取群聊详情（包含群信息和群成员）
     */
    @GetMapping("/getGroupInfo")
    @ApiOperation(value = "查询群聊详情", notes = "需传入群ID，仅群成员可查看详情")
    public R getGroupInfo(
            @ApiParam(value = "群聊ID（24位有效的ObjectId）", required = true)
            @RequestParam(required = true) String groupId) {
        try {
            // 1. 参数校验
            if (groupId == null || !ObjectId.isValid(groupId)) {
                return R.error().resultEnum(ResultEnum.INVALID_GROUP_ID);
            }

            // 2. 权限校验：仅群成员可查看详情
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null) {
                return R.error().resultEnum(ResultEnum.USER_NEED_AUTHORITIES);
            }
            boolean isMember = groupUserService.isGroupMember(groupId, currentUserId);
            if (!isMember) {
                return R.error().resultEnum(ResultEnum.PERMISSION_DENIED).message("非群成员无权查看群详情");
            }

            // 3. 查询群信息和成员
            Group groupInfo = groupService.getGroupInfo(groupId);
            List<MyGroupResultVo> groupUsers = groupUserService.getGroupUsersByGroupId(groupId);
            return R.ok()
                    .data("groupInfo", groupInfo)
                    .data("users", groupUsers != null ? groupUsers : Collections.emptyList());
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("查询群聊详情异常，groupId={}", groupId, e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }


    /**
     * 搜索群聊（仅当前用户可操作）
     */
    @PostMapping("/preFetchGroup")
    @ApiOperation(value = "搜索群聊", notes = "根据关键词搜索群聊，需传入搜索参数")
    public R searchGroup(
            @ApiParam(value = "搜索群聊请求参数（包含搜索类型、内容等）", required = true)
            @RequestBody SearchRequestVo requestVo) {
        try {
            // 1. 参数校验
            if (requestVo == null || requestVo.getSearchContent() == null) {
                return R.error().message("搜索内容不能为空");
            }

            // 2. 获取当前登录用户ID（复用UserService方法，避免强转风险）
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null) {
                return R.error().resultEnum(ResultEnum.USER_NEED_AUTHORITIES);
            }

            // 3. 搜索群聊
            List<SearchGroupResponseVo> groupResponseVos = groupService.searchGroup(requestVo, currentUserId);
            return R.ok().data("groupList", groupResponseVos != null ? groupResponseVos : Collections.emptyList());
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("搜索群聊异常", e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }


    /**
     * 创建群聊（仅客服可操作）
     */
    @PostMapping("/createGroup")
    @ApiOperation(value = "创建群聊", notes = "仅客服可创建群聊，需传入群名称、成员等信息")
    public R createGroup(
            @ApiParam(value = "创建群聊请求参数（包含群名称、成员列表等）", required = true)
            @RequestBody CreateGroupRequestVo requestVo) {
        try {
            // 1. 参数校验
            if (requestVo == null || requestVo.getTitle() == null || requestVo.getTitle().trim().isEmpty()) {
                return R.error().message("群聊名称不能为空");
            }

            // 2. 获取当前登录用户并校验角色（仅客服可创建）
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null) {
                return R.error().resultEnum(ResultEnum.USER_NEED_AUTHORITIES);
            }
            User currentUser = userService.getUserInfo(currentUserId);
            if (currentUser == null) {
                return R.error().resultEnum(ResultEnum.ACCOUNT_NOT_FOUND);
            }
            if (!UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(currentUser.getRole())) {
                return R.error().resultEnum(ResultEnum.PERMISSION_DENIED).message("仅客服账号可创建群聊");
            }

            // 3. 补充创建人信息并创建群聊
            requestVo.setHolderUserId(currentUserId);
            requestVo.setHolderName(currentUser.getNickname());
            String groupCode = groupService.createGroup(requestVo);
            if (groupCode == null || groupCode.isEmpty()) {
                return R.error().message("群聊创建失败");
            }
            return R.ok().data("groupCode", groupCode);
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("创建群聊异常", e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }


    /**
     * 获取所有群聊（仅管理员/客服可操作，增强安全性）
     */
    @GetMapping("/all")
    @ApiOperation(value = "获取所有群聊", notes = "仅管理员或客服可查看所有群聊列表")
    public R getAllGroup() {
        try {
            // 1. 权限校验：仅管理员/客服可访问
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null) {
                return R.error().resultEnum(ResultEnum.USER_NEED_AUTHORITIES);
            }
            User currentUser = userService.getUserInfo(currentUserId);
            if (currentUser == null) {
                return R.error().resultEnum(ResultEnum.ACCOUNT_NOT_FOUND);
            }
            boolean isAdminOrCs = UserRoleEnum.ADMIN.getCode().equals(currentUser.getRole())
                    || UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(currentUser.getRole());
            if (!isAdminOrCs) {
                return R.error().resultEnum(ResultEnum.PERMISSION_DENIED).message("仅管理员或客服可查看所有群聊");
            }

            // 2. 查询所有群聊
            List<SearchGroupResultVo> allGroup = groupService.getAllGroup();
            return R.ok().data("allGroup", allGroup != null ? allGroup : Collections.emptyList());
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("获取所有群聊异常", e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }


    /**
     * 退出群聊（仅本人可操作）
     */
    @PostMapping("/quitGroup")
    @ApiOperation(value = "退出群聊", notes = "需传入用户ID和群ID，仅本人可退出自己所在的群聊")
    public R quitGroup(
            @ApiParam(value = "退出群聊请求参数（包含userId和groupId）", required = true)
            @RequestBody QuitGroupRequestVo requestVo) {
        try {
            // 1. 参数校验
            if (requestVo == null || requestVo.getUserId() == null || requestVo.getGroupId() == null) {
                return R.error().message("用户ID和群ID不能为空");
            }
            if (!ObjectId.isValid(requestVo.getUserId()) || !ObjectId.isValid(requestVo.getGroupId())) {
                return R.error().message("用户ID或群ID格式错误");
            }

            // 2. 权限校验：必须是本人操作
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null || !currentUserId.equals(requestVo.getUserId())) {
                return R.error().resultEnum(ResultEnum.ILLEGAL_OPERATION);
            }

            // 3. 校验是否为群成员（避免退出非加入的群聊）
            boolean isMember = groupUserService.isGroupMember(requestVo.getGroupId(), currentUserId);
            if (!isMember) {
                return R.error().message("您不是该群成员，无法退出");
            }

            // 4. 执行退出群聊
            groupService.quitGroup(requestVo);
            return R.ok().message("退出群聊成功");
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("退出群聊异常，groupId={}, userId={}", requestVo.getGroupId(), requestVo.getUserId(), e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }
}
