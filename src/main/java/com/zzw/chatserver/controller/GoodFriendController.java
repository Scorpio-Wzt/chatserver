//package com.zzw.chatserver.controller;
//
//import com.zzw.chatserver.common.R;
//import com.zzw.chatserver.common.ResultEnum;
//import com.zzw.chatserver.pojo.vo.DelGoodFriendRequestVo;
//import com.zzw.chatserver.pojo.vo.MyFriendListResultVo;
//import com.zzw.chatserver.pojo.vo.RecentConversationVo;
//import com.zzw.chatserver.pojo.vo.SingleRecentConversationResultVo;
//import com.zzw.chatserver.service.GoodFriendService;
//import io.swagger.annotations.Api;
//import org.springframework.security.core.context.SecurityContextHolder;
//import org.springframework.web.bind.annotation.*;
//
//import javax.annotation.Resource;
//import java.util.List;
//
//@RestController
//@RequestMapping("/goodFriend")
//@Api(tags = "好友相关接口")
//public class GoodFriendController {
//
//    @Resource
//    private GoodFriendService goodFriendService;
//
//    /**
//     * 查询我的好友列表
//     */
//    @GetMapping("/getMyFriendsList")
//    public R getMyFriendsList(String userId) {
//        List<MyFriendListResultVo> myFriendsList = goodFriendService.getMyFriendsList(userId);
//        // System.out.println("我的好友列表为：" + myFriendsList);
//        return R.ok().data("myFriendsList", myFriendsList);
//    }
//
//    /**
//     * 查询最近好友列表
//     */
//    @PostMapping("/recentConversationList")
//    public R getRecentConversationList(@RequestBody RecentConversationVo recentConversationVo) {
//        List<SingleRecentConversationResultVo> resultVoList = goodFriendService.getRecentConversation(recentConversationVo);
//        return R.ok().data("singleRecentConversationList", resultVoList);
//    }
//
//    /**
//     * 删除好友
//     */
//    @DeleteMapping("/deleteGoodFriend")
//    public R deleteGoodFriend(@RequestBody DelGoodFriendRequestVo requestVo) {
//        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal(); // 这个 principal 跟校验token时保存认证信息有关
//        if (!userId.equals(requestVo.getUserM())) return R.error().resultEnum(ResultEnum.ILLEGAL_OPERATION); //不是本人，非法操作
//        goodFriendService.deleteFriend(requestVo);
//        return R.ok().message("删除好友成功");
//    }
//}
package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.ResultEnum;
import com.zzw.chatserver.pojo.vo.DelGoodFriendRequestVo;
import com.zzw.chatserver.pojo.vo.MyFriendListResultVo;
import com.zzw.chatserver.pojo.vo.RecentConversationVo;
import com.zzw.chatserver.pojo.vo.SingleRecentConversationResultVo;
import com.zzw.chatserver.service.GoodFriendService;
import com.zzw.chatserver.service.UserService;
import com.zzw.chatserver.service.impl.GoodFriendServiceImpl;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/goodFriend")
@Api(tags = "好友相关接口")
@Slf4j // 引入日志
@Validated // 开启方法参数校验
public class GoodFriendController {

    @Resource
    private GoodFriendService goodFriendService;

    @Resource
    private UserService userService; // 注入用户服务，复用当前用户ID获取逻辑

