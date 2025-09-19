package com.zzw.chatserver.service;

import com.zzw.chatserver.pojo.ValidateMessage;
import com.zzw.chatserver.pojo.vo.ValidateMessageResponseVo;

import java.util.List;

/**
 * 验证消息服务接口
 * 定义验证消息（好友申请、加群申请）的核心操作：状态更新、查询、新增等
 */
public interface ValidateMessageService {

    /**
     * 更新好友申请验证消息的状态（如：未处理→已同意/已拒绝）
     * @param validateMessageId 验证消息ID（字符串格式）
     * @param status 目标状态（0=未处理，1=已同意，2=已拒绝等，需符合业务定义）
     */
    void changeFriendValidateNewsStatus(String validateMessageId, Integer status);

    /**
     * 更新加群申请验证消息的状态（如：未处理→已同意/已拒绝）
     * @param validateMessageId 验证消息ID（字符串格式）
     * @param status 目标状态（0=未处理，1=已同意，2=已拒绝等，需符合业务定义）
     */
    void changeGroupValidateNewsStatus(String validateMessageId, Integer status);

    /**
     * 查询当前用户收到的所有验证消息列表（含好友申请、加群申请）
     * @param userId 当前用户ID（字符串格式）
     * @return 验证消息列表VO（含消息详情、关联群组信息等）
     */
    List<ValidateMessageResponseVo> getMyValidateMessageList(String userId);

    /**
     * 根据房间ID、状态、类型查询验证消息
     * @param roomId 房间ID（好友申请=单聊房间ID，加群申请=群聊房间ID）
     * @param status 消息状态（0=未处理等）
     * @param validateType 消息类型（0=好友申请，1=加群申请等）
     * @return 符合条件的验证消息（无结果返回null）
     */
    ValidateMessage findValidateMessage(String roomId, Integer status, Integer validateType);

    /**
     * 新增验证消息（先校验是否存在未处理的同类型消息，避免重复申请）
     * @param validateMessage 验证消息实体（含发送者、接收者、消息类型等）
     * @return 新增成功返回消息实体，存在未处理消息返回null
     */
    ValidateMessage addValidateMessage(ValidateMessage validateMessage);
}