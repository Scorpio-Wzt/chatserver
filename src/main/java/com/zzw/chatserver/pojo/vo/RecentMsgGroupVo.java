package com.zzw.chatserver.pojo.vo;

import com.zzw.chatserver.pojo.GoodFriend;
import com.zzw.chatserver.pojo.User;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RecentMsgGroupVo {
    private ObjectId otherSideId; // 对方用户ID
    private Long lastMsgTime; // 最后一条消息时间戳
    private String lastMsgContent; // 最后一条消息内容
    private List<User> otherUserInfo; // 对方用户信息（关联users表）
    private List<GoodFriend> friendRelation; // 好友关系（关联goodfriends表）
}