    /**
     * 查询我的好友列表
     *
     * @param userId 当前用户ID（必须为有效的ObjectId）
     * @return 好友列表（空列表而非null，避免前端解析问题）
     */
    @GetMapping("/getMyFriendsList")
    @ApiOperation(value = "查询当前用户的好友列表", notes = "需传入当前登录用户的ID，仅能查询自己的好友")
    public R getMyFriendsList(
            @ApiParam(value = "当前用户ID（24位有效的ObjectId）", required = true)
            @RequestParam(required = true) String userId) {
        try {
            // 参数校验：非空+格式正确
            if (userId == null || !ObjectId.isValid(userId)) {
                return R.error().resultEnum(ResultEnum.INVALID_USER_ID);
            }

            // 权限校验：只能查询自己的好友列表
            String currentUserId = userService.getCurrentUserId(); // 复用UserService中安全的获取方式
            if (currentUserId == null || !currentUserId.equals(userId)) {
                return R.error().resultEnum(ResultEnum.PERMISSION_DENIED);
            }

            // 调用服务层，处理null为empty list
            List<MyFriendListResultVo> myFriendsList = goodFriendService.getMyFriendsList(userId);
            return R.ok().data("myFriendsList", myFriendsList != null ? myFriendsList : Collections.emptyList());
        } catch (BusinessException e) {
            // 捕获业务异常（如服务层自定义错误）
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            // 捕获未知异常，避免暴露堆栈信息
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }


    /**
     * 查询最近有过聊天的好友列表
     * （核心：仅返回既是好友，且近期有聊天记录的用户）
     *
     * @param recentConversationVo 包含用户ID和分页信息的请求参数
     * @return 最近有聊天的好友列表
     */
    @PostMapping("/recentChatFriends")
    @ApiOperation(value = "查询最近有过聊天的好友列表", notes = "需传入当前用户ID和分页参数，仅返回有聊天记录的好友")
    public R getRecentChatFriends(
            @ApiParam(value = "请求参数（包含userId、pageIndex、pageSize）", required = true)
            @RequestBody RecentConversationVo recentConversationVo) {
        try {
            // 参数校验：确保用户ID非空且格式正确
            if (recentConversationVo == null || recentConversationVo.getUserId() == null) {
                return R.error().message("用户ID不能为空");
            }
            String userId = recentConversationVo.getUserId();
            if (!ObjectId.isValid(userId)) {
                return R.error().resultEnum(ResultEnum.INVALID_USER_ID);
            }

            // 权限校验：只能查询自己的记录
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null || !currentUserId.equals(userId)) {
                return R.error().resultEnum(ResultEnum.PERMISSION_DENIED);
            }

            // 调用服务层：查询“既是好友+有最近聊天记录”的用户
            // 服务层需确保过滤掉非好友和无聊天记录的用户
            List<SingleRecentConversationResultVo> resultVoList = goodFriendService.getRecentChatFriends(recentConversationVo);

            return R.ok().data("recentChatFriendsList",
                    resultVoList != null ? resultVoList : Collections.emptyList());
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("查询最近聊天好友异常", e);
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }


    /**
     * 删除好友（仅能删除自己的好友）
     *
     * @param requestVo 包含操作人ID（userM）和被删除好友ID（userY）的请求参数
     * @return 操作结果
     */
    @DeleteMapping("/deleteGoodFriend")
    @ApiOperation(value = "删除好友", notes = "需传入操作人ID（userM）和被删除好友ID（userY），仅能删除自己的好友")
    public R deleteGoodFriend(
            @ApiParam(value = "删除好友请求参数（userM为当前用户ID，userY为目标好友ID）", required = true)
            @RequestBody DelGoodFriendRequestVo requestVo) {
        try {
            // 参数完整性校验
            if (requestVo == null || requestVo.getUserM() == null || requestVo.getUserY() == null) {
                return R.error().message("操作人ID和被删除好友ID不能为空");
            }

            // ID格式校验
            if (!ObjectId.isValid(requestVo.getUserM()) || !ObjectId.isValid(requestVo.getUserY())) {
                return R.error().resultEnum(ResultEnum.INVALID_USER_ID);
            }

            // 权限校验：必须是本人操作（使用UserService获取当前用户ID，避免强转风险）
            String currentUserId = userService.getCurrentUserId();
            if (currentUserId == null || !currentUserId.equals(requestVo.getUserM())) {
                return R.error().resultEnum(ResultEnum.ILLEGAL_OPERATION);
            }

            // 调用服务层删除好友
            goodFriendService.deleteFriend(requestVo);
            return R.ok().message("删除好友成功");
        } catch (BusinessException e) {
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            return R.error().resultEnum(ResultEnum.SYSTEM_ERROR);
        }
    }
}
