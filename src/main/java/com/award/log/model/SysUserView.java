package com.award.log.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对外暴露的用户资料视图，不含密码哈希等鉴权字段。
 */
@Data
public class SysUserView {
    private Integer userId;
    private String username;
    private Integer role;
    @JsonProperty("wechat_userid")
    private String wechatUserid;
    private String email;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
