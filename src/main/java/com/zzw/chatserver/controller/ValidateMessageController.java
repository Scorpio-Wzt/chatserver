package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.pojo.ValidateMessage;
import com.zzw.chatserver.pojo.vo.ValidateMessageResponseVo;
import com.zzw.chatserver.service.ValidateMessageService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;
import java.util.List;

@RestController
@RequestMapping("/validate")
@Api(tags = "验证消息相关接口")
@Slf4j // 日志支持
@Validated // 启用参数校验
public class ValidateMessageController {

    @Resource
    private ValidateMessageService validateMessageService;

    /**
     * 获取我的验证消息列表
     * 场景：用户查看自己收到的好友请求、入群申请等验证消息
     */
    @GetMapping("/getMyValidateMessageList")
    @ApiOperation(value = "获取个人验证消息列表", notes = "查询指定用户收到的所有验证消息（如好友请求、入群申请等）")
    public R getMyValidateMessageList(
            @ApiParam(value = "用户ID（接收验证消息的用户）", required = true, example = "60d21b4667d0d8992e610c8")
            @RequestParam @NotBlank(message = "用户ID不能为空") String userId) { // 强制校验非空
        try {
            log.info("开始查询用户的验证消息列表：userId={}", userId);
            List<ValidateMessageResponseVo> validateMessageList = validateMessageService.getMyValidateMessageList(userId);
            log.info("查询验证消息列表成功：userId={}, 消息数量={}", userId, validateMessageList.size());
            return R.ok().data("validateMessageList", validateMessageList);
        } catch (BusinessException e) {
            log.warn("查询验证消息列表业务异常：userId={}, 原因={}", userId, e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("查询验证消息列表系统异常：userId={}", userId, e);
            return R.error().message("获取验证消息列表失败，请稍后重试");
        }
    }

    /**
     * 查询某条验证消息
     * 场景：查看特定验证消息的详情（如查看某个好友请求的具体信息）
     */
    @GetMapping("/getValidateMessage")
    @ApiOperation(value = "查询单条验证消息", notes = "根据房间ID、状态和类型查询特定的验证消息")
    public R getValidateMessage(
            @ApiParam(value = "房间ID（验证消息所属的会话房间）", required = true, example = "room_123456")
            @RequestParam @NotBlank(message = "房间ID不能为空") String roomId,

            @ApiParam(value = "消息状态（0-未处理，1-已同意，2-已拒绝）", required = true, example = "0")
            @RequestParam Integer status,

            @ApiParam(value = "验证类型（1-好友请求，2-入群申请等）", required = true, example = "1")
            @RequestParam Integer validateType) {
        try {
            log.info("开始查询单条验证消息：roomId={}, status={}, validateType={}", roomId, status, validateType);
            ValidateMessage validateMessage = validateMessageService.findValidateMessage(roomId, status, validateType);

            if (validateMessage == null) {
                log.warn("未查询到验证消息：roomId={}, status={}, validateType={}", roomId, status, validateType);
                return R.ok().message("未查询到对应的验证消息").data("validateMessage", null);
            }

            log.info("查询单条验证消息成功：roomId={}, messageId={}", roomId, validateMessage.getId());
            return R.ok().data("validateMessage", validateMessage);
        } catch (BusinessException e) {
            log.warn("查询单条验证消息业务异常：roomId={}, 原因={}", roomId, e.getMessage());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("查询单条验证消息系统异常：roomId={}, status={}, validateType={}", roomId, status, validateType, e);
            return R.error().message("查询验证消息失败，请稍后重试");
        }
    }
}
