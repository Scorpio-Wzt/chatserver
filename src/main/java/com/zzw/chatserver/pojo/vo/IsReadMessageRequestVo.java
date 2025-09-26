package com.zzw.chatserver.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IsReadMessageRequestVo {

    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @NotBlank(message = "房间ID不能为空")
    private String roomId;

    @NotBlank(message = "消息ID不能为空")
    private String messageId;
}
