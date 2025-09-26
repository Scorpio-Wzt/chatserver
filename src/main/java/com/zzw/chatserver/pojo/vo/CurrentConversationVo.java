package com.zzw.chatserver.pojo.vo;


import com.zzw.chatserver.pojo.Group;
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
public class CurrentConversationVo {
    private String id;
    private String beiZhu;
    private String card;
    private String conversationType;
    private String groupId;
    private Group groupInfo;
    private Integer holder;
    //@DateTimeFormat(pattern = DateUtil.yyyy_MM_dd_HH_mm_ss) //入参格式化
    private String createDate;
    private Boolean isGroup;
    private SingleMessageResultVo lastNews;
    private String lastNewsTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private Integer manager;
    private String roomId;
    private String time = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private String username;
    private SimpleUser userInfo;
    private String userId;
    private Long level;
    private String myAvatar;
    private String myId;
    private String myNickname;
    private String nickname;
    private Long onlineTime;
    private String photo;
    private String signature;
    private String webRtcType;
    private String type; //disagree，agree，busy
    private Object sdp; //必须弄成一个object对象
}
