package com.zzw.chatserver.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 卡片中的操作选项实体类
 */
@Data
@AllArgsConstructor
@NoArgsConstructor // 确保反序列化正常
public class CardOption {
    // 显示文本（如"申请退款"）
    private String text;
    // 后端接口路径（如"/order/refund"）
    private String url;
    // 请求方式（GET/POST）
    private String method;
}