package com.zzw.chatserver.controller;

import com.zzw.chatserver.common.R;
import com.zzw.chatserver.common.exception.BusinessException;
import com.zzw.chatserver.pojo.vo.GroupHistoryResultVo;
import com.zzw.chatserver.pojo.vo.GroupMessageResultVo;
import com.zzw.chatserver.pojo.vo.HistoryMsgRequestVo;
import com.zzw.chatserver.service.GroupMessageService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import java.util.Collections;
import java.util.List;

@RestController
@RequestMapping("/groupMessage")
@Api(tags = "群消息相关接口")
@Slf4j // 引入日志
@Validated // 开启方法参数校验
public class GroupMessageController {
    @Resource
    private GroupMessageService groupMessageService;

    /**
     * 获取最近的群消息（分页）
     *
     * @param roomId    群聊ID（必填）
     * @param pageIndex 页码（从1开始，默认1）
     * @param pageSize  每页条数（默认20，最大100）
     */
    @GetMapping("/getRecentGroupMessages")
    @ApiOperation(value = "分页获取群聊最近消息", notes = "按消息发送时间倒序返回，pageIndex从1开始，pageSize最大100")
    public R getRecentGroupMessages(
            @ApiParam(value = "群聊ID（如60d21b4667d0d8992e610c8）", required = true)
            @RequestParam @NotBlank(message = "群聊ID不能为空") String roomId,

            @ApiParam(value = "页码（默认1）", example = "1")
            @RequestParam(required = false, defaultValue = "1") @Positive(message = "页码必须为正整数") Integer pageIndex,

            @ApiParam(value = "每页条数（默认20，最大100）", example = "20")
            @RequestParam(required = false, defaultValue = "20") @Positive(message = "每页条数必须为正整数") Integer pageSize) {
        try {
            // 限制pageSize最大值，避免查询过多数据
            pageSize = Math.min(pageSize, 100);
            List<GroupMessageResultVo> recentGroupMessages = groupMessageService.getRecentGroupMessages(roomId, pageIndex, pageSize);
            // 确保返回空列表而非null，避免前端解析错误
            return R.ok().data("recentGroupMessages", recentGroupMessages != null ? recentGroupMessages : Collections.emptyList());
        } catch (BusinessException e) {
            log.warn("获取群最近消息失败：{}", e.getMessage());
            return R.error().code(e.getCode()).message(e.getMessage());
        } catch (Exception e) {
            log.error("获取群最近消息系统异常", e);
            return R.error().message("获取群消息失败，请稍后重试");
        }
    }

    /**
     * 获取群历史消息
     */
    @PostMapping("/historyMessages")
    public R getGroupHistoryMessages(@RequestBody HistoryMsgRequestVo historyMsgRequestVo) {
        GroupHistoryResultVo historyMessages = groupMessageService.getGroupHistoryMessages(historyMsgRequestVo);
        return R.ok().data("total", historyMessages.getCount()).data("msgList", historyMessages.getGroupMessages());
    }

    /**
     * 获取群最后一条消息
     */
    @GetMapping("/lastMessage")
    public R getGroupLastMessage(String roomId) {
        GroupMessageResultVo groupLastMessage = groupMessageService.getGroupLastMessage(roomId);
        return R.ok().data("groupLastMessage", groupLastMessage);
    }
}
