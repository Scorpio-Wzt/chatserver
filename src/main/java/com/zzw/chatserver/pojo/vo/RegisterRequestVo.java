package com.zzw.chatserver.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotBlank;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RegisterRequestVo {
    @NotBlank(message = "用户名不能为空")
    private String username;
    private String avatar;
    @NotBlank(message = "密码不能为空")
    private String password;
    private String rePassword;
    private String nickname;
    private String role;// 可选值"service"或"buyer" 客服、买家
}
