package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;
import com.zzw.chatserver.pojo.vo.IsReadMessageRequestVo;
import com.zzw.chatserver.pojo.vo.SingleHistoryResultVo;
import com.zzw.chatserver.pojo.vo.SingleMessageResultVo;
import com.zzw.chatserver.service.SingleMessageService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;
import java.util.List;

@RestController
@RequestMapping("/singleMessage")
@Api(tags = "单聊相关接口")
@Slf4j // 引入日志
@Validated // 开启方法参数校验
public class SingleMessageController {
    @Resource
    private SingleMessageService singleMessageService;

    /**
     * 获取好友之间的最后一条聊天记录
     */
    @GetMapping("/getLastMessage")
    @ApiOperation(value = "获取单聊最后一条消息", notes = "根据房间ID（roomId）查询双方最后的聊天记录")
    public R getLastMessage(
            @ApiParam(value = "单聊房间ID（如user1_user2）", required = true, example = "60d21b4667d0d8992e610c8_60d21b4667d0d8992e610c9")
            @RequestParam @NotBlank(message = "房间ID不能为空") String roomId) {
        try {
            SingleMessageResultVo lastMessage = singleMessageService.getLastMessage(roomId);
            log.info("查询单聊房间[{}]的最后一条消息成功", roomId);
            return R.ok().data("singleLastMessage", lastMessage);
        } catch (BusinessException e) {
            log.warn("查询单聊最后一条消息失败：{}（房间ID：{}）", e.getMessage(), roomId);
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("查询单聊最后一条消息系统异常（房间ID：{}）", roomId, e);
            return R.error().message("获取最后一条消息失败，请稍后重试");
        }
    }

    /**
     * 获取最近的单聊消息
     */
    @GetMapping("/getRecentSingleMessages")
    public R getRecentSingleMessages(String roomId, Integer pageIndex, Integer pageSize) {
        List<SingleMessageResultVo> recentMessage = singleMessageService.getRecentMessage(roomId, pageIndex, pageSize);
        return R.ok().data("recentMessage", recentMessage);
    }

    /**
     * 当用户在切换会话阅读消息后，标记该消息已读
     */
    @PostMapping("/isRead")
    public R userIsReadMessage(@RequestBody IsReadMessageRequestVo ivo) {
        singleMessageService.userIsReadMessage(ivo);
        return R.ok();
    }

    /**
     * 获取单聊历史记录
     */
    @PostMapping("/historyMessage")
    public R getSingleHistoryMessages(@RequestBody HistoryMsgRequestVo historyMsgVo) {
        // System.out.println("查看历史消息的请求参数为：" + historyMsgVo);
        SingleHistoryResultVo singleHistoryMsg = singleMessageService.getSingleHistoryMsg(historyMsgVo);
        return R.ok().data("total", singleHistoryMsg.getTotal()).data("msgList", singleHistoryMsg.getMsgList());
    }
}
