package com.award.log.model;

import lombok.Data;

/**
 * 用户与角色关联模型（userId + roleId）。
 */
@Data
public class SysUserRole {
    private Long userId;
    private Long roleId;
}