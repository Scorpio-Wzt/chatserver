package com.zzw.chatserver.pojo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Data
@NoArgsConstructor
@Document("groupusers")
public class GroupUser {
    @Id
    private ObjectId guid;
    private ObjectId groupId;
    private ObjectId userId; //成员id
    private String username; //成员账号名
    private String userNickname; // 用户名（冗余存储，便于查询）
    private Integer manager = 0; // 是否是管理员，默认0，不是，1是（可以设置一下这个需求）
    private Integer holder = 0;  // 是否是群主，默认0，不是，1是
    private String card = "";  // 群名片
    private String time = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
}
