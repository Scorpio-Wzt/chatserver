package com.zzw.chatserver.pojo;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import javax.validation.constraints.NotBlank;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

@Data
@NoArgsConstructor
@Document("feedbacks")
public class FeedBack {
    @Id
    private ObjectId id; //主键

    @ApiModelProperty(value = "反馈人用户名", required = true)
    @NotBlank(message = "用户名不能为空")
    private String username; //反馈人用户名

    @ApiModelProperty(value = "反馈用户ID", required = true)
    @NotBlank(message = "用户ID不能为空")
    private String userId;

    @ApiModelProperty(value = "反馈内容", required = true)
    @NotBlank(message = "反馈内容不能为空")
    private String feedBackContent;

    @ApiModelProperty(value = "反馈时间")
    private String createTime = Instant.now()
            // 转换为本地时区（如北京时间：UTC+8）
            .atZone(ZoneId.of("Asia/Shanghai"))
            // 格式化输出为友好字符串
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
}
