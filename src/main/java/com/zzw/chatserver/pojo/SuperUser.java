package com.zzw.chatserver.pojo;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import javax.validation.constraints.NotBlank;

@Data
@NoArgsConstructor
@Document("superusers")
public class SuperUser {
    @ApiModelProperty(value = "管理员账号", required = true, example = "admin")
    @NotBlank(message = "管理员账号不能为空") // 校验账号非空
    private String account;

    @ApiModelProperty(value = "管理员密码", required = true, example = "123456")
    @NotBlank(message = "密码不能为空") // 校验密码非空
    private String password;
    @Id
    private ObjectId sid;
    private Integer role; //角色分类：超级管理员0，具有增删改查权限；普通管理员1，只有有查的权限
    private String nickname = "xiaoqiezi";
    private String avatar = "img/admin-avatar.gif";
}
