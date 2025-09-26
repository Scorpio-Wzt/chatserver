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
@Document("groups")
public class Group {
    @Id
    private ObjectId groupId; // 群标识
    private String gid; //对应 groupId
    private String title = "";// 群名称
    private String desc = ""; //群描述
    private String img = "/img/zwsj5.png";//群图片
    private String code; //群号，唯一标识
    private Integer userNum = 1; // 群成员数量，避免某些情况需要多次联表查找，如搜索；所以每次加入一人，数量加一
    private String createDate = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private String holderName; // 群主账号，在user实体中对应name字段
    private ObjectId holderUserId; //群人员的id，作为关联查询
}
