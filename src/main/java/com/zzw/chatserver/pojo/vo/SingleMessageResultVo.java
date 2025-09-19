package com.zzw.chatserver.pojo.vo;

import com.zzw.chatserver.pojo.vo.CardOption;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SingleMessageResultVo {
    private String id;
    private String roomId;
    private String senderId;
    private String receiverId; // 新增：接收者ID字段（解决setReceiverId不存在问题）
    private String senderName;
    private String senderNickname;
    private String senderAvatar;
    private Date time = new Date();
    private String fileRawName; // 文件的原始名字
    private String message;
    private String messageType;
    private List<String> isReadUser = new ArrayList<>();

    // 卡片字段（与 SingleMessage 对齐）
    private String cardType;
    private List<CardOption> cardOptions;

}
