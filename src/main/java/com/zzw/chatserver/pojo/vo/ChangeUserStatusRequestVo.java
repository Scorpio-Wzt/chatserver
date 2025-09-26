package com.zzw.chatserver.pojo.vo;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

@Data
@ApiModel("修改用户状态请求参数")
public class ChangeUserStatusRequestVo {

    @ApiModelProperty(value = "用户ID", required = true, example = "60d21b4667d0d8992e610c8")
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @ApiModelProperty(value = "目标状态：0-正常，1-冻结，2-注销", required = true, example = "1")
    @NotNull(message = "状态值不能为空")
    private Integer status;
}
