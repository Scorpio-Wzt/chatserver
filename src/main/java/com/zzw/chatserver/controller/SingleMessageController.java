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
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import java.util.Collections;
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
     * 获取最近的单聊消息（分页）
     */
    @GetMapping("/getRecentSingleMessages")
    @ApiOperation(value = "分页获取最近单聊消息", notes = "按消息发送时间倒序返回，pageIndex从1开始，pageSize默认20")
    public R getRecentSingleMessages(
            @ApiParam(value = "单聊房间ID", required = true, example = "60d21b4667d0d8992e610c8_60d21b4667d0d8992e610c9")
            @RequestParam @NotBlank(message = "房间ID不能为空") String roomId,

            @ApiParam(value = "页码（默认1）", example = "1")
            @RequestParam(required = false, defaultValue = "1") @Positive(message = "页码必须为正整数") Integer pageIndex,

            @ApiParam(value = "每页条数（默认20，最大100）", example = "20")
            @RequestParam(required = false, defaultValue = "20") @Positive(message = "每页条数必须为正整数") Integer pageSize) {
        try {
            // 限制最大页大小，避免查询过多数据
            pageSize = Math.min(pageSize, 100);
            List<SingleMessageResultVo> recentMessage = singleMessageService.getRecentMessage(roomId, pageIndex, pageSize);
            log.info("查询单聊房间[{}]的最近消息成功（页码：{}，条数：{}）", roomId, pageIndex, pageSize);
            // 确保返回空列表而非null，避免前端解析错误
            return R.ok().data("recentMessage", recentMessage != null ? recentMessage : Collections.emptyList());
        } catch (BusinessException e) {
            log.warn("查询最近单聊消息失败：{}（房间ID：{}）", e.getMessage(), roomId);
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("查询最近单聊消息系统异常（房间ID：{}）", roomId, e);
            return R.error().message("获取最近消息失败，请稍后重试");
        }
    }

    /**
     * 标记消息为已读（用户切换会话后调用）
     */
    @PostMapping("/isRead")
    @ApiOperation(value = "标记单聊消息为已读", notes = "用户查看消息后调用，标记指定消息为已读状态")
    public R userIsReadMessage(
            @ApiParam(value = "标记已读请求参数（含房间ID、消息ID等）", required = true)
            @RequestBody @Valid IsReadMessageRequestVo ivo) { // 启用请求体参数校验
        try {
            singleMessageService.userIsReadMessage(ivo);
            log.info("标记单聊消息已读成功（房间ID：{}，消息ID：{}）", ivo.getRoomId(), ivo.getMessageId());
            return R.ok().message("消息已标记为已读");
        } catch (BusinessException e) {
            log.warn("标记消息已读失败：{}（房间ID：{}）", e.getMessage(), ivo.getRoomId());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("标记消息已读系统异常（房间ID：{}）", ivo.getRoomId(), e);
            return R.error().message("标记已读失败，请稍后重试");
        }
    }

    /**
     * 获取单聊历史消息（支持时间范围/分页）
     */
    @PostMapping("/historyMessage")
    @ApiOperation(value = "获取单聊历史消息", notes = "可按时间范围查询历史消息，返回总数和消息列表")
    public R getSingleHistoryMessages(
            @ApiParam(value = "历史消息查询参数（含房间ID、时间范围、分页）", required = true)
            @RequestBody @Valid HistoryMsgRequestVo historyMsgVo) { // 启用请求体参数校验
        try {
            SingleHistoryResultVo singleHistoryMsg = singleMessageService.getSingleHistoryMsg(historyMsgVo);
            log.info("查询单聊历史消息成功（房间ID：{}，查询范围：{}~{}）",
                    historyMsgVo.getRoomId(), historyMsgVo.getStartTime(), historyMsgVo.getEndTime());

            // 处理null场景，确保返回结构完整
            if (singleHistoryMsg == null) {
                return R.ok().data("total", 0).data("msgList", Collections.emptyList());
            }
            return R.ok()
                    .data("total", singleHistoryMsg.getTotal())
                    .data("msgList", singleHistoryMsg.getMsgList() != null ? singleHistoryMsg.getMsgList() : Collections.emptyList());
        } catch (BusinessException e) {
            log.warn("查询单聊历史消息失败：{}（房间ID：{}）", e.getMessage(), historyMsgVo.getRoomId());
            return R.error().message(e.getMessage());
        } catch (Exception e) {
            log.error("查询单聊历史消息系统异常（房间ID：{}）", historyMsgVo.getRoomId(), e);
            return R.error().message("获取历史消息失败，请稍后重试");
        }
    }
}
