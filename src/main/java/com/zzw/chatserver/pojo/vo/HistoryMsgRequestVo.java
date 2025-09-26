package com.zzw.chatserver.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class HistoryMsgRequestVo {

    private String type;//类型
    private String query; //搜索内容
    private String date = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

    @NotBlank(message = "群聊ID不能为空")
    private String roomId;

    @PositiveOrZero(message = "开始时间不能为负数")
    private Long startTime; // 可选：消息开始时间戳

    @PositiveOrZero(message = "结束时间不能为负数")
    private Long endTime; // 可选：消息结束时间戳

    @Positive(message = "页码必须为正整数")
    private Integer pageIndex = 1;

    @Positive(message = "每页条数必须为正整数")
    private Integer pageSize = 20;
}