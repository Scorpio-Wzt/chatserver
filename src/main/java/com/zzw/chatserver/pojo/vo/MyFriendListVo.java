package com.zzw.chatserver.pojo.vo;

import com.zzw.chatserver.pojo.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MyFriendListVo {
    private String id;
    private String createDate = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private String userM;
    private String userY;
    private List<User> uList;
    private List<User> uList1;
    private List<User> uList2;
}