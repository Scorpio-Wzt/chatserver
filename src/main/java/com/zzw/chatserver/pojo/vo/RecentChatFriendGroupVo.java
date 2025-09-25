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
public class RecentChatFriendGroupVo {
    private ObjectId otherSideId;
    private Long lastMsgTime;
    private String lastMsgContent;
    private List<User> otherUserInfo;
    private List<GoodFriend> friendRelation;
}
