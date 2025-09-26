package com.zzw.chatserver.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;


@Data
@AllArgsConstructor
@NoArgsConstructor
public class FeedBackResultVo {
    private String id; //主键
    private String userId; //反馈人id
    private String username; //反馈人账号名
    private String feedBackContent; //反馈内容
    private String createTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
}
