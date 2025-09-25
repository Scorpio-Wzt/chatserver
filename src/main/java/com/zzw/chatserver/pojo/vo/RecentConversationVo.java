package com.zzw.chatserver.pojo.vo;

import io.swagger.annotations.ApiModelProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecentConversationVo {

    @ApiModelProperty(value = "当前用户ID（必填）", example = "60d21b4667d0d8992e610c8")
    private String userId; // 仅需当前用户ID

    @ApiModelProperty(value = "最大返回数量（可选，0表示不返回，默认20条，最大100条）", example = "10")
    private Integer maxCount; // 仅保留此参数控制数量

//    @ApiModelProperty(value = "页码（从1开始）", example = "1")
//    private Integer pageIndex = 1; // 默认第一页
//
//    @ApiModelProperty(value = "每页条数", example = "20")
//    private Integer pageSize = 20; // 默认每页20条


}
