package com.zzw.chatserver.pojo;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.zzw.chatserver.common.UserRoleEnum;
import com.zzw.chatserver.common.UserStatusEnum;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import javax.validation.constraints.Email;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;
import java.util.*;

/**
 * 用户实体类
 * 存储系统用户的基本信息、个性化设置、社交关系等数据
 */
@Data
@NoArgsConstructor
@Document(collection = "users")
public class User implements Serializable {

    private static final long serialVersionUID = 1L;

    @Id
    private ObjectId userId;

    /**
     * 用户角色（关联枚举确保类型安全）
     * 默认值：普通用户（BUYER）
     */
    private String role = UserRoleEnum.BUYER.getCode();

    /**
     * 用户唯一标识字符串（与userId一一对应，用于前端展示和传输）
     * 注：在保存用户时需同步设置为userId的字符串形式
     */
    private String uid;

    /**
     * 登录用户名（唯一索引，用于登录验证）
     * 约束：非空且长度在4-20之间
     */
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度必须在4-20之间")
    @Indexed(unique = true)
    private String username;

    /**
     * 加密后的密码（使用BCrypt加密存储，前端返回时忽略）
     * 约束：非空且长度不小于6
     */
    @JsonIgnore
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, message = "密码长度不能小于6位")
    private String password;

    /**
     * 用户唯一编码（系统生成，唯一索引）
     */
    @Indexed(unique = true)
    private String code;

    /**
     * 头像URL（默认使用系统默认头像）
     */
    private String photo = "/img/picture.png";

    /**
     * 个人签名
     */
    private String signature = "";

    /**
     * 用户昵称
     */
    private String nickname = "";

    /**
     * 电子邮箱（用于找回密码等功能）
     */
    @Email(message = "邮箱格式不正确")
    private String email = "";

    /**
     * 手机号码（用于登录和验证，唯一约束）
     */
    @Indexed(unique = true, sparse = true) // sparse=true允许null值但非null时唯一
    private String phone;

    /**
     * 省份信息（关联Province实体）
     */
    private Province province = new Province();

    /**
     * 城市信息（关联City实体）
     */
    private City city = new City();

    /**
     * 乡镇信息（关联Town实体）
     */
    private Town town = new Town();

    /**
     * 性别（0-男，1-女，3-保密）
     */
    private Integer sex = 3;

    /**
     * 聊天框透明度（0.0-1.0）
     */
    private Double opacity = 0.75D;

    /**
     * 背景模糊度（像素值）
     */
    private Integer blur = 10;

    /**
     * 系统预设背景图种类
     */
    private String bgImg = "abstract";

    /**
     * 自定义背景图URL
     */
    private String customBgImgUrl = "";

    /**
     * 消息提示音类型
     */
    private String notifySound = "default";

    /**
     * 字体颜色
     */
    private String color = "#000";

    /**
     * 背景颜色
     */
    private String bgColor = "#fff";

    /**
     * 注册时间（自动设置为创建时间）
     */
    private Date signUpTime = new Date();

    /**
     * 最后登录时间
     */
    private Date lastLoginTime = new Date();

    /**
     * 最后登出时间（初始值为注册时间）
     */
    private Date lastLogoutTime = new Date();

    /**
     * 账号状态（使用枚举值确保合法性）
     * 0-正常，1-冻结，2-注销
     */
    private Integer status = UserStatusEnum.NORMAL.getCode();

    /**
     * 年龄
     */
    private Integer age = 18;

    /**
     * 累计在线时长（毫秒）
     */
    private Long onlineTime = 0L;

    /**
     * 当前在线状态（true-在线，false-离线）
     */
    private boolean isOnline = false;

    /**
     * 用户等级（根据在线时长等因素计算）
     */
    private Integer level = 0;

    /**
     * 登录设备信息
     */
    private BrowserSetting loginSetting;

    /**
     * 好友分组（key：分组名称，value：好友uid列表）
     * 默认包含"我的好友"分组
     */
    private Map<String, ArrayList<String>> friendFenZu = new HashMap<String, ArrayList<String>>() {
        {
            put("我的好友", new ArrayList<>());
        }
    };

    /**
     * 好友备注（key：好友uid，value：备注名称）
     */
    private Map<String, String> friendBeiZhu = new HashMap<>();

    /**
     * 用户标签（如兴趣爱好等）
     */
    private List<String> tags = new ArrayList<>();

    /**
     * 重写userId设置方法，同步更新uid
     * @param userId MongoDB自动生成的ObjectId
     */
    public void setUserId(ObjectId userId) {
        this.userId = userId;
        // 当userId不为null时，同步设置uid为其字符串表示
        if (userId != null) {
            this.uid = userId.toString();
        }
    }

    /**
     * 获取用户完整地址（省+市+镇）
     * @return 拼接后的完整地址字符串
     */
    public String getFullAddress() {
        StringBuilder address = new StringBuilder();
        if (province != null && province.getName() != null) {
            address.append(province.getName());
        }
        if (city != null && city.getName() != null) {
            address.append(city.getName());
        }
        if (town != null && town.getName() != null) {
            address.append(town.getName());
        }
        return address.toString();
    }

    /**
     * 判断用户是否为管理员
     * @return true-是管理员，false-不是
     */
    public boolean isAdmin() {
        return UserRoleEnum.ADMIN.getCode().equals(this.role);
    }

    /**
     * 判断用户是否为客服
     * @return true-是客服，false-不是
     */
    public boolean isCustomerService() {
        return UserRoleEnum.CUSTOMER_SERVICE.getCode().equals(this.role);
    }
}