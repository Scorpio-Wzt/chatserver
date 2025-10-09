package com.zzw.chatserver.pojo;

import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.common.UserStatusEnum;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Data
@NoArgsConstructor
@Document(collection = "users")
public class User {
    @Id
    private ObjectId userId; // MongoDB自动生成的ObjectId，保持原始类型
    private String role = UserRoleEnum.BUYER.getCode();// 默认普通用户
    private String uid; // 存储userId的字符串形式（关键字段）
    @Indexed(unique = true)
    private String username; //字段唯一
    private String password;
    @Indexed(unique = true)
    private String code; //字段唯一
    private String photo = "/img/picture.png"; //默认头像
    private String signature = "";
    private String nickname = "";
    private String email = "";
    private String phone = "13888888888";
    private String IDcard = "27465820000101853X";
    private Province province = new Province();
    private City city = new City();
    private Town town = new Town();
    private Integer sex = 3; // 0 男 1 女 3 保密（默认值）
    private Double opacity = 0.75D; //聊天框透明度
    private Integer blur = 10; //模糊度
    private String bgImg = "abstract"; //背景图种类名
    private String customBgImgUrl = ""; //自定义背景图链接
    private String notifySound = "default"; //提示音
    private String color = "#000"; //字体颜色
    private String bgColor = "#fff"; //背景颜色
    // 注册时间（默认初始化当前时间，格式：yyyy-MM-dd HH:mm:ss，时区：Asia/Shanghai）
    private String signUpTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private String lastLoginTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    private Integer status =  UserStatusEnum.NORMAL.getCode(); // 默认：正常可用（关联枚举） 0：正常，1：冻结，2：注销
    private Integer age = 18;
    private Long onlineTime = 0L; //在线时长
    private BrowserSetting loginSetting; //登录设备信息
    // 默认添加“我的好友”分组
    private Map<String, ArrayList<String>> friendFenZu = new HashMap<String, ArrayList<String>>() {
        {
            put("我的好友", new ArrayList<>());
        }
    };
    //好友备注信息
    private Map<String, String> friendBeiZhu = new HashMap<>();

    // 当userId被设置时，自动同步uid
    public void setUserId(ObjectId userId) {
        this.userId = userId;
        if (userId != null) {
            this.uid = userId.toString(); // 同步uid为userId的字符串形式
        }
    }
}