package com.zzw.chatserver.pojo.vo;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateGroupRequestVo {
    @ApiModelProperty(value = "群聊名称", required = true)
    @NotBlank(message = "群聊名称不能为空") // 非空校验
    @Size(min = 1, max = 30, message = "群聊名称长度必须在1-30个字符之间") // 长度限制
    private String title;// 群名称
    @ApiModelProperty(value = "群聊描述")
    @Size(max = 200, message = "群聊描述不能超过200个字符")
    private String desc; //群描述
    private String img;
    private String holderName;
    private String holderUserId;
    @ApiModelProperty(value = "初始群成员ID列表")
    private List<String> memberUserIds; // 初始成员ID列表
}
