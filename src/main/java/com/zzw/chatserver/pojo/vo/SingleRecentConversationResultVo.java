package com.zzw.chatserver.pojo.vo;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class SingleRecentConversationResultVo {

    private String createDate;//用字符串显示

    @ApiModelProperty("会话标识（通常用对方用户ID）")
    private String id;

    @ApiModelProperty("最后一条消息内容")
    private String lastMsgContent;

    @ApiModelProperty("最后一条消息时间（格式化后，如yyyy-MM-dd HH:mm:ss）")
    private String lastMsgTime;

    @ApiModelProperty("对方是否为好友（true=好友，false=陌生人/临时会话）")
    private Boolean isFriend;

    @ApiModelProperty("当前用户信息")
    private SimpleUser userM;

    @ApiModelProperty("对方用户信息")
    private SimpleUser userY;
}
